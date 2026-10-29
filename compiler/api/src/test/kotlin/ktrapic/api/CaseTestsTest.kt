package ktrapic.api

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import ktrapic.harness.Harness
import ktrapic.harness.TestSpec
import ktrapic.ic10.Program
import org.junit.jupiter.api.Test

/** Runs each `test/cases/X.test.json` against `X.ic10.kts`, compiled in normal and compact form. */
class CaseTestsTest {
  private val cases =
      File(System.getenv("TEST_SRCDIR"), System.getenv("TEST_WORKSPACE") + "/test/cases")

  @Test
  fun `each case's test passes`() {
    val tests = cases.listFiles { file -> file.name.endsWith(".test.json") }.orEmpty().sorted()
    assertThat(tests).isNotEmpty()
    for (test in tests) {
      val name = test.name.removeSuffix(".test.json")
      val source = cases.resolve("$name.ic10.kts")
      assertWithMessage("%s has no %s", test.name, source.name).that(source.isFile).isTrue()
      val spec = TestSpec.parse(test.readText())
      for (compact in listOf(false, true)) {
        val result = Compiler.compile(source.readText(), CompileOptions(compact = compact))
        if (result is CompileResult.Failure) throw AssertionError("$name: ${result.errors}")
        val run = Harness.run(Program.parse((result as CompileResult.Success).code), spec)
        assertWithMessage("%s (compact=%s): runtime error", name, compact).that(run.error).isNull()
        assertWithMessage("%s (compact=%s): failed checks", name, compact)
            .that(run.checks.filter { !it.passed }.map { it.description })
            .isEmpty()
      }
    }
  }
}
