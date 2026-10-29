package ktrapic.api

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import ktrapic.emu.Emulator
import ktrapic.emu.FakeDevice
import ktrapic.emu.Ic10RuntimeException
import ktrapic.ic10.Hashes
import ktrapic.ic10.Program
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * A function called on the stack that calls itself in tail position, compiled as a loop
 * (`tailCalls`, the default) or as a call.
 */
class TailCallTest {
  private fun compiled(source: String, options: CompileOptions = CompileOptions()) =
      when (val result = Compiler.compile("import ktrapic.*\n" + source.trimIndent(), options)) {
        is CompileResult.Success -> result
        is CompileResult.Failure -> throw AssertionError("compile failed: ${result.errors}")
      }

  /** [source] run to the end with [options], which must leave the stack as it found it. */
  private fun runWith(source: String, options: CompileOptions): Map<String, Double> {
    val code = compiled(source, options).code
    val db = FakeDevice()
    val emulator = Emulator(Program.parse(code), mapOf("db" to db)).runUntilHalt()
    assertWithMessage("$options\n$code").that(emulator.register("sp")).isEqualTo(0.0)
    return db.logic.toMap()
  }

  /**
   * Runs [source] to the end with tail calls on and off, with and without compact output, and
   * returns the housing's logic types, which every run must leave the same.
   */
  private fun run(source: String): Map<String, Double> {
    val results =
        listOf(true, false).flatMap { tailCalls ->
          listOf(false, true).map { compact ->
            runWith(source, CompileOptions(compact = compact, tailCalls = tailCalls))
          }
        }
    for (result in results) assertThat(result).isEqualTo(results[0])
    return results[0]
  }

  /**
   * The code of the function at [label], up to the next function's label or `end:`: those, unlike
   * the labels within a function, have no digits.
   */
  private fun body(code: String, label: String): List<String> {
    val lines = code.lines()
    val start = lines.indexOf("$label:")
    return lines.drop(start + 1).takeWhile { !it.matches(Regex("[A-Za-z]+:")) }
  }

  private val countdown =
      """
      fun countdown(n: Int, steps: Int): Int = if (n == 0) steps else countdown(n - 1, steps + 1)
      db.Setting = countdown(DEPTH, 0).toDouble()
      """

  @Test
  fun `a deep countdown in tail position runs as a loop, far past the stack's depth`() {
    val source = countdown.replace("DEPTH", "2000")
    for (compact in listOf(false, true)) {
      assertThat(runWith(source, CompileOptions(compact = compact))["Setting"]).isEqualTo(2000.0)
    }
    val code = compiled(source).code
    assertThat(code.lines().count { it == "jal countdown" }).isEqualTo(1)
    val loop = body(code, "countdown")
    assertThat(loop).doesNotContain("push ra")
    assertThat(loop).contains("j countdown_loop1")
  }

  @Test
  fun `the same countdown overflows the stack without tail calls`() {
    val code = compiled(countdown.replace("DEPTH", "2000"), CompileOptions(tailCalls = false)).code
    val emulator = Emulator(Program.parse(code), mapOf("db" to FakeDevice()))

    val error = assertThrows<Ic10RuntimeException> { emulator.runUntilHalt() }
    assertThat(error.message).contains("stack overflow")
  }

  @Test
  fun `a shallow countdown gives the same result either way`() {
    assertThat(run(countdown.replace("DEPTH", "100"))["Setting"]).isEqualTo(100.0)
  }

  @Test
  fun `factorial with an accumulator, as a return in a block body`() {
    val db =
        run(
            """
            fun fact(n: Int, acc: Int): Int {
              if (n <= 1) return acc
              return fact(n - 1, acc * n)
            }
            db.Setting = fact(10, 1).toDouble()
            db.Ratio = fact(1, 7).toDouble()
            """
        )

    assertThat(db["Setting"]).isEqualTo(3628800.0)
    assertThat(db["Ratio"]).isEqualTo(7.0)
  }

  @Test
  fun `gcd as a when expression`() {
    val source =
        """
        fun gcd(a: Int, b: Int): Int =
            when {
              b == 0 -> a
              else -> gcd(b, a % b)
            }
        db.Setting = gcd(1071, 462).toDouble()
        db.Ratio = gcd(17, 5).toDouble()
        """
    val db = run(source)

    assertThat(db["Setting"]).isEqualTo(21.0)
    assertThat(db["Ratio"]).isEqualTo(1.0)
    assertThat(body(compiled(source).code, "gcd")).doesNotContain("jal gcd")
  }

  @Test
  fun `a call whose result is used afterwards still uses the stack`() {
    val source =
        """
        fun sum(n: Int): Int = if (n == 0) 0 else n + sum(n - 1)
        fun twice(n: Int): Int = if (n == 0) 0 else sum(twice(n - 1)) - sum(twice(n - 1)) + 2
        db.Setting = sum(10).toDouble()
        db.Ratio = twice(3).toDouble()
        """
    val db = run(source)

    assertThat(db["Setting"]).isEqualTo(55.0)
    assertThat(db["Ratio"]).isEqualTo(2.0)
    val code = compiled(source).code
    assertThat(body(code, "sum")).contains("jal sum")
    assertThat(code).doesNotContain("_loop")
  }

