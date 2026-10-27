package ktrapic.api

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import ktrapic.emu.Emulator
import ktrapic.emu.FakeDevice
import ktrapic.ic10.Hashes
import ktrapic.ic10.Program
import org.junit.jupiter.api.Test

/** Views: a device's logic types grouped as ports, ratios, vectors and import/export. */
class ViewTest {
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

  /** The code, without the comment-free blank lines, as instructions to compare. */
  private fun lines(source: String) = compiled(source).code.lines().filter { it.isNotBlank() }

  @Test
  fun `a view's logic type compiles to the one load the flat property was`() {
    val code =
        lines(
            """
            db.Setting = GasSensors.at(d0).Ratios[Substance.Oxygen]
            db.Setting = Filtrations.at(d0).Ports.Input.Pressure
            db.Setting = Filtrations.at(d0).Ports.Output.Ratios[Substance.LiquidAlcohol]
            db.Setting = Robots.at(d0).Position.X
            db.Setting = SatelliteDishes.at(d0).Dimensions.Z
            db.Setting = SDBHoppers.at(d0).Import.Count
            """
        )

    assertThat(code.filter { it.startsWith("l ") }.map { it.substringAfterLast(' ') })
        .containsExactly(
            "RatioOxygen",
            "PressureInput",
            "RatioLiquidAlcoholOutput",
            "PositionX",
            "SizeZ",
            "ImportCount",
        )
        .inOrder()
    assertThat(code.filter { it.startsWith("l ") }).hasSize(6)
  }

  @Test
  fun `a writable view member stores with s`() {
    val code =
        compiled(
                """
                AdvancedFurnaces.at(d0).Ports.Output.Setting = 2.5
                Robots.at(d0).Target.Y = 4.0
                """
            )
            .code

    assertThat(code).contains("s d0 SettingOutput 2.5")
    assertThat(code).contains("s d0 TargetY 4")
  }

  @Test
  fun `an untyped pin has every view, unchecked`() {
    val code =
        compiled(
                """
                db.Setting = d0.Ratios[Substance.Steam] + d0.Ports.Input2.Temperature
                d1.Ports.Output2.Setting = 1.0
                db.Ratio = d0.Dimensions.Y
                """
            )
            .code

    assertThat(code).contains("d0 RatioSteam")
    assertThat(code).contains("d0 TemperatureInput2")
    assertThat(code).contains("s d1 SettingOutput2 1")
    assertThat(code).contains("d0 SizeY")
  }

  @Test
  fun `views and keys can be held in vals`() {
    val code =
        compiled(
                """
                val filtration = Filtrations.at(d0)
                val outlet = filtration.Ports.Output
                val gas = Substance.Hydrogen
                db.Setting = outlet.Ratios[gas] + outlet.Pressure
                """
            )
            .code

    assertThat(code).contains("d0 RatioHydrogenOutput")
    assertThat(code).contains("d0 PressureOutput")
  }

  @Test
  fun `a typed device's view has only what its prefab reports`() {
    assertThat(error("db.Setting = SatelliteDishes.at(d0).Dimensions.Y").message)
        .isEqualTo("SatelliteDish has no SizeY")
    assertThat(error("db.Setting = Filtrations.at(d0).Ports.Input2.Pressure").message)
        .isEqualTo("Filtration has no PressureInput2")
    assertThat(error("Filtrations.at(d0).Ports.Input.Setting = 1.0").message)
        .contains("SettingInput")
  }

  @Test
  fun `a read-only view member can't be assigned, nor a view a device lacks named`() {
    // Kotlin's own type errors: Ratios has no set, Position is a Vector of vals, and a gas sensor
    // has no Ports.
    assertThat(compile("GasSensors.at(d0).Ratios[Substance.Oxygen] = 1.0"))
        .isInstanceOf(CompileResult.Failure::class.java)
    assertThat(compile("Robots.at(d0).Position.X = 1.0"))
        .isInstanceOf(CompileResult.Failure::class.java)
    assertThat(compile("db.Setting = GasSensors.at(d0).Ports.Input.Pressure"))
        .isInstanceOf(CompileResult.Failure::class.java)
  }

  @Test
  fun `a view or a key is not a value`() {
    assertThat(error("val x = d0.Ports.Input\ndb.Setting = if (x == x) 1.0 else 0.0").message)
        .contains("Port")
    assertThat(error("var gas = Substance.Oxygen").message).contains("held in a val")
  }

  @Test
  fun `a batch reads and writes a view's logic type with one batch instruction`() {
    val code =
        compiled(
                """
                db.Setting = GasSensors.all.map { it.Ratios[Substance.Oxygen] }.average()
                db.Ratio = Filtrations.all.maxOf { it.Ports.Output.Pressure }
                AdvancedFurnaces.all.forEach { it.Ports.Output.Setting = 3.0 }
                """
            )
            .code
    val sensor = Hashes.hash("StructureGasSensor")
    val furnace = Hashes.hash("StructureAdvancedFurnace")
    val filtration = Hashes.hash("StructureFiltration")

    assertThat(code).contains("$sensor RatioOxygen Average")
    assertThat(code).contains("$filtration PressureOutput Maximum")
    assertThat(code).contains("sb $furnace SettingOutput 3")
  }

  @Test
  fun `a batch's view is checked against the prefab too`() {
    assertThat(error("db.Setting = Filtrations.all.map { it.Ports.Input2.Pressure }.sum()").message)
        .isEqualTo("Filtration has no PressureInput2")
  }

  @Test
  fun `a program reading views runs as the flat logic types did`() {
    val sensor =
        FakeDevice("StructureGasSensor").apply {
          this["RatioOxygen"] = 0.21
          this["RatioNitrogen"] = 0.78
        }
    val db = FakeDevice()
    val result =
        compiled(
            """
            val sensor = GasSensors.at(d0)
            db.Setting = sensor.Ratios[Substance.Oxygen] + sensor.Ratios[Substance.Nitrogen]
            """
        )
    val emulator =
        Emulator(Program.parse(result.code), mapOf("db" to db, "d0" to sensor), listOf(db, sensor))
            .runUntilHalt()

    assertWithMessage(result.code).that(emulator.halted).isTrue()
    assertThat(db["Setting"]).isWithin(1e-9).of(0.99)
  }
}
