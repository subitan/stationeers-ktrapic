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

  /** The functions [function] calls, directly. */
  fun callees(function: IrSimpleFunction): Set<IrSimpleFunction> =
      calls[function].orEmpty().mapTo(linkedSetOf()) { it.symbol.owner }

  /** [roots] and the functions they call, directly or through others. */
  fun reachable(roots: Collection<IrSimpleFunction>): Set<IrSimpleFunction> {
    val seen = linkedSetOf<IrSimpleFunction>()
    val pending = ArrayDeque(roots)
    while (pending.isNotEmpty()) {
      val function = pending.removeFirst()
      if (seen.add(function)) pending += callees(function)
    }
    return seen
  }

  /**
   * The groups of functions that call themselves through each other (each a strongly connected
   * component with a cycle: one function calling itself, or several that reach each other), in
   * declaration order.
   */
  val cycles: List<Set<IrSimpleFunction>> by lazy {
    val reaches = functions.associateWith { reachable(callees(it)) }
    val grouped = mutableSetOf<IrSimpleFunction>()
    buildList {
      for (function in functions) {
        if (function in grouped || function !in reaches.getValue(function)) continue
        val group =
            functions.filterTo(linkedSetOf()) {
              it in reaches.getValue(function) && function in reaches.getValue(it)
            }
        grouped += group
        add(group)
      }
    }
  }

  /** The functions that call themselves, directly or through others. */
  val recursive: Set<IrSimpleFunction> by lazy { cycles.flatten().toSet() }

  /**
   * The calls of a shortest cycle from [function] back to itself, in order, through functions of
   * its group in [cycles].
   */
  fun cycleThrough(function: IrSimpleFunction): List<IrCall> {
    val group = cycles.single { function in it }
    // The call that first reached each function, searching breadth-first from [function].
    val via = mutableMapOf<IrSimpleFunction, IrCall>()
    val pending = ArrayDeque(listOf(function))
    while (pending.isNotEmpty()) {
      val caller = pending.removeFirst()
      for (call in calls[caller].orEmpty()) {
        val callee = call.symbol.owner
        if (callee !in group || callee in via) continue
        via[callee] = call
        if (callee == function) {
          val path = mutableListOf(call)
          var at = caller
          while (at != function) {
            val into = via.getValue(at)
            path.add(0, into)
            at = callerOf.getValue(into)
          }
          return path
        }
        pending += callee
      }
    }
    error("${function.name} is in no cycle")
  }

  /** The function each call is made from. */
  private val callerOf: Map<IrCall, IrSimpleFunction> by lazy {
    calls.entries
        .filter { it.key != null }
        .flatMap { (caller, list) -> list.map { it to caller!! } }
        .toMap()
  }
}
