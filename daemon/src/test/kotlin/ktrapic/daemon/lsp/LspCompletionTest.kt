package ktrapic.daemon.lsp

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonObject
import org.junit.jupiter.api.Test

class LspCompletionTest {
  private val uri = "ktrapic:///1/program.kt"

  private fun JsonObject.items() =
      this["result"].asJsonObject["items"].asJsonArray.map { it.asJsonObject }

  private fun LspTestClient.complete(line: Int, character: Int) =
      request(
          "textDocument/completion",
          """{"textDocument": {"uri": "$uri"}, "position": {"line": $line, "character": $character}}""",
      )

  @Test
  fun `completion after a device lists its logic types as a CompletionList`() {
    LspTestClient().use { client ->
      client.initialize()
      client.open(uri, "val x = 1\nd0.\n")

      val result = client.complete(1, 3)["result"].asJsonObject
      assertThat(result["isIncomplete"].asBoolean).isFalse()
      val setting = client.complete(1, 3).items().single { it["label"].asString == "Setting" }
      assertThat(setting["kind"].asInt).isEqualTo(10)
      assertThat(setting["detail"].asString).isEqualTo("var Setting: Double")
    }
  }

  @Test
  fun `completion uses the document's text after changes`() {
    LspTestClient().use { client ->
      client.initialize()
      client.open(uri, "val x = 1\n")
      client.notify(
          "textDocument/didChange",
          """{"textDocument": {"uri": "$uri", "version": 2}, "contentChanges": [
            {"range": {"start": {"line": 1, "character": 0}, "end": {"line": 1, "character": 0}},
             "text": "PresenceSensor."}]}""",
      )

      assertThat(client.complete(1, 16).items().map { it["label"].asString })
          .containsAtLeast("Motion", "Proximity", "Occupancy")
    }
  }

  @Test
  fun `items keep the completer's order and use kinds the IC10 Editor can show`() {
    val completions =
        ktrapic.frontend.Completion.Kind.entries.map {
          ktrapic.frontend.Completion(it.name.lowercase(), it, "")
        }
    LspTestClient(completer = { _, _ -> completions }).use { client ->
      client.initialize()
      client.open(uri, "")

      val items = client.complete(0, 0).items()
      assertThat(items.map { it["label"].asString }).isEqualTo(completions.map { it.label })
      assertThat(items.map { it["sortText"].asString }).isInOrder()
      assertThat(items.map { it["kind"].asInt }.all { it in 1..23 }).isTrue()
      assertThat(items.any { it.has("detail") }).isFalse()
    }
  }

  @Test
  fun `completion is given the text and the position's index in it`() {
    var given: Pair<String, Int>? = null
    LspTestClient(
            completer = { text, offset ->
              emptyList<ktrapic.frontend.Completion>().also { given = text to offset }
            }
        )
        .use { client ->
          client.initialize()
          client.open(uri, "val a = 1\r\nval b = a.\r\n")

          client.complete(1, 10)
          assertThat(given).isEqualTo("val a = 1\r\nval b = a.\r\n" to 21)
        }
  }

  @Test
  fun `a completion that overflows the stack is answered with an error and the server carries on`() {
    LspTestClient().use { client ->
      client.initialize()
      client.open(uri, "d0.Setting = ${"(".repeat(20_000)}1.0${")".repeat(20_000)}\nd0.\n")

      val overflowed = client.complete(1, 3)
      client.change(uri, 2, "val x = 1\nd0.\n")
      val next = client.complete(1, 3)

      val error = overflowed["error"].asJsonObject
      assertThat(error["code"].asInt).isEqualTo(-32603)
      assertThat(error["message"].asString).contains("StackOverflowError")
      assertThat(next.items().map { it["label"].asString }).contains("Setting")
    }
  }

  @Test
  fun `a completion that throws an Error is answered with an error and the server carries on`() {
    var calls = 0
    LspTestClient(
            completer = { _, _ ->
              if (calls++ == 0) throw AssertionError("boom")
              listOf(
                  ktrapic.frontend.Completion(
                      "Setting",
                      ktrapic.frontend.Completion.Kind.PROPERTY,
                      "",
                  )
              )
            }
        )
        .use { client ->
          client.initialize()
          client.open(uri, "d0.\n")

          val failed = client.complete(0, 3)
          val next = client.complete(0, 3)

          assertThat(failed["error"].asJsonObject["code"].asInt).isEqualTo(-32603)
          assertThat(failed["error"].asJsonObject["message"].asString).contains("boom")
          assertThat(next.items().map { it["label"].asString }).containsExactly("Setting")
        }
  }

  @Test
  fun `a burst of the same completion analyses once, ahead of a compile`() {
    val calls = java.util.concurrent.atomic.AtomicInteger()
    LspTestClient(
            completer = { _, _ ->
              calls.incrementAndGet()
              Thread.sleep(200)
              emptyList()
            },
            compiler = { _, _ -> JsonObject() },
        )
        .use { client ->
          client.initialize()
          client.open(uri, "d0.\n")

          val position =
              """{"textDocument": {"uri": "$uri"}, "position": {"line": 0, "character": 3}}"""
          for (id in 100 until 120) {
            client.send(
                """{"jsonrpc": "2.0", "id": $id, "method": "textDocument/completion", "params": $position}"""
            )
          }
          val started = System.nanoTime()
          client.send(
              """{"jsonrpc": "2.0", "id": 200, "method": "ktrapic/compile", "params": {"textDocument": {"uri": "$uri"}}}"""
          )
          val answered = generateSequence { client.read() }.first { it["id"]?.asInt == 200 }
          val waitedMillis = (System.nanoTime() - started) / 1_000_000

          assertThat(answered.has("result")).isTrue()
          assertThat(calls.get()).isEqualTo(1)
          // Twenty analyses would take four seconds.
          assertThat(waitedMillis).isLessThan(4_000)
        }
  }

  @Test
  fun `completion of a document that isn't open is an error`() {
    LspTestClient().use { client ->
      client.initialize()

      assertThat(client.complete(0, 0)["error"].asJsonObject["code"].asInt).isEqualTo(-32602)
    }
  }
}
