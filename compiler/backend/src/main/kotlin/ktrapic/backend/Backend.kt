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
   * the chip's stack memory (see [Spilling]); for any other, the exception suggests that.
   */
  fun compile(
      module: IrModuleFragment,
      symbols: DeclarationFinder,
      stackCalls: Boolean = true,
      tailCalls: Boolean = true,
  ): Lowered {
    val translator = Translator(symbols, stackCalls, tailCalls)
    translator.translate(module)
    val housingOnly = module.files.any { it.hasAnnotation(CIRCUIT_HOUSING_ONLY) }
    val lowered =
        try {
          Lowering.lower(translator.lines, translator.stackFunctions, spill = housingOnly)
        } catch (e: TooManyRegistersException) {
          if (housingOnly) throw e
          throw TooManyRegistersException(e.needed, HOUSING_HINT)
        }
    return lowered.copy(hasCycle = translator.hasCycle)
  }

  private val CIRCUIT_HOUSING_ONLY = FqName("ktrapic.CircuitHousingOnly")

  private const val HOUSING_HINT =
      "if the chip will run in an IC housing, a rocket circuit housing or a suit, add " +
          "@file:CircuitHousingOnly to keep the values that don't fit in its stack memory"
}
