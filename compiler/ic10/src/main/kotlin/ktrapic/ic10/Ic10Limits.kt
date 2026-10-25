package ktrapic.ic10

/**
 * The hard limits of a Stationeers IC10 chip that every compiled program must fit within.
 *
 * The chip itself enforces none of the size limits; the game's code editor does (128 lines of up to
 * 90 characters, 4096 characters in all), and so does the IC10 Editor mod on export
 * (aproposmath/StationeersIC10Editor, `Editor.ExportLimitExceeded`).
 */
object Ic10Limits {
  /** Maximum number of lines in a program. */
  const val MAX_LINES = 128

  /**
   * Maximum program size. Called "bytes" in the game, but measured in characters (UTF-16 code
   * units, C# `string.Length`), not encoded bytes; see [ProgramSize.bytes] for how lines count.
   */
  const val MAX_BYTES = 4096

  /** Maximum length of a single line, in UTF-16 code units. */
  const val MAX_LINE_LENGTH = 90

  /** General-purpose registers `r0`..`r15`; `sp` and `ra` are separate. */
  const val REGISTERS = 16

  /** Entries on the chip's stack. */
  const val STACK_SIZE = 512

  /** Device pins `d0`..`d5` on a circuit housing (`db` is the housing itself). */
  const val DEVICE_PINS = 6
}

/**
 * How much of the chip's size budget a program uses. [bytes] is counted as the game's editor counts
 * it, which is stricter than the IC10 Editor mod (which counts a line break as one character), so a
 * program that fits here fits both.
 */
data class ProgramSize(val lines: Int, val bytes: Int, val maxLineLength: Int) {
  val fits: Boolean
    get() =
        lines <= Ic10Limits.MAX_LINES &&
            bytes <= Ic10Limits.MAX_BYTES &&
            maxLineLength <= Ic10Limits.MAX_LINE_LENGTH

  companion object {
    /**
     * Measures IC10 source text. Carriage returns are dropped and the text is split on `\n` with
     * nothing trimmed, so a trailing newline counts as an extra (empty) line and an empty program
     * as one line: emitted code must not end with a newline. [bytes] is the game editor's count:
     * every line's length, plus 2 for each line break before the last non-empty line (the editor
     * joins lines with CRLF; trailing empty lines are free). Lengths are UTF-16 code units
     * (Kotlin's [String.length], matching C#'s).
     */
    fun of(code: String): ProgramSize {
      val text = code.replace("\r", "")
      val lines = text.split('\n')
      return ProgramSize(
          lines = lines.size,
          bytes =
              lines.sumOf { it.length } +
                  2 * lines.indexOfLast { it.isNotEmpty() }.coerceAtLeast(0),
          maxLineLength = lines.maxOf { it.length },
      )
    }
  }
}
