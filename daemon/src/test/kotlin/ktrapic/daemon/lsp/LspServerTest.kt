package ktrapic.daemon.lsp

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Base64
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import ktrapic.daemon.Daemon
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/** Drives an [LspServer] in-process, over pipes, as a client would over the daemon's stdio. */
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class LspServerTest {
  private val gson = Gson()

  private val client = LspTestClient()

  @AfterEach fun stop() = client.close()

  private fun compile(uri: String, options: String = "{}") =
      client.request(
          "ktrapic/compile",
          """{"textDocument": {"uri": "$uri"}, "options": $options}""",
      )

  /** What the line protocol answers for compiling [source] with [options]. */
  private fun lineProtocolCompile(source: String, options: String = "{}"): JsonObject {
    val request = """{"action": "compile", "code": ${gson.toJson(source)}, "options": $options}"""
    val line = Daemon.handle(Base64.getEncoder().encodeToString(request.toByteArray()))
    return JsonParser.parseString(String(Base64.getDecoder().decode(line))).asJsonObject
  }

  private fun JsonObject.errorCode() = this["error"].asJsonObject["code"].asInt

  @Test
  fun `initializes with only the capabilities it implements`() {
    val response = client.initialize()

    assertThat(response["jsonrpc"].asString).isEqualTo("2.0")
    assertThat(response["id"].asInt).isEqualTo(1)
    val result = response["result"].asJsonObject
    assertThat(result["capabilities"].toString())
        .isEqualTo(
            """{"textDocumentSync":{"openClose":true,"change":2},""" +
                """"completionProvider":{"triggerCharacters":["."]}}"""
        )
    assertThat(result["serverInfo"].asJsonObject["name"].asString).isEqualTo("ktrapic-daemon")
  }

  @Test
  fun `answers requests before initialize with ServerNotInitialized and drops notifications`() {
    val early = compile("file:///a.kt")
    client.open("file:///a.kt", "text")
    client.initialize()

    assertThat(early["id"].asInt).isEqualTo(1)
    assertThat(early.errorCode()).isEqualTo(-32002)
    assertThat(client.server.documents).isEmpty()
  }

  @Test
  fun `shuts down and exits with 0`() {
    client.initialize()
    val shutdown = client.request("shutdown")
    val after = client.request("ktrapic/compile", "{}")
    client.notify("exit")

    assertThat(shutdown.has("result")).isTrue()
    assertThat(shutdown["result"].isJsonNull).isTrue()
    assertThat(after.errorCode()).isEqualTo(-32600)
    assertThat(client.awaitExit()).isEqualTo(0)
  }

  @Test
  fun `exits with 1 when told to exit without shutting down`() {
    client.initialize()
    client.notify("exit")

    assertThat(client.awaitExit()).isEqualTo(1)
  }

  @Test
  fun `exits with 1 when the input ends without exit`() {
    client.initialize()

    assertThat(client.awaitExit()).isEqualTo(1)
  }

  @Test
  fun `rejects a second initialize`() {
    client.initialize()

    assertThat(client.request("initialize", "{}").errorCode()).isEqualTo(-32600)
  }

  @Test
  fun `reads messages back to back and split into pieces`() {
    val initialize = """{"jsonrpc": "2.0", "id": "init", "method": "initialize", "params": {}}"""
    val initialized = """{"jsonrpc": "2.0", "method": "initialized", "params": {}}"""
    client.sendBytes(LspTestClient.frame(initialize) + LspTestClient.frame(initialized))
    val response = client.read()
    // A message sent a byte at a time, splitting its header and its multibyte characters.
    val text = "// é 😀 ü\n"
    val didOpen =
        """{"jsonrpc": "2.0", "method": "textDocument/didOpen", "params": {"textDocument":
          {"uri": "file:///a.kt", "languageId": "kotlin", "version": 1, "text": "// é 😀 ü\n"}}}"""
    for (byte in LspTestClient.frame(didOpen)) client.sendBytes(byteArrayOf(byte))
    // A request after it, so its answer shows the server has read the notification.
    val unknown = client.request("ktrapic/unknown")

    assertThat(response["id"].asString).isEqualTo("init")
    assertThat(response.has("result")).isTrue()
    assertThat(unknown.errorCode()).isEqualTo(-32601)
    assertThat(client.server.documents.getValue("file:///a.kt").text).isEqualTo(text)
  }

  @Test
  fun `applies full and incremental changes, with positions in UTF-16 code units`() {
    client.initialize()
    client.open("file:///a.kt", "val s = \"😀a\"\nval t = 1\n")
    // The emoji takes two UTF-16 code units, so "a" is at character 11.
    client.notify(
        "textDocument/didChange",
        """{"textDocument": {"uri": "file:///a.kt", "version": 2}, "contentChanges": [
          {"range": {"start": {"line": 0, "character": 11}, "end": {"line": 0, "character": 12}},
           "text": "b"},
          {"range": {"start": {"line": 1, "character": 8}, "end": {"line": 2, "character": 0}},
           "text": "2\nval u = 3\n"}]}""",
    )
    client.request("ktrapic/unknown")
    val document = client.server.documents.getValue("file:///a.kt")

    assertThat(document.text).isEqualTo("val s = \"😀b\"\nval t = 2\nval u = 3\n")
    assertThat(document.version).isEqualTo(2)
    assertThat(document.languageId).isEqualTo("python")

    client.notify(
        "textDocument/didChange",
        """{"textDocument": {"uri": "file:///a.kt", "version": 3},
          "contentChanges": [{"text": "whole"}]}""",
    )
    client.request("ktrapic/unknown")
    assertThat(document.text).isEqualTo("whole")
    assertThat(document.version).isEqualTo(3)

    client.notify("textDocument/didClose", """{"textDocument": {"uri": "file:///a.kt"}}""")
    client.request("ktrapic/unknown")
    assertThat(client.server.documents).isEmpty()
  }

  @Test
  fun `compiles an open document as the line protocol does`() {
    val program = "import ktrapic.*\nvar i = 0\nwhile (i < 3) i++\ndb.Setting = i.toDouble()\n"
    val broken = "import ktrapic.*\nd0.On = 1.0\n"
    client.initialize()
    client.open("file:///program.kt", program)
    client.open("file:///broken.kt", broken)

    val labelled = compile("file:///program.kt")
    val compact = compile("file:///program.kt", """{"compact": true}""")
    val failed = compile("file:///broken.kt")
    // Nested deeply enough to overflow the compiler's stack, which is answered as an error.
    val overflowing =
        "import ktrapic.*\ndb.Setting = ${"(".repeat(20_000)}1.0${")".repeat(20_000)}\n"
    client.open("file:///overflowing.kt", overflowing)
    val crashed = compile("file:///overflowing.kt")

    assertThat(labelled["result"]).isEqualTo(lineProtocolCompile(program))
    assertThat(labelled["result"].asJsonObject["code"].asString).contains(":")
    assertThat(compact["result"]).isEqualTo(lineProtocolCompile(program, """{"compact": true}"""))
    assertThat(compact["result"].asJsonObject["code"].asString).doesNotContain(":")
    assertThat(failed["result"]).isEqualTo(lineProtocolCompile(broken))
    assertThat(failed["result"].asJsonObject["error"].asJsonObject["line"].asInt).isEqualTo(2)
    // The stack traces differ, as the two compiles ran on different threads.
    val crashError = crashed["result"].asJsonObject["error"].asJsonObject
    val lineProtocolCrashError = lineProtocolCompile(overflowing)["error"].asJsonObject
    assertThat(crashError["description"]).isEqualTo(lineProtocolCrashError["description"])
    assertThat(crashError["description"].asString)
        .startsWith("Internal compiler error: java.lang.StackOverflowError")
    assertThat(crashError.has("stack_trace")).isTrue()
  }

  @Test
  fun `compiles a document's latest text`() {
    client.initialize()
    client.open("file:///a.kt", "import ktrapic.*\ndb.Setting = 1.0\n")
    client.notify(
        "textDocument/didChange",
        """{"textDocument": {"uri": "file:///a.kt", "version": 2}, "contentChanges": [
          {"range": {"start": {"line": 1, "character": 13}, "end": {"line": 1, "character": 16}},
           "text": "2.0"}]}""",
    )

    val response = compile("file:///a.kt")

    assertThat(response["result"].asJsonObject["code"].asString).isEqualTo("s db Setting 2")
  }

  @Test
  fun `answers bad ktrapic-compile params with InvalidParams`() {
    client.initialize()

    assertThat(compile("file:///closed.kt").errorCode()).isEqualTo(-32602)
    assertThat(client.request("ktrapic/compile", "{}").errorCode()).isEqualTo(-32602)
    assertThat(client.request("ktrapic/compile").errorCode()).isEqualTo(-32602)
  }

  @Test
  fun `answers unknown requests with MethodNotFound and ignores unknown notifications`() {
    client.initialize()
    client.notify("\$/cancelRequest", """{"id": 1}""")
    client.notify("workspace/didChangeConfiguration", "{}")
    client.send("""{"jsonrpc": "2.0", "id": "abc", "method": "textDocument/hover"}""")

    // The next message is the request's answer: the notifications got none.
    val response = client.read()
    assertThat(response["id"].asString).isEqualTo("abc")
    assertThat(response.errorCode()).isEqualTo(-32601)
  }

  @Test
  fun `answers malformed messages with ParseError or InvalidRequest and carries on`() {
    client.initialize()

    client.send("""{"jsonrpc": "2.0", "id": 7, "method": """)
    val parseError = client.read()
    client.send("[1, 2]")
    val notAnObject = client.read()
    client.send("""{"jsonrpc": "2.0", "id": 8}""")
    val noMethod = client.read()

    assertThat(parseError.errorCode()).isEqualTo(-32700)
    assertThat(parseError.has("id")).isTrue()
    assertThat(parseError["id"].isJsonNull).isTrue()
    assertThat(notAnObject.errorCode()).isEqualTo(-32600)
    assertThat(notAnObject["id"].isJsonNull).isTrue()
    assertThat(noMethod.errorCode()).isEqualTo(-32600)
    assertThat(noMethod["id"].asInt).isEqualTo(8)
    assertThat(client.request("shutdown").has("result")).isTrue()
  }

  @Test
  fun `answers JSON nested too deeply to parse with ParseError and carries on`() {
    client.initialize()

    client.send("[".repeat(200_000) + "]".repeat(200_000))
    val tooDeep = client.read()

    assertThat(tooDeep.errorCode()).isEqualTo(-32700)
    assertThat(tooDeep["id"].isJsonNull).isTrue()
    assertThat(client.request("shutdown").has("result")).isTrue()
  }

  @Test
  fun `stops at a header block with no valid Content-Length`() {
    val input =
        "Content-Type: x\r\n\r\n".toByteArray() +
            """{"jsonrpc": "2.0", "method": "initialized"}""".toByteArray() +
            LspTestClient.frame(
                """{"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}}"""
            )
    val output = ByteArrayOutputStream()
    val logged = mutableListOf<String>()

    val exitCode = LspServer(ByteArrayInputStream(input), output, logged::add).run()

    assertThat(exitCode).isEqualTo(1)
    assertThat(output.size()).isEqualTo(0)
    assertThat(logged.joinToString()).contains("Content-Length")
  }

  @Test
  fun `stops at a header block with no valid Content-Length even when a message follows it`() {
    val input =
        "Content-Type: x\r\n\r\n".toByteArray() +
            LspTestClient.frame(
                """{"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}}"""
            )
    val output = ByteArrayOutputStream()
    val logged = mutableListOf<String>()

    val exitCode = LspServer(ByteArrayInputStream(input), output, logged::add).run()

    assertThat(exitCode).isEqualTo(1)
    assertThat(output.size()).isEqualTo(0)
    assertThat(logged.joinToString()).contains("Content-Length")
  }
}

