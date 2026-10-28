package ktrapic.frontend

import com.google.common.truth.Truth.assertThat
import java.io.File
import ktrapic.Device
import org.junit.jupiter.api.Test

class K2CompletionTest {
  private val sdkJar = File(Device::class.java.protectionDomain.codeSource.location.toURI())

  /** Completes at the `|` in [source]. */
  private fun complete(source: String): List<Completion> {
    val offset = source.indexOf('|')
    return K2Completion.complete(
        source.removeRange(offset, offset + 1),
        offset,
        listOf(sdkJar),
        listOf("ktrapic.*"),
    )
  }

  private fun labels(source: String) = complete(source).map { it.label }

  @Test
  fun `after a device, its logic types`() {
    val items = complete("d0.|\n")

    assertThat(items.map { it.label }).containsAtLeast("Setting", "On", "Pressure")
    assertThat(items.first { it.label == "Setting" }.detail).isEqualTo("var Setting: Double")
    assertThat(items.map { it.label }).containsNoneOf("equals", "hashCode", "toString")
  }

  @Test
  fun `after a device with a word started, the same list`() {
    assertThat(labels("d0.Sett|\n")).contains("Setting")
  }

  @Test
  fun `after a local holding a typed device`() {
    val items = labels("val sensor = PresenceSensors.Motion.at(d0)\nif (sensor.|) yield()\n")

    assertThat(items).containsAtLeast("Activate", "Quantity", "On")
  }

  @Test
  fun `after an object, its nested objects`() {
    assertThat(labels("PresenceSensors.|")).containsAtLeast("Motion", "Proximity", "Occupancy")
  }

  @Test
  fun `after a family's object, its entry points`() {
    assertThat(labels("PresenceSensors.Motion.|")).containsAtLeast("at", "all", "named")
  }

  @Test
  fun `after an enum, its entries`() {
    val items = labels("d0[LogicType.|] = 1.0")

    assertThat(items).containsAtLeast("Setting", "On")
    assertThat(items).containsNoneOf("values", "valueOf", "entries")
  }

  @Test
  fun `after Substance, its entries and the static set of them`() {
    val items = labels("val s = Substance.|\n")

    assertThat(items).containsAtLeast("Oxygen", "entries")
    assertThat(items).containsNoneOf("values", "valueOf")
  }

  @Test
  fun `after a Ratios loop's entry, its key and value`() {
    assertThat(labels("for (e in GasSensors.at(d0).Ratios) {\n  db.Setting = e.|\n}\n"))
        .containsExactly("key", "value")
  }

  @Test
  fun `after a range, only its step`() {
    assertThat(labels("for (i in (0..10).|) yield()\n")).containsExactly("step")
    assertThat(labels("for (i in (10 downTo 0).|) yield()\n")).containsExactly("step")
  }

  @Test
  fun `overloads merged into one item are described by one KTrapIC compiles`() {
    val int = complete("val x = 2\nval y = x.|\n")
    val double = complete("val x = 2.0\nval y = x.|\n")

    assertThat(int.single { it.label == "until" }.detail).isEqualTo("fun until(to: Int): IntRange")
    assertThat(int.single { it.label == "plus" }.detail).isEqualTo("fun plus(other: Int): Int")
    assertThat(double.single { it.label == "plus" }.detail)
        .isEqualTo("fun plus(other: Double): Double")
    assertThat(double.single { it.label == "coerceIn" }.detail)
        .isEqualTo("fun coerceIn(minimumValue: Double, maximumValue: Double): Double")
  }

  @Test
  fun `only the keywords a program can use`() {
    val keywords = complete("val a = 1\n|\n").filter { it.kind == Completion.Kind.KEYWORD }

    assertThat(keywords.map { it.label }).containsAtLeast("val", "fun", "for", "in", "while")
    assertThat(keywords.map { it.label })
        .containsNoneOf("class", "interface", "object", "enum", "is")
  }

  @Test
  fun `not a property in its own initializer, nor a function in its own body`() {
    assertThat(labels("val a = 1\nval b = |\n")).doesNotContain("b")
    assertThat(labels("fun f(): Int {\n  return |\n}\n")).doesNotContain("f")
  }

  @Test
  fun `inside a lambda over a batch`() {
    assertThat(labels("PresenceSensors.Motion.all.forEach { it.| }"))
        .containsAtLeast("Activate", "Quantity")
  }

  @Test
  fun `SDK extensions on a mapped batch`() {
    assertThat(labels("val q = PresenceSensors.Motion.all.map { it.Quantity }.|\n"))
        .containsAtLeast("sum", "average", "min", "max")
  }

  @Test
  fun `bare names are locals, parameters, members and the SDK`() {
    val source =
        """
        val limit = 10
        fun square(x: Int): Int {
          val y = x * x
          return |
        }
        """
            .trimIndent()
    val items = labels(source)

    assertThat(items).containsAtLeast("x", "y", "limit", "d0", "sleep", "PresenceSensors")
    assertThat(items).contains("if")
  }

  private fun locals(source: String) =
      complete(source).filter { it.kind == Completion.Kind.LOCAL }.map { it.label }

  @Test
  fun `a loop variable is in scope in the loop`() {
    assertThat(locals("for (i in 0..3) {\n  d0.Setting = |\n}\n")).containsExactly("i")
  }

