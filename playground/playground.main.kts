#!/usr/bin/env kotlin
// The KTrapIC playground: an editor page on http://127.0.0.1:8765/ that compiles as you type.
//
// Run it from the repository root, after building the compile daemon:
//
//   bazel build //daemon:ktrapic-daemon_deploy.jar
//   kotlin playground/playground.main.kts [--port 8765] [--daemon-jar path] [--source-dir dir]...
//
// The script starts the daemon (a separate JVM, so this script needs no compiler of its own),
// serves playground/index.html, and forwards each compile or run request to the daemon unchanged.
// For completion, hovers and signature help it runs the same jar a second time in its LSP mode, as
// the IC10 Editor does in game, and relays the page's LSP messages to it (see LanguageServer).
// The Open menu lists the .ic10.kts programs in the source directories (default: test/cases); a
// program's test (X.test.json beside X.ic10.kts, see docs/TESTING.md) opens with it, and saving
// the test writes that file.

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

val usage =
    "usage: kotlin playground/playground.main.kts [--port N] [--daemon-jar path] [--source-dir dir]..."

var port = 8765
var daemonJar = File("bazel-bin/daemon/ktrapic-daemon_deploy.jar")
val sourceDirs = mutableListOf<File>()
var index = 0

while (index < args.size) {
  val value = args.getOrNull(index + 1)
  when (args[index]) {
    "--port" -> port = value?.toIntOrNull() ?: fail(usage)
    "--daemon-jar" -> daemonJar = File(value ?: fail(usage))
    "--source-dir" -> sourceDirs += File(value ?: fail(usage))
    else -> fail(usage)
  }
  index += 2
}

if (sourceDirs.isEmpty()) sourceDirs += File("test/cases")

val page = File("playground/index.html")

/** The page's own scripts, which it loads beside it: file annotations, and reading the compiler. */
val scripts = listOf("annotations.js", "editing.js").associateWith { File("playground/$it") }

if (!page.isFile) fail("Run this from the repository root (no playground/index.html here).")

if (!daemonJar.isFile) {
  fail("No daemon at $daemonJar. Build it with: bazel build //daemon:ktrapic-daemon_deploy.jar")
}

fun fail(message: String): Nothing {
  System.err.println(message)
  exitProcess(2)
}

/** The compile daemon at [jar], started on first use and restarted if it exits. */
class Daemon(private val jar: File) {
  private var process: Process? = null
  private lateinit var input: BufferedWriter
  private lateinit var output: BufferedReader

  /** Sends one JSON request and returns the JSON response. */
  @Synchronized
  fun request(json: String): String {
    val running = process?.takeIf { it.isAlive } ?: start()
    input.write(Base64.getEncoder().encodeToString(json.toByteArray()))
    input.newLine()
    input.flush()
    val line = output.readLine()
    if (line == null) {
      running.destroy()
      process = null
      return """{"error": {"description": "The compile daemon stopped; see the console."}}"""
    }
    return String(Base64.getDecoder().decode(line))
  }

  private fun start(): Process {
    val java = File(System.getProperty("java.home"), "bin/java").path
    val started =
        ProcessBuilder(java, "-jar", jar.path)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
    Runtime.getRuntime().addShutdownHook(Thread { started.destroy() })
    input = started.outputStream.bufferedWriter()
    output = started.inputStream.bufferedReader()
    process = started
    return started
  }
}

/**
 * The compile daemon's LSP mode at [jar] (`--lsp`, see daemon/.../lsp/LspServer.kt), started on
 * first use and restarted if it exits: the server the IC10 Editor talks to in game, so the page
 * completes and hovers as the game does.
 *
 * It runs as a second JVM beside [Daemon], whose line protocol has no completion or hovers. The
 * page sends one message per HTTP request (see `/api/lsp`): a request gets its id here, and its
 * response, with that id, is the HTTP response. The server's notifications (its diagnostics) are
 * dropped, since the page marks the problems its compiles find.
 *
 * A restarted server has no documents open, and answers a request about one with an error saying
 * so; the page then opens its document again. A server that doesn't answer a request in time, or
 * says it is shut down, is stopped, so the next message starts a fresh one.
 */
class LanguageServer(private val jar: File) {
  /** One run of the server, and the requests written to it that wait for its answers. */
  private class Run(val process: Process) {
    val input: OutputStream = process.outputStream
    val waiting = ConcurrentHashMap<Int, CompletableFuture<String>>()
  }

  @Volatile private var run: Run? = null
  private val ids = AtomicInteger()

