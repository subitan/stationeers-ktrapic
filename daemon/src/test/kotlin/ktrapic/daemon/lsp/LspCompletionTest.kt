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
             "text": "PresenceSensors."}]}""",
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
  fun `completion of a document that isn't open is an error`() {
    LspTestClient().use { client ->
      client.initialize()

      assertThat(client.complete(0, 0)["error"].asJsonObject["code"].asInt).isEqualTo(-32602)
    }
  }
}
