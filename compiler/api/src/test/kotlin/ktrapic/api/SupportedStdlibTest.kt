package ktrapic.api

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import ktrapic.frontend.K2Completion
import org.junit.jupiter.api.Test

/**
 * The standard library declarations completion offers ([K2Completion.SUPPORTED_STDLIB]) compile.
 */
class SupportedStdlibTest {
  private val prelude =
      """
      import kotlin.math.*
      val n = d0.Setting.toInt()
      val x = d0.Setting
      val b = d0.On
      val s = setOf(1, 2)
      val l = listOf(1, 2)

      """
          .trimIndent()

  /** A use of each supported declaration, by its fully qualified name. */
  private val uses =
      mapOf(
          "kotlin.Int.and" to "db.Setting = n.and(1).toDouble()",
          "kotlin.Int.dec" to "db.Setting = n.dec().toDouble()",
          "kotlin.Int.div" to "db.Setting = n.div(2).toDouble()",
          "kotlin.Int.inc" to "db.Setting = n.inc().toDouble()",
          "kotlin.Int.inv" to "db.Setting = n.inv().toDouble()",
          "kotlin.Int.minus" to "db.Setting = n.minus(1).toDouble()",
          "kotlin.Int.or" to "db.Setting = n.or(1).toDouble()",
          "kotlin.Int.plus" to "db.Setting = n.plus(1).toDouble()",
          "kotlin.Int.rangeTo" to "for (i in 0.rangeTo(n)) db.Setting = i.toDouble()",
          "kotlin.Int.rangeUntil" to "for (i in 0.rangeUntil(n)) db.Setting = i.toDouble()",
          "kotlin.Int.rem" to "db.Setting = n.rem(3).toDouble()",
          "kotlin.Int.shl" to "db.Setting = n.shl(1).toDouble()",
          "kotlin.Int.shr" to "db.Setting = n.shr(1).toDouble()",
          "kotlin.Int.times" to "db.Setting = n.times(2).toDouble()",
          "kotlin.Int.toDouble" to "db.Setting = n.toDouble()",
          "kotlin.Int.toInt" to "db.Setting = n.toInt().toDouble()",
          "kotlin.Int.unaryMinus" to "db.Setting = n.unaryMinus().toDouble()",
          "kotlin.Int.unaryPlus" to "db.Setting = n.unaryPlus().toDouble()",
          "kotlin.Int.ushr" to "db.Setting = n.ushr(1).toDouble()",
          "kotlin.Int.xor" to "db.Setting = n.xor(1).toDouble()",
          "kotlin.Double.dec" to "db.Setting = x.dec()",
          "kotlin.Double.div" to "db.Setting = x.div(2.0)",
          "kotlin.Double.inc" to "db.Setting = x.inc()",
          "kotlin.Double.minus" to "db.Setting = x.minus(1.0)",
          "kotlin.Double.plus" to "db.Setting = x.plus(1.0)",
          "kotlin.Double.rem" to "db.Setting = x.rem(3.0)",
          "kotlin.Double.times" to "db.Setting = x.times(2.0)",
          "kotlin.Double.toDouble" to "db.Setting = x.toDouble()",
          "kotlin.Double.toInt" to "db.Setting = x.toInt().toDouble()",
          "kotlin.Double.unaryMinus" to "db.Setting = x.unaryMinus()",
          "kotlin.Double.unaryPlus" to "db.Setting = x.unaryPlus()",
          "kotlin.Boolean.not" to "db.On = b.not()",
          "kotlin.collections.Set.contains" to "db.On = s.contains(n)",
          "kotlin.collections.Set.isEmpty" to "db.On = s.isEmpty()",
          "kotlin.collections.Set.size" to "db.Setting = s.size.toDouble()",
          "kotlin.collections.List.contains" to "db.On = l.contains(n)",
          "kotlin.collections.List.isEmpty" to "db.On = l.isEmpty()",
          "kotlin.collections.List.size" to "db.Setting = l.size.toDouble()",
          "kotlin.math.absoluteValue" to "db.Setting = x.absoluteValue",
          "kotlin.math.pow" to "db.Setting = x.pow(2.0)",
          "kotlin.math.roundToInt" to "db.Setting = x.roundToInt().toDouble()",
          "kotlin.ranges.coerceAtLeast" to "db.Setting = x.coerceAtLeast(0.0)",
          "kotlin.ranges.coerceAtMost" to "db.Setting = x.coerceAtMost(1.0)",
          "kotlin.ranges.coerceIn" to "db.Setting = x.coerceIn(0.0, 1.0)",
          "kotlin.ranges.downTo" to "for (i in n.downTo(0)) db.Setting = i.toDouble()",
          "kotlin.ranges.step" to "for (i in (0..n).step(2)) db.Setting = i.toDouble()",
          "kotlin.ranges.until" to "for (i in 0.until(n)) db.Setting = i.toDouble()",
          "kotlin.collections.intersect" to "db.Setting = s.intersect(setOf(1)).size.toDouble()",
          "kotlin.collections.minus" to "db.Setting = s.minus(1).size.toDouble()",
          "kotlin.collections.plus" to "db.Setting = s.plus(3).size.toDouble()",
      )

  @Test
  fun `every supported declaration has a use here`() {
    assertThat(uses.keys).containsExactlyElementsIn(K2Completion.SUPPORTED_STDLIB)
  }

  @Test
  fun `every use compiles`() {
    val failures = uses.mapNotNull { (name, use) ->
      (Compiler.compile(prelude + use + "\n") as? CompileResult.Failure)?.let {
        "$name: ${it.errors}"
      }
    }

    assertWithMessage("uses that don't compile").that(failures).isEmpty()
  }
}
