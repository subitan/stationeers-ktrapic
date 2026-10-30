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
 * Functions called with `jal`, under both conventions: arguments and result on the stack (the
 * default), and in registers (`stackCalls = false`, or `@InRegisters`).
 */
class StackCallTest {
  private fun compile(source: String, options: CompileOptions = CompileOptions()) =
      Compiler.compile("import ktrapic.*\n" + source.trimIndent(), options)

  private fun compiled(source: String, options: CompileOptions = CompileOptions()) =
      when (val result = compile(source, options)) {
        is CompileResult.Success -> result
        is CompileResult.Failure -> throw AssertionError("compile failed: ${result.errors}")
      }

  private fun error(source: String, options: CompileOptions = CompileOptions()): CompileError =
      (compile(source, options) as? CompileResult.Failure)?.errors?.first()
          ?: throw AssertionError("expected an error")

  /**
   * Runs [source] to the end under each of [conventions] (stack calls on or off), with and without
   * compact output, and returns the housing's logic types, which every run must leave the same.
   * Each run must also leave the stack as it found it.
   */
  private fun run(
      source: String,
      conventions: List<Boolean> = listOf(true, false),
  ): Map<String, Double> {
    val results = conventions.flatMap { stackCalls ->
      listOf(false, true).map { compact ->
        val code = compiled(source, CompileOptions(compact, stackCalls)).code
        val db = FakeDevice()
        val emulator = Emulator(Program.parse(code), mapOf("db" to db)).runUntilHalt()
        assertWithMessage("stackCalls=$stackCalls compact=$compact\n$code")
            .that(emulator.register("sp"))
            .isEqualTo(0.0)
        db.logic.toMap()
      }
    }
    for (result in results) assertThat(result).isEqualTo(results[0])
    return results[0]
  }

  @Test
  fun `calls, nested calls and results`() {
    val db =
        run(
            """
            fun step(x: Double, by: Double): Double {
              if (x > 10.0) return x
              return x + by
            }
            fun twice(x: Double): Double = step(step(x, x), 0.5)
            db.Setting = step(step(1.0, 2.0), 3.0)
            db.Ratio = step(11.0, 1.0)
            db.Mode = twice(2.0) + twice(3.0)
            """
        )

    assertThat(db["Setting"]).isEqualTo(6.0)
    assertThat(db["Ratio"]).isEqualTo(11.0)
    assertThat(db["Mode"]).isEqualTo(4.5 + 6.5)
  }

  @Test
  fun `a call on the stack pushes its arguments and pops its result`() {
    val source =
        """
        fun add(a: Double, b: Double): Double = a + b
        db.Setting = add(1.0, 2.0)
        db.Ratio = add(3.0, 4.0)
        """
    val code = compiled(source).code.lines()

    assertThat(code.subList(0, 4))
        .containsExactly("push 1", "push 2", "jal add", "pop r0")
        .inOrder()
    val body = code.subList(code.indexOf("add:") + 1, code.indexOf("end:"))
    assertThat(body.first()).matches("pop r\\d+")
    assertThat(body).doesNotContain("push ra")
    assertThat(body.takeLast(2).first()).matches("push r\\d+")
    assertThat(body.last()).isEqualTo("j ra")
    assertThat(compiled(source, CompileOptions(stackCalls = false)).code).doesNotContain("push")
  }

