package ktrapic.frontend

import com.google.common.truth.Truth.assertThat
import java.io.File
import ktrapic.Device
import org.junit.jupiter.api.Test

class K2HoverTest {
  private val sdkJar = File(Device::class.java.protectionDomain.codeSource.location.toURI())

  /** Hovers at the `|` in [source]. */
  private fun hover(source: String): Hover? {
    val offset = source.indexOf('|')
    return K2Hover.hover(
        source.removeRange(offset, offset + 1),
        offset,
        listOf(sdkJar),
        listOf("ktrapic.*"),
    )
  }

  /** Signature help at the `|` in [source]. */
  private fun signatures(source: String): SignatureHelp? {
    val offset = source.indexOf('|')
    return K2Hover.signatureHelp(
        source.removeRange(offset, offset + 1),
        offset,
        listOf(sdkJar),
        listOf("ktrapic.*"),
    )
  }

  /** The labels of the signatures [signatures] gives. */
  private fun labels(source: String) = signatures(source)?.signatures?.map { it.label }

  @Test
  fun `a device's logic type, with its class`() {
    val hover = hover("d0.Sett|ing = 1.0\n")!!

    assertThat(hover.declaration).isEqualTo("var Setting: Double")
    assertThat(hover.container).isEqualTo("Device")
    assertThat(hover.access).isNull()
  }

  @Test
  fun `a write-only logic type, with its interface's KDoc`() {
    val hover = hover("val sign = LedDisplays.Small.at(d5)\nsign.Text| = \"HOT\"\n")!!

    assertThat(hover.declaration).isEqualTo("var Text: String")
    assertThat(hover.container).isEqualTo("LedDisplay.Small")
    assertThat(hover.access).isEqualTo(Hover.Access.WRITE_ONLY)
    assertThat(hover.documentation).contains("one to six ASCII characters")
    assertThat(hover.text())
        .startsWith("LedDisplay.Small\nvar Text: String (write-only)\n\nText of one to six")
  }

  @Test
  fun `a read-only logic type`() {
    val hover = hover("fun f(display: LedDisplay) = display.|Power\n")!!

    assertThat(hover.declaration).isEqualTo("val Power: Boolean")
    assertThat(hover.container).isEqualTo("LedDisplay")
    assertThat(hover.access).isEqualTo(Hover.Access.READ_ONLY)
  }

  @Test
  fun `each name of a qualified object`() {
    val source = "val sign = LedDisplays.Small.at(d5)\n"
    val outer = hover(source.replace("LedDisplays", "LedDis|plays"))!!
    val inner = hover(source.replace("Small", "Sm|all"))!!

    assertThat(outer.declaration).isEqualTo("object LedDisplays")
    assertThat(outer.documentation)
        .isEqualTo("The LedDisplay devices on the chip's network, by prefab.")
    assertThat(inner.declaration).isEqualTo("object LedDisplays.Small")
    assertThat(inner.documentation)
        .isEqualTo("The StructureConsoleLED5 devices on the chip's network.")
  }

  @Test
  fun `a function, with its KDoc's links as names`() {
    val hover = hover("val sign = LedDisplays.Small.a|t(d5)\n")!!

    assertThat(hover.declaration).isEqualTo("fun at(pin: Device): LedDisplay.Small")
    assertThat(hover.container).isEqualTo("LedDisplays.Small")
    assertThat(hover.documentation).isEqualTo("The device on pin, as a StructureConsoleLED5.")
  }

  @Test
  fun `the overload a call is to`() {
    val hover = hover("slee|p(2)\n")!!

    assertThat(hover.declaration).isEqualTo("fun sleep(seconds: Int): Unit")
    assertThat(hover.container).isNull()
    assertThat(hover.documentation).isEqualTo("Pauses the chip for seconds (IC10 `sleep`).")
  }

  @Test
  fun `a type in a parameter`() {
    val hover = hover("fun show(display: LedDisp|lay) {}\n")!!

    assertThat(hover.declaration).isEqualTo("interface LedDisplay")
    assertThat(hover.documentation).startsWith("Devices of one kind")
  }

