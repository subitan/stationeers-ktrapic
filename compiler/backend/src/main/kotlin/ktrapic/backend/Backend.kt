package ktrapic.backend

import org.jetbrains.kotlin.backend.common.extensions.DeclarationFinder
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

/** Compiles the IR of a wrapped script-style program to IC10. */
object Backend {
  /**
   * The program's IC10 code, with labels, and where each line comes from; [symbols] finds the SDK's
   * declarations. Throws [CompileException] for a construct it cannot compile and
   * [TooManyRegistersException] when the program needs more than 16 registers. With [stackCalls],
   * functions called with `jal` pass their arguments and result on the stack, unless marked
   * `@InRegisters`; without, in registers.
   */
  fun compile(
      module: IrModuleFragment,
      symbols: DeclarationFinder,
      stackCalls: Boolean = true,
  ): Lowered {
    val translator = Translator(symbols, stackCalls)
    translator.translate(module)
    return Lowering.lower(translator.lines)
  }
}
