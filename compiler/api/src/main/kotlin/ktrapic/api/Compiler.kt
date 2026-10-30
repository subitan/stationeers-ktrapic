package ktrapic.api

import java.io.File
import ktrapic.backend.Backend
import ktrapic.backend.CompileException
import ktrapic.backend.Lowered
import ktrapic.backend.StackCalled
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
    /**
     * Compile a function's call to itself in tail position (its value the function's result, or in
     * a `Unit` function its last statement) as a jump back to the top of the function, so it takes
     * no stack and can recurse without limit. Only functions called on the stack can call
     * themselves, so without [stackCalls] this has no effect. A program that would need more
     * registers than the chip has with its tail calls as loops, but not with them as calls, makes
     * every one of them a call, with a warning: then a deep tail recursion can overflow the stack
     * again. A program annotated `@file:CircuitHousingOnly` keeps values in memory to fit with its
     * tail calls as loops first, and makes them calls only if that is not enough.
     */
    val tailCalls: Boolean = true,
)

/**
 * A problem with the program, at a 1-based [line] and [column] of its source (0 if unknown).
 * [kotlin] is true for the Kotlin compiler's own diagnostics and syntax errors, which an editor's
 * Kotlin support reports too, and false for those KTrapIC adds.
 */
data class CompileError(
    val message: String,
    val line: Int,
    val column: Int,
    val kotlin: Boolean = false,
)

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
            // Suggesting @file:CircuitHousingOnly for a program out of registers takes a trial
            // allocation, so it is asked for only of the compile whose failure is reported.
            fun backend(tailCalls: Boolean, suggestHousing: Boolean) =
                Backend.compile(
                    ir.module,
                    ir.symbols,
                    options.stackCalls,
                    tailCalls,
                    suggestHousing,
                )
            val (lowered, fallback) =
                try {
                  backend(options.tailCalls, suggestHousing = !options.tailCalls) to null
                } catch (e: TooManyRegistersException) {
                  if (!options.tailCalls) throw e
                  // Tail calls are only an optimisation, so the program may make them as calls
                  // instead. If it is still out of registers then, the error reports what it
                  // needs that way: no option makes it need fewer. A program annotated
                  // @file:CircuitHousingOnly gets here only when even keeping values in memory
                  // left it out of registers with its tail calls as loops.
                  try {
                    backend(tailCalls = false, suggestHousing = true) to
                        CompileError(tailCallHint(e.needed), 0, 0)
                  } catch (retry: TooManyRegistersException) {
                    throw retry
                  } catch (_: CompileException) {
                    // The first failure is the one reported: compiling it again throws it with
                    // the suggestion. This is defensive: translation differs only in the tail
                    // loop, and no CompileException is known to depend on it, so this runs only
                    // if one ever does, and then just to re-attach the housing suggestion.
                    backend(tailCalls = true, suggestHousing = true)
                    throw e
                  }
                }
            // The 0-based line of the user's source that each line of the program comes from.
            val sourceLines =
                lowered.offsets.map { offset -> offset?.let { ir.position(it) }?.first?.minus(1) }
            Result.success(Compiled(lowered, sourceLines, listOfNotNull(fallback)))
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
    val (lowered, sourceLines, fallbacks) = compiled
    val labelled = lowered.program
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
    val sizeHints =
        if (size.lines > Ic10Limits.MAX_LINES) {
          val compactSaves =
              if (options.compact) 0
              else size.lines - ProgramSize.of(labelled.compacted().print()).lines
          listOf(CompileError(sizeHint(size.lines, compactSaves, lowered), 0, 0))
        } else {
          emptyList()
        }
    return CompileResult.Success(
        program,
        code,
        size.lines,
        size.bytes,
        registersUsed(program),
        warnings + fallbacks + sizeHints,
        sourceMap,
    )
  }

  /**
   * The lowered program, the 0-based source line each of its lines comes from, and the warnings
   * about how it was compiled.
   */
  private data class Compiled(
      val lowered: Lowered,
      val sourceLines: List<Int?>,
      val warnings: List<CompileError>,
  )

  /**
   * Why a program compiled with tail calls on makes them as calls: as loops it needs [needed]
   * registers, more than the chip has, and as calls few enough. Every tail call in the program
   * becomes a call, so a recursion that relied on the loop for its depth can overflow the stack.
   * Turning tail calls off compiles the same code, only without trying loops first or this warning.
   */
  private fun tailCallHint(needed: Int): String =
      "The program needs $needed registers with tail calls compiled as loops, over the chip's " +
          "${Ic10Limits.REGISTERS}; all its tail calls are made as ordinary stack calls instead, " +
          "so deep recursion can overflow the stack (turn tail calls off to compile them as calls " +
          "without this warning)"

  /**
   * That [lowered], [lines] long, is over the chip's limit, and why it could be shorter, if it can
   * say. Compact output would be [compactSaves] lines shorter (0 when it already is compact, or has
   * nothing to drop). Each call on the stack pushes and pops what passes in a register otherwise;
   * each read and write of a value kept in memory is a line of its own.
   */
  private fun sizeHint(lines: Int, compactSaves: Int, lowered: Lowered): String {
    val over = "The program is $lines lines, over the chip's ${Ic10Limits.MAX_LINES}"
    val compact =
        if (compactSaves > 0) {
          "compact output, which resolves labels to line numbers, saves $compactSaves lines"
        } else {
          null
        }
    val memory =
        if (lowered.memorySlots > 0) {
          "each read and write of a value kept in memory takes a line (get or put) of its own"
        } else {
          null
        }
    val stack = stackHint(lowered.stackCalled, lowered.hasCycle, compactSaves > 0)
    val reasons = listOfNotNull(compact, stack, memory)
    return if (reasons.isEmpty()) over else "$over: ${reasons.joinToString("; ")}"
  }

  /**
   * How the functions in [stackCalled] could save lines, or null if none is called on the stack.
   * Only those that aren't in a cycle of calls can pass in registers, as a cycle needs the stack.
   * Turning stack calls off works only when the program declares no cycle at all ([hasCycle]),
   * called or not. When compact output would be shorter ([compactShortens]), it is the other way to
   * fit a program whose stack calls can't move to registers.
   */
  private fun stackHint(
      stackCalled: List<StackCalled>,
      hasCycle: Boolean,
      compactShortens: Boolean,
  ): String? {
    if (stackCalled.isEmpty()) return null
    val (recursive, plain) = stackCalled.partition { it.recursive }
    val names = plain.joinToString { it.name }
    return when {
      !hasCycle ->
          "passing functions' arguments in registers saves lines (turn stack calls off, or mark " +
              "the most-called functions @InRegisters)"
      plain.isEmpty() ->
          "the functions called on the stack (${recursive.joinToString { it.name }}) are in a " +
              "cycle of calls, so they can't pass their arguments in registers to save lines, " +
              "and the program must be shortened" +
              (if (compactShortens) " or compiled compact" else "")
      else ->
          "passing functions' arguments in registers saves lines (mark " +
              (if (plain.size == 1) names else "the most-called of $names") +
              " @InRegisters; functions in a cycle of calls must pass theirs on the stack)"
    }
  }

  private fun registersUsed(program: Program): Int =
      program.lines
          .filterIsInstance<Line.Instruction>()
          .flatMap { it.operands }
          .filterIsInstance<Register>()
          .filter { it != Register.SP && it != Register.RA }
          .distinct()
          .size

  private fun Diagnostic.toError() = CompileError(message, line, column, kotlin)

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
