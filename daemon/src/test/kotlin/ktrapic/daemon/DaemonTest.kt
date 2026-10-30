package ktrapic.daemon

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.Base64
import ktrapic.daemon.lsp.MessageReader
import org.junit.jupiter.api.Test

class DaemonTest {
  private fun request(json: String): JsonObject {
    val line = Base64.getEncoder().encodeToString(json.toByteArray())
    return JsonParser.parseString(String(Base64.getDecoder().decode(Daemon.handle(line))))
        .asJsonObject
  }

  @Test
  fun `compiles a program`() {
    val response =
        request("""{"action": "compile", "code": {"": "import ktrapic.*\ndb.Setting = 1.0\n"}}""")

    assertThat(response["code"].asString).isEqualTo("s db Setting 1")
    assertThat(response["num_lines"].asInt).isEqualTo(1)
    assertThat(response["num_registers"].asInt).isEqualTo(0)
    // Source line 1 (the second) compiled to line 0 of the code.
    assertThat(response["source_mapping"].toString()).isEqualTo("""{"1":[0]}""")
  }

  @Test
  fun `accepts the source as a plain string and compiles as its annotations say`() {
    val source = "import ktrapic.*\\nvar i = 0\\nwhile (i < 3) i++\\ndb.Setting = i.toDouble()\\n"
    val labeled = request("""{"action": "compile", "code": "$source"}""")
    val compact = request("""{"action": "compile", "code": "@file:Compact\n$source"}""")

    assertThat(labeled["code"].asString).contains(":")
    assertThat(compact["code"].asString).doesNotContain(":")
  }

  @Test
  fun `ignores the options older clients send`() {
    val source =
        "import ktrapic.*\\nfun down(n: Int): Int = if (n == 0) 0 else down(n - 1)\\n" +
            "fun f(x: Double) = x + 1.0\\ndb.Setting = f(1.0)\\ndb.Ratio = f(2.0)\\n" +
            "db.Mode = down(3).toDouble()\\n"
    val plain = request("""{"action": "compile", "code": "$source"}""")
    val options = """{"compact": true, "stack_calls": false, "tail_calls": false, "other": 1}"""
    val withOptions = request("""{"action": "compile", "code": "$source", "options": $options}""")
    val run = request("""{"action": "run", "code": "$source", "options": $options, "test": {}}""")

    assertThat(plain["code"].asString).contains(":")
    assertThat(plain["code"].asString).contains("push 1\njal f")
    assertThat(plain["code"].asString).contains("j down_loop")
    assertThat(withOptions).isEqualTo(plain)
    assertThat(run["code"]).isEqualTo(plain["code"])
  }

  @Test
  fun `reports errors with their position`() {
    val error =
        request("""{"action": "compile", "code": {"": "import ktrapic.*\nd0.On = 1.0\n"}}""")[
                "error"]
            .asJsonObject

    assertThat(error["line"].asInt).isEqualTo(2)
    assertThat(error["description"].asString).isNotEmpty()
  }

  @Test
  fun `marks the Kotlin compiler's own problems`() {
    val kotlinError =
        request("""{"action": "compile", "code": "val x: Int = \"s\"\n"}""")["error"]
            .asJsonObject["errors"]
            .asJsonArray[0]
            .asJsonObject
    val ownError =
        request("""{"action": "compile", "code": "val (a, b) = Pair(1, 2)\n"}""")["error"]
            .asJsonObject["errors"]
            .asJsonArray[0]
            .asJsonObject
    val ownWarning =
        request("""{"action": "compile", "code": "val unused = 2\n"}""")["warnings"]
            .asJsonArray[0]
            .asJsonObject

    assertThat(kotlinError["kotlin"].asBoolean).isTrue()
    assertThat(ownError.has("kotlin")).isFalse()
    assertThat(ownWarning.has("kotlin")).isFalse()
  }

  @Test
  fun `runs a program against a test`() {
    val source = "import ktrapic.*\\nd1.On = d0.Temperature < 290.0\\n"
    val test =
        """
        {"devices": [
           {"pin": "d0", "prefab": "StructureGasSensor", "logic": {"Temperature": 280}},
           {"id": "heater", "pin": "d1", "prefab": "StructureWallHeater"}],
         "expect": [{"device": "heater", "logic": "On", "equals": 1}]}
        """
    val response =
        request("""{"action": "run", "code": "$source", "test": $test, "trace_tick": 1}""")

    assertThat(response["code"].asString).isNotEmpty()
    val run = response["run"].asJsonObject
    assertThat(run["passed"].asBoolean).isTrue()
    assertThat(run["checks"].asJsonArray[0].asJsonObject["description"].asString)
        .isEqualTo("heater.On == 1 after the run: was 1")
    assertThat(run["lines"].asJsonArray).isNotEmpty()
  }

  @Test
  fun `answers a test it can't run with the compile result and an error`() {
    val response =
        request(
            """{"action": "run", "code": "import ktrapic.*\ndb.Setting = 1.0\n", "test": {"ticks": 0}}"""
        )

    assertThat(response["code"].asString).isEqualTo("s db Setting 1")
    assertThat(response["run"].asJsonObject["error"].asJsonObject["description"].asString)
        .isEqualTo("ticks must be 1 to 10000")
    assertThat(request("""{"action": "run", "code": "", "test": []}""").toString())
        .contains("must be a JSON object")
  }

