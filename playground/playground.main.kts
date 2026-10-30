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
// The Open menu lists the .ic10.kts programs in the source directories (default: test/cases); a
// program's test (X.test.json beside X.ic10.kts, see docs/TESTING.md) opens with it, and saving
// the test writes that file.

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.concurrent.Executors
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

/** The page's file-annotation code, which it loads as a script of its own. */
val annotations = File("playground/annotations.js")

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
      exchange.requestMethod == "GET" && path == "/annotations.js" ->
          send(exchange, 200, annotations.readText(), "text/javascript")
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
