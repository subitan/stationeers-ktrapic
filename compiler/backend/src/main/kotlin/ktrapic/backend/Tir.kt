package ktrapic.backend

import ktrapic.ic10.JumpKind
import ktrapic.ic10.LabelRef
import ktrapic.ic10.Opcodes
import ktrapic.ic10.Operand

/**
 * The backend's intermediate form: IC10 instructions whose register operands are virtual, so a
 * program can use as many values as it likes until [RegisterAllocator] maps them onto r0–r15.
 */
sealed interface TirOperand

/** A virtual register. [name] is the Kotlin variable it holds, if any, for comments. */
class VReg(val id: Int, val name: String?) : TirOperand {
  override fun toString() = "v$id" + (name?.let { "($it)" } ?: "")
}

/** An operand that is final as it is: a number, device, logic type name, label, or string. */
data class Fixed(val operand: Operand) : TirOperand

sealed interface TirLine

data class TirLabel(val name: String) : TirLine

/**
 * [negatable] is false for a branch whose opposite branch is not its negation (an ordered
 * comparison that may see NaN), which must not be inverted.
 */
data class TirInstruction(
    val op: String,
    val operands: List<TirOperand>,
    val negatable: Boolean = true,
) : TirLine {
  constructor(op: String, vararg operands: TirOperand) : this(op, operands.toList())

  /** The virtual register this instruction writes, if any: the first operand of most ops. */
  val def: VReg?
    get() = if (op in NO_DEF) null else operands.firstOrNull() as? VReg

  /** The virtual registers this instruction reads. */
  val uses: List<VReg>
    get() = (if (def == null) operands else operands.drop(1)).filterIsInstance<VReg>()

  /** True if control never continues to the next line. */
  val isUnconditionalJump: Boolean
    get() = op == "j"

  /** The label this instruction may jump to, if any. */
  val target: String?
    get() =
        if (Opcodes[op]?.jump == JumpKind.ABSOLUTE) {
          ((operands.lastOrNull() as? Fixed)?.operand as? LabelRef)?.name
        } else null

  companion object {
    /** Ops that write no register, whose first operand is read (or is not a register). */
    private val NO_DEF =
        setOf("s", "sb", "sbn", "sbs", "ss", "put", "putd", "push", "sleep", "yield", "j") +
            Opcodes.all.values.filter { it.jump != JumpKind.NONE }.map { it.name }
  }
}
