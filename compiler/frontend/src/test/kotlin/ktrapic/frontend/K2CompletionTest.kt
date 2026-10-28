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
    assertThat(labels("d0[LogicType.|] = 1.0")).containsAtLeast("Setting", "On")
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

    assertThat(items).containsAtLeast("x", "y", "limit", "square", "d0", "sleep", "PresenceSensors")
    assertThat(items).contains("if")
  }

  @Test
  fun `a loop variable is in scope in the loop`() {
    assertThat(labels("for (i in 0..3) {\n  d0.Setting = |\n}\n")).contains("i")
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
