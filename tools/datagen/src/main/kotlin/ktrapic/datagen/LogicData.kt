package ktrapic.datagen

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * The game's logic enums, by enum name then entry name, in the game's order. [source] says where
 * they came from.
 */
data class LogicEnums(val source: String, val enums: Map<String, Map<String, Int>>) {
  fun toJson(): String {
    val root = JsonObject()
    root.addProperty("source", source)
    val enumsJson = JsonObject()
    for ((name, entries) in enums) {
      enumsJson.add(name, JsonObject().apply { entries.forEach { (k, v) -> addProperty(k, v) } })
    }
    root.add("enums", enumsJson)
    return GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n"
  }

  companion object {
    fun fromJson(text: String): LogicEnums {
      val root = JsonParser.parseString(text).asJsonObject
      return LogicEnums(
          root["source"].asString,
          root["enums"].asJsonObject.entrySet().associate { (name, entries) ->
            name to entries.asJsonObject.entrySet().associate { (k, v) -> k to v.asInt }
          },
      )
    }
  }
}

/**
 * How the SDK types the game's logic, which is KTrapIC's choice rather than game data.
 *
 * @property operandEnums enums whose entries name an instruction operand (`l r0 d0 Setting`), which
 *   become Kotlin enum classes. Every other enum holds values a program reads or writes, and
 *   becomes an object of `Double` constants.
 * @property booleanLogicTypes logic types declared `Boolean` on devices; the rest are `Double`.
 * @property booleanSlotLogicTypes slot logic types declared `Boolean` on slots.
 * @property slotReadsAlways the slot logic types the game lets a program read on any slot of a
 *   structure, added to each slot kind's reads from the data. (On an item that runs a program, such
 *   as a suit, the game reads only some of them; the typed API doesn't tell the two apart.)
 * @property slotWrites the slot logic types the game lets a program write, by slot kind; `any` is
 *   every one some slot can write, which an untyped slot allows. The game's rule, not PyTrapIC's
 *   data, which marks every slot logic type writable. The game decides by the slot's class, whose
 *   names the kinds here share; a kind with no prefab yet is listed so the table is the game's
 *   whole rule. The generator checks that every kind is a `SlotClass` and that `any` is exactly the
 *   kinds' writes together. `Mode` on `Circuitboard` is a console's rule rather than the slot's: a
 *   console can write `Mode` on any of its slots, and consoles' slots are the only `Circuitboard`
 *   slots.
 * @property consoles the prefabs that are the game's consoles with slots. The generator checks that
 *   they are exactly the prefabs with `Circuitboard` slots, and have no other kind of slot, so the
 *   `Mode` that [slotWrites] gives `Circuitboard` is on a console's slots and only there.
 * @property thingSlotReadsAlways the slot logic types the game lets a program read on any slot of a
 *   thing that isn't a structure (an item, a robot, a rover): fewer than a structure's. A thing's
 *   slots can't be written at all.
 * @property thingSlotReadsByKind the further slot logic types a thing's slot of each kind can read.
 *   Each kind must be one of the prefab data's slot kinds, which the generator checks.
 * @property slottedThings the prefabs with slots that are things rather than structures. Prefabs
 *   named `Structure...` are structures; every other prefab with slots must be listed here or in
 *   [slottedStructures], which the generator checks, since a name doesn't always say which it is.
 * @property slottedStructures the prefabs with slots that are structures without being named so.
 */
data class Typing(
    val operandEnums: List<String>,
    val booleanLogicTypes: List<String>,
    val booleanSlotLogicTypes: List<String> = emptyList(),
    val slotReadsAlways: List<String> = emptyList(),
    val slotWrites: Map<String, List<String>> = emptyMap(),
    val consoles: List<String> = emptyList(),
    val thingSlotReadsAlways: List<String> = emptyList(),
    val thingSlotReadsByKind: Map<String, List<String>> = emptyMap(),
    val slottedThings: List<String> = emptyList(),
    val slottedStructures: List<String> = emptyList(),
) {
  companion object {
    fun fromJson(text: String): Typing {
      val root = JsonParser.parseString(text).asJsonObject
      return Typing(
          root["operandEnums"].asJsonArray.map { it.asString },
          root["booleanLogicTypes"].asJsonArray.map { it.asString },
          root["booleanSlotLogicTypes"]?.asJsonArray?.map { it.asString } ?: emptyList(),
          root["slotReadsAlways"]?.asJsonArray?.map { it.asString } ?: emptyList(),
          root["slotWrites"]?.asJsonObject?.entrySet()?.associate { (kind, types) ->
            kind to types.asJsonArray.map { it.asString }
          } ?: emptyMap(),
          root["consoles"]?.asJsonArray?.map { it.asString } ?: emptyList(),
          root["thingSlotReadsAlways"]?.asJsonArray?.map { it.asString } ?: emptyList(),
          root["thingSlotReadsByKind"]?.asJsonObject?.entrySet()?.associate { (kind, types) ->
            kind to types.asJsonArray.map { it.asString }
          } ?: emptyMap(),
          root["slottedThings"]?.asJsonArray?.map { it.asString } ?: emptyList(),
          root["slottedStructures"]?.asJsonArray?.map { it.asString } ?: emptyList(),
      )
    }
  }
}

/** Whether a prefab's logic type can be read and written. */
data class Access(val read: Boolean, val write: Boolean) {
  operator fun plus(other: Access) = Access(read || other.read, write || other.write)

  override fun toString() = (if (read) "r" else "") + (if (write) "w" else "")

