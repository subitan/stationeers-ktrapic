package ktrapic.harness

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import ktrapic.emu.Emulator
import ktrapic.emu.FakeDevice
import ktrapic.emu.Ic10RuntimeException
import ktrapic.ic10.Hashes
import ktrapic.ic10.Program
import ktrapic.ic10.Register

/**
 * What changed in a run from the [Snapshot] before: the chip's next line ([line]) and whether it
 * has halted, then only the registers, stack entries and device values whose values changed.
 *
 * @property tick the tick this follows (0 for the network as it starts).
 * @property ran for a snapshot after one line of a traced tick, the line that ran.
 */
data class Snapshot(
    val tick: Int,
    val line: Int,
    val halted: Boolean,
    val ran: Int? = null,
    val registers: Map<String, Double> = emptyMap(),
    val stack: Map<Int, Double> = emptyMap(),
    val logic: Map<String, Map<String, Double>> = emptyMap(),
    val slots: Map<String, Map<Int, Map<String, Double>>> = emptyMap(),
)

/** A runtime error that stopped the chip at [line] during [tick] (0 before the first). */
data class RunError(val tick: Int, val line: Int, val message: String)

/**
 * An [Expectation] as checked: what it read ([actual], null if it couldn't) and whether it held.
 */
data class Check(
    val expectation: Expectation,
    val tick: Int,
    val actual: Double?,
    val passed: Boolean,
    val description: String,
)

/**
 * A run of a test: [ticks] holds a snapshot of the start and one after each tick that ran, [lines]
 * one after each line of the traced tick, if any, starting from the tick before it.
 */
data class RunResult(
    val ticks: List<Snapshot>,
    val lines: List<Snapshot>,
    val error: RunError?,
    val checks: List<Check>,
) {
  val passed: Boolean
    get() = error == null && checks.all { it.passed }

  /**
   * The JSON the daemon answers a run with. Numbers that JSON can't hold (NaN and the infinities)
   * are the strings `"NaN"`, `"Infinity"` and `"-Infinity"`.
   */
  fun toJson(): JsonObject =
      JsonObject().apply {
        addProperty("passed", passed)
        add("ticks", JsonArray().apply { ticks.forEach { add(it.toJson()) } })
        add("lines", JsonArray().apply { lines.forEach { add(it.toJson()) } })
        add(
            "error",
            error?.let {
              JsonObject().apply {
                addProperty("tick", it.tick)
                addProperty("line", it.line)
                addProperty("message", it.message)
              }
            } ?: JsonNull.INSTANCE,
        )
        add(
            "checks",
            JsonArray().apply {
              for (check in checks) {
                add(
                    JsonObject().apply {
                      addProperty("description", check.description)
                      addProperty("tick", check.tick)
                      add("actual", check.actual?.let(::number) ?: JsonNull.INSTANCE)
                      addProperty("passed", check.passed)
                    }
                )
              }
            },
        )
      }

  private fun Snapshot.toJson() =
      JsonObject().apply {
        addProperty("tick", tick)
        addProperty("line", line)
        addProperty("halted", halted)
        if (ran != null) addProperty("ran", ran)
        add("registers", values(registers))
        add("stack", values(stack.mapKeys { it.key.toString() }))
        add("logic", JsonObject().apply { logic.forEach { (id, v) -> add(id, values(v)) } })
        add(
            "slots",
            JsonObject().apply {
              slots.forEach { (id, bySlot) ->
                add(
                    id,
                    JsonObject().apply {
                      bySlot.forEach { (slot, v) -> add(slot.toString(), values(v)) }
                    },
                )
              }
            },
        )
      }

  private fun values(values: Map<String, Double>) =
      JsonObject().apply { values.forEach { (key, value) -> add(key, number(value)) } }

  private fun number(value: Double): JsonElement =
      if (value.isFinite()) JsonPrimitive(value) else JsonPrimitive(value.toString())
}

