package ktrapic.api

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import ktrapic.emu.Emulator
import ktrapic.emu.FakeDevice
import ktrapic.emu.Ic10RuntimeException
import ktrapic.ic10.Program
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Programs annotated `@file:CircuitHousingOnly`, which keep the values that don't fit in registers
 * in the chip's stack memory, from address 511 down. The emulator's `db` memory is the chip's
 * stack, as in a circuit housing.
 */
class CircuitHousingTest {
  private val annotated = "@file:CircuitHousingOnly\n"

  private fun compile(source: String, options: CompileOptions = CompileOptions()) =
      Compiler.compile(source.trimIndent(), options)

  private fun compiled(source: String, options: CompileOptions = CompileOptions()) =
      when (val result = compile(source, options)) {
        is CompileResult.Success -> result
        is CompileResult.Failure -> throw AssertionError("compile failed: ${result.errors}")
      }

  private fun failure(source: String, options: CompileOptions = CompileOptions()) =
      compile(source, options) as? CompileResult.Failure
          ?: throw AssertionError("expected an error:\n${compile(source, options)}")

  /** Runs [code] to the end with [db] as the housing; it must leave the stack as it found it. */
  private fun execute(code: String, db: FakeDevice = FakeDevice()): Map<String, Double> {
    val emulator = Emulator(Program.parse(code), mapOf("db" to db)).runUntilHalt()
    assertWithMessage(code).that(emulator.register("sp")).isEqualTo(0.0)
    return db.logic.toMap()
  }

  /**
   * Runs [source] to the end, with and without compact output, and returns the housing's logic
   * types, which both runs must leave the same. [inputs] are the housing's values to start with.
   */
  private fun run(source: String, inputs: Map<String, Double> = INPUTS): Map<String, Double> {
    val results =
        listOf(false, true).map { compact ->
          val result = compiled(source, CompileOptions(compact = compact))
          assertWithMessage(result.code).that(result.registers).isAtMost(16)
          execute(result.code, FakeDevice().also { it.logic += inputs })
        }
    assertThat(results[1]).isEqualTo(results[0])
    return results[0]
  }

  /** The `get`s and `put`s of [code] on `db`, by the address they use. */
  private fun memoryUse(code: String): Set<Int> =
      code
          .lines()
          .mapNotNull { Regex("""^(?:get r\d+ db|put db) (\d+)\b""").find(it) }
          .map { it.groupValues[1].toInt() }
          .toSet()

  /** Values from 1 to [count], all live at once, read from the housing: no constant folds them. */
  private fun crowded(count: Int, indent: String = ""): String =
      (1..count).joinToString("\n") { "${indent}val a$it = x + $it.0" } +
          "\n${indent}val total = " +
          (1..count step 2).joinToString(" + ") { if (it < count) "a$it * a${it + 1}" else "a$it" }

  /** The same sum as [crowded], with each value computed where it is used: a few registers. */
  private fun inline(count: Int): String =
      (1..count step 2).joinToString(" + ") {
        if (it < count) "(x + $it.0) * (x + ${it + 1}.0)" else "(x + $it.0)"
      }

  @Test
  fun `a program with 17 to 21 values live at once runs with the values that don't fit in memory`() {
    for (count in 17..21) {
      val source =
          """
          |val x = db.Setting
          |${crowded(count)}
          |db.Ratio = total
          """
              .trimMargin()
      val restructured = "val x = db.Setting\ndb.Ratio = ${inline(count)}\n"
      val expected = run(restructured)

      val failure = failure(source)
      assertThat(failure.errors.single().message)
          .matches(
              "The program needs \\d+ registers at once; the chip has 16 \\(r0–r15\\); if the " +
                  "chip will run in an IC housing, a rocket circuit housing or a suit, add " +
                  "@file:CircuitHousingOnly to keep the values that don't fit in its stack memory"
          )
      assertThat(run(annotated + source)).isEqualTo(expected)
      val spilled = compiled(annotated + source)
      println("count=$count lines=${spilled.lines} registers=${spilled.registers}")
      assertThat(memoryUse(spilled.code)).contains(511)
    }
  }

  @Test
  fun `values in memory inside a loop`() {
    fun program(sum: String) =
        """
        |val x0 = db.Setting
        |var acc = 0.0
        |var i = 0
        |while (i < 4) {
        |  val x = x0 + i
        |$sum
        |  acc += total
        |  i++
        |}
        |db.Ratio = acc
        """
            .trimMargin()
    val source = program(crowded(18, "  "))
    val expected = run(program("  val total = ${inline(18)}"))

    assertThat(failure(source).errors).isNotEmpty()
    assertThat(run(annotated + source)).isEqualTo(expected)
    report("loop", annotated + source)
  }