  init {
    Runtime.getRuntime().addShutdownHook(Thread { run?.process?.destroy() })
  }

  /**
   * Sends a request with [params], one JSON object (see [isJsonObject]), and returns the JSON-RPC
   * response.
   */
  fun request(method: String, params: String): String {
    val id = ids.incrementAndGet()
    val answer = CompletableFuture<String>()
    val sent =
        write(
            """{"jsonrpc":"2.0","id":$id,"method":${jsonString(method)},"params":$params}""",
            id to answer,
        )
    try {
      val response = answer.get(30, TimeUnit.SECONDS)
      if (SHUT_DOWN.containsMatchIn(response)) stop(sent)
      return response
    } catch (e: TimeoutException) {
      stop(sent)
      return failure(id, "The language server didn't answer $method, so it was stopped")
    } finally {
      sent.waiting.remove(id)
    }
  }

  /**
   * Sends a notification with [params], one JSON object (see [isJsonObject]). A `didClose` is
   * dropped when no server is running: a fresh server has no documents open, so there is nothing
   * for it to close.
   */
  fun notify(method: String, params: String) {
    val body = """{"jsonrpc":"2.0","method":${jsonString(method)},"params":$params}"""
    if (method == "textDocument/didClose") writeIfRunning(body) else write(body)
  }

  /**
   * Writes one message to the running server, starting one first if none is running, and returns
   * that run. The [answer] it waits for, by id, is registered before the message is written, so the
   * response can't come first; it is removed again if the message can't be written.
   */
  @Synchronized
  private fun write(body: String, answer: Pair<Int, CompletableFuture<String>>? = null): Run {
    val current = running() ?: start()
    if (answer != null) current.waiting[answer.first] = answer.second
    try {
      frame(current, body)
    } catch (e: Exception) {
      if (answer != null) current.waiting.remove(answer.first)
      throw e
    }
    return current
  }

  /** Writes one message to the running server, if one is running. */
  @Synchronized
  private fun writeIfRunning(body: String) {
    running()?.let { frame(it, body) }
  }

  private fun running(): Run? = run?.takeIf { it.process.isAlive }

  /**
   * Stops [stale] if it is still the running server; the next message starts another. The process
   * is destroyed before the lock is taken, since a write blocked on the server's full input holds
   * it; the destroy breaks that pipe, so the write fails and lets go of the lock. A write that
   * takes the lock in between either finds the process dead and starts a fresh one, or fails
   * writing to it.
   */
  private fun stop(stale: Run) {
    stale.process.destroy()
    synchronized(this) { if (run === stale) run = null }
  }

  private fun frame(to: Run, body: String) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    to.input.write("Content-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.US_ASCII) + bytes)
    to.input.flush()
  }

  /** Starts the server and initializes it. It handles messages in order, so none wait for that. */
  private fun start(): Run {
    val java = File(System.getProperty("java.home"), "bin/java").path
    val started =
        Run(
            ProcessBuilder(java, "-jar", jar.path, "--lsp")
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
        )
    run = started
    val output = BufferedInputStream(started.process.inputStream)
    Thread {
      while (true) {
        val message = read(output) ?: break
        val id = RESPONSE.find(message)?.groupValues?.get(1)?.toIntOrNull() ?: continue
        started.waiting[id]?.complete(message)
      }
      // This run stopped: answer what was sent to it. Requests sent to a later run wait for that.
      for ((id, answer) in started.waiting) {
        answer.complete(failure(id, "The language server stopped"))
      }
    }
        .apply { isDaemon = true }
        .start()
    frame(
        started,
        """{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"capabilities":{}}}""",
    )
    frame(started, """{"jsonrpc":"2.0","method":"initialized","params":{}}""")
    return started
  }

  /** The next message's body, or null at the end of the output (as the daemon's MessageReader). */
  private fun read(from: BufferedInputStream): String? {
    var length = -1
    while (true) {
      val line = readLine(from) ?: return null
      if (line.isEmpty()) {
        if (length >= 0) break else continue
      }
      if (line.substringBefore(':').trim().equals("Content-Length", ignoreCase = true)) {
        length = line.substringAfter(':').trim().toIntOrNull() ?: return null
      }
    }
    val body = from.readNBytes(length)
    return if (body.size < length) null else String(body, Charsets.UTF_8)
  }

  private fun readLine(from: BufferedInputStream): String? {
    val bytes = ByteArrayOutputStream()
    while (true) {
      val b = from.read()
      if (b < 0) return null
      if (b == '\n'.code) break
      bytes.write(b)
    }
    return bytes.toString(Charsets.US_ASCII).removeSuffix("\r")
  }

