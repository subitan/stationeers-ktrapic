package ktrapic.ic10

/**
 * How the game's IC10 interpreter computes the operations whose results are not plain IEEE double
 * arithmetic, so the emulator and the compiler's constant folding agree with the chip.
 *
 * Bitwise operations work on integers of 53 bits plus sign: a value is truncated and wrapped modulo
 * 2^53 into an integer (or masked to 54 bits for the unsigned operations), and a result keeps bits
 * 0–52 with bit 53 as its sign. Values beyond the 64-bit range, infinities included, are errors.
 */
object Ic10Math {
  private const val TWO_TO_53 = 9007199254740992.0
  private const val LOW_53_BITS = 0x1FFFFFFFFFFFFFL
  private const val LOW_54_BITS = 0x3FFFFFFFFFFFFFL
  private const val BIT_53 = 0x20000000000000L

  /**
   * An operand of a bitwise operation as the integer the chip uses. NaN counts as 0: the game
   * converts it with a C# cast whose result for NaN is unspecified (`long.MinValue` on x64, which
   * differs from 0 only for `sra` by 10 or more), so this takes the JVM's conversion.
   */
  fun toBits(value: Double, signed: Boolean = true): Long {
    require(value >= -9.223372036854776E18 || value.isNaN()) { "bitwise operand underflow" }
    require(value <= 9.223372036854776E18 || value.isNaN()) { "bitwise operand overflow" }
    val wrapped = if (value.isNaN()) 0L else (value % TWO_TO_53).toLong()
    return if (signed) wrapped else wrapped and LOW_54_BITS
  }

  /** A bitwise result as the number the chip stores: bits 0–52, with bit 53 as the sign. */
  fun fromBits(bits: Long): Double {
    val low = bits and LOW_53_BITS
    return (if (bits and BIT_53 != 0L) low or -TWO_TO_53.toLong() else low).toDouble()
  }

  /**
   * A shift or field operand, which must fit an Int; Long shifts then use its low 6 bits. The
   * game's range check lets NaN through to a C# cast whose result is unspecified; it counts as 0
   * here, as in [toBits].
   */
  fun toCount(value: Double): Int {
    require(!(value < Int.MIN_VALUE)) { "shift count underflow" }
    require(!(value > Int.MAX_VALUE)) { "shift count overflow" }
    return if (value.isNaN()) 0 else value.toInt()
  }

  fun and(a: Double, b: Double) = fromBits(toBits(a) and toBits(b))

  fun or(a: Double, b: Double) = fromBits(toBits(a) or toBits(b))

  fun xor(a: Double, b: Double) = fromBits(toBits(a) xor toBits(b))

  fun nor(a: Double, b: Double) = fromBits((toBits(a) or toBits(b)).inv())

  fun not(a: Double) = fromBits(toBits(a).inv())

  /** `sll` and `sla`. */
  fun shiftLeft(a: Double, count: Double) = fromBits(toBits(a) shl toCount(count))

  /** `sra`: an arithmetic shift of the signed value. */
  fun shiftRightArithmetic(a: Double, count: Double) = fromBits(toBits(a) shr toCount(count))

  /** `srl`: a shift of the value as an unsigned 54-bit integer. */
  fun shiftRightLogical(a: Double, count: Double) =
      fromBits(toBits(a, signed = false) shr toCount(count))

  /** `rol` and `ror`: a rotation within 54 bits. */
  fun rotate(a: Double, count: Double, left: Boolean): Double {
    val bits = toBits(a, signed = false) and LOW_54_BITS
    val n = Math.floorMod(toCount(count), 54)
    val rotated =
        if (left) (bits shl n) or (bits ushr (54 - n)) else (bits ushr n) or (bits shl (54 - n))
    return fromBits(rotated and LOW_54_BITS)
  }

  /** `ext`: [length] bits of [source] starting at bit [offset]. */
  fun extract(source: Double, offset: Double, length: Double): Double {
    val (start, size) = field(offset, length)
    val bits = toBits(source, signed = false) and LOW_53_BITS
    return fromBits((bits ushr start) and fieldMask(size))
  }

  /** `ins`: [target] with [length] bits from [source] written at bit [offset]. */
  fun insert(target: Double, source: Double, offset: Double, length: Double): Double {
    val (start, size) = field(offset, length)
    val mask = fieldMask(size) shl start
    val kept = toBits(target, signed = false) and LOW_53_BITS and mask.inv()
    val added = ((toBits(source, signed = false) and LOW_53_BITS) shl start) and mask
    return fromBits((kept or added) and LOW_53_BITS)
  }

  private fun field(offset: Double, length: Double): Pair<Int, Int> {
    val start = toCount(offset)
    val size = toCount(length)
    require(size > 0 && start >= 0) { "bit field underflow" }
    require(start < 53) { "bit field overflow" }
    require(size <= 53 && start + size <= 53) { "bit field overflow" }
    return start to size
  }

  private fun fieldMask(size: Int) = if (size == 53) LOW_53_BITS else (1L shl size) - 1

  /** `mod`: the remainder, made non-negative once by adding [b] (so it may stay negative). */
  fun mod(a: Double, b: Double): Double {
    val remainder = a % b
    return if (remainder < 0.0) remainder + b else remainder
  }

  /**
   * `max`, as the game's runtime computes it: [a] if it is greater or NaN, else [b]. Unlike the
   * JVM's, it returns [b] when the two are zeros of different sign.
   */
  fun max(a: Double, b: Double) = if (a > b || a.isNaN()) a else b

  /** `min`: [a] if it is less or NaN, else [b], as for [max]. */
  fun min(a: Double, b: Double) = if (a < b || a.isNaN()) a else b

  /** `sgn`: 1, -1 or 0 (NaN gives 0). */
  fun sign(a: Double) = if (a > 0.0) 1.0 else if (a < 0.0) -1.0 else 0.0
}
