package ktrapic.datagen

/**
 * Reads the game's logic enums from PyTrapIC's generated `types_generated.py`, which declares each
 * as `class Name(_IntEnum):` followed by an `Entry = number` line, indented four spaces, per entry.
 * A trailing `_` is dropped from every entry name; PyTrapIC appends one to entry names that are
 * Python keywords (`None_`).
 *
 * The file is read line by line, not parsed as Python. An enum ends at the next unindented line
 * other than a comment, which Python allows at column 0 inside a class body; other unindented code
 * is skipped. Any other unindented `class Name(` header whose bases on that line mention `IntEnum`,
 * and anything else in an enum's header or body, is an error rather than skipped. Class headers of
 * other shapes (indented, a space before `(`, or bases on later lines) are not recognized and are
 * skipped with the code around them; PyTrapIC's generator emits none.
 */
object PyTrapicImport {
  private val ENUM_START = Regex("""^class (\w+)\(_IntEnum\):\s*$""")
  private val ENUM_START_PREFIX = Regex("""^class \w+\(.*IntEnum""")
  private val ENTRY = Regex("""^ {4}(\w+) = (-?\d+)\s*$""")

  fun enums(typesGenerated: String, source: String): LogicEnums {
    val enums = linkedMapOf<String, LinkedHashMap<String, Int>>()
    var current: LinkedHashMap<String, Int>? = null
    for ((index, line) in typesGenerated.lines().withIndex()) {
      val where = "line ${index + 1}"
      val start = ENUM_START.matchEntire(line)
      val entry = ENTRY.matchEntire(line)
      when {
        start != null -> {
          val name = start.groupValues[1]
          require(name !in enums) { "$where: enum $name declared twice" }
          current = linkedMapOf<String, Int>().also { enums[name] = it }
        }
        ENUM_START_PREFIX.containsMatchIn(line) ->
            throw IllegalArgumentException("$where: unrecognized enum header: $line")
        entry != null && current != null -> {
          val name = entry.groupValues[1].removeSuffix("_")
          require(name !in current) { "$where: entry $name declared twice" }
          current[name] = entry.groupValues[2].toInt()
        }
        line.isBlank() || line.startsWith("#") -> {}
        line[0].isWhitespace() ->
            require(current == null) { "$where: unrecognized line in an enum: $line" }
        else -> current = null
      }
    }
    require(enums.isNotEmpty()) { "no enums found" }
    val empty = enums.filterValues { it.isEmpty() }.keys
    require(empty.isEmpty()) { "enums with no entries: $empty" }
    return LogicEnums(source, enums)
  }
}
