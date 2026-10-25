package ktrapic.emu

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import ktrapic.ic10.Constants
import ktrapic.ic10.Device
import ktrapic.ic10.Ic10Limits
import ktrapic.ic10.JumpKind
import ktrapic.ic10.LabelRef
import ktrapic.ic10.Line
import ktrapic.ic10.Name
import ktrapic.ic10.Number
import ktrapic.ic10.Opcodes
import ktrapic.ic10.Operand
import ktrapic.ic10.Program
import ktrapic.ic10.Register
import ktrapic.ic10.StringConstant

/** A runtime error that halts the chip, at a 0-based program [line] as jumps count lines. */
class Ic10RuntimeException(val line: Int, message: String) : Exception("line $line: $message")

/**
 * Runs an IC10 program the way a programmable chip does, against fake devices.
 *
 * The chip runs up to [instructionsPerTick] lines each game tick (half a second), stopping early at
 * `yield` or `sleep`. Every line counts, labels, comments and blank lines included. Running off the
 * end of the program halts the chip. A `define` whose value isn't a constant fails when the
 * emulator is built, as an [Ic10RuntimeException] at that line.
 *
 * [pins] holds the devices on `d0`..`d5` and `db`; [network] is every device on the data network,
 * which batch instructions (`lb`, `sb`, ...) select from by prefab and name hash.
 *
 * Not modelled yet: logic types given as numbers, device reference ids (`getd`, `lbns`, `d?` from a
 * register), reagents, and indirect registers (`rr0`).
 */
