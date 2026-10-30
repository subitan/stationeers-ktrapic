package ktrapic.intellij

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.Topic
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** A program to compile: the text of the file at [path], which says itself how it compiles. */
data class CompileRequest(val path: String, val text: String)

/** [request]'s compile, answered with [response]. */
data class Compiled(val request: CompileRequest, val response: CompileResponse)

/** Told of each compile the project's [KtrapicCompileService] finishes. */
fun interface CompileListener {
  fun compiled(compiled: Compiled)

  companion object {
    @JvmField
    val TOPIC: Topic<CompileListener> =
        Topic.create("KTrapIC compiles", CompileListener::class.java)
  }
}

/**
 * Compiles a project's programs with the compile daemon, one at a time, and tells
 * [CompileListener]s of each result. The editor's annotator and the IC10 tool window both ask it,
 * so a text compiled for one is not compiled again for the other: a request for the text a file was
 * last asked to compile with gets that compile, finished or not. A queued compile that a newer
 * request for the same file has replaced is skipped. A compile the daemon gave no answer for (see
 * [CompileResponse.Failure.answered]) isn't shared: asking again compiles again.
 *
 * The daemon runs while the project is open, with the Java the settings name, from a copy of the
 * jar they name (see [DaemonJarCopies]), and is replaced when they or the jar change. When a daemon
 * starts, older copies are cleaned up (see [DaemonJarCopies.removeAllBut]).
 */
@Service(Service.Level.PROJECT)
class KtrapicCompileService(private val project: Project) : Disposable {
  private val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("KTrapIC compiles", 1)
  private val latest = HashMap<String, Pair<CompileRequest, CompletableFuture<CompileResponse>>>()
  private var daemon: Daemon? = null
  private var disposed = false
  /** The problem [runningOr] was last called with, until a daemon is started. */
  private var lastProblem: String? = null

  /** Used in place of the daemon, by tests. */
  @Volatile var compilerForTests: Ic10Compiler? = null

  /** The daemon's client, if there is one. */
  val daemonForTests: DaemonClient?
    get() = synchronized(this) { daemon?.client }

  /** [request]'s compile, which finishes on a pooled thread. */
  fun compile(request: CompileRequest): CompletableFuture<CompileResponse> {
    val future: CompletableFuture<CompileResponse>
    synchronized(latest) {
      latest[request.path]?.let { (asked, compile) ->
        if (asked == request && !compile.isCancelled && !unanswered(compile)) return compile
      }
      future = CompletableFuture()
      latest[request.path] = request to future
    }
    executor.execute {
      val current = synchronized(latest) { latest[request.path]?.second === future }
      if (!current || future.isDone) {
        future.cancel(false)
        return@execute
      }
      val response =
          try {
            compiler().compile(request.text)
          } catch (e: Exception) {
            LOG.warn("KTrapIC compile failed", e)
            CompileResponse.Failure("Compiling failed: $e", answered = false)
          }
      future.complete(response)
      if (!project.isDisposed) {
        project.messageBus
            .syncPublisher(CompileListener.TOPIC)
            .compiled(Compiled(request, response))
      }
    }
    return future
  }

  /**
   * The last finished compile of the file at [path], if it is the one last asked for and the daemon
   * answered it.
   */
  fun lastCompiled(path: String): Compiled? {
    val (request, future) = synchronized(latest) { latest[path] } ?: return null
    if (!future.isDone || future.isCancelled || unanswered(future)) return null
    return Compiled(request, future.getNow(null) ?: return null)
  }

  /**
   * Whether [compile] finished without the daemon's answer for the program (see
   * [CompileResponse.Failure.answered]). Such a compile isn't reused: asking again compiles again.
   */
  private fun unanswered(compile: CompletableFuture<CompileResponse>): Boolean =
      compile.isDone &&
          !compile.isCancelled &&
          (compile.getNow(null) as? CompileResponse.Failure)?.answered == false

  /** Stops the daemon, which starts again on the next compile, and forgets every compile. */
  fun restart() {
    synchronized(latest) { latest.clear() }
    synchronized(this) {
      daemon?.client?.close()
      daemon = null
    }
  }

  /**
   * [future]'s response, waiting for it while [checkCanceled] doesn't throw; null if the compile
   * was skipped for a newer one.
   */
  fun await(
      future: CompletableFuture<CompileResponse>,
      checkCanceled: () -> Unit,
  ): CompileResponse? {
    while (true) {
      checkCanceled()
      try {
        return future.get(50, TimeUnit.MILLISECONDS)
      } catch (_: TimeoutException) {} catch (_: CancellationException) {
        return null
      } catch (e: ExecutionException) {
        return CompileResponse.Failure("Compiling failed: ${e.cause}", answered = false)
      }
    }
  }

