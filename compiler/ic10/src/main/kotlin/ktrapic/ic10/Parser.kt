package ktrapic.ic10

/** IC10 text that doesn't parse, at a 1-based [line]. */
class Ic10ParseException(val line: Int, message: String) : Exception("line $line: $message")

/** Parses IC10 text into a [Program]. */
internal object Parser {
  // The game reads decimals without an exponent: `1e5` is not a number.
  private val decimal = Regex("""[-+]?(\d+\.?\d*|\.\d+)""")
  private val hex = Regex("""\$[0-9A-Fa-f_]+""")
  private val binary = Regex("""%[01_]+""")
  private val stringConstant = Regex("""(HASH|STR)\("([^"]*)"\)""")
  /**
   * Constants parsed straight to a [Number], because [Number] prints them back as the same word.
   * The other built-in constants stay [Name]s so that printed text keeps them.
   */
  private val numericConstants = setOf("nan", "pinf", "ninf")

  fun parse(text: String): Program {
    val rawLines = text.replace("\r", "").split('\n')
    val tokenized = rawLines.mapIndexed { index, raw -> tokenize(index + 1, raw) }
    val declared =
        tokenized
            .mapNotNull { (tokens, _) ->
              val index = tokens.firstOrNull()?.let { Opcodes[it]?.declaredIndex }
              index?.let { tokens.getOrNull(it + 1) }
            }
            .toSet()
    val defined = mutableSetOf<String>()
    tokenized.forEachIndexed { index, (tokens, _) ->
      val name = tokens.getOrNull(1)?.takeIf { tokens[0] == "define" } ?: return@forEachIndexed
      if (!defined.add(name)) throw Ic10ParseException(index + 1, "$name is defined again")
    }
    val labels = mutableSetOf<String>()
    tokenized.forEachIndexed { index, (tokens, _) ->
      val name = tokens.firstOrNull()?.removeSuffixOrNull(":") ?: return@forEachIndexed
      val clash = labelClash(name, declared)
      if (clash != null) throw Ic10ParseException(index + 1, "label $name is $clash")
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
      if (!identifier.matches(name)) throw Ic10ParseException(lineNumber, "bad label name: $name")
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
    val parsed = operands.mapIndexed { index, token ->
      operand(lineNumber, token, if (index in opcode.nameIndices) emptySet() else labels)
    }
    opcode.declaredIndex?.let { index ->
      if (parsed[index] !is Name) {
        throw Ic10ParseException(lineNumber, "$first needs a name, not ${operands[index]}")
      }
    }
    if (first == "define" && parsed[1] is LabelRef) {
      throw Ic10ParseException(lineNumber, "define needs a constant, not label ${operands[1]}")
    }
    if (opcode.jump == JumpKind.RELATIVE && parsed[opcode.targetIndex!!] is LabelRef) {
      throw Ic10ParseException(lineNumber, "$first jumps by an offset, not to a label")
    }
    return Line.Instruction(first, parsed, comment)
  }

  private fun operand(lineNumber: Int, token: String, labels: Set<String>): Operand {
    stringConstant.matchEntire(token)?.let { match ->
      return try {
        StringConstant(StringConstant.Kind.valueOf(match.groupValues[1]), match.groupValues[2])
      } catch (e: IllegalArgumentException) {
        throw Ic10ParseException(lineNumber, e.message ?: "bad string constant: $token")
      }
    }
    return when {
      token in Register.NAMES -> Register(token)
      token in Device.NAMES -> Device(token)
      token in numericConstants -> Number(Constants.VALUES.getValue(token))
      decimal.matches(token) -> Number(token.toDouble())
      hex.matches(token) -> integer(lineNumber, token, 16)
      binary.matches(token) -> integer(lineNumber, token, 2)
      token in labels -> LabelRef(token)
      identifier.matches(token) -> Name(token)
      else -> throw Ic10ParseException(lineNumber, "bad operand: $token")
    }
  }

  /**
   * A `$` hex or `%` binary literal of up to 64 bits, read as two's complement; `_` separates
   * digits.
   */
  private fun integer(lineNumber: Int, token: String, radix: Int): Number {
    val value =
        token.drop(1).replace("_", "").toULongOrNull(radix)?.toLong()
            ?: throw Ic10ParseException(lineNumber, "bad number: $token")
    return Number(value.toDouble())
  }

  /**
   * Splits a line into whitespace-separated tokens and a comment, keeping quoted text whole. As in
   * the game, the first `#` starts the comment even inside quotes.
   */
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
        c == '#' -> {
          if (quoted) throw Ic10ParseException(lineNumber, "unterminated string")
          if (current.isNotEmpty()) tokens += current.toString()
          return tokens to raw.substring(index + 1).trim()
        }
        quoted -> current.append(c)
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
