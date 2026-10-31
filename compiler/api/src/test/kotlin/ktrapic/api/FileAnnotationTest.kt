package ktrapic.api

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.jupiter.api.Test

/**
 * The file annotations that choose how a program compiles: `@file:Compact`, `@file:InRegisters` and
 * `@file:NoTailCalls`. Each compiles a program as the [CompileOptions] it stands for does, and
 * overrides the options it is compiled with.
 */
class FileAnnotationTest {
  private val cases =
      File(System.getenv("TEST_SRCDIR"), System.getenv("TEST_WORKSPACE") + "/test/cases")

  private fun compile(source: String, options: CompileOptions = CompileOptions()): String =
      when (val result = Compiler.compile(source, options)) {
        is CompileResult.Success -> result.code
        is CompileResult.Failure -> result.errors.joinToString("\n") { "error: ${it.message}" }
      }

  private fun compiled(source: String, options: CompileOptions = CompileOptions()) =
      when (val result = Compiler.compile(source, options)) {
        is CompileResult.Success -> result
        is CompileResult.Failure -> throw AssertionError("compile failed: ${result.errors}")
      }

  private val annotations =
      listOf(
          "@file:Compact" to CompileOptions(compact = true),
          "@file:InRegisters" to CompileOptions(stackCalls = false),
          "@file:NoTailCalls" to CompileOptions(tailCalls = false),
      )

  /**
   * [source] with each of [annotations] it lacks written above it. A case may carry one itself, and
   * none may be written twice.
   */
  private fun annotated(source: String, vararg annotations: String) =
      annotations.filterNot { it in source.lines() }.joinToString("") { "$it\n" } + source

  @Test
  fun `each annotation compiles every case as its option does`() {
    val sources = cases.listFiles { file -> file.name.endsWith(".ic10.kts") }.orEmpty().sorted()
    assertThat(sources).isNotEmpty()
    for (file in sources) {
      val source = file.readText()
      for ((annotation, options) in annotations) {
        assertWithMessage("%s with %s", file.name, annotation)
            .that(compile(annotated(source, annotation)))
            .isEqualTo(compile(source, options))
      }
      assertWithMessage("%s with all three", file.name)
          .that(compile(annotated(source, *annotations.map { it.first }.toTypedArray())))
          .isEqualTo(compile(source, CompileOptions(true, stackCalls = false, tailCalls = false)))
    }
  }

  @Test
  fun `each annotation changes the program it is written in`() {
    val f = "fun f(x: Double): Double = x + 1.0\ndb.Setting = f(db.Setting) + f(db.Mode)\n"
    assertThat(compile(f)).contains("f:")
    assertThat(compile("@file:Compact\n$f")).doesNotContain("f:")
    assertThat(compile(f)).contains("push")
    assertThat(compile("@file:InRegisters\n$f")).doesNotContain("push")

    val tail =
        "fun count(n: Int, steps: Int): Int = if (n == 0) steps else count(n - 1, steps + 1)\n" +
            "db.Mode = count(db.Setting.toInt(), 0).toDouble()\n" +
            "db.Ratio = count(db.Mode.toInt(), 1).toDouble()\n"
    assertThat(compile(tail).lines().count { it == "jal count" }).isEqualTo(2)
    assertThat(compile("@file:NoTailCalls\n$tail").lines().count { it == "jal count" }).isEqualTo(3)
  }

  @Test
  fun `annotations override the options a program is compiled with`() {
    val f = "fun f(x: Double): Double = x + 1.0\ndb.Setting = f(db.Setting) + f(db.Mode)\n"
    assertThat(compile("@file:Compact\n$f", CompileOptions(compact = false)))
        .isEqualTo(compile(f, CompileOptions(compact = true)))
    assertThat(compile("@file:InRegisters\n$f", CompileOptions(stackCalls = true)))
        .isEqualTo(compile(f, CompileOptions(stackCalls = false)))
  }

  @Test
  fun `annotations go after other file annotations and before imports`() {
    val f = "fun f(x: Double): Double = x + 1.0\ndb.Setting = f(db.Setting) + f(db.Mode)\n"
    val expected = compile(f, CompileOptions(compact = true, stackCalls = false))
    assertThat(
            compile("// A note.\n@file:Suppress(\"UNUSED\")\n@file:Compact\n@file:InRegisters\n$f")
        )
        .isEqualTo(expected)
    assertThat(compile("@file:Compact @file:InRegisters\nimport kotlin.math.*\n$f"))
        .isEqualTo(expected)
  }

  @Test
  fun `a function marked InRegisters still passes in registers alone`() {
    val source =
        "fun f(x: Double): Double = x + 1.0\n" +
            "@InRegisters fun g(x: Double): Double = x * 2.0\n" +
            "db.Setting = f(db.Setting) + f(db.Mode) + g(db.Setting) + g(db.Mode)\n"
    val code = compile(source).lines()
    val g = code.indexOf("g:")
    val f = code.indexOf("f:")
    assertThat(g).isAtLeast(0)
    assertThat(f).isAtLeast(0)
    // f pops its argument from the stack; g, in registers, pops nothing.
    val body = { start: Int -> code.drop(start + 1).takeWhile { !it.endsWith(":") } }
    assertThat(body(f).any { it.startsWith("pop") }).isTrue()
    assertThat(body(g).any { it.startsWith("pop") }).isFalse()
    // With the whole file in registers, f pops nothing either.
    assertThat(compile("@file:InRegisters\n$source")).doesNotContain("pop")
  }

  @Test
  fun `a recursive program annotated InRegisters says why it fails`() {
    val error =
        compile(
            "@file:InRegisters\n" +
                "fun a(n: Int): Int = if (n == 0) 0 else b(n - 1)\n" +
                "fun b(n: Int): Int = a(n)\n" +
                "db.Setting = a(3).toDouble()\n"
        )
    assertThat(error)
        .contains(
            "a -> b -> a (every function passes them in registers, as with @file:InRegisters)"
        )
  }

  @Test
  fun `warnings name the annotations to add`() {
    val source =
        "fun f(x: Double): Double = x + 1.0\n" +
            (1..40).joinToString("\n") { "db.Setting = f($it.0)" }
    val warning = compiled(source).warnings.single().message
    assertThat(warning).contains("compact output (@file:Compact)")
    assertThat(warning).contains("(add @file:InRegisters, or mark the most-called functions")
    // Annotated, the program is compact and passes in registers, so neither is suggested.
    val annotated = compiled("@file:Compact\n@file:InRegisters\n$source")
    for (left in annotated.warnings) assertThat(left.message).doesNotContain("@file:")
  }
}
