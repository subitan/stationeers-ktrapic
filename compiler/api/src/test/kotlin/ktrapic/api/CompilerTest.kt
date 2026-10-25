package ktrapic.api

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import ktrapic.emu.Emulator
import ktrapic.emu.FakeDevice
import org.junit.jupiter.api.Test

class CompilerTest {
  private fun compile(source: String, options: CompileOptions = CompileOptions()) =
      Compiler.compile("import ktrapic.*\nimport kotlin.math.*\n" + source.trimIndent(), options)

  private fun compiled(source: String, options: CompileOptions = CompileOptions()) =
      when (val result = compile(source, options)) {
        is CompileResult.Success -> result
        is CompileResult.Failure -> throw AssertionError("compile failed: ${result.errors}")
      }

  /** Compiles [source], runs it to the end with [db] as the housing, and returns [db]. */
  private fun run(
      source: String,
      db: FakeDevice = FakeDevice(),
      vararg pins: Pair<String, FakeDevice>,
  ): FakeDevice {
    for (compact in listOf(false, true)) {
      val result = compiled(source, CompileOptions(compact = compact))
      val device = if (compact) FakeDevice().also { it.logic.putAll(db.logic) } else db
      val emulator = Emulator(result.program, mapOf("db" to device) + pins).runUntilHalt()
      assertWithMessage("halted (compact=$compact)\n${result.code}").that(emulator.halted).isTrue()
      if (compact) assertThat(device.logic).isEqualTo(db.logic)
    }
    return db
  }

  private fun error(source: String): CompileError =
      (compile(source) as? CompileResult.Failure)?.errors?.first()
          ?: throw AssertionError("expected an error")

  @Test
  fun `writes a constant to a device`() {
    assertThat(run("db.Setting = 42.0")["Setting"]).isEqualTo(42.0)
  }

  @Test
  fun `folds constant expressions`() {
    val result = compiled("val x = 2 + 3 * 4\ndb.Setting = x.toDouble()")

    assertThat(result.code).isEqualTo("s db Setting 14")
  }

  @Test
  fun `sums a range in a for loop`() {
    val db =
        run(
            """
            var total = 0
            for (i in 1..10) total += i
            db.Setting = total.toDouble()
            """
        )

    assertThat(db["Setting"]).isEqualTo(55.0)
  }

  @Test
  fun `for loops over until, downTo and step`() {
    val db =
        run(
            """
            var a = 0
            for (i in 0 until 5) a += i
            var b = 0
            for (i in 10 downTo 1 step 3) b = b * 100 + i
            var c = 0
            for (i in 1..<10 step 4) c += i
            db.Setting = a.toDouble()
            db.Ratio = b.toDouble()
            db.Mode = c.toDouble()
            """
        )

    assertThat(db["Setting"]).isEqualTo(10.0)
    assertThat(db["Ratio"]).isEqualTo(10070401.0)
    assertThat(db["Mode"]).isEqualTo(15.0)
  }

  @Test
  fun `int division truncates and remainder keeps the dividend's sign`() {
    val db =
        run(
            """
            var a = -7
            var b = 2
            db.Setting = (a / b).toDouble()
            db.Ratio = (a % b).toDouble()
            db.Mode = (7 % -3).toDouble()
            """
        )

    assertThat(db["Setting"]).isEqualTo(-3.0)
    assertThat(db["Ratio"]).isEqualTo(-1.0)
    assertThat(db["Mode"]).isEqualTo(1.0)
  }

  @Test
  fun `while, do-while, labeled break and continue`() {
    val db =
        run(
            """
            var n = 0
            var visits = 0
            outer@ while (true) {
              n++
              var i = 0
              do {
                i++
                if (i == 2) {
                  if (n == 3) break@outer else continue@outer
                }
                visits++
              } while (i < 5)
            }
            db.Setting = n.toDouble()
            db.Ratio = visits.toDouble()
            """
        )

    assertThat(db["Setting"]).isEqualTo(3.0)
    assertThat(db["Ratio"]).isEqualTo(3.0)
  }

  @Test
  fun `if expressions and boolean operators`() {
    val d0 =
        FakeDevice().also {
          it["On"] = 2.0
          it["Open"] = 0.0
        }
    val db =
        run(
            """
            val on = d0.On
            val both = on && !d0.Open
            val either = !on || d0.Open
            db.Setting = if (both) 1.0 else 2.0
            db.Ratio = if (either) 1.0 else 2.0
            db.Mode = if (on == true) 3.0 else 4.0
            """,
            FakeDevice(),
            "d0" to d0,
        )

    assertThat(db["Setting"]).isEqualTo(1.0)
    assertThat(db["Ratio"]).isEqualTo(2.0)
    assertThat(db["Mode"]).isEqualTo(3.0)
  }

  @Test
  fun `reads and writes devices by property and by logic type`() {
    val d0 = FakeDevice().also { it["SignalStrength"] = 50.0 }
    val db =
        run(
            """
            val sensor = d0
            db[LogicType.Setting] = sensor.SignalStrength + d0[LogicType.SignalStrength]
            db.Color = Color.Red
            db.On = true
            """,
            FakeDevice(),
            "d0" to d0,
        )

    assertThat(db["Setting"]).isEqualTo(100.0)
    assertThat(db["Color"]).isEqualTo(4.0)
    assertThat(db["On"]).isEqualTo(1.0)
  }

  @Test
  fun `math functions`() {
    val db =
        run(
            """
            var x = -2.5
            db.Setting = abs(x) + sqrt(16.0) + max(x, 1.0)
            db.Ratio = round(x) + floor(x) + x.roundToInt().toDouble()
            db.Mode = (7.5 % 2.0)
            """
        )

    assertThat(db["Setting"]).isEqualTo(7.5)
    assertThat(db["Ratio"]).isEqualTo(-2.0 - 3.0 - 2.0)
    assertThat(db["Mode"]).isEqualTo(1.5)
  }

  @Test
  fun `hash and str become constants`() {
    val result = compiled("db.Setting = hash(\"StructureBattery\")\ndb.Mode = str(\"Hi\")")

    assertThat(result.code)
        .isEqualTo("s db Setting HASH(\"StructureBattery\")\ns db Mode STR(\"Hi\")")
  }

  @Test
  fun `sleep and yield take ticks`() {
    val result = compiled("db.Setting = 1.0\nsleep(1)\nyield()\ndb.Setting = 2.0")
    val db = FakeDevice()
    val emulator = Emulator(result.program, mapOf("db" to db)).runUntilHalt()

    assertThat(db["Setting"]).isEqualTo(2.0)
    assertThat(emulator.ticks).isAtLeast(3)
  }

  @Test
  fun `registers are reused once values are dead`() {
    val source = (1..20).joinToString("\n") { "val v$it = db.Setting\ndb.Ratio = v$it * 2.0" }

    assertThat(compiled(source).registers).isEqualTo(1)
  }

  @Test
  fun `too many live values is an error`() {
    val source =
        (1..17).joinToString("\n") { "var v$it = db.Setting" } +
            "\ndb.Ratio = " +
            (1..17).joinToString(" + ") { "v$it" }

    assertThat(error(source).message).contains("16")
  }

  @Test
  fun `unsupported constructs are reported where they are`() {
    val error = error("val a = 1\nfun f() = 2\ndb.Setting = f().toDouble()")

    assertThat(error.line).isEqualTo(5)
    assertThat(error.message).contains("f")
  }

  @Test
  fun `kotlin errors are reported`() {
    assertThat(error("db.On = 1.0").line).isEqualTo(3)
  }
}
