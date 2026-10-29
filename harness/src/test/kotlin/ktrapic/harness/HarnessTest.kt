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

    assertThat(result.checks.single().description).isEqualTo("r0 == 1: the run stopped at tick 1")
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
                     "logic": "Charge", "atLeast": 2}
                  ]
                }
                """
            ),
        )

    assertThat(result.checks.filter { !it.passed }.map { it.description }).isEmpty()
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
