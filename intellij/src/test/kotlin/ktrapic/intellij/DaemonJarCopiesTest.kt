package ktrapic.intellij

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
  fun `the jar can be replaced while a daemon runs from its copy`() {
    val jar = silentDaemonJar(temp.newFile("daemon.jar"))
    val copy = copies.copyOf(jar)
    val process = ProcessBuilder(KtrapicPaths.java(null), "-jar", copy.path).start()
    try {
      // SilentDaemon writes its process ID once it is running, by which time the JVM has its jar
      // open.
      val ready = process.errorStream.bufferedReader().readLine()
      assertEquals(process.pid().toString(), ready)

      assertTrue(jar.delete())
      if (System.getProperty("os.name").startsWith("Windows")) {
        // What the copy is for: Windows doesn't let a running daemon's jar go.
        assertFalse(copy.delete())
      }
    } finally {
      process.destroy()
      process.waitFor(30, TimeUnit.SECONDS)
    }
  }

  private fun file(name: String, text: String): File = temp.newFile(name).apply { writeText(text) }

  /** A jar at [jar] that runs [SilentDaemon]. */
  private fun silentDaemonJar(jar: File): File {
    val manifest =
        Manifest().apply {
          mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
          mainAttributes[Attributes.Name.MAIN_CLASS] = SilentDaemon::class.java.name
        }
    val path = SilentDaemon::class.java.name.replace('.', '/') + ".class"
    val bytes =
        SilentDaemon::class.java.classLoader.getResourceAsStream(path)!!.use { it.readBytes() }
    JarOutputStream(Files.newOutputStream(jar.toPath()), manifest).use {
      it.putNextEntry(JarEntry(path))
      it.write(bytes)
      it.closeEntry()
    }
    return jar
  }
}