// The client the LSP tests drive the server with. It lives here, in a test class's file, because
// every other file under src/test is run as a test class of its own.

/** A [Scheduler] whose tasks run only when a test calls [runAll], on the test's thread. */
internal class ManualScheduler : Scheduler {
  private val tasks = LinkedBlockingQueue<FutureTask<Unit>>()

  /** The delay of each task scheduled so far. */
  val delays: MutableList<Long> = java.util.Collections.synchronizedList(mutableListOf())

  override fun schedule(delayMillis: Long, task: Runnable): Future<*> {
    delays += delayMillis
    return FutureTask(task, Unit).also { tasks += it }
  }

  /** Runs the tasks scheduled so far, and any they schedule, in order; cancelled ones don't run. */
  fun runAll() {
    while (true) tasks.poll()?.run() ?: return
  }

  override fun close() {}
}

/**
 * A client connected over pipes to an [LspServer] running on its own thread, as a client would be
 * over the daemon's stdio. By default the server's diagnostics compiles run on [scheduler], a
 * [ManualScheduler], only when the test says.
 */
internal class LspTestClient(
    val scheduler: Scheduler = ManualScheduler(),
    debounceMillis: Long = 150,
    completer: (String, Int) -> List<ktrapic.frontend.Completion> = ktrapic.api.Compiler::complete,
    compiler: (String, JsonObject?) -> JsonObject = Daemon::compile,
) : AutoCloseable {
  private val gson = Gson()
  private val toServer = PipedOutputStream()
  private val serverInput = PipedInputStream(toServer, 1 shl 20)
  private val serverOutput = PipedOutputStream()
  private val fromServer = PipedInputStream(serverOutput, 1 shl 20)
  private val reader = MessageReader(fromServer) {}
  val server =
      LspServer(serverInput, serverOutput, {}, debounceMillis, scheduler, compiler, completer)
  @Volatile private var exitCode: Int? = null
  private val serverThread = thread {
    try {
      exitCode = server.run()
    } finally {
      serverOutput.close()
    }
  }
  private var nextId = 1

  /** The notifications read while waiting for responses, oldest first. */
  val notifications = ArrayDeque<JsonObject>()

  fun sendBytes(bytes: ByteArray) {
    toServer.write(bytes)
    toServer.flush()
  }

  fun send(body: String) = sendBytes(frame(body))

  /** Sends a request and returns its response, keeping notifications that come before it. */
  fun request(method: String, params: String = "null"): JsonObject {
    val id = nextId++
    send("""{"jsonrpc": "2.0", "id": $id, "method": "$method", "params": $params}""")
    while (true) {
      val message = read()
      if (message.has("method")) notifications += message
      else if (message["id"]?.takeIf { it.isJsonPrimitive }?.asInt == id) return message
      else throw AssertionError("Unexpected message $message")
    }
  }

  fun notify(method: String, params: String = "null") =
      send("""{"jsonrpc": "2.0", "method": "$method", "params": $params}""")

  /** The next message the server sends. */
  fun read(): JsonObject =
      JsonParser.parseString(checkNotNull(reader.read()) { "The server sent nothing" }).asJsonObject

  /** Waits until the server has handled everything sent so far, with a request it answers. */
  fun sync() {
    request("textDocument/semanticTokens/full", """{"textDocument": {"uri": "file:///sync"}}""")
  }

  /** The `publishDiagnostics` params received so far, after [sync]ing, and forgets them. */
  fun takeDiagnostics(): List<JsonObject> {
    sync()
    return takeNotifications()
  }

  /** The next `publishDiagnostics` params, waiting for it if none has been received yet. */
  fun awaitDiagnostics(): JsonObject {
    takeNotifications().firstOrNull()?.let {
      return it
    }
    while (true) {
      val message = read()
      if (message["method"]?.asString == PUBLISH) return message["params"].asJsonObject
      throw AssertionError("Unexpected message $message")
    }
  }

  private fun takeNotifications(): List<JsonObject> {
    val published =
        notifications.filter { it["method"].asString == PUBLISH }.map { it["params"].asJsonObject }
    notifications.clear()
    return published
  }

  fun initialize(): JsonObject {
    val response = request("initialize", """{"processId": null, "rootUri": null}""")
    notify("initialized", "{}")
    return response
  }

  fun open(uri: String, text: String, version: Int = 1) =
      notify(
          "textDocument/didOpen",
          """{"textDocument": {"uri": "$uri", "languageId": "python", "version": $version,
            "text": ${gson.toJson(text)}}}""",
      )

  /** Replaces the whole text of [uri]. */
  fun change(uri: String, version: Int, text: String) =
      notify(
          "textDocument/didChange",
          """{"textDocument": {"uri": "$uri", "version": $version},
            "contentChanges": [{"text": ${gson.toJson(text)}}]}""",
      )

  fun close(uri: String) = notify("textDocument/didClose", """{"textDocument": {"uri": "$uri"}}""")

  /** Ends the input and waits for the server to stop; its exit code. */
  fun awaitExit(): Int? {
    toServer.close()
    serverThread.join(60_000)
    return exitCode
  }

  override fun close() {
    awaitExit()
  }

  companion object {
    const val PUBLISH = "textDocument/publishDiagnostics"

    fun frame(body: String): ByteArray {
      val bytes = body.toByteArray()
      return "Content-Length: ${bytes.size}\r\n\r\n".toByteArray() + bytes
    }
  }
}
