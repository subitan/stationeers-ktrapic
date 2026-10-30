package ktrapic.intellij

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** How the daemon compiles a program: a compile request's `options`. */
data class CompileOptions(
    val compact: Boolean = false,
    val stackCalls: Boolean = true,
    val tailCalls: Boolean = true,
) {
  fun toJson(): JsonObject =
      JsonObject().apply {
        addProperty("compact", compact)
        addProperty("stack_calls", stackCalls)
        addProperty("tail_calls", tailCalls)
      }
}

/**
 * An error or warning at a 1-based [line] and [column] of the source, or with no position when
 * [line] is 0. [kotlin] marks the Kotlin compiler's own problems, which the Kotlin plugin reports
 * already.
 */
data class Problem(
    val description: String,
    val line: Int = 0,
    val column: Int = 0,
    val kotlin: Boolean = false,
)

/** The daemon's answer to a compile request. */
sealed interface CompileResponse {
  /** The program compiled: [code] is the IC10, measured in [lines], [bytes] and [registers]. */
  data class Success(
      val code: String,
      val lines: Int,
      val bytes: Int,
      val registers: Int,
      val warnings: List<Problem>,
  ) : CompileResponse

  /**
   * The program didn't compile, for [errors], of which there is at least one. [answered] is false
   * when the daemon gave no answer for the program (it isn't there, it stopped, or its answer
   * couldn't be read), so compiling the same text again may succeed.
   */
  data class Failure(val errors: List<Problem>, val answered: Boolean = true) : CompileResponse {
    constructor(
        description: String,
        answered: Boolean = true,
    ) : this(listOf(Problem(description)), answered)
  }

  companion object {
    /** The response a daemon's response line holds, as JSON. */
    fun parse(json: String): CompileResponse =
        try {
          val response = JsonParser.parseString(json)
          if (response.isJsonObject) of(response.asJsonObject)
          else
              Failure(
                  "The compile daemon answered with something other than an object",
                  answered = false,
              )
        } catch (e: RuntimeException) {
          Failure("The compile daemon's answer couldn't be read: ${e.message}", answered = false)
        }

    private fun of(response: JsonObject): CompileResponse {
      val error = response.objectAt("error")
      if (error != null) {
        val errors = error.arrayAt("errors")?.mapNotNull { problem(it) }.orEmpty()
        return Failure(errors.ifEmpty { listOf(problem(error) ?: Problem("Unknown error")) })
      }
      val code =
          response.stringAt("code")
              ?: return Failure(
                  "The compile daemon answered with neither code nor an error",
                  answered = false,
              )
      return Success(
          code = code,
          lines = response.intAt("num_lines"),
          bytes = response.intAt("num_bytes"),
          registers = response.intAt("num_registers"),
          warnings = response.arrayAt("warnings")?.mapNotNull { problem(it) }.orEmpty(),
      )
    }

    private fun problem(element: JsonElement): Problem? {
      if (!element.isJsonObject) return null
      val problem = element.asJsonObject
      return Problem(
          description = problem.stringAt("description") ?: "Unknown error",
          line = problem.intAt("line"),
          column = problem.intAt("column"),
          kotlin =
              problem["kotlin"]
                  ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
                  ?.asBoolean ?: false,
      )
    }

    private fun JsonObject.objectAt(name: String): JsonObject? =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.arrayAt(name: String): JsonArray? =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject.stringAt(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.intAt(name: String): Int =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: 0
  }
}