class Emulator(
    val program: Program,
    val pins: Map<String, FakeDevice> = emptyMap(),
    val network: List<FakeDevice> = pins.values.toList(),
    val instructionsPerTick: Int = 128,
    private val random: Random = Random(0),
) {
  private val registers = DoubleArray(Register.NAMES.size)
  val stack = DoubleArray(Ic10Limits.STACK_SIZE)

  /** Index of the line that runs next. */
  var pc = 0
    private set

  /** Game ticks run so far. */
  var ticks = 0
    private set

  /** Lines run so far, over all ticks. */
  var steps = 0L
    private set

  var halted = false
    private set

  private var sleepUntilTick = 0
  private val aliases = mutableMapOf<String, Operand>()
  private val defines: Map<String, Double> = buildMap {
    program.lines.forEachIndexed { index, line ->
      if (line is Line.Instruction && line.op == "define") {
        try {
          check(line.operands.size == 2) { "takes 2 operands, not ${line.operands.size}" }
          val name =
              line.operands[0] as? Name ?: error("needs a name, not ${line.operands[0].text()}")
          put(name.name, constant(line.operands[1]))
        } catch (e: IllegalStateException) {
          throw Ic10RuntimeException(index, "define: ${e.message}")
        }
      }
    }
  }

  init {
    for (pin in pins.keys) require(pin in Device.NAMES) { "not a device pin: $pin" }
  }

  fun register(name: String): Double = registers[Register(name).index]

  fun setRegister(name: String, value: Double) {
    registers[Register(name).index] = value
  }

  /** Runs [count] game ticks, or fewer if the chip halts. Returns this, for chaining. */
  fun runTicks(count: Int): Emulator {
    repeat(count) { if (!halted) tick() }
    return this
  }

  /** Runs ticks until the chip halts, failing after [maxTicks]. */
  fun runUntilHalt(maxTicks: Int = 10_000): Emulator {
    while (!halted) {
      check(ticks < maxTicks) { "still running after $maxTicks ticks, at line $pc" }
      tick()
    }
    return this
  }

  /** Runs one game tick. */
  fun tick() {
    check(!halted) { "the chip has halted" }
    ticks++
    if (ticks <= sleepUntilTick) return
    var executed = 0
    while (!halted && executed < instructionsPerTick) {
      executed++
      if (step() == Pause.END_TICK) break
    }
  }

  private enum class Pause {
    CONTINUE,
    END_TICK,
  }

  private fun step(): Pause {
    if (pc !in program.lines.indices) {
      halted = true
      return Pause.END_TICK
    }
    val line = program.lines[pc]
    steps++
    if (line !is Line.Instruction) {
      pc++
      return Pause.CONTINUE
    }
    return try {
      execute(line)
    } catch (e: Ic10RuntimeException) {
      throw e
    } catch (e: RuntimeException) {
      halted = true
      throw Ic10RuntimeException(pc, "${line.op}: ${e.message}")
    }
  }

  private fun execute(line: Line.Instruction): Pause {
    val ops = line.operands
    val opcode = Opcodes[line.op] ?: error("unknown instruction")
    var next = pc + 1

    fun v(i: Int) = value(ops[i])
    fun set(value: Double) {
      registers[destination(ops[0]).index] = value
    }
    fun setBool(condition: Boolean) = set(if (condition) 1.0 else 0.0)
    fun long(i: Int) = v(i).toLong()

    if (opcode.jump != JumpKind.NONE) {
      if (branchTaken(line.op, ops)) {
        if (opcode.links) registers[Register.RA.index] = (pc + 1).toDouble()
        val target = v(opcode.targetIndex!!).toInt()
        next = if (opcode.jump == JumpKind.RELATIVE) pc + target else target
      }
      pc = next
      return Pause.CONTINUE
    }

    when (line.op) {
      "alias" -> aliases[(ops[0] as Name).name] = resolveAlias(ops[1])
      "define" -> {}
      "hcf" -> {
        halted = true
        throw Ic10RuntimeException(pc, "hcf")
      }
      "yield" -> {
        pc = next
        return Pause.END_TICK
      }
      "sleep" -> {
        sleepUntilTick = ticks + ceil(v(0) / SECONDS_PER_TICK).toInt() - 1
        pc = next
        return Pause.END_TICK
      }

      "abs" -> set(abs(v(1)))
      "acos" -> set(kotlin.math.acos(v(1)))
      "add" -> set(v(1) + v(2))
      "asin" -> set(kotlin.math.asin(v(1)))
      "atan" -> set(kotlin.math.atan(v(1)))
      "atan2" -> set(kotlin.math.atan2(v(1), v(2)))
      "ceil" -> set(ceil(v(1)))
      "cos" -> set(kotlin.math.cos(v(1)))
      "div" -> set(v(1) / v(2))
      "exp" -> set(kotlin.math.exp(v(1)))
      "floor" -> set(kotlin.math.floor(v(1)))
      "lerp" -> set(v(1) + (v(2) - v(1)) * v(3).coerceIn(0.0, 1.0))
      "log" -> set(kotlin.math.ln(v(1)))
      "max" -> set(max(v(1), v(2)))
      "min" -> set(min(v(1), v(2)))
      "mod" -> set(((v(1) % v(2)) + v(2)) % v(2))
      "move" -> set(v(1))
      "mul" -> set(v(1) * v(2))
      "pow" -> set(Math.pow(v(1), v(2)))
      "rand" -> set(random.nextDouble())
      // C#'s Math.Round rounds halves to even, as rint does.
      "round" -> set(Math.rint(v(1)))
      "sin" -> set(kotlin.math.sin(v(1)))
      "sqrt" -> set(kotlin.math.sqrt(v(1)))
      "sub" -> set(v(1) - v(2))
      "tan" -> set(kotlin.math.tan(v(1)))
      "trunc" -> set(kotlin.math.truncate(v(1)))

      "and" -> set((long(1) and long(2)).toDouble())
      "or" -> set((long(1) or long(2)).toDouble())
      "xor" -> set((long(1) xor long(2)).toDouble())
      "nor" -> set((long(1) or long(2)).inv().toDouble())
      "not" -> set(long(1).inv().toDouble())
      "sll",
      "sla" -> set((long(1) shl long(2).toInt()).toDouble())
      "srl" -> set((long(1) ushr long(2).toInt()).toDouble())
      "sra" -> set((long(1) shr long(2).toInt()).toDouble())
      "ext" -> set(((long(1) shr long(2).toInt()) and mask(long(3))).toDouble())
      "ins" -> {
        val offset = long(2).toInt()
        val mask = mask(long(3)) shl offset
        val current = registers[destination(ops[0]).index].toLong()
        set(((current and mask.inv()) or ((long(1) shl offset) and mask)).toDouble())
      }

      "select" -> set(if (v(1) != 0.0) v(2) else v(3))
      "sdns" -> setBool(device(ops[1]) == null)
      "sdse" -> setBool(device(ops[1]) != null)
      "sap" -> setBool(approximately(v(1), v(2), v(3)))
      "sapz" -> setBool(approximately(v(1), 0.0, v(2)))
      "sna" -> setBool(!approximately(v(1), v(2), v(3)))
      "snaz" -> setBool(!approximately(v(1), 0.0, v(2)))
      "seq" -> setBool(v(1) == v(2))
      "seqz" -> setBool(v(1) == 0.0)
      "sge" -> setBool(v(1) >= v(2))
      "sgez" -> setBool(v(1) >= 0.0)
      "sgt" -> setBool(v(1) > v(2))
      "sgtz" -> setBool(v(1) > 0.0)
      "sle" -> setBool(v(1) <= v(2))
      "slez" -> setBool(v(1) <= 0.0)
      "slt" -> setBool(v(1) < v(2))
      "sltz" -> setBool(v(1) < 0.0)
      "sne" -> setBool(v(1) != v(2))
      "snez" -> setBool(v(1) != 0.0)
      "snan" -> setBool(v(1).isNaN())
      "snanz" -> setBool(!v(1).isNaN())

      "push" -> {
        val sp = stackPointer()
        require(sp < stack.size) { "stack overflow" }
        stack[sp] = v(0)
        registers[Register.SP.index] = (sp + 1).toDouble()
      }
      "pop" -> {
        val sp = stackPointer() - 1
        require(sp >= 0) { "stack underflow" }
        set(stack[sp])
        registers[Register.SP.index] = sp.toDouble()
      }
      "peek" -> {
        val sp = stackPointer() - 1
        require(sp in stack.indices) { "stack underflow" }
        set(stack[sp])
      }
      "poke" -> stack[address(v(0))] = v(1)
      "get" -> set(memoryOf(ops[1])[address(v(2))])
      "put" -> memoryOf(ops[0])[address(v(1))] = v(2)
      "clr" -> memoryOf(ops[0]).fill(0.0)

      "l" -> set(requireDevice(ops[1]).read(logicType(ops[2])))
      "s" -> requireDevice(ops[0]).write(logicType(ops[1]), v(2))
      "ls" -> set(requireDevice(ops[1]).readSlot(v(2).toInt(), logicType(ops[3])))
      "ss" -> requireDevice(ops[0]).writeSlot(v(1).toInt(), logicType(ops[2]), v(3))
      "lb" -> set(batch(matching(v(1), null).map { it.read(logicType(ops[2])) }, ops[3]))
      "lbn" -> set(batch(matching(v(1), v(2)).map { it.read(logicType(ops[3])) }, ops[4]))
      "lbs" -> {
        val slot = v(2).toInt()
        set(batch(matching(v(1), null).map { it.readSlot(slot, logicType(ops[3])) }, ops[4]))
      }
      "sb" -> matching(v(0), null).forEach { it.write(logicType(ops[1]), v(2)) }
      "sbn" -> matching(v(0), v(1)).forEach { it.write(logicType(ops[2]), v(3)) }
      "sbs" -> matching(v(0), null).forEach { it.writeSlot(v(1).toInt(), logicType(ops[2]), v(3)) }

      else -> error("not supported by the emulator yet")
    }
    pc = next
    return Pause.CONTINUE
  }

  private fun branchTaken(op: String, ops: List<Operand>): Boolean {
    if (op == "j" || op == "jal" || op == "jr") return true
    val condition = op.removePrefix("b").removePrefix("r").removeSuffix("al")
    fun v(i: Int) = value(ops[i])
    return when (condition) {
      "eq" -> v(0) == v(1)
      "eqz" -> v(0) == 0.0
      "ge" -> v(0) >= v(1)
      "gez" -> v(0) >= 0.0
      "gt" -> v(0) > v(1)
      "gtz" -> v(0) > 0.0
      "le" -> v(0) <= v(1)
      "lez" -> v(0) <= 0.0
      "lt" -> v(0) < v(1)
      "ltz" -> v(0) < 0.0
      "ne" -> v(0) != v(1)
      "nez" -> v(0) != 0.0
      "ap" -> approximately(v(0), v(1), v(2))
      "apz" -> approximately(v(0), 0.0, v(1))
      "na" -> !approximately(v(0), v(1), v(2))
      "naz" -> !approximately(v(0), 0.0, v(1))
      "nan" -> v(0).isNaN()
      "dns" -> device(ops[0]) == null
      "dse" -> device(ops[0]) != null
      // "branch if device not valid load/store": a missing logic type, or no device at all.
      "dnvl" -> device(ops[0])?.logic?.containsKey(logicType(ops[1])) != true
      "dnvs" -> device(ops[0])?.logic?.containsKey(logicType(ops[1])) != true
      else -> error("unknown branch")
    }
  }

  /** The game's approximate equality: `|a - b| <= max(c * max(|a|, |b|), epsilon * 8)`. */
  private fun approximately(a: Double, b: Double, c: Double) =
      abs(a - b) <= max(c * max(abs(a), abs(b)), Float.MIN_VALUE.toDouble() * 8)

  private fun value(operand: Operand): Double =
      when (operand) {
        is Register -> registers[operand.index]
        is Number -> operand.value
        is LabelRef -> program.labels.getValue(operand.name).toDouble()
        is StringConstant -> operand.numericValue
        is Name ->
            when (val alias = aliases[operand.name]) {
              is Register -> registers[alias.index]
              null ->
                  defines[operand.name]
                      ?: Constants.VALUES[operand.name]
                      ?: error("unknown name ${operand.name}")
              else -> error("${operand.name} is a device, not a value")
            }
        is Device -> error("${operand.name} is a device, not a value")
      }

  private fun constant(operand: Operand): Double =
      when (operand) {
        is Number -> operand.value
        is StringConstant -> operand.numericValue
        is Name -> Constants.VALUES[operand.name] ?: error("needs a constant, not ${operand.name}")
        else -> error("needs a constant, not ${operand.text()}")
      }

  private fun resolveAlias(operand: Operand): Operand =
      when (operand) {
        is Register,
        is Device -> operand
        is Name -> aliases[operand.name] ?: error("can't alias ${operand.name}")
        else -> error("can't alias ${operand.text()}")
      }

  private fun destination(operand: Operand): Register =
      when (operand) {
        is Register -> operand
        is Name -> aliases[operand.name] as? Register ?: error("${operand.name} isn't a register")
        else -> error("${operand.text()} isn't a register")
      }

  private fun pinName(operand: Operand): String =
      when (operand) {
        is Device -> operand.name
        is Name ->
            (aliases[operand.name] as? Device)?.name ?: error("${operand.name} isn't a device")
        else -> error("${operand.text()} isn't a device")
      }

  private fun device(operand: Operand): FakeDevice? = pins[pinName(operand)]

  private fun requireDevice(operand: Operand): FakeDevice =
      device(operand) ?: error("no device on ${pinName(operand)}")

  private fun memoryOf(operand: Operand): DoubleArray =
      if (pinName(operand) == "db" && "db" !in pins) stack else requireDevice(operand).memory

  private fun logicType(operand: Operand): String =
      (operand as? Name)?.name?.takeIf { it !in aliases && it !in defines }
          ?: error("logic types must be named, not ${operand.text()}")

  private fun matching(prefabHash: Double, nameHash: Double?) = network.filter {
    it.prefabHash.toDouble() == prefabHash &&
        (nameHash == null || it.nameHash.toDouble() == nameHash)
  }

  private fun batch(values: List<Double>, mode: Operand): Double {
    val method =
        when (mode) {
          is Name -> BATCH_METHODS[mode.name] ?: value(mode)
          else -> value(mode)
        }
    if (values.isEmpty()) return 0.0
    return when (method.toInt()) {
      0 -> values.average()
      1 -> values.sum()
      2 -> values.min()
      3 -> values.max()
      else -> error("unknown batch method $method")
    }
  }

  private fun stackPointer() = registers[Register.SP.index].toInt()

  private fun address(value: Double): Int =
      value.toInt().also { require(it in stack.indices) { "address $it is out of range" } }

  private fun mask(length: Long): Long = if (length >= 64) -1L else (1L shl length.toInt()) - 1

  companion object {
    const val SECONDS_PER_TICK = 0.5

    val BATCH_METHODS = mapOf("Average" to 0.0, "Sum" to 1.0, "Minimum" to 2.0, "Maximum" to 3.0)
  }
}
