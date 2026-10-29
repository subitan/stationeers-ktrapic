package ktrapic.harness

import com.google.common.truth.Truth.assertThat
import ktrapic.ic10.Program
import org.junit.jupiter.api.Test

class HarnessTest {
  /** A thermostat on IC10: heater on d1 below 290 K on d0, cooler on d2 above 300 K. */
  private val thermostat =
      Program.parse(
          """
          |start:
          |l r0 d0 Temperature
          |slt r1 r0 290
          |sgt r2 r0 300
          |s d1 On r1
          |s d2 On r2
          |yield
          |j start
          """
              .trimMargin()
      )

  private fun spec(json: String) = TestSpec.parse(json)

  private val network =
      """
      "devices": [
        {"id": "sensor", "pin": "d0", "prefab": "StructureGasSensor", "logic": {"Temperature": 295}},
        {"id": "heater", "pin": "d1", "prefab": "StructureWallHeater"},
        {"id": "cooler", "pin": "d2", "prefab": "StructureWallCooler"}
      ]
      """

  @Test
  fun `expectations pass and fail on the network after the run`() {
    val result =
        Harness.run(
            thermostat,
            spec(
                """
                {
                  $network,
                  "ticks": 3,
                  "expect": [
                    {"device": "heater", "logic": "On", "equals": 0},
                    {"device": "cooler", "logic": "On", "equals": 1}
                  ]
                }
                """
            ),
        )

    assertThat(result.error).isNull()
    assertThat(result.checks.map { it.passed }).containsExactly(true, false).inOrder()
    assertThat(result.checks[1].description).isEqualTo("cooler.On == 1 after the run: was 0")
    assertThat(result.passed).isFalse()
  }

  @Test
  fun `changes and ramps set values before their ticks`() {
    val result =
        Harness.run(
            thermostat,
            spec(
                """
                {
                  $network,
                  "ticks": 6,
                  "changes": [
                    {"tick": 2, "device": "sensor", "logic": "Temperature", "value": 280},
                    {"tick": 4, "device": "sensor", "logic": "Temperature", "value": 300,
                     "rampTo": {"tick": 6, "value": 310}}
                  ],
                  "expect": [
                    {"tick": 1, "device": "heater", "logic": "On", "equals": 0},
                    {"tick": 2, "device": "heater", "logic": "On", "equals": 1},
                    {"tick": 4, "device": "cooler", "logic": "On", "equals": 0},
                    {"tick": 5, "device": "sensor", "logic": "Temperature", "equals": 305},
                    {"tick": 5, "device": "cooler", "logic": "On", "equals": 1}
                  ]
                }
                """
            ),
        )

    assertThat(result.checks.filter { !it.passed }.map { it.description }).isEmpty()
    assertThat(result.passed).isTrue()
  }

  @Test
  fun `snapshots hold only what changed`() {
    val result = Harness.run(thermostat, spec("{$network, \"ticks\": 2}"))

    assertThat(result.ticks.map { it.tick }).containsExactly(0, 1, 2).inOrder()
    assertThat(result.ticks[0].logic["sensor"]).containsEntry("Temperature", 295.0)
    assertThat(result.ticks[1].registers).containsExactly("r0", 295.0)
    assertThat(result.ticks[1].line).isEqualTo(7)
    // The second tick writes the same values, so nothing changes.
    assertThat(result.ticks[2].registers).isEmpty()
    assertThat(result.ticks[2].logic).isEmpty()
  }

  @Test
  fun `a traced tick has a snapshot per line`() {
    val result = Harness.run(thermostat, spec("{$network, \"ticks\": 3}"), traceTick = 2)

    // `j start` ends tick 1; tick 2 runs `start:` to `yield`.
    assertThat(result.lines.map { it.ran }).containsExactly(7, 0, 1, 2, 3, 4, 5, 6).inOrder()
    assertThat(result.lines.all { it.tick == 2 }).isTrue()
    assertThat(result.lines[2].registers).isEmpty()
  }

  @Test
  fun `a runtime error stops the run`() {
    val result =
        Harness.run(
            Program.parse("move r0 1\nyield\nl r1 d3 On"),
            spec("""{"expect": [{"register": "r0", "equals": 1}]}"""),
        )

    assertThat(result.error).isEqualTo(RunError(2, 2, "l: no device on d3"))
    assertThat(result.checks.single().passed).isTrue()
    assertThat(result.passed).isFalse()
  }