  @Test
  fun `values needed after a call are saved on the stack, and only those`() {
    val source =
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
        db.Ratio = noisy(0).toDouble()
        """
    val db = run(source)

    val expected = (1..4).sumOf { 3 * it + (3 * it + 1) + (3 * it + 1) * (3 * it + 1) } + 1000
    assertThat(db["Setting"]).isEqualTo(expected.toDouble())
    assertThat(db["Ratio"]).isEqualTo(2.0)
    val code = compiled(source).code.lines()
    // In the loop, total, i and before are needed after the call; after the loop, nothing is.
    val first = code.indexOfFirst { it.startsWith("jal ") }
    assertThat(code.subList(first - 4, first).count { it.startsWith("push r") }).isEqualTo(4)
    val last = code.indexOfLast { it.startsWith("jal ") }
    assertThat(code[last - 1]).isEqualTo("push 0")
    assertThat(code[last - 2]).doesNotContain("push")
  }

  @Test
  fun `a function's registers are its own, so a caller with many live values can call one needing many`() {
    val source =
        """
        fun busy(x: Double): Double {
          val a = x + 1.0; val b = x + 2.0; val c = x + 3.0; val d = x + 4.0
          val e = x + 5.0; val f = x + 6.0; val g = x + 7.0; val h = x + 8.0
          return a * b + c * d + e * f + g * h + a * h + b * g + c * f + d * e
        }
        val p = d0.Setting; val q = d1.Setting; val r = d2.Setting; val s = d3.Setting
        val t = d4.Setting; val u = d5.Setting; val v = db.Setting; val w = db.Ratio
        val one = busy(1.0)
        val two = busy(2.0)
        db.Setting = one + two + p + q + r + s + t + u + v + w
        """

    val stack = compiled(source)
    assertThat(stack.registers).isAtMost(16)
    val registers = compile(source, CompileOptions(stackCalls = false))
    assertThat(registers).isInstanceOf(CompileResult.Failure::class.java)
    assertThat((registers as CompileResult.Failure).errors.single().message)
        .contains("registers at once")
  }

  @Test
  fun `a global written by the function is read by the caller after the call`() {
    val db =
        run(
            """
            var count = 0
            var last = 0.0
            fun bump(by: Int): Int { count += by; last = by.toDouble(); return count }
            fun show() { db.Setting = count.toDouble() }
            val before = count
            val a = bump(2)
            val after = count
            show()
            bump(3)
            show()
            db.Ratio = (before * 10000 + a * 100 + after).toDouble()
            db.Mode = count.toDouble() + last
            """
        )

    assertThat(db["Setting"]).isEqualTo(5.0)
    assertThat(db["Ratio"]).isEqualTo(202.0)
    assertThat(db["Mode"]).isEqualTo(8.0)
  }

  @Test
  fun `a global counted in a function called in a short-circuit condition is kept`() {
    val db =
        run(
            """
            var n = 0
            fun t(x: Int): Boolean { n += x; return x > 1 }
            val a = t(1) && t(5)
            val b = t(2) || t(100)
            db.Setting = n.toDouble()
            db.Ratio = if (a || !b) 1.0 else 0.0
            """
        )

    assertThat(db["Setting"]).isEqualTo(3.0)
    assertThat(db["Ratio"]).isEqualTo(0.0)
  }

  @Test
  fun `a global counted through mutual recursion is kept`() {
    val db =
        run(
            """
            var hits = 0
            fun a(n: Int): Int { hits += 1; return if (n <= 0) 0 else b(n - 1) + 1 }
            fun b(n: Int): Int { hits += 1; return if (n <= 0) 0 else c(n - 1) + 1 }
            fun c(n: Int): Int { hits += 1; return if (n <= 0) 0 else a(n - 1) + 1 }
            db.Setting = a(7).toDouble()
            db.Ratio = hits.toDouble()
            """,
            conventions = listOf(true),
        )

    assertThat(db["Setting"]).isEqualTo(7.0)
    assertThat(db["Ratio"]).isEqualTo(8.0)
  }

  @Test
  fun `a global written through an inlined or register function is not undone`() {
    val db =
        run(
            """
            var count = 0
            inline fun add(n: Int) { count += n }
            @InRegisters fun addTwice(n: Int) { add(n); add(n) }
            fun bump(n: Int): Int { addTwice(n); return count }
            val copy = count
            val a = bump(1)
            val b = bump(10)
            db.Setting = (copy * 10000 + a * 100 + b).toDouble()
            addTwice(0)
            db.Ratio = count.toDouble()
            """
        )

    assertThat(db["Setting"]).isEqualTo(222.0)
    assertThat(db["Ratio"]).isEqualTo(22.0)
  }

  @Test
  fun `a String argument is pushed packed by STR`() {
    val source =
        """
        fun show(msg: String, n: Double) {
          db.Setting = str(msg) + n
        }
        fun relay(msg: String) = show(msg, 1.0)
        show("foo", 0.0)
        relay("bar")
        relay("baz")
        """
    val db = run(source)

    assertThat(compiled(source).code).contains("push STR(\"foo\")")
    assertThat(db["Setting"]).isEqualTo(Hashes.str("baz") + 1.0)
  }

  @Test
  fun `a function may call itself on the stack`() {
    val db =
        run(
            """
            fun fact(n: Int): Int = if (n <= 1) 1 else n * fact(n - 1)
            fun fib(n: Int): Int = if (n < 2) n else fib(n - 1) + fib(n - 2)
            db.Setting = fact(10).toDouble()
            db.Ratio = fib(12).toDouble()
            """,
            conventions = listOf(true),
        )

    assertThat(db["Setting"]).isEqualTo(3628800.0)
    assertThat(db["Ratio"]).isEqualTo(144.0)
  }

  @Test
  fun `functions may call each other in a cycle on the stack`() {
    val source =
        """
        fun isEven(n: Int): Boolean = if (n == 0) true else isOdd(n - 1)
        fun isOdd(n: Int): Boolean = if (n == 0) false else isEven(n - 1)
        db.Setting = if (isEven(10)) 1.0 else 0.0
        db.Ratio = if (isEven(7)) 1.0 else 0.0
        """
    val db = run(source, conventions = listOf(true))

    assertThat(db["Setting"]).isEqualTo(1.0)
    assertThat(db["Ratio"]).isEqualTo(0.0)
    // isOdd is called once, but is never inlined, as it is in a cycle.
    assertThat(compiled(source).code).contains("jal isOdd")
  }

  @Test
  fun `a recursive function called once is not inlined`() {
    val source =
        """
        fun sum(n: Int): Int = if (n == 0) 0 else n + sum(n - 1)
        db.Setting = sum(4).toDouble()
        """

    assertThat(run(source, conventions = listOf(true))["Setting"]).isEqualTo(10.0)
    assertThat(compiled(source).code.lines().count { it == "jal sum" }).isEqualTo(2)
  }

  @Test
  fun `recursion deeper than the stack is a runtime error`() {
    val code =
        compiled(
                """
                fun depth(n: Int): Int = if (n == 0) 0 else 1 + depth(n - 1)
                db.Setting = depth(1000).toDouble()
                """
            )
            .code
    val emulator = Emulator(Program.parse(code), mapOf("db" to FakeDevice()))

    val error = assertThrows<Ic10RuntimeException> { emulator.runUntilHalt() }
    assertThat(error.message).contains("stack overflow")
  }

  @Test
  fun `register and inline functions mix with stack functions`() {
    val source =
        """
        inline fun sq(x: Double) = x * x
        @InRegisters fun hyp(a: Double, b: Double): Double = sqrt(sq(a) + sq(b))
        fun scaled(a: Double, b: Double, k: Double): Double = hyp(a, b) * k
        @InRegisters fun both(a: Double): Double = scaled(a, a, 1.0) + scaled(3.0, 4.0, a)
        db.Setting = 0.0
        val keep = db.Setting + 1.0
        db.Setting = scaled(3.0, 4.0, 2.0) + keep
        db.Ratio = both(1.0) + both(2.0) + keep
        db.Mode = hyp(6.0, 8.0) + keep
        """
            .replace("sqrt(", "kotlin.math.sqrt(")
    val db = run(source)

    val r2 = kotlin.math.sqrt(2.0)
    assertThat(db["Setting"]).isEqualTo(11.0)
    assertThat(db["Ratio"]).isWithin(1e-9).of((r2 + 5.0) + (2 * r2 + 10.0) + 1.0)
    assertThat(db["Mode"]).isEqualTo(11.0)
    val code = compiled(source).code
    // hyp and both keep their registers: their callers move arguments in, and push nothing.
    assertThat(code).contains("jal hyp")
    assertThat(code).contains("jal both")
    assertThat(code).contains("push 2\njal scaled")
    // The first call saves keep alone: hyp's and both's registers hold nothing there yet, though
    // flow through hyp, called in registers from both the program and scaled, makes them live.
    val first = code.lines().indexOf("jal scaled")
    assertThat(code.lines().subList(0, first).filter { it.matches(Regex("push r\\d+")) }).hasSize(1)
  }

  @Test
  fun `a cycle through a function passing arguments in registers is an error`() {
    val inRegisters =
        error(
            """
            @InRegisters fun a(n: Int): Int = if (n == 0) 0 else b(n - 1)
            fun b(n: Int): Int = a(n)
            db.Setting = a(3).toDouble()
            """
        )
    assertThat(inRegisters.message)
        .contains(
            "Recursive calls through a function passing arguments in registers are not " +
                "supported: a -> b -> a"
        )
    assertThat(inRegisters.message).contains("a is marked @InRegisters")
    assertThat(inRegisters.line to inRegisters.column).isEqualTo(3 to 22)

    val off =
        error(
            """
            fun a(n: Int): Int = if (n == 0) 0 else b(n - 1)
            fun b(n: Int): Int = a(n)
            db.Setting = a(3).toDouble()
            """,
            CompileOptions(stackCalls = false),
        )
    assertThat(off.message).contains("not supported: a -> b -> a (stack calls are turned off)")

    val self =
        error(
            "fun f(n: Int): Int = if (n < 1) 0 else f(n - 1)\ndb.Setting = f(3).toDouble()",
            CompileOptions(stackCalls = false),
        )
    assertThat(self.message).contains("f -> f")
    assertThat(self.line to self.column).isEqualTo(2 to 40)
  }

  @Test
  fun `a function in a cycle with a device parameter is an error naming the cycle`() {
    val error =
        error(
            """
            fun g(dev: Device, n: Int): Int = if (n == 0) 0 else g(dev, n - 1)
            db.Setting = g(db, 3).toDouble()
            """
        )

    assertThat(error.message)
        .isEqualTo(
            "g takes a device, view, substance or set and is in a cycle of calls (g -> g): a " +
                "function taking one must be inlined, which a function in a cycle of calls can't be"
        )
    assertThat(error.line).isEqualTo(3)
  }

  /** The size warning's clause for the lines compact output would save on [labelled]. */
  private fun compactSaves(source: String, labelled: CompileResult.Success): String {
    val saved = labelled.lines - compiled(source, CompileOptions(compact = true)).lines
    return "compact output, which resolves labels to line numbers, saves $saved lines"
  }

  @Test
  fun `a program over the line limit with stack calls is warned that registers save lines`() {
    fun calling(times: Int) =
        "fun f(x: Double): Double = x + 1.0\n" +
            (1..times).joinToString("\n") { "db.Setting = f($it.0)" }
    val source = calling(40)

    val stack = compiled(source)
    assertThat(stack.lines).isGreaterThan(128)
    assertThat(stack.warnings.map { it.message })
        .containsExactly(
            "The program is ${stack.lines} lines, over the chip's 128: " +
                "${compactSaves(source, stack)}; passing functions' " +
                "arguments in registers saves lines (turn stack calls off, or mark the most-called " +
                "functions @InRegisters)"
        )
    assertThat(stack.warnings.single().line).isEqualTo(0)
    // Nothing goes on the stack, so there is nothing to suggest, whatever the size.
    assertThat(compiled(source, CompileOptions(stackCalls = false)).warnings).isEmpty()
    assertThat(compiled("@InRegisters $source").warnings).isEmpty()
    // A program that fits isn't warned.
    assertThat(compiled(calling(3)).warnings).isEmpty()
  }

  private val factorial = "fun fact(n: Double): Double = if (n <= 1.0) 1.0 else n * fact(n - 1.0)\n"

  @Test
  fun `a program over the line limit whose stack calls are all recursive is told to shorten`() {
    val source = factorial + (1..60).joinToString("\n") { "db.Setting = fact($it.0)" }
    val stack = compiled(source)

    assertThat(stack.lines).isGreaterThan(128)
    assertThat(stack.warnings.map { it.message })
        .containsExactly(
            "The program is ${stack.lines} lines, over the chip's 128: " +
                "${compactSaves(source, stack)}; the functions called on " +
                "the stack (fact) are in a cycle of calls, so they can't pass their arguments in " +
                "registers to save lines, and the program must be shortened or compiled compact"
        )
    // Compiled compact, shortening is all that is left.
    val compact = compiled(source, CompileOptions(compact = true))
    assertThat(compact.lines).isGreaterThan(128)
    assertThat(compact.warnings.map { it.message })
        .containsExactly(
            "The program is ${compact.lines} lines, over the chip's 128: the functions called on " +
                "the stack (fact) are in a cycle of calls, so they can't pass their arguments in " +
                "registers to save lines, and the program must be shortened"
        )
  }

  @Test
  fun `a labelled program over the line limit is told what compact output saves`() {
    val source =
        "var i = 0\nwhile (i < db.Mode.toInt()) { i++ }\n" +
            (1..38).joinToString("\n") { "db.Setting = db.Mode + $it.0" }

    val labelled = compiled(source)
    assertThat(labelled.lines).isGreaterThan(128)
    assertThat(labelled.warnings.map { it.message })
        .containsExactly(
            "The program is ${labelled.lines} lines, over the chip's 128: " +
                compactSaves(source, labelled)
        )
    // Compact output that fits isn't warned.
    val compact = compiled(source, CompileOptions(compact = true))
    assertThat(compact.lines).isAtMost(128)
    assertThat(compact.warnings).isEmpty()
  }

  @Test
  fun `a program over the line limit with some recursive stack calls is told which to change`() {
    val plain = "fun f(x: Double): Double = x + 1.0\nfun g(x: Double): Double = x * 2.0\n"
    fun calls(vararg functions: String) =
        (1..30).joinToString("\n") { i ->
          functions.joinToString("\n") { "db.Setting = $it($i.0)" }
        }

    val oneSource = factorial + plain.lines().first() + "\n" + calls("f", "fact")
    val one = compiled(oneSource)
    assertThat(one.lines).isGreaterThan(128)
    assertThat(one.warnings.map { it.message })
        .containsExactly(
            "The program is ${one.lines} lines, over the chip's 128: " +
                "${compactSaves(oneSource, one)}; passing functions' arguments " +
                "in registers saves lines (mark f @InRegisters; functions in a cycle of calls must " +
                "pass theirs on the stack)"
        )

    val twoSource = factorial + plain + calls("f", "g", "fact")
    val two = compiled(twoSource)
    assertThat(two.lines).isGreaterThan(128)
    assertThat(two.warnings.map { it.message })
        .containsExactly(
            "The program is ${two.lines} lines, over the chip's 128: " +
                "${compactSaves(twoSource, two)}; passing functions' arguments " +
                "in registers saves lines (mark the most-called of f, g @InRegisters; functions in " +
                "a cycle of calls must pass theirs on the stack)"
        )
    // The advice compiles: the functions it names may pass their arguments in registers.
    val marked = compiled(factorial + "@InRegisters " + plain + calls("f", "fact"))
    assertThat(marked.lines).isLessThan(one.lines)
  }

  @Test
  fun `a program over the line limit that declares a cycle is not told to turn stack calls off`() {
    val plain = "fun f(x: Double): Double = x + 1.0\n"
    val calls = (1..60).joinToString("\n") { "db.Setting = f($it.0)" }

    for (cycle in listOf(factorial, factorial + "if (false) db.Mode = fact(3.0)\n")) {
      val source = cycle + plain + calls
      val stack = compiled(source)
      assertWithMessage(source).that(stack.lines).isGreaterThan(128)
      assertWithMessage(source)
          .that(stack.warnings.map { it.message })
          .containsExactly(
              "The program is ${stack.lines} lines, over the chip's 128: " +
                  "${compactSaves(source, stack)}; passing functions' " +
                  "arguments in registers saves lines (mark f @InRegisters; functions in a cycle " +
                  "of calls must pass theirs on the stack)"
          )
      // Turning stack calls off would fail on the cycle, called or not; the advice given compiles.
      assertWithMessage(source)
          .that(error(source, CompileOptions(stackCalls = false)).message)
          .contains("Recursive calls through a function passing arguments in registers")
      val marked = compiled(cycle + "@InRegisters " + plain + calls)
      assertWithMessage(source).that(marked.lines).isLessThan(stack.lines)
    }
  }

  @Test
  fun `an inline function in a cycle is an error`() {
    for (source in
        listOf(
            """
            inline fun a(n: Int): Int = if (n == 0) 0 else b(n - 1)
            fun b(n: Int): Int = a(n)
            db.Setting = a(3).toDouble()
            """,
            """
            fun b(n: Int): Int = a(n)
            inline fun a(n: Int): Int = if (n == 0) 0 else b(n - 1)
            db.Setting = a(3).toDouble()
            """,
        )) {
      val error = error(source)
      assertWithMessage(source).that(error.message).contains("a is in a cycle of calls")
      assertWithMessage(source).that(error.message).contains("can't be inline")
    }
  }
}