/** Runs programs against the network of a [TestSpec] on the [Emulator]. */
object Harness {
  /**
   * Runs [program] for [TestSpec.ticks] ticks, or until it halts or fails, applying the test's
   * changes before each tick and checking its expectations. With [traceTick], also snapshots each
   * line that tick runs.
   */
  fun run(program: Program, spec: TestSpec, traceTick: Int? = null): RunResult {
    val devices = spec.devices.associate { it.id to fake(it) }
    val pins = spec.devices.filter { it.pin != null }.associate { it.pin!! to devices[it.id]!! }
    val recorder = Recorder(devices)
    val ticks = mutableListOf<Snapshot>()
    val lines = mutableListOf<Snapshot>()
    val checks = mutableListOf<Check>()
    val emulator =
        try {
          Emulator(program, pins, devices.values.toList())
        } catch (e: Ic10RuntimeException) {
          return RunResult(emptyList(), emptyList(), RunError(0, e.line, message(e)), emptyList())
        }
    ticks += recorder.snapshot(emulator, 0)
    var error: RunError? = null
    for (tick in 1..spec.ticks) {
      if (emulator.halted) break
      for (change in spec.changes) {
        change.valueAt(tick)?.let { devices.getValue(change.device)[change.logic] = it }
      }
      if (tick == traceTick) {
        val traced = Recorder(devices).also { it.copyFrom(recorder) }
        emulator.onLine = { ran -> lines += traced.snapshot(emulator, tick, ran) }
      }
      try {
        emulator.tick()
      } catch (e: Ic10RuntimeException) {
        error = RunError(tick, e.line, message(e))
      } finally {
        emulator.onLine = {}
      }
      ticks += recorder.snapshot(emulator, tick)
      for (expectation in spec.expectations) {
        if (expectation.tick == tick) checks += check(expectation, tick, emulator, devices)
      }
      if (error != null) break
    }
    val last = emulator.ticks
    for (expectation in spec.expectations) {
      val tick = expectation.tick
      when {
        tick == null -> checks += check(expectation, last, emulator, devices)
        tick > last ->
            checks +=
                Check(
                    expectation,
                    tick,
                    null,
                    false,
                    "${describe(expectation)}: the run stopped at tick $last",
                )
      }
    }
    return RunResult(ticks, lines, error, checks)
  }

  private fun fake(spec: DeviceSpec): FakeDevice {
    val device = FakeDevice(spec.prefab.hash, spec.name?.let(Hashes::hash) ?: 0)
    for (type in spec.prefab.logicTypes) device[type] = 0.0
    for ((type, value) in spec.logic) device[type] = value
    for (slot in spec.prefab.slots + spec.slots.keys) {
      val values = device.slot(slot)
      for (type in GameData.slotLogicTypes) values.putIfAbsent(type, 0.0)
      spec.slots[slot]?.let(values::putAll)
    }
    return device
  }

  private fun message(e: Ic10RuntimeException) =
      e.message.orEmpty().removePrefix("line ${e.line}: ")

  private fun check(
      expectation: Expectation,
      tick: Int,
      emulator: Emulator,
      devices: Map<String, FakeDevice>,
  ): Check {
    val read = runCatching { read(expectation.subject, emulator, devices) }
    val actual = read.getOrNull()
    val passed = actual != null && expectation.comparison.holds(actual, expectation.value)
    val outcome = read.exceptionOrNull()?.message ?: "was ${format(actual!!)}"
    val at = expectation.tick?.let { "after tick $it" } ?: "after the run"
    return Check(expectation, tick, actual, passed, "${describe(expectation)} $at: $outcome")
  }