  @Test
  fun `values in memory inside a function called on the stack`() {
    fun program(body: String) =
        """
        |fun busy(x: Double): Double {
        |$body
        |  return total
        |}
        |db.Ratio = busy(db.Setting) + busy(db.Mode)
        """
            .trimMargin()
    val source = program(crowded(18, "  "))
    val expected = run(program("  val total = ${inline(18)}"))

    assertThat(failure(source).errors).isNotEmpty()
    val code = compiled(annotated + source).code
    assertThat(code).contains("jal busy")
    assertThat(run(annotated + source)).isEqualTo(expected)
    report("function", annotated + source)
  }

  @Test
  fun `values in memory across a call on the stack are not pushed`() {
    fun program(values: String, sum: String) =
        """
        |fun f(y: Double): Double = y * y + 1.0
        |val x = db.Setting
        |$values
        |val p = f(x + db.Mode)
        |val q = f(p)
        |db.Ratio = $sum + p + q
        """
            .trimMargin()
    val names = (1..18).joinToString(" + ") { "a$it" }
    val source = program((1..18).joinToString("\n") { "val a$it = x + $it.0" }, names)
    val expected = run(program("", (1..18).joinToString(" + ") { "(x + $it.0)" }))

    assertThat(failure(source).errors).isNotEmpty()
    val code = compiled(annotated + source).code
    assertThat(run(annotated + source)).isEqualTo(expected)
    // Each call pushes what it keeps in registers, its argument among them: never all 18 values.
    val pushes =
        code.lines().fold(listOf(0)) { runs, line ->
          when {
            line.startsWith("push") -> runs.dropLast(1) + (runs.last() + 1)
            line.startsWith("jal") -> runs + 0
            else -> runs
          }
        }
    assertThat(pushes.max()).isLessThan(16)
    report("across a call", annotated + source)
  }

  @Test
  fun `values in memory in a function whose tail call is a loop`() {
    fun program(body: String) =
        """
        |fun walk(n: Int, acc: Double, x: Double): Double {
        |  if (n == 0) return acc
        |$body
        |  return walk(n - 1, acc + total, x)
        |}
        |db.Ratio = walk(db.Mode.toInt(), 0.0, db.Setting)
        |db.Setting = walk(1, 1.0, 2.0)
        """
            .trimMargin()
    val source = program(crowded(18, "  "))
    val expected = run(program("  val total = ${inline(18)}"))

    assertThat(run(annotated + source)).isEqualTo(expected)
    val code = compiled(annotated + source).code
    assertThat(code.lines().count { it == "jal walk" }).isEqualTo(2)
    assertThat(code).contains("j walk_loop")
    // A loop takes no stack, so it runs far deeper than the stack's 512 values.
    val deep =
        execute(code, FakeDevice().also { it.logic += mapOf("Setting" to 0.5, "Mode" to 2000.0) })
    assertThat(deep["Ratio"]).isNotNull()
    report("tail loop", annotated + source)
  }

  /**
   * A program with [globals] top-level properties, all live across the calls to a function whose
   * tail call swaps its arguments, which takes a register more as a loop than as a call.
   */
  private fun crowdedGlobals(globals: Int): String {
    val names = (1..globals).map { "g$it" }
    return names.withIndex().joinToString("\n") { (i, g) -> "var $g = d0.Setting + ${i + 1}" } +
        "\nfun fib(n: Int, a: Int, b: Int): Int = if (n == 0) a else fib(n - 1, b, a + b)\n" +
        "fun main() {\n" +
        "  db.Setting = fib(d0.Setting.toInt(), 0, 1).toDouble() + ${names.joinToString(" + ")}\n" +
        names.joinToString("\n") { "  $it += 1.0" } +
        "\n}\n" +
        "main(); main()\n"
  }

