package ktrapic.frontend

import com.google.common.truth.Truth.assertThat
import java.io.File
import ktrapic.Device
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
    val result = K2Frontend.compile(script) { it.module.dump() }

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
  fun `the extra checkers warn of an unused lambda parameter, on the user's line`() {
    val result = K2Frontend.compile("var n = 0\nlistOf(1, 2).forEach { x -> n++ }\nprintln(n)\n") {}

    val warning = result.diagnostics.single()
    assertThat(warning.severity).isEqualTo(Diagnostic.Severity.WARNING)
    assertThat(warning.message).contains("Parameter 'x")
    assertThat(warning.line to warning.column).isEqualTo(2 to 24)
  }

  @Test
  fun `warnings are in source order with the top-level properties' among them`() {
    val result = K2Frontend.compile("val a = 1\nfun f() {\n  val b = 2\n}\nval c = 3\nf()\n") {}

    assertThat(result.diagnostics.map { it.line to it.column })
        .containsExactly(1 to 5, 3 to 7, 5 to 5)
        .inOrder()
  }

  @Test
  fun `a script with Windows line endings compiles`() {
    val result = K2Frontend.compile(script.replace("\n", "\r\n") + "\r\n") { it.module.dump() }

    assertThat(result.diagnostics.filter { it.severity == Diagnostic.Severity.ERROR }).isEmpty()
    assertThat(result).isInstanceOf(FrontendResult.Success::class.java)
  }

  @Test
  fun `a type error in a script with Windows line endings is reported on the user's line`() {
    val result =
        K2Frontend.compile("val ok = 1\r\nval bad: Int = \"text\"\r\n") { it.module.dump() }

    assertThat(result).isInstanceOf(FrontendResult.Failure::class.java)
    val error = result.diagnostics.single { it.severity == Diagnostic.Severity.ERROR }
    assertThat(error.line).isEqualTo(2)
    assertThat(error.message).doesNotContain("Syntax error")
  }

  @Test
  fun `a script starting with a byte-order mark compiles`() {
    val result = K2Frontend.compile(0xFEFF.toChar() + "println(1)\n") { it.module.dump() }

    assertThat(result.diagnostics.filter { it.severity == Diagnostic.Severity.ERROR }).isEmpty()
    assertThat(result).isInstanceOf(FrontendResult.Success::class.java)
  }

  @Test
  fun `a type error is reported on the user's line`() {
    val source = "val ok = 1\nval bad: Int = \"text\"\n"
    val result = K2Frontend.compile(source) { it.module.dump() }

    assertThat(result).isInstanceOf(FrontendResult.Failure::class.java)
    val error = result.diagnostics.first { it.severity == Diagnostic.Severity.ERROR }
    assertThat(error.line).isEqualTo(2)
    assertThat(error.column).isIn(1..source.lines()[1].length)
  }

  private val sdkJar = File(Device::class.java.protectionDomain.codeSource.location.toURI())

  /** The first error compiling [source] against the SDK, as a program is compiled. */
  private fun errorFor(source: String): String {
    val result =
        K2Frontend.compile(source, listOf(sdkJar), listOf("ktrapic.*")) { it.module.dump() }
    assertThat(result).isInstanceOf(FrontendResult.Failure::class.java)
    return result.diagnostics.first { it.severity == Diagnostic.Severity.ERROR }.message
  }

  @Test
  fun `a family member written alone is suggested with its family`() {
    assertThat(errorFor("val e = StirlingEngine.at(d0)\n"))
        .isEqualTo("Unresolved reference 'StirlingEngine'. Did you mean Generator.StirlingEngine?")
    assertThat(errorFor("val e = StructureStirlingEngine.at(d0)\n"))
        .endsWith("Did you mean Generator.StirlingEngine?")
    assertThat(errorFor("val e = SolidFuel.at(d0)\n")).endsWith("Did you mean Generator.SolidFuel?")
  }

  @Test
  fun `a family member written alone under a comment ending in a full stop is suggested`() {
    val hint = "Unresolved reference 'StirlingEngine'. Did you mean Generator.StirlingEngine?"
    assertThat(
            errorFor("while (true) {\n    // Turn the engine on.\n    StirlingEngine.at(d0)\n}\n")
        )
        .isEqualTo(hint)
    assertThat(errorFor("fun f() {\n    // Turn the engine on.\n    StirlingEngine.at(d0)\n}\n"))
        .isEqualTo(hint)
    assertThat(errorFor("val e = /* see x. /* y */ */ StirlingEngine.at(d0)\n")).isEqualTo(hint)
  }

  @Test
  fun `a member name several families share suggests a few of them`() {
    assertThat(errorFor("val e = Small.at(d0)\n"))
        .isEqualTo(
            "Unresolved reference 'Small'. Did you mean Battery.Small, BatteryCell.Small, " +
                "BatteryCharger.Small, Fridge.Small, HangarDoor.Small or one of 7 more?"
        )
  }

  @Test
  fun `a wrong member written after its qualifier gets no other family's member suggested`() {
    assertThat(errorFor("val e = Generator.Small.at(d0)\n"))
        .isEqualTo("Unresolved reference 'Small'.")
    assertThat(errorFor("val e = Generator.\n    Small.at(d0)\n"))
        .isEqualTo("Unresolved reference 'Small'.")
    assertThat(errorFor("val e = Generator. /* c */ Small\n"))
        .isEqualTo("Unresolved reference 'Small'.")
    assertThat(errorFor("val e = Generator. // c\n    Small\n"))
        .isEqualTo("Unresolved reference 'Small'.")
    assertThat(errorFor("val e = Generator. /* a /* b */ c */ Small\n"))
        .isEqualTo("Unresolved reference 'Small'.")
    assertThat(errorFor("val e = Generator.Small()\n")).isEqualTo("Unresolved reference 'Small'.")
    assertThat(errorFor("val e = Generator::Small\n")).isEqualTo("Unresolved reference 'Small'.")
    assertThat(errorFor("val e: Generator.Small? = null\n"))
        .isEqualTo("Unresolved reference 'Small'.")
  }

  @Test
  fun `an unresolved name no family member has is left as K2 says it`() {
    assertThat(errorFor("val e = NoSuchThing\n")).isEqualTo("Unresolved reference 'NoSuchThing'.")
  }

  @Test
  fun `an error inside a wrapped statement maps back to the original column`() {
    // Line 2 is compiled as `init { n += "one" }`; K2 reports the mismatch on the `+=`
    // operator, which is column 3 of the user's line (column 10 of the generated one).
    val result = K2Frontend.compile("var n = 0\nn += \"one\"\n") { it.module.dump() }

    assertThat(result).isInstanceOf(FrontendResult.Failure::class.java)
    val error = result.diagnostics.first { it.severity == Diagnostic.Severity.ERROR }
    assertThat(error.line).isEqualTo(2)
    assertThat(error.column).isEqualTo(3)
  }

  @Test
  fun `a missing closing brace is reported in the user's source`() {
    // The parser places the error after the last token, at the end of the unclosed line.
    val result = K2Frontend.compile("val a = 1\nfun f() {\n") { it.module.dump() }

    assertThat(result).isInstanceOf(FrontendResult.Failure::class.java)
    assertThat(result.diagnostics)
        .containsExactly(
            Diagnostic(
                Diagnostic.Severity.ERROR,
                "Syntax error: Expecting '}'",
                2,
                10,
                kotlin = true,
            )
        )
  }

  @Test
  fun `a stray closing brace is reported at the brace`() {
    // The parser also flags the statements after the brace; the first error is the brace itself.
    val result = K2Frontend.compile("val a = 1\n}\nprintln(a)\n") { it.module.dump() }

    assertThat(result).isInstanceOf(FrontendResult.Failure::class.java)
    assertThat(result.diagnostics.first())
        .isEqualTo(
            Diagnostic(
                Diagnostic.Severity.ERROR,
                "Syntax error: Unexpected symbol",
                2,
                1,
                kotlin = true,
            )
        )
  }

  @Test
  fun `syntax errors are reported alongside unsupported constructs`() {
    val result = K2Frontend.compile("val (a, b) = Pair(1, 2)\nfun f( {\n") { it.module.dump() }

    assertThat(result).isInstanceOf(FrontendResult.Failure::class.java)
    assertThat(result.diagnostics.map { it.line }).contains(1)
    assertThat(result.diagnostics.map { it.line }).contains(2)
  }

  @Test
  fun `a top-level destructuring declaration is rejected in script terms`() {
    val result =
        K2Frontend.compile("val (a, b) = Pair(1, 2)\nprintln(a + b)\n") { it.module.dump() }

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
