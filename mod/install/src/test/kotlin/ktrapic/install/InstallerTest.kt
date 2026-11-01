package ktrapic.install

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class InstallerTest {
  @TempDir lateinit var tmp: Path

  @Test
  fun `no mods directory is a usage error`() {
    assertThat(assertThrows<UsageError> { parseArgs(listOf(), tmp) })
        .hasMessageThat()
        .contains("mods directory")
    assertThrows<UsageError> { parseArgs(listOf("--local-compiler"), tmp) }
  }

  @Test
  fun `an unknown option or a second directory is a usage error`() {
    assertThat(assertThrows<UsageError> { parseArgs(listOf("mods", "--release"), tmp) })
        .hasMessageThat()
        .contains("--release")
    assertThrows<UsageError> { parseArgs(listOf("mods", "other"), tmp) }
  }

  @Test
  fun `a relative mods directory is relative to the working directory`() {
    assertThat(parseArgs(listOf("a/../mods"), tmp)).isEqualTo(Request(tmp.resolve("mods"), false))
    assertThat(parseArgs(listOf("--local-compiler", "mods"), tmp).localCompiler).isTrue()
  }

  @Test
  fun `a mods directory must exist and look like one`() {
    assertThat(assertThrows<UsageError> { checkModsDir(tmp.resolve("mods")) })
        .hasMessageThat()
        .contains("doesn't exist")
    assertThat(assertThrows<UsageError> { checkModsDir(tmp.resolve("file").createFile()) })
        .hasMessageThat()
        .contains("isn't a directory")
    assertThat(
            assertThrows<UsageError> { checkModsDir(tmp.resolve("Desktop").createDirectories()) }
        )
        .hasMessageThat()
        .contains("doesn't look like")
  }

  @Test
  fun `a directory named mods, or beside the game's setting xml, is a mods directory`() {
    checkModsDir(tmp.resolve("Mods").createDirectories())
    val stationeers = tmp.resolve("Stationeers").createDirectories()
    stationeers.resolve("setting.xml").createFile()
    checkModsDir(stationeers.resolve("local-mods").createDirectories())
  }

  @Test
  fun `the plugin and its About directory are installed, replacing an older install`() {
    val build = tmp.resolve("build").createDirectories()
    val dll = build.resolve("KTrapIC.dll").apply { writeText("new dll") }
    val about = build.resolve("About").createDirectories()
    about.resolve("About.xml").writeText("<ModMetadata/>")
    about.resolve("images").createDirectories().resolve("thumb.png").writeText("png")
    val mods = tmp.resolve("mods").createDirectories()
    mods.resolve("KTrapIC").createDirectories().resolve("KTrapIC.dll").writeText("old dll")
    mods.resolve("KTrapIC/About").createDirectories().resolve("old.png").writeText("old png")

    val pluginDir = installPlugin(dll, about, mods)

    assertThat(pluginDir).isEqualTo(mods.resolve("KTrapIC"))
    assertThat(pluginDir.resolve("KTrapIC.dll").readText()).isEqualTo("new dll")
    assertThat(pluginDir.resolve("About/About.xml").readText()).isEqualTo("<ModMetadata/>")
    assertThat(pluginDir.resolve("About/images/thumb.png").readText()).isEqualTo("png")
    assertThat(pluginDir.resolve("About/old.png").exists()).isFalse()
  }

  @Test
  fun `a linked About directory is refused, and what it points at is kept`() {
    val (dll, about, mods) = buildAndMods()
    val linked = tmp.resolve("checkout/About").createDirectories()
    linked.resolve("About.xml").writeText("<ModMetadata/>")
    val link = mods.resolve("KTrapIC").createDirectories().resolve("About")
    try {
      Files.createSymbolicLink(link, linked)
    } catch (e: Exception) {
      assumeTrue(false, "can't create a symbolic link here: $e")
    }

    assertThat(linkRefusal(mods)).contains("is a link")
    assertThrows<IOException> { installPlugin(dll, about, mods) }
    assertThat(linked.resolve("About.xml").exists()).isTrue()
  }

  @Test
  fun `a junction is refused`() {
    assumeTrue(System.getProperty("os.name").startsWith("Windows"), "junctions are Windows'")
    val (dll, about, mods) = buildAndMods()
    val linked = tmp.resolve("checkout").createDirectories()
    linked.resolve("KTrapIC.dll").writeText("kept")
    val link = mods.resolve("KTrapIC")
    val mklink = ProcessBuilder("cmd", "/c", "mklink", "/J", "$link", "$linked")
    assumeTrue(mklink.inheritIO().start().waitFor() == 0, "mklink /J failed")

    assertThat(linkRefusal(mods)).contains("is a link")
    assertThrows<IOException> { installPlugin(dll, about, mods) }
    assertThat(linked.resolve("KTrapIC.dll").readText()).isEqualTo("kept")
  }

  @Test
  fun `an install that isn't linked isn't refused`() {
    val mods = tmp.resolve("mods").createDirectories()
    assertThat(linkRefusal(mods)).isNull()
    mods.resolve("KTrapIC/About").createDirectories()
    assertThat(linkRefusal(mods)).isNull()
  }

  /** A built plugin (its dll and About directory) and an empty mods directory. */
  private fun buildAndMods(): Triple<Path, Path, Path> {
    val build = tmp.resolve("build").createDirectories()
    val dll = build.resolve("KTrapIC.dll").apply { writeText("new dll") }
    val about = build.resolve("About").createDirectories()
    about.resolve("About.xml").writeText("<ModMetadata/>")
    return Triple(dll, about, tmp.resolve("mods").createDirectories())
  }

  @Test
  fun `the local compiler is refused off Windows`() {
    assertThat(localCompilerRefusal("Windows 10")).isNull()
    assertThat(localCompilerRefusal("Windows Server 2022")).isNull()
    assertThat(localCompilerRefusal("Linux")).contains("DaemonJarPath")
    assertThat(localCompilerRefusal("Mac OS X")).contains("works only on Windows")
  }

  @Test
  fun `the compiler is installed in the plugin's directory`() {
    val jar = tmp.resolve("ktrapic-daemon_deploy.jar").apply { writeText("jar") }
    val installed = installCompiler(jar, tmp.resolve("mods/KTrapIC"))
    assertThat(installed).isEqualTo(tmp.resolve("mods/KTrapIC/compiler/ktrapic-daemon.jar"))
    assertThat(installed.readText()).isEqualTo("jar")

    // Bazel's outputs are read-only; installing again replaces the copy all the same.
    jar.toFile().setWritable(false)
    installCompiler(jar, tmp.resolve("mods/KTrapIC"))
    jar.toFile().setWritable(true)
    jar.writeText("newer jar")
    installCompiler(jar, tmp.resolve("mods/KTrapIC"))
    assertThat(installed.readText()).isEqualTo("newer jar")
  }

  @Test
  fun `only the compiler installed in the plugin's directory is the installed compiler`() {
    val pluginDir = tmp.resolve("mods/KTrapIC")
    val installed = compilerPath(pluginDir).toString()
    assertThat(isInstalledCompiler(installed, pluginDir)).isTrue()
    assertThat(isInstalledCompiler(installed.replace('\\', '/'), pluginDir)).isTrue()
    assertThat(isInstalledCompiler("$pluginDir/x/../compiler/ktrapic-daemon.jar", pluginDir))
        .isTrue()
    assertThat(isInstalledCompiler(tmp.resolve("ktrapic-daemon.jar").toString(), pluginDir))
        .isFalse()
    assertThat(isInstalledCompiler("$pluginDir/compiler/other.jar", pluginDir)).isFalse()
    assertThat(isInstalledCompiler("bad\u0000path", pluginDir)).isFalse()
  }

  @Test
  fun `the installed compiler is removed, and its directory if that's left empty`() {
    val pluginDir = tmp.resolve("mods/KTrapIC")
    val jar = tmp.resolve("ktrapic-daemon_deploy.jar").apply { writeText("jar") }
    installCompiler(jar, pluginDir)
    removeCompiler(pluginDir)
    assertThat(pluginDir.resolve("compiler").exists()).isFalse()

    installCompiler(jar, pluginDir)
    pluginDir.resolve("compiler/notes.txt").writeText("mine")
    removeCompiler(pluginDir)
    assertThat(compilerPath(pluginDir).exists()).isFalse()
    assertThat(pluginDir.resolve("compiler/notes.txt").exists()).isTrue()

    removeCompiler(tmp.resolve("elsewhere")) // Nothing installed: nothing to do.
  }

  @Test
  fun `DaemonJarPath is replaced in the plugin's settings, keeping the rest`() {
    val config = tmp.resolve("ktrapic.cfg").apply { writeText(SETTINGS) }
    val jar = tmp.resolve("compiler/ktrapic-daemon.jar")

    setDaemonJarPath(config, jar)

    val path = jar.toAbsolutePath().toString().replace('\\', '/')
    assertThat(config.readText())
        .isEqualTo(SETTINGS.replace("DaemonJarPath = C:/old/daemon.jar", "DaemonJarPath = $path"))
    assertThat(daemonJarPath(config)).isEqualTo(path)
  }

  @Test
  fun `DaemonJarPath is cleared`() {
    val config = tmp.resolve("ktrapic.cfg").apply { writeText(SETTINGS) }
    setDaemonJarPath(config, null)
    assertThat(config.readText())
        .isEqualTo(SETTINGS.replace("DaemonJarPath = C:/old/daemon.jar", "DaemonJarPath = "))
    assertThat(daemonJarPath(config)).isNull()
  }

  @Test
  fun `DaemonJarPath is added to a settings file without it`() {
    val config = tmp.resolve("ktrapic.cfg")
    config.writeText("[General]\n\nCompact = false\n")
    setDaemonJarPath(config, Path.of("/jar"))
    assertThat(config.readText()).startsWith("[General]\n\nCompact = false\n\n[Development]\n\n")
    assertThat(daemonJarPath(config)).isNotNull()

    config.writeText("[Development]\n\nJavaPath = \n\n[General]\n")
    setDaemonJarPath(config, Path.of("/jar"))
    assertThat(config.readText()).startsWith("[Development]\nDaemonJarPath = ")
    assertThat(config.readText()).endsWith("JavaPath = \n\n[General]\n")
  }

  @Test
  fun `a settings file's CRLF line endings are kept`() {
    val crlf = SETTINGS.replace("\n", "\r\n")
    val config = tmp.resolve("ktrapic.cfg").apply { writeText(crlf) }
    setDaemonJarPath(config, null)
    assertThat(config.readText())
        .isEqualTo(crlf.replace("DaemonJarPath = C:/old/daemon.jar", "DaemonJarPath = "))
    assertThat(daemonJarPath(config)).isNull()

    config.writeText("[General]\r\n\r\nCompact = false\r\n")
    setDaemonJarPath(config, Path.of("/jar"))
    assertThat(config.readText())
        .startsWith("[General]\r\n\r\nCompact = false\r\n\r\n[Development]\r\n")
    assertThat(config.readText()).doesNotContainMatch("[^\r]\n")
  }

  @Test
  fun `DaemonJarPath is read from the Development section only`() {
    val config = tmp.resolve("ktrapic.cfg")
    config.writeText("[General]\n\nDaemonJarPath = C:/elsewhere.jar\n")
    assertThat(daemonJarPath(config)).isNull()

    config.writeText("[General]\n\nDaemonJarPath = C:/elsewhere.jar\n\n[Development]\n")
    setDaemonJarPath(config, Path.of("/jar"))
    assertThat(daemonJarPath(config))
        .isEqualTo(Path.of("/jar").toAbsolutePath().toString().replace('\\', '/'))
    assertThat(config.readText()).contains("DaemonJarPath = C:/elsewhere.jar\n")
  }

  @Test
  fun `a missing settings file is created`() {
    val config = tmp.resolve("BepInEx/config/ktrapic.cfg")
    assertThat(daemonJarPath(config)).isNull()
    setDaemonJarPath(config, Path.of("/jar"))
    assertThat(config.readText()).startsWith("[Development]\n\nDaemonJarPath = ")
  }

  private companion object {
    /** As the plugin writes it (BepInEx 5), but with LF line endings, where Windows has CRLF. */
    val SETTINGS =
        """
        ## Settings file was created by plugin KTrapIC v0.1.0
        ## Plugin GUID: com.github.subitan.stationeers-ktrapic

        [Development]

        ## A java executable (21 or later) to run the compiler with, instead of the downloaded runtime
        # Setting type: String
        # Default value:
        JavaPath =

        ## A KTrapIC daemon jar (ktrapic-daemon_deploy.jar) to compile with, instead of the downloaded one
        # Setting type: String
        # Default value:
        DaemonJarPath = C:/old/daemon.jar

        """
            .trimIndent() + "\n"
  }
}
