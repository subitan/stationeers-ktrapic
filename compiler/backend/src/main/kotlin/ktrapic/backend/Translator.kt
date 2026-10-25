// The backend reads finished IR, after fir2ir, so symbol owners are always bound.
@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package ktrapic.backend

import ktrapic.frontend.PROGRAM_OBJECT
import ktrapic.ic10.Device
import ktrapic.ic10.LabelRef
import ktrapic.ic10.Name
import ktrapic.ic10.Number
import ktrapic.ic10.Operand
import ktrapic.ic10.StringConstant
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrAnonymousInitializer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrBreak
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstKind
import org.jetbrains.kotlin.ir.expressions.IrContainerExpression
import org.jetbrains.kotlin.ir.expressions.IrContinue
import org.jetbrains.kotlin.ir.expressions.IrDoWhileLoop
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.expressions.IrWhileLoop
import org.jetbrains.kotlin.ir.symbols.IrPropertySymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.isBoolean
import org.jetbrains.kotlin.ir.types.isDouble
import org.jetbrains.kotlin.ir.types.isInt
import org.jetbrains.kotlin.ir.types.isNothing
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentClassOrNull

/** A construct the backend cannot compile, at [offset] in the wrapped source (-1 if unknown). */
class CompileException(val offset: Int, message: String) : Exception(message) {
  constructor(element: IrElement, message: String) : this(element.startOffset, message)
}

/**
 * What an expression evaluates to: a constant, a register, a device, or nothing (`Unit` and
 * `Nothing`, and statements).
 */
internal sealed interface Value

/** A number known at compile time; [operand] is how it prints (`HASH("x")` keeps its text). */
internal data class Const(val number: Double, val operand: Operand = Number(number)) : Value

internal data class Reg(val reg: VReg) : Value

internal data class Dev(val device: Device) : Value

internal data object NoValue : Value

/**
 * Translates the IR of a wrapped script-style program into [TirLine]s: the `Program` object's
 * property initializers and `init` blocks, in source order.
 */
internal class Translator {
  val lines = mutableListOf<TirLine>()
  private var vregs = 0
  private val labelCounts = mutableMapOf<String, Int>()
  private val variables = mutableMapOf<IrValueDeclaration, Value>()
  private val properties = mutableMapOf<IrPropertySymbol, Value>()
  private val loops = mutableMapOf<IrLoop, Pair<String, String>>()

  fun translate(module: IrModuleFragment) {
    val program =
        module.files
            .flatMap { it.declarations }
            .filterIsInstance<IrClass>()
            .singleOrNull { it.name.asString() == PROGRAM_OBJECT } ?: return
    for (declaration in program.declarations) {
      when (declaration) {
        is IrProperty -> property(declaration)
        is IrAnonymousInitializer -> statement(declaration.body)
        is IrConstructor -> {}
        // Functions cost nothing until called; calls to them are reported where they are made.
        is IrSimpleFunction -> {}
        is IrClass -> unsupported(declaration, "classes, interfaces and objects")
        else -> unsupported(declaration, "this declaration")
      }
    }
  }

  private fun property(property: IrProperty) {
    val getter = property.getter
    if (getter != null && getter.origin != IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR) {
      unsupported(property, "properties with custom accessors")
    }
    val initializer =
        property.backingField?.initializer?.expression
            ?: unsupported(property, "properties without an initializer")
    val value = value(initializer)
    properties[property.symbol] =
        when {
          value is Dev && !property.isVar -> value
          value is Dev -> fail(property, "a device can only be held in a val")
          value is Const && !property.isVar -> value
          else -> Reg(newVReg(property.name.asString())).also { move(it.reg, value) }
        }
  }

  // Statements and expressions.

  private fun statement(element: IrElement) {
    when (element) {
      is IrBlockBody -> element.statements.forEach { statement(it) }
      is IrVariable -> variable(element)
      is IrExpression -> value(element)
      else -> unsupported(element, "this statement")
    }
  }

