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
 * comparison that may see NaN), which must not be inverted. [returnFrom] marks a function's `j ra`
 * with the function's label, so control flow can follow it back to each `jal` to that label (a
 * function called on the stack has no [returnFrom]: its `j ra` leads nowhere control flow follows).
 * [offset] is the IR start offset of the source statement the instruction comes from, if any, for
 * the source map.
 *
 * A call to a function that takes its arguments and gives its result on the stack is the pseudo-op
 * [CALL], whose operands are the arguments, in order, then the function's label, and which writes
 * [result] if the function has one. Control flow treats it as continuing on the next line, as the
 * function keeps none of its caller's values in a register: [Lowering] expands it, once registers
 * are allocated, into pushes of what is live across it, the call, and pops. The return of such a
 * function leads nowhere control flow follows.
 *
 * [globals] are the registers of the top-level properties that functions called on the stack read
 * or write, themselves or through the functions they call: a [CALL] reads and writes them all, and
 * such a function's `j ra` reads them, so they stay in their registers across calls rather than
 * being saved.
 */
data class TirInstruction(
    val op: String,
    val operands: List<TirOperand>,
    val negatable: Boolean = true,
    val returnFrom: String? = null,
    val offset: Int? = null,
    val result: VReg? = null,
    val globals: Set<VReg> = emptySet(),
) : TirLine {
  constructor(op: String, vararg operands: TirOperand) : this(op, operands.toList())

  /** The virtual register this instruction writes, if any: the first operand of most ops. */
  val def: VReg?
    get() =
        when (op) {
          CALL -> result
          in NO_DEF -> null
          else -> operands.firstOrNull() as? VReg
        }

  /** The virtual registers this instruction writes besides [def]: a [CALL]'s [globals]. */
  val clobbers: Set<VReg>
    get() = if (op == CALL) globals else emptySet()

  /** The virtual registers this instruction reads. */
  val uses: List<VReg>
    get() =
        (if (op == CALL || def == null) operands else operands.drop(1)).filterIsInstance<VReg>() +
            globals

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
    /** The pseudo-op of a call on the stack, before [Lowering] expands it (see [stack]). */
    const val CALL = "call"

    /** Ops that write no register, whose first operand is read (or is not a register). */
    private val NO_DEF =
        setOf("s", "sb", "sbn", "sbs", "ss", "put", "putd", "push", "sleep", "yield", "j") +
            Opcodes.all.values.filter { it.jump != JumpKind.NONE }.map { it.name }
  }
}
