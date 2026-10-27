// The backend reads finished IR, after fir2ir, so symbol owners are always bound.
@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package ktrapic.backend

import kotlin.math.floor
import kotlin.math.truncate
import ktrapic.frontend.PROGRAM_OBJECT
import ktrapic.ic10.Constants
import ktrapic.ic10.Device
import ktrapic.ic10.Hashes
import ktrapic.ic10.Ic10Math
import ktrapic.ic10.LabelRef
import ktrapic.ic10.Name
import ktrapic.ic10.Number
import ktrapic.ic10.Operand
import ktrapic.ic10.Register
import ktrapic.ic10.StringConstant
import org.jetbrains.kotlin.backend.common.extensions.DeclarationFinder
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrAnonymousInitializer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrEnumEntry
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
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
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.IrPropertyReference
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.expressions.IrWhileLoop
import org.jetbrains.kotlin.ir.symbols.IrPropertySymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.isBoolean
import org.jetbrains.kotlin.ir.types.isDouble
import org.jetbrains.kotlin.ir.types.isInt
import org.jetbrains.kotlin.ir.types.isNothing
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

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

/**
 * The devices of one prefab on the network, by the game's hash of the prefab's name ([prefab]): all
 * of them, or those whose name hashes to [name].
 */
internal data class Batch(val prefab: Double, val name: Double?) : Value

/**
 * A batch's logic type picked by `map` ([source] a [Batch], or a [SlotOf] one), to be read by one
 * of the [Translator.MAPPED_TERMINALS]. The batch is read where it is ended, so a map can't be held
 * in a val: Kotlin's `map` would keep the values it read when it was made.
 */
internal data class Mapped(val source: Value, val logicType: String) : Value

/** A device's or a batch's slots (`slots`), before one is picked. */
internal data class SlotsOf(val owner: Value) : Value

/** Slot [number] of a device ([Dev]) or of every device in a batch ([Batch]). */
internal data class SlotOf(val owner: Value, val number: TirOperand) : Value

internal data object NoValue : Value

/**
 * A view of a device's logic types (`Ports`, `Ratios`, `Position`, `Import` and their parts),
 * resolved when compiled: a member or key of it names the logic type [prefix] + the member or
 * key + [suffix]. Its [shape] is the view's runtime class, by simple name. [owner] is the device
 * ([Dev]), or a batch's element ([Element]). [access] is what a typed device's prefab has in this
 * view, or for an untyped pin every logic type the game has.
 */
internal data class View(
    val owner: Value,
    val shape: String,
    val prefix: String,
    val suffix: String,
    val access: ViewAccess?,
) : Value

/** The logic types a typed device ([device], its type's name) can read and write in a view. */
internal data class ViewAccess(val device: String, val read: Set<String>, val written: Set<String>)

/** A batch's element, `it` in a batch's lambda, whose views name logic types without reading. */
internal data object Element : Value

/** A logic type an [Element]'s view names, for a batch to read. */
internal data class Named(val logicType: String) : Value

/** An enum entry used as a view's key, such as `Substance.Oxygen`. */
internal data class Key(val name: String) : Value

/**
 * A collection known whole when compiled (`setOf(Substance.Oxygen, Substance.Nitrogen)`, `listOf(0,
 * 2)`, `Substance.entries`, a view's `keys`): it has no form on the chip, and is only looped over
 * (each element a constant in its own copy of the loop), tested with `in`, measured with `size`,
 * combined with `+`, `-` and `intersect`, held in a val or passed to an inlined function. Its
 * [elements] are each a [Const] or a [Key].
 */
internal data class StaticSet(val elements: List<Value>) : Value

/**
 * Translates the IR of a wrapped script-style program into [TirLine]s: the `Program` object's
 * property initializers and `init` blocks, in source order. [symbols] finds the runtime API's
 * declarations.
 */
internal class Translator(private val symbols: DeclarationFinder) {
  val lines = mutableListOf<TirLine>()
  private var vregs = 0
  private val labelCounts = mutableMapOf<String, Int>()
  private val variables = mutableMapOf<IrValueDeclaration, Value>()
  private val properties = mutableMapOf<IrPropertySymbol, Value>()
  private val loops = mutableMapOf<IrLoop, Pair<String, String>>()
  private lateinit var graph: CallGraph
  private val takenLabels = mutableSetOf<String>()
  private val functionLabels = mutableMapOf<IrSimpleFunction, String>()
  /** The functions whose bodies are being translated, and where their `return`s go. */
  private val exits = mutableMapOf<IrSimpleFunction, FunctionExit>()
  /** Functions that have a body of their own, called with `jal`, in the order first called. */
  private val called = linkedMapOf<IrSimpleFunction, CalledFunction>()

  /**
   * What an untyped device's views reach: every logic type the game has, read and written, as its
   * flat logic types are. A view's members combine (`Ports.Output2.Setting`) into names the game
   * may not have, which this rules out. The game's logic types are the entries of the runtime's
   * `LogicType`.
   */
  private val anyDeviceAccess: ViewAccess by lazy {
    val logicType =
        checkNotNull(symbols.findClass(LOGIC_TYPE)) {
          "the runtime has no ${LOGIC_TYPE.asString()}"
        }
    val names =
        logicType.owner.declarations
            .filterIsInstance<IrEnumEntry>()
            .map { it.name.asString() }
            .toSet()
    ViewAccess("a device", names, names)
  }

  /** The [Substance] entries, by name, in declaration order. */
  private val substances: List<String> by lazy {
    val substance =
        checkNotNull(symbols.findClass(SUBSTANCE_ID)) {
          "the runtime has no ${SUBSTANCE_ID.asString()}"
        }
    substance.owner.declarations.filterIsInstance<IrEnumEntry>().map { it.name.asString() }
  }

  /**
   * The substances a device's [Ratios] view reports, in [Substance]'s order: those whose logic type
   * (`RatioOxygen`, `RatioOxygenOutput`) the view's device can read.
   */
  private fun ratioKeys(view: View): StaticSet {
    val readable = (view.access ?: anyDeviceAccess).read
    return StaticSet(
        substances.filter { view.prefix + it + view.suffix in readable }.map { Key(it) }
    )
  }

  private class FunctionExit(val label: String, val result: VReg?)

  /** Where a called function's arguments and result travel: registers of its own. */
  private class CalledFunction(val label: String, val parameters: List<VReg>, val result: VReg?)

  fun translate(module: IrModuleFragment) {
    val program =
        module.files
            .flatMap { it.declarations }
            .filterIsInstance<IrClass>()
            .singleOrNull { it.name.asString() == PROGRAM_OBJECT } ?: return
    graph = CallGraph(program)
    graph.recursiveCall()?.let { fail(it, "Recursive calls are not supported") }
    takenLabels += END_LABEL
    for (function in graph.functions) {
      var label = labelName(function.name.asString())
      while (
          label in RESERVED_NAMES ||
              Constants.lookup(label) != null ||
              REGISTER_SPELLING.matches(label) ||
              label in takenLabels
      ) {
        label += "_"
      }
      takenLabels += label
      functionLabels[function] = label
    }
    for (declaration in program.declarations) {
      when (declaration) {
        is IrProperty -> at(declaration) { property(declaration) }
        is IrAnonymousInitializer -> statement(declaration.body)
        is IrConstructor -> {}
        // A function is translated where it is inlined, or after the program if it is called.
        is IrSimpleFunction -> {}
        is IrClass -> fail(declaration, "Classes, interfaces and objects are not supported")
        else -> fail(declaration, "This declaration is not supported")
      }
    }
    if (called.isNotEmpty()) {
      // The functions' bodies follow the program, which must not run on into them.
      emit("j", Fixed(LabelRef(END_LABEL)))
      var translated = 0
      while (translated < called.size) {
        functionBody(called.keys.elementAt(translated))
        translated++
      }
      label(END_LABEL)
    }
  }

  // Functions.

  private fun userCall(call: IrCall, function: IrSimpleFunction): Value {
    checkSignature(function)
    val parameters =
        function.parameters.withIndex().filter { it.value.kind == IrParameterKind.Regular }
    val arguments = parameters.map { (index, _) ->
      call.arguments[index] ?: fail(call, "Default arguments are not supported")
    }
    val values = operands(arguments, aliases = parameters.map { it.value.type.isViewOrKey() })
    return if (function.isInline || graph.callCount(function) == 1) {
      inlineCall(function, parameters.map { it.value }, values)
    } else {
      jalCall(call, function, parameters.map { it.value }, values)
    }
  }

  private fun checkSignature(function: IrSimpleFunction) {
    if (function.parameters.any { it.kind == IrParameterKind.ExtensionReceiver }) {
      fail(function, "Extension functions are not supported")
    }
    if (function.typeParameters.isNotEmpty()) {
      fail(function, "Generic functions are not supported")
    }
    for (parameter in function.parameters.filter { it.kind == IrParameterKind.Regular }) {
      val type = parameter.type
      if (parameter.varargElementType != null)
          fail(parameter, "vararg parameters are not supported")
      if (
          !type.isInt() &&
              !type.isDouble() &&
              !type.isBoolean() &&
              !type.isDevice() &&
              !type.isViewOrKey()
      ) {
        fail(parameter, "Parameters of type ${type.render()} are not supported")
      }
    }
    val result = function.returnType
    if (
        !result.isUnit() &&
            !result.isNothing() &&
            !result.isInt() &&
            !result.isDouble() &&
            !result.isBoolean()
    ) {
      fail(function, "Functions returning ${result.render()} are not supported")
    }
  }

  private fun resultRegister(function: IrSimpleFunction): VReg? =
      function.returnType.let { if (it.isUnit() || it.isNothing()) null else newVReg() }