  private fun variable(variable: IrVariable) {
    val initializer = variable.initializer
    if (initializer == null) {
      variables[variable] = Reg(newVReg(variable.name.asString()))
      return
    }
    val value = value(initializer)
    variables[variable] =
        when {
          value is Dev && !variable.isVar -> value
          value is Dev -> fail(variable, "a device can only be held in a val")
          value is Const && !variable.isVar -> value
          else -> Reg(newVReg(variable.name.asString())).also { move(it.reg, value) }
        }
  }

  private fun value(expression: IrExpression): Value =
      when (expression) {
        is IrConst -> constant(expression)
        is IrGetValue ->
            variables[expression.symbol.owner]
                ?: fail(expression, "${expression.symbol.owner.name} is not available here")
        is IrSetValue -> {
          val target = variables[expression.symbol.owner] as? Reg
          if (target == null) fail(expression, "${expression.symbol.owner.name} cannot be changed")
          move(target.reg, value(expression.value))
          NoValue
        }
        is IrGetField ->
            propertyValue(expression, expression.symbol.owner.correspondingPropertySymbol)
        is IrSetField -> {
          setProperty(
              expression,
              expression.symbol.owner.correspondingPropertySymbol,
              expression.value,
          )
          NoValue
        }
        is IrCall -> call(expression)
        is IrWhen -> whenExpression(expression)
        is IrWhileLoop -> whileLoop(expression)
        is IrDoWhileLoop -> doWhileLoop(expression)
        is IrBreak -> jumpOut(expression, expression.loop, continuing = false)
        is IrContinue -> jumpOut(expression, expression.loop, continuing = true)
        is IrBlock ->
            if (expression.origin == IrStatementOrigin.FOR_LOOP) forLoop(expression)
            else container(expression)
        is IrContainerExpression -> container(expression)
        is IrTypeOperatorCall ->
            when (expression.operator) {
              IrTypeOperator.IMPLICIT_COERCION_TO_UNIT -> {
                value(expression.argument)
                NoValue
              }
              else -> unsupported(expression, "casts and type checks")
            }
        else -> unsupported(expression, "this expression")
      }

  private fun container(container: IrContainerExpression): Value {
    var result: Value = NoValue
    for (statement in container.statements) {
      result = NoValue
      when (statement) {
        is IrVariable -> variable(statement)
        is IrExpression -> result = value(statement)
        else -> unsupported(statement, "this statement")
      }
    }
    return if (container.type.isUnit()) NoValue else result
  }

  private fun constant(constant: IrConst): Value =
      when (constant.kind) {
        IrConstKind.Int -> Const((constant.value as Int).toDouble())
        IrConstKind.Double -> Const(constant.value as Double)
        IrConstKind.Boolean -> Const(if (constant.value as Boolean) 1.0 else 0.0)
        IrConstKind.String -> fail(constant, "text can only be used in hash(...) or str(...)")
        else -> unsupported(constant, "${constant.type.render()} values")
      }

  private fun propertyValue(element: IrElement, symbol: IrPropertySymbol?): Value =
      symbol?.let { properties[it] }
          ?: fail(element, "${symbol?.owner?.name ?: "this property"} is not available here")

  private fun setProperty(element: IrElement, symbol: IrPropertySymbol?, value: IrExpression) {
    val target = symbol?.let { properties[it] } as? Reg
    if (target == null) fail(element, "${symbol?.owner?.name ?: "this"} cannot be changed")
    move(target.reg, value(value))
  }

  // Calls.