  @Test
  fun `an expectation after the chip halted fails`() {
    val result =
        Harness.run(
            Program.parse("move r0 1"),
            spec("""{"ticks": 5, "expect": [{"tick": 3, "register": "r0", "equals": 1}]}"""),
        )

    assertThat(result.checks.single().description)
        .isEqualTo("r0 == 1 after tick 3: the run stopped at tick 1")
  }

  @Test
  fun `the circuit housing is on db unless the test puts a device there`() {
    val result =
        Harness.run(
            Program.parse("s db Setting 4"),
            spec("""{"expect": [{"device": "db", "logic": "Setting", "equals": 4}]}"""),
        )

    assertThat(result.passed).isTrue()
  }

  @Test
  fun `batch expectations read over the network`() {
    val result =
        Harness.run(
            Program.parse("yield"),
            spec(
                """
                {
                  "devices": [
                    {"prefab": "StructureBatterySmall", "name": "A", "logic": {"Charge": 10}},
                    {"prefab": "StructureBatterySmall", "name": "B", "logic": {"Charge": 30}},
                    {"prefab": "StructureBatteryLarge", "id": "big", "logic": {"Charge": 500}}
                  ],
                  "expect": [
                    {"batch": {"prefab": "StructureBatterySmall", "mode": "Sum"},
                     "logic": "Charge", "equals": 40},
                    {"batch": {"prefab": "StructureBatterySmall", "name": "B", "mode": "Average"},
                     "logic": "Charge", "equals": 30},
                    {"batch": {"prefab": "StructureBatterySmall", "mode": "Count"},
                     "logic": "Charge", "atLeast": 2},
                    {"batch": {"prefab": "StructureBatterySmall", "name": "B", "mode": "Count"},
                     "logic": "Charge", "equals": 2}
                  ]
                }
                """
            ),
        )

    assertThat(result.checks.filter { !it.passed }.map { it.description }).isEmpty()
  }

  @Test
  fun `batch expectations read NaN and infinities as lb reads them`() {
    // d1's Lock goes to NaN, then, with d0's, to positive infinity; lb reads each into registers.
    val result =
        Harness.run(
            Program.parse(
                """
                |define HEATER HASH("StructureWallHeater")
                |move r0 0
                |div r0 r0 0
                |s d1 Lock r0
                |lb r1 HEATER Lock Minimum
                |lb r2 HEATER Lock Maximum
                |yield
                |move r0 1
                |div r0 r0 0
                |s d0 Lock r0
                |s d1 Lock r0
                |lb r3 HEATER Lock Minimum
                |lb r4 HEATER Lock Maximum
                |yield
                """
                    .trimMargin()
            ),
            spec(
                """
                {
                  "devices": [
                    {"pin": "d0", "prefab": "StructureWallHeater", "logic": {"Lock": 5}},
                    {"pin": "d1", "prefab": "StructureWallHeater"}
                  ],
                  "ticks": 2,
                  "expect": [
                    {"tick": 1, "batch": {"prefab": "StructureWallHeater", "mode": "Minimum"},
                     "logic": "Lock", "equals": 5},
                    {"tick": 1, "batch": {"prefab": "StructureWallHeater", "mode": "Maximum"},
                     "logic": "Lock", "equals": 5},
                    {"tick": 1, "register": "r1", "equals": 5},
                    {"tick": 1, "register": "r2", "equals": 5},
                    {"tick": 2, "batch": {"prefab": "StructureWallHeater", "mode": "Minimum"},
                     "logic": "Lock", "equals": 0},
                    {"tick": 2, "batch": {"prefab": "StructureWallHeater", "mode": "Maximum"},
                     "logic": "Lock", "above": 1e308},
                    {"tick": 2, "register": "r3", "equals": 0},
                    {"tick": 2, "register": "r4", "above": 1e308}
                  ]
                }
                """
            ),
        )

    assertThat(result.error).isNull()
    assertThat(result.checks.filter { !it.passed }.map { it.description }).isEmpty()
  }

