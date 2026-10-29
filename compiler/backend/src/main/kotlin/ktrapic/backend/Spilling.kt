package ktrapic.backend

import java.util.BitSet
import ktrapic.ic10.Device
import ktrapic.ic10.LabelRef
import ktrapic.ic10.Number
import ktrapic.ic10.Register

/**
 * [lines] with the registers [RegisterAllocator] gave them, and the number of memory [slots] at the
 * top of the chip's stack (511, 510 and down) that hold the values that didn't fit.
 */
internal data class Allocated(
    val lines: List<TirLine>,
    val registers: Map<VReg, Register>,
    val slots: Int,
)

/**
 * Keeps values in memory when a program annotated `@file:CircuitHousingOnly` needs more registers
 * than the chip has: in an IC housing, a rocket circuit housing or a suit, `get` and `put` on `db`
 * reach the chip's own stack, whose top addresses calls on the stack, growing up from 0, only reach
 * in a very deep recursion (which [Lowering] guards against).
 *
 * This works on [TirLine]s, before [Lowering] expands calls on the stack, so the values it moves to
 * memory are no longer live in a register across a call, and the call neither pushes nor pops them.
 */
internal object Spilling {
  /** The highest address of the chip's stack, the first slot used. */
  const val TOP_SLOT = 511

  private val DB = Fixed(Device("db"))

  /**
   * Allocates registers for [lines] as [RegisterAllocator] does, and when they don't fit in [limit]
   * registers, moves values to memory one at a time until they do. Each use of a value in memory
   * reads it into a register of its own just before (`get vT db slot`), and each write writes one
   * just after (`put db slot vT`); a `move` to or from it becomes the `put` or `get` itself. Those
   * registers live for one instruction, so the values around them have more room.
   *
   * A program that uses memory gets a `get` of the top slot as its first line, which fails at once
   * where `db` is not the chip's stack. [stackFunctions] tells which calls go to a recursive
   * function: a value live across one stays in its register, as the recursion would reuse its slot.
   *
   * Throws the first [TooManyRegistersException] when no value is left to move.
   */
  fun allocate(
      lines: List<TirLine>,
      stackFunctions: Map<String, StackCalled>,
      limit: Int = 16,
  ): Allocated {
    val failure =
        try {
          return Allocated(lines, RegisterAllocator.allocate(lines, limit), 0)
        } catch (e: TooManyRegistersException) {
          e
        }
    var nextId =
        lines
            .filterIsInstance<TirInstruction>()
            .flatMap { listOfNotNull(it.def, it.result) + it.uses }
            .maxOfOrNull { it.id }
            ?.plus(1) ?: 0
    val scratch = mutableSetOf<Int>()
    fun fresh(name: String?) = VReg(nextId++, name).also { scratch += it.id }
    val check = TirInstruction("get", listOf(fresh(null), DB, Fixed(Number(TOP_SLOT.toDouble()))))
    val recursive = stackFunctions.filterValues { it.recursive }.keys
    var current = lines
    var slots = 0
    while (true) {
      val spilled = choose(current, scratch, recursive, limit) ?: throw failure
      current = rewrite(current, spilled, TOP_SLOT - slots, ::fresh)
      slots++
      val program = listOf(check) + current
      try {
        return Allocated(program, RegisterAllocator.allocate(program, limit), slots)
      } catch (_: TooManyRegistersException) {}
    }
  }