  @Test
  fun `the program's own declarations, where they are declared and where they are used`() {
    val source = "fun twice(x: Int) = x * 2\nval y = twice(3)\n"

    assertThat(hover(source.replaceFirst("twice", "tw|ice"))!!.declaration)
        .isEqualTo("fun twice(x: Int): Int")
    assertThat(hover(source.replace("twice(3)", "twice|(3)"))!!.declaration)
        .isEqualTo("fun twice(x: Int): Int")
    val local = hover(source.replace("x * 2", "|x * 2"))!!
    assertThat(local.declaration).isEqualTo("val x: Int")
    assertThat(local.container).isNull()
    assertThat(hover(source.replace("val y", "val |y"))!!.declaration).isEqualTo("val y: Int")
  }

  @Test
  fun `the program's own classes, objects and enums, without the object it is wrapped in`() {
    val p = "class P(val a: Int)\n"

    val constructor = hover(p + "val p = P|(1)\n")!!
    assertThat(constructor.declaration).isEqualTo("P(a: Int)")
    assertThat(constructor.container).isNull()
    val property = hover(p + "val p = P(1)\nval q = p.a|\n")!!
    assertThat(property.container).isEqualTo("P")
    assertThat(property.declaration).isEqualTo("val a: Int")
    assertThat(hover("object O { val c = 1 }\nval y = O|.c\n")!!.declaration).isEqualTo("object O")
    assertThat(hover("enum class E { A, B }\nval e = E|.A\n")!!.declaration)
        .isEqualTo("enum class E")
    assertThat(hover("enum class E { A, B }\nval e = E.A|\n")!!.container).isEqualTo("E")
    assertThat(hover(p + "fun mk(): P = P(1)\nval z = mk|()\n")!!.declaration)
        .isEqualTo("fun mk(): P")
    assertThat(signatures(p + "val p = P(|)\n")!!.signatures.map { it.label })
        .containsExactly("P(a: Int)")
    assertThat(
            signatures(p + "fun take(p: P) = p.a\nval t = take(|)\n")!!.signatures.single().label
        )
        .isEqualTo("fun take(p: P): Int")
  }

  @Test
  fun `no hover on a call that resolved to none of its overloads yet`() {
    assertThat(hover("slee|p()\n")).isNull()
  }

  @Test
  fun `an extension's KDoc, apart from a function of the same name`() {
    val extension = hover("val a = \"GrowLight\".has|h()\n")!!
    val function = hover("val a = has|h(\"GrowLight\")\n")!!

    assertThat(extension.declaration).isEqualTo("fun hash(): Double")
    assertThat(extension.documentation).startsWith("The game's hash of this text")
    assertThat(function.declaration).isEqualTo("fun hash(text: String): Double")
    assertThat(function.documentation).startsWith("The game's hash of text")
  }

  @Test
  fun `a function type as Kotlin writes it`() {
    val hover = hover("val n = PresenceSensors.Motion.all.cou|nt { it.Activate }\n")!!

    assertThat(hover.declaration)
        .isEqualTo("fun count(predicate: (PresenceSensor.Motion) -> Boolean): Int")
  }

  @Test
  fun `an enum entry`() {
    val hover = hover("db[LogicType.Sett|ing] = 1.0\n")!!

    assertThat(hover.declaration).isEqualTo("Setting")
    assertThat(hover.container).isEqualTo("LogicType")
  }

  @Test
  fun `an object's constant`() {
    val hover = hover("db.Mode = DisplayMode.Str|ing\n")!!

    assertThat(hover.declaration).isEqualTo("val String: Double")
    assertThat(hover.container).isEqualTo("DisplayMode")
  }

  @Test
  fun `nothing on a keyword, a number, a comment or a string, or between names`() {
    assertThat(hover("va|l x = 1\n")).isNull()
    assertThat(hover("val x = 1|2\n")).isNull()
    assertThat(hover("// d0.Sett|ing\n")).isNull()
    assertThat(hover("val s = \"d0.Sett|ing\"\n")).isNull()
    assertThat(hover("d0.Setting = |  1.0\n")).isNull()
  }

