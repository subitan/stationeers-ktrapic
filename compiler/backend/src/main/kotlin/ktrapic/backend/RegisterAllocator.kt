package ktrapic.backend

import java.util.BitSet
import ktrapic.ic10.Register

/** The program needs more registers than the chip has. */
class TooManyRegistersException(val needed: Int) :
    Exception("The program needs $needed registers at once; the chip has 16 (r0–r15)")

/**
 * Maps virtual registers onto r0–r15 by colouring the interference graph that liveness analysis
 * gives. Two values interfere when one is written while the other is still needed. A `move`'s
 * source and destination are kept apart only if they interfere otherwise, and are given the same
 * register when possible, which turns the move into `move rN rN` for [Lowering] to drop.
 */
object RegisterAllocator {
  fun allocate(lines: List<TirLine>, limit: Int = 16): Map<VReg, Register> {
    val instructions = lines.filterIsInstance<TirInstruction>()
    val liveOut = Liveness.liveOut(lines)
    val vregs = instructions.flatMap { listOfNotNull(it.def) + it.uses }.distinct()
    val byId = vregs.associateBy { it.id }
    val neighbours = vregs.associateWith { mutableSetOf<VReg>() }
    val moveRelated = vregs.associateWith { mutableSetOf<VReg>() }
    for ((index, instruction) in instructions.withIndex()) {
      val def = instruction.def ?: continue
      val source = if (instruction.op == "move") instruction.operands[1] as? VReg else null
      if (source != null) {
        moveRelated.getValue(def) += source
        moveRelated.getValue(source) += def
      }
      liveOut[index].stream().forEach { id ->
        val other = byId.getValue(id)
        if (other != def && other != source) {
          neighbours.getValue(def) += other
          neighbours.getValue(other) += def
        }
      }
    }
    val colours = mutableMapOf<VReg, Int>()
    for (vreg in vregs) {
      val taken = neighbours.getValue(vreg).mapNotNullTo(mutableSetOf()) { colours[it] }
      val preferred =
          moveRelated.getValue(vreg).mapNotNull { colours[it] }.firstOrNull { it !in taken }
      colours[vreg] = preferred ?: generateSequence(0) { it + 1 }.first { it !in taken }
    }
    val needed = (colours.values.maxOrNull() ?: -1) + 1
    if (needed > limit) throw TooManyRegistersException(needed)
    return colours.mapValues { Register.r(it.value) }
  }
}

/** Which virtual registers are still needed after each instruction. */
object Liveness {
  /** For each instruction of [lines], in order, the ids of the registers live after it. */
  fun liveOut(lines: List<TirLine>): List<BitSet> {
    val instructions = mutableListOf<TirInstruction>()
    val labelIndex = mutableMapOf<String, Int>()
    for (line in lines) {
      when (line) {
        is TirLabel -> labelIndex[line.name] = instructions.size
        is TirInstruction -> instructions += line
      }
    }
    // A call continues in the function, and the function's return continues after every call to
    // it, so a value needed after any call is live throughout the function it calls.
    val returnSites = mutableMapOf<String, MutableList<Int>>()
    instructions.forEachIndexed { index, instruction ->
      if (instruction.op == "jal") {
        returnSites.getOrPut(instruction.target!!) { mutableListOf() } += index + 1
      }
    }
    val successors = instructions.mapIndexed { index, instruction ->
      buildList {
        val falls = !instruction.isUnconditionalJump && instruction.op != "jal"
        if (falls && index + 1 < instructions.size) add(index + 1)
        instruction.target
            ?.let { labelIndex.getValue(it) }
            ?.let {
              if (it < instructions.size) add(it)
            }
        instruction.returnFrom?.let { function ->
          returnSites[function].orEmpty().filter { it < instructions.size }.forEach(::add)
        }
      }
    }
    val liveIn = Array(instructions.size) { BitSet() }
    val liveOut = Array(instructions.size) { BitSet() }
    var changed = true
    while (changed) {
      changed = false
      for (index in instructions.indices.reversed()) {
        val out = BitSet()
        for (successor in successors[index]) out.or(liveIn[successor])
        val inSet = out.clone() as BitSet
        instructions[index].def?.let { inSet.clear(it.id) }
        for (use in instructions[index].uses) inSet.set(use.id)
        if (out != liveOut[index] || inSet != liveIn[index]) {
          liveOut[index] = out
          liveIn[index] = inSet
          changed = true
        }
      }
    }
    return liveOut.toList()
  }
}