  private fun call(call: IrCall): Value {
    val function = call.symbol.owner
    val name = function.name.asString()
    val owner = function.parentClassOrNull?.kotlinFqName?.asString()
    val property = function.correspondingPropertySymbol
    val args = call.arguments

    if (property != null && function.parentClassOrNull?.name?.asString() == PROGRAM_OBJECT) {
      return if (function == property.owner.setter) {
        setProperty(call, property, args.last() ?: fail(call, "missing value"))
        NoValue
      } else {
        propertyValue(call, property)
      }
    }
    if (property != null && owner in DEVICE_CLASSES) {
      val device = device(call, args[0])
      val logicType = Fixed(Name(property.owner.name.asString()))
      return if (function == property.owner.setter) {
        emit("s", Fixed(device.device), logicType, operand(value(args[1]!!)))
        NoValue
      } else {
        val result = newVReg()
        emit("l", result, Fixed(device.device), logicType)
        Reg(result)
      }
    }
    val fqName = function.kotlinFqName.asString()
    PIN_GETTERS[fqName]?.let {
      return Dev(Device(it))
    }
    return when (fqName) {
      "ktrapic.Device.get" -> {
        val result = newVReg()
        emit("l", result, Fixed(device(call, args[0]).device), logicType(call, args[1]))
        Reg(result)
      }
      "ktrapic.Device.set" -> {
        emit(
            "s",
            Fixed(device(call, args[0]).device),
            logicType(call, args[1]),
            operand(value(args[2]!!)),
        )
        NoValue
      }
      "ktrapic.sleep" -> {
        emit("sleep", operand(value(args[0]!!)))
        NoValue
      }
      "ktrapic.yield" -> {
        emit("yield")
        NoValue
      }
      "ktrapic.hash" -> stringConstant(call, StringConstant.Kind.HASH)
      "ktrapic.str" -> stringConstant(call, StringConstant.Kind.STR)
      else ->
          when {
            owner in setOf("kotlin.Int", "kotlin.Double", "kotlin.Boolean") -> operator(call, name)
            // The front end's comparison intrinsics (less, EQEQ, ...) are top-level functions.
            fqName.startsWith("kotlin.internal.ir.") -> comparisonValue(call)
            else -> math(call, fqName) ?: fail(call, "calls to $fqName are not supported")
          }
    }
  }

  private fun device(call: IrCall, argument: IrExpression?): Dev =
      argument?.let { value(it) } as? Dev
          ?: fail(argument ?: call, "this device must be a pin, or a val holding one")

  private fun logicType(call: IrCall, argument: IrExpression?): Fixed {
    if (argument !is IrGetEnumValue) {
      fail(argument ?: call, "the logic type must be written out, as LogicType.X")
    }
    return Fixed(Name(argument.symbol.owner.name.asString()))
  }

  private fun stringConstant(call: IrCall, kind: StringConstant.Kind): Value {
    val text = (call.arguments[0] as? IrConst)?.value as? String
    if (text == null) fail(call, "${kind.name.lowercase()}(...) needs a constant string")
    val constant =
        try {
          StringConstant(kind, text)
        } catch (e: IllegalArgumentException) {
          fail(call, e.message ?: "bad ${kind.name.lowercase()}(...) text")
        }
    return Const(constant.numericValue, constant)
  }

  /** Arithmetic and logic on Int, Double and Boolean. */
  private fun operator(call: IrCall, name: String): Value {
    val args = call.arguments.map { it ?: fail(call, "missing operand") }
    val int = call.type.isInt()
    fun a() = value(args[0])
    fun b() = value(args[1])
    return when (name) {
      "plus" -> arithmetic("add", a(), b(), int) { x, y -> x + y }
      "minus" -> arithmetic("sub", a(), b(), int) { x, y -> x - y }
      "times" -> arithmetic("mul", a(), b(), int) { x, y -> x * y }
      "div" ->
          if (int) intDivision(call, a(), b())
          else arithmetic("div", a(), b(), false) { x, y -> x / y }
      "rem" -> remainder(call, a(), b(), int)
      "unaryMinus" -> arithmetic("sub", Const(0.0), a(), int) { x, y -> x - y }
      "unaryPlus" -> a()
      "inc" -> arithmetic("add", a(), Const(1.0), int) { x, y -> x + y }
      "dec" -> arithmetic("sub", a(), Const(1.0), int) { x, y -> x - y }
      "toDouble" -> a()
      "toInt" ->
          if (args[0].type.isInt()) a() else unary("trunc", a()) { x -> x.toInt().toDouble() }
      "not" -> unary("seqz", a()) { x -> if (x == 0.0) 1.0 else 0.0 }
      "and" -> bitwise(call, "and", a(), b()) { x, y -> x and y }
      "or" -> bitwise(call, "or", a(), b()) { x, y -> x or y }
      "xor" -> bitwise(call, "xor", a(), b()) { x, y -> x xor y }
      "shl" -> bitwise(call, "sll", a(), b()) { x, y -> x shl y }
      "shr" -> bitwise(call, "sra", a(), b()) { x, y -> x shr y }
      "ushr" -> bitwise(call, "srl", a(), b()) { x, y -> x ushr y }
      "inv" -> unary("not", a()) { x -> x.toInt().inv().toDouble() }
      else -> fail(call, "${call.symbol.owner.kotlinFqName} is not supported")
    }
  }

