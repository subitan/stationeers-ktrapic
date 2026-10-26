package ktrapic.api

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import ktrapic.emu.Emulator
import ktrapic.emu.FakeDevice
import ktrapic.ic10.Hashes
import ktrapic.ic10.Program
import org.junit.jupiter.api.Test

/** Typed devices, batches, prefabs as values and display text. */
class TypedDeviceTest {
  private val growLight = "StructureGrowLight"
  private val smallBattery = "StructureBatterySmall"
  private val largeBattery = "StructureBatteryLarge"

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
   * network [network] makes (with `db` its first device and `d0` its second); returns the devices
   * of the normal run, after checking the compact run left its devices the same.
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
    }
    return runs[0]
  }

  private fun lights() =
      listOf(
          FakeDevice(),
          FakeDevice(growLight, "Potatoes").apply { this["On"] = 0.0 },
          FakeDevice(growLight, "Potatoes").apply { this["On"] = 0.0 },
          FakeDevice(growLight, "Tomatoes").apply { this["On"] = 0.0 },
          FakeDevice(smallBattery).apply { this["On"] = 0.0 },
      )

  @Test
  fun `a typed device loads and stores like its pin`() {
    val result =
        compiled(
            """
            val light = GrowLights.at(d0)
            light.On = true
            db.Setting = if (light.On) 1.0 else 0.0
            """
        )

    assertThat(result.code).contains("s d0 On 1")
    assertThat(result.code).contains("l r0 d0 On")
  }

  @Test
  fun `all writes every device of the prefab`() {
    val code = compiled("GrowLights.all.On = true").code
    val devices = run("GrowLights.all.On = true", ::lights)

    assertThat(code).isEqualTo("sb ${Hashes.hash(growLight)} On 1")
    assertThat(devices.drop(1).map { it["On"] }).containsExactly(1.0, 1.0, 1.0, 0.0).inOrder()
  }

  @Test
  fun `named writes the devices of the prefab with that name`() {
    val code = compiled("GrowLights.named(\"Potatoes\").On = true").code
    val devices = run("val potatoes = GrowLights.named(\"Potatoes\")\npotatoes.On = true", ::lights)

    assertThat(code).isEqualTo("sbn ${Hashes.hash(growLight)} ${Hashes.hash("Potatoes")} On 1")
    assertThat(devices.drop(1).map { it["On"] }).containsExactly(1.0, 1.0, 0.0, 0.0).inOrder()
  }

  @Test
  fun `batch methods read every device of the prefab`() {
    fun batteries() =
        listOf(
            FakeDevice(),
            FakeDevice(smallBattery, "A").apply { this["Charge"] = 10.0 },
            FakeDevice(smallBattery, "B").apply { this["Charge"] = 30.0 },
            FakeDevice(largeBattery, "A").apply { this["Charge"] = 1000.0 },
        )
    val source =
        """
        db.Setting = Batteries.Small.all.average.Charge
        db.Ratio = Batteries.Small.all.sum.Charge
        db.Maximum = Batteries.Small.named("B").maximum.Charge
        db.Mode = Batteries.Large.all.minimum.Charge
        """

    val db = run(source, ::batteries)[0]

    assertThat(compiled(source).code).contains("lb r0 ${Hashes.hash(smallBattery)} Charge Average")
    assertThat(compiled(source).code)
        .contains("lbn r0 ${Hashes.hash(smallBattery)} ${Hashes.hash("B")} Charge Maximum")
    assertThat(db["Setting"]).isEqualTo(20.0)
    assertThat(db["Ratio"]).isEqualTo(40.0)
    assertThat(db["Maximum"]).isEqualTo(30.0)
    assertThat(db["Mode"]).isEqualTo(1000.0)
  }

  @Test
  fun `reading a batch without a batch method is an error`() {
    val error = error("val x = 1\nval on = GrowLights.all.On")

    assertThat(error.message).contains("batch")
    assertThat(error.line to error.column).isEqualTo(2 to 25)
  }

  @Test
  fun `a prefab is its hash, and compares with a device's prefab`() {
    val source =
        """
        db.Setting = if (d0.PrefabHash == Battery.Small) 1.0 else 0.0
        db.Ratio = if (GrowLights.at(d0).PrefabHash == GrowLight) 1.0 else 0.0
        """
    fun network(prefab: String) = { listOf(FakeDevice(), FakeDevice(prefab)) }

    val small = run(source, network(smallBattery))[0]
    val light = run(source, network(growLight))[0]

    assertThat(compiled(source).code).contains("${Hashes.hash(smallBattery)}")
    assertThat(small["Setting"] to small["Ratio"]).isEqualTo(1.0 to 0.0)
    assertThat(light["Setting"] to light["Ratio"]).isEqualTo(0.0 to 1.0)
  }

  @Test
  fun `a prefab is not a number`() {
    val error = error("val x = Battery.Small + 1.0")

    assertThat(error.line to error.column).isEqualTo(1 to 23)
  }

  @Test
  fun `Text writes a display's Setting as STR`() {
    val source =
        """
        LedDisplays.Small.at(d0).Text = "HOT"
        LedDisplays.Large.all.Text = "OK"
        """
    val code = compiled(source).code

    assertThat(code).contains("s d0 Setting STR(\"HOT\")")
    assertThat(code).contains("sb ${Hashes.hash("StructureConsoleLED1x3")} Setting STR(\"OK\")")
    val devices =
        run(source) {
          listOf(
              FakeDevice(),
              FakeDevice("StructureConsoleLED5"),
              FakeDevice("StructureConsoleLED1x3"),
          )
        }
    assertThat(devices[1]["Setting"]).isEqualTo(Hashes.str("HOT"))
    assertThat(devices[2]["Setting"]).isEqualTo(Hashes.str("OK"))
  }

  @Test
  fun `Text must be one to six ASCII characters, written out`() {
    val computed = "\"T\${db.Setting}\""
    for (bad in listOf("\"SEVENCH\"", "\"\"", "\"Ä\"", "\"A\\tB\"", computed)) {
      val error = error("val x = 1\nLedDisplays.Small.at(d0).Text = $bad")
      assertWithMessage(bad).that(error.line to error.column).isEqualTo(2 to 26)
    }
  }

  @Test
  fun `hash of a string is the game's hash`() {
    val result = compiled("db.Setting = \"Potatoes\".hash()\ndb.Ratio = hash(\"Potatoes\")")

    assertThat(result.code).contains("s db Setting HASH(\"Potatoes\")")
    assertThat(result.code).contains("s db Ratio HASH(\"Potatoes\")")
  }

  @Test
  fun `a family's interface takes any of its members in an inlined function`() {
    val source =
        """
        inline fun ratio(battery: Battery): Double = battery.Ratio
        db.Setting = ratio(Batteries.Small.at(d0)) + ratio(Batteries.Large.at(d0))
        """
    val db =
        run(source) {
          listOf(FakeDevice(), FakeDevice(smallBattery).apply { this["Ratio"] = 0.25 })
        }[0]

    assertThat(db["Setting"]).isEqualTo(0.5)
  }

  @Test
  fun `a logic type the prefab lacks is an error`() {
    val error = error("GrowLights.at(d0).Charge")

    assertThat(error.line to error.column).isEqualTo(1 to 19)
  }

  @Test
  fun `a read-only logic type can't be written`() {
    val error = error("Batteries.Small.at(d0).Charge = 1.0")

    assertThat(error.line to error.column).isEqualTo(1 to 24)
  }
}
