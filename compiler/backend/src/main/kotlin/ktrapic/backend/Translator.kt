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
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrAnonymousInitializer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
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
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.IrReturn
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

/** A [Batch] read through a batch method (`Average`, `Sum`, `Minimum` or `Maximum`). */
internal data class BatchRead(val batch: Batch, val method: String) : Value

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
  private lateinit var graph: CallGraph
  private val takenLabels = mutableSetOf<String>()
  private val functionLabels = mutableMapOf<IrSimpleFunction, String>()
  /** The functions whose bodies are being translated, and where their `return`s go. */
  private val exits = mutableMapOf<IrSimpleFunction, FunctionExit>()
  /** Functions that have a body of their own, called with `jal`, in the order first called. */
  private val called = linkedMapOf<IrSimpleFunction, CalledFunction>()

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
        is IrProperty -> property(declaration)
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
    val values = operands(arguments)
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
      if (!type.isInt() && !type.isDouble() && !type.isBoolean() && !type.isDevice()) {
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
    if (parameters.any { it.type.isDevice() }) {
      fail(call, "A function with a device parameter must be inline, or called only once")
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
    val value = aliasOrValue(initializer)
    properties[property.symbol] =
        when {
          value.isAlias() && !property.isVar -> value
          value.isAlias() -> fail(property, "a device or batch can only be held in a val")
          value is Const && !property.isVar -> value
          else -> Reg(newVReg(property.name.asString())).also { move(it.reg, value) }
        }
  }

  // Statements and expressions.

  private fun statement(element: IrElement) {
    when (element) {
      is IrBlockBody -> element.statements.forEach { statement(it) }
      is IrVariable -> variable(element)
      is IrExpression -> aliasOrValue(element)
      is IrSimpleFunction -> fail(element, "Local functions are not supported")
      else -> fail(element, "This statement is not supported")
    }
  }

  private fun variable(variable: IrVariable) {
    val initializer = variable.initializer
    if (initializer == null) {
      variables[variable] = Reg(newVReg(variable.name.asString()))
      return
    }
    val value = aliasOrValue(initializer)
    variables[variable] =
        when {
          value.isAlias() && !variable.isVar -> value
          value.isAlias() -> fail(variable, "a device or batch can only be held in a val")
          value is Const && !variable.isVar -> value
          else -> Reg(newVReg(variable.name.asString())).also { move(it.reg, value) }
        }
  }

  /**
   * What [expression] evaluates to, which must not be a [Batch] or [BatchRead]: those have no value
   * and are only held in a `val` or used directly ([aliasOrValue]).
   */
  private fun value(expression: IrExpression): Value =
      aliasOrValue(expression).also {
        if (it is Batch || it is BatchRead) {
          fail(expression, "a batch can only be used directly or held in a val")
        }
      }

  /** What [expression] evaluates to, a [Batch] or [BatchRead] included. */
  private fun aliasOrValue(expression: IrExpression): Value =
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
        is IrGetObjectValue -> prefab(expression)
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
      when (statement) {
        is IrVariable -> variable(statement)
        is IrExpression -> result = aliasOrValue(statement)
        is IrSimpleFunction -> fail(statement, "Local functions are not supported")
        else -> fail(statement, "This statement is not supported")
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
            // The implicit `else` K2 gives an exhaustive `when` without one.
            fqName == "kotlin.internal.ir.noWhenBranchMatchedException" ->
                fail(call, "A when used as a value needs an else branch")
            // The front end's comparison intrinsics (less, EQEQ, ...) are top-level functions.
            fqName.startsWith("kotlin.internal.ir.") -> comparisonValue(call)
            else -> math(call, fqName) ?: fail(call, "calls to $fqName are not supported")
          }
    }
  }

  private fun device(call: IrCall, argument: IrExpression?): Dev =
      argument?.let { value(it) } as? Dev
          ?: fail(argument ?: call, "this device must be a pin, or a val holding one")

  // The typed device API: typed devices, their entry points, batches and batch readings.

  /**
   * A call into the generated typed device API, or null if [cls] isn't part of it. An entry point
   * (`GrowLights`) gives a typed device (`at(d0)`) or a [Batch] (`all`, `named("x")`); a typed
   * device's logic types load and store like a pin's; a batch's store to every device in it (`sb`,
   * `sbn`) and its batch methods give a [BatchRead], whose logic types load (`lb`, `lbn`).
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
      cls.isSubclassOf(DEVICE_BATCH) -> {
        val batch =
            aliasOrValue(args[0]!!) as? Batch
                ?: fail(call, "this batch must be all, named(...), or a val holding one")
        if (!isSetter) {
          // The other getters are logic types', which can't be read from a batch directly.
          if (name !in BATCH_METHODS)
              fail(call, "read a batch through a method, as in all.average.X")
          return BatchRead(batch, name.replaceFirstChar { it.uppercaseChar() })
        }
        val stored = storedValue(call, name, args.last()!!)
        val logicType = Fixed(Name(storedLogicType(name)))
        if (batch.name == null) {
          emit("sb", Fixed(Number(batch.prefab)), logicType, stored)
        } else {
          emit("sbn", Fixed(Number(batch.prefab)), Fixed(Number(batch.name)), logicType, stored)
        }
        NoValue
      }
      cls.isSubclassOf(BATCH_READING) -> {
        val read =
            aliasOrValue(args[0]!!) as? BatchRead
                ?: fail(call, "this batch reading must be a batch method's")
        val batch = read.batch
        val result = newVReg()
        val logicType = Fixed(Name(name))
        if (batch.name == null) {
          emit("lb", result, Fixed(Number(batch.prefab)), logicType, Fixed(Name(read.method)))
        } else {
          emit(
              "lbn",
              result,
              Fixed(Number(batch.prefab)),
              Fixed(Number(batch.name)),
              logicType,
              Fixed(Name(read.method)),
          )
        }
        Reg(result)
      }
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

  /** `Text` stores its string, packed by `STR`, to `Setting`. */
  private fun storedLogicType(name: String) = if (name == TEXT) "Setting" else name

  private fun storedValue(call: IrCall, name: String, argument: IrExpression): TirOperand =
      if (name == TEXT) {
        Fixed(stringConstant(call, StringConstant.Kind.STR, argument, TEXT).operand)
      } else {
        operand(value(argument))
      }

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

  private fun Value.isAlias() = this is Dev || this is Batch || this is BatchRead

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
   * the old `x`).
   */
  private fun operands(expressions: List<IrExpression>): List<Value> =
      expressions.mapIndexed { index, expression ->
        val value = value(expression)
        if (expressions.drop(index + 1).any { it.mayAssign() }) snapshot(value) else value
      }

  /** [value] in a register of its own if it is in one, so later assignments leave it alone. */
  private fun snapshot(value: Value): Value =
      if (value is Reg) Reg(newVReg()).also { move(it.reg, value) } else value

  /**
   * True if evaluating this expression may assign a variable or property, including a property
   * assigned by one of the program's functions it calls.
   */
  private fun IrExpression.mayAssign(): Boolean = assigns(this, variables = true, mutableSetOf())

  /**
   * True if [element] assigns a property, or a variable when [variables] is set. A call to one of
   * the program's functions assigns the properties its body does; the variables its body assigns
   * are its own, so they do not count. [visited] holds the functions already looked into.
   */
  private fun assigns(
      element: IrElement,
      variables: Boolean,
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
            if (function in graph.functions && visited.add(function)) {
              val body = function.body
              if (body != null && assigns(body, variables = false, visited)) {
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
    if (loop == null || loopVariable == null || rangeExpression !is IrCall) {
      fail(block, "Only for loops over Int ranges are supported")
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
        is BatchRead -> error("a batch has no value")
        NoValue -> error("no value")
      }

  private fun IrType.isAny() = classFqName?.asString() == "kotlin.Any"

  private fun IrType.isDevice() =
      classFqName?.asString() == "ktrapic.Device" || classOrNull?.owner?.isTypedDevice() == true

  private fun IrType.isPrefab() =
      classOrNull?.owner?.let { it.kotlinFqName.asString() == PREFAB || it.isSubclassOf(PREFAB) } ==
          true

  private fun IrType.render() = classFqName?.shortName()?.asString() ?: "these"

  private fun fail(element: IrElement, message: String): Nothing =
      throw CompileException(element, message)

  private companion object {
    val DEVICE_CLASSES = setOf("ktrapic.Device", "ktrapic.GenericDevice")
    const val TYPED_DEVICE = "ktrapic.TypedDevice"
    const val DEVICE_BATCH = "ktrapic.DeviceBatch"
    const val BATCH_READING = "ktrapic.BatchReading"
    const val PREFAB = "ktrapic.Prefab"
    const val PREFAB_NAME = "ktrapic.PrefabName"
    const val TEXT = "Text"
    val BATCH_METHODS = setOf("average", "sum", "minimum", "maximum")
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
