package ktrapic.intellij

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KtrapicPathsTest {
  @Test
  fun `the daemon jar setting wins`() {
    assertEquals("/opt/daemon.jar", KtrapicPaths.daemonJar(" /opt/daemon.jar ", "/nowhere"))
  }

  @Test
  fun `a blank setting uses the jar the project builds, if it exists`() {
    val project = Files.createTempDirectory("ktrapic-project").toFile()
    try {
      assertNull(KtrapicPaths.daemonJar("", project.path))
      val jar = File(project, KtrapicPaths.BUILT_DAEMON_JAR).apply { parentFile.mkdirs() }
      jar.writeText("")
      assertEquals(jar.path, KtrapicPaths.daemonJar(null, project.path))
    } finally {
      project.deleteRecursively()
    }
  }

  @Test
  fun `a blank Java setting uses the running Java`() {
    assertEquals("/usr/bin/java", KtrapicPaths.java("/usr/bin/java"))
    assertTrue(File(KtrapicPaths.java("")).isFile)
  }

  @Test
  fun `programs are ic10 kts files`() {
    assertTrue(KtrapicPaths.isProgram("pump.ic10.kts"))
    assertEquals(false, KtrapicPaths.isProgram("build.gradle.kts"))
  }
}
