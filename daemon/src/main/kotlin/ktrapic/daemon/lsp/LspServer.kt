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
import java.util.concurrent.Future
import ktrapic.api.Compiler
import ktrapic.daemon.Daemon
import ktrapic.frontend.Completion

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
 * **Diagnostics.** [debounceMillis] after a `didOpen` or `didChange`, the server compiles the
 * document and sends `textDocument/publishDiagnostics` with its URI and version: each error of a
 * failed compile with severity 1, or each warning of a successful one with severity 2, so a clean
 * compile gives an empty list (see [diagnosticsOf] for the ranges). `didClose` sends an empty list.
 * A newer change to the document cancels a pending compile, and a compile that finishes after one
 * is dropped, so diagnostics are never sent for a version the document no longer has. Diagnostics
 * compiles use the options of the latest `ktrapic/compile`, and the last few compiles are kept, so
 * a `ktrapic/compile` of the text a diagnostics compile has just compiled (or is compiling) gets
 * that compile's result, and the other way round; internal compiler errors are not kept, but
 * compiled again.
 *
 * **`textDocument/completion`** lists what could be written at the position, from the compiler's
 * analysis of the document's current text (see [completion]).
 *
 * **`textDocument/semanticTokens/full`** gets `{"data": []}`, although the server doesn't declare
 * the capability: the IC10 Editor asks for semantic tokens after every change whatever the server
 * declares, and an error answer to each would be noise.
 *
 * **Threads.** Messages are read and handled one at a time, in order, on the thread that calls
 * [run]. Diagnostics compiles run on [scheduler]'s worker. Compiles never run at the same time, one
 * waiting for the other, and every message goes out through one synchronized writer. Documents are
 * changed, and checked before diagnostics are sent, under one lock, so a change is either applied
 * after diagnostics for the version before it are sent, or the diagnostics are dropped.
 *
 * Requests the server doesn't know get MethodNotFound; notifications it doesn't know are ignored.
 * Requests before `initialize` get ServerNotInitialized, and requests after `shutdown` get
 * InvalidRequest.
 *
 * To serve another method, add its handler to [requests] or [notifications].
 *
 * @param debounceMillis how long after a change to compile for diagnostics, so that a burst of
 *   keystrokes compiles once; 150 ms, as the plugin's compile-as-you-type waits.
 * @param scheduler runs diagnostics compiles; tests can run them by hand.
 * @param compiler compiles a document's text with a `ktrapic/compile` request's options.
 * @param completer lists what could be written at an index in a document's text.
 */
