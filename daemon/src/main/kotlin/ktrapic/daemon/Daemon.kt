package ktrapic.daemon

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedOutputStream
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import kotlin.system.exitProcess
import ktrapic.api.CompileResult
import ktrapic.api.Compiler
import ktrapic.daemon.lsp.LspServer
import ktrapic.harness.Harness
import ktrapic.harness.RunResult
import ktrapic.harness.TestSpec
import ktrapic.harness.TestSpecException
import ktrapic.ic10.Program

/**
 * The compile daemon's protocol, as PyTrapIC's (`stationeers_pytrapic/mod_daemon.py`): each request
 * is one line of base64-encoded JSON, `{"action": "compile", "code": ...}`, and gets one line of
 * base64-encoded JSON back. `code` is the source, or a map of module names to sources whose `""`
 * entry is the program. A success is `{"code", "num_lines", "num_bytes", "num_registers",
 * "warnings", "source_mapping"}`; a failure is `{"error": {"description", "line", "column", ...}}`.
 * Each warning, and each of a failure's `errors`, is `{"description", "line", "column"}`, with
 * `"kotlin": true` added for the Kotlin compiler's own diagnostics and syntax errors, which an
 * editor with Kotlin support reports already. `source_mapping` maps each 0-based source line, as a
 * string key, to the 0-based lines of `code` it compiled to, as PyTrapIC's does. The program says
 * how it compiles, with file annotations; an `options` object, which older clients send, is
 * accepted and ignored.
 *
 * Unlike PyTrapIC, `line` and `column` are both 1-based, and `line_end`/`column_end` repeat them
 * because the compiler reports only where a problem starts. All four are 0 when the compiler gives
 * no position, as for a failure inside the compiler that it reports as an error. A request that is
 * neither a compile nor a run request gets `{"error": {"description"}}`, with an object rather than
 * PyTrapIC's plain string.
 *
 * KTrapIC adds `{"action": "run", "code", "test": {...}, "trace_tick": N}`, which compiles as a
 * compile request does and then runs the program against a test's fake devices (see [run]).
 *
 * Every request line gets exactly one response line, whatever it holds, so a client can read one
 * line per line it writes.
 *
 * Started with `--lsp`, the daemon speaks the Language Server Protocol instead (see [LspServer]),
 * whose `ktrapic/compile` request answers with the same JSON as a compile request here, and which
 * publishes the problems with each version of a document as it is edited.
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
   * The response to compiling [source]: the JSON object that a compile request's response line
   * encodes. Failures inside the compiler are answered as errors too, as [handle] answers them. The
   * program chooses how it compiles, with file annotations (`@file:Compact`, `@file:InRegisters`,
   * `@file:NoTailCalls`); a request's `options`, which older clients send, are ignored.
   */
  fun compile(source: String): JsonObject = guarded {
    when (val result = Compiler.compile(source)) {
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

  /**
   * The response to running [test], a test file's JSON object (see `docs/TESTING.md`), against
   * [source] compiled: the compile response, with `run` added if it compiled. `run` is the
   * harness's result (see [RunResult.toJson]), with a snapshot per line of tick [traceTick] if
   * given, or `{"error": {"description"}}` if the test can't be run as written or [traceTick] isn't
   * one of its ticks.
   */
  fun run(source: String, test: JsonObject?, traceTick: Int?): JsonObject = guarded {
    val compiled = compile(source)
    val code = compiled["code"]?.asString
    if (code != null) {
      val run =
          try {
            val spec = TestSpec.parse(test ?: JsonObject())
            if (traceTick != null && traceTick !in 1..spec.ticks) {
              throw TestSpecException("trace_tick: the test runs ticks 1 to ${spec.ticks}")
            }
            Harness.run(Program.parse(code), spec, traceTick).toJson()
          } catch (e: TestSpecException) {
            JsonObject().apply {
              add("error", JsonObject().apply { addProperty("description", e.message) })
            }
          }
      compiled.add("run", run)
    }
    compiled
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
    if (action != "compile" && action != "run") return error("Invalid action '$action'")
    val code = request["code"] ?: return error("No code provided")
    val program = if (code.isJsonObject) code.asJsonObject[""] else code
    val source =
        program?.takeIf { it.isJsonPrimitive }?.asString ?: return error("No code provided")
    if (action == "compile") return compile(source)
    val test = request["test"]
    if (test != null && !test.isJsonObject) return error("A run's test must be a JSON object")
    val traceTick =
        request["trace_tick"]?.let { tick ->
          tick
              .takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
              ?.asDouble
              ?.takeIf { it == Math.rint(it) }
              // A whole number too big for an Int saturates, so it is still outside the test's
              // ticks.
              ?.toInt() ?: return error("trace_tick must be a whole number")
        }
    return run(source, test?.asJsonObject, traceTick)
  }

  private fun describe(error: ktrapic.api.CompileError): Map<String, Any> =
      mapOf("description" to error.message, "line" to error.line, "column" to error.column) +
          (if (error.kotlin) mapOf("kotlin" to true) else emptyMap())

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
    listOf("--lsp") ->
        exitProcess(
            serveLsp(System.`in`, BufferedOutputStream(FileOutputStream(FileDescriptor.out)))
        )
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

/**
 * Serves the Language Server Protocol on [input] and [output], which is stdout, and returns the
 * exit code. Only protocol messages may reach stdout, so `System.out` is pointed at stderr for the
 * rest of the process.
 */
internal fun serveLsp(input: InputStream, output: OutputStream): Int {
  System.out.flush()
  System.setOut(System.err)
  System.err.println("KTrapIC compile daemon ready (LSP)")
  return LspServer(input, output).run()
}
