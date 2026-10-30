package ktrapic.intellij

/**
 * A problem to mark in the editor: an error or a warning over [start] until [end] (offsets in the
 * text), or about the whole file when [fileLevel].
 */
data class Mark(
    val error: Boolean,
    val message: String,
    val start: Int = 0,
    val end: Int = 0,
    val fileLevel: Boolean = false,
)

/** Where a compile's errors and warnings go in the program's text. */
object ProblemMarks {
  /** The text before each message, to tell KTrapIC's problems from the Kotlin plugin's. */
  const val PREFIX = "KTrapIC: "

  /**
   * The marks for [response], a compile of [text]: each error as an error and each warning as a
   * warning. The Kotlin compiler's own problems are left out unless [showKotlin], as the Kotlin
   * plugin shows them already, except a failure's errors when they are all the Kotlin compiler's:
   * the daemon compiles the program wrapped in an object, so some of those are errors the Kotlin
   * plugin doesn't see in the script, and a failed compile marks something. A problem with no
   * position (line 0) is about the whole file.
   *
   * The compiler gives only where a problem starts, so a mark covers the name or number there, or
   * else one character, or the line's end when it is at the end of a line.
   */
  fun of(response: CompileResponse, text: CharSequence, showKotlin: Boolean): List<Mark> {
    val (errors, warnings) =
        when (response) {
          is CompileResponse.Failure -> response.errors to emptyList()
          is CompileResponse.Success -> emptyList<Problem>() to response.warnings
        }
    val showKotlinErrors = showKotlin || errors.all { it.kotlin }
    val lines = LineStarts(text)
    return errors.filter { showKotlinErrors || !it.kotlin }.map { mark(it, true, text, lines) } +
        warnings.filter { showKotlin || !it.kotlin }.map { mark(it, false, text, lines) }
  }

  private fun mark(problem: Problem, error: Boolean, text: CharSequence, lines: LineStarts): Mark {
    val message = PREFIX + problem.description
    if (problem.line <= 0) return Mark(error, message, fileLevel = true)
    val line = minOf(problem.line, lines.count) - 1
    val lineStart = lines.start(line)
    val lineEnd = lines.end(line)
    val start = (lineStart + maxOf(problem.column, 1) - 1).coerceAtMost(lineEnd)
    var end = start
    while (end < lineEnd && Character.isJavaIdentifierPart(text[end])) end++
    if (end == start && start < lineEnd) end = start + 1
    return Mark(error, message, start, end)
  }

  /** Where each line of a text starts. */
  private class LineStarts(private val text: CharSequence) {
    private val starts = listOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }

    val count: Int
      get() = starts.size

    fun start(line: Int): Int = starts[line]

    /** The offset of [line]'s end, before its line break. */
    fun end(line: Int): Int {
      val next = if (line + 1 < starts.size) starts[line + 1] - 1 else text.length
      return if (next > starts[line] && text[next - 1] == '\r') next - 1 else next
    }
  }
}
