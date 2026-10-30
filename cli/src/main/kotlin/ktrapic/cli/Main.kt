package ktrapic.cli

import java.io.File
import kotlin.system.exitProcess
import ktrapic.api.CompileResult
import ktrapic.api.Compiler
import ktrapic.ic10.Ic10Limits

const val VERSION = "0.0.0-dev"

/**
 * `ktrapic <program.ic10.kts>` prints the program's IC10 code, or its errors (as `file:line:column:
 * message`) with exit status 1. The program says how it compiles, with file annotations such as
 * `@file:Compact`. With no arguments it prints what it is.
 */
fun main(args: Array<String>) {
  if (args.isEmpty()) {
    println("KTrapIC $VERSION: constrained Kotlin to Stationeers IC10")
    println(
        "Target chip: ${Ic10Limits.MAX_LINES} lines, ${Ic10Limits.MAX_BYTES} bytes, " +
            "${Ic10Limits.REGISTERS} registers"
    )
    println(USAGE)
    return
  }
  val path = args.singleOrNull()?.takeUnless { it.startsWith("--") } ?: usage()
  // `bazel run` starts in the runfiles tree; relative paths mean the caller's directory.
  val file = File(System.getenv("BUILD_WORKING_DIRECTORY") ?: ".").resolve(path)
  when (val result = Compiler.compile(file.readText())) {
    is CompileResult.Success -> {
      println(result.code)
      System.err.println(
          "${result.lines} lines, ${result.bytes} bytes, ${result.registers} registers"
      )
    }
    is CompileResult.Failure -> {
      for (error in result.errors) {
        System.err.println("$path:${error.line}:${error.column}: ${error.message}")
      }
      exitProcess(1)
    }
  }
}

private const val USAGE =
    "Usage: ktrapic <program.ic10.kts> (the program chooses compact output, calls in registers " +
        "and tail calls with @file:Compact, @file:InRegisters and @file:NoTailCalls)"

private fun usage(): Nothing {
  System.err.println(USAGE)
  exitProcess(2)
}
