package ktrapic.install

import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.system.exitProcess

/**
 * `bazel run //mod:install -- <mods-dir> [--local-compiler]` builds the IC10 Editor plugin (a Debug
 * build, which downloads the compiler from the rolling `dev` release) with `dotnet` and installs it
 * into StationeersLaunchPad's local mods directory. With `--local-compiler` it also installs this
 * checkout's compiler beside it and points the plugin's `DaemonJarPath` setting at it; without, it
 * clears a `DaemonJarPath` that points at a compiler this installed before.
 */
fun main(args: Array<String>) {
  val workspace =
      System.getenv("BUILD_WORKSPACE_DIRECTORY")?.let { Paths.get(it) }
          ?: fail("Run this with `bazel run //mod:install -- <mods-dir>`.")
  val request =
      try {
        parseArgs(args.toList(), Paths.get(System.getenv("BUILD_WORKING_DIRECTORY") ?: ".")).also {
          checkModsDir(it.modsDir)
        }
      } catch (e: UsageError) {
        System.err.println(e.message)
        System.err.println(USAGE)
        exitProcess(2)
      }

  val project = workspace.resolve("mod/KTrapIC.csproj")
  println("Building the plugin (dotnet build ${workspace.relativize(project)})")
  val build = listOf("dotnet", "build", project.toString(), "-c", "Debug", "-nologo", "-v:minimal")
  if (run(build) != 0) fail("The plugin's build failed.")
  val pluginDir =
      try {
        installPlugin(
            workspace.resolve("mod/bin/Debug/net48/KTrapIC.dll"),
            workspace.resolve("mod/About"),
            request.modsDir,
        )
      } catch (e: IOException) {
        fail("Couldn't copy the plugin into ${request.modsDir} (is the game running?): $e")
      }
  println("Installed the plugin in $pluginDir")

  val gameDir = gameDirectory(project)
  val config = gameDir?.resolve(CONFIG_FILE)
  if (request.localCompiler) {
    if (gameDir == null || !gameDir.resolve("BepInEx").isDirectory()) {
      fail(
          "To set DaemonJarPath, the game's directory, with BepInEx installed in it, is needed: " +
              "set StationeersDirectory in mod/Local.Developer.props or STATIONEERS_DIR" +
              (gameDir?.let { " (it is now $it)" } ?: "") +
              "."
      )
    }
    // `bazel run` starts in the runfiles tree, where the daemon jar is.
    val daemonJar = Paths.get(System.getProperty("ktrapic.daemonJar")).toAbsolutePath()
    val jar = installCompiler(daemonJar, pluginDir)
    setDaemonJarPath(config!!, jar)
    println("Installed this checkout's compiler as $jar, and set DaemonJarPath to it in $config")
  } else if (config != null) {
    val current = daemonJarPath(config)
    val ours = pluginDir.resolve("compiler/ktrapic-daemon.jar")
    if (current != null && Paths.get(current).toAbsolutePath() == ours.toAbsolutePath()) {
      setDaemonJarPath(config, null)
      println("Cleared DaemonJarPath in $config, so the plugin downloads the release's compiler")
    } else if (current != null) {
      println("Note: DaemonJarPath in $config is $current, so the plugin compiles with that jar")
    }
  }
}

/** The game's directory, as the plugin's build finds it, or null if it doesn't. */
private fun gameDirectory(project: Path): Path? {
  val output = StringBuilder()
  val status =
      run(
          listOf(
              "dotnet",
              "msbuild",
              project.toString(),
              "-nologo",
              "-getProperty:StationeersDirectory",
          )
      ) {
        output.append(it)
      }
  val dir = output.toString().trim()
  return if (status == 0 && dir.isNotEmpty()) Paths.get(dir).takeIf { it.exists() } else null
}

/** Runs [command], its output to ours, or to [capture] if given, and returns its exit status. */
private fun run(command: List<String>, capture: ((String) -> Unit)? = null): Int {
  val process =
      try {
        ProcessBuilder(command)
            .apply {
              if (capture == null) inheritIO() else redirectError(ProcessBuilder.Redirect.INHERIT)
            }
            .start()
      } catch (e: IOException) {
        fail("Couldn't run ${command.first()} (is the .NET SDK, 10 or later, on the PATH?): $e")
      }
  capture?.invoke(process.inputStream.bufferedReader().readText())
  return process.waitFor()
}

private fun fail(message: String): Nothing {
  System.err.println(message)
  exitProcess(1)
}