  @Test
  fun `positions count Windows line endings and a byte-order mark`() {
    val hover = hover("﻿val a = 1\r\nd0.Sett|ing = 2.0\r\n")

    assertThat(hover?.declaration).isEqualTo("var Setting: Double")
  }

  @Test
  fun `hover works on code with errors elsewhere`() {
    assertThat(hover("val broken: Int = \"x\"\nd0.Sett|ing = 1.0\nwhile (\n")?.declaration)
        .isEqualTo("var Setting: Double")
  }

  @Test
  fun `signature help in a call not yet closed`() {
    val help = signatures("val s = LedDisplays.Small.at(|\n")!!

    assertThat(help.signatures.map { it.label })
        .containsExactly("fun at(pin: Device): LedDisplay.Small")
    assertThat(help.signatures.single().parameters).containsExactly("pin: Device")
    assertThat(help.signatures.single().documentation)
        .isEqualTo("The device on pin, as a StructureConsoleLED5.")
    assertThat(help.activeParameter).isEqualTo(0)
  }

  @Test
  fun `signature help names the argument the caret is in`() {
    val source = "fun mix(a: Double, b: Double, c: Double) = a + b + c\nval m = mix(1.0, f(2), |)\n"
    val help = signatures(source)!!

    assertThat(help.signatures.single().label)
        .isEqualTo("fun mix(a: Double, b: Double, c: Double): Double")
    assertThat(help.activeParameter).isEqualTo(2)
  }

  @Test
  fun `signature help offers each overload a call could be to`() {
    val help = signatures("sleep(|)\n")!!

    assertThat(help.signatures.map { it.label })
        .containsExactly("fun sleep(seconds: Double): Unit", "fun sleep(seconds: Int): Unit")
  }

  @Test
  fun `signature help where a top-level statement ends`() {
    val sleep = listOf("fun sleep(seconds: Double): Unit", "fun sleep(seconds: Int): Unit")

    assertThat(labels("sleep(|")).containsExactlyElementsIn(sleep)
    assertThat(labels("d0.Setting = 1.0\nsleep(|")).containsExactlyElementsIn(sleep)
    assertThat(labels("d0.Setting = str(|")).containsExactly("fun str(text: String): Double")
    assertThat(labels("sleep(1|\n")).containsExactlyElementsIn(sleep)
    val multiLine = signatures("fun mix(a: Double, b: Double) = a + b\nmix(\n  1.0,\n  2.0|\n")!!
    assertThat(multiLine.signatures.single().label)
        .isEqualTo("fun mix(a: Double, b: Double): Double")
    assertThat(multiLine.activeParameter).isEqualTo(1)
  }

  @Test
  fun `signature help offers the receiver's other overloads of a call that resolved`() {
    val help = signatures("val n = PresenceSensors.Motion.all.count(|)\n")!!

    assertThat(help.signatures.map { it.label })
        .containsExactly(
            "fun count(): Int",
            "fun count(predicate: (PresenceSensor.Motion) -> Boolean): Int",
        )
        .inOrder()
  }

  @Test
  fun `signature help offers the program's own overloads, and a class's own`() {
    val g = "fun g() = 1\nfun g(a: Int) = a\n"

    assertThat(labels(g + "val x = g(|)\n"))
        .containsExactly("fun g(): Int", "fun g(a: Int): Int")
        .inOrder()
    assertThat(labels(g + "g(|")).containsExactly("fun g(): Int", "fun g(a: Int): Int")
    assertThat(labels("class K {\n  fun m() = 1\n  fun m(a: Int) = a\n  fun t() = m(|)\n}\n"))
        .containsExactly("fun m(): Int", "fun m(a: Int): Int")
        .inOrder()
  }

