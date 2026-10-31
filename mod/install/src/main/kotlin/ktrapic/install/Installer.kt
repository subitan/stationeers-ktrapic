package ktrapic.install

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** A problem with what `bazel run //mod:install` was given, reported with the usage. */
class UsageError(message: String) : Exception(message)

/** What `bazel run //mod:install` was asked to do. */
data class Request(val modsDir: Path, val localCompiler: Boolean)

const val USAGE =
    "Usage: bazel run //mod:install -- <mods-dir> [--local-compiler]\n" +
        "  <mods-dir>         StationeersLaunchPad's local mods directory, such as\n" +
        "                     \"Documents/My Games/Stationeers/mods\"\n" +
        "  --local-compiler   also install this checkout's compiler, and set the plugin's\n" +
        "                     DaemonJarPath to it, instead of downloading the release's"

/** The plugin's settings file under the game's directory, where `DaemonJarPath` is set. */
const val CONFIG_FILE = "BepInEx/config/com.github.subitan.stationeers-ktrapic.cfg"

/** Reads the arguments; a relative mods directory is relative to [workingDir]. */
fun parseArgs(args: List<String>, workingDir: Path): Request {
  val flags = args.filter { it.startsWith("-") }
  flags.firstOrNull { it != "--local-compiler" }?.let { throw UsageError("Unknown option $it.") }
  val dirs = args - flags.toSet()
  if (dirs.isEmpty()) throw UsageError("Name StationeersLaunchPad's local mods directory.")
  if (dirs.size > 1) throw UsageError("Name one mods directory, not ${dirs.size}.")
  return Request(workingDir.resolve(dirs.single()).normalize(), "--local-compiler" in flags)
}

/**
 * Checks [dir] looks like StationeersLaunchPad's local mods directory: an existing directory named
 * `mods`, or one beside the game's `setting.xml` (in `Documents/My Games/Stationeers`).
 */
fun checkModsDir(dir: Path) {
  if (!dir.exists()) throw UsageError("$dir doesn't exist.")
  if (!dir.isDirectory()) throw UsageError("$dir isn't a directory.")
  val besideSettings = dir.parent?.resolve("setting.xml")?.isRegularFile() == true
  if (!dir.name.equals("mods", ignoreCase = true) && !besideSettings) {
    throw UsageError(
        "$dir doesn't look like StationeersLaunchPad's local mods directory: that is \"mods\" " +
            "in \"Documents/My Games/Stationeers\", beside the game's setting.xml."
    )
  }
}

/**
 * Copies the plugin, [dll] and the [about] directory, into `<modsDir>/KTrapIC`, replacing what is
 * there (the whole `About` directory, so files no longer in [about] go), and returns that
 * directory.
 */
fun installPlugin(dll: Path, about: Path, modsDir: Path): Path {
  val target = modsDir.resolve("KTrapIC").createDirectories()
  Files.copy(dll, target.resolve(dll.name), REPLACE_EXISTING)
  val installedAbout = target.resolve("About")
  if (installedAbout.exists() && !installedAbout.toFile().deleteRecursively()) {
    throw IOException("Couldn't delete $installedAbout")
  }
  copyTree(about, installedAbout)
  return target
}

/** Where [installCompiler] puts the compiler in the plugin's directory. */
fun compilerPath(pluginDir: Path): Path = pluginDir.resolve("compiler/ktrapic-daemon.jar")

/**
 * Copies the compiler [jar] into the plugin's directory, and returns where it is. The copy is left
 * writable, though Bazel's outputs are read-only, so the next install can replace it.
 */
fun installCompiler(jar: Path, pluginDir: Path): Path {
  val target = compilerPath(pluginDir)
  target.parent.createDirectories()
  Files.copy(jar, target, REPLACE_EXISTING)
  target.toFile().setWritable(true)
  return target
}

/**
 * Whether [daemonJarPath], a `DaemonJarPath` setting, is the compiler [installCompiler] puts in
 * [pluginDir]. Paths are compared as the platform does, so on Windows case and a trailing separator
 * don't matter.
 */
fun isInstalledCompiler(daemonJarPath: String, pluginDir: Path): Boolean {
  val path =
      try {
        Paths.get(daemonJarPath)
      } catch (e: InvalidPathException) {
        return false
      }
  return path.toAbsolutePath().normalize() == compilerPath(pluginDir).toAbsolutePath().normalize()
}

/** Deletes the compiler [installCompiler] put in [pluginDir], and its directory if that's empty. */
fun removeCompiler(pluginDir: Path) {
  val jar = compilerPath(pluginDir)
  jar.deleteIfExists()
  val dir = jar.parent
  if (dir.isDirectory() && dir.listDirectoryEntries().isEmpty()) dir.deleteExisting()
}

/**
 * Sets `DaemonJarPath` in the [config] file's `[Development]` section to [jar] (or to nothing, if
 * null), keeping the rest of the file and its line endings. The file and the section are added if
 * they're missing.
 */
fun setDaemonJarPath(config: Path, jar: Path?) {
  val value = jar?.toAbsolutePath()?.toString()?.replace(File.separatorChar, '/') ?: ""
  val setting = "DaemonJarPath = $value"
  val text = if (config.exists()) config.readText() else ""
  val lines = splitLines(text)
  val section = developmentSection(lines)
  if (section == null) {
    if (lines.isNotEmpty() && lines.last().isNotBlank()) lines += ""
    lines += listOf("[Development]", "", setting)
  } else {
    val existing = section.firstOrNull { isDaemonJarPath(lines[it]) }
    if (existing != null) lines[existing] = setting else lines.add(section.first, setting)
  }
  val newline = if ("\r\n" in text) "\r\n" else "\n"
  config.parent.createDirectories()
  config.writeText(lines.joinToString(newline, postfix = newline))
}

/** The `DaemonJarPath` in the [config] file's `[Development]` section, or null if none or empty. */
fun daemonJarPath(config: Path): String? {
  if (!config.exists()) return null
  val lines = splitLines(config.readText())
  val section = developmentSection(lines) ?: return null
  return section
      .firstOrNull { isDaemonJarPath(lines[it]) }
      ?.let { lines[it].substringAfter('=').trim() }
      ?.takeIf { it.isNotEmpty() }
}

/** [text]'s lines, without their line endings or an empty last one. */
private fun splitLines(text: String): MutableList<String> =
    text.lines().toMutableList().apply { if (isNotEmpty() && last().isEmpty()) removeAt(lastIndex) }

/**
 * The indices of the lines after the `[Development]` header, up to the next section's, or null if
 * there's no such header. (The range is empty, starting after the header, if the section is.)
 */
private fun developmentSection(lines: List<String>): IntRange? {
  val header = lines.indexOfFirst { it.trim() == "[Development]" }
  if (header < 0) return null
  val end =
      (header + 1 until lines.size).firstOrNull { lines[it].trim().startsWith("[") } ?: lines.size
  return header + 1 until end
}

private fun isDaemonJarPath(line: String) =
    line.trim().substringBefore('=').trim() == "DaemonJarPath"

private fun copyTree(from: Path, to: Path) {
  Files.walk(from).use { paths ->
    for (path in paths) {
      val target = to.resolve(from.relativize(path).toString())
      if (path.isDirectory()) target.createDirectories()
      else Files.copy(path, target, REPLACE_EXISTING)
    }
  }
}
