package ktrapic.daemon.lsp

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.InputStream
import java.io.OutputStream
import java.io.StringReader
import ktrapic.daemon.Daemon

/** JSON-RPC's and LSP's error codes. */
internal object ErrorCodes {
  const val PARSE_ERROR = -32700
  const val INVALID_REQUEST = -32600
  const val METHOD_NOT_FOUND = -32601
  const val INVALID_PARAMS = -32602
  const val INTERNAL_ERROR = -32603
  const val SERVER_NOT_INITIALIZED = -32002
}

/** Thrown by a request's handler to answer with a JSON-RPC error. */
internal class ResponseError(val code: Int, message: String) : Exception(message)

/**
 * The daemon's Language Server Protocol mode (`ktrapic-daemon --lsp`): JSON-RPC 2.0 over [input]
 * and [output], framed with `Content-Length` headers.
 *
 * It keeps the text of the documents the client opens (`textDocument/didOpen`, `didChange` with
 * full or incremental changes, `didClose`) and compiles one on the custom request
 * `ktrapic/compile`, whose params are `{"textDocument": {"uri"}, "options": {"compact"}}` and whose
 * result is the JSON a line-protocol compile request answers with ([Daemon.compile]). Besides those
 * it implements the lifecycle: `initialize`, `initialized`, `shutdown` and `exit`.
 *
 * Messages are handled one at a time, in order. Requests the server doesn't know get
 * MethodNotFound; notifications it doesn't know are ignored. Requests before `initialize` get
 * ServerNotInitialized, and requests after `shutdown` get InvalidRequest.
 *
 * To serve another method, add its handler to [requests] or [notifications].
 */
