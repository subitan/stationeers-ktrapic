package ktrapic.datagen

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.jupiter.api.Test

class FamilyCandidatesTest {
  private val workspace = File(System.getenv("TEST_SRCDIR"), System.getenv("TEST_WORKSPACE"))

  @Test
  fun `every look-alike group of prefabs is a family, or listed as not one`() {
    val prefabs = Prefabs.fromJson(DataFiles.prefabs(workspace).readText()).prefabs.map { it.name }
    val grouping = Grouping.fromJson(DataFiles.grouping(workspace).readText())

    assertWithMessage(
            "prefabs that look like one device: add each group to grouping.json as a family, " +
                "or to notFamilies with the reason"
        )
        .that(FamilyCandidates.ungrouped(prefabs, grouping))
        .isEmpty()
  }

  @Test
  fun `a base name drops prefixes, sizes, fluids and variants`() {
    assertThat(FamilyCandidates.baseName("StructureLiquidValve")).isEqualTo("Valve")
    assertThat(FamilyCandidates.baseName("StructureTransformerMedium(Reversed)"))
        .isEqualTo("Transformer")
    assertThat(FamilyCandidates.baseName("StructureSuperLargeDirectHeatExchangeGastoLiquid"))
        .isEqualTo("DirectHeatExchange")
    assertThat(FamilyCandidates.baseName("Landingpad_GasTankConnectorPiece"))
        .isEqualTo("TankConnectorPiece")
    // A word inside another isn't a variant: Gasket isn't Gas + ket.
    assertThat(FamilyCandidates.baseName("StructureGasket")).isEqualTo("Gasket")
  }

  @Test
  fun `a group split across families, or partly outside one, is still a candidate`() {
    val grouping =
        Grouping(
            mapOf("Valve" to Grouping.Family("Valves", mapOf("Gas" to "StructureValve"))),
            emptyList(),
        )

    assertThat(
            FamilyCandidates.ungrouped(listOf("StructureValve", "StructureLiquidValve"), grouping)
        )
        .containsExactly("Valve", listOf("StructureValve", "StructureLiquidValve"))
  }
}
