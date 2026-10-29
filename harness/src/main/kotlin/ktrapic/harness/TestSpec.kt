package ktrapic.harness

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import ktrapic.ic10.Device
import ktrapic.ic10.Register

/** A test file that can't be run as written, with what is wrong with it. */
class TestSpecException(message: String) : Exception(message)

/**
 * A device on the test network. [id] names it in [Change]s and [Expectation]s. It starts with every
 * logic type its [prefab] has at 0 and each of its slots with every slot logic type at 0, then
 * [logic] and [slots] on top.
 */
data class DeviceSpec(
    val id: String,
    val prefab: Prefab,
    val pin: String? = null,
    val name: String? = null,
    val logic: Map<String, Double> = emptyMap(),
    val slots: Map<Int, Map<String, Double>> = emptyMap(),
)

/**
 * A logic value set from outside the chip before tick [tick] runs: [value] at [tick], then, with a
 * [rampTo], a straight line to its value at its tick, set before each tick in between.
 */
data class Change(
    val tick: Int,
    val device: String,
    val logic: String,
    val value: Double,
    val rampTo: Ramp? = null,
) {
  data class Ramp(val tick: Int, val value: Double)

  /** The value this change sets before [tick] runs, or null if it sets none then. */
  fun valueAt(tick: Int): Double? =
      when {
        tick == this.tick -> value
        rampTo == null || tick < this.tick || tick > rampTo.tick -> null
        else -> value + (rampTo.value - value) * (tick - this.tick) / (rampTo.tick - this.tick)
      }
}

/** What an [Expectation] reads. */
sealed interface Subject {
  data class Logic(val device: String, val logic: String) : Subject

  data class Slot(val device: String, val slot: Int, val logic: String) : Subject

  /**
   * A batch read over the network's devices of [prefab] (and [name], if given), with a [mode] of
   * the game's: Average, Sum, Minimum, Maximum or Count.
   */
  data class Batch(val prefab: Prefab, val name: String?, val logic: String, val mode: String) :
      Subject

  data class Register(val name: String) : Subject
}

/** How an [Expectation] compares, by the key a test file writes it with. */
enum class Comparison(val key: String, val symbol: String, val holds: (Double, Double) -> Boolean) {
  EQUALS("equals", "==", { a, b -> a == b }),
  NOT_EQUALS("notEquals", "!=", { a, b -> a != b }),
  BELOW("below", "<", { a, b -> a < b }),
  AT_MOST("atMost", "<=", { a, b -> a <= b }),
  ABOVE("above", ">", { a, b -> a > b }),
  AT_LEAST("atLeast", ">=", { a, b -> a >= b }),
}

/** A check on [subject] after tick [tick] has run, or after the whole run if [tick] is null. */
data class Expectation(
    val subject: Subject,
    val comparison: Comparison,
    val value: Double,
    val tick: Int? = null,
)

/**
 * A test of a program: the network it runs against, how many [ticks] it runs for (it stops sooner
 * if the chip halts), the [changes] made between ticks and the [expectations] checked.
 */