  private fun arithmetic(
      op: String,
      a: Value,
      b: Value,
      int: Boolean,
      fold: (Double, Double) -> Double,
  ): Value {
    if (a is Const && b is Const) {
      val result = fold(a.number, b.number)
      return Const(if (int) result.toLong().toInt().toDouble() else result)
    }
    val result = newVReg()
    emit(op, result, operand(a), operand(b))
    return Reg(result)
  }

  private fun unary(op: String, a: Value, fold: (Double) -> Double): Value {
    if (a is Const) return Const(fold(a.number))
    val result = newVReg()
    emit(op, result, operand(a))
    return Reg(result)
  }

  private fun bitwise(
      call: IrCall,
      op: String,
      a: Value,
      b: Value,
      fold: (Int, Int) -> Int,
  ): Value {
    if (!call.type.isInt()) unsupported(call, "Boolean and, or and xor (use && and ||)")
    if (a is Const && b is Const) return Const(fold(a.number.toInt(), b.number.toInt()).toDouble())
    val result = newVReg()
    emit(op, result, operand(a), operand(b))
    return Reg(result)
  }

  /** Int division truncates towards zero, as on the JVM. */
  private fun intDivision(call: IrCall, a: Value, b: Value): Value {
    if (b is Const && b.number == 0.0) fail(call, "division by zero")
    if (a is Const && b is Const) return Const((a.number.toInt() / b.number.toInt()).toDouble())
    val result = newVReg()
    emit("div", result, operand(a), operand(b))
    emit("trunc", result, result)
    return Reg(result)
  }

  /** `a % b` takes the sign of `a`, as on the JVM, unlike IC10's `mod`. */
  private fun remainder(call: IrCall, a: Value, b: Value, int: Boolean): Value {
    if (int && b is Const && b.number == 0.0) fail(call, "division by zero")
    if (a is Const && b is Const) {
      return Const(
          if (int) (a.number.toInt() % b.number.toInt()).toDouble() else a.number % b.number
      )
    }
    val quotient = newVReg()
    emit("div", quotient, operand(a), operand(b))
    emit("trunc", quotient, quotient)
    emit("mul", quotient, quotient, operand(b))
    val result = newVReg()
    emit("sub", result, operand(a), quotient)
    return Reg(result)
  }

  private fun math(call: IrCall, fqName: String): Value? {
    val args = call.arguments.map { it ?: fail(call, "missing argument") }
    val unaryOps =
        mapOf(
            "abs" to "abs",
            "sqrt" to "sqrt",
            "floor" to "floor",
            "ceil" to "ceil",
            "round" to "round",
            "truncate" to "trunc",
            "sin" to "sin",
            "cos" to "cos",
            "tan" to "tan",
            "asin" to "asin",
            "acos" to "acos",
            "atan" to "atan",
            "exp" to "exp",
            "ln" to "log",
        )
    val name = fqName.removePrefix("kotlin.math.")
    if (!fqName.startsWith("kotlin.math.") && !fqName.startsWith("kotlin.ranges.coerce")) {
      return null
    }
    val op = unaryOps[name]
    return when {
      op != null && args.size == 1 -> {
        val result = newVReg()
        emit(op, result, operand(value(args[0])))
        Reg(result)
      }
      name == "<get-absoluteValue>" -> {
        val result = newVReg()
        emit("abs", result, operand(value(args[0])))
        Reg(result)
      }
      name == "roundToInt" -> {
        // Kotlin rounds halves up here (Math.round), unlike round(), which rounds them to even.
        val result = newVReg()
        emit("add", result, operand(value(args[0])), Fixed(Number(0.5)))
        emit("floor", result, result)
        Reg(result)
      }
      name in setOf("atan2", "max", "min", "pow") && args.size == 2 -> {
        val result = newVReg()
        emit(name, result, operand(value(args[0])), operand(value(args[1])))
        Reg(result)
      }
      fqName == "kotlin.ranges.coerceAtLeast" -> {
        val result = newVReg()
        emit("max", result, operand(value(args[0])), operand(value(args[1])))
        Reg(result)
      }
      fqName == "kotlin.ranges.coerceAtMost" -> {
        val result = newVReg()
        emit("min", result, operand(value(args[0])), operand(value(args[1])))
        Reg(result)
      }
      fqName == "kotlin.ranges.coerceIn" && args.size == 3 -> {
        val result = newVReg()
        emit("max", result, operand(value(args[0])), operand(value(args[1])))
        emit("min", result, result, operand(value(args[2])))
        Reg(result)
      }
      else -> null
    }
  }

