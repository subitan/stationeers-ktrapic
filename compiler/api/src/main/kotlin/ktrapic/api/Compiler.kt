package ktrapic.api

import java.io.File
import ktrapic.backend.Backend
import ktrapic.backend.CompileException
import ktrapic.backend.TooManyRegistersException
import ktrapic.frontend.Completion
import ktrapic.frontend.Diagnostic
import ktrapic.frontend.FrontendResult
import ktrapic.frontend.Hover
import ktrapic.frontend.K2Completion
import ktrapic.frontend.K2Frontend
import ktrapic.frontend.K2Hover
import ktrapic.frontend.SignatureHelp
import ktrapic.ic10.Ic10Limits
import ktrapic.ic10.Line
import ktrapic.ic10.Program
import ktrapic.ic10.ProgramSize
import ktrapic.ic10.Register

/** How to compile a program. */
data class CompileOptions(
    /** Resolve labels to line numbers and drop them, to save lines on the chip. */
    val compact: Boolean = false,
    /**
     * Pass the arguments and result of functions called with `jal` on the chip's stack, saving the
     * caller's live registers around each call, so functions may be recursive. A function marked
     * `@InRegisters` passes them in registers all the same. False passes every function's in
     * registers.
     */
    val stackCalls: Boolean = true,
)

/** A problem with the program, at a 1-based [line] and [column] of its source (0 if unknown). */
data class CompileError(val message: String, val line: Int, val column: Int)

sealed interface CompileResult {
  /**
   * The compiled program. [code] is what goes on the chip; [lines], [bytes] and [registers] are
   * measured as the IC10 Editor measures them. [sourceMap] maps each 0-based line of the source to
   * the 0-based lines of [code] that it compiled to, for the lines that compiled to any.
   */
  data class Success(
      val program: Program,
      val code: String,
      val lines: Int,
      val bytes: Int,
      val registers: Int,
      val warnings: List<CompileError>,
      val sourceMap: Map<Int, List<Int>> = emptyMap(),
  ) : CompileResult

  data class Failure(val errors: List<CompileError>) : CompileResult
}

/** Compiles KTrapIC programs: script-style Kotlin, written against the `ktrapic` API, to IC10. */
object Compiler {
  fun compile(source: String, options: CompileOptions = CompileOptions()): CompileResult {
    val result =
        K2Frontend.compile(source, listOf(sdkJar()), listOf("ktrapic.*")) { ir ->
          try {
            val lowered = Backend.compile(ir.module, ir.symbols, options.stackCalls)
            // The 0-based line of the user's source that each line of the program comes from.
            val sourceLines =
                lowered.offsets.map { offset -> offset?.let { ir.position(it) }?.first?.minus(1) }
            Result.success(Triple(lowered.program, sourceLines, lowered.stackCalls))
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
    val (labelled, sourceLines, stackCalls) = compiled
    val program = if (options.compact) labelled.compacted() else labelled
    // Compacting keeps exactly the instructions, in order, so their source lines go with them.
    val printedSourceLines =
        if (options.compact) {
          sourceLines.filterIndexed { index, _ -> labelled.lines[index] is Line.Instruction }
        } else {
          sourceLines
        }
    val sourceMap =
        printedSourceLines
            .withIndex()
            .mapNotNull { (line, source) -> source?.let { it to line } }
            .groupBy({ it.first }, { it.second })
    val code = program.print()
    val size = ProgramSize.of(code)
    // Each call on the stack pushes and pops what passes in a register otherwise.
    val sizeHint =
        if (stackCalls && size.lines > Ic10Limits.MAX_LINES) {
          listOf(
              CompileError(
                  "The program is ${size.lines} lines, over the chip's ${Ic10Limits.MAX_LINES}: " +
                      "passing functions' arguments in registers saves lines (turn stack calls " +
                      "off, or mark the most-called functions @InRegisters)",
                  0,
                  0,
              )
          )
        } else {
          emptyList()
        }
    return CompileResult.Success(
        program,
        code,
        size.lines,
        size.bytes,
        registersUsed(program),
        warnings + sizeHint,
        sourceMap,
    )
  }

  private fun registersUsed(program: Program): Int =
      program.lines
          .filterIsInstance<Line.Instruction>()
          .flatMap { it.operands }
          .filterIsInstance<Register>()
          .filter { it != Register.SP && it != Register.RA }
          .distinct()
          .size

  private fun Diagnostic.toError() = CompileError(message, line, column)

  private class CompileFailure(val error: CompileError) : Exception(error.message)

  /**
   * What could be written at [offset] (a `String` index) in [source], as a program compiles it:
   * against the SDK, with `ktrapic.*` imported. See [K2Completion].
   */
  fun complete(source: String, offset: Int): List<Completion> =
      K2Completion.complete(source, offset, listOf(sdkJar()), listOf("ktrapic.*"))

  /** What the name at [offset] in [source] is, as a program compiles it. See [K2Hover]. */
  fun hover(source: String, offset: Int): Hover? =
      K2Hover.hover(source, offset, listOf(sdkJar()), listOf("ktrapic.*"))

  /**
   * The functions the call around [offset] in [source] could be to, as a program compiles it. See
   * [K2Hover].
   */
  fun signatureHelp(source: String, offset: Int): SignatureHelp? =
      K2Hover.signatureHelp(source, offset, listOf(sdkJar()), listOf("ktrapic.*"))

  /** The SDK's jar, which programs compile against. */
  private fun sdkJar(): File =
      File(ktrapic.Device::class.java.protectionDomain.codeSource.location.toURI())
}
