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
            "class A(IntEnum):\n    X = 0\n\nclass B(_IntEnum):\n    Z = 0\n",
            "class A(_IntEnum, metaclass=M):\n    X = 0\n\nclass B(_IntEnum):\n    Z = 0\n",
        )) {
      assertThrows<IllegalArgumentException>(bad) { PyTrapicImport.enums(bad, "test") }
    }
  }

  @Test
  fun `a comment at column 0 does not end an enum`() {
    val imported = PyTrapicImport.enums("class A(_IntEnum):\n    X = 0\n# c\n    Y = 1\n", "test")
    assertThat(imported.enums["A"]).containsExactly("X", 0, "Y", 1).inOrder()
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

  @Test
  fun `rejects thing slot reads that name slot logic types or slot kinds the data lacks`() {
    val slotEnums =
        PyTrapicImport.enums(
            """
            |class LogicType(_IntEnum):
            |    On = 28
            |
            |
            |class LogicSlotType(_IntEnum):
            |    Charge = 1
            |    Occupied = 2
            """
                .trimMargin(),
            "test",
        )
    val prefabs = Prefabs("test", emptyList(), mapOf("Battery" to listOf("Charge")))
    val grouping = Grouping(emptyMap(), emptyList())
    fun generate(always: List<String>, byKind: Map<String, List<String>>) =
        RuntimeGenerator.generate(
            slotEnums,
            Typing(
                listOf("LogicType"),
                listOf("On"),
                thingSlotReadsAlways = always,
                thingSlotReadsByKind = byKind,
            ),
            prefabs,
            grouping,
        )

    generate(listOf("Occupied"), mapOf("Battery" to listOf("Charge")))
    for ((always, byKind) in
        listOf(
            listOf("Ocupied") to mapOf("Battery" to listOf("Charge")),
            listOf("Occupied") to mapOf("Battery" to listOf("Charj")),
            listOf("Occupied") to mapOf("Batery" to listOf("Charge")),
        )) {
      assertThrows<IllegalArgumentException>("$always $byKind") { generate(always, byKind) }
    }
  }
}
