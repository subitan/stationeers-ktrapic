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
import ktrapic.frontend.Hover
import ktrapic.frontend.K2Completion
import ktrapic.frontend.SignatureHelp

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
 * `ktrapic/compile`, whose params are `{"textDocument": {"uri"}}` (an `"options"` object, which
 * older clients send, is ignored) and whose result is the JSON a line-protocol compile request
 * answers with ([Daemon.compile]). Besides those it implements the lifecycle: `initialize`,
 * `initialized`, `shutdown` and `exit`.
 *
 * **Diagnostics.** [debounceMillis] after a `didOpen` or `didChange`, the server compiles the
 * document and sends `textDocument/publishDiagnostics` with its URI and version: each error of a
 * failed compile with severity 1, or each warning of a successful one with severity 2, so a clean
 * compile gives an empty list (see [diagnosticsOf] for the ranges). `didClose` sends an empty list.
 * A newer change to the document cancels a pending compile, and a compile that finishes after one
 * is dropped, so diagnostics are never sent for a version the document no longer has. The last few
 * compiles are kept, so a `ktrapic/compile` of the text a diagnostics compile has just compiled (or
 * is compiling) gets that compile's result, and the other way round; internal compiler errors are
 * not kept, but compiled again.
 *
 * **`textDocument/completion`** lists what could be written at the position, from the compiler's
 * analysis of the document's current text, matched by SDK types' game prefab names as well as their
 * own (see [completion]); a repeat of the latest request costs nothing, and an analysis that fails
 * is answered with InternalError.
 *
 * **`textDocument/hover`** describes the name at the position ([hover]), and
 * **`textDocument/signatureHelp`** the functions the call around it could be to ([signatureHelp]),
 * both from the same analysis, as plain text; where there is nothing to say, the result is null. An
 * analysis that fails is answered with InternalError.
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
 * @param compiler compiles a document's text.
 * @param completer lists what could be written at an index in a document's text.
 * @param hoverer describes the name at an index in a document's text.
 * @param signer finds the functions the call around an index in a document's text could be to.
 */
