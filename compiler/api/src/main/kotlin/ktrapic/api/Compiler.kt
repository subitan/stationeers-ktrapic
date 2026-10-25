package ktrapic.api

import java.io.File
import ktrapic.backend.Backend
import ktrapic.backend.CompileException
import ktrapic.backend.TooManyRegistersException
import ktrapic.frontend.Diagnostic
import ktrapic.frontend.FrontendResult
import ktrapic.frontend.K2Frontend
import ktrapic.ic10.Program
import ktrapic.ic10.ProgramSize
import ktrapic.ic10.Register

/** How to compile a program. */
data class CompileOptions(
    /** Resolve labels to line numbers and drop them, to save lines on the chip. */
    val compact: Boolean = false
)

/** A problem with the program, at a 1-based [line] and [column] of its source (0 if unknown). */
data class CompileError(val message: String, val line: Int, val column: Int)

sealed interface CompileResult {
  /**
   * The compiled program. [code] is what goes on the chip; [lines], [bytes] and [registers] are
   * measured as the IC10 Editor measures them.
   */
  data class Success(
      val program: Program,
      val code: String,
      val lines: Int,
      val bytes: Int,
      val registers: Int,
      val warnings: List<CompileError>,
  ) : CompileResult

  data class Failure(val errors: List<CompileError>) : CompileResult
}

/** Compiles KTrapIC programs: script-style Kotlin, written against the `ktrapic` API, to IC10. */
object Compiler {
  fun compile(source: String, options: CompileOptions = CompileOptions()): CompileResult {
    val result =
        K2Frontend.compile(source, listOf(runtimeJar()), listOf("ktrapic.*")) { ir ->
          try {
            Result.success(Backend.compile(ir.module))
          } catch (e: CompileException) {
            val (line, column) = ir.position(e.offset) ?: (0 to 0)
            Result.failure(CompileFailure(CompileError(e.message ?: "error", line, column)))
          } catch (e: TooManyRegistersException) {
            Result.failure(CompileFailure(CompileError(e.message ?: "error", 0, 0)))
          }
        }
    val diagnostics = result.diagnostics.map { it.toError() }
    val errors =
        result.diagnostics.filter { it.severity == Diagnostic.Severity.ERROR }.map { it.toError() }
    val warnings =
        result.diagnostics
            .filter { it.severity == Diagnostic.Severity.WARNING }
            .map { it.toError() }
    if (result !is FrontendResult.Success)
        return CompileResult.Failure(errors.ifEmpty { diagnostics })
    val compiled =
        result.value.getOrElse {
          return CompileResult.Failure(listOf((it as CompileFailure).error))
        }
    val program = if (options.compact) compiled.compacted() else compiled
    val code = program.print()
    val size = ProgramSize.of(code)
    return CompileResult.Success(
        program,
        code,
        size.lines,
        size.bytes,
        registersUsed(program),
        warnings,
    )
  }

  private fun registersUsed(program: Program): Int =
      program.lines
          .filterIsInstance<ktrapic.ic10.Line.Instruction>()
          .flatMap { it.operands }
          .filterIsInstance<Register>()
          .filter { it != Register.SP && it != Register.RA }
          .distinct()
          .size

  private fun Diagnostic.toError() = CompileError(message, line, column)

  private class CompileFailure(val error: CompileError) : Exception(error.message)

  /** The runtime API's jar, which programs compile against. */
  private fun runtimeJar(): File =
      File(ktrapic.Device::class.java.protectionDomain.codeSource.location.toURI())
}
