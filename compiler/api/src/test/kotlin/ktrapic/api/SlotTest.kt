package ktrapic.api

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import ktrapic.emu.Emulator
import ktrapic.emu.FakeDevice
import ktrapic.ic10.Hashes
import ktrapic.ic10.Program
import org.junit.jupiter.api.Test

/** Slots on pins, typed devices and batches. */
class SlotTest {
  private val charger = "StructureBatteryCharger"
  private val tankStorage = "StructureGasTankStorage"

  private fun compile(source: String, options: CompileOptions = CompileOptions()) =
      Compiler.compile(source.trimIndent(), options)

  private fun compiled(source: String, options: CompileOptions = CompileOptions()) =
      when (val result = compile(source, options)) {
        is CompileResult.Success -> result
        is CompileResult.Failure -> throw AssertionError("compile failed: ${result.errors}")
      }

  private fun error(source: String): CompileError =
      (compile(source) as? CompileResult.Failure)?.errors?.first()
          ?: throw AssertionError("expected an error")

  /**
   * Compiles [source] in normal and compact form and runs each, as emitted text parsed back, on the
   * network [network] makes (`db` its first device, `d0` its second); returns the normal run's
   * devices, after checking the compact run left its devices' logic and slots the same.
   */
  private fun run(source: String, network: () -> List<FakeDevice>): List<FakeDevice> {
    val runs =
        listOf(false, true).map { compact ->
          val devices = network()
          val result = compiled(source, CompileOptions(compact = compact))
          val pins = mapOf("db" to devices[0], "d0" to devices[1])
          val emulator = Emulator(Program.parse(result.code), pins, devices).runUntilHalt()
          assertWithMessage("halted (compact=$compact)\n${result.code}")
              .that(emulator.halted)
              .isTrue()
          devices
        }
    for ((normal, compact) in runs[0].zip(runs[1])) {
      assertThat(compact.logic).isEqualTo(normal.logic)
      assertThat(compact.slots).isEqualTo(normal.slots)
    }
    return runs[0]
  }

  private fun chargerWith(vararg charges: Double, name: String? = null) =
      FakeDevice(charger, name).apply {
        charges.forEachIndexed { index, charge ->
          slot(index)["Charge"] = charge
          slot(index)["Quantity"] = 1.0
        }
      }

  @Test
  fun `a pin's slots load and store by number`() {
    val source =
        """
        db.Setting = d0.slots[1].Quantity
        d0.slots[0].Open = true
        """
    val code = compiled(source).code

    assertThat(code).contains("ls r0 d0 1 Quantity")
    assertThat(code).contains("ss d0 0 Open 1")
    val devices =
        run(source) {
          listOf(FakeDevice(), FakeDevice().apply { slot(1)["Quantity"] = 7.0 })
        }
    assertThat(devices[0]["Setting"]).isEqualTo(7.0)
    assertThat(devices[1].slot(0)["Open"]).isEqualTo(1.0)
  }

  @Test
  fun `a slot's number may be computed`() {
    val source =
        """
        var total = 0.0
        for (i in 0..<3) total += BatteryChargers.at(d0).slots[i].Charge
        db.Setting = total
        """
    val db = run(source) { listOf(FakeDevice(), chargerWith(1.0, 2.0, 4.0)) }[0]

    assertThat(db["Setting"]).isEqualTo(7.0)
  }

  @Test
  fun `a named slot is its number`() {
    val source =
        """
        val tanks = GasTankStorages.at(d0)
        db.Setting = tanks.GasCanister.Pressure
        tanks.GasCanister.Open = true
        """
    val code = compiled(source).code

    assertThat(code).contains("ls r0 d0 0 Pressure")
    assertThat(code).contains("ss d0 0 Open 1")
    val devices =
        run(source) {
          listOf(FakeDevice(), FakeDevice(tankStorage).apply { slot(0)["Pressure"] = 101.0 })
        }
    assertThat(devices[0]["Setting"]).isEqualTo(101.0)
    assertThat(devices[1].slot(0)["Open"]).isEqualTo(1.0)
  }

  @Test
  fun `batch slot reads go through a batch method`() {
    val source =
        """
        db.Setting = BatteryChargers.all.average.slots[0].Charge
        db.Ratio = BatteryChargers.named("North").maximum.slots[1].Charge
        """
    val code = compiled(source).code

    assertThat(code).contains("lbs r0 ${Hashes.hash(charger)} 0 Charge Average")
    assertThat(code)
        .contains("lbns r0 ${Hashes.hash(charger)} ${Hashes.hash("North")} 1 Charge Maximum")
    val db =
        run(source) {
          listOf(
              FakeDevice(),
              chargerWith(10.0, 1.0, name = "North"),
              chargerWith(30.0, 5.0, name = "South"),
          )
        }[0]
    assertThat(db["Setting"]).isEqualTo(20.0)
    assertThat(db["Ratio"]).isEqualTo(1.0)
  }

  @Test
  fun `batch slot writes go to every device of the prefab`() {
    val source = "GasTankStorages.all.slots[0].Open = true"

    assertThat(compiled(source).code).isEqualTo("sbs ${Hashes.hash(tankStorage)} 0 Open 1")
    val devices =
        run(source) { listOf(FakeDevice(), FakeDevice(tankStorage), FakeDevice(tankStorage)) }
    assertThat(devices.drop(1).map { it.slot(0)["Open"] }).containsExactly(1.0, 1.0)
  }

  @Test
  fun `slots and slots of batches can be held in vals`() {
    val source =
        """
        val battery = BatteryChargers.at(d0).slots[1]
        val average = BatteryChargers.all.average.slots
        db.Setting = battery.Charge + average[0].Charge
        """
    val db = run(source) { listOf(FakeDevice(), chargerWith(3.0, 5.0)) }[0]

    assertThat(db["Setting"]).isEqualTo(8.0)
  }

  @Test
  fun `a computed slot number is fixed when the slot is taken`() {
    val source =
        """
        var i = 0
        val slot = BatteryChargers.at(d0).slots[i]
        i = 1
        db.Setting = slot.Charge
        """
    val db = run(source) { listOf(FakeDevice(), chargerWith(3.0, 5.0)) }[0]

    assertThat(db["Setting"]).isEqualTo(3.0)
  }

  @Test
  fun `writes the game doesn't allow are compile errors`() {
    // A battery slot can't write Charge; a named batch has no slot writes; a slot has no value.
    for ((source, at) in
        listOf(
            "val x = 1\nBatteryChargers.at(d0).slots[0].Charge = 1.0" to (2 to 33),
            "val x = 1\nGasTankStorages.named(\"A\").slots[0].Open = true" to (2 to 37),
            "val x = 1\nval s = if (db.On) d0.slots[0] else d0.slots[1]" to (2 to 20),
            "val x = 1\nval c = BatteryChargers.all.slots[0].Charge" to (2 to 38),
        )) {
      val error = error(source)
      assertWithMessage(source).that(error.line to error.column).isEqualTo(at)
    }
  }
}
