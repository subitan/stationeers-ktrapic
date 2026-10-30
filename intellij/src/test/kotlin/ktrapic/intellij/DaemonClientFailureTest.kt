package ktrapic.intellij

import org.junit.Assert.assertTrue
import org.junit.Test

/** The daemon client's answers when the daemon can't answer: no jar needed. */
class DaemonClientFailureTest {
  @Test
  fun `a daemon that exits without answering gives an error`() {
    // java -version prints to standard error and exits without reading its input.
    DaemonClient(listOf(KtrapicPaths.java(null), "-version")).use { client ->
      val response = client.compile("db.Setting = 1.0\n", CompileOptions())

      val error = (response as CompileResponse.Failure).errors.single()
      assertTrue(error.description, error.description.startsWith("The compile daemon stopped"))
      assertTrue(error.description, error.description.contains("version"))
    }
  }

  @Test
  fun `a daemon that can't be started gives an error`() {
    DaemonClient(listOf("no-such-java-ktrapic", "-jar", "daemon.jar")).use { client ->
      val response = client.compile("db.Setting = 1.0\n", CompileOptions())

      val error = (response as CompileResponse.Failure).errors.single()
      assertTrue(error.description, error.description.contains("couldn't be started"))
    }
  }
}