class LspServer
internal constructor(
    input: InputStream,
    output: OutputStream,
    private val log: (String) -> Unit,
    private val debounceMillis: Long,
    private val scheduler: Scheduler,
    private val compiler: (String) -> JsonObject,
    private val completer: (String, Int) -> List<Completion> = Compiler::complete,
    private val hoverer: (String, Int) -> Hover? = Compiler::hover,
    private val signer: (String, Int) -> SignatureHelp? = Compiler::signatureHelp,
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

  /** The latest compiles' results by text, for [compileOnce]. Under its lock. */
  private val compiles =
      object : LinkedHashMap<String, JsonObject>() {
        override fun removeEldestEntry(eldest: Map.Entry<String, JsonObject>) = size > KEPT_COMPILES
      }

  private class LastCompletion(val text: String, val offset: Int, val completions: List<Completion>)

  /** The latest completion's text, offset and list, for [completion]. Under [compiles]' lock. */
  private var lastCompletion: LastCompletion? = null

  /** Request handlers by method: each returns the result, or throws [ResponseError]. */
  private val requests: Map<String, (JsonElement?) -> JsonElement> =
      mapOf(
          "initialize" to ::initialize,
          "shutdown" to ::shutdown,
          "ktrapic/compile" to ::compile,
          "textDocument/completion" to ::completion,
          "textDocument/hover" to ::hover,
          "textDocument/signatureHelp" to ::signatureHelp,
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
            addProperty("hoverProvider", true)
            add(
                "signatureHelpProvider",
                JsonObject().apply {
                  add(
                      "triggerCharacters",
                      JsonArray().apply {
                        add("(")
                        add(",")
                      },
                  )
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
    return compileOnce(document.text)
  }

  /**
   * [compiler]'s result for [text], compiled now or kept from a recent compile. Compiles run one at
   * a time: this waits for one running on another thread, which may well be of the same text.
   *
   * An internal compiler error, a result whose error has a `stack_trace`, is not kept: it can
   * depend on more than the text, as a stack overflow does on the thread's stack and on what the
   * JIT has compiled, so the same text may compile next time.
   */
  private fun compileOnce(text: String): JsonObject {
    synchronized(compiles) {
      return compiles[text]
          ?: compiler(text).also { if (!it.isInternalError()) compiles[text] = it }
    }
  }

  /**
   * What could be written at a position in an open document, as a `CompletionList` ([completer]
   * lists them). Each item's `sortText` keeps [completer]'s order, `detail` is its declaration
   * (such as `var Setting: Double`), and its `kind` is one the IC10 Editor can show: it colours an
   * item by indexing a 24-colour table with the kind, so Operator (24) and TypeParameter (25) are
   * never sent. An SDK type's item has a `filterText` of its label and the game's names of its
   * prefabs (`LedDisplay StructureConsoleLED5 StructureConsoleLED1x2 StructureConsoleLED1x3`), so a
   * client's filter finds it by either; the list is `isIncomplete` while the word typed is too
   * short for family members to be offered by their game names ([gameNamesPending]). Runs as
   * compiles do, one at a time. A request at the same text and index as the latest one gets that
   * one's list again without analysing the text, as the IC10 Editor asks twice for each keystroke.
   * If the analysis fails, the request is answered with InternalError.
   */
  private fun completion(params: JsonElement?): JsonElement {
    val (text, offset) = textAndOffset(params)
    val completions =
        synchronized(compiles) {
          lastCompletion?.takeIf { it.text == text && it.offset == offset }?.completions
              ?: complete(text, offset).also { lastCompletion = LastCompletion(text, offset, it) }
        }
    val items = JsonArray()
    completions.forEachIndexed { index, completion ->
      items.add(
          JsonObject().apply {
            addProperty("label", completion.label)
            addProperty("kind", kindOf(completion.kind))
            if (completion.detail.isNotEmpty()) addProperty("detail", completion.detail)
            addProperty("sortText", "%05d".format(index))
            completion.filterText?.let { addProperty("filterText", it) }
          }
      )
    }
    return JsonObject().apply {
      addProperty("isIncomplete", gameNamesPending(text, offset))
      add("items", items)
    }
  }

  /**
   * Whether the list at [offset] in [text] may grow as the word there is typed: with no dot before
   * it, a word shorter than [K2Completion.GAME_NAME_MIN_LENGTH] gets no family members by their
   * game names, which a longer one may. A client that keeps a list while the word grows, as Monaco
   * does, asks again until then.
   */
  private fun gameNamesPending(text: String, offset: Int): Boolean {
    var start = offset
    while (start > 0 && Character.isJavaIdentifierPart(text[start - 1])) start--
    val afterDot = text.substring(0, start).trimEnd().endsWith('.')
    return !afterDot && offset - start < K2Completion.GAME_NAME_MIN_LENGTH
  }

  /** [completer]'s list, or an InternalError answer if it fails ([analysed]). */
  private fun complete(text: String, offset: Int): List<Completion> =
      analysed("Completion") { completer(text, offset) }

  /**
   * What [analysis] gives, or an InternalError answer if it fails. Like a compile, it can fail with
   * an Error as well as an Exception: the compiler's parser overflows the stack on deeply nested
   * source, and its internal checks throw AssertionError. A stack overflow has unwound by the time
   * it is caught, so the server can answer and carry on; other VirtualMachineErrors mean it cannot.
   */
  private fun <T> analysed(what: String, analysis: () -> T): T =
      try {
        analysis()
      } catch (e: Throwable) {
        if (e is VirtualMachineError && e !is StackOverflowError) throw e
        log("$what failed: ${e.stackTraceToString()}")
        throw ResponseError(ErrorCodes.INTERNAL_ERROR, "$what failed: $e")
      }

  /** An open document's text, and the index in it of a request's `position`. */
  private fun textAndOffset(params: JsonElement?): Pair<String, Int> {
    val fields = params.asObject("params")
    val uri = fields["textDocument"].asObject("textDocument").string("uri")
    val document =
        documents[uri] ?: throw ResponseError(ErrorCodes.INVALID_PARAMS, "$uri is not open")
    return document.text to document.offsetAt(fields["position"].asObject("position").toPosition())
  }

  /**
   * A `Hover` of the name at a position in an open document ([hoverer]), its contents plain text
   * ([Hover.text]), or null where there is none. Runs as compiles do, one at a time.
   */
  private fun hover(params: JsonElement?): JsonElement {
    val (text, offset) = textAndOffset(params)
    val hover =
        synchronized(compiles) { analysed("Hover") { hoverer(text, offset) } }
            ?: return JsonNull.INSTANCE
    return JsonObject().apply { add("contents", plainText(hover.text())) }
  }

  /**
   * The `SignatureHelp` of the call around a position in an open document ([signer]), or null
   * outside one. Each signature's `documentation`, when it has one, is a `MarkupContent`, as the
   * IC10 Editor reads it. Runs as compiles do, one at a time.
   */
  private fun signatureHelp(params: JsonElement?): JsonElement {
    val (text, offset) = textAndOffset(params)
    val help =
        synchronized(compiles) { analysed("Signature help") { signer(text, offset) } }
            ?: return JsonNull.INSTANCE
    val signatures = JsonArray()
    for (signature in help.signatures) {
      signatures.add(
          JsonObject().apply {
            addProperty("label", signature.label)
            signature.documentation?.let { add("documentation", plainText(it)) }
            add(
                "parameters",
                JsonArray().apply {
                  signature.parameters.forEach {
                    add(JsonObject().apply { addProperty("label", it) })
                  }
                },
            )
          }
      )
    }
    return JsonObject().apply {
      add("signatures", signatures)
      addProperty("activeSignature", 0)
      addProperty("activeParameter", help.activeParameter)
    }
  }

  private fun plainText(value: String) =
      JsonObject().apply {
        addProperty("kind", "plaintext")
        addProperty("value", value)
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
            val diagnostics = diagnosticsOf(compileOnce(text), text)
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
