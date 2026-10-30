package ktrapic.intellij

import com.intellij.openapi.application.PathManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * The compile service running its daemon from a copy of the jar, with a stand-in daemon
 * ([PidDaemon]) that answers each compile with its process ID.
 */
class KtrapicCompileServiceDaemonTest : BasePlatformTestCase() {
  private lateinit var service: KtrapicCompileService
  private lateinit var dir: File
  private lateinit var jar: File
  private var savedJarSetting: String? = null

  override fun setUp() {
    super.setUp()
    service = KtrapicCompileService.getInstance(project)
    service.compilerForTests = null
    dir = Files.createTempDirectory("ktrapic-daemon").toFile()
    jar = TestJars.daemon(File(dir, "ktrapic-daemon_deploy.jar"), PidDaemon::class.java)
    val settings = KtrapicSettings.getInstance().state
    savedJarSetting = settings.daemonJar
    settings.daemonJar = jar.path
  }

  override fun tearDown() {
    try {
      KtrapicSettings.getInstance().state.daemonJar = savedJarSetting
      service.restart()
      dir.deleteRecursively()
    } finally {
      super.tearDown()
    }
  }

  private fun request(text: String) = CompileRequest("/program.ic10.kts", text, CompileOptions())

  private fun compile(text: String): CompileResponse =
      service.compile(request(text)).get(60, TimeUnit.SECONDS)

  /** The process ID of the daemon that compiled [text]. */
  private fun pidOf(text: String): Long = (compile(text) as CompileResponse.Success).code.toLong()

  private fun runningCopy(): File = File(service.daemonForTests!!.command.last())

  fun `test runs the daemon from a shared copy of the jar`() {
    val pid = pidOf("a")

    val copy = runningCopy()
    assertEquals(KtrapicCompileService.jarCopies.copyPathOf(jar), copy)
    assertEquals(File(PathManager.getSystemPath(), "ktrapic/daemon-jars"), copy.parentFile)
    assertTrue(jar.readBytes().contentEquals(copy.readBytes()))
    assertEquals(service.daemonForTests!!.processForTests!!.pid(), pid)
  }

  fun `test a rebuilt jar gets a new daemon from a new copy`() {
    val firstPid = pidOf("a")
    val firstCopy = runningCopy()
    // Old enough for a daemon's start to clean it up.
    assertTrue(
        firstCopy.setLastModified(
            System.currentTimeMillis() - DaemonJarCopies.MIN_AGE_MILLIS - 60_000
        )
    )

    jar.setLastModified(jar.lastModified() + 10_000)
    val secondPid = pidOf("b")
    val secondCopy = runningCopy()

    assertFalse(firstPid == secondPid)
    assertFalse(firstCopy == secondCopy)
    assertTrue(secondCopy.isFile)
    ProcessHandle.of(firstPid).ifPresent { it.onExit().get(30, TimeUnit.SECONDS) }
    // On Windows the old copy goes once its daemon has stopped, when the next daemon starts.
    if (!TestJars.windows) assertFalse(firstCopy.exists())
  }

  fun `test a missing jar keeps the running daemon`() {
    val pid = pidOf("a")

    assertTrue(jar.delete())

    assertEquals(pid, pidOf("b"))
  }

  fun `test a jar cut short, as while Bazel writes it, keeps the running daemon`() {
    val pid = pidOf("a")
    val built = jar.lastModified()
    val whole = jar.readBytes()

    jar.writeBytes(whole.copyOf(whole.size / 2))
    jar.setLastModified(built + 10_000)

    assertEquals(pid, pidOf("b"))
    assertTrue(service.daemonForTests!!.processForTests!!.isAlive)

    jar.writeBytes(whole)
    jar.setLastModified(built + 20_000)

    assertFalse(pid == pidOf("c"))
    assertEquals(KtrapicCompileService.jarCopies.copyPathOf(jar), runningCopy())
  }

  fun `test with neither the jar nor a daemon the compile fails unanswered`() {
    assertTrue(jar.delete())

    val failure = compile("a") as CompileResponse.Failure

    assertFalse(failure.answered)
    assertEquals(
        "The compile daemon's jar ${jar.path} doesn't exist",
        failure.errors.single().description,
    )
  }

  fun `test a jar that couldn't be copied is copied on the next compile`() {
    // A directory where the copy goes stops it being made.
    val blocked = KtrapicCompileService.jarCopies.copyPathOf(jar)!!
    File(blocked, "in the way").apply { parentFile.mkdirs() }.writeText("")
    try {
      val failure = compile("a") as CompileResponse.Failure
      assertFalse(failure.answered)
      assertTrue(
          failure.errors.single().description,
          failure.errors.single().description.startsWith("The compile daemon's jar couldn't be"),
      )
    } finally {
      blocked.deleteRecursively()
    }

    assertTrue(compile("a") is CompileResponse.Success)
    assertEquals(blocked, runningCopy())
  }
}
