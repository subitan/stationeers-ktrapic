package ktrapic.datagen

/**
 * Reads the game's prefabs and their logic types from PyTrapIC's generated
 * `structures_generated.py`.
 *
 * There, each prefab is a class with a `_hash` and a `_prefab_name`, whose logic types are
 * properties returning `float` (readable) with an `@Name.setter` (writable), its own or inherited
 * from mixin classes such as `_Charge`. Classes whose names start with `_` are mixins, slot types
 * or batch views; only their properties returning `float` are used, through inheritance.
 *
 * The file is read line by line, not parsed as Python: a class header is an unindented `class
 * Name(` (its bases may continue on later lines up to `):`) or `class Name:`; members are indented
 * four spaces. PyTrapIC writes a write-only logic type as readable too, so this data can't tell the
 * two apart.
 */
object PyTrapicStructures {
  private val CLASS_START = Regex("""^class (\w+)(\(|:)(.*)$""")
  private val HASH = Regex("""^ {4}_hash: int = (-?\d+)\s*$""")
  private val PREFAB_NAME = Regex("""^ {4}_prefab_name: int = "([^"]+)"\s*$""")
  private val GETTER = Regex("""^ {4}def (\w+)\(self\) -> float:\s*$""")
  private val SETTER = Regex("""^ {4}@(\w+)\.setter\s*$""")
  private val SLOT = Regex("""^ {4}def slot(\d+)\(self\) -> (_SlotType\w*):\s*$""")
  private val SLOT_ALIAS = Regex("""^ {4}def (\w+)\(self\) -> (_SlotType\w*):\s*$""")
  private val RETURN_SLOT = Regex("""^ {8}return self\.slot(\d+)\s*$""")
  private const val SLOT_KIND_ROOT = "_SlotTypeCommon"

  private class PyClass(val name: String, val bases: List<String>) {
    var hash: Int? = null
    var prefabName: String? = null
    val readable = linkedSetOf<String>()
    val writable = linkedSetOf<String>()
    /** Slot kind classes by slot index, and slot names by index. */
    val slots = sortedMapOf<Int, String>()
    val slotNames = sortedMapOf<Int, String>()
  }

