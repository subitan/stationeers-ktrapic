package ktrapic.emu

/**
 * Batch reads over a data network (`lb`, `lbn`, `lbs`, `lbns`) as the game computes them, for the
 * [Emulator] and for anything else that must read a network the way the chip does.
 */
object Batch {
  const val AVERAGE = 0
  const val SUM = 1
  const val MINIMUM = 2
  const val MAXIMUM = 3
  const val COUNT = 4

  /** The batch methods by the names IC10 gives them. */
  val METHODS =
      mapOf(
          "Average" to AVERAGE,
          "Sum" to SUM,
          "Minimum" to MINIMUM,
          "Maximum" to MAXIMUM,
          "Count" to COUNT,
      )

  /** The devices of [network] with the prefab hash [prefabHash] and, if given, [nameHash]. */
  fun matching(
      network: List<FakeDevice>,
      prefabHash: Int,
      nameHash: Int? = null,
  ): List<FakeDevice> = network.filter {
    it.prefabHash == prefabHash && (nameHash == null || it.nameHash == nameHash)
  }

  /**
   * A batch read with [method] over the devices of [network] with the prefab hash [prefabHash] and,
   * if given, [nameHash], each read with [read].
   *
   * As in the game: every matching device must be readable, whatever the method; Count counts by
   * prefab only, ignoring [nameHash]; devices are visited last to first; Minimum takes each value
   * that isn't `>=` the one it holds (and Maximum each that isn't `<=`), so a NaN replaces the
   * value held and the next device's value replaces the NaN. An empty Average is NaN (0/0), an
   * empty Minimum, or one whose values are all positive infinity, is 0, and an empty Maximum is
   * negative infinity.
   */
  fun read(
      network: List<FakeDevice>,
      prefabHash: Int,
      nameHash: Int?,
      method: Int,
      read: (FakeDevice) -> Double,
  ): Double {
    val values = matching(network, prefabHash, nameHash).asReversed().map(read)
    return when (method) {
      COUNT -> matching(network, prefabHash).size.toDouble()
      AVERAGE -> values.sum() / values.size
      SUM -> values.sum()
      MINIMUM -> {
        var minimum = Double.POSITIVE_INFINITY
        for (value in values) if (!(value >= minimum)) minimum = value
        if (minimum >= Double.POSITIVE_INFINITY) 0.0 else minimum
      }
      MAXIMUM -> {
        var maximum = Double.NEGATIVE_INFINITY
        for (value in values) if (!(value <= maximum)) maximum = value
        maximum
      }
      else -> error("unknown batch method $method")
    }
  }
}
