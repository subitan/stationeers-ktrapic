package ktrapic.api

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.jupiter.api.Test

/**
 * Compiles each `test/cases/X.ic10.kts` and compares the result with `X.ic10` and `X.compact.ic10`.
 * A case whose code calls functions with `jal` is also compiled with `stackCalls = false`, to
 * `X.registers.ic10`, so the convention passing arguments in registers stays covered, unless it is
 * recursive, which that convention rejects. `X.ic10` is compiled with any `@file:Compact` in the
 * case's header blanked out, so that the labelled form of a compact case is still checked; the
 * other two keep it. Only `@file:Compact` is blanked: it is the one annotation a case needs to fit
 * the chip, and the labelled form is what `X.ic10` exists to show, while a case's other file
 * annotations are part of how it is meant to compile.
 *
 * To write the expected files from the current compiler, run with the workspace's `test/cases`
 * directory in KTRAPIC_UPDATE_GOLDENS: `bazel test //compiler/api:GoldenTest
 * --test_env=KTRAPIC_UPDATE_GOLDENS=$PWD/test/cases`
 */
class GoldenTest {
  private val cases =
      File(System.getenv("TEST_SRCDIR"), System.getenv("TEST_WORKSPACE") + "/test/cases")
  private val update = System.getenv("KTRAPIC_UPDATE_GOLDENS")?.let { File(it) }

  @Test
  fun `each case compiles to its expected IC10`() {
    val sources = cases.listFiles { file -> file.name.endsWith(".ic10.kts") }.orEmpty().sorted()
    assertThat(sources).isNotEmpty()
    for (source in sources) {
      val name = source.name.removeSuffix(".ic10.kts")
      val text = source.readText()
      val outputs =
          listOf(
              ".ic10" to compile(withoutCompact(text), CompileOptions()),
              ".compact.ic10" to compile(text, CompileOptions(compact = true)),
          )
      val registers =
          if (outputs[0].second.lines().any { it.startsWith("jal ") }) {
            listOf(".registers.ic10" to compile(text, CompileOptions(stackCalls = false)))
                .filterNot { (_, code) -> code.startsWith("error: ") && "Recursive calls" in code }
          } else {
            emptyList()
          }
      for ((suffix, actual) in outputs + registers) {
        if (update != null) {
          update.resolve(name + suffix).writeText(actual)
          continue
        }
        val expected = cases.resolve(name + suffix)
        assertWithMessage("%s%s is missing", name, suffix).that(expected.exists()).isTrue()
        assertWithMessage("%s%s", name, suffix)
            .that(actual)
            .isEqualTo(expected.readText().replace("\r\n", "\n"))
      }
      if (update == null && registers.isEmpty()) {
        assertWithMessage(
                "%s.registers.ic10 is stale: the case calls nothing with jal, or is recursive",
                name,
            )
            .that(cases.resolve("$name.registers.ic10").exists())
            .isFalse()
      }
    }
  }

  @Test
  fun `each case compiles without warnings`() {
    val sources = cases.listFiles { file -> file.name.endsWith(".ic10.kts") }.orEmpty().sorted()
    assertThat(sources).isNotEmpty()
    for (source in sources) {
      val result = Compiler.compile(source.readText())
      assertWithMessage(source.name).that(result).isInstanceOf(CompileResult.Success::class.java)
      assertWithMessage(source.name).that((result as CompileResult.Success).warnings).isEmpty()
    }
  }

  private fun compile(source: String, options: CompileOptions): String =
      when (val result = Compiler.compile(source, options)) {
        is CompileResult.Success -> result.code + "\n"
        is CompileResult.Failure -> result.errors.joinToString("\n") { "error: $it" } + "\n"
      }

  private companion object {
    /**
     * [text] with each `@file:Compact` in its header (everything before its first `package` or
     * `import`, where file annotations go) blanked out: the annotation alone is removed, leaving
     * the rest of its line, such as a trailing comment or another annotation, and every line where
     * it was, so the source's line numbers stay as they were.
     */
    fun withoutCompact(text: String): String {
      val end = HEADER_END.find(text)?.range?.first ?: text.length
      return COMPACT.replace(text.substring(0, end), "") + text.substring(end)
    }

    val HEADER_END = Regex("^[ \\t]*(package|import)\\b", RegexOption.MULTILINE)
    val COMPACT = Regex("@file:(ktrapic\\.)?Compact\\b(\\(\\))?")
  }
}
