package ktrapic.daemon.lsp

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonObject
import ktrapic.frontend.Signature
import ktrapic.frontend.SignatureHelp
import org.junit.jupiter.api.Test

class LspHoverTest {
  private val uri = "ktrapic:///1/program.kt"

  private fun LspTestClient.at(method: String, line: Int, character: Int) =
      request(
          method,
          """{"textDocument": {"uri": "$uri"}, "position": {"line": $line, "character": $character}}""",
      )

  @Test
  fun `hover describes a logic type with its KDoc, as plain text`() {
    LspTestClient().use { client ->
      client.initialize()
      client.open(uri, "val sign = LedDisplay.Small.at(d5)\nsign.Text = \"HOT\"\n")

      val contents = client.at("textDocument/hover", 1, 6)["result"].asJsonObject["contents"]
      assertThat(contents.asJsonObject["kind"].asString).isEqualTo("plaintext")
      assertThat(contents.asJsonObject["value"].asString)
          .startsWith("LedDisplay.Small\nvar Text: String (write-only)\n\nText of one to six")
    }
  }

  @Test
  fun `hover where there is nothing to say is null`() {
    LspTestClient().use { client ->
      client.initialize()
      client.open(uri, "val x = 1\n")

      assertThat(client.at("textDocument/hover", 0, 1)["result"].isJsonNull).isTrue()
    }
  }

  @Test
  fun `signature help lists the call's functions, with documentation as MarkupContent`() {
    LspTestClient().use { client ->
      client.initialize()
      client.open(uri, "val s = LedDisplay.Small.at(\n")

      val result = client.at("textDocument/signatureHelp", 0, 29)["result"].asJsonObject
      val signature = result["signatures"].asJsonArray.single().asJsonObject
      assertThat(signature["label"].asString).isEqualTo("fun at(pin: Device): LedDisplay.Small")
      assertThat(signature["documentation"].asJsonObject["kind"].asString).isEqualTo("plaintext")
      assertThat(signature["documentation"].asJsonObject["value"].asString)
          .isEqualTo("The device on pin, as one of this prefab.")
      assertThat(signature["parameters"].asJsonArray.map { it.asJsonObject["label"].asString })
          .containsExactly("pin: Device")
      assertThat(result["activeSignature"].asInt).isEqualTo(0)
      assertThat(result["activeParameter"].asInt).isEqualTo(0)
    }
  }

  @Test
  fun `signature help outside a call is null`() {
    LspTestClient().use { client ->
      client.initialize()
      client.open(uri, "val x = 1\n")

      assertThat(client.at("textDocument/signatureHelp", 0, 9)["result"].isJsonNull).isTrue()
    }
  }

  @Test
  fun `a signature without documentation has none`() {
    val help = SignatureHelp(listOf(Signature("fun f(): Unit", emptyList(), null)), 0)
    LspTestClient(signer = { _, _ -> help }).use { client ->
      client.initialize()
      client.open(uri, "")

      val result = client.at("textDocument/signatureHelp", 0, 0)["result"].asJsonObject
      val signature = result["signatures"].asJsonArray.single().asJsonObject
      assertThat(signature.has("documentation")).isFalse()
      assertThat(signature["parameters"].asJsonArray.size()).isEqualTo(0)
    }
  }

  @Test
  fun `both are given the text and the position's index in it`() {
    val given = mutableListOf<Pair<String, Int>>()
    LspTestClient(
            hoverer = { text, offset -> null.also { given += text to offset } },
            signer = { text, offset -> null.also { given += text to offset } },
        )
        .use { client ->
          client.initialize()
          client.open(uri, "val a = 1\r\nval b = a\r\n")

          client.at("textDocument/hover", 1, 8)
          client.at("textDocument/signatureHelp", 1, 9)

          assertThat(given)
              .containsExactly(
                  "val a = 1\r\nval b = a\r\n" to 19,
                  "val a = 1\r\nval b = a\r\n" to 20,
              )
              .inOrder()
        }
  }

  @Test
  fun `an analysis that fails is answered with InternalError, and the server carries on`() {
    LspTestClient(hoverer = { _, _ -> throw StackOverflowError() }).use { client ->
      client.initialize()
      client.open(uri, "val x = 1\n")

      val error: JsonObject = client.at("textDocument/hover", 0, 4)["error"].asJsonObject
      assertThat(error["code"].asInt).isEqualTo(ErrorCodes.INTERNAL_ERROR)
      assertThat(client.at("textDocument/signatureHelp", 0, 9)["result"].isJsonNull).isTrue()
    }
  }
}