  // Comparisons and conditions.

  private class Comparison(val op: String, val a: Value, val b: Value)

  /** A comparison call (`a < b`, `a == b`) as an IC10 condition, such as `lt` or `eq`. */
  private fun comparison(call: IrCall): Comparison? {
    val condition =
        when (call.symbol.owner.name.asString()) {
          "less" -> "lt"
          "lessOrEqual" -> "le"
          "greater" -> "gt"
          "greaterOrEqual" -> "ge"
          "EQEQ",
          "ieee754equals" -> "eq"
          else -> return null
        }
    val left = call.arguments[0] ?: return null
    val right = call.arguments[1] ?: return null
    for (side in listOf(left, right)) {
      val type = side.type
      if (!type.isInt() && !type.isDouble() && !type.isBoolean()) {
        unsupported(call, "comparing ${type.render()} values")
      }
    }
    val boolean = left.type.isBoolean()
    return Comparison(
        condition,
        value(left).let { if (boolean) truth(it) else it },
        value(right).let { if (boolean) truth(it) else it },
    )
  }

  /** A Boolean as exactly 0 or 1: a device may report any non-zero value for true. */
  private fun truth(value: Value): Value = unary("snez", value) { x -> if (x != 0.0) 1.0 else 0.0 }

  private fun comparisonValue(call: IrCall): Value {
    val comparison =
        comparison(call) ?: fail(call, "${call.symbol.owner.kotlinFqName} is not supported")
    val (a, b) = comparison.a to comparison.b
    if (a is Const && b is Const)
        return Const(if (holds(comparison.op, a.number, b.number)) 1.0 else 0.0)
    val result = newVReg()
    if (b == Const(0.0)) emit("s${comparison.op}z", result, operand(a))
    else emit("s${comparison.op}", result, operand(a), operand(b))
    return Reg(result)
  }

  private fun holds(op: String, a: Double, b: Double) =
      when (op) {
        "lt" -> a < b
        "le" -> a <= b
        "gt" -> a > b
        "ge" -> a >= b
        "eq" -> a == b
        else -> a != b
      }

  private val NEGATED =
      mapOf("lt" to "ge", "le" to "gt", "gt" to "le", "ge" to "lt", "eq" to "ne", "ne" to "eq")

