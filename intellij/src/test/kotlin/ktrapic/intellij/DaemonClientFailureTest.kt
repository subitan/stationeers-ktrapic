package ktrapic.intellij

import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The daemon client's answers when the daemon can't answer: no jar needed. */
class DaemonClientFailureTest {
  @Test
  fun `a daemon that exits without answering gives an error`() {
    // java -version prints to standard error and exits without reading its input.
    DaemonClient(listOf(KtrapicPaths.java(null), "-version")).use { client ->
      val response = client.compile("db.Setting = 1.0\n")

      val failure = response as CompileResponse.Failure
      val error = failure.errors.single()
      assertTrue(error.description, error.description.startsWith("The compile daemon stopped"))
      assertTrue(error.description, error.description.contains("version"))
      assertFalse(failure.answered)
    }
  }

  @Test
  fun `a daemon that can't be started gives an error`() {
    DaemonClient(listOf("no-such-java-ktrapic", "-jar", "daemon.jar")).use { client ->
      val response = client.compile("db.Setting = 1.0\n")

      val failure = response as CompileResponse.Failure
      val error = failure.errors.single()
      assertTrue(error.description, error.description.contains("couldn't be started"))
      assertFalse(failure.answered)
    }
  }

  @Test
  fun `a daemon that doesn't answer in time is stopped and started again`() {
    // SilentDaemon writes its process ID to standard error, which the client logs.
    val pids = LinkedBlockingQueue<Long>()
    val command =
        listOf(KtrapicPaths.java(null), "-cp", testClasses(), SilentDaemon::class.java.name)
    DaemonClient(
            command,
            timeoutMillis = 2000,
            log = { line -> line.toLongOrNull()?.let(pids::put) },
        )
        .use { client ->
          val response = client.compile("db.Setting = 1.0\n")

          val failure = response as CompileResponse.Failure
          val error = failure.errors.single()
          assertTrue(error.description, error.description.contains("didn't answer within"))
          assertFalse(failure.answered)
          val first = pids.poll(30, TimeUnit.SECONDS)
          assertNotNull(first)
          assertFalse(ProcessHandle.of(first!!).map { it.isAlive }.orElse(false))

          client.compile("db.Setting = 1.0\n")

          val second = pids.poll(30, TimeUnit.SECONDS)
          assertNotNull(second)
          assertNotEquals(first, second)
          assertEquals(0, pids.size)
        }
  }

  /** The directory of classes [SilentDaemon] is in. */
  private fun testClasses(): String {
    val name = SilentDaemon::class.java.name
    var dir =
        File(
            SilentDaemon::class.java.getResource("${name.substringAfterLast('.')}.class")!!.toURI()
        )
    repeat(name.count { it == '.' } + 1) { dir = dir.parentFile }
    return dir.path
  }
}
