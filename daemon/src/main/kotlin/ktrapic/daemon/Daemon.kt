package ktrapic.daemon

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedOutputStream
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.util.Base64
import kotlin.system.exitProcess
import ktrapic.api.CompileOptions
import ktrapic.api.CompileResult
import ktrapic.api.Compiler
import ktrapic.daemon.lsp.LspServer

/**
 * The compile daemon's protocol, as PyTrapIC's (`stationeers_pytrapic/mod_daemon.py`): each request
 * is one line of base64-encoded JSON, `{"action": "compile", "code": ..., "options": {...}}`, and
 * gets one line of base64-encoded JSON back. `code` is the source, or a map of module names to
 * sources whose `""` entry is the program. A success is `{"code", "num_lines", "num_bytes",
 * "num_registers", "warnings", "source_mapping"}`; a failure is `{"error": {"description", "line",
 * "column", ...}}`. `source_mapping` maps each 0-based source line, as a string key, to the 0-based
 * lines of `code` it compiled to, as PyTrapIC's does.
 *
 * Unlike PyTrapIC, `line` and `column` are both 1-based, and `line_end`/`column_end` repeat them
 * because the compiler reports only where a problem starts. All four are 0 when the compiler gives
 * no position, as for a failure inside the compiler that it reports as an error. A request that is
 * not a compile request gets `{"error": {"description"}}`, with an object rather than PyTrapIC's
 * plain string.
 *
 * Every request line gets exactly one response line, whatever it holds, so a client can read one
 * line per line it writes.
 *
 * Started with `--lsp`, the daemon speaks the Language Server Protocol instead (see [LspServer]),
 * whose `ktrapic/compile` request answers with the same JSON as a compile request here.
 */
object Daemon {
  private val gson = Gson()

  /** The response line for one request line. */
  fun handle(line: String): String {
    val request = decode(line)
    val response =
        if (request == null) error("Requests must be base64-encoded JSON objects")
        else guarded { respond(request) }
    return Base64.getEncoder().encodeToString(gson.toJson(response).toByteArray())
  }

  /**
   * The response to compiling [source] with [options], a compile request's `options` object: the
   * JSON object that a compile request's response line encodes. Failures inside the compiler are
   * answered as errors too, as [handle] answers them.
   */
  fun compile(source: String, options: JsonObject?): JsonObject = guarded {
    val compact = options?.get("compact")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
    when (val result = Compiler.compile(source, CompileOptions(compact = compact))) {
      is CompileResult.Success ->
          JsonObject().apply {
            addProperty("code", result.code)
            addProperty("num_lines", result.lines)
            addProperty("num_bytes", result.bytes)
            addProperty("num_registers", result.registers)
            add("warnings", gson.toJsonTree(result.warnings.map { describe(it) }))
            add("source_mapping", gson.toJsonTree(result.sourceMap))
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

  /** What [respond] returns, or an error response if the compiler fails. */
  private inline fun guarded(respond: () -> JsonObject): JsonObject =
      try {
        respond()
      } catch (e: Throwable) {
        // The compiler can fail with an Error as well as an Exception: its recursive-descent
        // parser overflows the stack on deeply nested source, and its internal checks throw
        // AssertionError. A stack overflow has unwound by the time it is caught, so the
        // daemon can answer and carry on; other VirtualMachineErrors mean it cannot.
        if (e is VirtualMachineError && e !is StackOverflowError) throw e
        error("Internal compiler error: $e", e.stackTraceToString())
      }

  private fun decode(line: String): JsonObject? =
      try {
        JsonParser.parseString(String(Base64.getDecoder().decode(line.trim())))
            .takeIf { it.isJsonObject }
            ?.asJsonObject
      } catch (e: IllegalArgumentException) {
        null
      } catch (e: com.google.gson.JsonParseException) {
        null
      }

  private fun respond(request: JsonObject): JsonObject {
    val action = request["action"]?.takeIf { it.isJsonPrimitive }?.asString
    if (action != "compile") return error("Invalid action '$action'")
    val code = request["code"] ?: return error("No code provided")
    val program = if (code.isJsonObject) code.asJsonObject[""] else code
    val source =
        program?.takeIf { it.isJsonPrimitive }?.asString ?: return error("No code provided")
    return compile(source, request["options"]?.takeIf { it.isJsonObject }?.asJsonObject)
  }

  private fun describe(error: ktrapic.api.CompileError) =
      mapOf("description" to error.message, "line" to error.line, "column" to error.column)

  private fun error(description: String, stackTrace: String? = null) =
      JsonObject().apply {
        add(
            "error",
            JsonObject().apply {
              addProperty("description", description)
              if (stackTrace != null) addProperty("stack_trace", stackTrace)
            },
        )
      }
}

/**
 * With no arguments, serves [Daemon] requests on stdin until EOF or an `EXIT` line. With `--lsp`,
 * serves the Language Server Protocol on stdin and stdout instead ([LspServer]) and exits with the
 * code the protocol gives. Logging goes to stderr.
 */
fun main(args: Array<String>) {
  when (args.toList()) {
    emptyList<String>() -> serveLines()
    listOf("--lsp") -> exitProcess(serveLsp())
    else -> {
      System.err.println("usage: ktrapic-daemon [--lsp]")
      exitProcess(2)
    }
  }
}

private fun serveLines() {
  System.err.println("KTrapIC compile daemon ready")
  while (true) {
    val line = readlnOrNull()?.trim() ?: break
    if (line == "EXIT") break
    println(Daemon.handle(line))
    System.out.flush()
  }
}

private fun serveLsp(): Int {
  // Only protocol messages may reach stdout, so anything else printed there goes to stderr.
  System.out.flush()
  val stdout = BufferedOutputStream(FileOutputStream(FileDescriptor.out))
  System.setOut(System.err)
  System.err.println("KTrapIC compile daemon ready (LSP)")
  return LspServer(System.`in`, stdout).run()
}
