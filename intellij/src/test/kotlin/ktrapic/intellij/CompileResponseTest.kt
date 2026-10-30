package ktrapic.intellij

import org.junit.Assert.assertEquals
import org.junit.Test

class CompileResponseTest {
  @Test
  fun `reads a success`() {
    val response =
        CompileResponse.parse(
            """{"code": "s db Setting 1", "num_lines": 1, "num_bytes": 14, "num_registers": 0,
               "warnings": [{"description": "Variable is unused.", "line": 2, "column": 5},
                            {"description": "Never read.", "line": 3, "column": 1, "kotlin": true}],
               "source_mapping": {"1": [0]}}"""
        )

    assertEquals(
        CompileResponse.Success(
            "s db Setting 1",
            1,
            14,
            0,
            listOf(
                Problem("Variable is unused.", 2, 5),
                Problem("Never read.", 3, 1, kotlin = true),
            ),
        ),
        response,
    )
  }

  @Test
  fun `reads a failure's errors`() {
    val response =
        CompileResponse.parse(
            """{"error": {"description": "First", "line": 2, "column": 1, "line_end": 2,
               "column_end": 1, "errors": [{"description": "First", "line": 2, "column": 1},
               {"description": "Second", "line": 3, "column": 4, "kotlin": true}]}}"""
        )

    assertEquals(
        CompileResponse.Failure(listOf(Problem("First", 2, 1), Problem("Second", 3, 4, true))),
        response,
    )
  }

  @Test
  fun `a failure without a list of errors is its own error`() {
    val response =
        CompileResponse.parse("""{"error": {"description": "Internal compiler error: boom"}}""")

    assertEquals(CompileResponse.Failure("Internal compiler error: boom"), response)
  }

  @Test
  fun `an unreadable answer is a failure`() {
    val response = CompileResponse.parse("not json {")

    assertEquals(1, (response as CompileResponse.Failure).errors.size)
    assertEquals(false, response.answered)
  }

  @Test
  fun `options are sent as the daemon names them`() {
    assertEquals(
        """{"compact":true,"stack_calls":false,"tail_calls":true}""",
        CompileOptions(compact = true, stackCalls = false).toJson().toString(),
    )
  }
}
