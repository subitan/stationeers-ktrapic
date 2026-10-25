package ktrapic.ic10

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ProgramSizeTest {
  @Test
  fun `empty program has no lines`() {
    assertThat(ProgramSize.of("")).isEqualTo(ProgramSize(lines = 0, bytes = 0))
  }

  @Test
  fun `trailing newline does not add a line`() {
    assertThat(ProgramSize.of("yield\nj 0\n").lines).isEqualTo(2)
    assertThat(ProgramSize.of("yield\nj 0").lines).isEqualTo(2)
  }

  @Test
  fun `bytes are counted as UTF-8`() {
    assertThat(ProgramSize.of("# °C\n").bytes).isEqualTo(6)
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
  fun `program over the byte limit does not fit`() {
    val program = "#" + "x".repeat(Ic10Limits.MAX_BYTES)
    assertThat(ProgramSize.of(program).fits).isFalse()
  }
}
