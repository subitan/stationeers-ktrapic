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
      Writes, from runtime/data, the runtime's generated sources, the in-game plugin's list of
      their names, and the device reference docs/DEVICES.md."""

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
      DataFiles.modNames(workspace).writeText(DataFiles.generateModNames(workspace, files))
      println("Wrote ${DataFiles.modNames(workspace)}")
      DataFiles.deviceReference(workspace)
          .writeText(DataFiles.generateDeviceReference(workspace, files))
      println("Wrote ${DataFiles.deviceReference(workspace)}")
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

  fun modNames(root: File) = root.resolve(ModNamesGenerator.PATH)

  fun deviceReference(root: File) = root.resolve(DeviceReferenceGenerator.PATH)

  /** The generated sources for the data under [root]. */
  fun generate(root: File): Map<String, String> =
      RuntimeGenerator.generate(
          LogicEnums.fromJson(enums(root).readText()),
          Typing.fromJson(typing(root).readText()),
          Prefabs.fromJson(prefabs(root).readText()),
          Grouping.fromJson(grouping(root).readText()),
      )

  /** The in-game plugin's list of the names in [generated], the generated sources for [root]. */
  fun generateModNames(root: File, generated: Map<String, String>): String =
      ModNamesGenerator.generate(generated, LogicEnums.fromJson(enums(root).readText()))

  /** The device reference for [generated], the generated sources for [root]. */
  fun generateDeviceReference(root: File, generated: Map<String, String>): String =
      DeviceReferenceGenerator.generate(
          generated.getValue("GeneratedPrefabs.kt"),
          LogicEnums.fromJson(enums(root).readText()),
          Typing.fromJson(typing(root).readText()),
      )
}
