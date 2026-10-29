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
            val light = GrowLight.at(d0)
            light.On = true
            db.Setting = if (light.On) 1.0 else 0.0
            """
        )

    assertThat(result.code).contains("s d0 On 1")
    assertThat(result.code).contains("l r0 d0 On")
  }

  @Test
  fun `a prefab is its hash, and compares with a device's prefab`() {
    val source =
        """
        db.Setting = if (d0.PrefabHash == Battery.Small) 1.0 else 0.0
        db.Ratio = if (GrowLight.at(d0).PrefabHash == GrowLight) 1.0 else 0.0
        """
    fun network(prefab: String) = { listOf(FakeDevice(), FakeDevice(prefab)) }

    val small = run(source, network(smallBattery))[0]
    val light = run(source, network(growLight))[0]

    assertThat(compiled(source).code).contains("${Hashes.hash(smallBattery)}")
    assertThat(small["Setting"] to small["Ratio"]).isEqualTo(1.0 to 0.0)
    assertThat(light["Setting"] to light["Ratio"]).isEqualTo(0.0 to 1.0)
  }

  @Test
  fun `the type a hover shows for a prefab or a batch compiles written out`() {
    // Each declares `x`, whose hover is taken on its use (at the `|`), and uses it.
    val sources =
        mapOf(
            "val x = GrowLight\n|x.all.forEach { it.On = true }" to "Prefab<GrowLight>",
            "val x = LedDisplay.Small\n|x.at(d0).Setting = 1.0" to "Prefab<LedDisplay.Small>",
            "val x = BatteryCharger.Standard.all\ndb.Setting = |x.count().toDouble()" to
                "BatteryCharger.Standard.Batch",
            "val x = GrowLight.at(d0).PrefabHash\ndb.On = |x == GrowLight" to "Prefab<*>",
            "val x = BatteryCharger.Standard\n" +
                "db.Setting = |x.all.slots[0].map { it.Charge }.average()" to
                "BatteryCharger.Standard.Companion",
        )
    for ((source, expected) in sources) {
      val offset = source.indexOf('|')
      val program = source.removeRange(offset, offset + 1)
      val hover = Compiler.hover(program, offset)!!
      assertWithMessage(source).that(hover.declaration).isEqualTo("val x: $expected")
      val written =
          program.replaceFirst("val x =", "val x: ${hover.declaration.removePrefix("val x: ")} =")
      assertWithMessage(written)
          .that(compile(written))
          .isInstanceOf(CompileResult.Success::class.java)
    }
  }

  @Test
  fun `a typed device reads its NameHash`() {
    val db =
        run("db.Setting = GrowLight.at(d0).NameHash") {
          listOf(FakeDevice(), FakeDevice(growLight, "Potatoes"))
        }[0]

    assertThat(db["Setting"]).isEqualTo(Hashes.hash("Potatoes").toDouble())
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
        LedDisplay.Small.at(d0).Text = "HOT"
        LedDisplay.Large.all.forEach { it.Text = "OK" }
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
  fun `Text is on the LED displays' interface, for any of them`() {
    val source =
        """
        inline fun show(display: LedDisplay) {
          display.Mode = DisplayMode.String
          display.Text = "HOT"
        }
        show(LedDisplay.Small.at(d0))
        show(LedDisplay.Large.at(db))
        """
    val devices =
        run(source) {
          listOf(FakeDevice("StructureConsoleLED1x3"), FakeDevice("StructureConsoleLED5"))
        }
    for (display in devices) assertThat(display["Setting"]).isEqualTo(Hashes.str("HOT"))
  }

  @Test
  fun `Text can't be read through the LED displays' interface either`() {
    val error = error("inline fun f(display: LedDisplay) { db.Setting = display.Text.hash() }")

    assertThat(error.message).contains("this logic type can only be written")
  }

  @Test
  fun `Text must be one to six ASCII characters, written out`() {
    val computed = "\"T\${db.Setting}\""
    for (bad in listOf("\"SEVENCH\"", "\"\"", "\"Ä\"", "\"A\\tB\"", computed)) {
      val error = error("val x = 1\nLedDisplay.Small.at(d0).Text = $bad")
      assertWithMessage(bad).that(error.line to error.column).isEqualTo(2 to 25)
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
        db.Setting = ratio(Battery.Small.at(d0)) + ratio(Battery.Large.at(d0))
        """
    val db =
        run(source) {
          listOf(FakeDevice(), FakeDevice(smallBattery).apply { this["Ratio"] = 0.25 })
        }[0]

    assertThat(db["Setting"]).isEqualTo(0.5)
  }

  @Test
  fun `a family that extends another is taken where the other is`() {
    val source =
        """
        inline fun speed(shaft: ElevatorShaft): Double = shaft.ElevatorSpeed
        val shaft: ElevatorShaft = ElevatorLevel.Cabled.at(d0)
        shaft.ElevatorLevel = 3.0
        db.Setting = speed(ElevatorLevel.Cabled.at(d0)) + speed(shaft)
        """
    val devices =
        run(source) {
          listOf(
              FakeDevice(),
              FakeDevice("StructureElevatorLevelFront").apply { this["ElevatorSpeed"] = 2.0 },
          )
        }

    assertThat(devices[1]["ElevatorLevel"]).isEqualTo(3.0)
    assertThat(devices[0]["Setting"]).isEqualTo(4.0)
  }

  @Test
  fun `every door is a Door`() {
    val source =
        """
        inline fun close(door: Door) {
          door.Open = false
        }
        close(Door.Glass.at(d0))
        close(InteriorDoor.Padded.at(d0))
        close(HangarDoor.Large.at(d0))
        close(Airlock.Wide.at(d0))
        db.Setting = 1.0
        """
    val devices =
        run(source) {
          listOf(FakeDevice(), FakeDevice("StructureGlassDoor").apply { this["Open"] = 1.0 })
        }

    assertThat(devices[1]["Open"]).isEqualTo(0.0)
    assertThat(devices[0]["Setting"]).isEqualTo(1.0)
  }

  @Test
  fun `a logic type the prefab lacks is an error`() {
    val error = error("GrowLight.at(d0).Charge")

    assertThat(error.line to error.column).isEqualTo(1 to 18)
  }

  @Test
  fun `a read-only logic type can't be written`() {
    val error = error("Battery.Small.at(d0).Charge = 1.0")

    assertThat(error.line to error.column).isEqualTo(1 to 22)
  }
}
