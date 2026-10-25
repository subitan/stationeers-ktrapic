package ktrapic.frontend

import com.google.common.truth.Truth.assertThat
import org.jetbrains.kotlin.ir.util.dump
import org.junit.jupiter.api.Test

class K2FrontendTest {
  private val script =
      """
      val limit = 10
      fun square(x: Int): Int = x * x
      var total = 0
      for (i in 1..limit) total += square(i)
      println(total)
      """
          .trimIndent()

  @Test
  fun `a script compiles to IR with its statements in a program object`() {
    val result = K2Frontend.compile(script) { it.dump() }

    assertThat(result.diagnostics.filter { it.severity == Diagnostic.Severity.ERROR }).isEmpty()
    assertThat(result).isInstanceOf(FrontendResult.Success::class.java)
    val dump = (result as FrontendResult.Success).value
    assertThat(dump).contains("CLASS OBJECT name:Program")
    assertThat(dump).contains("PROPERTY name:limit")
    assertThat(dump).contains("FUN name:square")
    assertThat(dump).contains("ANONYMOUS_INITIALIZER")
    assertThat(dump).contains("CALL 'public final fun square")
  }

  @Test
  fun `a type error is reported on the user's line`() {
    val source = "val ok = 1\nval bad: Int = \"text\"\n"
    val result = K2Frontend.compile(source) { it.dump() }

    assertThat(result).isInstanceOf(FrontendResult.Failure::class.java)
    val error = result.diagnostics.first { it.severity == Diagnostic.Severity.ERROR }
    assertThat(error.line).isEqualTo(2)
    assertThat(error.column).isIn(1..source.lines()[1].length)
  }

  @Test
  fun `an error inside a wrapped statement maps back to the original column`() {
    // Line 2 is compiled as `init { n += "one" }`; K2 reports the mismatch on the `+=`
    // operator, which is column 3 of the user's line (column 10 of the generated one).
    val result = K2Frontend.compile("var n = 0\nn += \"one\"\n") { it.dump() }

    assertThat(result).isInstanceOf(FrontendResult.Failure::class.java)
    val error = result.diagnostics.first { it.severity == Diagnostic.Severity.ERROR }
    assertThat(error.line).isEqualTo(2)
    assertThat(error.column).isEqualTo(3)
  }

  @Test
  fun `a missing closing brace is reported in the user's source`() {
    // The parser places the error after the last token, at the end of the unclosed line.
    val result = K2Frontend.compile("val a = 1\nfun f() {\n") { it.dump() }

    assertThat(result).isInstanceOf(FrontendResult.Failure::class.java)
    assertThat(result.diagnostics)
        .containsExactly(
            Diagnostic(Diagnostic.Severity.ERROR, "Syntax error: Expecting '}'", 2, 10)
        )
  }

  @Test
  fun `a stray closing brace is reported at the brace`() {
    // The parser also flags the statements after the brace; the first error is the brace itself.
    val result = K2Frontend.compile("val a = 1\n}\nprintln(a)\n") { it.dump() }

    assertThat(result).isInstanceOf(FrontendResult.Failure::class.java)
    assertThat(result.diagnostics.first())
        .isEqualTo(Diagnostic(Diagnostic.Severity.ERROR, "Syntax error: Unexpected symbol", 2, 1))
  }

  @Test
  fun `syntax errors are reported alongside unsupported constructs`() {
    val result = K2Frontend.compile("val (a, b) = Pair(1, 2)\nfun f( {\n") { it.dump() }

    assertThat(result).isInstanceOf(FrontendResult.Failure::class.java)
    assertThat(result.diagnostics.map { it.line }).contains(1)
    assertThat(result.diagnostics.map { it.line }).contains(2)
  }

  @Test
  fun `a top-level destructuring declaration is rejected in script terms`() {
    val result = K2Frontend.compile("val (a, b) = Pair(1, 2)\nprintln(a + b)\n") { it.dump() }

    assertThat(result).isInstanceOf(FrontendResult.Failure::class.java)
    assertThat(result.diagnostics)
        .containsExactly(
            Diagnostic(
                Diagnostic.Severity.ERROR,
                "Top-level destructuring declarations are not supported.",
                1,
                1,
            )
        )
  }
}
