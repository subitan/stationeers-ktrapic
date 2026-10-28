package ktrapic.daemon.lsp

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Reads the LSP base protocol's messages: a block of ASCII header lines, each ending in `\r\n`,
 * ended by an empty line, then a body of exactly `Content-Length` bytes of UTF-8. Other headers
 * (`Content-Type`) are ignored. It reads raw bytes, so the platform's charset never applies.
 */
internal class MessageReader(input: InputStream, private val log: (String) -> Unit) {
  private val input = input as? BufferedInputStream ?: BufferedInputStream(input)

  /**
   * The next message's body, or null at the end of the input or at a header block with no valid
   * `Content-Length`. Without a length there is no telling where that block's body ends, and so
   * where the next message starts, so nothing after it can be read.
   */
  fun read(): String? {
    val length = readHeaders() ?: return null
    if (length < 0) {
      log("A header block has no valid Content-Length, so no further messages can be read")
      return null
    }
    val body = input.readNBytes(length)
    if (body.size < length) {
      log("The input ended in the middle of a message")
      return null
    }
    return String(body, Charsets.UTF_8)
  }

  /**
   * Reads one header block and returns its `Content-Length`: -1 if it had no valid one, and null at
   * the end of the input.
   */
  private fun readHeaders(): Int? {
    var length = -1
    var any = false
    while (true) {
      val line = readLine() ?: return null
      if (line.isEmpty()) {
        // Blank lines before a header block are tolerated.
        if (any) return length else continue
      }
      any = true
      val name = line.substringBefore(':', missingDelimiterValue = "").trim()
      if (name.equals("Content-Length", ignoreCase = true)) {
        length = line.substringAfter(':').trim().toIntOrNull()?.takeIf { it >= 0 } ?: -1
      }
    }
  }

  /** One header line without its line ending, or null at the end of the input. */
  private fun readLine(): String? {
    val bytes = ByteArrayOutputStream()
    while (true) {
      val b = input.read()
      if (b < 0) return null
      if (b == '\n'.code) break
      bytes.write(b)
    }
    val line = bytes.toString(Charsets.US_ASCII)
    return line.removeSuffix("\r")
  }
}

/** Writes LSP base-protocol messages: a `Content-Length` header, then the body as UTF-8. */
internal class MessageWriter(private val output: OutputStream) {
  @Synchronized
  fun write(body: String) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    val header = "Content-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.US_ASCII)
    output.write(header + bytes)
    output.flush()
  }
}