  /** Emits a jump to [target] taken when [condition] is [whenTrue]. */
  private fun branch(condition: IrExpression, whenTrue: Boolean, target: String) {
    if (condition is IrConst && condition.kind == IrConstKind.Boolean) {
      if (condition.value == whenTrue) emit("j", Fixed(LabelRef(target)))
      return
    }
    if (condition is IrCall) {
      val function = condition.symbol.owner
      if (
          function.name.asString() == "not" &&
              function.parentClassOrNull?.kotlinFqName?.asString() == "kotlin.Boolean"
      ) {
        return branch(condition.arguments[0]!!, !whenTrue, target)
      }
      if (
          function.parentClassOrNull?.kotlinFqName?.asString() == null &&
              function.kotlinFqName.asString().startsWith("kotlin.internal.ir.")
      ) {
        val comparison = comparison(condition)
        if (comparison != null) {
          val op = if (whenTrue) comparison.op else NEGATED.getValue(comparison.op)
          val (a, b) = comparison.a to comparison.b
          if (a is Const && b is Const) {
            if (holds(op, a.number, b.number)) emit("j", Fixed(LabelRef(target)))
          } else if (b == Const(0.0)) {
            emit("b${op}z", operand(a), Fixed(LabelRef(target)))
          } else {
            emit("b$op", operand(a), operand(b), Fixed(LabelRef(target)))
          }
          return
        }
      }
    }
    if (condition is IrWhen && condition.origin == IrStatementOrigin.ANDAND) {
      // a && b is `when { a -> b; else -> false }`.
      val (a, b) = condition.branches[0].condition to condition.branches[0].result
      if (whenTrue) {
        val skip = newLabel("and")
        branch(a, false, skip)
        branch(b, true, target)
        label(skip)
      } else {
        branch(a, false, target)
        branch(b, false, target)
      }
      return
    }
    if (condition is IrWhen && condition.origin == IrStatementOrigin.OROR) {
      // a || b is `when { a -> true; else -> b }`.
      val a = condition.branches[0].condition
      val b = condition.branches[1].result
      if (whenTrue) {
        branch(a, true, target)
        branch(b, true, target)
      } else {
        val skip = newLabel("or")
        branch(a, true, skip)
        branch(b, false, target)
        label(skip)
      }
      return
    }
    when (val value = value(condition)) {
      is Const -> if ((value.number != 0.0) == whenTrue) emit("j", Fixed(LabelRef(target)))
      else -> emit(if (whenTrue) "bnez" else "beqz", operand(value), Fixed(LabelRef(target)))
    }
  }

  // Control flow.

  private fun whenExpression(expression: IrWhen): Value {
    val type = expression.type
    val result = if (type.isUnit() || type.isNothing() || type.isAny()) null else newVReg()
    val end = newLabel("endif")
    for (branch in expression.branches) {
      val condition = branch.condition
      val always = condition is IrConst && condition.value == true
      val never = condition is IrConst && condition.value == false
      if (never) continue
      val next = if (always) null else newLabel("else")
      if (next != null) branch(condition, false, next)
      val value = value(branch.result)
      if (result != null && value != NoValue) move(result, value)
      if (next == null) break
      emit("j", Fixed(LabelRef(end)))
      label(next)
    }
    label(end)
    return result?.let { Reg(it) } ?: NoValue
  }

  private fun whileLoop(loop: IrWhileLoop): Value {
    val top = newLabel("while")
    val end = newLabel("endwhile")
    label(top)
    branch(loop.condition, false, end)
    loops[loop] = top to end
    loop.body?.let { value(it) }
    emit("j", Fixed(LabelRef(top)))
    label(end)
    return NoValue
  }

  private fun doWhileLoop(loop: IrDoWhileLoop): Value {
    val top = newLabel("do")
    val test = newLabel("dotest")
    val end = newLabel("enddo")
    label(top)
    loops[loop] = test to end
    loop.body?.let { value(it) }
    label(test)
    branch(loop.condition, true, top)
    label(end)
    return NoValue
  }

