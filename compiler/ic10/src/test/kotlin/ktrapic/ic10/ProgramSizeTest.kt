package ktrapic.ic10

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ProgramSizeTest {
  @Test
  fun `empty program is one empty line`() {
    assertThat(ProgramSize.of("")).isEqualTo(ProgramSize(lines = 1, bytes = 0, maxLineLength = 0))
  }

  @Test
  fun `trailing newline counts as an extra line`() {
    assertThat(ProgramSize.of("yield\nj 0").lines).isEqualTo(2)
    assertThat(ProgramSize.of("yield\nj 0\n").lines).isEqualTo(3)
  }

  @Test
  fun `a line break counts as two characters, as the game's editor joins lines with CRLF`() {
    assertThat(ProgramSize.of("yield\nj 0"))
        .isEqualTo(ProgramSize(lines = 2, bytes = 10, maxLineLength = 5))
    assertThat(ProgramSize.of("yield\r\nj 0")).isEqualTo(ProgramSize.of("yield\nj 0"))
  }

  @Test
  fun `trailing empty lines cost no characters`() {
    assertThat(ProgramSize.of("yield\n\n").bytes).isEqualTo(5)
    assertThat(ProgramSize.of("\nyield").bytes).isEqualTo(7)
  }

  @Test
  fun `bytes are counted as characters, not encoded bytes`() {
    // "°" is one UTF-16 code unit but two UTF-8 bytes; the game measures string length.
    assertThat(ProgramSize.of("# °C").bytes).isEqualTo(4)
  }

  @Test
  fun `program at the line limit fits`() {
    val program = List(Ic10Limits.MAX_LINES) { "yield" }.joinToString("\n")
    assertThat(ProgramSize.of(program).lines).isEqualTo(Ic10Limits.MAX_LINES)
    assertThat(ProgramSize.of(program).fits).isTrue()
  }

  @Test
  fun `program over the line limit does not fit`() {
    val program = List(Ic10Limits.MAX_LINES + 1) { "yield" }.joinToString("\n")
    assertThat(ProgramSize.of(program).fits).isFalse()
  }

  @Test
  fun `program at the byte limit fits`() {
    val size = ProgramSize.of(programOfLength(Ic10Limits.MAX_BYTES))
    assertThat(size.bytes).isEqualTo(Ic10Limits.MAX_BYTES)
    assertThat(size.fits).isTrue()
  }

  @Test
  fun `program over the byte limit does not fit`() {
    val size = ProgramSize.of(programOfLength(Ic10Limits.MAX_BYTES + 1))
    // Only the byte limit is exceeded.
    assertThat(size.lines).isAtMost(Ic10Limits.MAX_LINES)
    assertThat(size.maxLineLength).isAtMost(Ic10Limits.MAX_LINE_LENGTH)
    assertThat(size.bytes).isEqualTo(Ic10Limits.MAX_BYTES + 1)
    assertThat(size.fits).isFalse()
  }

  @Test
  fun `line at the length limit fits`() {
    val program = "#" + "x".repeat(Ic10Limits.MAX_LINE_LENGTH - 1)
    assertThat(ProgramSize.of(program).maxLineLength).isEqualTo(Ic10Limits.MAX_LINE_LENGTH)
    assertThat(ProgramSize.of(program).fits).isTrue()
  }

  @Test
  fun `line over the length limit does not fit`() {
    val program = "yield\n#" + "x".repeat(Ic10Limits.MAX_LINE_LENGTH) + "\nj 0"
    assertThat(ProgramSize.of(program).maxLineLength).isEqualTo(Ic10Limits.MAX_LINE_LENGTH + 1)
    assertThat(ProgramSize.of(program).fits).isFalse()
  }

  /**
   * A comment-only program measuring exactly [length], in lines of at most 89 characters, each line
   * break counting 2.
   */
  private fun programOfLength(length: Int): String {
    val lines = mutableListOf<String>()
    var remaining = length
    while (remaining > 0) {
      val separator = if (lines.isEmpty()) 0 else 2
      val lineLength = minOf(89, remaining - separator)
      lines += "#" + "x".repeat(lineLength - 1)
      remaining -= lineLength + separator
    }
    return lines.joinToString("\n")
  }
}
