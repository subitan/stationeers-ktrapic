package ktrapic.api

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import ktrapic.emu.Emulator
import ktrapic.emu.FakeDevice
import ktrapic.ic10.Hashes
import ktrapic.ic10.Program
import org.junit.jupiter.api.Test

/** Batches read with collection operations and written with `forEach`. */
class BatchTest {
  private val growLight = "StructureGrowLight"
  private val smallBattery = "StructureBatterySmall"
  private val largeBattery = "StructureBatteryLarge"
  private val charger = "StructureBatteryCharger"
  private val tankStorage = "StructureGasTankStorage"

  private fun compile(source: String) = Compiler.compile(source.trimIndent(), CompileOptions())

  private fun compiled(source: String, options: CompileOptions = CompileOptions()) =
      when (val result = Compiler.compile(source.trimIndent(), options)) {
        is CompileResult.Success -> result
        is CompileResult.Failure -> throw AssertionError("compile failed: ${result.errors}")
      }

  private fun error(source: String): CompileError =
      (compile(source) as? CompileResult.Failure)?.errors?.first()
          ?: throw AssertionError("expected an error")

  /**
   * Compiles [source] in normal and compact form and runs each, as emitted text parsed back, on the
   * network [network] makes (`db` its first device, `d0` its second); returns the normal run's
   * devices, after checking the compact run left its devices the same.
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

  private fun lights() =
      listOf(
          FakeDevice(),
          FakeDevice(growLight, "Potatoes").apply { this["On"] = 0.0 },
          FakeDevice(growLight, "Potatoes").apply { this["On"] = 1.0 },
          FakeDevice(growLight, "Tomatoes").apply { this["On"] = 1.0 },
          FakeDevice(smallBattery).apply { this["On"] = 0.0 },
      )

  private fun batteries() =
      listOf(
          FakeDevice(),
          FakeDevice(smallBattery, "A").apply { this["Charge"] = 10.0 },
          FakeDevice(smallBattery, "B").apply { this["Charge"] = 30.0 },
          FakeDevice(largeBattery, "A").apply { this["Charge"] = 1000.0 },
      )

  private fun chargerWith(vararg charges: Double, name: String? = null) =
      FakeDevice(charger, name).apply {
        charges.forEachIndexed { index, charge -> slot(index)["Charge"] = charge }
      }

  @Test
  fun `forEach writes every device of the prefab, or of the name`() {
    val source =
        """
        GrowLights.all.forEach { it.On = true }
        GrowLights.named("Tomatoes").forEach { light -> light.On = false }
        """
    val code = compiled(source).code

    assertThat(code)
        .isEqualTo(
            "sb ${Hashes.hash(growLight)} On 1\nsbn ${Hashes.hash(growLight)} ${Hashes.hash("Tomatoes")} On 0"
        )
    val devices = run(source, ::lights)
    assertThat(devices.drop(1).map { it["On"] }).containsExactly(1.0, 1.0, 0.0, 0.0).inOrder()
  }

  @Test
  fun `forEach writes several logic types, values computed once, slots and Text`() {
    val source =
        """
        val level = db.Setting * 2.0
        GrowLights.all.forEach {
          it.On = level > 1.0
          it.Lock = true
        }
        GasTankStorages.all.forEach { it.GasCanister.Open = true }
        GasTankStorages.all.forEach { it.slots[0].Open = false }
        LedDisplays.Large.all.forEach { it.Text = "OK" }
        """
    val code = compiled(source).code

    assertThat(code).contains("sb ${Hashes.hash(growLight)} Lock 1")
    assertThat(code).contains("sbs ${Hashes.hash(tankStorage)} 0 Open 1")
    assertThat(code).contains("sbs ${Hashes.hash(tankStorage)} 0 Open 0")
    assertThat(code).contains("sb ${Hashes.hash("StructureConsoleLED1x3")} Setting STR(\"OK\")")
  }

  @Test
  fun `map and a terminal read the batch with one batch method`() {
    val source =
        """
        db.Setting = Batteries.Small.all.map { it.Charge }.average()
        db.Ratio = Batteries.Small.all.map(Battery.Small::Charge).sum()
        db.Maximum = Batteries.Small.named("B").map { it.Charge }.max()
        db.Mode = Batteries.Large.all.map { it.Charge }.min()
        """
    val code = compiled(source).code

    assertThat(code).contains("lb r0 ${Hashes.hash(smallBattery)} Charge Average")
    assertThat(code).contains("lb r0 ${Hashes.hash(smallBattery)} Charge Sum")
    assertThat(code)
        .contains("lbn r0 ${Hashes.hash(smallBattery)} ${Hashes.hash("B")} Charge Maximum")
    assertThat(code).contains("lb r0 ${Hashes.hash(largeBattery)} Charge Minimum")
    val db = run(source, ::batteries)[0]
    assertThat(db["Setting"]).isEqualTo(20.0)
    assertThat(db["Ratio"]).isEqualTo(40.0)
    assertThat(db["Maximum"]).isEqualTo(30.0)
    assertThat(db["Mode"]).isEqualTo(1000.0)
  }

  @Test
  fun `sumOf, minOf, maxOf and count read with one batch method`() {
    val source =
        """
        db.Setting = Batteries.Small.all.sumOf { it.Charge }
        db.Ratio = Batteries.Small.all.minOf { it.Charge } + Batteries.Small.all.maxOf { it.Charge }
        db.Mode = GrowLights.named("Potatoes").count { it.On }.toDouble()
        db.Maximum = GrowLights.all.count().toDouble()
        """
    val code = compiled(source).code

    assertThat(code).contains("lb r0 ${Hashes.hash(smallBattery)} Charge Sum")
    assertThat(code).contains("lbn r0 ${Hashes.hash(growLight)} ${Hashes.hash("Potatoes")} On Sum")
    assertThat(code).contains("lb r0 ${Hashes.hash(growLight)} PrefabHash Count")
    val db = run(source) { batteries() + lights().drop(1).take(3) }[0]
    assertThat(db["Setting"]).isEqualTo(40.0)
    assertThat(db["Ratio"]).isEqualTo(40.0)
    assertThat(db["Mode"]).isEqualTo(1.0)
    assertThat(db["Maximum"]).isEqualTo(3.0)
  }

  @Test
  fun `an empty batch reads as the game gives`() {
    val source =
        """
        db.Setting = Batteries.Small.all.maxOf { it.Charge }
        db.Ratio = Batteries.Small.all.map { it.Charge }.average()
        db.Mode = Batteries.Small.all.minOf { it.Charge }
        """
    val db = run(source) { listOf(FakeDevice(), FakeDevice()) }[0]

    assertThat(db["Setting"]).isEqualTo(Double.NEGATIVE_INFINITY)
    assertThat(db["Ratio"]).isNaN()
    assertThat(db["Mode"]).isEqualTo(0.0)
  }

  @Test
  fun `a batch's slots read with lbs and lbns`() {
    val source =
        """
        db.Setting = BatteryChargers.all.slots[0].map { it.Charge }.average()
        db.Ratio = BatteryChargers.named("North").slots[1].maxOf { it.Charge }
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
  fun `batches, their slots and their maps can be held in vals`() {
    val source =
        """
        val lights = GrowLights.named("Potatoes")
        lights.forEach { it.On = false }
        val charges = BatteryChargers.all.slots[1]
        val mapped = charges.map { it.Charge }
        db.Setting = mapped.sum()
        """
    val devices = run(source) { lights() + chargerWith(1.0, 2.0) + chargerWith(3.0, 4.0) }

    assertThat(devices[0]["Setting"]).isEqualTo(6.0)
    assertThat(devices.drop(1).take(2).map { it["On"] }).containsExactly(0.0, 0.0)
  }

  @Test
  fun `only the agreed lambda shapes compile, each error at its place`() {
    for ((source, at) in
        listOf(
            // A reading lambda picks one logic type of it.
            "val x = 1\nval c = Batteries.Small.all.map { it.Charge * 2.0 }.average()" to (2 to 33),
            "val x = 1\nval c = Batteries.Small.all.sumOf { db.Setting }" to (2 to 35),
            // A writing lambda only assigns the logic types of it, or of its slots.
            "val x = 1\nGrowLights.all.forEach { db.On = true }" to (2 to 26),
            "val x = 1\nGrowLights.all.forEach { it.On = it.Power }" to (2 to 37),
            "val x = 1\nGrowLights.all.forEach { sleep(1) }" to (2 to 26),
            "val x = 1\nval f = { l: GrowLight -> l.On = true }\nGrowLights.all.forEach(f)" to
                (2 to 9),
            // The game has no named batch slot write.
            "val x = 1\nGasTankStorages.named(\"N\").forEach { it.slots[0].Open = true }" to
                (2 to 50),
            // A batch is not a value, nor are its other members.
            "val x = 1\nval b = if (db.On) GrowLights.all else GrowLights.named(\"x\")" to
                (2 to 31),
            "val x = 1\nval h = GrowLights.all.hashCode()" to (2 to 24),
        )) {
      val error = error(source)
      assertWithMessage("$source\n${error.message}").that(error.line to error.column).isEqualTo(at)
    }
  }

  @Test
  fun `Text can't be read from a display`() {
    for (source in
        listOf("LedDisplays.Small.at(d0).Text", "LedDisplays.Small.all.map { it.Text }")) {
      assertWithMessage(source).that(error("val t = $source").message).contains("written")
    }
  }
}
