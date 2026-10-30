package ktrapic.intellij

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.jar.JarOutputStream
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DaemonJarCopiesTest {
  @get:Rule val temp = TemporaryFolder()

  private val copies by lazy { DaemonJarCopies(temp.newFolder("copies")) }

  /** A time at which every file made so far is old enough for [DaemonJarCopies.removeAllBut]. */
  private val later: Long
    get() = System.currentTimeMillis() + DaemonJarCopies.MIN_AGE_MILLIS + 60_000

  @Test
  fun `a copy has the jar's content and is made once`() {
    val jar = jar("daemon.jar")

    val copy = copies.copyOf(jar)
    val again = copies.copyOf(jar)

    assertNotEquals(jar, copy)
    assertArrayEquals(jar.readBytes(), copy.readBytes())
    assertEquals(copy, again)
  }

  @Test
  fun `a rebuilt jar gets a new copy`() {
    val jar = jar("daemon.jar")
    val first = jar.readBytes()
    val old = copies.copyOf(jar)

    TestJars.daemon(jar, PidDaemon::class.java)
    jar.setLastModified(old.lastModified() + 10_000)
    val new = copies.copyOf(jar)

    assertNotEquals(old, new)
    assertArrayEquals(jar.readBytes(), new.readBytes())
    assertArrayEquals(first, old.readBytes())
  }

  @Test
  fun `a jar cut short, as while Bazel writes it, isn't copied`() {
    val jar = jar("daemon.jar")
    val whole = jar.readBytes()
    jar.writeBytes(whole.copyOf(whole.size / 2))

    val e = assertThrows(IOException::class.java) { copies.copyOf(jar) }

    assertTrue(e.message, e.message!!.contains("isn't a complete jar"))
    assertEquals(emptyList<File>(), copies.dir.listFiles().orEmpty().toList())
  }

  @Test
  fun `a jar with no main class isn't copied`() {
    val jar = temp.newFile("daemon.jar")
    JarOutputStream(Files.newOutputStream(jar.toPath())).close()

    val e = assertThrows(IOException::class.java) { copies.copyOf(jar) }

    assertTrue(e.message, e.message!!.contains("names no main class"))
  }

  @Test
  fun `removes every old copy but the one kept`() {
    val first = copies.copyOf(jar("first.jar"))
    val second = copies.copyOf(jar("second.jar"))

    copies.removeAllBut(second, now = later)

    assertFalse(first.exists())
    assertTrue(second.exists())
  }

  @Test
  fun `leaves recent copies and partly made ones, which may be another project's`() {
    val first = copies.copyOf(jar("first.jar"))
    val partial = File(copies.dir, "partial-1.jar").apply { writeText("") }

    copies.removeAllBut(null)

    assertTrue(first.exists())
    assertTrue(partial.exists())

    copies.removeAllBut(null, now = later)

    assertFalse(first.exists())
    assertFalse(partial.exists())
  }

  @Test
  fun `a copy handed out again is recent again`() {
    val jar = jar("daemon.jar")
    val copy = copies.copyOf(jar)
    val old = System.currentTimeMillis() - DaemonJarCopies.MIN_AGE_MILLIS - 60_000
    assertTrue(copy.setLastModified(old))

    assertEquals(copy, copies.copyOf(jar))
    copies.removeAllBut(null)

    assertTrue(copy.exists())
  }

  @Test
  fun `copies are shared by everything using the same directory`() {
    val jar = jar("daemon.jar")
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
        copies.removeAllBut(null, now = later)
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

      copies.removeAllBut(null, now = later)
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

  @Test
  fun `daemons started at once from different jars all start`() {
    val jars =
        listOf("a.jar", "b.jar").mapIndexed { i, name ->
          TestJars.daemon(temp.newFile(name), PidDaemon::class.java, padding = 16 shl 20).apply {
            setLastModified(lastModified() + i * 10_000L)
          }
        }
    val failures = ConcurrentLinkedQueue<String>()
    repeat(8) { iteration ->
      copies.dir.listFiles()?.forEach { it.delete() }
      val together = CyclicBarrier(jars.size)
      val starts = jars.map { jar ->
        thread {
          together.await()
          try {
            // What the compile service does as it starts a daemon.
            val copy = copies.copyOf(jar)
            copies.removeAllBut(copy)
            val process = ProcessBuilder(KtrapicPaths.java(null), "-jar", copy.path).start()
            try {
              val ready = process.errorStream.bufferedReader().readLine()
              if (ready != process.pid().toString()) {
                failures += "iteration $iteration, ${jar.name}: $ready"
              }
            } finally {
              process.destroy()
              process.waitFor(30, TimeUnit.SECONDS)
            }
          } catch (e: Exception) {
            failures += "iteration $iteration, ${jar.name}: $e"
          }
        }
      }
      starts.forEach { it.join() }
    }
    assertEquals(emptyList<String>(), failures.toList())
  }

  private fun jar(name: String): File =
      TestJars.daemon(temp.newFile(name), SilentDaemon::class.java)
}
