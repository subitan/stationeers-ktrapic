package ktrapic.api

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The front end's warnings: K2's extra checkers (unused variables, values never read and the like),
 * the same for the script's top-level properties, and none about the text the script wrapper adds.
 */
class WarningTest {
  private fun warnings(source: String): List<CompileError> =
      when (val result = Compiler.compile(source)) {
        is CompileResult.Success -> result.warnings
        is CompileResult.Failure -> throw AssertionError("compile failed: ${result.errors}")
      }

  @Test
  fun `an unused top-level val warns at its name`() {
    val warning = warnings("val unused = 2\n").single()

    assertThat(warning.message).contains("unused")
    assertThat(warning.line).isEqualTo(1)
    assertThat(warning.column).isEqualTo(5)
  }

  @Test
  fun `an unused val in a block warns at its name`() {
    val warning = warnings("while (true) {\n  val unused = 2\n  yield()\n}\n").single()

    assertThat(warning).isEqualTo(CompileError("Variable is unused.", 2, 7))
  }

  @Test
  fun `an assignment never read warns at the assignment`() {
    val source = "while (true) {\n  var x = d0.Setting\n  db.Setting = x\n  x = 2.0\n  yield()\n}\n"

    assertThat(warnings(source))
        .containsExactly(CompileError("Assigned value is never read.", 4, 3))
  }

  @Test
  fun `a top-level var only ever assigned warns that it is never read`() {
    val warning = warnings("var x = 1.0\nx = 2.0\n").single()

    assertThat(warning).isEqualTo(CompileError("Variable is never read.", 1, 5))
  }

  @Test
  fun `used variables give no warning`() {
    assertThat(warnings("val used = 2.0\ndb.Setting = used\n")).isEmpty()
    assertThat(
            warnings("while (true) {\n  val used = d0.Setting\n  db.Setting = used\n  yield()\n}")
        )
        .isEmpty()
    // Read in a function, and written by a compound assignment, which reads it too.
    assertThat(warnings("var n = 0.0\nfun f(): Double = n\nn += 1.0\ndb.Setting = f()\n")).isEmpty()
  }

  @Test
  fun `a device alias used through its logic types gives no warning`() {
    val source =
        """
        val sensor = d0
        val gas = GasSensor.at(d1)
        while (true) {
          val heater = d2
          heater.On = sensor.Temperature < 290.0
          db.Setting = gas.Pressure
          yield()
        }
        """
            .trimIndent()

    assertThat(warnings(source)).isEmpty()
  }

  @Test
  fun `an inline function taking a device gives no warning about inlining`() {
    val source =
        "inline fun charge(b: Battery): Double = b.Ratio\ndb.Setting = charge(Battery.Small.at(d0))\n"

    assertThat(warnings(source)).isEmpty()
  }

  @Test
  fun `the script wrapper's own text gives no warning`() {
    // Imports, a declaration before the first statement, statements that are wrapped in init
    // blocks, one ending in a comment, and declarations among them: all wrapper text.
    val source =
        """
        import kotlin.math.*
        fun twice(x: Double): Double = 2.0 * x
        db.Setting = twice(abs(d0.Setting)) // a comment the closing brace must not join
        val limit = 3.0
        if (d0.Setting > limit) db.On = true
        var count = 0
        while (true) {
          count++
          db.Mode = count.toDouble()
          yield()
        }
        """
            .trimIndent()

    assertThat(warnings(source)).isEmpty()
  }

  @Test
  fun `Suppress on a top-level property or the file turns its warning off`() {
    assertThat(warnings("@Suppress(\"UNUSED_VARIABLE\") val unused = 2\n")).isEmpty()
    assertThat(warnings("@file:Suppress(\"VARIABLE_NEVER_READ\")\nvar x = 1.0\nx = 2.0\n"))
        .isEmpty()
  }
}
