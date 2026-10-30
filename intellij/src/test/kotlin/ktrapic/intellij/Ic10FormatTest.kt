package ktrapic.intellij

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ic10FormatTest {
  @Test
  fun `a compiled program shows its code, counts and warnings`() {
    val view =
        Ic10Format.view(
            CompileResponse.Success(
                code = "s db Setting 1",
                lines = 1,
                bytes = 14,
                registers = 0,
                warnings =
                    listOf(
                        Problem("Variable is unused.", line = 2, column = 5),
                        Problem(
                            "Assigned value is never read.",
                            line = 3,
                            column = 1,
                            kotlin = true,
                        ),
                        Problem("The program needs 17 registers with tail calls as loops"),
                    ),
            )
        )

    assertEquals("s db Setting 1", view.code)
    assertEquals("1 / 128 lines   14 / 4096 bytes   0 / 16 registers", view.countsText)
    assertFalse(view.over)
    assertEquals(
        listOf(
            "Line 2:5: Variable is unused.",
            "Line 3:1 (Kotlin): Assigned value is never read.",
            "The program needs 17 registers with tail calls as loops",
        ),
        view.warnings,
    )
    assertTrue(view.errors.isEmpty())
  }

  @Test
  fun `counts over the chip's limits are marked`() {
    val view = Ic10Format.view(CompileResponse.Success("", 130, 4097, 16, emptyList()))

    assertTrue(view.over)
    assertEquals(
        "130 / 128 lines (over)   4097 / 4096 bytes (over)   16 / 16 registers",
        view.countsText,
    )
  }

  @Test
  fun `a failure shows its errors and no code`() {
    val view =
        Ic10Format.view(
            CompileResponse.Failure(
                listOf(Problem("Unsupported", line = 4, column = 2), Problem("Internal error"))
            )
        )

    assertEquals("", view.code)
    assertTrue(view.counts.isEmpty())
    assertEquals(listOf("Line 4:2: Unsupported", "Internal error"), view.errors)
  }
}
