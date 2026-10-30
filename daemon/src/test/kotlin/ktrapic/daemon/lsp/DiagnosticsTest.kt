package ktrapic.daemon.lsp

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import ktrapic.daemon.Daemon
import org.junit.jupiter.api.Test

class DiagnosticsTest {
  private fun range(startLine: Int, startCharacter: Int, endLine: Int, endCharacter: Int) =
      JsonParser.parseString(
          """{"start": {"line": $startLine, "character": $startCharacter},
            "end": {"line": $endLine, "character": $endCharacter}}"""
      )

  private fun json(text: String) = JsonParser.parseString(text).asJsonObject

  @Test
  fun `converts the compiler's 1-based line and column to an LSP range over the identifier`() {
    assertThat(rangeOf("a = 1\nval bar2 = x\n", 2, 5)).isEqualTo(range(1, 4, 1, 8))
  }

  @Test
  fun `counts columns in UTF-16 code units, as the compiler does`() {
    val text = "val s = \"😀\"; d0.Foo = 1.0\n"
    // The compiler's own position for Foo, which is past the emoji's two code units.
    val error = Daemon.compile(text)["error"].asJsonObject

    assertThat(error["column"].asInt).isEqualTo(18)
    assertThat(rangeOf(text, 1, 18)).isEqualTo(range(0, 17, 0, 20))
  }

  @Test
  fun `covers one character, a whole surrogate pair, where no identifier starts`() {
    assertThat(rangeOf("x = (1\n", 1, 3)).isEqualTo(range(0, 2, 0, 3))
    assertThat(rangeOf("x = 😀\n", 1, 5)).isEqualTo(range(0, 4, 0, 6))
  }

  @Test
  fun `is empty at the end of a line, and a column past it means the end of the line`() {
    assertThat(rangeOf("abc\ndef", 1, 4)).isEqualTo(range(0, 3, 0, 3))
    assertThat(rangeOf("abc\r\ndef", 1, 9)).isEqualTo(range(0, 3, 0, 3))
    assertThat(rangeOf("abc", 1, 9)).isEqualTo(range(0, 3, 0, 3))
  }

  @Test
  fun `puts a problem with no position at the start of the text`() {
    assertThat(rangeOf("abc def\n", 0, 0)).isEqualTo(range(0, 0, 0, 3))
  }

  @Test
  fun `counts a leading byte-order mark, which the compiler skips, on the first line only`() {
    val text = "${0xFEFF.toChar()}db.Setting = nope\nd0.On = foo\n"
    val error = Daemon.compile(text)["error"].asJsonObject
    val (first, second) = error["errors"].asJsonArray.map { it.asJsonObject }

    assertThat(first["column"].asInt).isEqualTo(14)
    assertThat(rangeOf(text, 1, 14)).isEqualTo(range(0, 14, 0, 18))
    assertThat(second["column"].asInt).isEqualTo(9)
    assertThat(rangeOf(text, 2, 9)).isEqualTo(range(1, 8, 1, 11))
  }

  @Test
  fun `counts lines as LSP does when the text has carriage returns`() {
    // The compiler counts lines by \n only; LSP also ends a line at a lone \r.
    assertThat(rangeOf("a\r\nb = c\r\n", 2, 5)).isEqualTo(range(1, 4, 1, 5))
    assertThat(rangeOf("a\rb\nc = d\n", 2, 5)).isEqualTo(range(2, 4, 2, 5))
  }

  @Test
  fun `gives a failure's errors with severity 1`() {
    val diagnostics =
        diagnosticsOf(
            json(
                """{"error": {"description": "first", "line": 1, "column": 1, "line_end": 1,
                  "column_end": 1, "errors": [
                    {"description": "first", "line": 1, "column": 1},
                    {"description": "second", "line": 2, "column": 3}]}}"""
            ),
            "abc\nde fg\n",
        )

    assertThat(diagnostics)
        .isEqualTo(
            JsonParser.parseString(
                """[{"range": ${range(0, 0, 0, 3)}, "severity": 1, "source": "ktrapic",
                    "message": "first"},
                  {"range": ${range(1, 2, 1, 3)}, "severity": 1, "source": "ktrapic",
                    "message": "second"}]"""
            )
        )
  }

  @Test
  fun `gives an internal compiler error, which has no position or list, at the start`() {
    val diagnostics =
        diagnosticsOf(
            json(
                """{"error": {"description": "Internal compiler error: boom", "stack_trace": ""}}"""
            ),
            "abc\n",
        )

    assertThat(diagnostics.single().asJsonObject["range"]).isEqualTo(range(0, 0, 0, 3))
    assertThat(diagnostics.single().asJsonObject["message"].asString)
        .isEqualTo("Internal compiler error: boom")
  }

  @Test
  fun `gives a success's warnings with severity 2, and nothing for a clean success`() {
    val warned =
        diagnosticsOf(
            json(
                """{"code": "", "warnings": [{"description": "careful", "line": 1, "column": 5}],
                  "source_mapping": {}}"""
            ),
            "val x = 1\n",
        )
    val clean = diagnosticsOf(Daemon.compile("db.Setting = 1.0\n"), "db.Setting = 1.0\n")

    assertThat(warned.single().asJsonObject["severity"].asInt).isEqualTo(2)
    assertThat(warned.single().asJsonObject["range"]).isEqualTo(range(0, 4, 0, 5))
    assertThat(clean.size()).isEqualTo(0)
  }
}
