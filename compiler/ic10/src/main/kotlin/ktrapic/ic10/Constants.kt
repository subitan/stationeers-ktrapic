package ktrapic.ic10

/** IC10's built-in named constants. */
object Constants {
  /** The value of each built-in constant, by the name a program uses for it. */
  val VALUES: Map<String, Double> =
      mapOf(
          "pi" to Math.PI,
          "deg2rad" to Math.PI / 180,
          "rad2deg" to 180 / Math.PI,
          "epsilon" to Float.MIN_VALUE.toDouble(),
          "nan" to Double.NaN,
          "pinf" to Double.POSITIVE_INFINITY,
          "ninf" to Double.NEGATIVE_INFINITY,
      )
}
