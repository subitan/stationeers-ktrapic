package ktrapic.harness

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TestSpecTest {
  private fun problem(json: String): String? =
      assertThrows<TestSpecException> { TestSpec.parse(json) }.message

  @Test
  fun `devices are named by id, then pin, then name, then prefab`() {
    val spec =
        TestSpec.parse(
            """
            {"devices": [
              {"id": "sensor", "pin": "d0", "prefab": "StructureGasSensor"},
              {"pin": "d1", "prefab": "StructureWallHeater", "name": "Heater"},
              {"prefab": "StructureBatterySmall", "name": "Backup"},
              {"prefab": "StructureBatteryLarge"}
            ]}
            """
        )

    assertThat(spec.devices.map { it.id })
        .containsExactly("sensor", "d1", "Backup", "StructureBatteryLarge", "db")
        .inOrder()
    assertThat(spec.devices.last().prefab.name).isEqualTo(TestSpec.HOUSING)
    assertThat(spec.ticks).isEqualTo(TestSpec.DEFAULT_TICKS)
  }

  @Test
  fun `a device on db replaces the housing`() {
    val spec = TestSpec.parse("""{"devices": [{"pin": "db", "prefab": "StructureConsole"}]}""")

    assertThat(spec.devices.single().prefab.name).isEqualTo("StructureConsole")
  }

  @Test
  fun `booleans are 1 and 0`() {
    val spec =
        TestSpec.parse(
            """
            {"devices": [{"pin": "d0", "prefab": "StructureWallHeater", "logic": {"On": true}}],
             "expect": [{"device": "d0", "logic": "On", "equals": false}]}
            """
        )

    assertThat(spec.devices[0].logic).containsExactly("On", 1.0)
    assertThat(spec.expectations.single().value).isEqualTo(0.0)
  }

  @Test
  fun `a ramp is a straight line between its ticks`() {
    val change = Change(2, "d0", "Pressure", 100.0, Change.Ramp(6, 300.0))

    assertThat((1..7).map { change.valueAt(it) })
        .containsExactly(null, 100.0, 150.0, 200.0, 250.0, 300.0, null)
        .inOrder()
  }

  @Test
  fun `mistakes are reported with where they are`() {
    assertThat(problem("[]")).isEqualTo("a test is a JSON object")
    assertThat(problem("""{"device": []}""")).isEqualTo("test: unknown device")
    assertThat(problem("""{"devices": [{"prefab": "Nope"}]}"""))
        .isEqualTo("devices[0].prefab: no prefab is named Nope")
    assertThat(problem("""{"devices": [{"pin": "d6", "prefab": "StructureWallHeater"}]}"""))
        .isEqualTo("devices[0].pin: d6 is not a pin (d0 to d5, or db)")
    assertThat(
            problem(
                """{"devices": [{"pin": "d0", "prefab": "StructureWallHeater"},
                               {"pin": "d0", "id": "x", "prefab": "StructureWallHeater"}]}"""
            )
        )
        .isEqualTo("two devices are on d0")
    assertThat(problem("""{"devices": [{"id": "db", "prefab": "StructureWallHeater"}]}"""))
        .isEqualTo("the id db is the circuit housing's; give the device another id or put it on db")
    assertThat(problem("""{"ticks": 0}""")).isEqualTo("ticks must be 1 to 10000")
    assertThat(problem("""{"changes": [{"tick": 1, "device": "x", "logic": "On", "value": 1}]}"""))
        .isEqualTo("changes[0].device: no device has the id x")
    assertThat(
            problem(
                """{"changes": [{"tick": 3, "device": "db", "logic": "On", "value": 1,
                                 "rampTo": {"tick": 3, "value": 0}}]}"""
            )
        )
        .isEqualTo("changes[0].rampTo.tick must be after changes[0].tick")
    assertThat(problem("""{"expect": [{"register": "r0"}]}"""))
        .isEqualTo(
            "expect[0] needs exactly one of equals, notEquals, below, atMost, above, atLeast"
        )
    assertThat(problem("""{"expect": [{"register": "r0", "equals": 1, "tick": 21}]}"""))
        .isEqualTo("expect[0].tick: the test runs ticks 1 to 20")
    assertThat(
            problem(
                """{"expect": [{"batch": {"prefab": "StructureWallHeater", "mode": "Median"},
                                "logic": "On", "equals": 1}]}"""
            )
        )
        .isEqualTo(
            "expect[0].batch.mode: Median is not one of Average, Sum, Minimum, Maximum, Count"
        )
    assertThat(problem("""{"ticks": 1.5}""")).isEqualTo("ticks must be a whole number")
    assertThat(problem("""{"ticks": 1e10}""")).isEqualTo("ticks must be 1 to 10000")
    assertThat(
            problem(
                """{"expect": [{"batch": {"prefab": "Nope", "mode": "Sum"},
                                "logic": "On", "equals": 1}]}"""
            )
        )
        .isEqualTo("expect[0].batch.prefab: no prefab is named Nope")
  }

  @Test
  fun `an expectation names exactly one subject`() {
    val heater = """"devices": [{"id": "heater", "prefab": "StructureWallHeater"}]"""
    val oneOf = "expect[0] needs exactly one of register, batch, device"

    assertThat(problem("""{"expect": [{"logic": "On", "equals": 1}]}""")).isEqualTo(oneOf)
    assertThat(
            problem(
                """{$heater, "expect": [{"register": "r0", "device": "heater", "logic": "On",
                                          "equals": 1}]}"""
            )
        )
        .isEqualTo(oneOf)
    assertThat(
            problem(
                """{$heater, "expect": [{"batch": {"prefab": "StructureWallHeater", "mode": "Sum"},
                                          "device": "heater", "logic": "On", "equals": 1}]}"""
            )
        )
        .isEqualTo(oneOf)
    assertThat(problem("""{"expect": [{"register": "r0", "slot": 0, "equals": 1}]}"""))
        .isEqualTo("expect[0].slot: only a device has slots")
    assertThat(problem("""{"expect": [{"register": "r0", "logic": "On", "equals": 1}]}"""))
        .isEqualTo("expect[0].logic: a register has no logic type")
    assertThat(
            problem(
                """{$heater, "expect": [{"device": "heater", "slot": -1, "logic": "Quantity",
                                          "equals": 0}]}"""
            )
        )
        .isEqualTo("expect[0].slot: -1 is not a slot number")
  }

  @Test
  fun `a change must fall within the run and name a logic type the device has`() {
    fun changes(change: String) =
        """
        {"devices": [{"id": "s", "prefab": "StructureGasSensor", "logic": {"Extra": 1}}],
         "ticks": 5, "changes": [$change]}
        """

    assertThat(
            problem(changes("""{"tick": 6, "device": "s", "logic": "Temperature", "value": 1}"""))
        )
        .isEqualTo("changes[0].tick: the test runs ticks 1 to 5")
    assertThat(
            problem(changes("""{"tick": 0, "device": "s", "logic": "Temperature", "value": 1}"""))
        )
        .isEqualTo("changes[0].tick: the test runs ticks 1 to 5")
    assertThat(
            problem(
                changes(
                    """{"tick": 2, "device": "s", "logic": "Temperature", "value": 1,
                        "rampTo": {"tick": 9, "value": 2}}"""
                )
            )
        )
        .isEqualTo("changes[0].rampTo.tick: the test runs ticks 1 to 5")
    assertThat(
            problem(changes("""{"tick": 1, "device": "s", "logic": "Temprature", "value": 1}"""))
        )
        .isEqualTo("changes[0].logic: s (StructureGasSensor) has no Temprature")

    val spec =
        TestSpec.parse(
            changes(
                """{"tick": 1, "device": "s", "logic": "Temperature", "value": 1},
                   {"tick": 5, "device": "s", "logic": "Extra", "value": 2}"""
            )
        )
    assertThat(spec.changes.map { it.logic }).containsExactly("Temperature", "Extra").inOrder()
  }
}