  /** The function's body, in place, with its parameters bound to the arguments' values. */
  private fun inlineCall(
      function: IrSimpleFunction,
      parameters: List<IrValueParameter>,
      values: List<Value>,
  ): Value {
    for ((parameter, value) in parameters.zip(values)) {
      // A copy, as the body may change a variable passed in while the parameter keeps its value.
      variables[parameter] = if (value is Reg) snapshot(value) else value
    }
    val exit = FunctionExit(newLabel("return"), resultRegister(function))
    body(function, exit)
    return exit.result?.let { Reg(it) } ?: NoValue
  }

  private fun jalCall(
      call: IrCall,
      function: IrSimpleFunction,
      parameters: List<IrValueParameter>,
      values: List<Value>,
  ): Value {
    if (parameters.any { it.type.isDevice() || it.type.isViewOrKey() }) {
      fail(
          call,
          "A function with a device, view, substance or set parameter must be inline, or called only once",
      )
    }
    val target =
        called.getOrPut(function) {
          CalledFunction(
              functionLabels.getValue(function),
              parameters.map { newVReg(it.name.asString()) },
              resultRegister(function),
          )
        }
    for ((parameter, value) in target.parameters.zip(values)) move(parameter, value)
    emit("jal", Fixed(LabelRef(target.label)))
    // A copy, as the next call to the function overwrites its result register.
    return target.result?.let { snapshot(Reg(it)) } ?: NoValue
  }

  /** A called function: its label, its body, and `j ra`, saving `ra` if it calls others. */
  private fun functionBody(function: IrSimpleFunction) {
    val target = called.getValue(function)
    label(target.label)
    val start = lines.size
    function.parameters
        .filter { it.kind == IrParameterKind.Regular }
        .zip(target.parameters)
        .forEach { (parameter, register) -> variables[parameter] = Reg(register) }
    body(function, FunctionExit(newLabel("return"), target.result))
    val callsOthers = lines.subList(start, lines.size).any { (it as? TirInstruction)?.op == "jal" }
    if (callsOthers) {
      lines.add(start, TirInstruction("push", Fixed(Register.RA)))
      emit("pop", Fixed(Register.RA))
    }
    lines += TirInstruction("j", listOf(Fixed(Register.RA)), returnFrom = target.label)
  }

  private fun body(function: IrSimpleFunction, exit: FunctionExit) {
    exits[function] = exit
    when (val body = function.body) {
      is IrBlockBody -> statement(body)
      is IrExpressionBody -> returnValue(exit, body.expression)
      else -> fail(function, "This function has no body")
    }
    exits.remove(function)
    label(exit.label)
  }

  private fun returnValue(exit: FunctionExit, expression: IrExpression) {
    if (expression is IrGetObjectValue && expression.type.isUnit()) return
    val value = value(expression)
    if (exit.result != null && value != NoValue) move(exit.result, value)
  }

  private fun returnFrom(expression: IrReturn): Value {
    val exit =
        (expression.returnTargetSymbol.owner as? IrSimpleFunction)?.let { exits[it] }
            ?: fail(expression, "return is only supported in a function")
    returnValue(exit, expression.value)
    emit("j", Fixed(LabelRef(exit.label)))
    return NoValue
  }

  private fun property(property: IrProperty) {
    if (property.isDelegated) fail(property, "Delegated properties are not supported")
    // Reads and writes use the backing register directly, so accessor bodies would never run.
    if (
        listOfNotNull(property.getter, property.setter).any {
          it.origin != IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR
        }
    ) {
      fail(property, "Properties with a custom getter or setter are not supported")
    }
    val initializer =
        property.backingField?.initializer?.expression
            ?: fail(property, "Properties without an initializer are not supported")
    // A Unit property holds nothing, like a Unit variable (see variable()).
    if (property.backingField?.type?.isUnit() == true) {
      value(initializer)
      properties[property.symbol] = NoValue
      return
    }
    val value = aliasOrValue(initializer)
    properties[property.symbol] =
        when {
          value is Mapped -> fail(initializer, MAPPED_HELD)
          value.isAlias() && !property.isVar -> value
          value.isAlias() ->
              fail(property, "a device, batch, slot or view can only be held in a val")
          value is Const && !property.isVar -> value
          else -> Reg(newVReg(property.name.asString())).also { move(it.reg, value) }
        }
  }

  // Statements and expressions.

  private fun statement(element: IrElement) {
    at(element) {
      when (element) {
        is IrBlockBody -> element.statements.forEach { statement(it) }
        is IrVariable -> variable(element)
        is IrExpression -> aliasOrValue(element)
        is IrSimpleFunction -> fail(element, "Local functions are not supported")
        else -> fail(element, "This statement is not supported")
      }
    }
  }

  /**
   * Runs [translate] for [element], then gives the instructions it emitted [element]'s start offset
   * where a statement inside [element] hasn't already given them its own, so each instruction is
   * attributed to the innermost statement it comes from. An [element] with no place in the source
   * (a negative offset, as compiler-made IR can have) leaves them to the statement around it. That
   * check is defensive: no construct the backend compiles is known to reach it.
   */
  private inline fun <T> at(element: IrElement, translate: () -> T): T {
    val start = lines.size
    return translate().also {
      if (element.startOffset < 0) return@also
      for (index in start until lines.size) {
        val line = lines[index]
        if (line is TirInstruction && line.offset == null) {
          lines[index] = line.copy(offset = element.startOffset)
        }
      }
    }
  }

  private fun variable(variable: IrVariable) {
    val initializer = variable.initializer
    // A Unit variable holds nothing: its initializer, like anything later assigned to it, is run
    // only for what it does.
    if (variable.type.isUnit()) {
      initializer?.let { value(it) }
      variables[variable] = NoValue
      return
    }
    if (initializer == null) {
      variables[variable] = Reg(newVReg(variable.name.asString()))
      return
    }
    val value = aliasOrValue(initializer)
    variables[variable] =
        when {
          value is Mapped -> fail(initializer, MAPPED_HELD)
          value.isAlias() && !variable.isVar -> value
          value.isAlias() ->
              fail(variable, "a device, batch, slot or view can only be held in a val")
          value is Const && !variable.isVar -> value
          else -> Reg(newVReg(variable.name.asString())).also { move(it.reg, value) }
        }
  }

  /**
   * What [expression] evaluates to, which must not be a [Batch], a slot or a [Mapped]: those have
   * no value and are only used directly ([aliasOrValue]) or held in a `val`, a [Mapped] not even
   * that.
   */
  private fun value(
      expression: IrExpression,
      batchError: String = "a batch can only be used directly or held in a val",
  ): Value =
      aliasOrValue(expression).also {
        if (it is SlotsOf || it is SlotOf) {
          fail(expression, "a slot can only be used directly or held in a val")
        }
        if (it is View) {
          fail(
              expression,
              "a view is used by naming one of its logic types, as Ports.Input.Pressure",
          )
        }
        if (it is Key) fail(expression, "a substance can only be used as a key, as Ratios[it]")
        if (it is StaticSet) {
          fail(expression, "a static set can only be looped over, tested with in, or measured")
        }
        if (it.isAlias() && it !is Dev) fail(expression, batchError)
      }