data class TestSpec(
    val devices: List<DeviceSpec>,
    val ticks: Int = DEFAULT_TICKS,
    val changes: List<Change> = emptyList(),
    val expectations: List<Expectation> = emptyList(),
) {
  companion object {
    const val DEFAULT_TICKS = 20
    const val MAX_TICKS = 10_000
    const val HOUSING = "StructureCircuitHousing"
    val BATCH_MODES = listOf("Average", "Sum", "Minimum", "Maximum", "Count")

    /**
     * Reads a test file's JSON (see `docs/TESTING.md`). A network without a device on `db` gets a
     * circuit housing there, with the id `db`.
     */
    fun parse(json: String): TestSpec {
      val root =
          try {
            JsonParser.parseString(json)
          } catch (e: JsonParseException) {
            throw TestSpecException("not JSON: ${e.message}")
          }
      if (!root.isJsonObject) throw TestSpecException("a test is a JSON object")
      return parse(root.asJsonObject)
    }

    fun parse(root: JsonObject): TestSpec {
      known(root, "test", "devices", "ticks", "changes", "expect")
      val devices = array(root, "devices", "test").mapIndexed { i, it -> device(it, "devices[$i]") }
      val pinned = devices.mapNotNull { it.pin }
      val network =
          if ("db" in pinned) devices
          else devices + DeviceSpec("db", prefab(HOUSING, "db"), pin = "db")
      for ((id, count) in network.groupingBy { it.id }.eachCount()) {
        if (count > 1) {
          throw TestSpecException(
              if (id == "db" && "db" !in pinned) {
                "the id db is the circuit housing's; give the device another id or put it on db"
              } else {
                "two devices have the id $id"
              }
          )
        }
      }
      for ((pin, count) in pinned.groupingBy { it }.eachCount()) {
        if (count > 1) throw TestSpecException("two devices are on $pin")
      }
      val ids = network.map { it.id }.toSet()
      val ticks = root["ticks"]?.let { int(it, "ticks") } ?: DEFAULT_TICKS
      if (ticks !in 1..MAX_TICKS) throw TestSpecException("ticks must be 1 to $MAX_TICKS")
      val changes =
          array(root, "changes", "test").mapIndexed { i, it -> change(it, "changes[$i]", ids) }
      val expectations =
          array(root, "expect", "test").mapIndexed { i, it ->
            expectation(it, "expect[$i]", ids, ticks)
          }
      return TestSpec(network, ticks, changes, expectations)
    }

    private fun device(element: JsonElement, where: String): DeviceSpec {
      val device = obj(element, where)
      known(device, where, "id", "pin", "prefab", "name", "logic", "slots")
      val pin = device["pin"]?.let { string(it, "$where.pin") }
      if (pin != null && pin !in Device.NAMES) {
        throw TestSpecException("$where.pin: $pin is not a pin (d0 to d5, or db)")
      }
      val prefab = prefab(string(required(device, "prefab", where), "$where.prefab"), where)
      val name = device["name"]?.let { string(it, "$where.name") }
      val logic = values(device["logic"], "$where.logic")
      val slots =
          device["slots"]
              ?.let { obj(it, "$where.slots") }
              ?.entrySet()
              .orEmpty()
              .associate { (key, value) ->
                val index =
                    key.toIntOrNull()?.takeIf { it >= 0 }
                        ?: throw TestSpecException("$where.slots: $key is not a slot number")
                index to values(value, "$where.slots.$key")
              }
      val id = device["id"]?.let { string(it, "$where.id") } ?: pin ?: name ?: prefab.name
      return DeviceSpec(id, prefab, pin, name, logic, slots)
    }

    private fun change(element: JsonElement, where: String, ids: Set<String>): Change {
      val change = obj(element, where)
      known(change, where, "tick", "device", "logic", "value", "rampTo")
      val tick = int(required(change, "tick", where), "$where.tick")
      if (tick < 1) throw TestSpecException("$where.tick: ticks count from 1")
      val rampTo =
          change["rampTo"]?.let {
            val ramp = obj(it, "$where.rampTo")
            known(ramp, "$where.rampTo", "tick", "value")
            val to = int(required(ramp, "tick", "$where.rampTo"), "$where.rampTo.tick")
            if (to <= tick) throw TestSpecException("$where.rampTo.tick must be after $where.tick")
            Change.Ramp(to, number(required(ramp, "value", "$where.rampTo"), "$where.rampTo.value"))
          }
      return Change(
          tick,
          deviceId(change, where, ids),
          string(required(change, "logic", where), "$where.logic"),
          number(required(change, "value", where), "$where.value"),
          rampTo,
      )
    }

    private fun expectation(
        element: JsonElement,
        where: String,
        ids: Set<String>,
        ticks: Int,
    ): Expectation {
      val expectation = obj(element, where)
      val comparisons = Comparison.entries.map { it.key }
      known(
          expectation,
          where,
          *(listOf("tick", "device", "slot", "logic", "batch", "register") + comparisons)
              .toTypedArray(),
      )
      val tick = expectation["tick"]?.let { int(it, "$where.tick") }
      if (tick != null && tick !in 1..ticks) {
        throw TestSpecException("$where.tick: the test runs ticks 1 to $ticks")
      }
      val given = Comparison.entries.filter { expectation.has(it.key) }
      val comparison =
          given.singleOrNull()
              ?: throw TestSpecException(
                  "$where needs exactly one of ${comparisons.joinToString(", ")}"
              )
      val subject =
          when {
            expectation.has("register") -> {
              val name = string(expectation["register"], "$where.register")
              if (name !in Register.NAMES) {
                throw TestSpecException("$where.register: $name is not a register")
              }
              Subject.Register(name)
            }
            expectation.has("batch") -> {
              val batch = obj(expectation["batch"], "$where.batch")
              known(batch, "$where.batch", "prefab", "name", "mode")
              val mode = string(required(batch, "mode", "$where.batch"), "$where.batch.mode")
              if (mode !in BATCH_MODES) {
                throw TestSpecException(
                    "$where.batch.mode: $mode is not one of ${BATCH_MODES.joinToString(", ")}"
                )
              }
              Subject.Batch(
                  prefab(
                      string(required(batch, "prefab", "$where.batch"), "$where.batch.prefab"),
                      where,
                  ),
                  batch["name"]?.let { string(it, "$where.batch.name") },
                  string(required(expectation, "logic", where), "$where.logic"),
                  mode,
              )
            }
            expectation.has("slot") ->
                Subject.Slot(
                    deviceId(expectation, where, ids),
                    int(expectation["slot"], "$where.slot"),
                    string(required(expectation, "logic", where), "$where.logic"),
                )
            else ->
                Subject.Logic(
                    deviceId(expectation, where, ids),
                    string(required(expectation, "logic", where), "$where.logic"),
                )
          }
      return Expectation(
          subject,
          comparison,
          number(expectation[comparison.key], "$where.${comparison.key}"),
          tick,
      )
    }

    private fun prefab(name: String, where: String): Prefab =
        GameData.prefabs[name] ?: throw TestSpecException("$where: no prefab is named $name")

    private fun deviceId(element: JsonObject, where: String, ids: Set<String>): String {
      val id = string(required(element, "device", where), "$where.device")
      if (id !in ids) throw TestSpecException("$where.device: no device has the id $id")
      return id
    }

    private fun values(element: JsonElement?, where: String): Map<String, Double> =
        element
            ?.let { obj(it, where) }
            ?.entrySet()
            .orEmpty()
            .associate { (key, value) ->
              key to number(value, "$where.$key")
            }

    private fun known(element: JsonObject, where: String, vararg keys: String) {
      val unknown = element.keySet() - keys.toSet()
      if (unknown.isNotEmpty()) {
        throw TestSpecException("$where: unknown ${unknown.joinToString(", ")}")
      }
    }

    private fun required(element: JsonObject, key: String, where: String): JsonElement =
        element[key]?.takeUnless { it.isJsonNull } ?: throw TestSpecException("$where needs $key")

    private fun obj(element: JsonElement, where: String): JsonObject =
        element.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw TestSpecException("$where must be an object")

    private fun array(root: JsonObject, key: String, where: String): List<JsonElement> =
        root[key]?.let {
          it.takeIf { it.isJsonArray }?.asJsonArray?.toList()
              ?: throw TestSpecException("$where.$key must be a list")
        } ?: emptyList()

    private fun string(element: JsonElement, where: String): String =
        element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?: throw TestSpecException("$where must be a string")

    /** A number, or a boolean as 1 or 0, as the chip reads it. */
    private fun number(element: JsonElement, where: String): Double {
      val primitive =
          element.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
              ?: throw TestSpecException("$where must be a number")
      return when {
        primitive.isBoolean -> if (primitive.asBoolean) 1.0 else 0.0
        primitive.isNumber -> primitive.asDouble
        else -> throw TestSpecException("$where must be a number")
      }
    }

    private fun int(element: JsonElement, where: String): Int {
      val value = number(element, where)
      if (value != Math.rint(value) || value !in -1e9..1e9) {
        throw TestSpecException("$where must be a whole number")
      }
      return value.toInt()
    }
  }
}