  @Test
  fun `signature help offers the program's own overloads whatever their visibility or name`() {
    assertThat(labels("private fun g() = 1\nprivate fun g(a: Int) = a\nval x = g(|)\n"))
        .containsExactly("fun g(): Int", "fun g(a: Int): Int")
    assertThat(labels("fun g() = 1\nprivate fun g(a: Int) = a\nval x = g(|)\n"))
        .containsExactly("fun g(): Int", "fun g(a: Int): Int")
    assertThat(labels("fun _g() = 1\nfun _g(a: Int) = a\nval x = _g(|)\n"))
        .containsExactly("fun _g(): Int", "fun _g(a: Int): Int")
    assertThat(
            labels(
                "class K {\n  private fun m() = 1\n  private fun m(a: Int) = a\n  fun t() = m(|)\n}\n"
            )
        )
        .containsExactly("fun m(): Int", "fun m(a: Int): Int")
  }

  @Test
  fun `signature help lists only the standard library overloads KTrapIC compiles`() {
    val max = listOf("fun max(a: Double, b: Double): Double", "fun max(a: Int, b: Int): Int")
    assertThat(labels("import kotlin.math.max\nval m = max(1.0, 2.0|)\n"))
        .containsExactlyElementsIn(max)
        .inOrder()
    assertThat(labels("import kotlin.math.max\nval m = max(|)\n")).containsExactlyElementsIn(max)
    assertThat(labels("import kotlin.math.max\nval m = max(1.0, |)\n"))
        .containsExactlyElementsIn(max)
    assertThat(labels("import kotlin.math.abs\nval a = abs(|)\n"))
        .containsExactly("fun abs(x: Double): Double", "fun abs(n: Int): Int")
    val plus = listOf("fun plus(other: Int): Int", "fun plus(other: Double): Double")
    assertThat(labels("val n = 1.plus(2|)\n")).containsExactlyElementsIn(plus)
    assertThat(labels("val n = 1.plus(|)\n")).containsExactlyElementsIn(plus)
    assertThat(labels("sleep(|"))
        .containsExactly("fun sleep(seconds: Double): Unit", "fun sleep(seconds: Int): Unit")
  }

  @Test
  fun `signature help lists the overload KTrapIC compiles where the call fits only another`() {
    // With nothing after the comma, the call has one argument, which fits only the range overload.
    assertThat(labels("db.Setting = d0.Setting.coerceIn(0.0, |)\n"))
        .containsExactly("fun coerceIn(minimumValue: Double, maximumValue: Double): Double")
    assertThat(labels("val x = 2\nval y = x.coerceIn(1, |)\n"))
        .containsExactly("fun coerceIn(minimumValue: Int, maximumValue: Int): Int")
    assertThat(labels("import kotlin.math.*\nval p = 2.0.pow(2.0|)\n"))
        .containsExactly("fun pow(x: Double): Double", "fun pow(n: Int): Double")
  }

  @Test
  fun `signature help lists nothing of the standard library KTrapIC does not compile`() {
    assertThat(labels("val m = maxOf(1, |")).isNull()
    assertThat(labels("import kotlin.math.*\nval h = hypot(|")).isNull()
    assertThat(labels("println(1|")).isNull()
  }

  @Test
  fun `signature help for the innermost call`() {
    val help = signatures("val h = str(hash(|))\n")!!

    assertThat(help.signatures.single().label).isEqualTo("fun hash(text: String): Double")
  }

  @Test
  fun `no signature help outside a call's parentheses`() {
    assertThat(signatures("val x = |1\n")).isNull()
    assertThat(signatures("sleep(1)|\n")).isNull()
    assertThat(signatures("PresenceSensors.Motion.all.forEach { | }\n")).isNull()
    assertThat(signatures("// sleep(|\n")).isNull()
  }

  @Test
  fun `KDoc as text`() {
    assertThat(
            KDocIndex.clean(
                """
                /**
                 * The device on [pin], as
                 * a [LedDisplay.Small].
                 *
                 * - one
                 * - two
                 * @param pin a pin
                 */
                """
                    .trimIndent()
            )
        )
        .isEqualTo("The device on pin, as a LedDisplay.Small.\n\n- one\n- two\n@param pin a pin")
  }
}
