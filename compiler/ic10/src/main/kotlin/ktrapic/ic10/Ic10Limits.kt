package ktrapic.ic10

/** The hard limits of a Stationeers IC10 chip that every compiled program must fit within. */
object Ic10Limits {
  /** Maximum number of lines in a program. */
  const val MAX_LINES = 128

  /** Maximum program size in bytes. */
  const val MAX_BYTES = 4096

  /** General-purpose registers `r0`..`r15`; `sp` and `ra` are separate. */
  const val REGISTERS = 16

  /** Entries on the chip's stack. */
  const val STACK_SIZE = 512

  /** Device pins `d0`..`d5` on a circuit housing (`db` is the housing itself). */
  const val DEVICE_PINS = 6
}

/** How much of the chip's line and byte budget a program uses. */
data class ProgramSize(val lines: Int, val bytes: Int) {
  val fits: Boolean
    get() = lines <= Ic10Limits.MAX_LINES && bytes <= Ic10Limits.MAX_BYTES

  companion object {
    /**
     * Measures IC10 source text. A trailing newline doesn't start another line; bytes are counted
     * as UTF-8, as the game stores them.
     */
    fun of(code: String): ProgramSize {
      val lines = if (code.isEmpty()) 0 else code.removeSuffix("\n").count { it == '\n' } + 1
      return ProgramSize(lines, code.toByteArray(Charsets.UTF_8).size)
    }
  }
}
