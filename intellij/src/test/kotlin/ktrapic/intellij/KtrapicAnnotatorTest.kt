package ktrapic.intellij

import com.intellij.codeInsight.daemon.ProblemHighlightFilter
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The annotator in an editor, with a fake compiler in place of the daemon. */
class KtrapicAnnotatorTest : BasePlatformTestCase() {
  private val compiled = mutableListOf<String>()

  override fun setUp() {
    super.setUp()
    // The Kotlin plugin highlights a script only once it has the script's definition, which this
    // light project lacks; the annotator runs in its highlighting pass.
    ExtensionTestUtil.maskExtensions(
        ProblemHighlightFilter.EP_NAME,
        emptyList(),
        testRootDisposable,
    )
    KtrapicCompileService.getInstance(project).compilerForTests = Ic10Compiler { code ->
      synchronized(compiled) { compiled += code }
      CompileResponse.Failure(
          listOf(
              Problem("Top-level destructuring declarations are not supported.", 2, 1),
              Problem("Unresolved reference 'Pair'.", 2, 14, kotlin = true),
              Problem("The program needs 17 registers"),
          )
      )
    }
  }

  override fun tearDown() {
    try {
      KtrapicCompileService.getInstance(project).compilerForTests = null
    } finally {
      super.tearDown()
    }
  }

  fun `test marks KTrapIC's errors at their positions`() {
    myFixture.configureByText("program.ic10.kts", "val a = 1\nval (b, c) = Pair(1, 2)\n")

    val ours =
        myFixture.doHighlighting().filter {
          it.description?.startsWith(ProblemMarks.PREFIX) == true
        }

    val positioned = ours.single { it.description.contains("destructuring") }
    assertEquals(HighlightSeverity.ERROR, positioned.severity)
    assertEquals(
        "KTrapIC: Top-level destructuring declarations are not supported.",
        positioned.description,
    )
    assertEquals("val", myFixture.file.text.substring(positioned.startOffset, positioned.endOffset))
    // The Kotlin compiler's own error is left to the Kotlin plugin.
    assertFalse(ours.any { it.description.contains("Pair") })
    assertEquals(
        listOf("val a = 1\nval (b, c) = Pair(1, 2)\n"),
        synchronized(compiled) { compiled.toList() },
    )
  }

  fun `test a program with only the Kotlin compiler's errors gets one note for the file`() {
    KtrapicCompileService.getInstance(project).compilerForTests = Ic10Compiler { _ ->
      CompileResponse.Failure(
          listOf(
              Problem(
                  "Initializer type mismatch: expected 'Int', actual 'String'.",
                  1,
                  14,
                  kotlin = true,
              )
          )
      )
    }
    myFixture.configureByText("program.ic10.kts", "val x: Int = \"s\"\n")

    val ours =
        myFixture.doHighlighting().filter {
          it.description?.startsWith(ProblemMarks.PREFIX) == true
        }

    val note = ours.single()
    assertEquals(HighlightSeverity.ERROR, note.severity)
    assertEquals(
        "KTrapIC: doesn't compile: Initializer type mismatch: expected 'Int', actual 'String'. " +
            "(line 1)",
        note.description,
    )
    // File-level, not at the error, which the Kotlin plugin marks.
    assertTrue(note.isFileLevelAnnotation)
  }

  fun `test leaves other Kotlin files alone`() {
    myFixture.configureByText("program.kts", "val a = 1\n")

    myFixture.doHighlighting()

    assertTrue(synchronized(compiled) { compiled.isEmpty() })
  }
}
