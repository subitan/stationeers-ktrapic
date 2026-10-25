package ktrapic.ic10

/**
 * One line of an IC10 program. The chip numbers every line of its text, labels, comments and blank
 * lines included, so a program is kept as lines: jump targets are line numbers.
 */
sealed interface Line {
  val comment: String?

  data class Label(val name: String, override val comment: String? = null) : Line

  data class Instruction(
      val op: String,
      val operands: List<Operand> = emptyList(),
      override val comment: String? = null,
  ) : Line {
    constructor(op: String, vararg operands: Operand) : this(op, operands.toList())
  }

  /** A comment-only line, or a blank line when [comment] is null. */
  data class Comment(override val comment: String?) : Line
}

/** An IC10 program: its lines, in order. */
data class Program(val lines: List<Line>) {
  constructor(vararg lines: Line) : this(lines.toList())

  /** Line number of each label, as the chip resolves it. */
  val labels: Map<String, Int> by lazy {
    buildMap {
      lines.forEachIndexed { index, line -> if (line is Line.Label) put(line.name, index) }
    }
  }

  init {
    val names = lines.filterIsInstance<Line.Label>().map { it.name }
    val duplicates = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    require(duplicates.isEmpty()) { "labels defined more than once: $duplicates" }
    val declared =
        lines
            .filterIsInstance<Line.Instruction>()
            .filter { it.op == "define" || it.op == "alias" || it.op == "label" }
            .mapNotNullTo(mutableSetOf()) { (it.operands.firstOrNull() as? Name)?.name }
    // The game refuses to run a program that defines a name twice.
    val defined =
        lines
            .filterIsInstance<Line.Instruction>()
            .filter { it.op == "define" }
            .mapNotNull {
              (it.operands.firstOrNull() as? Name)?.name
            }
    val redefined = defined.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    require(redefined.isEmpty()) { "names defined more than once: $redefined" }
    for (name in names) {
      require(identifier.matches(name)) { "bad label name: $name" }
      val clash = labelClash(name, declared)
      require(clash == null) { "label $name is $clash" }
    }
    val undefined =
        lines
            .filterIsInstance<Line.Instruction>()
            .flatMap { it.operands }
            .filterIsInstance<LabelRef>()
            .map { it.name }
            .filterNot { it in names }
            .toSet()
    require(undefined.isEmpty()) { "references to undefined labels: $undefined" }
    for (line in lines.filterIsInstance<Line.Instruction>()) {
      val opcode = Opcodes[line.op] ?: continue
      for (index in opcode.nameIndices) {
        require(line.operands.getOrNull(index) !is LabelRef) {
          "${line.op} takes a name, not a label, as operand ${index + 1}"
        }
      }
      if (line.op == "define") {
        require(line.operands.getOrNull(1) !is LabelRef) { "define takes a constant, not a label" }
      }
      if (opcode.jump == JumpKind.RELATIVE) {
        require(line.operands.getOrNull(opcode.targetIndex!!) !is LabelRef) {
          "${line.op} jumps by an offset, not to a label"
        }
      }
    }
  }

  /** The program as IC10 text: labels and comments kept, no trailing newline. */
  fun print(): String = lines.joinToString("\n") { printLine(it) }

  /**
   * The same program with only its instructions: labels, comments and blank lines dropped, label
   * references replaced by line numbers, and literal jump targets renumbered to match. A jump to a
   * dropped line goes to the next instruction after it.
   *
   * Jump targets computed at run time (`j r0`, a `define`d number) aren't renumbered, so they break
   * here; the compiler only jumps through labels, `ra` and literal offsets. That includes
   * arithmetic on a label: a jump table read as `add r0 table r1` / `j r0` counts its entries from
   * the label's own line, which compaction drops, so every index lands one entry later. A literal
   * jump target that isn't a whole number is left as it is, so it may truncate to a different line.
   */
  fun compacted(): Program {
    // newIndex[i]: the compacted line that old line i falls to. newIndex[lines.size] is the end.
    val newIndex = IntArray(lines.size + 1)
    var kept = 0
    for (i in lines.indices) {
      newIndex[i] = kept
      if (lines[i] is Line.Instruction) kept++
    }
    newIndex[lines.size] = kept
    fun inRange(target: Double) =
        target == Math.rint(target) && target in 0.0..lines.size.toDouble()

    return Program(
        lines.withIndex().mapNotNull { (i, line) ->
          if (line !is Line.Instruction) return@mapNotNull null
          val opcode = Opcodes[line.op]
          val target = opcode?.targetIndex
          val operands =
              line.operands.mapIndexed { index, operand ->
                when {
                  operand is LabelRef -> Number(newIndex[labels.getValue(operand.name)].toDouble())
                  index != target || operand !is Number -> operand
                  opcode.jump == JumpKind.ABSOLUTE && inRange(operand.value) ->
                      Number(newIndex[operand.value.toInt()].toDouble())
                  opcode.jump == JumpKind.RELATIVE && inRange(i + operand.value) ->
                      Number((newIndex[i + operand.value.toInt()] - newIndex[i]).toDouble())
                  else -> operand
                }
              }
          Line.Instruction(line.op, operands)
        }
    )
  }

  companion object {
    /** Parses IC10 text. */
    fun parse(text: String): Program = Parser.parse(text)

    private fun printLine(line: Line): String {
      val code =
          when (line) {
            is Line.Label -> "${line.name}:"
            is Line.Instruction ->
                (listOf(line.op) + line.operands.map { it.text() }).joinToString(" ")
            is Line.Comment -> ""
          }
      val comment = line.comment?.let { if (it.isEmpty()) "#" else "# $it" } ?: return code
      return if (code.isEmpty()) comment else "$code $comment"
    }
  }
}

/** A name as IC10 text spells it: a label, a `define` or `alias` name, or a [Name] operand. */
internal val identifier = Regex("""[A-Za-z_][A-Za-z0-9_]*""")

/**
 * Why [name] can't be a label in a program that declares [declared] with `define` and `alias`, or
 * null if it can. A label sharing a name with a register, device, constant, define or alias could
 * never be told apart from it where the name is used.
 */
internal fun labelClash(name: String, declared: Set<String>): String? =
    when (name) {
      in Register.NAMES -> "a register"
      in Device.NAMES -> "a device"
      in Constants.VALUES -> "a constant"
      in declared -> "also a define or alias"
      else -> null
    }
