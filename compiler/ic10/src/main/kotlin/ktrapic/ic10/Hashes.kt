package ktrapic.ic10

import java.util.zip.CRC32

/** The game's string-to-number conversions used by `HASH("...")` and `STR("...")`. */
object Hashes {
  /** `HASH("...")`: the CRC-32 of the UTF-8 bytes, as a signed 32-bit integer. */
  fun hash(text: String): Int {
    val crc = CRC32()
    crc.update(text.toByteArray(Charsets.UTF_8))
    return crc.value.toInt()
  }

  /** The most characters `STR("...")` can pack into one value. */
  const val MAX_STR_LENGTH = 6

  /** `STR("...")`: one to six ASCII characters packed one byte each, first character highest. */
  fun str(text: String): Double {
    require(text.length <= MAX_STR_LENGTH) {
      "STR(\"$text\") is longer than $MAX_STR_LENGTH characters"
    }
    require(text.isNotEmpty()) { "STR(\"\") is empty" }
    require(text.all { it.code in 0..127 }) { "STR(\"$text\") has a character that isn't ASCII" }
    return text.fold(0L) { acc, c -> (acc shl 8) or c.code.toLong() }.toDouble()
  }
}
