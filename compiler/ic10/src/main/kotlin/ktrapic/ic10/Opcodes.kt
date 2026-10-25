package ktrapic.ic10

/** What an instruction does with control flow, and which operand holds its jump target. */
enum class JumpKind {
  /** Falls through to the next line. */
  NONE,
  /** Jumps to the line number in its target operand. */
  ABSOLUTE,
  /** Jumps by the offset in its target operand, relative to its own line. */
  RELATIVE,
}

/** The shape of one IC10 instruction: how many operands it takes and how it jumps. */
data class Opcode(val name: String, val arity: Int, val jump: JumpKind = JumpKind.NONE) {
  /** Index of the jump target operand: the first for `j`/`jal`/`jr`, otherwise the last. */
  val targetIndex: Int?
    get() =
        when {
          jump == JumpKind.NONE -> null
          name in setOf("j", "jal", "jr") -> 0
          else -> arity - 1
        }

  /** Whether the instruction stores the next line number in `ra` when it jumps. */
  val links: Boolean
    get() = name == "jal" || (name.startsWith("b") && name.endsWith("al"))

  /**
   * Indices of the operands that are names rather than values: the name a `define`, `alias` or
   * `label` declares, and logic types, slot logic types, reagent modes and batch modes, which the
   * game looks up by name. A label never stands in for one of these.
   */
  val nameIndices: Set<Int>
    get() = NAME_OPERANDS[name] ?: emptySet()

  /**
   * Index of the operand that declares a name, or null: the first for `define` and `alias`, the
   * second for `label`, which takes the device first (`label d0 Sensor`).
   */
  val declaredIndex: Int?
    get() =
        when (name) {
          "define",
          "alias" -> 0
          "label" -> 1
          else -> null
        }

  private companion object {
    val NAME_OPERANDS =
        mapOf(
            "alias" to setOf(0),
            "label" to setOf(1),
            "ld" to setOf(2),
            "sd" to setOf(1),
            "define" to setOf(0),
            "bdnvl" to setOf(1),
            "bdnvs" to setOf(1),
            "l" to setOf(2),
            "lb" to setOf(2, 3),
            "lbn" to setOf(3, 4),
            "lbns" to setOf(4, 5),
            "lbs" to setOf(3, 4),
            "lr" to setOf(2),
            "ls" to setOf(3),
            "s" to setOf(1),
            "sb" to setOf(1),
            "sbn" to setOf(2),
            "sbs" to setOf(2),
            "ss" to setOf(2),
        )
  }
}

/** The IC10 instruction set, by name. */
object Opcodes {
  private val fixed =
      listOf(
          // Control.
          "alias" to 2,
          "define" to 2,
          // An older spelling of alias, with its operands the other way round.
          "label" to 2,
          "hcf" to 0,
          "sleep" to 1,
          "yield" to 0,
          // Maths: destination register first.
          "abs" to 2,
          "acos" to 2,
          "add" to 3,
          "asin" to 2,
          "atan" to 2,
          "atan2" to 3,
          "ceil" to 2,
          "clamp" to 4,
          "cos" to 2,
          "div" to 3,
          "exp" to 2,
          "floor" to 2,
          "lerp" to 4,
          "log" to 2,
          "max" to 3,
          "min" to 3,
          "mod" to 3,
          "move" to 2,
          "mul" to 3,
          "pow" to 3,
          "rand" to 1,
          "round" to 2,
          "sgn" to 2,
          "sin" to 2,
          "sqrt" to 2,
          "sub" to 3,
          "tan" to 2,
          "trunc" to 2,
          // Bitwise.
          "and" to 3,
          "ext" to 4,
          "ins" to 4,
          "nor" to 3,
          "not" to 2,
          "or" to 3,
          "rol" to 3,
          "ror" to 3,
          "sla" to 3,
          "sll" to 3,
          "sra" to 3,
          "srl" to 3,
          "xor" to 3,
          // Compare and select.
          "sap" to 4,
          "sapz" to 3,
          "sdns" to 2,
          "sdse" to 2,
          "select" to 4,
          "seq" to 3,
          "seqz" to 2,
          "sge" to 3,
          "sgez" to 2,
          "sgt" to 3,
          "sgtz" to 2,
          "sle" to 3,
          "slez" to 2,
          "slt" to 3,
          "sltz" to 2,
          "sna" to 4,
          "snan" to 2,
          "snanz" to 2,
          "snaz" to 3,
          "sne" to 3,
          "snez" to 2,
          // Stack.
          "clr" to 1,
          "clrd" to 1,
          "get" to 3,
          "getd" to 3,
          "peek" to 1,
          "poke" to 2,
          "pop" to 1,
          "push" to 1,
          "put" to 3,
          "putd" to 3,
          // Devices.
          "l" to 3,
          "ld" to 3,
          "lb" to 4,
          "lbn" to 5,
          "lbns" to 6,
          "lbs" to 5,
          "lr" to 4,
          "ls" to 4,
          "rmap" to 3,
          "s" to 3,
          "sd" to 3,
          "sb" to 3,
          "sbn" to 4,
          "sbs" to 4,
          "ss" to 4,
      )

  /** Branch conditions and how many operands each tests, before the jump target. */
  private val conditions =
      mapOf(
          "ap" to 3,
          "apz" to 2,
          "dns" to 1,
          "dse" to 1,
          "eq" to 2,
          "eqz" to 1,
          "ge" to 2,
          "gez" to 1,
          "gt" to 2,
          "gtz" to 1,
          "le" to 2,
          "lez" to 1,
          "lt" to 2,
          "ltz" to 1,
          "na" to 3,
          "nan" to 1,
          "naz" to 2,
          "ne" to 2,
          "nez" to 1,
      )

  private val branches = buildList {
    add(Opcode("j", 1, JumpKind.ABSOLUTE))
    add(Opcode("jal", 1, JumpKind.ABSOLUTE))
    add(Opcode("jr", 1, JumpKind.RELATIVE))
    add(Opcode("bdnvl", 3, JumpKind.ABSOLUTE))
    add(Opcode("bdnvs", 3, JumpKind.ABSOLUTE))
    for ((condition, tested) in conditions) {
      add(Opcode("b$condition", tested + 1, JumpKind.ABSOLUTE))
      add(Opcode("br$condition", tested + 1, JumpKind.RELATIVE))
      if (condition != "nan") add(Opcode("b${condition}al", tested + 1, JumpKind.ABSOLUTE))
    }
  }

  val all: Map<String, Opcode> =
      (fixed.map { (name, arity) -> Opcode(name, arity) } + branches).associateBy { it.name }

  operator fun get(name: String): Opcode? = all[name]
}
