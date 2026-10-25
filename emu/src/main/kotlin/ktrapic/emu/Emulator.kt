package ktrapic.emu

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import ktrapic.ic10.Constants
import ktrapic.ic10.Device
import ktrapic.ic10.Ic10Limits
import ktrapic.ic10.Ic10Math
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
 * Its arithmetic, bitwise operations (see [ktrapic.ic10.Ic10Math]), batch reads, jumps and stack
 * follow the game's IC10 interpreter, with the chip in a circuit housing (so `get`/`put` on `db`
 * reach its own stack). Where it deliberately differs: a runtime error halts and throws (the game
 * retries the failing line every tick), and `sleep` resumes after exactly `ceil(seconds / 0.5)`
 * ticks (the game's real tick lengths can make it one tick later at exact multiples of 0.5 s).
 *
 * Not modelled yet: logic types given as numbers, device reference ids (`ld`, `sd`, `getd`, `lbns`,
 * `d?` from a register), reagents, other hosts (machines, suits, tablets), and indirect registers
 * (`rr0`).
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
        } catch (e: RuntimeException) {
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

    if (opcode.jump != JumpKind.NONE) {
      // `jal` sets ra before reading its target; the branch-and-link ops read the target, and
      // set ra only when they branch.
      if (line.op == "jal") registers[Register.RA.index] = (pc + 1).toDouble()
      if (branchTaken(line.op, ops)) {
        val target = integer(ops[opcode.targetIndex!!])
        if (opcode.links && line.op != "jal") registers[Register.RA.index] = (pc + 1).toDouble()
        next = if (opcode.jump == JumpKind.RELATIVE) pc + target else target
      }
      // A negative line -k pauses until the next tick, then carries on at line k.
      if (next < 0) {
        pc = -next
        return Pause.END_TICK
      }
      pc = next
      return Pause.CONTINUE
    }

    when (line.op) {
      "alias",
      "label" -> aliases[(ops[0] as Name).name] = resolveAlias(ops[1])
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
        val seconds = v(0)
        // The game counts the time left down each tick; NaN never runs out.
        sleepUntilTick =
            if (seconds.isNaN()) Int.MAX_VALUE
            else ticks + ceil(seconds / SECONDS_PER_TICK).toInt() - 1
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
      "clamp" -> set(min(max(v(1), v(2)), v(3)))
      "sgn" -> set(Ic10Math.sign(v(1)))
      "mod" -> set(Ic10Math.mod(v(1), v(2)))
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

      "and" -> set(Ic10Math.and(v(1), v(2)))
      "or" -> set(Ic10Math.or(v(1), v(2)))
      "xor" -> set(Ic10Math.xor(v(1), v(2)))
      "nor" -> set(Ic10Math.nor(v(1), v(2)))
      "not" -> set(Ic10Math.not(v(1)))
      "sll",
      "sla" -> set(Ic10Math.shiftLeft(v(1), v(2)))
      "srl" -> set(Ic10Math.shiftRightLogical(v(1), v(2)))
      "sra" -> set(Ic10Math.shiftRightArithmetic(v(1), v(2)))
      "rol" -> set(Ic10Math.rotate(v(1), v(2), left = true))
      "ror" -> set(Ic10Math.rotate(v(1), v(2), left = false))
      "ext" -> set(Ic10Math.extract(v(1), v(2), v(3)))
      "ins" -> set(Ic10Math.insert(registers[destination(ops[0]).index], v(1), v(2), v(3)))

      "select" -> set(if (v(1) != 0.0) v(2) else v(3))
      "sdns" -> setBool(device(ops[1]) == null)
      "sdse" -> setBool(device(ops[1]) != null)
      "sap" -> setBool(approximately(v(1), v(2), v(3)))
      "sapz" -> setBool(approximately(v(1), 0.0, v(2)))
      "sna" -> setBool(notApproximately(v(1), v(2), v(3)))
      "snaz" -> setBool(notApproximately(v(1), 0.0, v(2)))
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
        // sp moves down before the check, so a failed pop still lowers it.
        val sp = stackPointer() - 1
        registers[Register.SP.index] = sp.toDouble()
        require(sp in stack.indices) { "stack underflow" }
        set(stack[sp])
      }
      "peek" -> {
        val sp = stackPointer() - 1
        require(sp in stack.indices) { "stack underflow" }
        set(stack[sp])
      }
      "poke" -> stack[address(ops[0])] = v(1)
      "get" -> set(memoryOf(ops[1])[address(ops[2])])
      "put" -> memoryOf(ops[0])[address(ops[1])] = v(2)
      "clr" -> memoryOf(ops[0]).fill(0.0)

      "l" -> set(requireDevice(ops[1]).read(logicType(ops[2])))
      "s" -> requireDevice(ops[0]).write(logicType(ops[1]), v(2))
      "ls" -> set(requireDevice(ops[1]).readSlot(integer(ops[2]), logicType(ops[3])))
      "ss" -> requireDevice(ops[0]).writeSlot(integer(ops[1]), logicType(ops[2]), v(3))
      "lb" -> set(batch(matching(v(1), null), ops[3]) { it.read(logicType(ops[2])) })
      // The game counts by prefab only for `lbn ... Count`, ignoring the name.
      "lbn" ->
          set(
              if (batchMethod(ops[4]) == COUNT) matching(v(1), null).size.toDouble()
              else batch(matching(v(1), v(2)), ops[4]) { it.read(logicType(ops[3])) }
          )
      "lbs" -> {
        val slot = integer(ops[2])
        set(batch(matching(v(1), null), ops[4]) { it.readSlot(slot, logicType(ops[3])) })
      }
      "sb" -> matching(v(0), null).forEach { it.write(logicType(ops[1]), v(2)) }
      "sbn" -> matching(v(0), v(1)).forEach { it.write(logicType(ops[2]), v(3)) }
      "sbs" ->
          matching(v(0), null).forEach { it.writeSlot(integer(ops[1]), logicType(ops[2]), v(3)) }

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
      "na" -> notApproximately(v(0), v(1), v(2))
      "naz" -> notApproximately(v(0), 0.0, v(1))
      "nan" -> v(0).isNaN()
      "dns" -> device(ops[0]) == null
      "dse" -> device(ops[0]) != null
      // "branch if device not valid load/store": the device lacks the logic type. No device at all
      // is an error, as in the game.
      "dnvl",
      "dnvs" -> !requireDevice(ops[0]).logic.containsKey(logicType(ops[1]))
      else -> error("unknown branch")
    }
  }

  /** The game's approximate equality: `|a - b| <= max(c * max(|a|, |b|), epsilon * 8)`. */
  private fun approximately(a: Double, b: Double, c: Double) =
      abs(a - b) <= max(c * max(abs(a), abs(b)), Float.MIN_VALUE.toDouble() * 8)

  /**
   * The game's `sna`: the same comparison with `>`, so NaN is neither approximately equal nor not.
   */
  private fun notApproximately(a: Double, b: Double, c: Double) =
      abs(a - b) > max(c * max(abs(a), abs(b)), Float.MIN_VALUE.toDouble() * 8)

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
        // The game gives a define whose value is another name NaN.
        is Name -> Constants.VALUES[operand.name] ?: Double.NaN
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

  private fun batchMethod(mode: Operand): Int =
      (if (mode is Name) BATCH_METHODS[mode.name] ?: value(mode) else value(mode)).toInt()

  /**
   * A batch read over [devices], as the game computes it: devices are visited last to first, an
   * empty Average is NaN (0/0), an empty Minimum is 0 and an empty Maximum is negative infinity.
   */
  private fun batch(
      devices: List<FakeDevice>,
      mode: Operand,
      read: (FakeDevice) -> Double,
  ): Double =
      when (val method = batchMethod(mode)) {
        COUNT -> devices.size.toDouble()
        0,
        1 -> {
          val sum = devices.asReversed().sumOf(read)
          if (method == 0) sum / devices.size else sum
        }
        2 -> {
          var minimum = Double.POSITIVE_INFINITY
          for (device in devices.asReversed()) {
            val value = read(device)
            if (!(value >= minimum)) minimum = value
          }
          if (minimum >= Double.POSITIVE_INFINITY) 0.0 else minimum
        }
        3 -> {
          var maximum = Double.NEGATIVE_INFINITY
          for (device in devices.asReversed()) {
            val value = read(device)
            if (!(value <= maximum)) maximum = value
          }
          maximum
        }
        else -> error("unknown batch method $method")
      }

  /** The stack index `sp` points at, rounded as the game rounds it. */
  private fun stackPointer() = Math.rint(registers[Register.SP.index]).toInt()

  /**
   * An operand used as a whole number (a jump target, address or slot), converted as the game does:
   * a register's value is rounded half to even, an alias's or define's truncated, and a literal
   * must already be whole.
   */
  private fun integer(operand: Operand): Int {
    val value = value(operand)
    return when (operand) {
      is Register -> Math.rint(value).toInt()
      is Number -> value.toInt().also { require(it.toDouble() == value) { "not a whole number" } }
      else -> value.toInt()
    }
  }

  private fun address(operand: Operand): Int =
      integer(operand).also { require(it in stack.indices) { "address $it is out of range" } }

  companion object {
    const val SECONDS_PER_TICK = 0.5

    private const val COUNT = 4

    val BATCH_METHODS =
        mapOf(
            "Average" to 0.0,
            "Sum" to 1.0,
            "Minimum" to 2.0,
            "Maximum" to 3.0,
            "Count" to COUNT.toDouble(),
        )
  }
}