  @Test
  fun `the variables a destructuring declaration makes, and not the compiler's`() {
    assertThat(locals("fun f() {\n  val (a, b) = Pair(1, 2)\n  val c = |\n}\n"))
        .containsExactly("a", "b")
    assertThat(locals("for ((gas, ratio) in GasSensors.at(d0).Ratios) {\n  d0.Setting = |\n}\n"))
        .containsExactly("gas", "ratio")
  }

  @Test
  fun `a when subject's variable is in scope in the branches`() {
    val source = "when (val v = d0.Setting) {\n  1.0 -> d0.Setting = |\n  else -> {}\n}\n"

    assertThat(locals(source)).containsExactly("v")
  }

  @Test
  fun `a shadowed name is offered once, as the nearer declaration`() {
    val items = complete("val x = 1\nfun f() {\n  val x = 2.0\n  val y = |\n}\n")

    val xs = items.filter { it.label == "x" }
    assertThat(xs).hasSize(1)
    assertThat(xs.single().kind).isEqualTo(Completion.Kind.LOCAL)
    assertThat(xs.single().detail).isEqualTo("val x: Double")
  }

  @Test
  fun `names that need backticks are not offered`() {
    assertThat(labels("val `my val` = 1\nval y = |\n")).doesNotContain("my val")
  }

  @Test
  fun `after an Int, only the members and extensions KTrapIC compiles`() {
    val items = labels("val x = 1\nval y = x.|\n")

    assertThat(items).containsAtLeast("plus", "toDouble", "toInt", "shl", "coerceIn", "until")
    assertThat(items)
        .containsNoneOf("toLong", "toFloat", "toByte", "toShort", "toChar", "compareTo")
    assertThat(
            K2Completion.SUPPORTED_STDLIB.getValue("kotlin.Int").map {
              it.substringBefore('(').substringAfterLast('.')
            }
        )
        .containsAtLeastElementsIn(items)
  }

  @Test
  fun `after a Double, kotlin-math's extensions once imported`() {
    val plain = labels("val x = 2.0\nval y = x.|\n")
    val withMath = labels("import kotlin.math.*\nval x = 2.0\nval y = x.|\n")
    val withOne = labels("import kotlin.math.roundToInt\nval x = 2.0\nval y = x.|\n")

    assertThat(plain).containsAtLeast("toInt", "coerceAtLeast")
    assertThat(plain).containsNoneOf("roundToInt", "toLong", "toFloat", "compareTo")
    assertThat(withMath).containsAtLeast("roundToInt", "absoluteValue", "pow")
    assertThat(withMath).containsNoneOf("roundToLong", "sign", "ulp", "withSign")
    assertThat(withOne).contains("roundToInt")
    assertThat(withOne).doesNotContain("pow")
  }

  @Test
  fun `after an enum entry or a static set, only what KTrapIC compiles`() {
    assertThat(labels("val s = setOf(1, 2)\nval n = s.|\n"))
        .containsExactly("contains", "intersect", "isEmpty", "minus", "plus", "size")
    assertThat(labels("val o = Substance.Oxygen.|\n"))
        .containsNoneOf("name", "ordinal", "compareTo")
  }

  @Test
  fun `a local declared after the caret is not offered`() {
    assertThat(labels("val a = 1\nval b = |\nval c = 3\n")).doesNotContain("c")
  }

  @Test
  fun `errors elsewhere in the program don't stop completion`() {
    assertThat(labels("val broken: Int = \"x\"\nd0.|\nwhile (\n")).containsAtLeast("Setting", "On")
  }

  @Test
  fun `nothing in a comment or a string`() {
    assertThat(labels("// d0.|\n")).isEmpty()
    assertThat(labels("/* d|")).isEmpty()
    assertThat(labels("val s = \"d0.|\"\n")).isEmpty()
  }

  @Test
  fun `in a string template's expression`() {
    assertThat(labels("val s = \"\${d0.|}\"\n")).contains("Setting")
  }

  @Test
  fun `in a function's body, a property declared after it is offered`() {
    assertThat(labels("fun f() {\n  db.Setting = |\n}\nval late = 1.0\nf()\n")).contains("late")
    assertThat(labels("db.Setting = |\nval late = 1.0\n")).doesNotContain("late")
    assertThat(labels("PresenceSensors.Motion.all.forEach { db.Setting = | }\nval late = 1.0\n"))
        .doesNotContain("late")
  }

  @Test
  fun `after a number literal, its type's members and extensions`() {
    assertThat(labels("val y = 3.|\n")).containsAtLeast("toDouble", "until", "plus", "shl")
    assertThat(labels("val y = 3.t|\n")).contains("toDouble")
    assertThat(labels("val y = (-3).|\n")).containsAtLeast("toDouble", "coerceIn")
    assertThat(labels("val y = 1.5.|\n")).containsAtLeast("toInt", "coerceIn")
  }

  @Test
  fun `a function declared later in the program is offered`() {
    assertThat(labels("val a = |\nfun later() = 1\n")).contains("later")
  }

  @Test
  fun `positions count Windows line endings`() {
    assertThat(labels("val a = 1\r\nd0.|\r\n")).contains("Setting")
  }

  @Test
  fun `internal SDK declarations are not offered`() {
    assertThat(labels("|")).doesNotContain("intrinsic")
  }
}