  fun prefabs(structuresGenerated: String, source: String): Prefabs {
    val classes = linkedMapOf<String, PyClass>()
    var current: PyClass? = null
    var pendingHeader: StringBuilder? = null
    var pendingName = ""
    // A named slot's getter (`def Battery(self) -> _SlotTypeBattery:`) returns `self.slotN`.
    var pendingAlias: String? = null

    // A class declared again replaces the earlier one, as in Python; PyTrapIC declares the
    // _ImportCount mixin twice, and every prefab class comes after both.
    fun startClass(name: String, bases: String) {
      classes.remove(name)
      val list = bases.split(',').map { it.trim() }.filter { it.isNotEmpty() }
      current = PyClass(name, list).also { classes[name] = it }
    }

    for ((index, line) in structuresGenerated.lines().withIndex()) {
      val where = "line ${index + 1}"
      pendingHeader?.let { header ->
        if (line.trim() == "):") {
          startClass(pendingName, header.toString())
          pendingHeader = null
        } else {
          header.append(line).append(',')
        }
        continue
      }
      val start = CLASS_START.matchEntire(line)
      if (start != null) {
        val (name, opener, rest) = start.destructured
        when {
          opener == ":" -> startClass(name, "")
          rest.endsWith("):") -> startClass(name, rest.removeSuffix("):"))
          rest.isBlank() -> {
            pendingName = name
            pendingHeader = StringBuilder()
          }
          else -> throw IllegalArgumentException("$where: unrecognized class header: $line")
        }
        continue
      }
      if (line.isNotEmpty() && !line[0].isWhitespace() && !line.startsWith("#")) {
        current = null
        continue
      }
      val cls = current ?: continue
      HASH.matchEntire(line)?.let { cls.hash = it.groupValues[1].toInt() }
      PREFAB_NAME.matchEntire(line)?.let { cls.prefabName = it.groupValues[1] }
      GETTER.matchEntire(line)?.let { cls.readable += it.groupValues[1] }
      SETTER.matchEntire(line)?.let { cls.writable += it.groupValues[1] }
      pendingAlias?.let { alias ->
        RETURN_SLOT.matchEntire(line)?.let { cls.slotNames[it.groupValues[1].toInt()] = alias }
        pendingAlias = null
      }
      val slot = SLOT.matchEntire(line)
      if (slot != null) {
        cls.slots[slot.groupValues[1].toInt()] = slot.groupValues[2]
      } else {
        SLOT_ALIAS.matchEntire(line)?.let { pendingAlias = it.groupValues[1] }
      }
    }
    require(pendingHeader == null) { "unterminated class header for $pendingName" }

    fun access(name: String, seen: Set<String>): Map<String, Access> {
      val cls = classes[name] ?: return emptyMap()
      require(name !in seen) { "class $name inherits from itself" }
      val result = linkedMapOf<String, Access>()
      for (base in cls.bases) {
        for ((logicType, inherited) in access(base, seen + name)) {
          result[logicType] = result[logicType]?.let { it + inherited } ?: inherited
        }
      }
      for (logicType in cls.readable) {
        val own = Access(read = true, write = logicType in cls.writable)
        result[logicType] = result[logicType]?.let { it + own } ?: own
      }
      return result
    }

    // A slot kind is _SlotTypeCommon or a class built on it; the plural batch views are not.
    fun isSlotKind(name: String): Boolean =
        name == SLOT_KIND_ROOT || classes[name]?.bases?.any { isSlotKind(it) } == true

    val prefabs =
        classes.values
            .filter { !it.name.startsWith("_") && it.hash != null }
            .map { cls ->
              val prefabName = requireNotNull(cls.prefabName) { "${cls.name} has no _prefab_name" }
              // The compiler hashes the prefab's name itself, so the data must agree with it.
              require(cls.hash == gameHash(prefabName)) {
                "${cls.name}: _hash ${cls.hash} is not the hash of $prefabName"
              }
              val slots =
                  cls.slots.mapValues { (index, kindClass) ->
                    require(isSlotKind(kindClass)) {
                      "${cls.name} slot $index: $kindClass is not a slot kind"
                    }
                    SlotInfo(slotKindName(kindClass), cls.slotNames[index])
                  }
              Prefab(prefabName, cls.hash!!, access(cls.name, emptySet()).toSortedMap(), slots)
            }
            .sortedBy { it.name }
    require(prefabs.isNotEmpty()) { "no prefabs found" }
    val duplicates = prefabs.groupBy { it.name }.filterValues { it.size > 1 }.keys
    require(duplicates.isEmpty()) { "prefabs declared twice: $duplicates" }
    // PyTrapIC marks every slot logic type writable, which the game's aren't; only reads are kept.
    val slotKinds =
        classes.keys
            .filter { isSlotKind(it) }
            .associate { slotKindName(it) to access(it, emptySet()).keys.sorted() }
            .toSortedMap()
    return Prefabs(source, prefabs, slotKinds)
  }

  /** `_SlotTypeCommon` is `Common`, `_SlotTypeBattery` is `Battery`, and `_SlotType` `Filtered`. */
  private fun slotKindName(className: String): String =
      when (className) {
        SLOT_KIND_ROOT -> "Common"
        "_SlotType" -> "Filtered"
        else -> className.removePrefix("_SlotType")
      }

  /** The game's hash of a name: the CRC-32 of its UTF-8 bytes, as a signed 32-bit integer. */
  fun gameHash(name: String): Int =
      java.util.zip.CRC32().apply { update(name.toByteArray(Charsets.UTF_8)) }.value.toInt()
}
