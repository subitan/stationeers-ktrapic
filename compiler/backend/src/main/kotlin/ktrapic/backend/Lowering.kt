package ktrapic.backend

import ktrapic.ic10.Ic10Limits
import ktrapic.ic10.LabelRef
import ktrapic.ic10.Line
import ktrapic.ic10.Number
import ktrapic.ic10.Operand
import ktrapic.ic10.Program
import ktrapic.ic10.Register

/**
 * A function called with its arguments and result on the stack: its [name], and whether it is in a
 * cycle of calls.
 */
data class StackCalled(val name: String, val recursive: Boolean)

/**
 * A compiled [program] and, for each of its lines, the IR start offset of the source statement it
 * comes from ([TirInstruction.offset]), or null. [stackCalled] is the functions that the program
 * still calls on the stack, in the order their first calls appear in it. [hasCycle] is whether the
 * program declares a cycle of calls, even one it never calls into.
 */
data class Lowered(
    val program: Program,
    val offsets: List<Int?>,
    val stackCalled: List<StackCalled> = emptyList(),
    val hasCycle: Boolean = false,
)

/**
 * Tidies [TirLine]s, allocates registers and produces the IC10 [Program], expanding each call on
 * the stack ([TirInstruction.CALL]) as it goes.
 */
object Lowering {
  /**
   * [stackFunctions] describes the function at each label that calls on the stack go to. With
   * [spill], values that don't fit in registers are kept in the chip's stack memory (see
   * [Spilling]); without, the program must fit.
   */
  fun lower(
      lines: List<TirLine>,
      stackFunctions: Map<String, StackCalled> = emptyMap(),
      spill: Boolean = false,
  ): Lowered {
    val (simplified, registers, slots) =
        if (spill) {
          Spilling.allocate(simplify(lines), stackFunctions)
        } else {
          simplify(lines).let { Allocated(it, RegisterAllocator.allocate(it), 0) }
        }
    val liveOut = Liveness.liveOut(simplified)
    val written = Liveness.maybeWritten(simplified)
    val byId =
        simplified
            .filterIsInstance<TirInstruction>()
            .flatMap { listOfNotNull(it.def) + it.uses }
            .associateBy { it.id }
    val program = mutableListOf<Line>()
    val offsets = mutableListOf<Int?>()
    // How much each call on the stack leaves `sp` changed by, for each function called: the
    // function pops its arguments and pushes its result.
    val stackEffects = mutableMapOf<String, Int>()
    var index = -1
    for (line in simplified) {
      when (line) {
        is TirLabel -> {
          program += Line.Label(line.name)
          offsets += null
        }
        is TirInstruction -> {
          index++
          fun physical(operand: TirOperand): Operand =
              when (operand) {
                is VReg -> registers.getValue(operand)
                is Fixed -> operand.operand
              }
          if (line.op == TirInstruction.CALL) {
            // The registers the caller needs after the call, pushed below the arguments and
            // popped back after the result. A global the call reads and writes stays in its
            // register, the result is new, and a register no path to the call has written holds
            // nothing to keep.
            val saved =
                liveOut[index]
                    .stream()
                    .toArray()
                    .filter { id -> id != line.result?.id && line.globals.none { it.id == id } }
                    .filter { written[index].get(it) }
                    .map { registers.getValue(byId.getValue(it)) }
                    .distinct()
                    .sortedBy { it.index }
            val label = line.operands.last()
            ((label as? Fixed)?.operand as? LabelRef)?.let {
              stackEffects[it.name] = (if (line.result != null) 1 else 0) - (line.operands.size - 1)
            }
            val expanded = buildList {
              saved.forEach { add(Line.Instruction("push", listOf(it))) }
              line.operands.dropLast(1).forEach {
                add(Line.Instruction("push", listOf(physical(it))))
              }
              add(Line.Instruction("jal", listOf(physical(label))))
              line.result?.let { add(Line.Instruction("pop", listOf(physical(it)))) }
              saved.asReversed().forEach { add(Line.Instruction("pop", listOf(it))) }
            }
            program += expanded
            repeat(expanded.size) { offsets += line.offset }
            continue
          }
          val operands = line.operands.map(::physical)
          if (line.op == "move" && operands[0] == operands[1]) continue
          program += Line.Instruction(line.op, operands)
          offsets += line.offset
        }
      }
    }
    if (slots > 0) guardRecursion(program, offsets, stackEffects, Ic10Limits.STACK_SIZE - slots)
    return Lowered(
        Program(program),
        offsets,
        stackCalled =
            simplified
                .filter { it is TirInstruction && it.op == TirInstruction.CALL }
                .mapNotNull { call ->
                  val label = ((call as TirInstruction).operands.last() as? Fixed)?.operand
                  (label as? LabelRef)?.let { stackFunctions[it.name] }
                }
                .distinct(),
    )
  }

