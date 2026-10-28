package ktrapic.daemon.lsp

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.Test

class MessageTransportTest {
  private val logged = mutableListOf<String>()

  private fun reader(vararg chunks: ByteArray) =
      MessageReader(ByteArrayInputStream(chunks.reduce { a, b -> a + b }), logged::add)

  private fun frame(body: String, header: String = "Content-Length") =
      "$header: ${body.toByteArray().size}\r\n\r\n".toByteArray() + body.toByteArray()

  @Test
  fun `reads back-to-back messages`() {
    val reader = reader(frame("""{"a":1}"""), frame("""{"b":2}"""))

    assertThat(reader.read()).isEqualTo("""{"a":1}""")
    assertThat(reader.read()).isEqualTo("""{"b":2}""")
    assertThat(reader.read()).isNull()
  }

  @Test
  fun `counts the body in UTF-8 bytes`() {
    val body = """{"text":"é😀"}"""
    val reader = reader(frame(body), frame("{}"))

    assertThat(body.toByteArray().size).isGreaterThan(body.length)
    assertThat(reader.read()).isEqualTo(body)
    assertThat(reader.read()).isEqualTo("{}")
  }

  @Test
  fun `ignores other headers and the header name's case`() {
    val reader =
        reader(
            "Content-Type: application/vscode-jsonrpc; charset=utf-8\r\ncontent-length: 2\r\n\r\n{}"
                .toByteArray()
        )

    assertThat(reader.read()).isEqualTo("{}")
  }

  @Test
  fun `stops at a header block with no valid Content-Length`() {
    for (header in listOf("Content-Type: x", "Content-Length: -1", "Content-Length: many")) {
      logged.clear()
      val reader = reader("$header\r\n\r\n".toByteArray(), """{"a":1}""".toByteArray(), frame("{}"))

      assertThat(reader.read()).isNull()
      assertThat(logged).isNotEmpty()
    }
  }

  @Test
  fun `stops at the end of the input, even in the middle of a message`() {
    assertThat(reader("Content-Length: 10\r\n\r\n{}".toByteArray()).read()).isNull()
    assertThat(reader("Content-Len".toByteArray()).read()).isNull()
    assertThat(reader(ByteArray(0)).read()).isNull()
  }

  @Test
  fun `writes a Content-Length header counting UTF-8 bytes`() {
    val output = ByteArrayOutputStream()

    MessageWriter(output).write("""{"text":"é😀"}""")

    val written = output.toByteArray()
    assertThat(String(written, Charsets.UTF_8))
        .isEqualTo("Content-Length: 17\r\n\r\n{\"text\":\"é😀\"}")
    assertThat(MessageReader(ByteArrayInputStream(written), logged::add).read())
        .isEqualTo("""{"text":"é😀"}""")
  }
}
