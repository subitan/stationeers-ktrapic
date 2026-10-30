package ktrapic.intellij

import org.junit.Assert.assertEquals
import org.junit.Test

class ProblemMarksTest {
  private val text = "val a = 1\nval (b, c) = Pair(1, 2)\nwhile (true) {\n\n}\n"

  @Test
  fun `an error covers the name at its position`() {
    val marks =
        ProblemMarks.of(
            CompileResponse.Failure(listOf(Problem("No Pair here", line = 2, column = 14))),
            text,
            showKotlin = false,
        )

    val mark = marks.single()
    assertEquals(true, mark.error)
    assertEquals("KTrapIC: No Pair here", mark.message)
    assertEquals("Pair", text.substring(mark.start, mark.end))
  }

  @Test
  fun `a mark on punctuation covers one character`() {
    val mark =
        ProblemMarks.of(
                CompileResponse.Failure(listOf(Problem("Unsupported", line = 2, column = 5))),
                text,
                showKotlin = false,
            )
            .single()

    assertEquals("(", text.substring(mark.start, mark.end))
  }

  @Test
  fun `the Kotlin compiler's own problems are left out unless asked for`() {
    val response =
        CompileResponse.Failure(
            listOf(
                Problem("Type mismatch", line = 1, column = 9, kotlin = true),
                Problem("Unsupported", line = 2, column = 1),
            )
        )

    assertEquals(
        listOf("KTrapIC: Unsupported"),
        ProblemMarks.of(response, text, showKotlin = false).map { it.message },
    )
    assertEquals(2, ProblemMarks.of(response, text, showKotlin = true).size)
  }

  @Test
  fun `a failure with only the Kotlin compiler's errors marks them`() {
    val failure =
        CompileResponse.Failure(
            listOf(Problem("'return' is prohibited here.", line = 1, column = 1, kotlin = true))
        )
    val success =
        CompileResponse.Success(
            "s db Setting 1",
            1,
            14,
            0,
            listOf(Problem("Variable is unused.", line = 1, column = 5, kotlin = true)),
        )

    assertEquals(
        listOf("KTrapIC: 'return' is prohibited here."),
        ProblemMarks.of(failure, text, showKotlin = false).map { it.message },
    )
    assertEquals(emptyList<Mark>(), ProblemMarks.of(success, text, showKotlin = false))
  }

  @Test
  fun `warnings are marked as warnings`() {
    val response =
        CompileResponse.Success(
            "s db Setting 1",
            1,
            14,
            0,
            listOf(Problem("Variable is unused.", line = 1, column = 5)),
        )

    val mark = ProblemMarks.of(response, text, showKotlin = false).single()
    assertEquals(false, mark.error)
    assertEquals("a", text.substring(mark.start, mark.end))
  }

  @Test
  fun `a problem with no position is about the whole file`() {
    val response =
        CompileResponse.Failure(listOf(Problem("The program needs 17 registers", line = 0)))

    val mark = ProblemMarks.of(response, text, showKotlin = false).single()
    assertEquals(true, mark.fileLevel)
  }

  @Test
  fun `positions past a line or the text are kept within them`() {
    val response =
        CompileResponse.Failure(
            listOf(
                Problem("At an empty line", line = 4, column = 1),
                Problem("Past the line", line = 1, column = 40),
                Problem("Past the text", line = 99, column = 1),
            )
        )

    val marks = ProblemMarks.of(response, text, showKotlin = false)
    val emptyLine = text.indexOf("\n\n") + 1
    assertEquals(emptyLine to emptyLine, marks[0].start to marks[0].end)
    assertEquals(9 to 9, marks[1].start to marks[1].end)
    assertEquals(text.length to text.length, marks[2].start to marks[2].end)
  }
}
