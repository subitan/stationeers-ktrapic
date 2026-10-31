package ktrapic.install

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readLines
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
 * there, and returns that directory.
 */
fun installPlugin(dll: Path, about: Path, modsDir: Path): Path {
  val target = modsDir.resolve("KTrapIC").createDirectories()
  Files.copy(dll, target.resolve(dll.name), REPLACE_EXISTING)
  copyTree(about, target.resolve("About"))
  return target
}

/**
 * Copies the compiler [jar] into the plugin's directory, and returns where it is. The copy is left
 * writable, though Bazel's outputs are read-only, so the next install can replace it.
 */
fun installCompiler(jar: Path, pluginDir: Path): Path {
  val target = pluginDir.resolve("compiler").createDirectories().resolve("ktrapic-daemon.jar")
  Files.copy(jar, target, REPLACE_EXISTING)
  target.toFile().setWritable(true)
  return target
}

/**
 * Sets `DaemonJarPath` in the [config] file's `[Development]` section to [jar] (or to nothing, if
 * null), keeping the rest of the file. The file and the section are added if they're missing.
 */
fun setDaemonJarPath(config: Path, jar: Path?) {
  val value = jar?.toAbsolutePath()?.toString()?.replace(File.separatorChar, '/') ?: ""
  val setting = "DaemonJarPath = $value"
  val lines = if (config.exists()) config.readLines().toMutableList() else mutableListOf()
  val section = lines.indexOfFirst { it.trim() == "[Development]" }
  if (section < 0) {
    if (lines.isNotEmpty() && lines.last().isNotBlank()) lines += ""
    lines += listOf("[Development]", "", setting)
  } else {
    val end =
        (section + 1 until lines.size).firstOrNull { lines[it].trim().startsWith("[") }
            ?: lines.size
    val existing = (section + 1 until end).firstOrNull { isDaemonJarPath(lines[it]) }
    if (existing != null) lines[existing] = setting else lines.add(section + 1, setting)
  }
  config.parent.createDirectories()
  config.writeText(lines.joinToString("\n", postfix = "\n"))
}

/** The [config] file's `DaemonJarPath`, or null if it's missing or empty. */
fun daemonJarPath(config: Path): String? {
  if (!config.exists()) return null
  return config.readLines().firstOrNull(::isDaemonJarPath)?.substringAfter('=')?.trim()?.takeIf {
    it.isNotEmpty()
  }
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
