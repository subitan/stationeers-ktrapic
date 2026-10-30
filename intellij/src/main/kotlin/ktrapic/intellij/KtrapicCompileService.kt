package ktrapic.intellij

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.Topic
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** A program to compile: the text of the file at [path], compiled with [options]. */
data class CompileRequest(val path: String, val text: String, val options: CompileOptions)

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
 * request for the same file has replaced is skipped.
 *
 * The daemon runs while the project is open, from the jar and Java the settings name, and is
 * replaced when they change.
 */
@Service(Service.Level.PROJECT)
class KtrapicCompileService(private val project: Project) : Disposable {
  private val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("KTrapIC compiles", 1)
  private val latest = HashMap<String, Pair<CompileRequest, CompletableFuture<CompileResponse>>>()
  private var daemon: DaemonClient? = null
  private var disposed = false

  /** Used in place of the daemon, by tests. */
  @Volatile var compilerForTests: Ic10Compiler? = null

  /** [request]'s compile, which finishes on a pooled thread. */
  fun compile(request: CompileRequest): CompletableFuture<CompileResponse> {
    val future: CompletableFuture<CompileResponse>
    synchronized(latest) {
      latest[request.path]?.let { (asked, compile) ->
        if (asked == request && !compile.isCancelled) return compile
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
            compiler().compile(request.text, request.options)
          } catch (e: Exception) {
            LOG.warn("KTrapIC compile failed", e)
            CompileResponse.Failure("Compiling failed: $e")
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

  /** The last finished compile of the file at [path], if it is the one last asked for. */
  fun lastCompiled(path: String): Compiled? {
    val (request, future) = synchronized(latest) { latest[path] } ?: return null
    if (!future.isDone || future.isCancelled) return null
    return Compiled(request, future.getNow(null) ?: return null)
  }

  /** Stops the daemon, which starts again on the next compile, and forgets every compile. */
  fun restart() {
    synchronized(latest) { latest.clear() }
    synchronized(this) {
      daemon?.close()
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
        return CompileResponse.Failure("Compiling failed: ${e.cause}")
      }
    }
  }

  private fun compiler(): Ic10Compiler {
    compilerForTests?.let {
      return it
    }
    val settings = KtrapicSettings.getInstance().state
    val jar =
        KtrapicPaths.daemonJar(settings.daemonJar, project.basePath)
            ?: return Ic10Compiler { _, _ ->
              CompileResponse.Failure(
                  "No KTrapIC compile daemon: set its jar under Settings > Tools > KTrapIC, or " +
                      "build it in this project with bazel build //daemon:ktrapic-daemon_deploy.jar"
              )
            }
    val command = listOf(KtrapicPaths.java(settings.javaPath), "-jar", jar)
    synchronized(this) {
      if (disposed) return Ic10Compiler { _, _ -> CompileResponse.Failure("The project is closed") }
      val running = daemon
      if (running != null && running.command == command) return running
      running?.close()
      return DaemonClient(command, log = { LOG.info("daemon: $it") }).also { daemon = it }
    }
  }

  override fun dispose() {
    synchronized(this) {
      disposed = true
      daemon?.close()
      daemon = null
    }
    executor.shutdownNow()
  }

  companion object {
    private val LOG = logger<KtrapicCompileService>()

    fun getInstance(project: Project): KtrapicCompileService = project.service()
  }
}