  /**
   * Has each function of [program] in a cycle of calls stop the chip, as it is called, if its calls
   * could push values at or above [lowest], the lowest address of the stack that holds values kept
   * in memory. `push` itself only fails at the top of the stack, after overwriting them.
   *
   * Between one such check and the next, the stack grows by at most what the function itself pushes
   * ([stackEffects] says what each call on the stack leaves behind), and what the functions outside
   * any cycle that it may call, directly or through others outside a cycle, push, each at most
   * once. So the check, `bgt sp limit overflow`, allows that much room; `overflow:` is an `hcf`,
   * placed before the first checked function, where only a jump reaches it. A program with no cycle
   * of calls gets no check: its stack is never deeper than all its functions' pushes together,
   * fewer than its lines.
   */
  private fun guardRecursion(
      program: MutableList<Line>,
      offsets: MutableList<Int?>,
      stackEffects: Map<String, Int>,
      lowest: Int,
  ) {
    fun Line.Instruction.label() = (operands.lastOrNull() as? LabelRef)?.name
    val functions =
        program
            .filterIsInstance<Line.Instruction>()
            .filter { it.op == "jal" }
            .mapNotNullTo(mutableSetOf()) { it.label() }
    // Each function's calls, and how far above its entry it takes the stack; null is the program
    // before the functions.
    val calls = mutableMapOf<String?, MutableSet<String>>()
    val deepest = mutableMapOf<String?, Int>()
    var function: String? = null
    var depth = 0
    for (line in program) {
      when (line) {
        is Line.Label ->
            if (line.name in functions) {
              function = line.name
              depth = 0
            }
        is Line.Instruction ->
            when (line.op) {
              "push" -> depth++
              "pop" -> depth--
              "jal" -> {
                val target = line.label()!!
                calls.getOrPut(function) { mutableSetOf() } += target
                depth += stackEffects[target] ?: 0
              }
            }
        else -> {}
      }
      deepest.merge(function, depth, ::maxOf)
    }
    fun reaches(from: String, to: String): Boolean {
      val seen = mutableSetOf<String>()
      val pending = ArrayDeque(calls[from].orEmpty())
      while (pending.isNotEmpty()) {
        val next = pending.removeFirst()
        if (next == to) return true
        if (seen.add(next)) pending += calls[next].orEmpty()
      }
      return false
    }
    val cyclic = functions.filterTo(mutableSetOf()) { reaches(it, it) }
    if (cyclic.isEmpty()) return
    // The functions outside any cycle that [from] may call before the next check, directly or
    // through others outside a cycle.
    fun acyclicCallees(from: String): Set<String> {
      val found = mutableSetOf<String>()
      val pending = ArrayDeque(calls[from].orEmpty())
      while (pending.isNotEmpty()) {
        val next = pending.removeFirst()
        if (next !in cyclic && found.add(next)) pending += calls[next].orEmpty()
      }
      return found
    }
    val labels = program.filterIsInstance<Line.Label>().mapTo(mutableSetOf()) { it.name }
    val overflow = generateSequence("overflow") { it + "_" }.first { it !in labels }
    var placed = false
    var at = 0
    while (at < program.size) {
      val line = program[at]
      if (line is Line.Label && line.name in cyclic) {
        if (!placed) {
          placed = true
          // Function bodies follow the program's `j end`, and each ends in `j ra`.
          val before = program.subList(0, at).lastOrNull { it is Line.Instruction }
          check((before as? Line.Instruction)?.op == "j") { "${line.name} can be run into" }
          program.addAll(at, listOf(Line.Label(overflow), Line.Instruction("hcf", emptyList())))
          offsets.addAll(at, listOf(null, null))
          at += 2
        }
        val room = (deepest[line.name] ?: 0) + acyclicCallees(line.name).sumOf { deepest[it] ?: 0 }
        val limit = lowest - room
        program.add(
            at + 1,
            Line.Instruction(
                "bgt",
                listOf(Register.SP, Number(limit.toDouble()), LabelRef(overflow)),
            ),
        )
        offsets.add(at + 1, null)
        at++
      }
      at++
    }
  }

