package ktrapic.ic10

/** IC10 text that doesn't parse, at a 1-based [line]. */
class Ic10ParseException(val line: Int, message: String) : Exception("line $line: $message")

/** Parses IC10 text into a [Program]. */
internal object Parser {
  private val decimal = Regex("""[-+]?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?""")
  private val hex = Regex("""\$[0-9A-Fa-f_]+""")
  private val binary = Regex("""%[01_]+""")
  private val stringConstant = Regex("""(HASH|STR)\("([^"]*)"\)""")
  private val label = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
  private val constants =
      mapOf(
          "nan" to Double.NaN,
          "pinf" to Double.POSITIVE_INFINITY,
          "ninf" to Double.NEGATIVE_INFINITY,
      )

  fun parse(text: String): Program {
    val rawLines = text.replace("\r", "").split('\n')
    val tokenized = rawLines.mapIndexed { index, raw -> tokenize(index + 1, raw) }
    val labels = mutableSetOf<String>()
    tokenized.forEachIndexed { index, (tokens, _) ->
      val name = tokens.firstOrNull()?.removeSuffixOrNull(":") ?: return@forEachIndexed
      if (!labels.add(name)) throw Ic10ParseException(index + 1, "label $name defined again")
    }
    return Program(
        tokenized.mapIndexed { index, (tokens, comment) ->
          parseLine(index + 1, tokens, comment, labels)
        }
    )
  }

  private fun parseLine(
      lineNumber: Int,
      tokens: List<String>,
      comment: String?,
      labels: Set<String>,
  ): Line {
    if (tokens.isEmpty()) return Line.Comment(comment)
    val first = tokens.first()
    first.removeSuffixOrNull(":")?.let { name ->
      if (tokens.size > 1) throw Ic10ParseException(lineNumber, "text after label $first")
      if (!label.matches(name)) throw Ic10ParseException(lineNumber, "bad label name: $name")
      return Line.Label(name, comment)
    }
    val opcode =
        Opcodes[first] ?: throw Ic10ParseException(lineNumber, "unknown instruction: $first")
    val operands = tokens.drop(1)
    if (operands.size != opcode.arity) {
      throw Ic10ParseException(
          lineNumber,
          "$first takes ${opcode.arity} operands, not ${operands.size}",
      )
    }
    val parsed = operands.map { operand(lineNumber, it, labels) }
    if (opcode.jump == JumpKind.RELATIVE && parsed[opcode.targetIndex!!] is LabelRef) {
      throw Ic10ParseException(lineNumber, "$first jumps by an offset, not to a label")
    }
    return Line.Instruction(first, parsed, comment)
  }

  private fun operand(lineNumber: Int, token: String, labels: Set<String>): Operand {
    stringConstant.matchEntire(token)?.let { match ->
      return StringConstant(StringConstant.Kind.valueOf(match.groupValues[1]), match.groupValues[2])
    }
    return when {
      token in Register.NAMES -> Register(token)
      token in Device.NAMES -> Device(token)
      token in constants -> Number(constants.getValue(token))
      decimal.matches(token) -> Number(token.toDouble())
      hex.matches(token) -> Number(token.drop(1).replace("_", "").toLong(16).toDouble())
      binary.matches(token) -> Number(token.drop(1).replace("_", "").toLong(2).toDouble())
      token in labels -> LabelRef(token)
      label.matches(token) -> Name(token)
      else -> throw Ic10ParseException(lineNumber, "bad operand: $token")
    }
  }

  /** Splits a line into whitespace-separated tokens and a comment, keeping quoted text whole. */
  private fun tokenize(lineNumber: Int, raw: String): Pair<List<String>, String?> {
    val tokens = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    for ((index, c) in raw.withIndex()) {
      when {
        c == '"' -> {
          quoted = !quoted
          current.append(c)
        }
        quoted -> current.append(c)
        c == '#' -> {
          if (current.isNotEmpty()) tokens += current.toString()
          return tokens to raw.substring(index + 1).trim()
        }
        c.isWhitespace() -> {
          if (current.isNotEmpty()) tokens += current.toString()
          current.clear()
        }
        else -> current.append(c)
      }
    }
    if (quoted) throw Ic10ParseException(lineNumber, "unterminated string")
    if (current.isNotEmpty()) tokens += current.toString()
    return tokens to null
  }

  private fun String.removeSuffixOrNull(suffix: String) =
      if (endsWith(suffix)) dropLast(suffix.length) else null
}