  @Test
  fun `a tail call on one branch and a call on the stack on another`() {
    val source =
        """
        fun collatz(n: Int, steps: Int): Int =
            if (n == 1) steps
            else if (n % 2 == 0) collatz(n / 2, steps + 1)
            else 1 + collatz(3 * n + 1, steps)
        db.Setting = collatz(27, 0).toDouble()
        db.Ratio = collatz(1, 5).toDouble()
        """
    val db = run(source)

    assertThat(db["Setting"]).isEqualTo(111.0)
    assertThat(db["Ratio"]).isEqualTo(5.0)
    val loop = body(compiled(source).code, "collatz")
    // The call on the stack saves ra, once, before the loop's head.
    assertThat(loop.count { it == "push ra" }).isEqualTo(1)
    assertThat(loop.indexOf("push ra")).isLessThan(loop.indexOf("collatz_loop1:"))
    assertThat(loop.count { it == "jal collatz" }).isEqualTo(1)
    assertThat(loop).contains("j collatz_loop1")
  }

  @Test
  fun `a Unit function's last statement, or a call before return, is a tail call`() {
    val source =
        """
        var total = 0
        fun add(n: Int) {
          if (n == 0) return
          total += n
          add(n - 1)
        }
        fun addOdd(n: Int) {
          if (n <= 0) return
          if (n % 2 == 1) total += n
          addOdd(n - 1)
          return
        }
        add(1000)
        addOdd(5)
        db.Setting = total.toDouble()
        """
    assertThat(runWith(source, CompileOptions())["Setting"]).isEqualTo(500500.0 + 9.0)
    val code = compiled(source).code
    assertThat(body(code, "add")).doesNotContain("jal add")
    assertThat(body(code, "addOdd")).doesNotContain("jal addOdd")
    assertThat(run(source.replace("1000", "50"))["Setting"]).isEqualTo(1275.0 + 9.0)
  }

  @Test
  fun `a Unit function ending in an if whose other branch has a value`() {
    val source =
        """
        var count = 0
        fun walk(n: Int) {
          if (n > 0) walk(n - 1) else count++
        }
        walk(900)
        walk(3)
        db.Setting = count.toDouble()
        """
    assertThat(runWith(source, CompileOptions())["Setting"]).isEqualTo(2.0)
    assertThat(body(compiled(source).code, "walk")).doesNotContain("jal walk")
  }

  @Test
  fun `swapped arguments go through a temporary`() {
    val source =
        """
        fun swaps(a: Int, b: Int, n: Int): Int = if (n == 0) a * 10 + b else swaps(b, a, n - 1)
        db.Setting = swaps(1, 2, 3).toDouble()
        db.Ratio = swaps(1, 2, 4).toDouble()
        """
    val db = run(source)

    assertThat(db["Setting"]).isEqualTo(21.0)
    assertThat(db["Ratio"]).isEqualTo(12.0)
  }

  @Test
  fun `a parameter read by a later argument is moved after it`() {
    val db =
        run(
            """
            fun fib(n: Int, a: Int, b: Int): Int = if (n == 0) a else fib(n - 1, b, a + b)
            db.Setting = fib(20, 0, 1).toDouble()
            """
        )

    assertThat(db["Setting"]).isEqualTo(6765.0)
  }

  @Test
  fun `a String argument is passed on packed`() {
    val source =
        """
        var steps = 0
        fun show(msg: String, n: Int) {
          if (n == 0) {
            db.Setting = str(msg)
            return
          }
          steps++
          show(msg, n - 1)
        }
        show("tail", 3)
        show("call", 2)
        db.Ratio = steps.toDouble()
        """
    val db = run(source)

    assertThat(db["Setting"]).isEqualTo(Hashes.str("call"))
    assertThat(db["Ratio"]).isEqualTo(5.0)
  }

  @Test
  fun `a global changed in the loop is kept`() {
    val db =
        run(
            """
            var calls = 0
            var last = 0.0
            fun down(n: Int): Int {
              calls++
              last = n.toDouble()
              return if (n == 0) calls else down(n - 1)
            }
            db.Setting = down(5).toDouble()
            db.Ratio = down(3).toDouble() + last
            db.Mode = calls.toDouble()
            """
        )

    assertThat(db["Setting"]).isEqualTo(6.0)
    assertThat(db["Ratio"]).isEqualTo(10.0)
    assertThat(db["Mode"]).isEqualTo(10.0)
  }

  @Test
  fun `a call in a tail call's arguments is not a tail call`() {
    val source =
        """
        fun f(n: Int): Int = if (n <= 0) 0 else f(f(n - 2) + n - 1)
        db.Setting = f(6).toDouble()
        """
    val code = compiled(source).code

    assertThat(run(source)["Setting"]).isEqualTo(0.0)
    assertThat(body(code, "f").count { it == "jal f" }).isEqualTo(1)
    assertThat(body(code, "f")).contains("j f_loop1")
  }

  @Test
  fun `a function with no parameters loops back to its own label`() {
    val source =
        """
        var n = 0
        fun spin() {
          n++
          if (n < 1000) spin()
        }
        spin()
        db.Setting = n.toDouble()
        """
    val code = compiled(source).code

    assertThat(runWith(source, CompileOptions())["Setting"]).isEqualTo(1000.0)
    assertThat(code).doesNotContain("_loop")
    assertThat(body(code, "spin")).contains("j spin")
  }

  @Test
  fun `tail calls have no effect without stack calls`() {
    val source =
        """
        fun add(a: Double, b: Double): Double = a + b
        db.Setting = add(1.0, 2.0)
        db.Ratio = add(3.0, 4.0)
        """

    assertThat(compiled(source, CompileOptions(stackCalls = false, tailCalls = true)).code)
        .isEqualTo(compiled(source, CompileOptions(stackCalls = false, tailCalls = false)).code)
  }
}
