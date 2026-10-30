package ktrapic.api

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.jupiter.api.Test

/**
 * Compiles every example in docs/LANGUAGE.md, so the guide's examples keep compiling. Its prose
 * claims are not checked here.
 */
class LanguageGuideTest {
  private val guide =
      File(System.getenv("TEST_SRCDIR"), System.getenv("TEST_WORKSPACE") + "/docs/LANGUAGE.md")
          .readText()
          .replace("\r\n", "\n")

  @Test
  fun `every code block in the language guide is a kotlin example`() {
    // Fences alternate opening and closing; an opening fence spelled otherwise (```kt, ~~~ or an
    // indented one) would be skipped by the test below.
    val openingFences =
        guide
            .lines()
            .filter { it.trimStart().startsWith("```") || it.trimStart().startsWith("~~~") }
            .filterIndexed { i, _ -> i % 2 == 0 }

    assertThat(openingFences).isNotEmpty()
    assertThat(openingFences.distinct()).containsExactly("```kotlin")
  }

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
      // No unused variable or the like: warnings about the program as a whole, such as one over
      // the chip's 128 lines, have no position and are allowed.
      assertWithMessage(example)
          .that((result as CompileResult.Success).warnings.filter { it.line > 0 })
          .isEmpty()
    }
  }
}
