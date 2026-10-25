package ktrapic.frontend

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class WrappedSourceTest {
  // "x()" on line 2 wrapped as "init { x() }": 7 characters inserted at column 0, 2 at column 3,
  // then a closing brace on a line after the source.
  private val wrapped =
      WrappedSource(
          "a\ninit { x() }\n\n}\n",
          "a\nx()\n",
          mapOf(2 to listOf(WrappedSource.Insertion(0, 7), WrappedSource.Insertion(3, 2))),
      )

  @Test
  fun `lines without insertions keep their columns`() {
    assertThat(wrapped.originalPosition(1, 1)).isEqualTo(1 to 1)
  }

  @Test
  fun `columns after an insertion shift back by its length`() {
    // Generated column 8 is the "x" just after "init { ".
    assertThat(wrapped.originalPosition(2, 8)).isEqualTo(2 to 1)
    assertThat(wrapped.originalPosition(2, 10)).isEqualTo(2 to 3)
  }

  @Test
  fun `a column inside inserted text maps to where the insertion was made`() {
    assertThat(wrapped.originalPosition(2, 3)).isEqualTo(2 to 1)
    assertThat(wrapped.originalPosition(2, 12)).isEqualTo(2 to 4)
  }

  @Test
  fun `a position in the closing text maps to the end of the source`() {
    // The source's last line is the empty line 3, after the final newline.
    assertThat(wrapped.originalPosition(3, 1)).isEqualTo(3 to 1)
    assertThat(wrapped.originalPosition(4, 2)).isEqualTo(3 to 1)
  }
}
