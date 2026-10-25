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

  /**
   * Compiles [source], runs it to the end with [db] as the housing, and returns [db]. The compact
   * program runs too, on copies of the devices as they were before, and must leave them the same.
   */
  private fun run(
      source: String,
      db: FakeDevice = FakeDevice(),
      vararg pins: Pair<String, FakeDevice>,
  ): FakeDevice {
    val devices = mapOf("db" to db) + pins
    val copies = devices.mapValues { (_, device) -> device.copy() }
    for ((compact, runDevices) in listOf(false to devices, true to copies)) {
      val result = compiled(source, CompileOptions(compact = compact))
      val emulator = Emulator(result.program, runDevices).runUntilHalt()
      assertWithMessage("halted (compact=$compact)\n${result.code}").that(emulator.halted).isTrue()
    }
    for ((pin, device) in devices) {
      assertWithMessage("$pin after the compact program")
          .that(copies.getValue(pin).logic)
          .isEqualTo(device.logic)
    }
    return db
  }

  private fun FakeDevice.copy() = FakeDevice(prefabHash, nameHash).also { it.logic.putAll(logic) }

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
  fun `an operand keeps its value when a later operand assigns it`() {
    val db =
        run(
            """
            var x = 1
            val y = x + x++
            var z = 1
            var c = true
            val w = z + if (c) { z = 10; 1 } else 2
            var a = 1
            db.Setting = y.toDouble()
            db.Ratio = (x * 10 + w).toDouble()
            db.On = a > a++
            """
        )

    assertThat(db["Setting"]).isEqualTo(2.0)
    assertThat(db["Ratio"]).isEqualTo(22.0)
    assertThat(db["On"]).isEqualTo(0.0)
  }

  @Test
  fun `a math call's argument keeps its value when a later argument assigns it`() {
    val db =
        run(
            """
            var u = 1.0
            db.Setting = max(u, if (true) { u = 7.0; 0.0 } else 0.0)
            var c = 5
            db.Ratio = c.coerceIn(c++, 10).toDouble()
            """
        )

    assertThat(db["Setting"]).isEqualTo(1.0)
    assertThat(db["Ratio"]).isEqualTo(5.0)
  }

  @Test
  fun `a range's bounds keep their values when its step assigns them`() {
    val db =
        run(
            """
            var s = 1
            var n = 0
            var first = -1
            for (i in s..10 step s++) { if (first < 0) first = i; n++ }
            db.Setting = first.toDouble()
            db.Ratio = n.toDouble()
            """
        )

    assertThat(db["Setting"]).isEqualTo(1.0)
    assertThat(db["Ratio"]).isEqualTo(10.0)
  }

  @Test
  fun `a for loop's step is evaluated once`() {
    val db =
        run(
            """
            var s = 2
            var n = 0
            for (i in 0..10 step s) { n++; s = 5 }
            db.Setting = n.toDouble()
            """
        )

    assertThat(db["Setting"]).isEqualTo(6.0)
  }

  @Test
  fun `comparisons with NaN are false in branches`() {
    val db =
        run(
            """
            val x = sqrt(-1.0)
            if (x > 5.0) db.Setting = 1.0 else db.Setting = 2.0
            db.Ratio = if (x <= 5.0) 1.0 else 2.0
            var n = 0
            while (x < 1.0) { n++; if (n > 3) break }
            db.Mode = n.toDouble()
            """
        )

    assertThat(db["Setting"]).isEqualTo(2.0)
    assertThat(db["Ratio"]).isEqualTo(2.0)
    assertThat(db["Mode"]).isEqualTo(0.0)
  }

  @Test
  fun `comparisons with an Int holding NaN are false in branches`() {
    val db =
        run(
            """
            var z = 0
            val n = z / z
            db.Setting = if (n > 5) 1.0 else 2.0
            db.Ratio = if (n <= 5) 1.0 else 2.0
            val q = z / z
            db.Mode = if (q > 0 || q <= 0) 1.0 else 2.0
            """
        )

    assertThat(db["Setting"]).isEqualTo(2.0)
    assertThat(db["Ratio"]).isEqualTo(2.0)
    assertThat(db["Mode"]).isEqualTo(2.0)
  }

  @Test
  fun `Double remainder folds to what the chip computes`() {
    val db =
        run(
            """
            val a = 10.0
            var b = 10.0
            db.Setting = b % 0.1
            db.Ratio = a % 0.1
            """
        )

    assertThat(db["Setting"]).isEqualTo(0.0)
    assertThat(db["Ratio"]).isEqualTo(0.0)
  }

  @Test
  fun `a step of a stepped range is rejected`() {
    assertThat(error("for (i in 1..10 step 2 step 3) db.Setting = i.toDouble()").message)
        .contains("step")
  }

  @Test
  fun `a for loop with a NaN bound ends`() {
    val db =
        run(
            """
            var z = 0
            val n = z / z
            var count = 0
            for (i in 0..n) count++
            for (i in n downTo 0) count++
            db.Setting = count.toDouble()
            """
        )

    assertThat(db["Setting"]).isEqualTo(0.0)
  }

  @Test
  fun `for loops with run-time bounds`() {
    val db =
        run(
            """
            var e = 5
            var a = 0
            for (i in 0 until e) { if (i == 2) continue; a += i }
            var b = 0
            for (i in 0..<e) b += i
            var c = 0
            for (i in e downTo 1 step 2) c = c * 10 + i
            var d = 0
            for (i in 1..e step 2) d += i
            var g = 0
            for (i in e until e) g++
            for (i in e..0) g++
            for (i in 0 downTo e) g++
            db.Setting = a.toDouble()
            db.Ratio = b.toDouble()
            db.Mode = c.toDouble()
            db.Charge = d.toDouble()
            db.Pressure = g.toDouble()
            """
        )

    assertThat(db["Setting"]).isEqualTo(8.0)
    assertThat(db["Ratio"]).isEqualTo(10.0)
    assertThat(db["Mode"]).isEqualTo(531.0)
    assertThat(db["Charge"]).isEqualTo(9.0)
    assertThat(db["Pressure"]).isEqualTo(0.0)
  }

  @Test
  fun `Int arithmetic past the Int range does not wrap at run time`() {
    val db =
        run(
            """
            var a = 2147483647
            a += 1
            db.Setting = a.toDouble()
            """
        )

    assertThat(db["Setting"]).isEqualTo(2147483648.0)
  }

  @Test
  fun `a constant Int expression that overflows is an error`() {
    for (expression in listOf("2147483647 + 1", "-1 ushr 28", "1 shl 31", "-2147483647 - 2")) {
      assertWithMessage(expression)
          .that(error("val x = $expression\ndb.Setting = x.toDouble()").message)
          .contains("overflows")
    }
    assertThat(error("val x = 1 shl 32\ndb.Setting = x.toDouble()").message).contains("shift")
  }

  @Test
  fun `toInt saturates and maps NaN to 0, folded or not`() {
    val db =
        run(
            """
            var big = 3e10
            var small = -3e10
            var nan = sqrt(-1.0)
            var x = -2.7
            db.Setting = big.toInt().toDouble() + 3e10.toInt().toDouble()
            db.Ratio = small.toInt().toDouble() + (-3e10).toInt().toDouble()
            db.Mode = nan.toInt().toDouble() + sqrt(-1.0).toInt().toDouble()
            db.Charge = x.toInt().toDouble() + (-2.7).toInt().toDouble()
            """
        )

    assertThat(db["Setting"]).isEqualTo(2.0 * Int.MAX_VALUE)
    assertThat(db["Ratio"]).isEqualTo(2.0 * Int.MIN_VALUE)
    assertThat(db["Mode"]).isEqualTo(0.0)
    assertThat(db["Charge"]).isEqualTo(-4.0)
  }

  @Test
  fun `roundToInt saturates and maps NaN to 0, folded or not`() {
    val db =
        run(
            """
            var big = 3e10
            var nan = sqrt(-1.0)
            var x = -2.5
            db.Setting = big.roundToInt().toDouble() + 3e10.roundToInt().toDouble()
            db.Ratio = (-big).roundToInt().toDouble() + (-3e10).roundToInt().toDouble()
            db.Mode = nan.roundToInt().toDouble() + sqrt(-1.0).roundToInt().toDouble()
            db.Charge = x.roundToInt().toDouble() + (-2.5).roundToInt().toDouble()
            """
        )

    assertThat(db["Setting"]).isEqualTo(2.0 * Int.MAX_VALUE)
    assertThat(db["Ratio"]).isEqualTo(2.0 * Int.MIN_VALUE)
    assertThat(db["Mode"]).isEqualTo(0.0)
    assertThat(db["Charge"]).isEqualTo(-4.0)
  }

  @Test
  fun `roundToInt rounds just below a half down, folded or not`() {
    val db =
        run(
            """
            var a = 0.49999999999999994
            var b = -0.49999999999999994
            var c = -0.7
            db.Setting = a.roundToInt().toDouble() + 0.49999999999999994.roundToInt().toDouble()
            db.Ratio = b.roundToInt().toDouble() + (-0.49999999999999994).roundToInt().toDouble()
            db.Mode = c.roundToInt().toDouble() + (-0.7).roundToInt().toDouble()
            db.Charge = 1.0 / a.roundToInt().toDouble()
            """
        )

    assertThat(db["Setting"]).isEqualTo(0.0)
    assertThat(db["Ratio"]).isEqualTo(0.0)
    assertThat(db["Mode"]).isEqualTo(-2.0)
    assertThat(db["Charge"]).isEqualTo(Double.POSITIVE_INFINITY)
  }

  @Test
  fun `toInt of a negative fraction is a positive zero, folded or not`() {
    val db =
        run(
            """
            var h = -0.5
            val c = -0.5
            db.Setting = 1.0 / h.toInt().toDouble()
            db.Ratio = 1.0 / c.toInt().toDouble()
            """
        )

    assertThat(db["Setting"]).isEqualTo(Double.POSITIVE_INFINITY)
    assertThat(db["Ratio"]).isEqualTo(Double.POSITIVE_INFINITY)
  }

  @Test
  fun `custom setters and delegated properties are rejected`() {
    assertThat(error("var x = 0\n  set(v) { field = v * 2 }\nx = 5").message).contains("setter")
    assertThat(error("val x by lazy { 3 }\ndb.Setting = x.toDouble()").message)
        .contains("Delegated")
  }

  @Test
  fun `a when used as a value without else is reported`() {
    val error = error("db.Setting = when (d0.On) { true -> 1.0; false -> 2.0 }")

    assertThat(error.message).contains("else")
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