  companion object {
    fun parse(text: String): Access {
      require(text in setOf("r", "w", "rw")) { "bad access $text" }
      return Access('r' in text, 'w' in text)
    }
  }
}

/** A slot: its kind (a key of [Prefabs.slotKinds]) and the game's name for it, if it has one. */
data class SlotInfo(val kind: String, val name: String? = null) {
  override fun toString() = if (name == null) kind else "$kind:$name"

  companion object {
    fun parse(text: String) = text.split(':', limit = 2).let { SlotInfo(it[0], it.getOrNull(1)) }
  }
}

/**
 * A prefab: its game name, the game's hash of that name, its logic types and its slots by number.
 */
data class Prefab(
    val name: String,
    val hash: Int,
    val logic: Map<String, Access>,
    val slots: Map<Int, SlotInfo> = emptyMap(),
)

/**
 * The game's prefabs that have logic types, by name, and the slot logic types each kind of slot can
 * read ([slotKinds]). [source] says where they came from.
 */
data class Prefabs(
    val source: String,
    val prefabs: List<Prefab>,
    val slotKinds: Map<String, List<String>> = emptyMap(),
) {
  fun toJson(): String {
    val root = JsonObject()
    root.addProperty("source", source)
    val list = com.google.gson.JsonArray()
    for (prefab in prefabs) {
      list.add(
          JsonObject().apply {
            addProperty("name", prefab.name)
            addProperty("hash", prefab.hash)
            add(
                "logic",
                JsonObject().apply {
                  prefab.logic.forEach { (k, v) -> addProperty(k, v.toString()) }
                },
            )
            if (prefab.slots.isNotEmpty()) {
              add(
                  "slots",
                  JsonObject().apply {
                    prefab.slots.forEach { (index, slot) -> addProperty("$index", slot.toString()) }
                  },
              )
            }
          }
      )
    }
    root.add("prefabs", list)
    root.add(
        "slotKinds",
        JsonObject().apply {
          slotKinds.forEach { (kind, types) ->
            add(kind, com.google.gson.JsonArray().apply { types.forEach { add(it) } })
          }
        },
    )
    return GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n"
  }

  companion object {
    fun fromJson(text: String): Prefabs {
      val root = JsonParser.parseString(text).asJsonObject
      return Prefabs(
          root["source"].asString,
          root["prefabs"].asJsonArray.map { element ->
            val prefab = element.asJsonObject
            Prefab(
                prefab["name"].asString,
                prefab["hash"].asInt,
                prefab["logic"].asJsonObject.entrySet().associate { (k, v) ->
                  k to Access.parse(v.asString)
                },
                prefab["slots"]?.asJsonObject?.entrySet()?.associate { (index, slot) ->
                  index.toInt() to SlotInfo.parse(slot.asString)
                } ?: emptyMap(),
            )
          },
          root["slotKinds"]?.asJsonObject?.entrySet()?.associate { (kind, types) ->
            kind to types.asJsonArray.map { it.asString }
          } ?: emptyMap(),
      )
    }
  }
}

/**
 * KTrapIC's hand-written grouping of prefabs, which is a choice rather than game data.
 *
 * @property families prefabs grouped by the one kind of job they do, by the singular name of their
 *   interface: the plural name of their entry point, and each member's name and prefab. A prefab in
 *   a family is reachable only through it.
 * @property textDisplays prefabs whose `Setting` shows text (packed by `STR`) in their `String`
 *   mode, which get a `Text` setter.
 * @property notFamilies prefabs that look like one device in several kinds but aren't, each group
 *   with the reason: checked by datagen's test of family candidates, so a new look-alike gets a
 *   decision. In `grouping.json` an entry's `prefabs` holds a prefab's name, or an array of the
 *   prefabs of one family.
 */
data class Grouping(
    val families: Map<String, Family>,
    val textDisplays: List<String>,
    val notFamilies: List<NotFamily> = emptyList(),
) {
  /**
   * @property extends the families this one is a special case of: its interface extends theirs, so
   *   a function taking one of them takes a member of this one too. Every logic type they declare
   *   must be one all of this family's members can read, and write where they declare it a `var`.
   */
  data class Family(
      val plural: String,
      val members: Map<String, String>,
      val extends: List<String> = emptyList(),
  )

  /**
   * Prefabs that look alike but are different devices, and why.
   *
   * @property groups the different devices: each group is one prefab, or the prefabs of one family.
   *   Only prefabs in different groups are decided as not one family; prefabs in one group must be
   *   one family, and one family's members must be in one group.
   */
  data class NotFamily(val groups: List<List<String>>, val reason: String) {
    val prefabs: List<String>
      get() = groups.flatten()
  }

  companion object {
    fun fromJson(text: String): Grouping {
      val root = JsonParser.parseString(text).asJsonObject
      return Grouping(
          root["families"].asJsonObject.entrySet().associate { (name, family) ->
            val obj = family.asJsonObject
            name to
                Family(
                    obj["plural"].asString,
                    obj["members"].asJsonObject.entrySet().associate { (k, v) -> k to v.asString },
                    obj["extends"]?.asJsonArray?.map { it.asString } ?: emptyList(),
                )
          },
          root["textDisplays"].asJsonArray.map { it.asString },
          root["notFamilies"]?.asJsonArray?.map { element ->
            val obj = element.asJsonObject
            NotFamily(
                obj["prefabs"].asJsonArray.map { group ->
                  if (group.isJsonArray) group.asJsonArray.map { it.asString }
                  else listOf(group.asString)
                },
                obj["reason"].asString,
            )
          } ?: emptyList(),
      )
    }
  }
}
