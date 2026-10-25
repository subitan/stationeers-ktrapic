package ktrapic.frontend

import com.google.common.truth.Truth.assertThat
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.psi.KtFile
import org.junit.jupiter.api.Test

class ScriptWrapperTest {
  private fun <T> withScript(source: String, use: (KtFile) -> T): T {
    val disposable = Disposer.newDisposable("ScriptWrapperTest")
    try {
      return use(K2Frontend.parseScript(source, disposable))
    } finally {
      Disposer.dispose(disposable)
    }
  }

  @Test
  fun `declarations stay members and statements become init blocks`() {
    val source =
        """
        import kotlin.math.max
        val limit = 10
        fun twice(x: Int) = x * 2
        println(twice(limit))
        var best = 0
        best = max(best, limit)
        """
            .trimIndent()

    val wrapped = withScript(source) { ScriptWrapper.wrap(it) }

    assertThat(wrapped.text)
        .isEqualTo(
            """
            import kotlin.math.max
            object Program { val limit = 10
            fun twice(x: Int) = x * 2
            init { println(twice(limit)) }
            var best = 0
            init { best = max(best, limit) }
            }

            """
                .trimIndent()
        )
  }

  @Test
  fun `default imports go before the first import, moving no line`() {
    val wrapped =
        withScript("import kotlin.math.max\nval a = max(1, 2)\n") {
          ScriptWrapper.wrap(it, listOf("ktrapic.*"))
        }

    assertThat(wrapped.text)
        .isEqualTo(
            "import ktrapic.*; import kotlin.math.max\nobject Program { val a = max(1, 2)\n\n}\n"
        )
    // Column 22 of the generated first line is the user's column 4 ("ort kotlin...").
    assertThat(wrapped.originalPosition(1, 22)).isEqualTo(1 to 4)
    assertThat(wrapped.originalPosition(2, 18)).isEqualTo(2 to 1)
  }

  @Test
  fun `default imports follow a shebang, file annotations and a package line`() {
    val withImport =
        withScript(
            "#!/usr/bin/env kotlin\n@file:Suppress(\"X\")\npackage p\nimport a.b\nval x = 1\n"
        ) {
          ScriptWrapper.wrap(it, listOf("ktrapic.*"))
        }
    assertThat(withImport.text)
        .isEqualTo(
            "#!/usr/bin/env kotlin\n@file:Suppress(\"X\")\npackage p\n" +
                "import ktrapic.*; import a.b\nobject Program { val x = 1\n\n}\n"
        )

    val withoutImport =
        withScript("#!/usr/bin/env kotlin\npackage p\n\nval x = 1\n") {
          ScriptWrapper.wrap(it, listOf("ktrapic.*"))
        }
    assertThat(withoutImport.text)
        .isEqualTo(
            "#!/usr/bin/env kotlin\npackage p\n\nimport ktrapic.*; object Program { val x = 1\n\n}\n"
        )
    // Column 36 of the generated fourth line is the user's column 1 ("val x").
    assertThat(withoutImport.originalPosition(4, 36)).isEqualTo(4 to 1)
  }

  @Test
  fun `a script with only declarations is still wrapped`() {
    val wrapped = withScript("val a = 1\n") { ScriptWrapper.wrap(it) }

    assertThat(wrapped.text).isEqualTo("object Program { val a = 1\n\n}\n")
  }

  @Test
  fun `a top-level destructuring declaration is reported at its position`() {
    val diagnostics =
        withScript("val a = 1\n  val (b, c) = Pair(a, a)\n") { ScriptWrapper.unsupported(it) }

    assertThat(diagnostics.map { it.line to it.column }).containsExactly(2 to 3)
  }
}