  /** What [expression] evaluates to, a [Batch], a slot or a [Mapped] included. */
  private fun aliasOrValue(expression: IrExpression): Value =
      when (expression) {
        is IrConst -> constant(expression)
        is IrGetValue ->
            variables[expression.symbol.owner]
                ?: fail(expression, "${expression.symbol.owner.name} is not available here")
        is IrSetValue -> {
          if (variables[expression.symbol.owner] == NoValue) {
            value(expression.value)
            return NoValue
          }
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
        is IrGetObjectValue -> prefab(expression)
        is IrGetEnumValue -> key(expression)
        is IrReturn -> returnFrom(expression)
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
                aliasOrValue(expression.argument)
                NoValue
              }
              else -> fail(expression, "Casts and type checks are not supported")
            }
        else -> fail(expression, "This expression is not supported")
      }

  private fun container(container: IrContainerExpression): Value {
    var result: Value = NoValue
    for (statement in container.statements) {
      result = NoValue
      at(statement) {
        when (statement) {
          is IrVariable -> variable(statement)
          is IrExpression -> result = aliasOrValue(statement)
          is IrSimpleFunction -> fail(statement, "Local functions are not supported")
          else -> fail(statement, "This statement is not supported")
        }
      }
    }
    return if (container.type.isUnit()) NoValue else result
  }

  private fun constant(constant: IrConst): Value =
      when (constant.kind) {
        IrConstKind.Int -> Const((constant.value as Int).toDouble())
        IrConstKind.Double -> Const(constant.value as Double)
        IrConstKind.Boolean -> Const(if (constant.value as Boolean) 1.0 else 0.0)
        IrConstKind.String ->
            fail(constant, "text can only be used in hash(...), str(...), named(...) or Text")
        else -> fail(constant, "${constant.type.render()} values are not supported")
      }

  private fun propertyValue(element: IrElement, symbol: IrPropertySymbol?): Value =
      symbol?.let { properties[it] }
          ?: fail(element, "${symbol?.owner?.name ?: "this property"} is not available here")

  private fun setProperty(element: IrElement, symbol: IrPropertySymbol?, value: IrExpression) {
    if (symbol?.let { properties[it] } == NoValue) {
      value(value)
      return
    }
    val target = symbol?.let { properties[it] } as? Reg
    if (target == null) fail(element, "${symbol?.owner?.name ?: "this"} cannot be changed")
    move(target.reg, value(value))
  }

  // Calls.

  private fun call(call: IrCall): Value {
    val function = call.symbol.owner
    if (function in graph.functions) return userCall(call, function)
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
    function.parentClassOrNull?.let { cls ->
      typedCall(call, cls, function)?.let {
        return it
      }
    }
    if (property != null && owner in DEVICE_CLASSES) {
      if (function.returnType.classFqName?.asString() in VIEW_CLASSES) {
        return startView(call, property.owner, viewOwner(call, args[0]), anyDeviceAccess)
      }
      val device = device(call, args[0])
      if (property.owner.name.asString() == SLOTS) return SlotsOf(device)
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
    staticSetCall(call, function, fqName)?.let {
      return it
    }
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
      in MAPPED_TERMINALS -> {
        val mapped =
            aliasOrValue(args[0]!!) as? Mapped
                ?: fail(call, "${function.name}() reads a batch's map { ... }")
        batchRead(mapped.source, mapped.logicType, MAPPED_TERMINALS.getValue(fqName))
      }
      "ktrapic.hash" -> stringConstant(call, StringConstant.Kind.HASH)
      "ktrapic.str" -> stringConstant(call, StringConstant.Kind.STR)
      else ->
          when {
            owner in setOf("kotlin.Int", "kotlin.Double", "kotlin.Boolean") -> operator(call, name)
            // The implicit `else` K2 gives an exhaustive `when` without one.
            fqName == "kotlin.internal.ir.noWhenBranchMatchedException" ->
                fail(call, "A when used as a value needs an else branch")
            // The front end's comparison intrinsics (less, EQEQ, ...) are top-level functions.
            fqName.startsWith("kotlin.internal.ir.") -> comparisonValue(call)
            else -> math(call, fqName) ?: fail(call, "calls to $fqName are not supported")
          }
    }
  }

  // Static sets: collections known whole when compiled.

  /**
   * A call making or using a [StaticSet], or null if [function] is none of those: `setOf`, `listOf`
   * and their empty forms, an enum's `entries`, `+`, `-` and `intersect` of a static set, and its
   * `contains` (`in`) and `size`.
   */
  private fun staticSetCall(call: IrCall, function: IrSimpleFunction, fqName: String): Value? {
    val args = call.arguments
    return when (fqName) {
      in SET_BUILDERS -> {
        val elements =
            args.filterNotNull().flatMap { argument ->
              if (argument is IrVararg) {
                argument.elements.map {
                  it as? IrExpression ?: fail(argument, "a set's elements must be written out")
                }
              } else {
                listOf(argument)
              }
            }
        val values = elements.map { setElement(it) }
        StaticSet(if (fqName.endsWith("setOf")) values.distinct() else values)
      }
      in EMPTY_SET_BUILDERS -> StaticSet(emptyList())
      "kotlin.collections.plus",
      "kotlin.collections.minus",
      "kotlin.collections.intersect" -> {
        val left = aliasOrValue(args[0]!!) as? StaticSet ?: return null
        val other = aliasOrValue(args[1]!!)
        val right =
            when (other) {
              is StaticSet -> other.elements
              is Const -> listOf(Const(other.number))
              is Key -> listOf(other)
              else -> fail(args[1]!!, "a static set combines only with constants or static sets")
            }
        // As in Kotlin: a Set's `+` and `-` give a Set and `intersect` always does, while a
        // list's `+` keeps duplicates and its `-` of one element drops only the first match.
        val isSet = call.type.classFqName?.asString() == "kotlin.collections.Set"
        val elements =
            when (fqName) {
              "kotlin.collections.plus" -> left.elements + right
              "kotlin.collections.minus" ->
                  if (other is StaticSet) {
                    left.elements.filter { it !in right }
                  } else {
                    left.elements.toMutableList().apply { remove(right.single()) }
                  }
              else -> left.elements.filter { it in right }
            }
        StaticSet(if (isSet) elements.distinct() else elements)
      }
      else -> {
        val owner = function.parentClassOrNull ?: return null
        val name = function.correspondingPropertySymbol?.owner?.name?.asString()
        when {
          owner.kind == ClassKind.ENUM_CLASS && name == "entries" -> {
            if (owner.kotlinFqName.asString() != SUBSTANCE) {
              fail(call, "only Substance.entries can be looped over")
            }
            StaticSet(
                owner.declarations.filterIsInstance<IrEnumEntry>().map {
                  Key(it.name.asString())
                }
            )
          }
          owner.kotlinFqName.asString() in COLLECTION_CLASSES -> {
            val set = aliasOrValue(args[0]!!) as? StaticSet ?: return null
            when (name ?: function.name.asString()) {
              "size" -> Const(set.elements.size.toDouble())
              "contains" -> membership(set, args[1]!!)
              "isEmpty" -> Const(if (set.elements.isEmpty()) 1.0 else 0.0)
              else ->
                  fail(
                      call,
                      "a static set is looped over, tested with in, or measured with size, " +
                          "not ${function.name}",
                  )
            }
          }
          else -> null
        }
      }
    }
  }

  /** An element of a set being made, which must be a constant number or a [Substance]. */
  private fun setElement(expression: IrExpression): Value =
      when (val value = aliasOrValue(expression)) {
        // By its number alone, so a set's elements compare as numbers however they were written.
        is Const -> Const(value.number)
        is Key -> value
        else ->
            fail(
                expression,
                "a set's elements must be constants, as 2 or Substance.Oxygen, or vals holding them",
            )
      }

  /**
   * Whether [element] is in [set]: a constant when the element is one, else 1 if it equals any of
   * the set's numbers (one `seq` and `or` each).
   */
  private fun membership(set: StaticSet, element: IrExpression): Value {
    val value = aliasOrValue(element)
    if (value is Const) {
      return Const(if (set.elements.any { (it as? Const)?.number == value.number }) 1.0 else 0.0)
    }
    if (value is Key) return Const(if (value in set.elements) 1.0 else 0.0)
    if (value !is Reg) fail(element, "this can't be tested against a set")
    if (set.elements.any { it !is Const }) {
      fail(element, "a number is tested only against a set of numbers")
    }
    val result = newVReg()
    if (set.elements.isEmpty()) {
      emit("move", result, Fixed(Number(0.0)))
      return Reg(result)
    }
    for ((index, number) in set.elements.withIndex()) {
      val equal = if (index == 0) result else newVReg()
      emit("seq", equal, value.reg, operand(number))
      if (index > 0) emit("or", result, result, equal)
    }
    return Reg(result)
  }

  private fun device(call: IrCall, argument: IrExpression?): Dev =
      argument?.let { value(it) } as? Dev
          ?: fail(argument ?: call, "this device must be a pin, or a val holding one")

  // The typed device API: typed devices, their entry points, batches and slots.

  /**
   * A call into the generated typed device API, or null if [cls] isn't part of it. An entry point
   * (`GrowLights`) gives a typed device (`at(d0)`) or a [Batch] (`all`, `named("x")`); a typed
   * device's logic types load and store like a pin's; a batch is read and written through its
   * collection operations ([batchCall]).
   */
  private fun typedCall(call: IrCall, cls: IrClass, function: IrSimpleFunction): Value? {
    val property = function.correspondingPropertySymbol?.owner
    val name = property?.name?.asString() ?: function.name.asString()
    val isSetter = property != null && function == property.setter
    val args = call.arguments
    return when {
      cls.kind == ClassKind.OBJECT && !cls.isCompanion && cls.prefabName() != null -> {
        val prefab = prefabHash(cls)
        when (name) {
          "at" -> device(call, args[1])
          "all" -> Batch(prefab, null)
          "named" ->
              Batch(
                  prefab,
                  stringConstant(call, StringConstant.Kind.HASH, args[1], "named(...)").number,
              )
          else -> fail(call, "$name is not supported")
        }
      }
      cls.isBatchClass() -> batchCall(call, name, property)
      cls.kotlinFqName.asString() == SLOTS_CLASS && name == "get" -> {
        val owner =
            aliasOrValue(args[0]!!) as? SlotsOf
                ?: fail(call, "these slots must be a device's or a batch's")
        // A copy of a computed number, so a later change to its variable doesn't move the slot.
        SlotOf(owner.owner, operand(snapshot(slotNumber(args[1]!!))))
      }
      cls.kotlinFqName.asString() in VIEW_CLASSES -> {
        // Only a view's own members and Ratios' key: any other (equals, hashCode, ...) names no
        // logic type.
        if (property == null && !(name == "get" && cls.name.asString() == "Ratios")) {
          fail(call, "$name is not supported on a view, only its logic types")
        }
        viewCall(call, cls, name, isSetter)
      }
      property != null &&
          cls.isTypedDevice() &&
          function.returnType.classFqName?.asString() in VIEW_CLASSES ->
          startView(call, property, viewOwner(call, args[0]), property.viewAccess(cls))
      // Only a slot's logic types: any other member (hashCode, ...) is left unsupported.
      property != null && cls.isSubclassOf(DEVICE_SLOT) -> slotCall(call, name, isSetter)
      property != null && cls.isTypedDevice() && name == SLOTS -> SlotsOf(device(call, args[0]))
      property != null && cls.isTypedDevice() && property.slotNumber() != null ->
          SlotOf(device(call, args[0]), Fixed(Number(property.slotNumber()!!.toDouble())))
      property != null && cls.isTypedDevice() -> {
        val device = Fixed(device(call, args[0]).device)
        if (isSetter) {
          emit(
              "s",
              device,
              Fixed(Name(storedLogicType(name))),
              storedValue(call, name, args.last()!!),
          )
          NoValue
        } else {
          if (name == TEXT) fail(call, "Text can only be written")
          val result = newVReg()
          emit("l", result, device, Fixed(Name(name)))
          Reg(result)
        }
      }
      else -> null
    }
  }

  // Views: a device's logic types grouped (`Ports.Input.Pressure`, `Ratios[Substance.Oxygen]`).

  /** The device or batch element a view is taken of. */
  private fun viewOwner(call: IrCall, argument: IrExpression?): Value =
      argument?.let { aliasOrValue(it) }?.takeIf { it is Dev || it is Element }
          ?: fail(argument ?: call, "this device must be a pin, or a val holding one")

  /** A device's view [property] of [owner], checked against [access] if the device is typed. */
  private fun startView(
      call: IrCall,
      property: IrProperty,
      owner: Value,
      access: ViewAccess?,
  ): View {
    val name = property.name.asString()
    val shape = property.getter?.returnType?.classFqName?.shortName()?.asString()
    return when (shape) {
      "Ports" -> View(owner, "Ports", "", "", access)
      "Ratios" -> View(owner, "Ratios", "Ratio", "", access)
      "Vector",
      "MutableVector" -> View(owner, "Vector", VECTOR_PREFIXES[name] ?: name, "", access)
      "Transfer" -> View(owner, "Transfer", name, "", access)
      else -> fail(call, "$name is not supported")
    }
  }

  /** The logic types a typed device's view reaches, from its `@LogicTypes`. */
  private fun IrProperty.viewAccess(device: IrClass): ViewAccess? {
    val annotation =
        annotations.firstOrNull { it.type.classFqName?.asString() == LOGIC_TYPES } ?: return null
    fun names(index: Int) =
        ((annotation.arguments[index] as? IrConst)?.value as? String)
            .orEmpty()
            .split(',')
            .filter { it.isNotEmpty() }
            .toSet()
    return ViewAccess(device.name.asString(), names(0), names(1))
  }

  /**
   * A member, key or index of a view: another view (`Ports.Input`), or the logic type it names,
   * read or written there.
   */
  private fun viewCall(call: IrCall, cls: IrClass, name: String, isSetter: Boolean): Value {
    val args = call.arguments
    val view =
        aliasOrValue(args[0]!!) as? View
            ?: fail(call, "this must be a device's ${cls.name}, or a val holding one")
    return when (view.shape) {
      "Ports" -> View(view.owner, "Port", "", name, view.access)
      "Port" ->
          if (name == "Ratios") View(view.owner, "Ratios", "Ratio", view.suffix, view.access)
          else viewLogicType(call, view, name, isSetter, args.getOrNull(1))
      "Ratios" -> {
        if (name == "keys") return ratioKeys(view)
        val key =
            args[1]?.let { aliasOrValue(it) } as? Key
                ?: fail(args[1] ?: call, "the substance must be written out, as Substance.Oxygen")
        viewLogicType(call, view, key.name, isSetter = false, written = null)
      }
      else -> viewLogicType(call, view, name, isSetter, args.getOrNull(1))
    }
  }

  /**
   * The logic type [member] of [view] names, read, or written with [written]: an `l` or `s` on a
   * device, or the name alone ([Named]) on a batch's element, for the batch to read.
   */
  private fun viewLogicType(
      call: IrCall,
      view: View,
      member: String,
      isSetter: Boolean,
      written: IrExpression?,
  ): Value {
    val logicType = view.prefix + member + view.suffix
    checkViewAccess(call, view.access, logicType, isSetter)
    val owner = view.owner
    if (owner is Element) {
      if (isSetter) fail(call, "a batch is written with forEach { ... }")
      return Named(logicType)
    }
    val device = Fixed((owner as Dev).device)
    return if (isSetter) {
      emit("s", device, Fixed(Name(logicType)), operand(value(written!!)))
      NoValue
    } else {
      Reg(newVReg()).also { emit("l", it.reg, device, Fixed(Name(logicType))) }
    }
  }

  /** Fails unless a typed device's view has [logicType] to read, or to write if [isSetter]. */
  private fun checkViewAccess(
      element: IrElement,
      access: ViewAccess?,
      logicType: String,
      isSetter: Boolean,
  ) {
    if (access == null) return
    when {
      isSetter && logicType !in access.written ->
          fail(
              element,
              if (logicType in access.read) "${access.device}'s $logicType can only be read"
              else "${access.device} has no $logicType",
          )
      !isSetter && logicType !in access.read ->
          fail(
              element,
              if (logicType in access.written) "${access.device}'s $logicType can only be written"
              else "${access.device} has no $logicType",
          )
    }
  }

  /** An enum entry, which is only a view's key: a [Substance]. */
  private fun key(expression: IrGetEnumValue): Value {
    val entry = expression.symbol.owner
    if (entry.parentAsClass.kotlinFqName.asString() != SUBSTANCE) {
      fail(expression, "${entry.parentAsClass.name} entries can only be used as d0[LogicType.X]")
    }
    return Key(entry.name.asString())
  }

  /** `Text` stores its string, packed by `STR`, to `Setting`. */
  private fun storedLogicType(name: String) = if (name == TEXT) "Setting" else name

  private fun storedValue(call: IrCall, name: String, argument: IrExpression): TirOperand =
      if (name == TEXT) {
        Fixed(stringConstant(call, StringConstant.Kind.STR, argument, TEXT).operand)
      } else {
        operand(value(argument))
      }

  /** A slot's logic type on a device's slot, loaded with `ls` and stored with `ss`. */
  private fun slotCall(call: IrCall, name: String, isSetter: Boolean): Value {
    val slot =
        aliasOrValue(call.arguments[0]!!) as? SlotOf
            ?: fail(call, "this slot must be slots[...], a named slot, or a val holding one")
    val owner = slot.owner as? Dev ?: fail(call, "this slot must be a device's")
    val logicType = Fixed(Name(name))
    return if (isSetter) {
      val stored = operand(value(call.arguments.last()!!))
      emit("ss", Fixed(owner.device), slot.number, logicType, stored)
      NoValue
    } else {
      Reg(newVReg()).also { emit("ls", it.reg, Fixed(owner.device), slot.number, logicType) }
    }
  }

  /**
   * A batch's collection operation: `map` picks a logic type for a [MAPPED_TERMINALS] function;
   * `sumOf`, `minOf`, `maxOf` and `count { ... }` read one with a batch method; `count()` counts
   * the devices; `forEach` writes. The only property is a batch of devices' `slots`.
   */
  private fun batchCall(call: IrCall, name: String, property: IrProperty?): Value {
    val args = call.arguments
    val source = aliasOrValue(args[0]!!)
    if (source !is Batch && !(source is SlotOf && source.owner is Batch)) {
      fail(call, "this batch must be all, named(...), a batch's slots[...], or a val holding one")
    }
    if (property != null) {
      if (name == SLOTS && source is Batch) return SlotsOf(source)
      fail(call, "$name is not supported on a batch")
    }
    return when (name) {
      "map" -> Mapped(source, selected(call, args[1]))
      "sumOf" -> batchRead(source, selected(call, args[1]), "Sum")
      "minOf" -> batchRead(source, selected(call, args[1]), "Minimum")
      "maxOf" -> batchRead(source, selected(call, args[1]), "Maximum")
      // count { it.On } sums the logic type; count() counts the devices, reading a logic type
      // every device has. The game's named batch read counts every device of the prefab, whatever
      // its name, so a named batch has no count().
      "count" ->
          when {
            args.size > 1 -> batchRead(source, selected(call, args[1]), "Sum")
            source is Batch && source.name != null ->
                fail(call, "the game can't count a named batch, only all of a prefab's devices")
            else -> batchRead(source, "PrefabHash", "Count")
          }
      "forEach" -> {
        batchWrites(call, source, args[1])
        NoValue
      }
      in BATCH_TESTS -> {
        // As a value, the test's comparison sets a register; in a condition, branch() uses it.
        val test = batchTest(call, source, name)
        Reg(newVReg()).also { emit("s${test.op}z", it.reg, operand(test.a)) }
      }
      else -> fail(call, "$name is not supported on a batch")
    }
  }

  /**
   * `any`, `all` or `none { it.On }`: one batch read and a comparison with 0. `any` and `none` read
   * the `Maximum` (negative infinity for no devices, so `any` is false and `none` true, as in
   * Kotlin); `all` reads the `Minimum` (0 for no devices, so false, where Kotlin gives true). The
   * logic type is a Boolean one, which the game reports as 0 or 1, so the read is never NaN, and
   * comparing it with 0 needs no [truth].
   */
  private fun batchTest(call: IrCall, source: Value, name: String): Comparison {
    val (method, op) = BATCH_TESTS.getValue(name)
    val read = batchRead(source, selected(call, call.arguments[1]), method)
    return Comparison(op, read, Const(0.0), mayBeNaN = false)
  }

  /** A call of a batch's `any`, `all` or `none`, as the comparison [branch] can jump on. */
  private fun batchTestCondition(condition: IrCall): Comparison? {
    val function = condition.symbol.owner
    val name = function.name.asString()
    if (name !in BATCH_TESTS || function.parentClassOrNull?.isBatchClass() != true) return null
    val source = aliasOrValue(condition.arguments[0]!!)
    if (source !is Batch && !(source is SlotOf && source.owner is Batch)) return null
    return batchTest(condition, source, name)
  }

  /**
   * The logic type a reading lambda picks: `{ it.Charge }`, or a property reference such as
   * `GrowLight::Charge`.
   */
  private fun selected(call: IrCall, argument: IrExpression?): String {
    when (argument) {
      is IrFunctionExpression -> {
        val lambda = argument.function
        val parameter = lambda.parameters.single { it.kind == IrParameterKind.Regular }
        val statement = (lambda.body as? IrBlockBody)?.statements?.singleOrNull()
        val get = ((statement as? IrReturn)?.value ?: statement) as? IrCall
        val property = get?.symbol?.owner?.correspondingPropertySymbol?.owner
        if (
            property != null &&
                get.symbol.owner == property.getter &&
                (get.arguments[0] as? IrGetValue)?.symbol == parameter.symbol
        ) {
          return property.name.asString()
        }
      }
      is IrPropertyReference -> return argument.symbol.owner.name.asString()
      else -> {}
    }
    // A view's logic type: { it.Ports.Input.Pressure }, { it.Ratios[Substance.Oxygen] }.
    (argument as? IrFunctionExpression)?.function?.let { lambda ->
      val parameter = lambda.parameters.single { it.kind == IrParameterKind.Regular }
      val statement = (lambda.body as? IrBlockBody)?.statements?.singleOrNull()
      val expression = ((statement as? IrReturn)?.value ?: statement) as? IrExpression
      if (expression != null && expression.isViewChainOf(parameter)) {
        val named = elementView(parameter) { aliasOrValue(expression) } as? Named
        if (named != null) return named.logicType
      }
    }
    fail(argument ?: call, "a batch is read by picking a logic type, as { it.Charge }")
  }

  /** [block]'s result with the batch lambda's [parameter] standing for a batch's element. */
  private fun <T> elementView(parameter: IrValueParameter, block: () -> T): T {
    variables[parameter] = Element
    try {
      return block()
    } finally {
      variables.remove(parameter)
    }
  }

  /**
   * Whether this is a chain of view members, keys and indexes from [parameter] that ends at a logic
   * type: calls on view classes, or a device's view property, whose keys don't depend on the
   * element.
   */
  private fun IrExpression.isViewChainOf(parameter: IrValueParameter): Boolean {
    val call = this as? IrCall ?: return false
    val function = call.symbol.owner
    val cls = function.parentClassOrNull ?: return false
    val receiver = call.arguments[0] ?: return false
    val onView = cls.kotlinFqName.asString() in VIEW_CLASSES
    val viewProperty = function.returnType.classFqName?.asString() in VIEW_CLASSES
    if (!onView && !viewProperty) return false
    if (call.arguments.drop(1).any { it != null && it.mentions(parameter) }) return false
    return (receiver is IrGetValue && receiver.symbol == parameter.symbol && viewProperty) ||
        (onView && receiver.isViewChainOf(parameter))
  }

  /** The `lb`, `lbn`, `lbs` or `lbns` reading [logicType] of [source] with batch [method]. */
  private fun batchRead(source: Value, logicType: String, method: String): Value {
    val result = newVReg()
    val type = Fixed(Name(logicType))
    val mode = Fixed(Name(method))
    when (source) {
      is Batch -> {
        val prefab = Fixed(Number(source.prefab))
        if (source.name == null) emit("lb", result, prefab, type, mode)
        else emit("lbn", result, prefab, Fixed(Number(source.name)), type, mode)
      }
      is SlotOf -> {
        val batch = source.owner as Batch
        val prefab = Fixed(Number(batch.prefab))
        if (batch.name == null) {
          emit("lbs", result, prefab, source.number, type, mode)
        } else {
          emit("lbns", result, prefab, Fixed(Number(batch.name)), source.number, type, mode)
        }
      }
      else -> error("not a batch")
    }
    return Reg(result)
  }

  /**
   * `forEach { it.On = true }`: each assignment to the element's logic type, or to one of its
   * slots' (`it.slots[0].Open`, `it.Battery.On`), writes every device (`sb`, `sbn`, `sbs`). Each
   * value is computed once for all the devices, even for none, so it may have no side effect (see
   * [hasSideEffects]). One that reads the batch again reads it before any device is written.
   */
  private fun batchWrites(call: IrCall, source: Value, argument: IrExpression?) {
    val lambda =
        (argument as? IrFunctionExpression)?.function
            ?: fail(
                argument ?: call,
                "forEach needs a lambda assigning logic types, as { it.On = true }",
            )
    val parameter = lambda.parameters.single { it.kind == IrParameterKind.Regular }
    val statements = (lambda.body as? IrBlockBody)?.statements ?: emptyList()
    for (statement in statements) {
      if (statement is IrReturn) fail(statement, "forEach can't return, only assign logic types")
      val set = statement as? IrCall
      val property = set?.symbol?.owner?.correspondingPropertySymbol?.owner
      if (property == null || set.symbol.owner != property.setter) {
        fail(statement, "forEach may only assign logic types, as it.On = true")
      }
      val written = set.arguments.last()!!
      val receiver = set.arguments[0]!!
      // A view's logic type: it.Ports.Output.Setting = x.
      if (
          set.symbol.owner.parentClassOrNull?.kotlinFqName?.asString() in VIEW_CLASSES &&
              receiver.isViewChainOf(parameter)
      ) {
        val view = elementView(parameter) { aliasOrValue(receiver) } as View
        val logicType = view.prefix + property.name.asString() + view.suffix
        checkViewAccess(set, view.access, logicType, isSetter = true)
        checkBatchValue(written, parameter)
        batchWrite(set, source, null, Fixed(Name(logicType)), operand(value(written)))
        continue
      }
      val slot = writeTarget(receiver, parameter)
      checkBatchValue(written, parameter)
      val name = property.name.asString()
      val stored = Fixed(Name(storedLogicType(name)))
      batchWrite(set, source, slot, stored, storedValue(set, name, written))
    }
  }

  /** Fails unless [written] can be computed once for every device of the batch. */
  private fun checkBatchValue(written: IrExpression, parameter: IrValueParameter) {
    if (written.mentions(parameter)) {
      fail(written, "a value written to a batch can't use the batch's devices (it)")
    }
    if (written.hasSideEffects()) {
      fail(
          written,
          "a value written to a batch is computed once, so it can't have side effects " +
              "(assign, write a device, sleep or yield)",
      )
    }
  }

  /** Where a `forEach` assignment writes: the element itself (null), or one of its slots. */
  private fun writeTarget(receiver: IrExpression, parameter: IrValueParameter): TirOperand? {
    if (receiver is IrGetValue && receiver.symbol == parameter.symbol) return null
    val call = receiver as? IrCall
    val function = call?.symbol?.owner
    val property = function?.correspondingPropertySymbol?.owner
    val onElement = (call?.arguments?.getOrNull(0) as? IrGetValue)?.symbol == parameter.symbol
    // A named slot, such as it.Battery.
    property?.slotNumber()?.let { if (onElement) return Fixed(Number(it.toDouble())) }
    // it.slots[n], with n not depending on the devices.
    if (
        function?.parentClassOrNull?.kotlinFqName?.asString() == SLOTS_CLASS &&
            function.name.asString() == "get"
    ) {
      val slots = call.arguments[0] as? IrCall
      val slotsOwner = slots?.symbol?.owner?.correspondingPropertySymbol?.owner
      val number = call.arguments[1]!!
      if (
          slotsOwner?.name?.asString() == SLOTS &&
              (slots.arguments[0] as? IrGetValue)?.symbol == parameter.symbol &&
              !number.mentions(parameter)
      ) {
        if (number.hasSideEffects()) {
          fail(
              number,
              "a slot number written to a batch is computed once, so it can't have side effects " +
                  "(assign, write a device, sleep or yield)",
          )
        }
        return operand(snapshot(slotNumber(number)))
      }
    }
    fail(receiver, "forEach may only assign the logic types of it, or of its slots")
  }

  private fun batchWrite(
      element: IrElement,
      source: Value,
      slot: TirOperand?,
      logicType: TirOperand,
      stored: TirOperand,
  ) {
    val batch = (if (source is SlotOf) source.owner else source) as Batch
    val prefab = Fixed(Number(batch.prefab))
    val number = slot ?: (source as? SlotOf)?.number
    when {
      number == null && batch.name == null -> emit("sb", prefab, logicType, stored)
      number == null -> emit("sbn", prefab, Fixed(Number(batch.name!!)), logicType, stored)
      // The game has `sbs` but no named form of it.
      batch.name != null -> fail(element, "the slots of a named batch can't be written")
      else -> emit("sbs", prefab, number, logicType, stored)
    }
  }

  /** Whether this expression reads [parameter] anywhere. */
  private fun IrExpression.mentions(parameter: IrValueParameter): Boolean {
    var found = false
    acceptVoid(
        object : IrVisitorVoid() {
          override fun visitElement(element: IrElement) {
            if (element is IrGetValue && element.symbol == parameter.symbol) found = true
            element.acceptChildrenVoid(this)
          }
        }
    )
    return found
  }

  /**
   * A runtime function with a side effect that is not an assignment: a device's indexed write
   * (`Device.set`), a batch's `forEach`, `sleep` or `yield`. Every `forEach` that compiles assigns
   * logic types in its lambda, which [assigns] finds on its own; the call is counted too, so that
   * doesn't depend on what [batchWrites] accepts.
   */
  private fun IrSimpleFunction.isSideEffect(): Boolean =
      kotlinFqName.asString() in SIDE_EFFECTS ||
          (name.asString() == "forEach" && parentClassOrNull?.isBatchClass() == true)

  /** A batch's or a batch of slots' class, whose members are its collection operations. */
  private fun IrClass.isBatchClass(): Boolean =
      kotlinFqName.asString() == BATCH_CLASS ||
          superTypes.any { it.classOrNull?.owner?.isBatchClass() == true }

  /**
   * A slot's number, which if constant must be one a device can have: the game has no slot below 0,
   * and none above 255, the number of a robotic arm dock's target slot.
   */
  private fun slotNumber(expression: IrExpression): Value =
      value(expression).also {
        if (it is Const && it.number !in 0.0..MAX_SLOT.toDouble()) {
          fail(expression, "slot numbers run from 0 to $MAX_SLOT")
        }
      }

  /** The number of the slot a generated named slot property stands for. */
  private fun IrProperty.slotNumber(): Int? =
      annotations
          .firstOrNull { it.type.classFqName?.asString() == SLOT_NUMBER }
          ?.let { (it.arguments[0] as? IrConst)?.value as? Int }

  /** A prefab used as a value (a typed device's companion object): its hash. */
  private fun prefab(expression: IrGetObjectValue): Value {
    val cls = expression.symbol.owner
    if (!cls.isCompanion || !cls.isSubclassOf(PREFAB))
        fail(expression, "This expression is not supported")
    return Const(prefabHash(cls.parentAsClass))
  }

  private fun prefabHash(cls: IrClass): Double =
      Hashes.hash(cls.prefabName() ?: error("${cls.name} has no prefab name")).toDouble()

  private fun IrClass.prefabName(): String? =
      annotations
          .firstOrNull { it.type.classFqName?.asString() == PREFAB_NAME }
          ?.let { (it.arguments[0] as? IrConst)?.value as? String }

  private fun IrClass.isSubclassOf(fqName: String) = superTypes.any {
    it.classFqName?.asString() == fqName
  }

  /** A generated typed device, a family's interface, or [TYPED_DEVICE] itself. */
  private fun IrClass.isTypedDevice(): Boolean {
    val fqName = kotlinFqName.asString()
    return fqName == TYPED_DEVICE ||
        isSubclassOf(TYPED_DEVICE) ||
        (kind == ClassKind.INTERFACE && kotlinFqName.parent().asString() == "ktrapic")
  }

  /** A value that names something on the chip rather than holding a number: kept as is in a val. */
  private fun Value.isAlias() =
      this is Dev ||
          this is Batch ||
          this is Mapped ||
          this is SlotsOf ||
          this is SlotOf ||
          this is View ||
          this is Key ||
          this is StaticSet

  private fun logicType(call: IrCall, argument: IrExpression?): Fixed {
    if (argument !is IrGetEnumValue) {
      fail(argument ?: call, "the logic type must be written out, as LogicType.X")
    }
    return Fixed(Name(argument.symbol.owner.name.asString()))
  }

  private fun stringConstant(
      call: IrCall,
      kind: StringConstant.Kind,
      argument: IrExpression? = call.arguments[0],
      what: String = "${kind.name.lowercase()}(...)",
  ): Const {
    val text = (argument as? IrConst)?.value as? String
    if (text == null) fail(call, "$what needs a constant string")
    // The game would take a tab or another control character other than a line break, but in
    // source text one is almost certainly a mistake.
    if (text.any { it.isISOControl() }) {
      fail(
          call,
          "$what text can't contain a line break or another control character",
      )
    }
    // Both the constructor and numericValue reject text the game can't take (STR's length and
    // ASCII limits are checked when the value is computed).
    return try {
      val constant = StringConstant(kind, text)
      Const(constant.numericValue, constant)
    } catch (e: IllegalArgumentException) {
      fail(call, e.message ?: "bad $what text")
    }
  }

  /**
   * Arithmetic and logic on Int, Double and Boolean. IC10 has only doubles, so an Int behaves as on
   * the JVM only while it stays within the Int range: the chip doesn't wrap, and its bitwise ops
   * and shifts work on integers of 53 bits plus sign. Constant operands fold to what the chip
   * computes, and a constant Int result outside the Int range, where the chip and the JVM would
   * differ, is an error.
   */
  private fun operator(call: IrCall, name: String): Value {
    val args = call.arguments.map { it ?: fail(call, "missing operand") }
    val int = call.type.isInt()
    val values by lazy { operands(args) }
    val result = operation(call, name, int, args, { values[0] }, { values[1] })
    if (int && result is Const && result.number.isFinite() && result.number !in INT_RANGE) {
      fail(
          call,
          "This constant Int expression overflows: the chip would give " +
              "${result.operand.text()}, where the JVM wraps",
      )
    }
    // A constant Int that is zero is +0, as on the JVM, even where the chip's arithmetic (0 * -1)
    // would give -0.
    if (int && result is Const && result.number == 0.0) return Const(0.0)
    return result
  }

  private fun operation(
      call: IrCall,
      name: String,
      int: Boolean,
      args: List<IrExpression>,
      a: () -> Value,
      b: () -> Value,
  ): Value =
      when (name) {
        "plus" -> arithmetic("add", a(), b()) { x, y -> x + y }
        "minus" -> arithmetic("sub", a(), b()) { x, y -> x - y }
        "times" -> arithmetic("mul", a(), b()) { x, y -> x * y }
        "div" ->
            if (int) intDivision(call, a(), b()) else arithmetic("div", a(), b()) { x, y -> x / y }
        "rem" -> remainder(call, a(), b(), int)
        // A Double is negated by multiplying by -1, so -0.0 is negative zero as on the JVM (0 - x
        // would give +0.0). An Int has no negative zero, so 0 - x is right for it.
        "unaryMinus" ->
            if (int) arithmetic("sub", Const(0.0), a()) { x, y -> x - y }
            else arithmetic("mul", a(), Const(-1.0)) { x, _ -> -x }
        "unaryPlus" -> a()
        "inc" -> arithmetic("add", a(), Const(1.0)) { x, y -> x + y }
        "dec" -> arithmetic("sub", a(), Const(1.0)) { x, y -> x - y }
        "toDouble" -> a()
        "toInt" -> if (args[0].type.isInt()) a() else toInt(a())
        "not" -> unary("seqz", a()) { x -> if (x == 0.0) 1.0 else 0.0 }
        "and" -> bitwise(call, "and", a(), b(), Ic10Math::and) { x, y -> x and y }
        "or" -> bitwise(call, "or", a(), b(), Ic10Math::or) { x, y -> x or y }
        "xor" -> bitwise(call, "xor", a(), b(), Ic10Math::xor) { x, y -> x xor y }
        "shl" -> shift(call, "sll", a(), b(), Ic10Math::shiftLeft) { x, y -> x shl y }
        "shr" -> shift(call, "sra", a(), b(), Ic10Math::shiftRightArithmetic) { x, y -> x shr y }
        "ushr" -> shift(call, "srl", a(), b(), Ic10Math::shiftRightLogical) { x, y -> x ushr y }
        "inv" -> bitwise(call, "not", a(), null, { x, _ -> Ic10Math.not(x) }) { x, _ -> x.inv() }
        else -> fail(call, "${call.symbol.owner.kotlinFqName} is not supported")
      }

  /**
   * A shift; a constant count outside 0..31, which the chip and the JVM treat differently, is an
   * error.
   */
  private fun shift(
      call: IrCall,
      op: String,
      a: Value,
      b: Value,
      chip: (Double, Double) -> Double,
      jvm: (Int, Int) -> Int,
  ): Value {
    if (b is Const && (b.number < 0 || b.number > 31)) {
      fail(call, "A shift count must be between 0 and 31")
    }
    return bitwise(call, op, a, b, chip, jvm)
  }

  /**
   * `toInt()` as on the JVM: truncated, limited to the Int range, and 0 for NaN. The final `add 0`
   * turns the -0.0 that truncating a value in (-1, 0) gives into the Int 0 (-0 + 0 is +0), which
   * would otherwise show once the Int becomes a Double again (`1.0 / n.toDouble()`).
   */
  private fun toInt(a: Value): Value {
    if (a is Const) return Const(a.number.toInt().toDouble())
    val nan = newVReg()
    val result = newVReg()
    emit("snan", nan, operand(a))
    emit("trunc", result, operand(a))
    emit("max", result, result, Fixed(Number(Int.MIN_VALUE.toDouble())))
    emit("min", result, result, Fixed(Number(Int.MAX_VALUE.toDouble())))
    emit("select", result, nan, Fixed(Number(0.0)), result)
    emit("add", result, result, Fixed(Number(0.0)))
    return Reg(result)
  }

  /**
   * Evaluates [expressions] in order. A register one of them yields is copied first if a later one
   * may assign it, since Kotlin uses the value an operand had when it was evaluated (`x + x++` adds
   * the old `x`). Where [aliases] is set, the expression is a view or a substance, an inline
   * function's argument, kept as the [View] or [Key] it is.
   */
  private fun operands(
      expressions: List<IrExpression>,
      aliases: List<Boolean> = emptyList(),
  ): List<Value> = expressions.mapIndexed { index, expression ->
    val value =
        if (aliases.getOrElse(index) { false }) {
          aliasOrValue(expression).takeIf { it is View || it is Key || it is StaticSet }
              ?: fail(
                  expression,
                  "this must be a device's view, a substance or a static set, or a val holding one",
              )
        } else {
          value(expression)
        }
    if (expressions.drop(index + 1).any { it.mayAssign() }) snapshot(value) else value
  }

  /** [value] in a register of its own if it is in one, so later assignments leave it alone. */
  private fun snapshot(value: Value): Value =
      if (value is Reg) Reg(newVReg()).also { move(it.reg, value) } else value

  /**
   * True if evaluating this expression may assign a variable or property, including a property
   * assigned by one of the program's functions it calls.
   */
  private fun IrExpression.mayAssign(): Boolean =
      assigns(this, variables = true, effects = false, mutableSetOf())

  /**
   * True if evaluating this expression does anything besides compute its value: assigns a variable
   * or property (a logic type or slot's included), writes a device by index (`d0[LogicType.On] =
   * 1.0`) or a batch (`forEach`), or calls `sleep` or `yield`, itself or in one of the program's
   * functions it calls.
   */
  private fun IrExpression.hasSideEffects(): Boolean =
      assigns(this, variables = true, effects = true, mutableSetOf())

  /**
   * True if [element] assigns a property, or a variable when [variables] is set, or, when [effects]
   * is set, calls a runtime function with a side effect. A call to one of the program's functions
   * counts what its body does, except the variables it assigns, which are its own. [visited] holds
   * the functions already looked into.
   */
  private fun assigns(
      element: IrElement,
      variables: Boolean,
      effects: Boolean,
      visited: MutableSet<IrSimpleFunction>,
  ): Boolean {
    var assigns = false
    element.acceptVoid(
        object : IrVisitorVoid() {
          override fun visitElement(element: IrElement) {
            if (!assigns) element.acceptChildrenVoid(this)
          }

          override fun visitSetValue(expression: IrSetValue) {
            if (variables) assigns = true else super.visitSetValue(expression)
          }

          override fun visitSetField(expression: IrSetField) {
            assigns = true
          }

          override fun visitCall(expression: IrCall) {
            val function = expression.symbol.owner
            if (function == function.correspondingPropertySymbol?.owner?.setter) {
              assigns = true
              return
            }
            if (effects && function.isSideEffect()) {
              assigns = true
              return
            }
            if (function in graph.functions && visited.add(function)) {
              val body = function.body
              if (body != null && assigns(body, variables = false, effects, visited)) {
                assigns = true
                return
              }
            }
            super.visitCall(expression)
          }
        }
    )
    return assigns
  }

  private fun arithmetic(op: String, a: Value, b: Value, fold: (Double, Double) -> Double): Value {
    if (a is Const && b is Const) return Const(fold(a.number, b.number))
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

  /**
   * A bitwise operation on Ints ([b] is null for `inv`). Constants fold as the chip computes them
   * ([chip], see [Ic10Math]); where that differs from the JVM's 32-bit result ([jvm]), as with `-1
   * ushr 28`, it is an error rather than a silent difference.
   */
  private fun bitwise(
      call: IrCall,
      op: String,
      a: Value,
      b: Value?,
      chip: (Double, Double) -> Double,
      jvm: (Int, Int) -> Int,
  ): Value {
    if (!call.type.isInt()) fail(call, "Boolean and, or and xor are not supported (use && and ||)")
    if (a is Const && (b == null || b is Const)) {
      val y = (b as Const?)?.number ?: 0.0
      val onChip = chip(a.number, y)
      val onJvm = jvm(a.number.toInt(), y.toInt()).toDouble()
      if (onChip != onJvm) {
        fail(
            call,
            "This constant Int expression gives ${Number.format(onChip)} on the chip but " +
                "${Number.format(onJvm)} on the JVM",
        )
      }
      return Const(onChip)
    }
    val result = newVReg()
    if (b == null) emit(op, result, operand(a)) else emit(op, result, operand(a), operand(b))
    return Reg(result)
  }

  /** Int division truncates towards zero, as on the JVM. */
  private fun intDivision(call: IrCall, a: Value, b: Value): Value {
    if (b is Const && b.number == 0.0) fail(call, "division by zero")
    if (a is Const && b is Const) return Const(truncate(a.number / b.number))
    val result = newVReg()
    emit("div", result, operand(a), operand(b))
    emit("trunc", result, result)
    return Reg(result)
  }

  /**
   * `a % b` is `a - truncate(a / b) * b`, which takes the sign of `a`, as on the JVM, unlike IC10's
   * `mod`. It is exact for Ints; for Doubles the division and multiplication round, so it can
   * differ from the JVM's exact remainder. A constant one folds with the same arithmetic.
   */
  private fun remainder(call: IrCall, a: Value, b: Value, int: Boolean): Value {
    if (int && b is Const && b.number == 0.0) fail(call, "division by zero")
    if (a is Const && b is Const) {
      return Const(a.number - truncate(a.number / b.number) * b.number)
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
    val values by lazy { operands(args).map { operand(it) } }
    return when {
      op != null && args.size == 1 -> {
        val result = newVReg()
        emit(op, result, values[0])
        Reg(result)
      }
      name == "<get-absoluteValue>" -> {
        val result = newVReg()
        emit("abs", result, values[0])
        Reg(result)
      }
      name == "roundToInt" -> {
        // Kotlin rounds halves up here (Math.round), unlike round(), which rounds them to even.
        // floor(x + 0.5) would round x + 0.5 first, taking 0.49999999999999994 to 1, so this
        // compares the fraction x - floor(x), which is exact, with 0.5 instead.
        // It saturates like toInt(); the JVM throws for NaN, which gives 0 here, as toInt() does.
        val x = operands(args)[0]
        val whole = unary("floor", x) { floor(it) }
        val fraction = arithmetic("sub", x, whole) { a, b -> a - b }
        val up = arithmetic("sge", fraction, Const(0.5)) { a, b -> if (a >= b) 1.0 else 0.0 }
        toInt(arithmetic("add", whole, up) { a, b -> a + b })
      }
      name in setOf("atan2", "max", "min", "pow") && args.size == 2 -> {
        val result = newVReg()
        emit(name, result, values[0], values[1])
        Reg(result)
      }
      fqName == "kotlin.ranges.coerceAtLeast" -> {
        val result = newVReg()
        emit("max", result, values[0], values[1])
        Reg(result)
      }
      fqName == "kotlin.ranges.coerceAtMost" -> {
        val result = newVReg()
        emit("min", result, values[0], values[1])
        Reg(result)
      }
      fqName == "kotlin.ranges.coerceIn" && args.size == 3 -> {
        val result = newVReg()
        emit("max", result, values[0], values[1])
        emit("min", result, result, values[2])
        Reg(result)
      }
      else -> null
    }
  }

  // Comparisons and conditions.

  /**
   * [mayBeNaN] is true for an ordered comparison of numbers: with a NaN operand both `a < b` and
   * `a >= b` are false, so the opposite IC10 branch is not the negation. Ints count too, since a
   * run-time `0 / 0` gives NaN.
   */
  private class Comparison(val op: String, val a: Value, val b: Value, val mayBeNaN: Boolean)

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
      // A prefab is its hash; Kotlin allows only == and != on one.
      if (!type.isInt() && !type.isDouble() && !type.isBoolean() && !type.isPrefab()) {
        fail(call, "Comparing ${type.render()} values is not supported")
      }
    }
    val boolean = left.type.isBoolean()
    // Comparisons can't tell -0.0 from 0.0, so a negative-zero constant is compared as 0, which
    // needs no line to compute and can use the `z` forms.
    val (a, b) =
        operands(listOf(left, right)).map {
          when {
            boolean -> truth(it)
            it is Const && it.number.toRawBits() == NEGATIVE_ZERO_BITS -> Const(0.0)
            else -> it
          }
        }
    return Comparison(condition, a, b, mayBeNaN = condition != "eq" && !boolean)
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

  /**
   * Emits a jump to [target] taken when [condition] is [whenTrue]. A condition is an expression,
   * not a statement, and is often on a line of its own (as is each operand of a multi-line `&&` or
   * `||`), so its code is attributed to where it is.
   */
  private fun branch(condition: IrExpression, whenTrue: Boolean, target: String) {
    at(condition) { emitBranch(condition, whenTrue, target) }
  }

  private fun emitBranch(condition: IrExpression, whenTrue: Boolean, target: String) {
    if (condition is IrConst && condition.kind == IrConstKind.Boolean) {
      if (condition.value == whenTrue) emit("j", Fixed(LabelRef(target)))
      return
    }
    if (condition is IrCall) {
      batchTestCondition(condition)?.let {
        compareAndBranch(it, if (whenTrue) it.op else NEGATED.getValue(it.op), target)
        return
      }
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
          val (a, b) = comparison.a to comparison.b
          when {
            a is Const && b is Const ->
                if (holds(comparison.op, a.number, b.number) == whenTrue) {
                  emit("j", Fixed(LabelRef(target)))
                }
            whenTrue -> compareAndBranch(comparison, comparison.op, target)
            !comparison.mayBeNaN ->
                compareAndBranch(comparison, NEGATED.getValue(comparison.op), target)
            else -> {
              // Branch over the jump: the negated branch would not be taken for NaN.
              val skip = newLabel("skip")
              compareAndBranch(comparison, comparison.op, skip)
              emit("j", Fixed(LabelRef(target)))
              label(skip)
            }
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

  /** Emits `b[op]` (or `b[op]z` against zero) on [comparison]'s operands, jumping to [target]. */
  private fun compareAndBranch(comparison: Comparison, op: String, target: String) {
    val (a, b) = comparison.a to comparison.b
    val instruction =
        if (b == Const(0.0)) {
          TirInstruction("b${op}z", listOf(operand(a), Fixed(LabelRef(target))))
        } else {
          TirInstruction("b$op", listOf(operand(a), operand(b), Fixed(LabelRef(target))))
        }
    lines += instruction.copy(negatable = !comparison.mayBeNaN)
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
      // A branch's result is an expression, not a statement, and is often on a line of its own,
      // so it is attributed to where it is.
      at(branch.result) {
        // Kotlin types an if or when with an else by its branches even where it is a statement.
        val value = value(branch.result, "a batch can't be the result of an if or when")
        if (result != null && value != NoValue) move(result, value)
      }
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
   * optionally with `step`. Kotlin evaluates the range's bounds and step once, before the loop. A
   * step that is not a constant is not checked: IC10 has no exceptions to throw for one that is not
   * positive.
   */
  private fun forLoop(block: IrBlock): Value {
    val iterator = block.statements.getOrNull(0) as? IrVariable
    val loop = block.statements.getOrNull(1) as? IrWhileLoop
    val body = loop?.body as? IrContainerExpression
    val loopVariable = body?.statements?.firstOrNull() as? IrVariable
    val iteratorCall = iterator?.initializer as? IrCall
    val rangeExpression = iteratorCall?.arguments?.getOrNull(0)
    if (loop == null || loopVariable == null || rangeExpression == null) {
      fail(block, "Only for loops over Int ranges and static sets are supported")
    }
    if (
        rangeExpression !is IrCall ||
            rangeExpression.symbol.owner.kotlinFqName.asString() !in RANGE_FUNCTIONS
    ) {
      val set =
          aliasOrValue(rangeExpression) as? StaticSet
              ?: fail(
                  rangeExpression,
                  "a for loop goes over an Int range, or a set known when compiled, as " +
                      "setOf(Substance.Oxygen, Substance.Nitrogen)",
              )
      return unrolledLoop(loop, loopVariable, body, set)
    }
    val range = range(rangeExpression)
    val counter = newVReg(loopVariable.name.asString())
    move(counter, range.start)
    val end = snapshot(range.end)
    val step = snapshot(range.step)
    val top = newLabel("for")
    val next = newLabel("next")
    val done = newLabel("endfor")
    label(top)
    if (range.start is Const && end is Const) {
      val exit =
          when {
            range.descending -> "blt"
            range.inclusive -> "bgt"
            else -> "bge"
          }
      emit(exit, counter, operand(end), Fixed(LabelRef(done)))
    } else {
      // A bound computed at run time may be NaN (an Int holding NaN), which every comparison
      // fails: branch into the body only while the loop should go on, so NaN ends the loop
      // instead of running it forever.
      val stay =
          when {
            range.descending -> "bge"
            range.inclusive -> "ble"
            else -> "blt"
          }
      val body = newLabel("forbody")
      lines +=
          TirInstruction(stay, listOf(counter, operand(end), Fixed(LabelRef(body))))
              .copy(negatable = false)
      emit("j", Fixed(LabelRef(done)))
      label(body)
    }
    variables[loopVariable] = Reg(counter)
    loops[loop] = next to done
    for (statement in body.statements.drop(1)) statement(statement)
    label(next)
    emit(if (range.descending) "sub" else "add", counter, counter, operand(step))
    emit("j", Fixed(LabelRef(top)))
    label(done)
    return NoValue
  }

  /**
   * A for loop over a [StaticSet], one copy of [body] per element, in which the loop variable is
   * that element, a constant: so it may key a view. `continue` goes on to the next copy, `break`
   * past the last.
   */
  private fun unrolledLoop(
      loop: IrWhileLoop,
      loopVariable: IrVariable,
      body: IrContainerExpression,
      set: StaticSet,
  ): Value {
    val done = newLabel("endfor")
    for (element in set.elements) {
      val next = newLabel("next")
      variables[loopVariable] = element
      loops[loop] = next to done
      for (statement in body.statements.drop(1)) statement(statement)
      label(next)
    }
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
      fail(call, "Only Int ranges are supported")
    }
    fun bounds(inclusive: Boolean, descending: Boolean): Range {
      val (start, end) = operands(args)
      return Range(start, end, Const(1.0), inclusive, descending)
    }
    return when (fqName) {
      "kotlin.Int.rangeTo" -> bounds(inclusive = true, descending = false)
      "kotlin.Int.rangeUntil",
      "kotlin.ranges.until" -> bounds(inclusive = false, descending = false)
      "kotlin.ranges.downTo" -> bounds(inclusive = true, descending = true)
      "kotlin.ranges.step" -> {
        val inner = args[0] as? IrCall ?: fail(call, "This range is not supported")
        // Kotlin rebuilds a stepped progression from the last value it reaches, not its end bound.
        if (inner.symbol.owner.kotlinFqName.asString() == "kotlin.ranges.step") {
          fail(call, "a step of a stepped range is not supported")
        }
        val range = range(inner)
        val stepAssigns = args[1].mayAssign()
        val (start, end) =
            listOf(range.start, range.end).map { if (stepAssigns) snapshot(it) else it }
        val step = value(args[1])
        if (step is Const && step.number <= 0.0) fail(args[1], "a range's step must be positive")
        Range(start, end, step, range.inclusive, range.descending)
      }
      else -> fail(call, "This range is not supported (use .., ..<, until, downTo and step)")
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

  /**
   * [name] as an IC10 label, which is `[A-Za-z_][A-Za-z0-9_]*`: any other character becomes `_`,
   * and a leading digit gets a `_` in front. Kotlin also allows non-ASCII letters, and in backticks
   * spaces and a leading digit.
   */
  private fun labelName(name: String): String {
    val label = name.map { if (it in LABEL_CHARS) it else '_' }.joinToString("")
    return if (label.first().isDigit()) "_$label" else label
  }

  private fun newLabel(base: String): String {
    while (true) {
      val label = base + labelCounts.merge(base, 1, Int::plus)!!
      if (takenLabels.add(label)) return label
    }
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
        // The chip rejects `nan` written as an operand, so NaN is computed as 0 / 0 instead, and
        // negative zero as 0 * -1: the chip reads a number with the runtime's decimal parser,
        // which is not known to keep the sign of `-0`.
        is Const ->
            if (value.number.isNaN()) {
              newVReg().also { emit("div", it, Fixed(Number(0.0)), Fixed(Number(0.0))) }
            } else if (value.number.toRawBits() == NEGATIVE_ZERO_BITS) {
              newVReg().also { emit("mul", it, Fixed(Number(0.0)), Fixed(Number(-1.0))) }
            } else {
              Fixed(value.operand)
            }
        is Reg -> value.reg
        is Dev -> Fixed(value.device)
        is Batch,
        is Mapped,
        is SlotsOf,
        is SlotOf,
        is View,
        is Key,
        is StaticSet,
        Element,
        is Named -> error("a batch, slot or view has no value")
        NoValue -> error("no value")
      }

  private fun IrType.isAny() = classFqName?.asString() == "kotlin.Any"

  private fun IrType.isDevice() =
      classFqName?.asString() == "ktrapic.Device" || classOrNull?.owner?.isTypedDevice() == true

  /** A view class or [SUBSTANCE]: an argument named when compiled, as a device is. */
  private fun IrType.isViewOrKey() =
      classFqName?.asString().let {
        it in VIEW_CLASSES || it == SUBSTANCE || it in COLLECTION_CLASSES
      }

  private fun IrType.isPrefab() =
      classOrNull?.owner?.let { it.kotlinFqName.asString() == PREFAB || it.isSubclassOf(PREFAB) } ==
          true

  private fun IrType.render() = classFqName?.shortName()?.asString() ?: "these"

  private fun fail(element: IrElement, message: String): Nothing =
      throw CompileException(element, message)

  private companion object {
    const val DEVICE = "ktrapic.Device"
    val DEVICE_CLASSES = setOf(DEVICE, "ktrapic.GenericDevice")
    const val TYPED_DEVICE = "ktrapic.TypedDevice"
    const val BATCH_CLASS = "ktrapic.Batch"
    const val PREFAB = "ktrapic.Prefab"
    const val PREFAB_NAME = "ktrapic.PrefabName"
    const val TEXT = "Text"
    const val SLOTS = "slots"
    const val MAPPED_HELD =
        "a map can't be held, only ended where it is made, as all.map { it.X }.sum()"
    const val SLOTS_CLASS = "ktrapic.Slots"
    const val DEVICE_SLOT = "ktrapic.DeviceSlot"
    const val SLOT_NUMBER = "ktrapic.SlotNumber"
    const val MAX_SLOT = 255
    const val LOGIC_TYPES = "ktrapic.LogicTypes"
    const val SUBSTANCE = "ktrapic.Substance"
    val LOGIC_TYPE: ClassId = ClassId.topLevel(FqName("ktrapic.LogicType"))
    val SUBSTANCE_ID: ClassId = ClassId.topLevel(FqName(SUBSTANCE))

    /** The ranges a for loop counts through as a real loop; anything else must be a static set. */
    val RANGE_FUNCTIONS =
        setOf(
            "kotlin.Int.rangeTo",
            "kotlin.Int.rangeUntil",
            "kotlin.ranges.until",
            "kotlin.ranges.downTo",
            "kotlin.ranges.step",
        )

    val SET_BUILDERS = setOf("kotlin.collections.setOf", "kotlin.collections.listOf")
    val EMPTY_SET_BUILDERS = setOf("kotlin.collections.emptySet", "kotlin.collections.emptyList")

    /** The collection interfaces a static set is typed as. */
    val COLLECTION_CLASSES =
        setOf("Set", "List", "Collection", "Iterable").map { "kotlin.collections.$it" }.toSet() +
            "kotlin.enums.EnumEntries"

    /** The runtime's view classes, whose members name logic types ([View]). */
    val VIEW_CLASSES =
        setOf(
                "Ports",
                "Port",
                "Ratios",
                "Vector",
                "MutableVector",
                "Transfer",
            )
            .map { "ktrapic.$it" }
            .toSet()

    /** A vector view's logic types' prefix, where it isn't the view's name. */
    val VECTOR_PREFIXES = mapOf("Dimensions" to "Size")

    /** A batch's Boolean tests, by the batch method each reads and its comparison with 0. */
    val BATCH_TESTS =
        mapOf(
            "any" to ("Maximum" to "gt"),
            "all" to ("Minimum" to "gt"),
            "none" to ("Maximum" to "le"),
        )

    /** The functions ending a `map`, by the game's batch method they read with. */
    val MAPPED_TERMINALS =
        mapOf(
            "ktrapic.average" to "Average",
            "ktrapic.sum" to "Sum",
            "ktrapic.min" to "Minimum",
            "ktrapic.max" to "Maximum",
        )
    /** The runtime functions besides a batch's `forEach` with a side effect not an assignment. */
    val SIDE_EFFECTS = setOf("ktrapic.Device.set", "ktrapic.sleep", "ktrapic.yield")
    val PIN_GETTERS =
        listOf("d0", "d1", "d2", "d3", "d4", "d5", "db").associateBy { "ktrapic.<get-$it>" }
    val INT_RANGE = Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()
    val NEGATIVE_ZERO_BITS = (-0.0).toRawBits()
    const val END_LABEL = "end"
    /**
     * Register and pin names, which a function label never takes. Nor does it take a constant's
     * name in any case, which [Constants.lookup] checks.
     */
    val RESERVED_NAMES = Register.NAMES + Device.NAMES
    /**
     * Every register or device-pin shape, which a function label never takes: `r` or `d` and digits
     * (`r16` and `r17` are `sp` and `ra`; the game or a mod may add more registers or pins), and
     * indirect registers and pins such as `rr0`, `rrr1`, `dr0` and `drr1`. The game recognizes
     * registers, pins, `sp` and `ra` by their lower-case spelling only, so `R0`, `DB` or `Sp` is an
     * ordinary label and is kept as is.
     */
    val REGISTER_SPELLING = Regex("(d|d?r+)[0-9]+")
    val LABEL_CHARS = ('A'..'Z') + ('a'..'z') + ('0'..'9') + '_'
  }
}
