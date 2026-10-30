package ktrapic.api

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.jupiter.api.Test

/**
 * Compiles each `test/cases/X.ic10.kts` and compares the result with `X.ic10` and `X.compact.ic10`.
 * A case whose code calls functions with `jal` is also compiled with `stackCalls = false`, to
 * `X.registers.ic10`, so the convention passing arguments in registers stays covered, unless it is
 * recursive, which that convention rejects.
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
      val variants =
          listOf(".ic10" to CompileOptions(), ".compact.ic10" to CompileOptions(compact = true))
      val outputs = variants.map { (suffix, options) -> suffix to compile(source, options) }
      val registers =
          if (outputs[0].second.lines().any { it.startsWith("jal ") }) {
            listOf(".registers.ic10" to compile(source, CompileOptions(stackCalls = false)))
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

  private fun compile(source: File, options: CompileOptions): String =
      when (val result = Compiler.compile(source.readText(), options)) {
        is CompileResult.Success -> result.code + "\n"
        is CompileResult.Failure -> result.errors.joinToString("\n") { "error: $it" } + "\n"
      }
}
