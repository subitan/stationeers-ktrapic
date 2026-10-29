package ktrapic.api

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import ktrapic.emu.Emulator
import ktrapic.emu.FakeDevice
import ktrapic.ic10.Hashes
import ktrapic.ic10.Program
import org.junit.jupiter.api.Test

/** `String` parameters: constant text passed to a function, inlined or called with `jal`. */
class StringParameterTest {
  private val display = "StructureConsoleLED5"

  private fun compile(source: String, options: CompileOptions = CompileOptions()) =
      Compiler.compile(source.trimIndent(), options)

  private fun compiled(source: String, options: CompileOptions = CompileOptions()) =
      when (val result = compile(source, options)) {
        is CompileResult.Success -> result
        is CompileResult.Failure -> throw AssertionError("compile failed: ${result.errors}")
      }

  private fun error(source: String): CompileError =
      (compile(source) as? CompileResult.Failure)?.errors?.first()
          ?: throw AssertionError("expected an error")

  /**
   * Compiles [source] in normal and compact form and runs each until it halts, with a display on
   * `d0` and another on `d1`; returns the displays' `Setting`s after the normal run, having checked
   * the compact run left them the same.
   */
  private fun settings(source: String): List<Double?> {
    val runs =
        listOf(false, true).map { compact ->
          val devices = listOf(FakeDevice(display), FakeDevice(display))
          val code = compiled(source, CompileOptions(compact = compact)).code
          val emulator =
              Emulator(Program.parse(code), mapOf("d0" to devices[0], "d1" to devices[1]))
                  .runUntilHalt()
          assertWithMessage("halted (compact=$compact)\n$code").that(emulator.halted).isTrue()
          devices.map { it["Setting"] }
        }
    assertThat(runs[1]).isEqualTo(runs[0])
    return runs[0]
  }

  @Test
  fun `an inlined function writes its String parameter as Text`() {
    val source =
        """
        val panel = LedDisplays.Large.at(d0)
        fun debug(msg: String) {
          panel.Mode = DisplayMode.String
          panel.Text = msg
        }
        debug("foo")
        """

    assertThat(settings(source)[0]).isEqualTo(Hashes.str("foo"))
    // Inlined, the text is a constant: no register carries it.
    assertThat(compiled(source).code).contains("s d0 Setting")
    assertThat(compiled(source).registers).isEqualTo(0)
  }

  @Test
  fun `a function called with jal gets its String packed in a register`() {
    val source =
        """
        fun show(msg: String) {
          d0.Setting = str(msg)
          d1.Setting = 1.0
        }
        show("foo")
        show("bar")
        """

    val code = compiled(source).code
    assertThat(code).contains("jal")
    assertThat(code).contains("STR(\"foo\")")
    assertThat(code).contains("STR(\"bar\")")
    assertThat(settings(source)[0]).isEqualTo(Hashes.str("bar"))
  }

  @Test
  fun `Text takes a packed String in a function called with jal`() {
    val source =
        """
        fun debug(msg: String) {
          LedDisplays.Large.at(d0).Text = msg
        }
        debug("one")
        debug("two")
        """

    assertThat(settings(source)[0]).isEqualTo(Hashes.str("two"))
  }

  @Test
  fun `the issue's workaround still compiles`() {
    val source =
        """
        fun debug(msg: Double) {
          d0.Setting = msg
        }
        debug(str("foo"))
        """

    assertThat(settings(source)[0]).isEqualTo(Hashes.str("foo"))
  }

  @Test
  fun `an inlined function can hash its String parameter and pass it on`() {
    val source =
        """
        inline fun label(msg: String) {
          d1.Setting = hash(msg)
          d0.Setting = msg.hash()
        }
        inline fun relay(msg: String) = label(msg)
        relay("GrowLight")
        """

    assertThat(settings(source))
        .containsExactly(Hashes.hash("GrowLight").toDouble(), Hashes.hash("GrowLight").toDouble())
  }

  @Test
  fun `a String parameter can be passed on to a function called with jal`() {
    val source =
        """
        fun show(msg: String) {
          d0.Setting = str(msg)
        }
        fun twice(msg: String) {
          show(msg)
          d1.Setting = str(msg)
        }
        twice("ab")
        twice("cd")
        show("ef")
        """

    assertThat(settings(source)).containsExactly(Hashes.str("ef"), Hashes.str("cd")).inOrder()
  }