class LspServer
internal constructor(
    input: InputStream,
    output: OutputStream,
    private val log: (String) -> Unit,
    private val debounceMillis: Long,
    private val scheduler: Scheduler,
    private val compiler: (String, JsonObject?) -> JsonObject,
    private val completer: (String, Int) -> List<Completion> = Compiler::complete,
) {
  constructor(
      input: InputStream,
      output: OutputStream,
      log: (String) -> Unit = { System.err.println(it) },
      debounceMillis: Long = 150,
  ) : this(input, output, log, debounceMillis, WorkerScheduler(), Daemon::compile)

  private val reader = MessageReader(input, log)
  private val writer = MessageWriter(output)
  private val gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()

  private enum class State {
    STARTING,
    RUNNING,
    SHUT_DOWN,
  }

  @Volatile private var state = State.STARTING
  private var exitCode: Int? = null

  /** The open documents, by URI. Changed only under [documentsLock]. */
  internal val documents = mutableMapOf<String, TextDocument>()
  private val documentsLock = Any()

  /** Each open document's pending or running diagnostics compile. Used on the reading thread. */
  private val pendingDiagnostics = mutableMapOf<String, Future<*>>()

  /** The options of the latest `ktrapic/compile`, which diagnostics compiles use too. */
  @Volatile private var latestOptions: JsonObject? = null

  /** The latest compiles' results by text and options, for [compileOnce]. Under its lock. */
  private val compiles =
      object : LinkedHashMap<Pair<String, String>, JsonObject>() {
        override fun removeEldestEntry(eldest: Map.Entry<Pair<String, String>, JsonObject>) =
            size > KEPT_COMPILES
      }

  /** Request handlers by method: each returns the result, or throws [ResponseError]. */
  private val requests: Map<String, (JsonElement?) -> JsonElement> =
      mapOf(
          "initialize" to ::initialize,
          "shutdown" to ::shutdown,
          "ktrapic/compile" to ::compile,
          "textDocument/completion" to ::completion,
          "textDocument/semanticTokens/full" to ::semanticTokens,
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
   * server down first, else 1. Diagnostics compiles not yet finished are then abandoned.
   */
  fun run(): Int =
      try {
        serve()
      } finally {
        scheduler.close()
      }

  private fun serve(): Int {
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
            add(
                "completionProvider",
                JsonObject().apply {
                  add("triggerCharacters", JsonArray().apply { add(".") })
                },
            )
          },
      )
      add("serverInfo", JsonObject().apply { addProperty("name", "ktrapic-daemon") })
    }
  }

  private fun shutdown(params: JsonElement?): JsonElement {
    // Under the lock, so diagnostics being published finish before the response goes out, and
    // none are published after it.
    synchronized(documentsLock) { state = State.SHUT_DOWN }
    pendingDiagnostics.values.forEach { it.cancel(false) }
    pendingDiagnostics.clear()
    return JsonNull.INSTANCE
  }

  private fun compile(params: JsonElement?): JsonElement {
    val fields = params.asObject("params")
    val uri = fields["textDocument"].asObject("textDocument").string("uri")
    val document =
        documents[uri] ?: throw ResponseError(ErrorCodes.INVALID_PARAMS, "$uri is not open")
    val options = fields["options"]?.takeUnless { it.isJsonNull }?.asObject("options")
    latestOptions = options
    return compileOnce(document.text, options)
  }

  /**
   * [compiler]'s result for [text] and [options], compiled now or kept from a recent compile.
   * Compiles run one at a time: this waits for one running on another thread, which may well be of
   * the same text.
   *
   * An internal compiler error, a result whose error has a `stack_trace`, is not kept: it can
   * depend on more than the text, as a stack overflow does on the thread's stack and on what the
   * JIT has compiled, so the same text may compile next time.
   */
  private fun compileOnce(text: String, options: JsonObject?): JsonObject {
    val key = text to (options ?: JsonObject()).toString()
    synchronized(compiles) {
      return compiles[key]
          ?: compiler(text, options).also { if (!it.isInternalError()) compiles[key] = it }
    }
  }

  /**
   * What could be written at a position in an open document, as a `CompletionList` ([completer]
   * lists them). Each item's `sortText` keeps [completer]'s order, `detail` is its declaration
   * (such as `var Setting: Double`), and its `kind` is one the IC10 Editor can show: it colours an
   * item by indexing a 24-colour table with the kind, so Operator (24) and TypeParameter (25) are
   * never sent. Runs as compiles do, one at a time.
   */
  private fun completion(params: JsonElement?): JsonElement {
    val fields = params.asObject("params")
    val uri = fields["textDocument"].asObject("textDocument").string("uri")
    val document =
        documents[uri] ?: throw ResponseError(ErrorCodes.INVALID_PARAMS, "$uri is not open")
    val text = document.text
    val offset = document.offsetAt(fields["position"].asObject("position").toPosition())
    val completions = synchronized(compiles) { completer(text, offset) }
    val items = JsonArray()
    completions.forEachIndexed { index, completion ->
      items.add(
          JsonObject().apply {
            addProperty("label", completion.label)
            addProperty("kind", kindOf(completion.kind))
            if (completion.detail.isNotEmpty()) addProperty("detail", completion.detail)
            addProperty("sortText", "%05d".format(index))
          }
      )
    }
    return JsonObject().apply {
      addProperty("isIncomplete", false)
      add("items", items)
    }
  }

  /** Answers semantic tokens with none, so the IC10 Editor's request after each change succeeds. */
  private fun semanticTokens(params: JsonElement?): JsonElement =
      JsonObject().apply { add("data", JsonArray()) }

  private fun didOpen(params: JsonElement?) {
    val item = params.asObject("params")["textDocument"].asObject("textDocument")
    val uri = item.string("uri")
    val document =
        TextDocument(uri, item.string("languageId"), item.int("version"), item.string("text"))
    synchronized(documentsLock) { documents[uri] = document }
    scheduleDiagnostics(document)
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
    val version = identifier.int("version")
    synchronized(documentsLock) { document.apply(version, changes) }
    scheduleDiagnostics(document)
  }

  private fun didClose(params: JsonElement?) {
    val uri = params.asObject("params")["textDocument"].asObject("textDocument").string("uri")
    pendingDiagnostics.remove(uri)?.cancel(false)
    synchronized(documentsLock) {
      if (documents.remove(uri) != null) publishDiagnostics(uri, null, JsonArray())
    }
  }

  /**
   * Compiles [document]'s current text after [debounceMillis], in place of any compile still
   * pending for it, and publishes the diagnostics if the document is still at that text then.
   */
  private fun scheduleDiagnostics(document: TextDocument) {
    val uri = document.uri
    val version = document.version
    val text = document.text
    fun isCurrent() =
        documents[uri] === document && document.version == version && document.text == text
    pendingDiagnostics.remove(uri)?.cancel(false)
    pendingDiagnostics[uri] =
        scheduler.schedule(debounceMillis) {
          try {
            if (synchronized(documentsLock) { !isCurrent() }) return@schedule
            val diagnostics = diagnosticsOf(compileOnce(text, latestOptions), text)
            synchronized(documentsLock) {
              if (isCurrent() && state == State.RUNNING) {
                publishDiagnostics(uri, version, diagnostics)
              }
            }
          } catch (e: Exception) {
            log("Compiling $uri for diagnostics failed: ${e.stackTraceToString()}")
          }
        }
  }

  private fun publishDiagnostics(uri: String, version: Int?, diagnostics: JsonArray) =
      send(
          JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("method", "textDocument/publishDiagnostics")
            add(
                "params",
                JsonObject().apply {
                  addProperty("uri", uri)
                  if (version != null) addProperty("version", version)
                  add("diagnostics", diagnostics)
                },
            )
          }
      )

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

    /** How many recent compiles [compileOnce] keeps. */
    const val KEPT_COMPILES = 4

    fun invalidParams(message: String) = ResponseError(ErrorCodes.INVALID_PARAMS, message)

    /** LSP's `CompletionItemKind` for [kind]: all below 24, which the IC10 Editor can show. */
    fun kindOf(kind: Completion.Kind): Int =
        when (kind) {
          Completion.Kind.FUNCTION -> 3
          Completion.Kind.LOCAL -> 6
          Completion.Kind.CLASS -> 7
          Completion.Kind.INTERFACE -> 8
          Completion.Kind.OBJECT -> 9
          Completion.Kind.PROPERTY -> 10
          Completion.Kind.ENUM -> 13
          Completion.Kind.KEYWORD -> 14
          Completion.Kind.ENUM_ENTRY -> 20
        }

    /** Whether a [Daemon.compile] result is an internal compiler error. */
    fun JsonObject.isInternalError() =
        get("error")?.takeIf { it.isJsonObject }?.asJsonObject?.has("stack_trace") == true

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
