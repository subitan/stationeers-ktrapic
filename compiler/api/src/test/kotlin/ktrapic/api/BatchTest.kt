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
  fun `any, all and none read once and compare, or branch in a condition`() {
    val source =
        """
        db.Setting = if (GrowLights.all.any { it.On }) 1.0 else 0.0
        db.Ratio = if (GrowLights.named("Potatoes").all { it.On }) 1.0 else 0.0
        val dark = GrowLights.named("Tomatoes").none { it.On }
        db.Mode = if (dark) 1.0 else 0.0
        """
    val code = compiled(source).code

    assertThat(code).contains("lb r0 ${Hashes.hash(growLight)} On Maximum\nblez r0 ")
    assertThat(code)
        .contains(
            "lbn r0 ${Hashes.hash(growLight)} ${Hashes.hash("Potatoes")} On Minimum\nblez r0 "
        )
    assertThat(code)
        .contains(
            "lbn r0 ${Hashes.hash(growLight)} ${Hashes.hash("Tomatoes")} On Maximum\nslez r0 r0"
        )
    // Potatoes: one off, one on; Tomatoes: on.
    val db = run(source, ::lights)[0]
    assertThat(listOf(db["Setting"], db["Ratio"], db["Mode"]))
        .containsExactly(1.0, 0.0, 0.0)
        .inOrder()
  }

  @Test
  fun `a batch test negated, before an or, or as a loop condition branches as in Kotlin`() {
    val source =
        """
        db.Setting = if (!GrowLights.all.any { it.On }) 1.0 else 0.0
        db.Ratio = if (GrowLights.named("Tomatoes").all { it.On } || db.Setting > 5.0) 1.0 else 0.0
        var i = 0
        while (GrowLights.named("Potatoes").any { it.On }) {
          i++
          if (i == 3) GrowLights.named("Potatoes").forEach { it.On = false }
        }
        db.Mode = i.toDouble()
        """
    val code = compiled(source).code

    assertThat(code).contains("lb r0 ${Hashes.hash(growLight)} On Maximum\nbgtz r0 ")
    // Potatoes: one off, one on; Tomatoes: on.
    val db = run(source, ::lights)[0]
    assertThat(listOf(db["Setting"], db["Ratio"], db["Mode"]))
        .containsExactly(0.0, 1.0, 3.0)
        .inOrder()
  }

  @Test
  fun `on an empty batch, any is false and none true as in Kotlin, and all is false`() {
    val source =
        """
        db.Setting = if (GrowLights.all.any { it.On }) 1.0 else 0.0
        db.Ratio = if (GrowLights.all.none { it.On }) 1.0 else 0.0
        db.Mode = if (GrowLights.all.all { it.On }) 1.0 else 0.0
        """
    val db = run(source) { listOf(FakeDevice(), FakeDevice()) }[0]

    assertThat(listOf(db["Setting"], db["Ratio"], db["Mode"]))
        .containsExactly(0.0, 1.0, 0.0)
        .inOrder()
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
  fun `batches and their slots can be held in vals, a slot keeping its number`() {
    val source =
        """
        val lights = GrowLights.named("Potatoes")
        lights.forEach { it.On = false }
        var i = 1
        val charges = BatteryChargers.all.slots[i]
        i = 0
        db.Setting = charges.map { it.Charge }.sum()
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
            "val x = 1\nval c = Batteries.Small.all.sumOf { val v = it.Charge; v }" to (2 to 35),
            "val x = 1\nval c = Batteries.Small.all.sumOf { it.run { Charge } }" to (2 to 35),
            "val x = 1\nval c = Batteries.Small.all.sumOf { it.let { b -> b.Charge } }" to
                (2 to 35),
            // A writing lambda only assigns the logic types of it, or of its slots.
            "val x = 1\nGrowLights.all.forEach { db.On = true }" to (2 to 26),
            "val x = 1\nGrowLights.all.forEach { it.On = it.Power }" to (2 to 37),
            "val x = 1\nGrowLights.all.forEach { sleep(1) }" to (2 to 26),
            "val x = 1\nGrowLights.all.forEach { GrowLights.all.forEach { it.On = true } }" to
                (2 to 41),
            "val x = 1\nGrowLights.all.forEach { val v = true; it.On = v }" to (2 to 26),
            "var n = 0.0\nGrowLights.all.forEach { n = 1.0 }" to (2 to 26),
            "val x = 1\nGasTankStorages.all.forEach { it.slots[it.ReferenceId.toInt()].Open = true }" to
                (2 to 31),
            "val x = 1\nBatteries.Small.all.forEach { it.On = Batteries.Small.all.sumOf { b -> it.Charge } > 0 }" to
                (2 to 39),
            "val x = 1\nval f = { l: GrowLight -> l.On = true }\nGrowLights.all.forEach(f)" to
                (2 to 9),
            "fun on(l: GrowLight) { l.On = true }\nGrowLights.all.forEach(::on)" to (2 to 24),
            // A lambda that can't return, nor run a written value or slot number more than once.
            "val x = 1\nGrowLights.all.forEach {\n  return@forEach\n  it.On = true\n}" to (3 to 3),
            "var n = 0\nGrowLights.all.forEach { it.Lock = n++ > 100 }" to (2 to 36),
            "var n = 0\nGasTankStorages.all.forEach { it.slots[n++].Open = true }" to (2 to 40),
            "fun w(): Boolean {\n  yield()\n  return true\n}\nGrowLights.all.forEach { it.On = w() }" to
                (5 to 34),
            // The game has no named batch slot write, nor count of a named batch.
            "val x = 1\nGasTankStorages.named(\"N\").forEach { it.slots[0].Open = true }" to
                (2 to 50),
            "val x = 1\nval c = GrowLights.named(\"N\").count()" to (2 to 31),
            // A batch is not a value, nor are its other members, and a map can't be held.
            "val x = 1\nval b = if (db.On) GrowLights.all else GrowLights.named(\"x\")" to
                (2 to 31),
            "val x = 1\nval h = GrowLights.all.hashCode()" to (2 to 24),
            "val x = 1\nval m = GrowLights.all.map { it.Power }" to (2 to 24),
            "fun f() {\n  val m = GrowLights.all.map { it.Power }\n}\nf()" to (2 to 26),
        )) {
      val error = error(source)
      assertWithMessage("$source\n${error.message}").that(error.line to error.column).isEqualTo(at)
    }
  }

  @Test
  fun `a batch error says what the game or Kotlin would do differently`() {
    for ((source, message) in
        listOf(
            "var n = 0\nGrowLights.all.forEach { it.Lock = n++ > 100 }" to "computed once",
            "var n = 0\nGasTankStorages.all.forEach { it.slots[n++].Open = true }" to
                "computed once",
            "fun on(): Boolean {\n  db.On = true\n  return true\n}\nGrowLights.all.forEach { it.On = on() }" to
                "computed once",
            "fun bump(): Boolean {\n  d0[LogicType.Setting] = d0[LogicType.Setting] + 1.0\n" +
                "  return true\n}\nGrowLights.all.forEach { it.On = bump() }" to "computed once",
            "fun w(): Boolean {\n  yield()\n  return true\n}\nGrowLights.all.forEach { it.On = w() }" to
                "computed once",
            "fun n(): Int {\n  sleep(1)\n  return 0\n}\nGasTankStorages.all.forEach { it.slots[n()].Open = true }" to
                "computed once",
            "fun g(): Boolean {\n  GrowLights.all.forEach { it.Lock = true }\n  return true\n}\n" +
                "GrowLights.all.forEach { it.On = g() }" to "computed once",
            "GrowLights.all.forEach { it.On = !it.On }" to "can't use the batch's devices",
            "GrowLights.all.forEach { return@forEach }" to "can't return",
            "GrowLights.all.forEach { sleep(1) }" to "may only assign logic types",
            "GrowLights.all.forEach { GrowLights.all.forEach { it.On = true } }" to
                "may only assign logic types",
            "val c = GrowLights.named(\"N\").count()" to "can't count a named batch",
            "val m = GrowLights.all.map { it.Power }" to "a map can't be held",
        )) {
      assertWithMessage(source).that(error(source).message).contains(message)
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
