package ktrapic.cli

import java.io.File
import kotlin.system.exitProcess
import ktrapic.api.CompileOptions
import ktrapic.api.CompileResult
import ktrapic.api.Compiler
import ktrapic.ic10.Ic10Limits

const val VERSION = "0.0.0-dev"

/**
 * `ktrapic [--compact] [--register-calls] <program.ic10.kts>` prints the program's IC10 code, or
 * its errors (as `file:line:column: message`) with exit status 1. `--register-calls` passes every
 * function's arguments and result in registers rather than on the stack. With no arguments it
 * prints what it is.
 */
fun main(args: Array<String>) {
  if (args.isEmpty()) {
    println("KTrapIC $VERSION: constrained Kotlin to Stationeers IC10")
    println(
        "Target chip: ${Ic10Limits.MAX_LINES} lines, ${Ic10Limits.MAX_BYTES} bytes, " +
            "${Ic10Limits.REGISTERS} registers"
    )
    println("Usage: ktrapic [--compact] [--register-calls] <program.ic10.kts>")
    return
  }
  val compact = "--compact" in args
  val registerCalls = "--register-calls" in args
  val path = args.singleOrNull { it !in FLAGS } ?: usage()
  // `bazel run` starts in the runfiles tree; relative paths mean the caller's directory.
  val file = File(System.getenv("BUILD_WORKING_DIRECTORY") ?: ".").resolve(path)
  when (
      val result =
          Compiler.compile(
              file.readText(),
              CompileOptions(compact = compact, stackCalls = !registerCalls),
          )
  ) {
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

private fun usage(): Nothing {
  System.err.println("Usage: ktrapic [--compact] [--register-calls] <program.ic10.kts>")
  exitProcess(2)
}

private val FLAGS = setOf("--compact", "--register-calls")
