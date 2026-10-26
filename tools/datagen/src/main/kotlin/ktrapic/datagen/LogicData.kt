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
 * How the runtime API types the game's logic, which is KTrapIC's choice rather than game data.
 *
 * @property operandEnums enums whose entries name an instruction operand (`l r0 d0 Setting`), which
 *   become Kotlin enum classes. Every other enum holds values a program reads or writes, and
 *   becomes an object of `Double` constants.
 * @property booleanLogicTypes logic types declared `Boolean` on devices; the rest are `Double`.
 */
data class Typing(val operandEnums: List<String>, val booleanLogicTypes: List<String>) {
  companion object {
    fun fromJson(text: String): Typing {
      val root = JsonParser.parseString(text).asJsonObject
      return Typing(
          root["operandEnums"].asJsonArray.map { it.asString },
          root["booleanLogicTypes"].asJsonArray.map { it.asString },
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

/** A prefab: its game name, the game's hash of that name, and its logic types. */
data class Prefab(val name: String, val hash: Int, val logic: Map<String, Access>)

/** The game's prefabs that have logic types, by name. [source] says where they came from. */
data class Prefabs(val source: String, val prefabs: List<Prefab>) {
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
          }
      )
    }
    root.add("prefabs", list)
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
            )
          },
      )
    }
  }
}

/**
 * KTrapIC's hand-written grouping of prefabs, which is a choice rather than game data.
 *
 * @property families device kinds that come in several prefabs, by the singular name of their
 *   interface: the plural name of their entry point, and each member's name and prefab. A prefab in
 *   a family is reachable only through it.
 * @property textDisplays prefabs whose `Setting` shows text (packed by `STR`) in their `String`
 *   mode, which get a `Text` setter.
 */
data class Grouping(val families: Map<String, Family>, val textDisplays: List<String>) {
  data class Family(val plural: String, val members: Map<String, String>)

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
                )
          },
          root["textDisplays"].asJsonArray.map { it.asString },
      )
    }
  }
}