  /**
   * The value to move to memory next, or null if none can be: one live where the most values are,
   * read and written least often over the longest stretch of the program. A value read or written
   * by [scratch] registers only can't be, and nor can one that a function in [recursive] keeps
   * across a call into a cycle of calls, which could come back to it and reuse its address.
   */
  private fun choose(
      lines: List<TirLine>,
      scratch: Set<Int>,
      recursive: Set<String>,
      limit: Int,
  ): VReg? {
    val instructions = lines.filterIsInstance<TirInstruction>()
    val liveOut = Liveness.liveOut(lines)
    val functions =
        instructions
            .filter { it.op == "jal" || it.op == TirInstruction.CALL }
            .mapNotNullTo(mutableSetOf()) { it.calls() }
    val byId = mutableMapOf<Int, VReg>()
    val occurrences = mutableMapOf<Int, Int>()
    val pinned = BitSet()
    // The function whose body each instruction is in; null before the functions.
    var function: String? = null
    var index = -1
    for (line in lines) {
      if (line is TirLabel) {
        if (line.name in functions) function = line.name
        continue
      }
      val instruction = line as TirInstruction
      index++
      val named =
          (instruction.operands.filterIsInstance<VReg>() + listOfNotNull(instruction.result))
              .distinctBy { it.id }
      for (vreg in named) {
        byId[vreg.id] = vreg
        occurrences.merge(vreg.id, 1, Int::plus)
      }
      instruction.globals.forEach { byId[it.id] = it }
      if (
          instruction.op == TirInstruction.CALL &&
              function in recursive &&
              instruction.calls() in recursive
      ) {
        val across = liveOut[index].clone() as BitSet
        instruction.result?.let { across.clear(it.id) }
        instruction.globals.forEach { across.clear(it.id) }
        pinned.or(across)
      }
    }
    val candidates = byId.keys.filter { it !in scratch && !pinned.get(it) && it in occurrences }
    if (candidates.isEmpty()) return null
    val span = mutableMapOf<Int, Int>()
    val pressure =
        instructions.indices.map { index ->
          val live = liveOut[index].clone() as BitSet
          instructions[index].def?.let { live.set(it.id) }
          live.stream().forEach { span.merge(it, 1, Int::plus) }
          live
        }
    val most = pressure.maxOf { it.cardinality() }
    val crowded = BitSet()
    if (most > limit) pressure.filter { it.cardinality() == most }.forEach { crowded.or(it) }
    val preferred = candidates.filter { crowded.get(it) }.ifEmpty { candidates }
    return preferred
        .minWithOrNull(
            compareBy<Int> { occurrences.getValue(it).toDouble() / (span[it] ?: 1) }
                .thenByDescending { span[it] ?: 0 }
                .thenBy { it }
        )
        ?.let { byId.getValue(it) }
  }

  /** The label a [TirInstruction.CALL] or `jal` calls. */
  private fun TirInstruction.calls(): String? =
      ((operands.lastOrNull() as? Fixed)?.operand as? LabelRef)?.name

  /**
   * [lines] with [spilled] kept in memory at [slot]: no instruction names it any longer, and the
   * calls and returns that keep the globals in their registers no longer list it.
   */
  private fun rewrite(
      lines: List<TirLine>,
      spilled: VReg,
      slot: Int,
      fresh: (String?) -> VReg,
  ): List<TirLine> = buildList {
    val address = Fixed(Number(slot.toDouble()))
    fun TirOperand.isSpilled() = this is VReg && id == spilled.id
    for (line in lines) {
      if (line !is TirInstruction) {
        add(line)
        continue
      }
      val instruction =
          if (line.globals.any { it.isSpilled() }) {
            line.copy(globals = line.globals.filterTo(linkedSetOf()) { !it.isSpilled() })
          } else line
      val isResult = instruction.result?.isSpilled() == true
      if (!isResult && instruction.operands.none { it.isSpilled() }) {
        add(instruction)
        continue
      }
      if (instruction.op == "move") {
        val (to, from) = instruction.operands
        when {
          to.isSpilled() && from.isSpilled() -> {}
          to.isSpilled() -> add(instruction.copy(op = "put", operands = listOf(DB, address, from)))
          else -> add(instruction.copy(op = "get", operands = listOf(to, DB, address)))
        }
        continue
      }
      val reads = instruction.uses.any { it.isSpilled() }
      val writes = instruction.def?.isSpilled() == true
      val temp = fresh(spilled.name)
      if (reads) {
        add(TirInstruction("get", listOf(temp, DB, address), offset = instruction.offset))
      }
      add(
          instruction.copy(
              operands = instruction.operands.map { if (it.isSpilled()) temp else it },
              result = if (isResult) temp else instruction.result,
          )
      )
      if (writes) {
        add(TirInstruction("put", listOf(DB, address, temp), offset = instruction.offset))
      }
    }
  }
}
