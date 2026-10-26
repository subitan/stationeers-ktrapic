package ktrapic.ic10

import java.math.BigDecimal

/** An argument of an IC10 instruction. */
sealed interface Operand {
  /** The operand as it appears in IC10 text. */
  fun text(): String
}

/** A register: `r0`..`r15`, `sp` (stack pointer) or `ra` (return address). */
data class Register(val name: String) : Operand {
  init {
    require(name in NAMES) { "not an IC10 register: $name" }
  }

  /** Index in the chip's register file: 0..15 for `r0`..`r15`, then `sp`, then `ra`. */
  val index: Int
    get() = NAMES.indexOf(name)

  override fun text() = name

  companion object {
    val NAMES = List(Ic10Limits.REGISTERS) { "r$it" } + listOf("sp", "ra")
    val SP = Register("sp")
    val RA = Register("ra")

    fun r(index: Int) = Register("r$index")
  }
}

/** A device pin `d0`..`d5`, or `db`, the circuit housing itself. */
data class Device(val name: String) : Operand {
  init {
    require(name in NAMES) { "not an IC10 device: $name" }
  }

  override fun text() = name

  companion object {
    val NAMES = List(Ic10Limits.DEVICE_PINS) { "d$it" } + "db"
  }
}

/** A numeric literal. */
data class Number(val value: Double) : Operand {
  override fun text(): String = format(value)

  companion object {
    /** Formats a number as IC10 accepts it: integers without a fraction, never exponents. */
    fun format(value: Double): String =
        when {
          value.isNaN() -> "nan"
          value == Double.POSITIVE_INFINITY -> "pinf"
          value == Double.NEGATIVE_INFINITY -> "ninf"
          value == Math.rint(value) && kotlin.math.abs(value) < 1e15 -> value.toLong().toString()
          else -> BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
        }
  }
}

/** A reference to a label, resolved to a line number when the program runs or is compacted. */
data class LabelRef(val name: String) : Operand {
  override fun text() = name
}

/**
 * Any other bare word: a logic type (`Setting`), a `define`d or `alias`ed name, or a built-in
 * constant such as `pi`.
 */
data class Name(val name: String) : Operand {
  override fun text() = name
}

/** `HASH("...")` or `STR("...")`: a string the game turns into a number. */
data class StringConstant(val kind: Kind, val value: String) : Operand {
  enum class Kind {
    HASH,
    STR,
  }

  init {
    require(value.isNotEmpty()) { "${kind.name}(\"\") is empty" }
    // The game cuts a line at its first `#`, even inside quotes.
    require('"' !in value && '#' !in value) {
      "string constants can't contain a double quote or a #"
    }
    // A line break would split the instruction across two lines of IC10 text. The game takes any
    // other character, a tab included.
    require('\n' !in value && '\r' !in value) { "string constants can't contain a line break" }
  }

  val numericValue: Double
    get() =
        when (kind) {
          Kind.HASH -> Hashes.hash(value).toDouble()
          Kind.STR -> Hashes.str(value)
        }

  override fun text() = "${kind.name}(\"$value\")"
}
