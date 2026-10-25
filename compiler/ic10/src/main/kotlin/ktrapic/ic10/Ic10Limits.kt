package ktrapic.ic10

/**
 * The hard limits of a Stationeers IC10 chip that every compiled program must fit within.
 *
 * The size limits are the ones the IC10 Editor mod enforces on export
 * (aproposmath/StationeersIC10Editor, `Editor.ExportLimitExceeded` and
 * `ICodeFormatter.UpdateCodeSize`).
 */
object Ic10Limits {
  /** Maximum number of lines in a program. */
  const val MAX_LINES = 128

  /**
   * Maximum program size. Called "bytes" in the game and the IC10 Editor, but measured as the
   * length of the program string in UTF-16 code units (C# `string.Length`), not encoded bytes.
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

/** How much of the chip's size budget a program uses, measured as the IC10 Editor does. */
data class ProgramSize(val lines: Int, val bytes: Int, val maxLineLength: Int) {
  val fits: Boolean
    get() =
        lines <= Ic10Limits.MAX_LINES &&
            bytes <= Ic10Limits.MAX_BYTES &&
            maxLineLength <= Ic10Limits.MAX_LINE_LENGTH

  companion object {
    /**
     * Measures IC10 source text the way the IC10 Editor does: carriage returns are dropped, then
     * the text is split on `\n` with nothing trimmed. A trailing newline therefore counts as an
     * extra (empty) line, and an empty program as one line, so emitted code must not end with a
     * newline. [bytes] and [maxLineLength] are lengths in UTF-16 code units (Kotlin's
     * [String.length], matching C#'s).
     */
    fun of(code: String): ProgramSize {
      val text = code.replace("\r", "")
      val lines = text.split('\n')
      return ProgramSize(
          lines = lines.size,
          bytes = text.length,
          maxLineLength = lines.maxOf { it.length },
      )
    }
  }
}