  /** A program's whole state, as snapshots add up to. */
  private data class State(
      val line: Int,
      val halted: Boolean,
      val registers: Map<String, Double>,
      val stack: Map<Int, Double>,
      val logic: Map<String, Map<String, Double>>,
      val slots: Map<String, Map<Int, Map<String, Double>>>,
  )

  private fun fold(snapshots: List<Snapshot>): State {
    val registers = mutableMapOf<String, Double>()
    val stack = mutableMapOf<Int, Double>()
    val logic = mutableMapOf<String, MutableMap<String, Double>>()
    val slots = mutableMapOf<String, MutableMap<Int, MutableMap<String, Double>>>()
    for (snapshot in snapshots) {
      registers += snapshot.registers
      stack += snapshot.stack
      snapshot.logic.forEach { (id, values) -> logic.getOrPut(id) { mutableMapOf() } += values }
      snapshot.slots.forEach { (id, bySlot) ->
        bySlot.forEach { (slot, values) ->
          slots.getOrPut(id) { mutableMapOf() }.getOrPut(slot) { mutableMapOf() } += values
        }
      }
    }
    val last = snapshots.last()
    return State(last.line, last.halted, registers, stack.filterValues { it != 0.0 }, logic, slots)
  }

  /**
   * Checks the tick before [tick] plus the traced lines give the same state as [tick]'s snapshot.
   */
  private fun assertRebuilds(result: RunResult, tick: Int) {
    assertThat(fold(result.ticks.take(tick) + result.lines))
        .isEqualTo(fold(result.ticks.take(tick + 1)))
  }

  @Test
  fun `the tick before plus a traced tick's lines rebuild that tick`() {
    val traced = Harness.run(thermostat, spec("{$network, \"ticks\": 3}"), traceTick = 2)
    assertRebuilds(traced, 2)

    val failing =
        Harness.run(
            Program.parse("move r0 1\nyield\nmove r1 2\npush 7\nl r2 d3 On"),
            spec("""{"ticks": 3}"""),
            traceTick = 2,
        )
    assertThat(failing.error).isEqualTo(RunError(2, 4, "l: no device on d3"))
    assertThat(failing.lines.map { it.ran to it.halted })
        .containsExactly(2 to false, 3 to false, 4 to true)
        .inOrder()
    assertRebuilds(failing, 2)

    val ending =
        Harness.run(Program.parse("move r0 1\nyield\nmove r1 2"), spec("{}"), traceTick = 2)
    assertThat(ending.lines.map { it.ran to it.halted })
        .containsExactly(2 to false, null to true)
        .inOrder()
    assertRebuilds(ending, 2)

    val sleeping =
        Harness.run(
            Program.parse("sleep 10"),
            spec(
                """
                {"devices": [{"id": "sensor", "prefab": "StructureGasSensor"}], "ticks": 3,
                 "changes": [{"tick": 2, "device": "sensor", "logic": "Temperature", "value": 300}]}
                """
            ),
            traceTick = 2,
        )
    assertThat(sleeping.lines.single().ran).isNull()
    assertRebuilds(sleeping, 2)
  }

  @Test
  fun `prefab slots start with every slot logic type at 0`() {
    val result =
        Harness.run(
            Program.parse("ls r0 d0 0 Occupied\nls r1 d0 0 Quantity"),
            spec(
                """
                {
                  "devices": [{"pin": "d0", "prefab": "StructureWallHeater",
                               "slots": {"0": {"Quantity": 3}}}],
                  "expect": [
                    {"register": "r0", "equals": 0},
                    {"register": "r1", "equals": 3},
                    {"device": "d0", "slot": 0, "logic": "Quantity", "equals": 3}
                  ]
                }
                """
            ),
        )

    assertThat(result.checks.filter { !it.passed }.map { it.description }).isEmpty()
  }

  @Test
  fun `JSON holds NaN and the infinities as strings`() {
    val json = Harness.run(Program.parse("div r0 1 0\ndiv r1 0 0"), spec("{}")).toJson()

    val registers = json["ticks"].asJsonArray[1].asJsonObject["registers"].asJsonObject
    assertThat(registers["r0"].asString).isEqualTo("Infinity")
    assertThat(registers["r1"].asString).isEqualTo("NaN")
    assertThat(json["passed"].asBoolean).isTrue()
  }
}
