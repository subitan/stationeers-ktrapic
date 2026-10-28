package ktrapic.daemon.lsp

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class TextDocumentTest {
  private fun document(text: String) = TextDocument("file:///a.kt", "kotlin", 1, text)

  private fun change(
      startLine: Int,
      startCharacter: Int,
      endLine: Int,
      endCharacter: Int,
      text: String,
  ) =
      ContentChange(
          Range(Position(startLine, startCharacter), Position(endLine, endCharacter)),
          text,
      )

  @Test
  fun `finds offsets on each kind of line ending`() {
    val document = document("ab\ncd\r\nef\rgh")

    assertThat(document.offsetAt(Position(0, 1))).isEqualTo(1)
    assertThat(document.offsetAt(Position(1, 0))).isEqualTo(3)
    assertThat(document.offsetAt(Position(2, 1))).isEqualTo(8)
    assertThat(document.offsetAt(Position(3, 2))).isEqualTo(12)
  }

  @Test
  fun `clamps positions past the end of a line or of the text`() {
    val document = document("ab\r\ncd\n")

    // Past the end of a line is its end, before the line ending.
    assertThat(document.offsetAt(Position(0, 10))).isEqualTo(2)
    assertThat(document.offsetAt(Position(2, 0))).isEqualTo(7)
    assertThat(document.offsetAt(Position(5, 3))).isEqualTo(7)
  }

  @Test
  fun `counts characters in UTF-16 code units`() {
    // The emoji is one code point outside the BMP: two UTF-16 code units.
    val document = document("val s = \"😀a\"\nval t = 1\n")

    document.apply(2, listOf(change(0, 11, 0, 12, "b")))

    assertThat(document.text).isEqualTo("val s = \"😀b\"\nval t = 1\n")
    document.apply(3, listOf(change(0, 9, 0, 11, "")))
    assertThat(document.text).isEqualTo("val s = \"b\"\nval t = 1\n")
  }

  @Test
  fun `applies changes in order, each to the text before it`() {
    val document = document("one\ntwo\nthree\n")

    document.apply(
        5,
        listOf(
            change(0, 3, 1, 3, ""), // "one\nthree\n"
            change(1, 0, 1, 0, "2.5\n"), // "one\n2.5\nthree\n"
            change(3, 0, 3, 0, "four"), // inserted at the end
        ),
    )

    assertThat(document.text).isEqualTo("one\n2.5\nthree\nfour")
    assertThat(document.version).isEqualTo(5)
  }

  @Test
  fun `replaces the whole text on a change without a range`() {
    val document = document("old")

    document.apply(2, listOf(ContentChange(null, "new\ntext"), change(1, 0, 1, 4, "TEXT")))

    assertThat(document.text).isEqualTo("new\nTEXT")
  }
}