  @Test
  fun `trace_tick must be a whole number within the test's ticks`() {
    fun run(traceTick: String) =
        request(
            """{"action": "run", "code": "import ktrapic.*\ndb.Setting = 1.0\n",
                "test": {"ticks": 4}, "trace_tick": $traceTick}"""
        )
    fun runError(traceTick: String) =
        run(traceTick)["run"].asJsonObject["error"].asJsonObject["description"].asString

    assertThat(run("1.5")["error"].asJsonObject["description"].asString)
        .isEqualTo("trace_tick must be a whole number")
    assertThat(run("\"2\"")["error"].asJsonObject["description"].asString)
        .isEqualTo("trace_tick must be a whole number")
    assertThat(runError("0")).isEqualTo("trace_tick: the test runs ticks 1 to 4")
    assertThat(runError("5")).isEqualTo("trace_tick: the test runs ticks 1 to 4")
    assertThat(runError("1e20")).isEqualTo("trace_tick: the test runs ticks 1 to 4")
    assertThat(run("4")["run"].asJsonObject["passed"].asBoolean).isTrue()
  }

  @Test
  fun `rejects bad requests`() {
    assertThat(request("""{"action": "format"}""")["error"].asJsonObject["description"].asString)
        .contains("Invalid action")
    assertThat(request("""{"action": "compile"}""")["error"].asJsonObject["description"].asString)
        .contains("No code")
    assertThat(
            request("""{"action": "compile", "code": {"": null}}""")["error"]
                .asJsonObject["description"]
                .asString
        )
        .contains("No code")
    assertThat(
            request("""{"action": "compile", "code": {"": {"nested": 1}}}""")["error"]
                .asJsonObject["description"]
                .asString
        )
        .contains("No code")
    assertThat(request("[1, 2]")["error"].asJsonObject["description"].asString).contains("base64")
    val garbage = String(Base64.getDecoder().decode(Daemon.handle("not base64 at all!")))

    assertThat(garbage).contains("base64")
  }

  @Test
  fun `answers every request line and keeps running`() {
    fun encode(json: String) = Base64.getEncoder().encodeToString(json.toByteArray())
    val good = encode("""{"action": "compile", "code": "import ktrapic.*\ndb.Setting = 1.0\n"}""")
    val nested = "(".repeat(20_000) + "1.0" + ")".repeat(20_000)
    val overflowing =
        encode("""{"action": "compile", "code": "import ktrapic.*\ndb.Setting = $nested\n"}""")
    val requests =
        listOf(good, "", encode("""{"action": "compile", "code": {"": null}}"""), overflowing, good)
            .joinToString("\n", postfix = "\n")
    val stdin = System.`in`
    val stdout = System.out
    val captured = ByteArrayOutputStream()
    try {
      System.setIn(ByteArrayInputStream(requests.toByteArray()))
      System.setOut(PrintStream(captured, true))
      main(arrayOf())
    } finally {
      System.setIn(stdin)
      System.setOut(stdout)
    }

    val responses =
        captured
            .toString()
            .lines()
            .filter { it.isNotEmpty() }
            .map {
              JsonParser.parseString(String(Base64.getDecoder().decode(it))).asJsonObject
            }
    assertThat(responses).hasSize(5)
    assertThat(responses[0]["code"].asString).isEqualTo("s db Setting 1")
    assertThat(responses[1].has("error")).isTrue()
    assertThat(responses[2].has("error")).isTrue()
    val overflow = responses[3]["error"].asJsonObject
    assertThat(overflow["description"].asString)
        .startsWith("Internal compiler error: java.lang.StackOverflowError")
    assertThat(overflow.has("stack_trace")).isTrue()
    assertThat(responses[4]["code"].asString).isEqualTo("s db Setting 1")
  }

  @Test
  fun `serves LSP with only protocol messages on stdout`() {
    fun frame(body: String) = "Content-Length: ${body.toByteArray().size}\r\n\r\n$body"
    val messages =
        listOf(
                """{"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}}""",
                """{"jsonrpc": "2.0", "method": "initialized", "params": {}}""",
                """{"jsonrpc": "2.0", "id": 2, "method": "shutdown"}""",
                """{"jsonrpc": "2.0", "method": "exit"}""",
            )
            .joinToString("") { frame(it) }
    val stdout = System.out
    val stderr = System.err
    val capturedOut = ByteArrayOutputStream()
    val capturedErr = ByteArrayOutputStream()
    val protocol = ByteArrayOutputStream()
    val exitCode: Int
    try {
      System.setOut(PrintStream(capturedOut, true))
      System.setErr(PrintStream(capturedErr, true))
      exitCode = serveLsp(ByteArrayInputStream(messages.toByteArray()), protocol)
      // Anything printed afterwards goes to stderr too.
      println("stray")
    } finally {
      System.setOut(stdout)
      System.setErr(stderr)
    }

    assertThat(exitCode).isEqualTo(0)
    assertThat(capturedOut.size()).isEqualTo(0)
    assertThat(capturedErr.toString()).contains("ready (LSP)")
    assertThat(capturedErr.toString()).contains("stray")
    val reader = MessageReader(ByteArrayInputStream(protocol.toByteArray())) {}
    val ids = mutableListOf<Int>()
    while (true) ids += JsonParser.parseString(reader.read() ?: break).asJsonObject["id"].asInt
    assertThat(ids).containsExactly(1, 2).inOrder()
  }
}
