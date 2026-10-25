package ktrapic.cli

import ktrapic.ic10.Ic10Limits

const val VERSION = "0.0.0-dev"

fun main() {
  println("KTrapIC $VERSION: constrained Kotlin to Stationeers IC10")
  println(
      "Target chip: ${Ic10Limits.MAX_LINES} lines, ${Ic10Limits.MAX_BYTES} bytes, " +
          "${Ic10Limits.REGISTERS} registers"
  )
}
