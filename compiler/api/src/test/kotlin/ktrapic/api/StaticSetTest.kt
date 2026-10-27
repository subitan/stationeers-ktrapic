package ktrapic.api

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import ktrapic.emu.Emulator
import ktrapic.emu.FakeDevice
import ktrapic.ic10.Program
import org.junit.jupiter.api.Test

/** Static sets: collections known when compiled, and the loops unrolled over them. */
class StaticSetTest {
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

  private fun loads(code: String) =
      code.lines().filter { it.startsWith("l ") }.map { it.substringAfterLast(' ') }

  /**
   * Runs [source], normal and compact, with `db` and `d0` as given; returns `db` of the normal run
   * after checking the compact run left it the same.
   */
  private fun run(source: String, d0: () -> FakeDevice, db: () -> FakeDevice = { FakeDevice() }) =
      listOf(false, true)
          .map { compact ->
            val devices = listOf(db(), d0())
            val result = compiled(source, CompileOptions(compact = compact))
            val pins = mapOf("db" to devices[0], "d0" to devices[1])
            val emulator = Emulator(Program.parse(result.code), pins, devices).runUntilHalt()
            assertWithMessage("halted (compact=$compact)\n${result.code}")
                .that(emulator.halted)
                .isTrue()
            devices[0]
          }
          .also { (normal, compact) -> assertThat(compact.logic).isEqualTo(normal.logic) }
          .first()

  private fun sensor() =
      FakeDevice("StructureGasSensor").apply {
        this["RatioOxygen"] = 0.2
        this["RatioNitrogen"] = 0.7
        this["RatioCarbonDioxide"] = 0.05
      }

  @Test
  fun `a loop over a set of substances is unrolled, one load per substance`() {
    val source =
        """
        val sensor = GasSensors.at(d0)
        var total = 0.0
        for (s in setOf(Substance.Oxygen, Substance.Nitrogen)) total += sensor.Ratios[s]
        db.Setting = total
        """
    val code = compiled(source).code

    assertThat(loads(code)).containsExactly("RatioOxygen", "RatioNitrogen").inOrder()
    assertThat(code.lines().none { it.startsWith("b") || it.startsWith("j ") }).isTrue()
    assertThat(run(source, ::sensor)["Setting"]).isWithin(1e-9).of(0.9)
  }

  @Test
  fun `a set can be held in a val and combined, and its size and in fold`() {
    val code =
        compiled(
                """
                val air = setOf(Substance.Oxygen, Substance.Nitrogen, Substance.CarbonDioxide)
                val breathable = air - Substance.CarbonDioxide
                val some = breathable intersect setOf(Substance.Oxygen, Substance.Water)
                db.Setting = air.size + breathable.size * 10.0 + some.size * 100.0
                db.Ratio = if (Substance.Water in air) 1.0 else 2.0
                db.Mode = if (Substance.Oxygen !in some) 1.0 else 3.0
                """
            )
            .code

    assertThat(code).contains("s db Setting 123")
    // Each `in` folds to a constant, so neither if branches.
    assertThat(code.lines().none { it.startsWith("b") || it.startsWith("j ") }).isTrue()
    assertThat(code).contains("move r0 2\ns db Ratio r0")
    assertThat(code).contains("move r0 3\ns db Mode r0")
  }

  @Test
  fun `a view's keys are the substances its device reports`() {
    val code =
        compiled(
                """
                var total = 0.0
                for (s in GasSensors.at(d0).Ratios.keys) total += GasSensors.at(d0).Ratios[s]
                for (s in Filtrations.at(d1).Ports.Output.Ratios.keys) total += Filtrations.at(d1).Ports.Output.Ratios[s]
                db.Setting = total + Substance.entries.size
                """
            )
            .code
    val loaded = loads(code)

    assertThat(loaded.filter { !it.endsWith("Output") }).hasSize(28)
    assertThat(loaded.filter { it.endsWith("Output") }).hasSize(28)
    assertThat(loaded).contains("RatioLiquidAlcoholOutput")
  }

  @Test
  fun `an enum's entries and a list of slot numbers unroll too`() {
    val code =
        compiled(
                """
                var total = 0.0
                for (s in Substance.entries) total += d0.Ratios[s]
                for (i in listOf(0, 2)) total += d1.slots[i].Quantity
                db.Setting = total
                """
            )
            .code

    assertThat(loads(code)).hasSize(28)
    assertThat(code).contains("ls")
    assertThat(code.lines().filter { it.startsWith("ls ") }.map { it.split(' ')[3] })
        .containsExactly("0", "2")
        .inOrder()
  }

  @Test
  fun `continue goes on to the next copy and break past the last`() {
    val source =
        """
        val sensor = GasSensors.at(d0)
        var total = 0.0
        for (s in setOf(Substance.Oxygen, Substance.Nitrogen, Substance.CarbonDioxide, Substance.Water)) {
          val ratio = sensor.Ratios[s]
          if (ratio > 0.5) continue
          if (ratio < 0.1) break
          total += ratio
        }
        db.Setting = total
        """

    // Oxygen (0.2) counts, Nitrogen (0.7) is skipped, CarbonDioxide (0.05) ends the loop.
    assertThat(run(source, ::sensor)["Setting"]).isWithin(1e-9).of(0.2)
  }

  @Test
  fun `a number known only when run is tested against a set of numbers`() {
    val source =
        """
        val mode = d0.Mode.toInt()
        db.Setting = if (mode in setOf(1, 3, 5)) 1.0 else 0.0
        """
    fun device(mode: Double) = { FakeDevice().apply { this["Mode"] = mode } }

    assertThat(run(source, device(3.0))["Setting"]).isEqualTo(1.0)
    assertThat(run(source, device(2.0))["Setting"]).isEqualTo(0.0)
  }

  @Test
  fun `a set can be passed to an inlined function`() {
    val source =
        """
        inline fun total(ratios: Ratios, gases: Set<Substance>): Double {
          var sum = 0.0
          for (g in gases) sum += ratios[g]
          return sum
        }
        db.Setting = total(GasSensors.at(d0).Ratios, setOf(Substance.Oxygen, Substance.CarbonDioxide))
        """

    assertThat(run(source, ::sensor)["Setting"]).isWithin(1e-9).of(0.25)
  }

  @Test
  fun `a range loop is still a real loop`() {
    val code = compiled("var n = 0\nfor (i in 0..9) n += i\ndb.Setting = n.toDouble()").code

    assertThat(code.lines().any { it.startsWith("j ") || it.startsWith("b") }).isTrue()
  }

  @Test
  fun `what isn't known when compiled is an error`() {
    assertThat(error("val x = d0.Mode\nval s = setOf(1.0, x)").message).contains("constants")
    assertThat(error("var s = setOf(1, 2)").message).contains("held in a val")
    assertThat(error("for (s in LogicType.entries) db.Setting = 1.0").message)
        .contains("Substance.entries")
    assertThat(error("val s = setOf(1, 2)\ndb.Setting = s.first().toDouble()").message)
        .contains("first")
  }
}