  /**
   * The daemon to compile with: the one running, from a copy of the jar the settings name, or else
   * a new one from a copy of that jar as it is now. While the jar can't be copied (Bazel deletes it
   * and then writes it in place, and `bazel clean` deletes it) the daemon running from a copy of it
   * goes on being used, and copying is tried again on the next compile; a daemon is replaced only
   * once a complete copy of the new jar has been made.
   */
  private fun compiler(): Ic10Compiler {
    compilerForTests?.let {
      return it
    }
    val settings = KtrapicSettings.getInstance().state
    val java = KtrapicPaths.java(settings.javaPath)
    val jarSetting = settings.daemonJar.orEmpty().trim()
    val jar =
        KtrapicPaths.daemonJar(jarSetting, project.basePath, KtrapicPlugin.daemonJar)
            ?: return runningOr(
                java,
                jarSetting,
                "No KTrapIC compile daemon: the plugin's own is missing, so set its jar under " +
                    "Settings > Tools > KTrapIC",
            )
    if (!File(jar).isFile) {
      return runningOr(java, jarSetting, "The compile daemon's jar $jar doesn't exist")
    }
    val copy =
        try {
          jarCopies.copyOf(File(jar))
        } catch (e: IOException) {
          return runningOr(
              java,
              jarSetting,
              "The compile daemon's jar couldn't be copied to run: ${e.message}",
          )
        }
    synchronized(this) {
      if (disposed) return failing("The project is closed")
      daemon?.let { if (it.java == java && it.copy == copy) return it.client }
      lastProblem = null
      daemon?.client?.close()
      jarCopies.removeAllBut(copy)
      val client = DaemonClient(listOf(java, "-jar", copy.path), log = { LOG.info("daemon: $it") })
      daemon = Daemon(java, jarSetting, copy, client)
      return client
    }
  }

  /**
   * A daemon run by [java] from [copy], a copy of the jar that the daemon jar setting [jarSetting]
   * names.
   */
  private class Daemon(
      val java: String,
      val jarSetting: String,
      val copy: File,
      val client: DaemonClient,
  )

  /**
   * The daemon started by [java] from a copy of the jar [jarSetting] names, if there is one, or
   * else a compiler that fails, unanswered, with [problem]. The daemon's copy may have been deleted
   * since (see [DaemonJarCopies.removeAllBut]); a running daemon still compiles, and one that has
   * stopped fails to restart, unanswered.
   */
  private fun runningOr(java: String, jarSetting: String, problem: String): Ic10Compiler {
    synchronized(this) {
      if (disposed) return failing("The project is closed")
      // Logged once while it lasts: a lasting problem (a full system drive, say) would otherwise
      // silently keep an old daemon compiling.
      if (problem != lastProblem) LOG.warn("KTrapIC: $problem")
      lastProblem = problem
      daemon?.let { if (it.java == java && it.jarSetting == jarSetting) return it.client }
    }
    return failing(problem)
  }

  private fun failing(description: String) = Ic10Compiler { _ ->
    CompileResponse.Failure(description, answered = false)
  }

  override fun dispose() {
    synchronized(this) {
      disposed = true
      daemon?.client?.close()
      daemon = null
    }
    executor.shutdownNow()
  }

  companion object {
    private val LOG = logger<KtrapicCompileService>()

    /** The daemon jar's copies, shared by every project (see [DaemonJarCopies]). */
    val jarCopies by lazy { DaemonJarCopies(copiesDir(File(PathManager.getSystemPath()))) }

    /**
     * Where the daemon jar's copies go, given the IDE's [systemDir]. In the usual layout, where
     * each IDE version has a directory of its own in a `JetBrains` directory, every version shares
     * one beside them, so the copies an older IDE made are cleaned up once a newer one runs. Else,
     * this IDE's own.
     */
    fun copiesDir(systemDir: File): File {
      val parent = systemDir.absoluteFile.parentFile
      return if (parent?.name == "JetBrains") File(parent, "ktrapic-daemon-jars")
      else File(systemDir.absoluteFile, "ktrapic/daemon-jars")
    }

    fun getInstance(project: Project): KtrapicCompileService = project.service()
  }
}
