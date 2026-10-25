package ktrapic.datagen

/**
 * Reads the game's logic enums from PyTrapIC's generated `types_generated.py`, which declares each
 * as `class Name(_IntEnum):` followed by an `Entry = number` line, indented four spaces, per entry.
 * PyTrapIC appends `_` to entry names that are Python keywords (`None_`); that is dropped.
 */
object PyTrapicImport {
  private val ENUM_START = Regex("""^class (\w+)\(_IntEnum\):\s*$""")
  private val ENTRY = Regex("""^ {4}(\w+) = (-?\d+)\s*$""")

  fun enums(typesGenerated: String, source: String): LogicEnums {
    val enums = linkedMapOf<String, LinkedHashMap<String, Int>>()
    var current: LinkedHashMap<String, Int>? = null
    for ((index, line) in typesGenerated.lines().withIndex()) {
      val start = ENUM_START.matchEntire(line)
      val entry = ENTRY.matchEntire(line)
      when {
        start != null -> {
          val name = start.groupValues[1]
          require(name !in enums) { "line ${index + 1}: enum $name declared twice" }
          current = linkedMapOf<String, Int>().also { enums[name] = it }
        }
        entry != null && current != null -> {
          val name = entry.groupValues[1].removeSuffix("_")
          require(name !in current) { "line ${index + 1}: entry $name declared twice" }
          current[name] = entry.groupValues[2].toInt()
        }
        line.isNotBlank() -> current = null
      }
    }
    require(enums.isNotEmpty()) { "no enums found" }
    return LogicEnums(source, enums)
  }
}
