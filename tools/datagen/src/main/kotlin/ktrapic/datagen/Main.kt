package ktrapic.datagen

import java.io.File
import kotlin.system.exitProcess

private const val USAGE =
    """usage:
  datagen import-pytrapic <types_generated.py> <source description>
      Writes runtime/data/logic-enums.json from PyTrapIC's generated types.
  datagen import-pytrapic-structures <structures_generated.py> <source description>
      Writes runtime/data/prefabs.json from PyTrapIC's generated structures.
  datagen generate
      Writes the runtime's generated sources from runtime/data."""

/**
 * Maintains the runtime API's data and generated sources. Paths are relative to the workspace root
 * (`bazel run` sets BUILD_WORKSPACE_DIRECTORY), and arguments naming input files are resolved
 * against the directory `bazel run` was started from.
 */
fun main(args: Array<String>) {
  val workspace = File(System.getenv("BUILD_WORKSPACE_DIRECTORY") ?: ".")
  val workingDir = File(System.getenv("BUILD_WORKING_DIRECTORY") ?: ".")
  when (args.firstOrNull()) {
    "import-pytrapic" -> {
      if (args.size != 3) usage()
      val input = workingDir.resolve(args[1])
      val enums = PyTrapicImport.enums(input.readText(), args[2])
      DataFiles.enums(workspace).writeText(enums.toJson())
      println("Wrote ${enums.enums.size} enums to ${DataFiles.enums(workspace)}")
    }
    "import-pytrapic-structures" -> {
      if (args.size != 3) usage()
      val input = workingDir.resolve(args[1])
      val prefabs = PyTrapicStructures.prefabs(input.readText(), args[2])
      DataFiles.prefabs(workspace).writeText(prefabs.toJson())
      println("Wrote ${prefabs.prefabs.size} prefabs to ${DataFiles.prefabs(workspace)}")
    }
    "generate" -> {
      if (args.size != 1) usage()
      val files = DataFiles.generate(workspace)
      val outDir = DataFiles.generatedDir(workspace)
      for ((name, text) in files) outDir.resolve(name).writeText(text)
      println("Wrote ${files.keys.joinToString()} to $outDir")
    }
    else -> usage()
  }
}

private fun usage(): Nothing {
  System.err.println(USAGE)
  exitProcess(2)
}

/** Where the runtime's data and generated sources live, relative to a workspace root. */
object DataFiles {
  fun enums(root: File) = root.resolve("runtime/data/logic-enums.json")

  fun typing(root: File) = root.resolve("runtime/data/typing.json")

  fun prefabs(root: File) = root.resolve("runtime/data/prefabs.json")

  fun grouping(root: File) = root.resolve("runtime/data/grouping.json")

  fun generatedDir(root: File) = root.resolve("runtime/src/main/kotlin/ktrapic")

  /** The generated sources for the data under [root]. */
  fun generate(root: File): Map<String, String> =
      RuntimeGenerator.generate(
          LogicEnums.fromJson(enums(root).readText()),
          Typing.fromJson(typing(root).readText()),
          Prefabs.fromJson(prefabs(root).readText()),
          Grouping.fromJson(grouping(root).readText()),
      )
}