  @Test
  fun `out of registers with tail calls as loops, memory keeps them loops, without a warning`() {
    for (globals in listOf(13, 14)) {
      val source = crowdedGlobals(globals)
      val plain = compile(source)
      val spilled = compiled(annotated + source)

      assertThat(spilled.code).contains("j fib_loop")
      assertThat(spilled.warnings).isEmpty()
      assertThat(memoryUse(spilled.code)).isNotEmpty()
      fun execute(code: String) =
          FakeDevice().also { db ->
            val d0 = FakeDevice().also { it["Setting"] = 10.0 }
            Emulator(Program.parse(code), mapOf("db" to db, "d0" to d0)).runUntilHalt()
          }
      // Unannotated, 13 globals fit with the tail calls made as calls, with a warning; 14 don't.
      if (globals == 13) {
        assertThat((plain as CompileResult.Success).warnings).isNotEmpty()
        assertThat(execute(spilled.code).logic).isEqualTo(execute(plain.code).logic)
      } else {
        assertThat(plain).isInstanceOf(CompileResult.Failure::class.java)
        // fib(10), and each global on the second call: d0's 10, plus its number, plus 1.
        assertThat(execute(spilled.code)["Setting"]).isEqualTo(55.0 + (1..14).sumOf { 11.0 + it })
      }
    }
  }

  @Test
  fun `the first line reads memory only when the program uses memory`() {
    val fits = "val x = db.Setting\n${crowded(8)}\ndb.Ratio = total\n"
    val crowdedSource = "val x = db.Setting\n${crowded(18)}\ndb.Ratio = total\n"

    for (compact in listOf(false, true)) {
      val options = CompileOptions(compact = compact)
      val plain = compiled(fits, options)
      val marked = compiled(annotated + fits, options)
      assertThat(marked.code).isEqualTo(plain.code)
      assertThat(memoryUse(marked.code)).isEmpty()

      val spilled = compiled(annotated + crowdedSource, options).code
      assertThat(spilled.lines().first()).matches("get r\\d+ db 511")
      assertThat(spilled.lines().count { Regex("get r\\d+ db 511").matches(it) }).isAtLeast(2)
    }
  }

  @Test
  fun `the first line's register holds nothing the program needs`() {
    // The register the check reads into is written before it is read anywhere else.
    val source = annotated + "val x = db.Setting\n${crowded(18)}\ndb.Ratio = total\n"
    val code = compiled(source).code.lines()
    val register = code.first().split(" ")[1]
    val next = code.drop(1).first { register in it.split(" ") }
    assertThat(next.split(" ")[1]).isEqualTo(register)
  }

  /**
   * A recursive sum called through `g`, which isn't recursive, while 18 values are live: those kept
   * in memory stay there across the call, deep as the recursion goes.
   */
  private val recursive =
      """
      |fun sum(n: Int): Int = if (n == 0) 0 else n + sum(n - 1)
      |fun g(n: Int): Int = sum(n) + 1
      |val x = db.Setting
      |${(1..18).joinToString("\n") { "val a$it = x + $it.0" }}
      |val s = g(db.Mode.toInt())
      |val t = g(2)
      |db.Ratio = ${(1..18).joinToString(" + ") { "a$it" }} + s + t
      """
          .trimMargin()

  @Test
  fun `a recursion that fits beside the memory in use runs as it would`() {
    val expected =
        run(
            "fun sum(n: Int): Int = if (n == 0) 0 else n + sum(n - 1)\n" +
                "val x = db.Setting\n" +
                "db.Ratio = ${(1..18).joinToString(" + ") { "(x + $it.0)" }} + " +
                "(sum(db.Mode.toInt()) + 1) + (sum(2) + 1)\n",
            mapOf("Setting" to 0.5, "Mode" to 100.0),
        )

    assertThat(run(annotated + recursive, mapOf("Setting" to 0.5, "Mode" to 100.0)))
        .isEqualTo(expected)
    val code = compiled(annotated + recursive).code
    assertThat(code.lines().filter { it.startsWith("bgt sp ") }).hasSize(1)
    assertThat(code.lines()).contains("hcf")
    report("recursion", annotated + recursive)
  }

  @Test
  fun `values in memory in a recursive function, and across a call into it from outside`() {
    fun program(body: String, values: String, sum: String) =
        """
        |fun deep(n: Int, x: Double): Double {
        |  if (n == 0) return 0.0
        |$body
        |  return total + deep(n - 1, x)
        |}
        |val x = db.Setting
        |$values
        |val d = deep(db.Mode.toInt(), x)
        |db.Ratio = $sum + d
        """
            .trimMargin()
    val source =
        program(
            crowded(18, "  "),
            (1..18).joinToString("\n") { "val b$it = x * $it.0" },
            (1..18).joinToString(" + ") { "b$it" },
        )
    val expected =
        run(
            program(
                "  val total = ${inline(18)}",
                "",
                (1..18).joinToString(" + ") { "(x * $it.0)" },
            )
        )

    assertThat(failure(source).errors).isNotEmpty()
    assertThat(run(annotated + source)).isEqualTo(expected)
    // `deep` keeps `total` across its call to itself in a register, pushed and popped around it.
    val code = compiled(annotated + source).code
    assertThat(code.lines().filter { it.startsWith("bgt sp ") }).hasSize(1)
    report("recursive function", annotated + source)
  }

