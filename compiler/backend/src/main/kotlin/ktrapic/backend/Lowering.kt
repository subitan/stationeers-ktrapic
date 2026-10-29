package ktrapic.backend

import ktrapic.ic10.LabelRef
import ktrapic.ic10.Line
import ktrapic.ic10.Operand
import ktrapic.ic10.Program

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
  /** [stackFunctions] describes the function at each label that calls on the stack go to. */
  fun lower(lines: List<TirLine>, stackFunctions: Map<String, StackCalled> = emptyMap()): Lowered {
    val simplified = simplify(lines)
    val registers = RegisterAllocator.allocate(simplified)
    val liveOut = Liveness.liveOut(simplified)
    val written = Liveness.maybeWritten(simplified)
    val byId =
        simplified
            .filterIsInstance<TirInstruction>()
            .flatMap { listOfNotNull(it.def) + it.uses }
            .associateBy { it.id }
    val program = mutableListOf<Line>()
    val offsets = mutableListOf<Int?>()
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
