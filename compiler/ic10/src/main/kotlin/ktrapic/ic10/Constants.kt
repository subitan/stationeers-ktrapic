package ktrapic.ic10

/** IC10's built-in named constants, with the game's values. */
object Constants {
  /**
   * The value of each built-in constant, by the name a program uses for it. The game matches these
   * names ignoring case (`PI` is `pi`), after labels, defines and aliases.
   */
  val VALUES: Map<String, Double> =
      mapOf(
          "nan" to Double.NaN,
          "pinf" to Double.POSITIVE_INFINITY,
          "ninf" to Double.NEGATIVE_INFINITY,
          "pi" to Math.PI,
          "tau" to Math.PI * 2.0,
          // The game's conversion factors are single-precision values widened to double.
          "deg2rad" to 0.01745329238474369,
          "rad2deg" to 57.295780181884766,
          "epsilon" to Double.MIN_VALUE,
          "rgas" to 8.31446261815324,
      )

  /** The value of the built-in constant [name] spells, ignoring case as the game does, or null. */
  fun lookup(name: String): Double? =
      VALUES[name] ?: VALUES.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
