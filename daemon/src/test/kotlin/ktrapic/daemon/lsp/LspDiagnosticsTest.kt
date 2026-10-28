package ktrapic.daemon.lsp

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Base64
import java.util.concurrent.TimeUnit
import ktrapic.daemon.Daemon
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/** [LspServer]'s diagnostics as you type, and the requests that go with them. */
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class LspDiagnosticsTest {
  private val gson = Gson()

  /** The source of each compile the server ran, in order. */
  private val compiled = java.util.Collections.synchronizedList(mutableListOf<String>())

  /** Called with each source before it is compiled. */
  @Volatile private var beforeCompile: (String) -> Unit = {}

  private val scheduler = ManualScheduler()
  private val client =
      LspTestClient(scheduler) { source, options ->
        compiled += source
        beforeCompile(source)
        Daemon.compile(source, options)
      }

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

  /** The diagnostics the server published since the last call, after running its compiles. */
  private fun published(): List<JsonObject> {
    client.sync()
    scheduler.runAll()
    return client.takeDiagnostics()
  }

  private fun JsonObject.diagnostics() = this["diagnostics"].asJsonArray.map { it.asJsonObject }

  private fun range(startLine: Int, startCharacter: Int, endLine: Int, endCharacter: Int) =
      JsonParser.parseString(
          """{"start": {"line": $startLine, "character": $startCharacter},
            "end": {"line": $endLine, "character": $endCharacter}}"""
      )

  @Test
  fun `publishes a compile error at its range in UTF-16 code units, after the debounce`() {
    client.initialize()
    // The emoji is one code point but two UTF-16 code units, so Foo starts at character 17.
    client.open("file:///a.kt", "db.Setting = 1.0\nval s = \"😀\"; d0.Foo = 1.0\n", version = 3)

    val published = published()

    assertThat(scheduler.delays).containsExactly(150L)
    val params = published.single()
    assertThat(params["uri"].asString).isEqualTo("file:///a.kt")
    assertThat(params["version"].asInt).isEqualTo(3)
    val diagnostic = params.diagnostics().single()
    assertThat(diagnostic["range"]).isEqualTo(range(1, 17, 1, 20))
    assertThat(diagnostic["severity"].asInt).isEqualTo(1)
    assertThat(diagnostic["message"].asString).contains("Unresolved reference 'Foo'")
  }

  @Test
  fun `publishes every error of a failed compile`() {
    client.initialize()
    client.open("file:///a.kt", "db.Setting = nope\nd0.On = foo\n")

    val diagnostics = published().single().diagnostics()

    assertThat(diagnostics.map { it["range"] })
        .containsExactly(range(0, 13, 0, 17), range(1, 8, 1, 11))
        .inOrder()
    assertThat(diagnostics.map { it["severity"].asInt }).containsExactly(1, 1)
  }

  @Test
  fun `publishes an empty list for a change that fixes the error, with the new version`() {
    client.initialize()
    client.open("file:///a.kt", "db.Setting = nope\n")
    assertThat(published().single().diagnostics()).hasSize(1)

    client.change("file:///a.kt", 2, "db.Setting = 1.0\n")
    val params = published().single()

    assertThat(params["version"].asInt).isEqualTo(2)
    assertThat(params.diagnostics()).isEmpty()
  }

  @Test
  fun `publishes an empty list when a document is closed`() {
    client.initialize()
    client.open("file:///a.kt", "db.Setting = nope\n")
    assertThat(published().single().diagnostics()).hasSize(1)

    client.close("file:///a.kt")
    val params = published().single()

    assertThat(params["uri"].asString).isEqualTo("file:///a.kt")
    assertThat(params.has("version")).isFalse()
    assertThat(params.diagnostics()).isEmpty()
  }

  @Test
  fun `a change before the compile replaces it, so only the newer version is compiled and published`() {
    client.initialize()
    client.open("file:///a.kt", "db.Setting = nope\n")
    client.change("file:///a.kt", 2, "db.Setting = 1.0\n")

    val published = published()

    assertThat(published.map { it["version"].asInt }).containsExactly(2)
    assertThat(published.single().diagnostics()).isEmpty()
    assertThat(compiled).containsExactly("db.Setting = 1.0\n")
  }

  @Test
  fun `a change while compiling drops that compile's diagnostics`() {
    client.initialize()
    client.open("file:///a.kt", "db.Setting = nope\n")
    beforeCompile = { source ->
      if (source == "db.Setting = nope\n") {
        client.change("file:///a.kt", 2, "db.Setting = 1.0\n")
        client.sync()
      }
    }

    val published = published()

    assertThat(compiled).containsExactly("db.Setting = nope\n", "db.Setting = 1.0\n").inOrder()
    assertThat(published.map { it["version"].asInt }).containsExactly(2)
    assertThat(published.single().diagnostics()).isEmpty()
  }

  @Test
  fun `a document closed while compiling gets no diagnostics after the empty list`() {
    client.initialize()
    client.open("file:///a.kt", "db.Setting = nope\n")
    beforeCompile = {
      client.close("file:///a.kt")
      client.sync()
    }

    val published = published()

    assertThat(published.map { it.diagnostics().size }).containsExactly(0)
  }

  @Test
  fun `answers semantic tokens with none`() {
    client.initialize()
    client.open("file:///a.kt", "db.Setting = 1.0\n")

    val response =
        client.request(
            "textDocument/semanticTokens/full",
            """{"textDocument": {"uri": "file:///a.kt"}}""",
        )

    assertThat(response["result"].toString()).isEqualTo("""{"data":[]}""")
  }

  @Test
  fun `ktrapic-compile and diagnostics share a compile of the same text and options`() {
    val program = "var i = 0\nwhile (i < 3) i++\ndb.Setting = i.toDouble()\n"
    client.initialize()
    client.open("file:///a.kt", program)
    published()

    val labelled = compile("file:///a.kt")
    val compact = compile("file:///a.kt", """{"compact": true}""")
    // Diagnostics now compile with the latest options, so this compile serves the request after.
    client.change("file:///a.kt", 2, program + "db.Setting = 1.0\n")
    published()
    val changed = compile("file:///a.kt", """{"compact": true}""")

    assertThat(labelled["result"]).isEqualTo(lineProtocolCompile(program))
    assertThat(compact["result"]).isEqualTo(lineProtocolCompile(program, """{"compact": true}"""))
    assertThat(changed["result"])
        .isEqualTo(lineProtocolCompile(program + "db.Setting = 1.0\n", """{"compact": true}"""))
    assertThat(compiled).containsExactly(program, program, program + "db.Setting = 1.0\n")
  }

  @Test
  fun `publishes diagnostics from its own worker thread`() {
    LspTestClient(WorkerScheduler(), debounceMillis = 0).use { client ->
      client.initialize()
      client.open("file:///a.kt", "db.Setting = nope\n")
      val error = client.awaitDiagnostics()
      client.change("file:///a.kt", 2, "db.Setting = 1.0\n")
      val fixed = client.awaitDiagnostics()

      assertThat(error["version"].asInt).isEqualTo(1)
      assertThat(error.diagnostics().single()["range"]).isEqualTo(range(0, 13, 0, 17))
      assertThat(fixed["version"].asInt).isEqualTo(2)
      assertThat(fixed.diagnostics()).isEmpty()
    }
  }
}
