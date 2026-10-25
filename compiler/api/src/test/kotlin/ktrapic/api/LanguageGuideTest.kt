package ktrapic.api

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.jupiter.api.Test

/** Compiles every ```kotlin example in docs/LANGUAGE.md, so the guide stays true. */
class LanguageGuideTest {
  private val guide =
      File(System.getenv("TEST_SRCDIR"), System.getenv("TEST_WORKSPACE") + "/docs/LANGUAGE.md")
          .readText()
          .replace("\r\n", "\n")

  @Test
  fun `every example in the language guide compiles`() {
    val examples =
        Regex("""```kotlin\n(.*?)```""", RegexOption.DOT_MATCHES_ALL)
            .findAll(guide)
            .map { it.groupValues[1] }
            .toList()
    assertThat(examples).isNotEmpty()
    for (example in examples) {
      val result = Compiler.compile(example)
      assertWithMessage(example).that(result).isInstanceOf(CompileResult.Success::class.java)
    }
  }
}
