package ktrapic.intellij

/** One of a program's sizes, against the chip's [limit]. */
data class Count(val name: String, val value: Int, val limit: Int) {
  val over: Boolean
    get() = value > limit

  override fun toString(): String = "$value / $limit $name"
}

/**
 * What the IC10 tool window shows for a compile: the [code], the [counts] against the chip's
 * limits, and the [warnings], or the [errors] when it didn't compile.
 */
data class Ic10View(
    val code: String,
    val counts: List<Count>,
    val warnings: List<String>,
    val errors: List<String>,
) {
  val over: Boolean
    get() = counts.any { it.over }

  /** The counts as one line, with those over the limit marked. */
  val countsText: String
    get() = counts.joinToString("   ") { if (it.over) "$it (over)" else it.toString() }
}

/** How the IC10 tool window shows a compile. */
object Ic10Format {
  const val MAX_LINES = 128
  const val MAX_BYTES = 4096
  const val REGISTERS = 16

  fun view(response: CompileResponse): Ic10View =
      when (response) {
        is CompileResponse.Success ->
            Ic10View(
                code = response.code,
                counts =
                    listOf(
                        Count("lines", response.lines, MAX_LINES),
                        Count("bytes", response.bytes, MAX_BYTES),
                        Count("registers", response.registers, REGISTERS),
                    ),
                warnings = response.warnings.map { describe(it) },
                errors = emptyList(),
            )
        is CompileResponse.Failure ->
            Ic10View(
                code = "",
                counts = emptyList(),
                warnings = emptyList(),
                errors = response.errors.map { describe(it) },
            )
      }

  /** A problem as a line of text: its position, if it has one, and its description. */
  fun describe(problem: Problem): String {
    val origin = if (problem.kotlin) " (Kotlin)" else ""
    return if (problem.line <= 0) "${problem.description}$origin"
    else "Line ${problem.line}:${problem.column}$origin: ${problem.description}"
  }
}
