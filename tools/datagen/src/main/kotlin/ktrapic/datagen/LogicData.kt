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
