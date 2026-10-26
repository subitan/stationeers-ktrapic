package ktrapic.api

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import ktrapic.emu.Emulator
import ktrapic.emu.FakeDevice
import ktrapic.ic10.Program
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
   * What runs is the emitted text parsed back, as the chip would get it, so a value the text
   * doesn't carry (such as the sign of a zero) can't pass unnoticed.
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
      val emulator = Emulator(Program.parse(result.code), runDevices).runUntilHalt()
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
  fun `a Unit variable holds nothing, but its initializer and assignments still run`() {
    val db =
        run(
            """
            fun bump() { db.Ratio = db.Ratio + 1.0 }
            // Top-level, so properties.
            val u: Unit = if (db.Setting > 0.0) { db.Mode = 1.0 } else { db.Mode = 2.0 }
            var w = bump()
            fun locals() {
              val v = if (db.Setting > 0.0) bump() else {}
              var x: Unit
              x = when { db.Setting > 0.0 -> bump() else -> {} }
              x = v
              w = bump()
            }
            w = u
            locals()
            """,
            FakeDevice().also {
              it["Setting"] = 1.0
              it["Ratio"] = 0.0
            },
        )

    assertThat(db["Mode"]).isEqualTo(1.0)
    assertThat(db["Ratio"]).isEqualTo(4.0)
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
    val emulator = Emulator(Program.parse(result.code), mapOf("db" to db)).runUntilHalt()

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
  fun `a NaN constant is computed, since the chip rejects a nan operand`() {
    val source = "db.Setting = Double.NaN\ndb.Ratio = 0.0 / 0.0"
    val result = compiled(source)

    assertThat(result.code).doesNotContain("nan")
    val db = run(source)
    assertThat(db["Setting"]).isNaN()
    assertThat(db["Ratio"]).isNaN()
  }

  @Test
  fun `a constant Int expression that overflows is an error`() {
    for (expression in listOf("2147483647 + 1", "-2147483647 - 2")) {
      assertWithMessage(expression)
          .that(error("val x = $expression\ndb.Setting = x.toDouble()").message)
          .contains("overflows")
    }
    // Bitwise results the chip computes differently from the JVM, in range or not.
    for (expression in listOf("-1 ushr 28", "1 shl 31", "-8 ushr 1")) {
      assertWithMessage(expression)
          .that(error("val x = $expression\ndb.Setting = x.toDouble()").message)
          .contains("on the chip but")
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
  fun `negating a Double zero gives negative zero, folded or not`() {
    val db =
        run(
            """
            var x = 0.0
            var w = -0.0
            db.Setting = 1.0 / -x
            db.Ratio = 1.0 / -0.0
            db.Mode = 1.0 / w
            """
        )

    assertThat(db["Setting"]).isEqualTo(Double.NEGATIVE_INFINITY)
    assertThat(db["Ratio"]).isEqualTo(Double.NEGATIVE_INFINITY)
    assertThat(db["Mode"]).isEqualTo(Double.NEGATIVE_INFINITY)
  }

  @Test
  fun `a negative-zero constant is compared as zero`() {
    val source =
        """
        var x = 0.0
        db.Setting = if (x == -0.0) 1.0 else 2.0
        db.Ratio = when (x) { -0.0 -> 1.0; else -> 2.0 }
        db.Mode = if (-0.0 < x) 1.0 else 2.0
        """

    assertThat(compiled(source).code).doesNotContain("mul")
    val db = run(source)
    assertThat(db["Setting"]).isEqualTo(1.0)
    assertThat(db["Ratio"]).isEqualTo(1.0)
    assertThat(db["Mode"]).isEqualTo(2.0)
  }

  @Test
  fun `an Int zero stays positive when negated or folded`() {
    val db =
        run(
            """
            var n = 0
            var m = 0 * -1
            db.Setting = 1.0 / (-n).toDouble()
            db.Ratio = 1.0 / m.toDouble()
            """
        )

    assertThat(db["Setting"]).isEqualTo(Double.POSITIVE_INFINITY)
    assertThat(db["Ratio"]).isEqualTo(Double.POSITIVE_INFINITY)
  }

  @Test
  fun `kotlin math constants are folded`() {
    val result = compiled("db.Setting = PI\ndb.Ratio = E")

    assertThat(result.code).isEqualTo("s db Setting ${Math.PI}\ns db Ratio ${Math.E}")
  }

  @Test
  fun `str text the game can't take is reported where it is`() {
    for (text in listOf("1234567", "é", "a\\nb", "a\\tb", "a\\u0000")) {
      val error = error("db.Setting = str(\"$text\")")

      assertWithMessage(text).that(error.line).isEqualTo(3)
      assertWithMessage(text).that(error.column).isEqualTo(14)
    }
  }

  @Test
  fun `hash text with a line break or another control character is reported where it is`() {
    val q = "\"\"\""
    for (argument in listOf("\"a\\nb\"", "\"a\\rb\"", "\"a\\u007f\"", "${q}a\nb$q")) {
      val error = error("db.Setting = hash($argument)")

      assertWithMessage(argument).that(error.line).isEqualTo(3)
      assertWithMessage(argument).that(error.column).isEqualTo(14)
      assertWithMessage(argument).that(error.message).contains("control character")
    }
  }

  @Test
  fun `ordered Boolean comparisons, in outside a for, and coerceIn with a range are rejected`() {
    assertThat(error("db.Setting = if (d0.Open > d0.On) 1.0 else 0.0").line).isEqualTo(3)
    assertThat(error("var n = 3\ndb.Setting = if (n in 0..5) 1.0 else 0.0").line).isEqualTo(4)
    assertThat(error("var n = 3\ndb.Setting = when (n) { in 0..5 -> 1.0; else -> 2.0 }").line)
        .isEqualTo(4)
    assertThat(error("var x = 0.5\ndb.Setting = x.coerceIn(0.0..1.0)").line).isEqualTo(4)
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
    val error = error("val a = 1\nclass Box(val x: Int)\ndb.Setting = 1.0")

    assertThat(error.line).isEqualTo(4)
    assertThat(error.message).contains("Classes")
  }

  @Test
  fun `a function called once is inlined`() {
    val result = compiled("fun twice(x: Double) = x * 2.0\ndb.Setting = twice(db.Ratio)")

    assertThat(result.code).doesNotContain("jal")
    val db = run("fun twice(x: Double) = x * 2.0\ndb.Setting = twice(21.0)")
    assertThat(db["Setting"]).isEqualTo(42.0)
  }

  @Test
  fun `the source map points each source line at the code it compiled to`() {
    val source =
        """
        var total = 0.0
        for (i in 0..<3) {
          total += d0.Setting
        }
        fun bump(x: Double): Double {
          return x + 7.0
        }
        db.Setting = bump(total)
        db.Ratio = bump(2.0)
        """
    for (compact in listOf(false, true)) {
      val result = compiled(source, CompileOptions(compact = compact))
      val code = result.code.lines()
      // compile() puts two import lines before the source.
      fun at(line: Int) = result.sourceMap[line + 2].orEmpty().map { code[it] }

      assertWithMessage("compact=$compact\n${result.code}").apply {
        that(result.sourceMap.values.flatten().map { code[it] })
            .containsNoneIn(code.filter { it.endsWith(":") })
        that(at(1).count { it.startsWith("bge ") }).isEqualTo(1)
        that(at(2).single { it.startsWith("l ") }).endsWith("d0 Setting")
        that(at(5).single { it.startsWith("add ") }).endsWith(" 7")
        that(at(7).count { it.startsWith("jal ") }).isEqualTo(1)
        that(at(7).last()).startsWith("s db Setting ")
        that(at(8).last()).startsWith("s db Ratio ")
        that(result.sourceMap.keys).containsNoneOf(3 + 2, 6 + 2)
      }
    }
  }

  @Test
  fun `the source map points branches and loop conditions at their own lines`() {
    val source =
        """
        val m = d0.Setting
        when {
          m > 5.0 -> db.Setting = 1.0
          m > 2.0 -> db.Setting = 2.0
          else -> db.Setting = 3.0
        }
        var x = d0.Ratio
        if (x > 1.0)
          x = x * 3.0
        else
          x = x + 5.0
        db.Ratio = x
        var n = 0
        do {
          n++
        } while (
          n < 4
        )
        while (true) {
          n++
          if (n == 9) break
          val done = d0.On
          if (done) break
        }
        db.Setting = n.toDouble()
        when {
          n > 20 ->
            db.Ratio = 1.0
          else -> db.Ratio = 2.0
        }
        while (
          n < 30
        ) n++
        if (n > 1 &&
            n < 50)
          db.Setting = 1.0
        """
    for (compact in listOf(false, true)) {
      val result = compiled(source, CompileOptions(compact = compact))
      val code = result.code.lines()
      // compile() puts two import lines before the source.
      fun at(line: Int) = result.sourceMap[line + 2].orEmpty().map { code[it] }

      assertWithMessage("compact=$compact\n${result.code}").apply {
        that(at(2).single { it.startsWith("s ") }).isEqualTo("s db Setting 1")
        that(at(3).single { it.startsWith("s ") }).isEqualTo("s db Setting 2")
        that(at(4).single { it.startsWith("s ") }).isEqualTo("s db Setting 3")
        that(at(1).none { it.startsWith("s ") }).isTrue()
        that(at(8).single()).startsWith("mul ")
        that(at(10).single()).startsWith("add ")
        that(at(16).single()).startsWith("blt ")
        // Each of these is a branch over a `j`, which lowering inverts into one branch.
        that(at(20).single()).matches("b(eq|ne) .* 9 .*")
        that(at(22).single()).matches("b(eq|ne)z .*")
        that(at(26).map { it.substringBefore(" ") }).containsExactly("bgt", "j").inOrder()
        that(at(27)).containsExactly("s db Ratio 1")
        that(at(31).map { it.substringBefore(" ") }).containsExactly("blt", "j").inOrder()
        that(at(33).map { it.substringBefore(" ") }).containsExactly("bgt", "j").inOrder()
        that(at(34).map { it.substringBefore(" ") }).containsExactly("blt", "j").inOrder()
      }
    }
  }

  @Test
  fun `a function called more than once has one body, called with jal`() {
    val source =
        """
        fun step(x: Double, by: Double): Double {
          if (x > 10.0) return x
          return x + by
        }
        db.Setting = step(step(1.0, 2.0), 3.0)
        db.Ratio = step(11.0, 1.0)
        """
    val result = compiled(source)

    assertThat(result.code.lines().count { it.startsWith("jal ") }).isEqualTo(3)
    assertThat(result.code).doesNotContain("push ra")
    val db = run(source)
    assertThat(db["Setting"]).isEqualTo(6.0)
    assertThat(db["Ratio"]).isEqualTo(11.0)
  }

  @Test
  fun `values needed after a call keep their registers`() {
    val db =
        run(
            """
            fun noisy(x: Int): Int {
              var a = x * 3
              var b = a + 1
              var c = b * b
              return a + b + c
            }
            var total = 0
            for (i in 1..4) {
              val before = i * 100
              total += noisy(i) + before
            }
            db.Setting = total.toDouble()
            """
        )

    // noisy(i) = 3i + (3i + 1) + (3i + 1)^2; summed over 1..4, plus 1000.
    val expected = (1..4).sumOf { 3 * it + (3 * it + 1) + (3 * it + 1) * (3 * it + 1) } + 1000
    assertThat(db["Setting"]).isEqualTo(expected.toDouble())
  }

  @Test
  fun `a function that calls another saves ra on the stack`() {
    val source =
        """
        var calls = 0
        fun inner(x: Double): Double { calls++; return x + 1.0 }
        fun outer(x: Double): Double = inner(x) * inner(x + 10.0)
        db.Setting = outer(1.0) + outer(2.0)
        db.Ratio = calls.toDouble()
        """
    val result = compiled(source)

    assertThat(result.code).contains("push ra")
    assertThat(result.code).contains("pop ra")
    val db = run(source)
    assertThat(db["Setting"]).isEqualTo(2.0 * 12.0 + 3.0 * 13.0)
    assertThat(db["Ratio"]).isEqualTo(4.0)
  }

  @Test
  fun `functions read and write top-level properties`() {
    val db =
        run(
            """
            var count = 0
            fun bump() { count += 2 }
            fun show() { db.Setting = count.toDouble() }
            bump()
            bump()
            show()
            bump()
            show()
            """
        )

    assertThat(db["Setting"]).isEqualTo(6.0)
  }

  @Test
  fun `an inlined function keeps its parameter when the argument changes`() {
    val db =
        run(
            """
            var x = 5
            fun f(p: Int): Int { x = 100; return p + x }
            db.Setting = f(x).toDouble()
            """
        )

    assertThat(db["Setting"]).isEqualTo(105.0)
  }

  @Test
  fun `an inline function can take a device`() {
    val d1 = FakeDevice()
    val d2 = FakeDevice()
    run(
        """
        inline fun light(device: Device, on: Boolean) { device.On = on }
        light(d1, true)
        light(d2, false)
        """,
        FakeDevice(),
        "d1" to d1,
        "d2" to d2,
    )

    assertThat(d1["On"]).isEqualTo(1.0)
    assertThat(d2["On"]).isEqualTo(0.0)
  }

  @Test
  fun `an operand keeps its value when a later call assigns it`() {
    val inlined =
        run(
            """
            var count = 0
            fun bump(): Int { count += 1; return 10 }
            db.Setting = (count + bump()).toDouble()
            """
        )
    assertThat(inlined["Setting"]).isEqualTo(10.0)

    val called =
        run(
            """
            var count = 0
            fun bump(): Int { count += 1; return 10 }
            db.Setting = (count + bump()).toDouble()
            db.Ratio = (count + bump()).toDouble()
            """
        )
    assertThat(called["Setting"]).isEqualTo(10.0)
    assertThat(called["Ratio"]).isEqualTo(11.0)

    val argument =
        run(
            """
            var x = 5
            fun change(): Int { x = 100; return 1 }
            fun pair(a: Int, b: Int): Int = a * 1000 + b
            db.Setting = pair(x, change()).toDouble()
            """
        )
    assertThat(argument["Setting"]).isEqualTo(5001.0)

    val nested =
        run(
            """
            var n = 1
            fun inc(): Int { n += 1; return 0 }
            fun viaInc(): Int = inc() + 2
            db.Setting = (n + viaInc()).toDouble()
            db.Ratio = (n + viaInc()).toDouble()
            """
        )
    assertThat(nested["Setting"]).isEqualTo(3.0)
    assertThat(nested["Ratio"]).isEqualTo(4.0)
  }

  @Test
  fun `function names that are not IC10 labels get labels that are`() {
    val source =
        "fun größe(x: Int) = x + 1\n" +
            "fun `two words`(x: Int) = x * 10\n" +
            "fun `2x`(x: Int) = x * 2\n" +
            "db.Setting = (größe(1) + größe(2)).toDouble()\n" +
            "db.Ratio = (`two words`(1) + `two words`(2)).toDouble()\n" +
            "db.Mode = (`2x`(1) + `2x`(2)).toDouble()"
    val result = compiled(source)

    Program.parse(result.code) // Throws if a label is not one IC10 accepts.
    assertThat(result.code).contains("jal _2x")
    val db = run(source)
    assertThat(db["Setting"]).isEqualTo(5.0)
    assertThat(db["Ratio"]).isEqualTo(30.0)
    assertThat(db["Mode"]).isEqualTo(6.0)
  }

  @Test
  fun `function names shaped like registers or devices get other labels`() {
    val names =
        listOf("r0", "r16", "r99", "rr0", "rrr1", "d0", "d7", "dr0", "drr1", "sp", "ra", "db")
    for (name in names) {
      val source = "fun $name(x: Int) = x + 1\n" + "db.Setting = ($name(1) + $name(2)).toDouble()"
      val result = compiled(source)

      assertWithMessage(name).that(result.code).contains("jal ${name}_\n")
      assertWithMessage(name).that(result.code).contains("\n${name}_:")
      assertWithMessage(name).that(run(source)["Setting"]).isEqualTo(5.0)
    }
  }

  @Test
  fun `function names spelled like constants get other labels`() {
    for (name in listOf("pi", "PI", "Tau", "nan", "Epsilon")) {
      val source = "fun $name(x: Int) = x + 1\n" + "db.Setting = ($name(1) + $name(2)).toDouble()"
      val result = compiled(source)

      assertWithMessage(name).that(result.code).contains("jal ${name}_\n")
      assertWithMessage(name).that(run(source)["Setting"]).isEqualTo(5.0)
    }
  }

  @Test
  fun `function names only resembling registers keep their names`() {
    for (name in listOf("r", "rx", "d", "dr", "r0x", "rd0", "dd0")) {
      val source = "fun $name(x: Int) = x + 1\n" + "db.Setting = ($name(1) + $name(2)).toDouble()"
      assertWithMessage(name).that(compiled(source).code).contains("jal $name\n")
    }
  }

  private fun assertError(source: String, message: String, line: Int, column: Int) {
    val error = error(source)
    assertWithMessage(source).that(error.message).contains(message)
    assertWithMessage(source).that(error.line to error.column).isEqualTo(line to column)
  }

  @Test
  fun `unsupported function forms are reported where they are`() {
    // Source line N is reported as N + 2, after the two imports compile() adds.
    assertError(
        "fun f(n: Int): Int = if (n < 1) 0 else f(n - 1)\ndb.Setting = f(3).toDouble()",
        "Recursive",
        3,
        40,
    )
    assertError("fun f(n: Int = 2) = n\ndb.Setting = f().toDouble()", "Default", 4, 14)
    assertError("fun f(d: Device) { d.On = true }\nf(d0)\nf(d1)", "device", 4, 1)
    assertError("fun Int.twice() = this * 2\ndb.Setting = 3.twice().toDouble()", "Extension", 3, 1)
    assertError(
        "fun f() {\n  fun g() = 1\n  db.Setting = g().toDouble()\n}\nf()",
        "Local functions",
        4,
        3,
    )
    assertError("fun <T> f(x: T): Int = 1\ndb.Setting = f(1).toDouble()", "Generic", 3, 1)
    assertError("fun f(vararg x: Int): Int = 1\ndb.Setting = f(1).toDouble()", "vararg", 3, 7)
    assertError(
        "fun f(x: String): Int = 1\ndb.Setting = f(\"a\").toDouble()",
        "Parameters of type String",
        3,
        7,
    )
    assertError("fun f(): String = \"a\"\nf()", "Functions returning String", 3, 1)
  }

  @Test
  fun `the ktrapic API needs no import, and an explicit one is still fine`() {
    for (source in listOf("db.Setting = 1.0", "import ktrapic.*\ndb.Setting = 1.0")) {
      val result = Compiler.compile(source)
      assertWithMessage(source).that(result).isInstanceOf(CompileResult.Success::class.java)
    }
    val error = (Compiler.compile("val x = 1\nd0.On = 1.0") as CompileResult.Failure).errors.first()
    assertThat(error.line).isEqualTo(2)
    assertThat(error.column).isEqualTo(7)
  }

  @Test
  fun `the implied import comes after a shebang, file annotations and a package line`() {
    val sources =
        listOf(
            "#!/usr/bin/env kotlin\nimport ktrapic.*\ndb.Setting = 1.0",
            "#!/usr/bin/env kotlin\ndb.Setting = 1.0",
            "@file:Suppress(\"UNUSED_VARIABLE\")\nimport ktrapic.*\nval x = 1\ndb.Setting = 1.0",
            "@file:Suppress(\"UNUSED_VARIABLE\")\nval x = 1\ndb.Setting = 1.0",
            "package foo\nimport ktrapic.*\ndb.Setting = 1.0",
            "package foo\ndb.Setting = 1.0",
        )
    for (source in sources) {
      assertWithMessage(source)
          .that(Compiler.compile(source))
          .isInstanceOf(CompileResult.Success::class.java)
    }
    val error =
        (Compiler.compile("package foo\n\nd0.On = 1.0") as CompileResult.Failure).errors.first()
    assertThat(error.line to error.column).isEqualTo(3 to 7)
    val sameLineError =
        (Compiler.compile("package p; d0.On = 1.0") as CompileResult.Failure).errors.first()
    assertThat(sameLineError.line to sameLineError.column).isEqualTo(1 to 18)
  }

  @Test
  fun `the implied import comes before semicolons and comments ahead of the first statement`() {
    val sources =
        listOf(
            "; db.Setting = 1.0",
            ";\ndb.Setting = 1.0",
            "/* c */ ;\n;\ndb.Setting = 1.0",
            "package foo;\n;\ndb.Setting = 1.0",
            "#!/usr/bin/env kotlin\n;\ndb.Setting = 1.0",
            "import kotlin.math.max;\n;\ndb.Setting = max(1.0, 2.0)",
        )
    for (source in sources) {
      assertWithMessage(source)
          .that(Compiler.compile(source))
          .isInstanceOf(CompileResult.Success::class.java)
    }
    val error = (Compiler.compile("; d0.On = 1.0") as CompileResult.Failure).errors.first()
    assertThat(error.line to error.column).isEqualTo(1 to 9)
  }

  @Test
  fun `kotlin errors are reported`() {
    assertThat(error("db.On = 1.0").line).isEqualTo(3)
  }
}