class LspServer(
    input: InputStream,
    output: OutputStream,
    private val log: (String) -> Unit = { System.err.println(it) },
) {
  private val reader = MessageReader(input, log)
  private val writer = MessageWriter(output)
  private val gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()

  private enum class State {
    STARTING,
    RUNNING,
    SHUT_DOWN,
  }

  private var state = State.STARTING
  private var exitCode: Int? = null

  /** The open documents, by URI. */
  internal val documents = mutableMapOf<String, TextDocument>()

  /** Request handlers by method: each returns the result, or throws [ResponseError]. */
  private val requests: Map<String, (JsonElement?) -> JsonElement> =
      mapOf(
          "initialize" to ::initialize,
          "shutdown" to ::shutdown,
          "ktrapic/compile" to ::compile,
      )

  /** Notification handlers by method. `exit` is handled before these. */
  private val notifications: Map<String, (JsonElement?) -> Unit> =
      mapOf(
          "initialized" to { _ -> },
          "textDocument/didOpen" to ::didOpen,
          "textDocument/didChange" to ::didChange,
          "textDocument/didClose" to ::didClose,
      )

  /**
   * Serves messages until `exit`, or until no more can be read (the end of the input, or a header
   * block without a valid `Content-Length`), and returns the exit code: 0 if the client shut the
   * server down first, else 1.
   */
  fun run(): Int {
    while (true) {
      exitCode?.let {
        return it
      }
      val body = reader.read()
      if (body == null) {
        log("Stopped reading messages without an exit notification")
        return if (state == State.SHUT_DOWN) 0 else 1
      }
      try {
        handle(body)
      } catch (e: StackOverflowError) {
        // Deeply nested JSON can overflow the stack wherever it is walked. The stack has unwound
        // by the time the error is caught, so the server can answer and carry on.
        log("Handling a message overflowed the stack")
        sendError(JsonNull.INSTANCE, ErrorCodes.INTERNAL_ERROR, "The message is nested too deeply")
      }
    }
  }

  private fun handle(body: String) {
    val message =
        try {
          parse(body)
        } catch (e: Exception) {
          return sendError(JsonNull.INSTANCE, ErrorCodes.PARSE_ERROR, "Invalid JSON: ${e.message}")
        } catch (e: StackOverflowError) {
          // gson reads JSON recursively, so it overflows the stack on deeply nested input.
          return sendError(
              JsonNull.INSTANCE,
              ErrorCodes.PARSE_ERROR,
              "The JSON is nested too deeply",
          )
        }
    if (!message.isJsonObject) {
      return sendError(JsonNull.INSTANCE, ErrorCodes.INVALID_REQUEST, "Not a JSON-RPC message")
    }
    val fields = message.asJsonObject
    val id = fields["id"]
    val method = fields["method"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
    if (id != null && !isValidId(id)) {
      return sendError(JsonNull.INSTANCE, ErrorCodes.INVALID_REQUEST, "Invalid id: $id")
    }
    when {
      method == null && (fields.has("result") || fields.has("error")) ->
          // A response, though the server never sends requests.
          log("Ignoring a response to request $id")
      method == null ->
          sendError(id ?: JsonNull.INSTANCE, ErrorCodes.INVALID_REQUEST, "No method given")
      id == null -> notify(method.asString, fields["params"])
      else -> request(id, method.asString, fields["params"])
    }
  }

  /** Strict JSON, unlike [com.google.gson.JsonParser], which is lenient. */
  private fun parse(body: String): JsonElement {
    val reader = JsonReader(StringReader(body)).apply { isLenient = false }
    val element = gson.getAdapter(JsonElement::class.java).read(reader)
    if (reader.peek() != JsonToken.END_DOCUMENT) throw IllegalStateException("Trailing data")
    return element
  }

  private fun isValidId(id: JsonElement) =
      id.isJsonNull || (id.isJsonPrimitive && !id.asJsonPrimitive.isBoolean)

  private fun request(id: JsonElement, method: String, params: JsonElement?) {
    val result =
        try {
          when {
            state == State.STARTING && method != "initialize" ->
                throw ResponseError(ErrorCodes.SERVER_NOT_INITIALIZED, "Not initialized yet")
            state != State.STARTING && method == "initialize" ->
                throw ResponseError(ErrorCodes.INVALID_REQUEST, "Already initialized")
            state == State.SHUT_DOWN ->
                throw ResponseError(ErrorCodes.INVALID_REQUEST, "The server is shut down")
          }
          val handler =
              requests[method]
                  ?: throw ResponseError(ErrorCodes.METHOD_NOT_FOUND, "Unknown method $method")
          handler(params)
        } catch (e: ResponseError) {
          return sendError(id, e.code, e.message ?: "")
        } catch (e: Exception) {
          log("$method failed: ${e.stackTraceToString()}")
          return sendError(id, ErrorCodes.INTERNAL_ERROR, "$method failed: $e")
        }
    send(
        JsonObject().apply {
          addProperty("jsonrpc", "2.0")
          add("id", id)
          add("result", result)
        }
    )
  }

  private fun notify(method: String, params: JsonElement?) {
    if (method == "exit") {
      exitCode = if (state == State.SHUT_DOWN) 0 else 1
      return
    }
    if (state != State.RUNNING) return log("Ignoring $method: the server is not running")
    val handler = notifications[method] ?: return
    try {
      handler(params)
    } catch (e: Exception) {
      log("$method failed: $e")
    }
  }

  private fun initialize(params: JsonElement?): JsonElement {
    state = State.RUNNING
    return JsonObject().apply {
      add(
          "capabilities",
          JsonObject().apply {
            add(
                "textDocumentSync",
                JsonObject().apply {
                  addProperty("openClose", true)
                  addProperty("change", TEXT_DOCUMENT_SYNC_INCREMENTAL)
                },
            )
          },
      )
      add("serverInfo", JsonObject().apply { addProperty("name", "ktrapic-daemon") })
    }
  }

  private fun shutdown(params: JsonElement?): JsonElement {
    state = State.SHUT_DOWN
    return JsonNull.INSTANCE
  }

  private fun compile(params: JsonElement?): JsonElement {
    val fields = params.asObject("params")
    val uri = fields["textDocument"].asObject("textDocument").string("uri")
    val document =
        documents[uri] ?: throw ResponseError(ErrorCodes.INVALID_PARAMS, "$uri is not open")
    val options = fields["options"]?.takeUnless { it.isJsonNull }?.asObject("options")
    return Daemon.compile(document.text, options)
  }

  private fun didOpen(params: JsonElement?) {
    val item = params.asObject("params")["textDocument"].asObject("textDocument")
    val uri = item.string("uri")
    documents[uri] =
        TextDocument(uri, item.string("languageId"), item.int("version"), item.string("text"))
  }

  private fun didChange(params: JsonElement?) {
    val fields = params.asObject("params")
    val identifier = fields["textDocument"].asObject("textDocument")
    val uri = identifier.string("uri")
    val document = documents[uri] ?: return log("Ignoring a change to $uri, which is not open")
    val changes =
        fields["contentChanges"].asArray("contentChanges").map { element ->
          val change = element.asObject("a content change")
          ContentChange(
              change["range"]?.takeUnless { it.isJsonNull }?.asObject("range")?.toRange(),
              change.string("text"),
          )
        }
    document.apply(identifier.int("version"), changes)
  }

  private fun didClose(params: JsonElement?) {
    val uri = params.asObject("params")["textDocument"].asObject("textDocument").string("uri")
    documents.remove(uri)
  }

  private fun send(message: JsonObject) = writer.write(gson.toJson(message))

  private fun sendError(id: JsonElement, code: Int, message: String) =
      send(
          JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            add("id", id)
            add(
                "error",
                JsonObject().apply {
                  addProperty("code", code)
                  addProperty("message", message)
                },
            )
          }
      )

  private companion object {
    const val TEXT_DOCUMENT_SYNC_INCREMENTAL = 2

    fun invalidParams(message: String) = ResponseError(ErrorCodes.INVALID_PARAMS, message)

    fun JsonElement?.asObject(what: String): JsonObject =
        this?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw invalidParams("$what must be an object")

    fun JsonElement?.asArray(what: String): JsonArray =
        this?.takeIf { it.isJsonArray }?.asJsonArray
            ?: throw invalidParams("$what must be an array")

    fun JsonObject.string(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?: throw invalidParams("$name must be a string")

    fun JsonObject.int(name: String): Int =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
            ?: throw invalidParams("$name must be a number")

    fun JsonObject.toRange() =
        Range(get("start").asObject("start").toPosition(), get("end").asObject("end").toPosition())

    fun JsonObject.toPosition() = Position(int("line"), int("character"))
  }
}
