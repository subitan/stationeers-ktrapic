package ktrapic.intellij

import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DaemonJarCopiesTest {
  @get:Rule val temp = TemporaryFolder()

  private val copies by lazy { DaemonJarCopies(temp.newFolder("copies")) }

  @Test
  fun `a copy has the jar's content and is made once`() {
    val jar = file("daemon.jar", "first")

    val copy = copies.copyOf(jar)
    val again = copies.copyOf(jar)

    assertNotEquals(jar, copy)
    assertArrayEquals(jar.readBytes(), copy.readBytes())
    assertEquals(copy, again)
  }

  @Test
  fun `a rebuilt jar gets a new copy`() {
    val jar = file("daemon.jar", "first")
    val old = copies.copyOf(jar)

    jar.writeText("second, longer")
    jar.setLastModified(old.lastModified() + 10_000)
    val new = copies.copyOf(jar)

    assertNotEquals(old, new)
    assertEquals("second, longer", new.readText())
    assertEquals("first", old.readText())
  }

  @Test
  fun `removes every copy but the one kept`() {
    val first = copies.copyOf(file("first.jar", "first"))
    val second = copies.copyOf(file("second.jar", "second"))

    copies.removeAllBut(second)

    assertFalse(first.exists())
    assertTrue(second.exists())
  }

  @Test
  fun `copies are shared by everything using the same directory`() {
    val jar = file("daemon.jar", "first")
    val dir = temp.newFolder("shared")

    val copy = DaemonJarCopies(dir).copyOf(jar)

    assertEquals(copy, DaemonJarCopies(dir).copyPathOf(jar))
    assertEquals(copy, DaemonJarCopies(dir).copyOf(jar))
  }

  @Test
  fun `the jar can be replaced while a daemon runs from its copy`() {
    val jar = TestJars.daemon(temp.newFile("daemon.jar"), SilentDaemon::class.java)
    val copy = copies.copyOf(jar)
    val process = ProcessBuilder(KtrapicPaths.java(null), "-jar", copy.path).start()
    try {
      // SilentDaemon writes its process ID once it is running, by which time the JVM has its jar
      // open.
      val ready = process.errorStream.bufferedReader().readLine()
      assertEquals(process.pid().toString(), ready)

      assertTrue(jar.delete())
      if (TestJars.windows) {
        // What the copy is for: Windows doesn't let a running daemon's jar go, so its copy is kept.
        copies.removeAllBut(null)
        assertTrue(copy.exists())
      }
    } finally {
      process.destroy()
      process.waitFor(30, TimeUnit.SECONDS)
    }
  }

  @Test
  fun `elsewhere than Windows, a running daemon's copy can be deleted and the daemon goes on`() {
    assumeFalse(TestJars.windows)
    val copy = copies.copyOf(TestJars.daemon(temp.newFile("daemon.jar"), PidDaemon::class.java))
    val process = ProcessBuilder(KtrapicPaths.java(null), "-jar", copy.path).start()
    try {
      // Running, with its jar open.
      assertEquals(process.pid().toString(), process.errorStream.bufferedReader().readLine())

      copies.removeAllBut(null)
      assertFalse(copy.exists())

      // PidDaemon loads the class that answers from its jar only now.
      process.outputStream.write("request\n".toByteArray())
      process.outputStream.flush()
      val answer = process.inputStream.bufferedReader().readLine()
      assertEquals(
          """{"code":"${process.pid()}"}""",
          String(Base64.getDecoder().decode(answer), Charsets.UTF_8),
      )
    } finally {
      process.destroy()
      process.waitFor(30, TimeUnit.SECONDS)
    }
  }

  private fun file(name: String, text: String): File = temp.newFile(name).apply { writeText(text) }
}
