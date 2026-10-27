package ktrapic.datagen

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class LogicViewsTest {
  private val substances = setOf("Oxygen", "LiquidOxygen")

  @Test
  fun `a family's logic types fall to their view, and plain names that look alike don't`() {
    val views =
        listOf(
                "PressureInput",
                "RatioOxygenOutput2",
                "SettingOutput",
                "RatioLiquidOxygen",
                "PositionX",
                "SizeZ",
                "TargetY",
                "ExportSlotHash",
                // Plain logic types whose names a view's would start or end with.
                "Ratio",
                "Output",
                "Setting",
                "Size",
                "Pressure",
                "Channel0",
                "PlantHealth1",
            )
            .associateWith { LogicViews.viewOf(it, substances) }

    assertThat(views)
        .containsExactly(
            "PressureInput",
            "Ports",
            "RatioOxygenOutput2",
            "Ports",
            "SettingOutput",
            "Ports",
            "RatioLiquidOxygen",
            "Ratios",
            "PositionX",
            "Position",
            "SizeZ",
            "Dimensions",
            "TargetY",
            "Target",
            "ExportSlotHash",
            "Export",
            "Ratio",
            null,
            "Output",
            null,
            "Setting",
            null,
            "Size",
            null,
            "Pressure",
            null,
            "Channel0",
            null,
            "PlantHealth1",
            null,
        )
  }

  @Test
  fun `the substances are the ratios that come for a port too`() {
    assertThat(LogicViews.substances(listOf("Ratio", "RatioOxygen", "RatioOxygenInput", "RatioX")))
        .containsExactly("Oxygen")
  }

  @Test
  fun `a device's views replace its family logic types, each with what it reaches`() {
    val vent =
        Prefab(
            "StructureVent",
            1,
            mapOf(
                "On" to Access(read = true, write = true),
                "PressureInput" to Access(read = true, write = false),
                "SettingOutput" to Access(read = true, write = true),
                "RatioOxygen" to Access(read = true, write = false),
                "PositionX" to Access(read = true, write = false),
            ),
        )
    val source =
        PrefabGenerator.generate(
                Prefabs("test", listOf(vent)),
                Grouping(emptyMap(), emptyList()),
                emptySet(),
                Typing(
                    operandEnums = emptyList(),
                    booleanLogicTypes = listOf("On"),
                    booleanSlotLogicTypes = emptyList(),
                    slotReadsAlways = emptyList(),
                    slotWrites = emptyMap(),
                ),
                substances,
            )
            .second

    assertThat(source)
        .contains(
            "  @LogicTypes(read = \"PressureInput,SettingOutput\", written = \"SettingOutput\")\n" +
                "  val Ports: Ports\n"
        )
    assertThat(source)
        .contains("  @LogicTypes(read = \"RatioOxygen\", written = \"\")\n  val Ratios: Ratios\n")
    assertThat(source)
        .contains("  @LogicTypes(read = \"PositionX\", written = \"\")\n  val Position: Vector\n")
    assertThat(source).contains("var On: Boolean")
    for (flat in listOf("PressureInput", "SettingOutput", "RatioOxygen", "PositionX")) {
      assertThat(source).doesNotContain(" $flat: ")
    }
  }
}