  private fun read(
      subject: Subject,
      emulator: Emulator,
      devices: Map<String, FakeDevice>,
  ): Double =
      when (subject) {
        is Subject.Register -> emulator.register(subject.name)
        is Subject.Logic -> logic(devices.getValue(subject.device), subject.logic)
        is Subject.Slot ->
            devices.getValue(subject.device).slots[subject.slot]?.get(subject.logic)
                ?: error("slot ${subject.slot} has no ${subject.logic}")
        is Subject.Batch -> {
          val nameHash = subject.name?.let(Hashes::hash)
          val matching =
              devices.values.filter {
                it.prefabHash == subject.prefab.hash &&
                    (nameHash == null || it.nameHash == nameHash)
              }
          val values = matching.map { logic(it, subject.logic) }
          when (subject.mode) {
            "Average" -> values.sum() / values.size
            "Sum" -> values.sum()
            "Minimum" -> values.minOrNull() ?: 0.0
            "Maximum" -> values.maxOrNull() ?: Double.NEGATIVE_INFINITY
            else -> values.size.toDouble()
          }
        }
      }

  private fun logic(device: FakeDevice, type: String): Double =
      device[type]
          ?: when (type) {
            "PrefabHash" -> device.prefabHash.toDouble()
            "NameHash" -> device.nameHash.toDouble()
            else -> error("has no $type")
          }

  /** How an expectation reads, as `heater.On == 1`. */
  fun describe(expectation: Expectation): String {
    val subject =
        when (val s = expectation.subject) {
          is Subject.Logic -> "${s.device}.${s.logic}"
          is Subject.Slot -> "${s.device} slot ${s.slot} ${s.logic}"
          is Subject.Batch ->
              "${s.mode} of ${s.prefab.name}${s.name?.let { " named $it" }.orEmpty()} ${s.logic}"
          is Subject.Register -> s.name
        }
    return "$subject ${expectation.comparison.symbol} ${format(expectation.value)}"
  }

  private fun format(value: Double): String =
      if (value == Math.rint(value) && value.isFinite() && kotlin.math.abs(value) < 1e15) {
        value.toLong().toString()
      } else {
        value.toString()
      }

  /** Takes snapshots, each holding what changed since the one before. */
  private class Recorder(private val devices: Map<String, FakeDevice>) {
    private var registers = mapOf<String, Double>()
    private var stack = mapOf<Int, Double>()
    private var logic = mapOf<String, Map<String, Double>>()
    private var slots = mapOf<String, Map<Int, Map<String, Double>>>()

    fun copyFrom(other: Recorder) {
      registers = other.registers
      stack = other.stack
      logic = other.logic
      slots = other.slots
    }

    fun snapshot(emulator: Emulator, tick: Int, ran: Int? = null): Snapshot {
      val registers = Register.NAMES.associateWith { emulator.register(it) }
      val stack =
          emulator.stack.withIndex().filter { it.value != 0.0 }.associate { it.index to it.value }
      val logic = devices.mapValues { (_, device) -> device.logic.toMap() }
      val slots = devices.mapValues { (_, device) ->
        device.slots.mapValues { (_, values) -> values.toMap() }
      }
      val snapshot =
          Snapshot(
              tick,
              emulator.pc,
              emulator.halted,
              ran,
              changed(this.registers, registers),
              changed(this.stack, stack, removed = 0.0),
              logic
                  .mapValues { (id, values) -> changed(this.logic[id].orEmpty(), values) }
                  .filterValues { it.isNotEmpty() },
              slots
                  .mapValues { (id, bySlot) ->
                    bySlot
                        .mapValues { (slot, values) ->
                          changed(this.slots[id]?.get(slot).orEmpty(), values)
                        }
                        .filterValues { it.isNotEmpty() }
                  }
                  .filterValues { it.isNotEmpty() },
          )
      this.registers = registers
      this.stack = stack
      this.logic = logic
      this.slots = slots
      return snapshot
    }

    /**
     * The entries of [now] that differ from [before], plus [removed] for keys only [before] has.
     * NaN equals NaN here, so a NaN that stays put isn't reported again.
     */
    private fun <K> changed(
        before: Map<K, Double>,
        now: Map<K, Double>,
        removed: Double? = null,
    ): Map<K, Double> = buildMap {
      for ((key, value) in now) {
        val old = before[key]
        if (old == null || old.compareTo(value) != 0) put(key, value)
      }
      if (removed != null) for (key in before.keys - now.keys) put(key, removed)
    }
  }
}
