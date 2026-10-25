package ktrapic.backend

import ktrapic.ic10.LabelRef
import ktrapic.ic10.Line
import ktrapic.ic10.Program

/** Tidies [TirLine]s, allocates registers and produces the IC10 [Program]. */
object Lowering {
  fun lower(lines: List<TirLine>): Program {
    val simplified = simplify(lines)
    val registers = RegisterAllocator.allocate(simplified)
    val program = mutableListOf<Line>()
    for (line in simplified) {
      when (line) {
        is TirLabel -> program += Line.Label(line.name)
        is TirInstruction -> {
          val operands =
              line.operands.map {
                when (it) {
                  is VReg -> registers.getValue(it)
                  is Fixed -> it.operand
                }
              }
          if (line.op == "move" && operands[0] == operands[1]) continue
          program += Line.Instruction(line.op, operands)
        }
      }
    }
    return Program(program)
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
   * and similar produce.
   */
  private fun invertBranchesOverJumps(lines: List<TirLine>): List<TirLine> {
    val result = lines.toMutableList()
    var index = 0
    while (index < result.size - 1) {
      val branch = result[index] as? TirInstruction
      val jump = result[index + 1] as? TirInstruction
      val negated = branch?.op?.let { NEGATED_BRANCHES[it] }
      if (negated != null && jump != null && jump.isUnconditionalJump) {
        val following =
            result.drop(index + 2).takeWhile { it is TirLabel }.map { (it as TirLabel).name }
        if (branch.target in following) {
          result[index] = TirInstruction(negated, branch.operands.dropLast(1) + jump.operands)
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
  private val IMPURE = setOf("pop")
}

private val NEGATED_BRANCHES: Map<String, String> =
    listOf("eq" to "ne", "lt" to "ge", "le" to "gt")
        .flatMap { (a, b) -> listOf(a to b, b to a) }
        .flatMap { (a, b) -> listOf("b$a" to "b$b", "b${a}z" to "b${b}z") }
        .toMap()
