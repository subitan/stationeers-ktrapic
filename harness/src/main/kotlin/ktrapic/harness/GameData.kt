package ktrapic.harness

import com.google.gson.JsonParser

/** A prefab from the SDK's game data: its hash, the logic types it has and its slots. */
data class Prefab(
    val name: String,
    val hash: Int,
    val logicTypes: List<String>,
    val slots: List<Int>,
)

/**
 * The game data a test network is built from: the SDK's `prefabs.json` and the slot logic types of
 * `logic-enums.json`, read from this library's resources.
 */
object GameData {
  val prefabs: Map<String, Prefab> by lazy {
    val root = JsonParser.parseString(resource("prefabs.json")).asJsonObject
    root["prefabs"].asJsonArray.associate { element ->
      val prefab = element.asJsonObject
      val name = prefab["name"].asString
      name to
          Prefab(
              name,
              prefab["hash"].asInt,
              prefab["logic"]?.asJsonObject?.keySet()?.toList().orEmpty(),
              prefab["slots"]?.asJsonObject?.keySet()?.map { it.toInt() }?.sorted().orEmpty(),
          )
    }
  }

  /** Every slot logic type but `None`, as a slot in the game can read them all. */
  val slotLogicTypes: List<String> by lazy {
    val root = JsonParser.parseString(resource("logic-enums.json")).asJsonObject
    root["enums"].asJsonObject["LogicSlotType"].asJsonObject.keySet().filter { it != "None" }
  }

  private fun resource(name: String): String =
      GameData::class.java.getResourceAsStream("/ktrapic/harness/$name")?.use {
        it.readBytes().decodeToString()
      } ?: error("$name is missing from the harness's resources")
}