  /** Repeats the clean-ups until none changes anything. */
  fun simplify(lines: List<TirLine>): List<TirLine> {
    var current = lines
    while (true) {
      val next =
          removeUnusedLabels(
              removeDeadDefinitions(
                  removeJumpsToNext(invertBranchesOverJumps(removeUnreachable(current)))
              )
          )
      if (next == current) return current
      current = next
    }
  }

  /** Drops instructions after a `j` that no label makes reachable. */
  private fun removeUnreachable(lines: List<TirLine>): List<TirLine> {
    val result = mutableListOf<TirLine>()
    var reachable = true
    for (line in lines) {
      if (line is TirLabel) reachable = true
      if (reachable) result += line
      if (line is TirInstruction && line.isUnconditionalJump) reachable = false
    }
    return result
  }

  /** Drops a `j` to a label that is already next. */
  private fun removeJumpsToNext(lines: List<TirLine>): List<TirLine> =
      lines.filterIndexed { index, line ->
        if (line !is TirInstruction || !line.isUnconditionalJump) return@filterIndexed true
        val following =
            lines.drop(index + 1).takeWhile { it is TirLabel }.map { (it as TirLabel).name }
        line.target !in following
      }

  /**
   * Turns `bX a b skip; j far; skip:` into `bY a b far` (Y the negation of X), as `if (c) break`
   * and similar produce. A branch that is not [TirInstruction.negatable] is left as it is, and so
   * is a branch over `j ra`, which has no label for the branch to take (the translator puts no
   * branch target right after a `j ra`, so this only guards against one). The merged branch keeps
   * the branch's source line, so a `break` or `continue` on a line of its own after the condition
   * has no line of code mapped to it.
   */
  private fun invertBranchesOverJumps(lines: List<TirLine>): List<TirLine> {
    val result = lines.toMutableList()
    var index = 0
    while (index < result.size - 1) {
      val branch = result[index] as? TirInstruction
      val jump = result[index + 1] as? TirInstruction
      val negated = branch?.takeIf { it.negatable }?.op?.let { NEGATED_BRANCHES[it] }
      if (negated != null && jump != null && jump.isUnconditionalJump && jump.target != null) {
        val following =
            result.drop(index + 2).takeWhile { it is TirLabel }.map { (it as TirLabel).name }
        if (branch.target in following) {
          result[index] =
              branch.copy(op = negated, operands = branch.operands.dropLast(1) + jump.operands)
          result.removeAt(index + 1)
        }
      }
      index++
    }
    return result
  }

  /** Drops instructions whose only effect is writing a register nothing reads. */
  private fun removeDeadDefinitions(lines: List<TirLine>): List<TirLine> {
    val liveOut = Liveness.liveOut(lines)
    var index = -1
    return lines.filter { line ->
      if (line !is TirInstruction) return@filter true
      index++
      val def = line.def ?: return@filter true
      line.op in IMPURE || liveOut[index].get(def.id)
    }
  }

  private fun removeUnusedLabels(lines: List<TirLine>): List<TirLine> {
    val used =
        lines.filterIsInstance<TirInstruction>().flatMapTo(mutableSetOf()) { instruction ->
          instruction.operands.mapNotNull { ((it as? Fixed)?.operand as? LabelRef)?.name }
        }
    return lines.filter { it !is TirLabel || it.name in used }
  }

  /** Ops that write a register but also do something else, so are kept even when unread. */
  private val IMPURE = setOf("pop", TirInstruction.CALL)
}

private val NEGATED_BRANCHES: Map<String, String> =
    listOf("eq" to "ne", "lt" to "ge", "le" to "gt")
        .flatMap { (a, b) -> listOf(a to b, b to a) }
        .flatMap { (a, b) -> listOf("b$a" to "b$b", "b${a}z" to "b${b}z") }
        .toMap()
