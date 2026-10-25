package ktrapic.datagen

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RuntimeGeneratorTest {
  private val enums =
      PyTrapicImport.enums(
          """
          |from enum import IntEnum as _IntEnum
          |
          |
          |class LogicType(_IntEnum):
          |    None_ = 0
          |    On = 28
          |    Setting = 12
          |
          |
          |class Color(_IntEnum):
          |    Blue = 0
          |    Red = 4
          |
          |
          |class _GenericStructure:
          |    Ignored = 1
          """
              .trimMargin(),
          "test",
      )

  @Test
  fun `imports enums in order and drops the keyword suffix`() {
    assertThat(enums.enums.keys).containsExactly("LogicType", "Color").inOrder()
    assertThat(enums.enums["LogicType"])
        .containsExactly("None", 0, "On", 28, "Setting", 12)
        .inOrder()
  }

  @Test
  fun `rejects enum contents it does not recognize`() {
    for (bad in
        listOf(
            "class A(_IntEnum):\n    X = 0\n    # a comment\n    Y = 1\n",
            "class A(_IntEnum):  # a comment\n    X = 0\n",
            "class A(_IntEnum):\n    \"\"\"doc\"\"\"\n    X = 0\n",
            "class A(_IntEnum):\n\nclass B(_IntEnum):\n    X = 0\n",
        )) {
      assertThrows<IllegalArgumentException>(bad) { PyTrapicImport.enums(bad, "test") }
    }
  }

  @Test
  fun `json round-trips`() {
    assertThat(LogicEnums.fromJson(enums.toJson())).isEqualTo(enums)
  }

  @Test
  fun `operand enums become enum classes and the rest Double constants`() {
    val files = RuntimeGenerator.generate(enums, Typing(listOf("LogicType"), listOf("On")))

    val generatedEnums = files.getValue("GeneratedEnums.kt")
    assertThat(generatedEnums).contains("enum class LogicType(val value: Int) {\n  None(0),\n")
    assertThat(generatedEnums).contains("  const val Red: Double = 4.0\n")
    val device = files.getValue("GenericDevice.kt")
    assertThat(device).contains("  var On: Boolean\n")
    assertThat(device).contains("  var Setting: Double\n")
    assertThat(device).doesNotContain("var None")
  }

  @Test
  fun `rejects typing that names logic types the data lacks`() {
    assertThrows<IllegalArgumentException> {
      RuntimeGenerator.generate(enums, Typing(listOf("LogicType"), listOf("Missing")))
    }
  }
}
