package ktrapic.daemon

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Base64
import ktrapic.api.CompileOptions
import ktrapic.api.CompileResult
import ktrapic.api.Compiler

/**
 * The compile daemon's protocol, as PyTrapIC's (`stationeers_pytrapic/mod_daemon.py`): each request
 * is one line of base64-encoded JSON, `{"action": "compile", "code": ..., "options": {...}}`, and
 * gets one line of base64-encoded JSON back. `code` is the source, or a map of module names to
 * sources whose `""` entry is the program. A success is `{"code", "num_lines", "num_bytes",
 * "num_registers", "warnings"}`; a failure is `{"error": {"description", "line", "column", ...}}`.
 */
object Daemon {
  private val gson = Gson()

  /** The response line for one request line. */
  fun handle(line: String): String {
    val response =
        try {
          respond(
              JsonParser.parseString(String(Base64.getDecoder().decode(line.trim()))).asJsonObject
          )
        } catch (e: IllegalArgumentException) {
          error("Requests must be base64-encoded JSON objects")
        } catch (e: IllegalStateException) {
          error("Requests must be base64-encoded JSON objects")
        } catch (e: com.google.gson.JsonParseException) {
          error("Requests must be base64-encoded JSON objects")
        }
    return Base64.getEncoder().encodeToString(gson.toJson(response).toByteArray())
  }

  private fun respond(request: JsonObject): JsonObject {
    val action = request["action"]?.takeIf { it.isJsonPrimitive }?.asString
    if (action != "compile") return error("Invalid action '$action'")
    val code = request["code"] ?: return error("No code provided")
    val source =
        when {
          code.isJsonPrimitive -> code.asString
          code.isJsonObject -> code.asJsonObject[""]?.asString ?: return error("No code provided")
          else -> return error("No code provided")
        }
    val options = request["options"]?.takeIf { it.isJsonObject }?.asJsonObject
    val compact = options?.get("compact")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
    return when (val result = Compiler.compile(source, CompileOptions(compact = compact))) {
      is CompileResult.Success ->
          JsonObject().apply {
            addProperty("code", result.code)
            addProperty("num_lines", result.lines)
            addProperty("num_bytes", result.bytes)
            addProperty("num_registers", result.registers)
            add("warnings", gson.toJsonTree(result.warnings.map { describe(it) }))
          }
      is CompileResult.Failure -> {
        val first = result.errors.first()
        JsonObject().apply {
          add(
              "error",
              JsonObject().apply {
                addProperty("description", first.message)
                addProperty("line", first.line)
                addProperty("column", first.column)
                addProperty("line_end", first.line)
                addProperty("column_end", first.column)
                add("errors", gson.toJsonTree(result.errors.map { describe(it) }))
              },
          )
        }
      }
    }
  }

  private fun describe(error: ktrapic.api.CompileError) =
      mapOf("description" to error.message, "line" to error.line, "column" to error.column)

  private fun error(description: String) =
      JsonObject().apply {
        add("error", JsonObject().apply { addProperty("description", description) })
      }
}

/** Serves [Daemon] requests on stdin until EOF or an `EXIT` line. Logging goes to stderr. */
fun main() {
  System.err.println("KTrapIC compile daemon ready")
  while (true) {
    val line = readlnOrNull()?.trim() ?: break
    if (line == "EXIT") break
    if (line.isEmpty()) continue
    println(Daemon.handle(line))
    System.out.flush()
  }
}
