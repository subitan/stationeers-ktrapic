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
  fun `a position is in the source unless it is in inserted text or the closing text`() {
    assertThat(wrapped.isInSource(1, 1)).isTrue()
    // "init { " is columns 1 to 7, "x()" 8 to 10, " }" 11 and 12.
    assertThat((1..12).filter { wrapped.isInSource(2, it) }).containsExactly(8, 9, 10)
    // Just past the end of the line is still the user's: K2 reports some problems there.
    assertThat(wrapped.isInSource(2, 13)).isTrue()
    assertThat(wrapped.isInSource(4, 1)).isFalse()
    assertThat(wrapped.isInSource(0, 1)).isFalse()
  }

  @Test
  fun `an offset in the source moves past the text inserted before or at it`() {
    assertThat(wrapped.wrappedOffset(0)).isEqualTo(0)
    // The "x", after "init { ".
    assertThat(wrapped.wrappedOffset(2)).isEqualTo(9)
    assertThat(wrapped.text[9]).isEqualTo('x')
    // The newline after "x()", after " }" too.
    assertThat(wrapped.wrappedOffset(5)).isEqualTo(14)
    assertThat(wrapped.text[14]).isEqualTo('\n')
  }

  @Test
  fun `a position in the closing text maps to the end of the source`() {
    // The source's last line is the empty line 3, after the final newline.
    assertThat(wrapped.originalPosition(3, 1)).isEqualTo(3 to 1)
    assertThat(wrapped.originalPosition(4, 2)).isEqualTo(3 to 1)
  }
}