  private fun failure(id: Int, message: String) =
      """{"jsonrpc":"2.0","id":$id,"error":{"code":-32603,"message":${jsonString(message)}}}"""

  private companion object {
    /** A response's id: the daemon writes `jsonrpc` and then `id` first, in that order. */
    val RESPONSE = Regex("""^\{"jsonrpc":"2\.0","id":(\d+),""")

    /** The error a server that has been shut down answers every request with. */
    val SHUT_DOWN =
        Regex("""^\{"jsonrpc":"2\.0","id":\d+,"error":\{[^}]*"The server is shut down"""")
  }
}

/**
 * Whether [text] is exactly one JSON object (RFC 8259), nested at most 1000 deep, with no number
 * longer than [MAX_NUMBER_LENGTH] characters. Such a text, written into a message as a field's
 * value, is that value and nothing more: it can't add fields of its own, such as another `method`
 * or `id`. The language server's parser (Gson) can't read a number over about 1024 characters, and
 * its answer to a message it can't parse carries no id, so nothing here would ever receive it.
 */
fun isJsonObject(text: String): Boolean {
  val json = JsonCheck(text)
  return json.next() == '{' && json.value(0) && json.atEnd()
}

/** Reads JSON from [text] without keeping it, for [isJsonObject]. */
class JsonCheck(private val text: String) {
  private var at = 0

  /** The next character after any white space, or null at the end. */
  fun next(): Char? {
    while (at < text.length && text[at] in " \t\r\n") at++
    return text.getOrNull(at)
  }

  fun atEnd() = next() == null

  /** Reads one value, inside [depth] arrays and objects, and says whether it is valid. */
  fun value(depth: Int): Boolean =
      when (next()) {
        '{' -> depth < 1000 && members('}', depth) { string() && next() == ':' && skip() }
        '[' -> depth < 1000 && members(']', depth) { true }
        '"' -> string()
        't' -> word("true")
        'f' -> word("false")
        'n' -> word("null")
        else -> number()
      }

  /** Reads `{`/`[`, then values (each after [key]) separated by commas, then [close]. */
  private fun members(close: Char, depth: Int, key: () -> Boolean): Boolean {
    at++
    if (next() == close) return skip()
    while (true) {
      if (!key() || !value(depth + 1)) return false
      when (next()) {
        ',' -> skip()
        close -> return skip()
        else -> return false
      }
    }
  }

  /** Steps past the current character; true, for use in conditions. */
  private fun skip(): Boolean {
    at++
    return true
  }

  private fun word(word: String) = text.startsWith(word, at).also { if (it) at += word.length }

  private fun string(): Boolean {
    if (next() != '"') return false
    at++
    while (at < text.length) {
      val c = text[at++]
      when {
        c == '"' -> return true
        c < ' ' -> return false
        c == '\\' -> {
          val escape = text.getOrNull(at++) ?: return false
          if (escape == 'u') {
            if (at + 4 > text.length || !text.substring(at, at + 4).all(::isHex)) return false
            at += 4
          } else if (escape !in "\"\\/bfnrt") return false
        }
      }
    }
    return false
  }

  private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

  private fun number(): Boolean {
    val start = at
    if (!numberSyntax()) return false
    return at - start <= MAX_NUMBER_LENGTH
  }

  private fun numberSyntax(): Boolean {
    if (text.getOrNull(at) == '-') at++
    if (text.getOrNull(at) == '0') at++ else if (!digits()) return false
    if (text.getOrNull(at) == '.') {
      at++
      if (!digits()) return false
    }
    if (text.getOrNull(at) == 'e' || text.getOrNull(at) == 'E') {
      at++
      if (text.getOrNull(at) == '+' || text.getOrNull(at) == '-') at++
      if (!digits()) return false
    }
    return true
  }