  @Test
  fun `a String parameter selects named devices in an inlined function`() {
    val source =
        """
        inline fun lightsOn(name: String) {
          GrowLights.named(name).forEach { it.On = true }
        }
        lightsOn("Tomatoes")
        """

    assertThat(compiled(source).code).contains("sbn -1758710260 ${Hashes.hash("Tomatoes")} On 1")
  }

  @Test
  fun `hash and named need the text, not a packed String`() {
    val hashed =
        error(
            """
            fun f(msg: String) { d0.Setting = hash(msg) }
            f("a")
            f("b")
            """
        )
    val named =
        error(
            """
            fun f(msg: String) { GrowLights.named(msg).forEach { it.On = true } }
            f("a")
            f("b")
            """
        )

    assertThat(hashed.message).contains("as f is called with jal: mark f inline")
    assertThat(hashed.line).isEqualTo(1)
    assertThat(named.message).contains("as f is called with jal: mark f inline")
  }

  @Test
  fun `a packed String in an inlined function names the function called with jal`() {
    val hashed =
        error(
            """
            inline fun h(m: String) { d0.Setting = hash(m) }
            fun g(m: String) { h(m) }
            g("a")
            g("b")
            """
        )
    val named =
        error(
            """
            inline fun h(m: String) { GrowLights.named(m).forEach { it.On = true } }
            fun g(m: String) { h(m) }
            g("a")
            g("b")
            """
        )

    assertThat(hashed.message).contains("as g is called with jal: mark g inline")
    assertThat(hashed.line).isEqualTo(1)
    assertThat(named.message).contains("as g is called with jal: mark g inline")
    assertThat(named.line).isEqualTo(1)
  }

  @Test
  fun `a val holds a String parameter in an inlined function`() {
    val source =
        """
        inline fun f(msg: String) {
          val s = msg
          d0.Setting = str(s)
          d1.Setting = hash(s)
        }
        f("abc")
        """

    assertThat(settings(source))
        .containsExactly(Hashes.str("abc"), Hashes.hash("abc").toDouble())
        .inOrder()
  }

  @Test
  fun `a val holds a packed String parameter in a function called with jal`() {
    val source =
        """
        fun f(msg: String) {
          val s = msg
          d0.Setting = str(s)
          LedDisplays.Large.at(d1).Text = s
        }
        f("one")
        f("two")
        """

    assertThat(compiled(source).code).contains("jal")
    assertThat(settings(source)).containsExactly(Hashes.str("two"), Hashes.str("two")).inOrder()
  }

  @Test
  fun `a var can't hold a String parameter`() {
    val inlined =
        error(
            """
            inline fun f(msg: String) {
              var s = msg
              d0.Setting = 1.0
            }
            f("a")
            """
        )
    val jal =
        error(
            """
            fun f(msg: String) {
              var s = msg
              d0.Setting = 1.0
            }
            f("a")
            f("b")
            """
        )

    for (error in listOf(inlined, jal)) {
      assertThat(error.message).contains("a String has no value on the chip")
      assertThat(error.message).contains("only be held in a val, not a var")
      assertThat(error.line).isEqualTo(2)
    }
  }

  @Test
  fun `a String parameter can't be a when subject`() {
    val error =
        error(
            """
            inline fun f(msg: String) {
              d0.Setting = when (msg) { "a" -> 1.0; else -> 0.0 }
            }
            f("a")
            """
        )

    assertThat(error.message).contains("Comparing String values is not supported")
    assertThat(error.line).isEqualTo(2)
  }

  @Test
  fun `an argument must be a constant string`() {
    val error =
        error(
            """
            fun f(msg: String) { LedDisplays.Large.at(d0).Text = msg }
            f("a".repeat(2))
            """
        )

    assertThat(error.message).contains("a String argument must be a constant string")
  }

  @Test
  fun `a String argument to a function called with jal is checked at the call`() {
    val error =
        error(
            """
            fun f(msg: String) { d0.Setting = str(msg) }
            f("ok")
            f("toolong")
            """
        )

    assertThat(error.line).isEqualTo(3)
  }

  @Test
  fun `a String parameter can't be compared`() {
    val error =
        error(
            """
            inline fun f(msg: String) { d0.Setting = if (msg == "a") 1.0 else 0.0 }
            f("a")
            """
        )

    assertThat(error.message).contains("Comparing String values is not supported")
  }
}
