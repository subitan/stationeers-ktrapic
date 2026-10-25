package ktrapic.backend

import ktrapic.ic10.Program
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

/** Compiles the IR of a wrapped script-style program to IC10. */
object Backend {
  /**
   * The program's IC10 code, with labels. Throws [CompileException] for a construct it cannot
   * compile and [TooManyRegistersException] when the program needs more than 16 registers.
   */
  fun compile(module: IrModuleFragment): Program {
    val translator = Translator()
    translator.translate(module)
    return Lowering.lower(translator.lines)
  }
}
