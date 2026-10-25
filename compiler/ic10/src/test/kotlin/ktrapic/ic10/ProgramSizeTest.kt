package ktrapic.ic10

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ProgramSizeTest {
  @Test
  fun `empty program has no lines`() {
    assertThat(ProgramSize.of("")).isEqualTo(ProgramSize(lines = 0, bytes = 0, maxLineLength = 0))
  }

  @Test
  fun `trailing newline does not add a line`() {
    assertThat(ProgramSize.of("yield\nj 0\n").lines).isEqualTo(2)
    assertThat(ProgramSize.of("yield\nj 0").lines).isEqualTo(2)
  }

  @Test
  fun `bytes are counted as characters, not encoded bytes`() {
    // "°" is one UTF-16 code unit but two UTF-8 bytes; the game measures string length.
    assertThat(ProgramSize.of("# °C\n").bytes).isEqualTo(5)
  }

  @Test
  fun `program at the line limit fits`() {
    val program = "yield\n".repeat(Ic10Limits.MAX_LINES)
    assertThat(ProgramSize.of(program).fits).isTrue()
  }

  @Test
  fun `program over the line limit does not fit`() {
    val program = "yield\n".repeat(Ic10Limits.MAX_LINES + 1)
    assertThat(ProgramSize.of(program).fits).isFalse()
  }

  @Test
  fun `program at the byte limit fits`() {
    val line = "#" + "x".repeat(Ic10Limits.MAX_LINE_LENGTH - 2) + "\n"
    val program =
        line.repeat(Ic10Limits.MAX_BYTES / line.length).let {
          it + "#".repeat(Ic10Limits.MAX_BYTES - it.length)
        }
    val size = ProgramSize.of(program)
    assertThat(size.bytes).isEqualTo(Ic10Limits.MAX_BYTES)
    assertThat(size.fits).isTrue()
  }

  @Test
  fun `program over the byte limit does not fit`() {
    val program = "#\n".repeat(Ic10Limits.MAX_BYTES / 2) + "#"
    assertThat(ProgramSize.of(program).bytes).isEqualTo(Ic10Limits.MAX_BYTES + 1)
    assertThat(ProgramSize.of(program).fits).isFalse()
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
}