  /**
   * `for (i in range)` over an Int range: `a..b`, `a..<b`, `a until b`, `a downTo b`, each
   * optionally with `step`. Kotlin evaluates the range's bounds once, before the loop.
   */
  private fun forLoop(block: IrBlock): Value {
    val iterator = block.statements.getOrNull(0) as? IrVariable
    val loop = block.statements.getOrNull(1) as? IrWhileLoop
    val body = loop?.body as? IrContainerExpression
    val loopVariable = body?.statements?.firstOrNull() as? IrVariable
    val iteratorCall = iterator?.initializer as? IrCall
    val rangeExpression = iteratorCall?.arguments?.getOrNull(0)
    if (loop == null || loopVariable == null || rangeExpression !is IrCall) {
      unsupported(block, "this kind of for loop (only Int ranges are supported)")
    }
    val range = range(rangeExpression)
    val counter = newVReg(loopVariable.name.asString())
    move(counter, range.start)
    val end =
        when (val last = range.end) {
          is Const -> last
          else -> Reg(newVReg()).also { move(it.reg, last) }
        }
    val top = newLabel("for")
    val next = newLabel("next")
    val done = newLabel("endfor")
    label(top)
    val exit =
        when {
          range.descending -> "blt"
          range.inclusive -> "bgt"
          else -> "bge"
        }
    emit(exit, counter, operand(end), Fixed(LabelRef(done)))
    variables[loopVariable] = Reg(counter)
    loops[loop] = next to done
    for (statement in body.statements.drop(1)) statement(statement)
    label(next)
    emit(if (range.descending) "sub" else "add", counter, counter, operand(range.step))
    emit("j", Fixed(LabelRef(top)))
    label(done)
    return NoValue
  }

  private class Range(
      val start: Value,
      val end: Value,
      val step: Value,
      val inclusive: Boolean,
      val descending: Boolean,
  )

  private fun range(call: IrCall): Range {
    val function = call.symbol.owner
    val fqName = function.kotlinFqName.asString()
    val args = call.arguments.map { it ?: fail(call, "missing range bound") }
    if (fqName != "kotlin.ranges.step" && args.any { !it.type.isInt() }) {
      unsupported(call, "ranges of anything but Int")
    }
    return when (fqName) {
      "kotlin.Int.rangeTo" -> Range(value(args[0]), value(args[1]), Const(1.0), true, false)
      "kotlin.Int.rangeUntil",
      "kotlin.ranges.until" -> Range(value(args[0]), value(args[1]), Const(1.0), false, false)
      "kotlin.ranges.downTo" -> Range(value(args[0]), value(args[1]), Const(1.0), true, true)
      "kotlin.ranges.step" -> {
        val inner = args[0] as? IrCall ?: unsupported(call, "this range")
        val range = range(inner)
        val step = value(args[1])
        if (step is Const && step.number <= 0.0) fail(args[1], "a range's step must be positive")
        Range(range.start, range.end, step, range.inclusive, range.descending)
      }
      else -> unsupported(call, "this range (use .., ..<, until, downTo and step)")
    }
  }

  private fun jumpOut(element: IrElement, loop: IrLoop, continuing: Boolean): Value {
    val (continueLabel, breakLabel) =
        loops[loop] ?: fail(element, "break or continue outside a loop")
    emit("j", Fixed(LabelRef(if (continuing) continueLabel else breakLabel)))
    return NoValue
  }

  // Emitting.

  private fun newVReg(name: String? = null) = VReg(vregs++, name)

  private fun newLabel(base: String): String {
    val count = labelCounts.merge(base, 1, Int::plus)!!
    return "$base$count"
  }

  private fun label(name: String) {
    lines += TirLabel(name)
  }

  private fun emit(op: String, vararg operands: TirOperand) {
    lines += TirInstruction(op, *operands)
  }

  private fun move(target: VReg, value: Value) {
    if (value == Reg(target)) return
    emit("move", target, operand(value))
  }

  private fun operand(value: Value): TirOperand =
      when (value) {
        is Const -> Fixed(value.operand)
        is Reg -> value.reg
        is Dev -> Fixed(value.device)
        NoValue -> error("no value")
      }

  private fun IrType.isAny() = classFqName?.asString() == "kotlin.Any"

  private fun IrType.render() = classFqName?.shortName()?.asString() ?: "these"

  private fun fail(element: IrElement, message: String): Nothing =
      throw CompileException(element, message)

  private fun unsupported(element: IrElement, what: String): Nothing =
      fail(
          element,
          "${what.replaceFirstChar { it.uppercase() }} ${if (what.endsWith("s")) "are" else "is"} not supported",
      )

  private companion object {
    val DEVICE_CLASSES = setOf("ktrapic.Device", "ktrapic.GenericDevice")
    val PIN_GETTERS =
        listOf("d0", "d1", "d2", "d3", "d4", "d5", "db").associateBy { "ktrapic.<get-$it>" }
  }
}
