package ktrapic.api

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.jupiter.api.Test

/**
 * Compiles each `test/cases/X.ic10.kts` and compares the result with `X.ic10` and `X.compact.ic10`.
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
      for ((suffix, compact) in listOf(".ic10" to false, ".compact.ic10" to true)) {
        val result = Compiler.compile(source.readText(), CompileOptions(compact = compact))
        val actual =
            when (result) {
              is CompileResult.Success -> result.code + "\n"
              is CompileResult.Failure -> result.errors.joinToString("\n") { "error: $it" } + "\n"
            }
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
    }
  }
}
