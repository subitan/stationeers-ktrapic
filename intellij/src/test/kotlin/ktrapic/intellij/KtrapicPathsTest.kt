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
    assertEquals("/opt/daemon.jar", KtrapicPaths.daemonJar(" /opt/daemon.jar ", "/nowhere", null))
  }

  @Test
  fun `a blank setting uses the jar the project builds, if it exists, or else the bundled one`() {
    val project = Files.createTempDirectory("ktrapic-project").toFile()
    try {
      val bundled = File(project, "bundled.jar")
      assertNull(KtrapicPaths.daemonJar("", project.path, bundled))
      bundled.writeText("")
      assertEquals(bundled.path, KtrapicPaths.daemonJar("", project.path, bundled))
      val jar = File(project, KtrapicPaths.BUILT_DAEMON_JAR).apply { parentFile.mkdirs() }
      jar.writeText("")
      assertEquals(jar.path, KtrapicPaths.daemonJar(null, project.path, bundled))
    } finally {
      project.deleteRecursively()
    }
  }

  @Test
  fun `daemon jar copies are shared by every IDE version in the usual layout`() {
    val jetBrains = File("/home/me/.cache/JetBrains").absoluteFile

    assertEquals(
        File(jetBrains, "ktrapic-daemon-jars"),
        KtrapicCompileService.copiesDir(File(jetBrains, "IntelliJIdea2025.1")),
    )
    assertEquals(
        File("/idea/system/ktrapic/daemon-jars").absoluteFile,
        KtrapicCompileService.copiesDir(File("/idea/system")),
    )
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
