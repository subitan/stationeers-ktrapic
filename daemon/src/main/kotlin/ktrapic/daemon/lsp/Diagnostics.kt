package ktrapic.daemon.lsp

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** LSP's `DiagnosticSeverity`. */
internal object Severity {
  const val ERROR = 1
  const val WARNING = 2
}

/**
 * The LSP diagnostics for a compile of [text]: [result] is what [ktrapic.daemon.Daemon.compile]
 * answered. A failure gives each of its errors (`error.errors`, or `error` itself if it has none,
 * as an internal compiler error has none) with severity 1; a success gives its warnings with
 * severity 2, so a program that compiles without warnings gives none.
 */
internal fun diagnosticsOf(result: JsonObject, text: String): JsonArray {
  val error = result["error"]?.takeIf { it.isJsonObject }?.asJsonObject
  val (problems, severity) =
      if (error != null) {
        (error["errors"]?.takeIf { it.isJsonArray }?.asJsonArray?.toList() ?: listOf(error)) to
            Severity.ERROR
      } else {
        result["warnings"]?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty() to
            Severity.WARNING
      }
  return JsonArray().apply {
    for (problem in problems.filter { it.isJsonObject }.map { it.asJsonObject }) {
      add(
          JsonObject().apply {
            add("range", rangeOf(text, problem.intOrZero("line"), problem.intOrZero("column")))
            addProperty("severity", severity)
            addProperty("source", "ktrapic")
            addProperty("message", problem["description"]?.asString ?: "error")
          }
      )
    }
  }
}

private fun JsonObject.intOrZero(name: String): Int =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: 0

/**
 * The LSP range in [text] of a problem the compiler places at a 1-based [line] and [column], or at
 * the start of the text if [line] is 0 (no position).
 *
 * The compiler counts lines by `\n` only, and columns in UTF-16 code units (a Kotlin [String]'s
 * indices) without a leading byte-order mark. LSP counts lines ending in `\n`, `\r\n` or `\r`, and
 * characters in UTF-16 code units too, with the mark. A column past the end of its line means the
 * end of the line.
 *
 * The compiler gives only where a problem starts, so the range covers the identifier there, or the
 * one character there if it is not part of one (empty at the end of a line). Clients that mark only
 * a range's start, as the IC10 Editor does, mark the same token either way.
 */
internal fun rangeOf(text: String, line: Int, column: Int): JsonElement {
  val start = if (line <= 0) 0 else offsetOf(text, line, column)
  val first = if (start < text.length) text.codePointAt(start) else '\n'.code
  val end =
      when {
        first == '\n'.code || first == '\r'.code -> start
        !Character.isJavaIdentifierPart(first) -> start + Character.charCount(first)
        else -> {
          var end = start
          while (end < text.length && Character.isJavaIdentifierPart(text.codePointAt(end))) {
            end += Character.charCount(text.codePointAt(end))
          }
          end
        }
      }
  return JsonObject().apply {
    add("start", positionAt(text, start))
    add("end", positionAt(text, end))
  }
}

/** The index in [text] of the compiler's 1-based [line] and [column]. */
private fun offsetOf(text: String, line: Int, column: Int): Int {
  var lineStart = 0
  repeat(line - 1) {
    val newline = text.indexOf('\n', lineStart)
    if (newline < 0) return text.length
    lineStart = newline + 1
  }
  // The compiler drops a leading byte-order mark before counting columns.
  if (lineStart == 0 && text.startsWith(0xFEFF.toChar())) lineStart = 1
  var lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
  if (lineEnd > lineStart && text[lineEnd - 1] == '\r') lineEnd--
  return lineStart + (column - 1).coerceIn(0, lineEnd - lineStart)
}

/** The LSP position of [offset] in [text]: lines end at `\n`, `\r\n` or `\r`. */
private fun positionAt(text: String, offset: Int): JsonObject {
  var line = 0
  var lineStart = 0
  var i = 0
  while (i < offset) {
    val c = text[i]
    if (c == '\n' || (c == '\r' && !text.startsWith("\r\n", i))) {
      line++
      lineStart = i + 1
    }
    i++
  }
  return JsonObject().apply {
    addProperty("line", line)
    addProperty("character", offset - lineStart)
  }
}