  private fun digits(): Boolean {
    val start = at
    while (at < text.length && text[at] in '0'..'9') at++
    return at > start
  }
}

val MAX_NUMBER_LENGTH = 100

/** The LSP requests and notifications the page may send, all about its own document. */
val lspRequests =
    setOf("textDocument/completion", "textDocument/hover", "textDocument/signatureHelp")
val lspNotifications =
    setOf("textDocument/didOpen", "textDocument/didChange", "textDocument/didClose")

fun jsonString(text: String): String = buildString {
  append('"')
  for (c in text) {
    when {
      c == '"' -> append("\\\"")
      c == '\\' -> append("\\\\")
      c == '\n' -> append("\\n")
      c == '\r' -> append("\\r")
      c == '\t' -> append("\\t")
      c < ' ' -> append("\\u%04x".format(c.code))
      else -> append(c)
    }
  }
  append('"')
}

fun sources(): Map<String, List<String>> =
    sourceDirs
        .filter { it.isDirectory }
        .associate { dir ->
          dir.path.replace('\\', '/') to
              dir.listFiles { file -> file.name.endsWith(".ic10.kts") }
                  .orEmpty()
                  .map { it.name }
                  .sorted()
        }

/** The file the page asked for, if it is a .ic10.kts program directly inside a listed directory. */
fun source(group: String, name: String): File? {
  val dir = sourceDirs.firstOrNull { it.path.replace('\\', '/') == group } ?: return null
  val file = dir.resolve(name)
  return file.takeIf {
    it.isFile && it.name.endsWith(".ic10.kts") && it.canonicalFile.parentFile == dir.canonicalFile
  }
}

/**
 * The test file kept beside [program]: `X.test.json` for `X.ic10.kts`, in the program's own
 * directory. A symbolic link there, which could point anywhere, is refused, so it is neither read
 * nor written through. A hard link can't be told from an ordinary file without platform-specific
 * calls, so one is still read through; [saveTest] replaces it rather than writing through it.
 */
fun testOf(program: File): File? {
  val dir = program.canonicalFile.parentFile
  val name = program.canonicalFile.name.removeSuffix(".ic10.kts") + ".test.json"
  val file = dir.resolve(name)
  return file.takeIf {
    !Files.isSymbolicLink(it.toPath()) &&
        it.canonicalFile.parentFile == dir &&
        it.canonicalFile.name == name
  }
}

/**
 * Writes [body] to [file] through a temporary file in the same directory that then takes its place,
 * so a reader never sees half a file and a hard link at [file] is replaced, not written through.
 */
fun saveTest(file: File, body: ByteArray) {
  val target = file.toPath()
  val temp = Files.createTempFile(target.parent, ".${file.name}.", ".tmp")
  try {
    Files.write(temp, body)
    try {
      Files.move(
          temp,
          target,
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE,
      )
    } catch (e: AtomicMoveNotSupportedException) {
      Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
    }
  } finally {
    Files.deleteIfExists(temp)
  }
}

val MAX_TEST_BYTES = 1 shl 20

/** The largest LSP message body the page may send: a whole program, in a `didOpen`/`didChange`. */
val MAX_LSP_BYTES = 1 shl 20

fun query(exchange: HttpExchange): Map<String, String> =
    exchange.requestURI.rawQuery
        .orEmpty()
        .split('&')
        .filter { '=' in it }
        .associate {
          val (key, value) = it.split('=', limit = 2)
          URLDecoder.decode(key, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
        }

fun send(exchange: HttpExchange, status: Int, body: String, type: String = "application/json") {
  val bytes = body.toByteArray()
  exchange.responseHeaders.add("Content-Type", "$type; charset=utf-8")
  exchange.responseHeaders.add("Cache-Control", "no-store")
  exchange.sendResponseHeaders(status, bytes.size.toLong())
  exchange.responseBody.use { it.write(bytes) }
}

val version =
    runCatching {
      ProcessBuilder("git", "describe", "--always", "--dirty")
          .start()
          .inputStream
          .bufferedReader()
          .readText()
          .trim()
    }
        .getOrNull()
        ?.ifEmpty { null } ?: "dev"

val daemon = Daemon(daemonJar)

val languageServer = LanguageServer(daemonJar)

val hosts = setOf("127.0.0.1:$port", "localhost:$port")

/**
 * Whether the request comes from this playground's own page. Listening on 127.0.0.1 keeps other
 * machines out but not other pages in the user's browser: checking `Host` defeats DNS rebinding,
 * and checking `Origin` refuses requests that other sites send.
 */
fun isLocal(exchange: HttpExchange): Boolean {
  val origin = exchange.requestHeaders.getFirst("Origin")
  return exchange.requestHeaders.getFirst("Host") in hosts &&
      (origin == null || origin.removePrefix("http://") in hosts)
}

val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)

// A thread per request, so a slow compile does not hold up loading the page.
server.executor = Executors.newCachedThreadPool()

server.createContext("/") { exchange ->
  try {
    val path = exchange.requestURI.path
    when {
      !isLocal(exchange) -> send(exchange, 403, """{"error":"forbidden"}""")
      exchange.requestMethod == "GET" && (path == "/" || path == "/index.html") ->
          send(exchange, 200, page.readText(), "text/html")
      exchange.requestMethod == "GET" && path.removePrefix("/") in scripts ->
          send(
              exchange,
              200,
              scripts.getValue(path.removePrefix("/")).readText(),
              "text/javascript",
          )
      exchange.requestMethod == "GET" && path == "/api/info" -> {
        val groups =
            sources().entries.joinToString(",") { (group, names) ->
              jsonString(group) + ":[" + names.joinToString(",") { jsonString(it) } + "]"
            }
        send(exchange, 200, """{"version":${jsonString(version)},"sources":{$groups}}""")
      }
      exchange.requestMethod == "GET" && path == "/api/source" -> {
        val params = query(exchange)
        val file = source(params["group"].orEmpty(), params["name"].orEmpty())
        if (file == null) send(exchange, 404, """{"error":"not found"}""")
        else send(exchange, 200, """{"code":${jsonString(file.readText())}}""")
      }
      exchange.requestMethod == "GET" && path == "/api/test" -> {
        val params = query(exchange)
        val file = source(params["group"].orEmpty(), params["name"].orEmpty())?.let(::testOf)
        when {
          file == null -> send(exchange, 404, """{"error":"not found"}""")
          !file.isFile -> send(exchange, 200, """{"test":null}""")
          else -> send(exchange, 200, """{"test":${jsonString(file.readText())}}""")
        }
      }
      exchange.requestMethod == "POST" && path == "/api/test" -> {
        // The same JSON-only rule as compiling: the body is the test file's text, as JSON.
        val type = exchange.requestHeaders.getFirst("Content-Type").orEmpty()
        val params = query(exchange)
        val file = source(params["group"].orEmpty(), params["name"].orEmpty())?.let(::testOf)
        val body = exchange.requestBody.use { it.readNBytes(MAX_TEST_BYTES + 1) }
        when {
          !type.startsWith("application/json") ->
              send(exchange, 415, """{"error":{"description":"Send application/json"}}""")
          file == null -> send(exchange, 404, """{"error":"not found"}""")
          body.size > MAX_TEST_BYTES ->
              send(exchange, 413, """{"error":{"description":"Too large"}}""")
          else -> {
            saveTest(file, body)
            send(exchange, 200, """{"saved":${jsonString(file.name)}}""")
          }
        }
      }
      exchange.requestMethod == "POST" && (path == "/api/compile" || path == "/api/run") -> {
        // Requiring JSON makes a cross-site request need a CORS preflight, which this server fails.
        val type = exchange.requestHeaders.getFirst("Content-Type").orEmpty()
        val request = exchange.requestBody.use { it.readBytes().decodeToString() }
        when {
          !type.startsWith("application/json") ->
              send(exchange, 415, """{"error":{"description":"Send application/json"}}""")
          request.isBlank() -> send(exchange, 400, """{"error":{"description":"No request"}}""")
          else -> send(exchange, 200, daemon.request(request))
        }
      }
      exchange.requestMethod == "POST" && path == "/api/lsp" -> {
        // One LSP message to the language server: its method in the query, its params as the body.
        // A request is answered with the JSON-RPC response; a notification with {}. The message's
        // method and id are the relay's own; the body is only ever the value of its params.
        val type = exchange.requestHeaders.getFirst("Content-Type").orEmpty()
        val method = query(exchange)["method"].orEmpty()
        val body = exchange.requestBody.use { it.readNBytes(MAX_LSP_BYTES + 1) }
        val params = body.decodeToString()
        when {
          !type.startsWith("application/json") ->
              send(exchange, 415, """{"error":{"description":"Send application/json"}}""")
          body.size > MAX_LSP_BYTES ->
              send(exchange, 413, """{"error":{"description":"Too large"}}""")
          !isJsonObject(params) ->
              send(exchange, 400, """{"error":{"description":"Send one JSON object"}}""")
          method in lspRequests -> send(exchange, 200, languageServer.request(method, params))
          method in lspNotifications -> {
            languageServer.notify(method, params)
            send(exchange, 200, "{}")
          }
          else -> send(exchange, 404, """{"error":"not found"}""")
        }
      }
      else -> send(exchange, 404, """{"error":"not found"}""")
    }
  } catch (e: Exception) {
    System.err.println("Request failed: $e")
    runCatching { send(exchange, 500, """{"error":{"description":${jsonString(e.toString())}}}""") }
  }
}

server.start()

println("KTrapIC $version playground on http://127.0.0.1:$port/ (Ctrl+C to stop)")

// The server runs on its own threads; keep the script alive until it is stopped.
Thread.currentThread().join()