  @Test
  fun `a recursion deep enough to reach the memory in use stops the chip before it does`() {
    // `main` pushes 12 values and an argument, and `g` its `ra` and an argument, so `sum` is first
    // entered with sp 14, and each level deeper adds 2: at depth 247, the innermost call pops its
    // argument and pushes `ra` at 507, an address in use (506 is the lowest), though the stack's
    // top, 512, is still beyond it.
    val depth = 247
    val inputs = mapOf("Setting" to 0.5, "Mode" to depth.toDouble())
    val expected = (1..18).sumOf { 0.5 + it } + (depth * (depth + 1) / 2 + 1) + 4.0
    for (compact in listOf(false, true)) {
      val code = compiled(annotated + recursive, CompileOptions(compact = compact)).code
      assertThat(memoryUse(code).min()).isEqualTo(506)

      val error =
          assertThrows<Ic10RuntimeException> {
            Emulator(Program.parse(code), mapOf("db" to FakeDevice().also { it.logic += inputs }))
                .runUntilHalt()
          }
      assertThat(error.message).contains("hcf")

      // Without its check, the same recursion overwrites a value in memory, unnoticed.
      val unchecked = code.lines().filterNot { it.startsWith("bgt sp ") }.joinToString("\n")
      if (!compact) {
        val db = FakeDevice().also { it.logic += inputs }
        Emulator(Program.parse(unchecked), mapOf("db" to db)).runUntilHalt()
        assertThat(db["Ratio"]).isNotEqualTo(expected)
      }
      // Two levels less, it fits.
      val shallower = FakeDevice().also { it.logic += inputs + ("Mode" to depth - 2.0) }
      Emulator(Program.parse(code), mapOf("db" to shallower)).runUntilHalt()
      assertThat(shallower["Ratio"]).isEqualTo(expected - depth - (depth - 1))
    }
  }

  @Test
  fun `a program without recursion, or that fits, gets no stack check`() {
    val noRecursion = annotated + "val x = db.Setting\n${crowded(18)}\ndb.Ratio = total\n"
    val fits =
        annotated +
            "fun sum(n: Int): Int = if (n == 0) 0 else n + sum(n - 1)\n" +
            "db.Ratio = sum(db.Mode.toInt()).toDouble() + sum(3)\n"

    for (source in listOf(noRecursion, fits)) {
      val code = compiled(source).code
      assertThat(code).doesNotContain("hcf")
      assertThat(code).doesNotContain("bgt sp")
    }
  }

  @Test
  fun `the annotation goes only at the top of the file`() {
    val body = "val x = db.Setting\ndb.Ratio = x\n"
    // Before the imports, after a comment, or with no imports at all.
    for (source in
        listOf(
            annotated + "import ktrapic.*\n" + body,
            "// A housing program.\n" + annotated + body,
            annotated + body,
            "@file:CircuitHousingOnly @file:Suppress(\"UNUSED\")\n" + body,
        )) {
      assertWithMessage(source)
          .that(compile(source))
          .isInstanceOf(CompileResult.Success::class.java)
    }
    for (source in
        listOf(
            "@CircuitHousingOnly fun f() = 1\n" + body,
            "@CircuitHousingOnly val y = 1.0\n" + body,
            body + annotated,
            "val y = 1.0\n" + annotated + body,
        )) {
      assertWithMessage(source)
          .that(compile(source))
          .isInstanceOf(CompileResult.Failure::class.java)
    }
  }

  /** Prints [source]'s size, compiled and compacted, for the record. */
  private fun report(name: String, source: String) {
    val labelled = compiled(source)
    val compact = compiled(source, CompileOptions(compact = true))
    println(
        "$name: lines=${labelled.lines} compact=${compact.lines} " +
            "registers=${labelled.registers} slots=${memoryUse(labelled.code).size}"
    )
  }

  companion object {
    private val INPUTS = mapOf("Setting" to 0.5, "Mode" to 3.0)
  }
}
