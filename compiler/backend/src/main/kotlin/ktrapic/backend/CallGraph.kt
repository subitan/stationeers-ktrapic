@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package ktrapic.backend

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid

/** Which of a program's own functions call which, and how often each is called. */
internal class CallGraph(program: IrClass) {
  /** The program's own functions, in declaration order. */
  val functions: List<IrSimpleFunction> =
      program.declarations.filterIsInstance<IrSimpleFunction>().filter {
        it.origin != IrDeclarationOrigin.FAKE_OVERRIDE
      }

  private val calls = mutableMapOf<IrSimpleFunction?, MutableList<IrCall>>()

  init {
    for (declaration in program.declarations) {
      val caller = declaration as? IrSimpleFunction
      if (caller != null && caller !in functions) continue
      declaration.acceptChildrenVoid(
          object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

            override fun visitCall(expression: IrCall) {
              if (expression.symbol.owner in functions) {
                calls.getOrPut(caller) { mutableListOf() } += expression
              }
              super.visitCall(expression)
            }
          }
      )
    }
  }

  /** How many calls to [function] the program's code makes, wherever they are. */
  fun callCount(function: IrSimpleFunction): Int =
      calls.values.sumOf { list -> list.count { it.symbol.owner == function } }

  /** A call that makes a function call itself, directly or through others, if there is one. */
  fun recursiveCall(): IrCall? {
    val done = mutableSetOf<IrSimpleFunction>()
    val path = mutableListOf<IrSimpleFunction>()
    fun visit(function: IrSimpleFunction): IrCall? {
      if (function in done) return null
      path += function
      for (call in calls[function].orEmpty()) {
        val callee = call.symbol.owner
        if (callee in path) return call
        visit(callee)?.let {
          return it
        }
      }
      path.removeAt(path.lastIndex)
      done += function
      return null
    }
    return functions.firstNotNullOfOrNull { visit(it) }
  }
}
