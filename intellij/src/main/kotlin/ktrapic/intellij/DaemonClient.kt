package ktrapic.intellij

import com.google.gson.JsonObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.util.ArrayDeque
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Compiles KTrapIC programs to IC10. */
fun interface Ic10Compiler {
  fun compile(code: String): CompileResponse
}

/**
 * KTrapIC's compile daemon, run as a separate process by [command] (`java -jar` the daemon's deploy
 * jar), speaking its line protocol: one line of base64-encoded JSON per request and per response.
 * The daemon bundles its own Kotlin compiler, so it runs outside the IDE's JVM.
 *
 * The process starts on the first compile, and again on the next compile after it exits or stops
 * answering within [timeoutMillis]. Compiles are answered one at a time, as the daemon answers
 * them. [log] gets each line the daemon writes to its standard error.
 */
class DaemonClient(
    val command: List<String>,
    private val timeoutMillis: Long = 120_000,
    private val log: (String) -> Unit = {},
) : Ic10Compiler, AutoCloseable {
  @Volatile private var running: Running? = null
  @Volatile private var closed = false

  /** The daemon's process, if it is running. */
  val processForTests: Process?
    @Synchronized get() = running?.process

  @Synchronized
  override fun compile(code: String): CompileResponse {
    val request =
        JsonObject().apply {
          addProperty("action", "compile")
          addProperty("code", code)
        }
    return when (val answer = request(request.toString())) {
      is Answer.Json -> CompileResponse.parse(answer.json)
      is Answer.None -> CompileResponse.Failure(answer.why, answered = false)
    }
  }

  /** Sends one JSON request and returns the daemon's JSON response, or why there is none. */
  private fun request(json: String): Answer {
    if (closed) return Answer.None("The compile daemon has been stopped")
    val daemon =
        running?.takeIf { it.process.isAlive }
            ?: try {
              start()
            } catch (e: IOException) {
              return Answer.None(
                  "The compile daemon couldn't be started (${command.joinToString(" ")}): ${e.message}"
              )
            }
    if (closed) {
      // Closed while it was starting.
      stop(daemon)
      return Answer.None("The compile daemon has been stopped")
    }
    try {
      daemon.input.write(Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8)))
      daemon.input.newLine()
      daemon.input.flush()
    } catch (e: IOException) {
      return stopped(daemon, "it couldn't be sent the request (${e.message})")
    }
    val line =
        daemon.responses.poll(timeoutMillis, TimeUnit.MILLISECONDS)
            ?: return stopped(daemon, "it didn't answer within ${timeoutMillis / 1000} seconds")
    val response = line.value ?: return stopped(daemon, "it exited")
    return try {
      Answer.Json(String(Base64.getDecoder().decode(response.trim()), Charsets.UTF_8))
    } catch (e: IllegalArgumentException) {
      stopped(daemon, "it answered with a line that isn't base64")
    }
  }

  /**
   * Stops the daemon, without waiting for a compile in progress, which fails; later compiles fail
   * too.
   */
  override fun close() {
    closed = true
    running?.process?.destroy()
  }

  private fun start(): Running {
    val process = ProcessBuilder(command).start()
    val daemon = Running(process)
    val name = "KTrapIC daemon ${process.pid()}"
    thread("$name output") {
      process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
        while (true) {
          val line = readLine(reader)
          daemon.responses.put(Line(line))
          if (line == null) break
        }
      }
    }
    daemon.errorReader =
        thread("$name errors") {
          process.errorStream.bufferedReader(Charsets.UTF_8).use { reader ->
            while (true) {
              val line = readLine(reader) ?: break
              synchronized(daemon.errors) {
                daemon.errors.addLast(line)
                if (daemon.errors.size > ERROR_LINES_KEPT) daemon.errors.removeFirst()
              }
              log(line)
            }
          }
        }
    running = daemon
    return daemon
  }

  private fun stopped(daemon: Running, why: String): Answer {
    stop(daemon)
    running = null
    // Its last words, once its standard error has been read to the end.
    daemon.errorReader?.join(1000)
    val errors = synchronized(daemon.errors) { daemon.errors.joinToString("\n") }
    val detail = if (errors.isBlank()) "" else "; its last output:\n$errors"
    return Answer.None("The compile daemon stopped: $why. It restarts on the next compile$detail")
  }

  private fun stop(daemon: Running) {
    try {
      daemon.input.close()
    } catch (_: IOException) {}
    daemon.process.destroy()
    if (!daemon.process.waitFor(5, TimeUnit.SECONDS)) daemon.process.destroyForcibly()
  }

  /** The next line [reader] reads, or null at the end or once the stream is closed. */
  private fun readLine(reader: BufferedReader): String? =
      try {
        reader.readLine()
      } catch (_: IOException) {
        null
      }

  private fun thread(name: String, body: () -> Unit) =
      Thread(body, name).apply {
        isDaemon = true
        start()
      }

  /** What a request got: the daemon's response, or why there is none. */
  private sealed interface Answer {
    class Json(val json: String) : Answer

    class None(val why: String) : Answer
  }

  /** A line the daemon wrote, or null [value] at the end of its output. */
  private class Line(val value: String?)

  private class Running(val process: Process) {
    val input: BufferedWriter = process.outputStream.bufferedWriter(Charsets.UTF_8)
    val responses = LinkedBlockingQueue<Line>()
    val errors = ArrayDeque<String>()
    @Volatile var errorReader: Thread? = null
  }

  private companion object {
    const val ERROR_LINES_KEPT = 20
  }
}
