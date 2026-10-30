package ktrapic.backend

import org.jetbrains.kotlin.backend.common.extensions.DeclarationFinder
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.name.FqName

/** Compiles the IR of a wrapped script-style program to IC10. */
object Backend {
  /**
   * The program's IC10 code, with labels, and where each line comes from; [symbols] finds the SDK's
   * declarations. Throws [CompileException] for a construct it cannot compile and
   * [TooManyRegistersException] when the program needs more than 16 registers. With [stackCalls],
   * functions called with `jal` pass their arguments and result on the stack, unless marked
   * `@InRegisters`; without, in registers. With [tailCalls], such a function's calls to itself in
   * tail position loop back rather than call.
   *
   * A program annotated `@file:CircuitHousingOnly` keeps the values that don't fit in registers in
   * the chip's stack memory (see [Spilling]). For any other, with [suggestHousing], the exception
   * suggests the annotation when keeping values in memory would make the program fit in registers,
   * which takes a second, trial, allocation; without, it suggests nothing, and saves that trial for
   * a caller that may not report this failure.
   */
  fun compile(
      module: IrModuleFragment,
      symbols: DeclarationFinder,
      stackCalls: Boolean = true,
      tailCalls: Boolean = true,
      suggestHousing: Boolean = true,
  ): Lowered {
    val translator = Translator(symbols, stackCalls, tailCalls)
    translator.translate(module)
    val housingOnly = module.files.any { it.hasAnnotation(CIRCUIT_HOUSING_ONLY) }
    val lowered =
        try {
          Lowering.lower(translator.lines, translator.stackFunctions, spill = housingOnly)
        } catch (e: TooManyRegistersException) {
          if (housingOnly || !suggestHousing) throw e
          // Suggest the annotation only if keeping values in memory would make the program fit in
          // registers.
          try {
            Lowering.lower(translator.lines, translator.stackFunctions, spill = true)
          } catch (spilled: TooManyRegistersException) {
            throw TooManyRegistersException(e.needed, spilled.hint)
          }
          throw TooManyRegistersException(e.needed, HOUSING_HINT)
        }
    return lowered.copy(hasCycle = translator.hasCycle)
  }

  /**
   * The compile options [module]'s program chooses with file annotations: `@file:Compact`,
   * `@file:InRegisters` and `@file:NoTailCalls`. Each is a departure from the default.
   */
  fun fileOptions(module: IrModuleFragment): FileOptions =
      FileOptions(
          compact = module.files.any { it.hasAnnotation(COMPACT) },
          inRegisters = module.files.any { it.hasAnnotation(IN_REGISTERS) },
          noTailCalls = module.files.any { it.hasAnnotation(NO_TAIL_CALLS) },
      )

  private val CIRCUIT_HOUSING_ONLY = FqName("ktrapic.CircuitHousingOnly")
  private val COMPACT = FqName("ktrapic.Compact")
  private val IN_REGISTERS = FqName("ktrapic.InRegisters")
  private val NO_TAIL_CALLS = FqName("ktrapic.NoTailCalls")

  private const val HOUSING_HINT =
      "if the chip will run in an IC housing, a rocket circuit housing or a suit, add " +
          "@file:CircuitHousingOnly to keep the values that don't fit in its stack memory"
}

/**
 * The file annotations that choose how a program compiles: [compact] output, every function's
 * arguments [inRegisters], and [noTailCalls], which makes tail calls calls rather than loops.
 */
data class FileOptions(
    val compact: Boolean = false,
    val inRegisters: Boolean = false,
    val noTailCalls: Boolean = false,
)
