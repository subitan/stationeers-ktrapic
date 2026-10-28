package ktrapic.daemon.lsp

/**
 * A position in a document as LSP gives it: a 0-based [line], and a 0-based [character] counted in
 * UTF-16 code units, which is what a Kotlin [String]'s indices count too.
 */
internal data class Position(val line: Int, val character: Int)

internal data class Range(val start: Position, val end: Position)

/** One of a `didChange`'s content changes: [text] replaces [range], or the whole text if null. */
internal data class ContentChange(val range: Range?, val text: String)

/** An open document: its text as the client has it, and the version the client last gave it. */
internal class TextDocument(val uri: String, val languageId: String, version: Int, text: String) {
  var version = version
    private set

  var text = text
    private set

  /** Applies [changes] in order, each to the text the one before it left, and sets [version]. */
  fun apply(version: Int, changes: List<ContentChange>) {
    for (change in changes) {
      val range = change.range
      text =
          if (range == null) {
            change.text
          } else {
            val start = offsetAt(range.start)
            val end = offsetAt(range.end)
            text.substring(0, minOf(start, end)) + change.text + text.substring(maxOf(start, end))
          }
    }
    this.version = version
  }

  /**
   * The index in [text] of [position]. Lines end at `\n`, `\r\n` or `\r`, as LSP has it. A
   * character past the end of its line means the end of the line, and a line past the last means
   * the end of the text.
   */
  fun offsetAt(position: Position): Int {
    var lineStart = 0
    repeat(position.line.coerceAtLeast(0)) {
      val end = lineEnd(lineStart)
      if (end == text.length) return text.length
      lineStart = end + if (text.startsWith("\r\n", end)) 2 else 1
    }
    return lineStart + position.character.coerceIn(0, lineEnd(lineStart) - lineStart)
  }

  /** The index of the line ending after [from], or the text's length if there is none. */
  private fun lineEnd(from: Int): Int {
    var i = from
    while (i < text.length && text[i] != '\n' && text[i] != '\r') i++
    return i
  }
}
