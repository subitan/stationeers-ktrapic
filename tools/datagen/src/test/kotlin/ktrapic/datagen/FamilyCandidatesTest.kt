package ktrapic.datagen

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.jupiter.api.Test

class FamilyCandidatesTest {
  private val workspace = File(System.getenv("TEST_SRCDIR"), System.getenv("TEST_WORKSPACE"))

  private fun prefab(name: String, vararg logic: String) =
      Prefab(name, 0, logic.associateWith { Access.parse("r") })

  @Test
  fun `every look-alike group of prefabs is a family, or listed as not one`() {
    val prefabs = Prefabs.fromJson(DataFiles.prefabs(workspace).readText()).prefabs
    val grouping = Grouping.fromJson(DataFiles.grouping(workspace).readText())

    assertWithMessage(
            "prefabs that look like one device: add each group to grouping.json as a family, " +
                "or to notFamilies with the reason"
        )
        .that(FamilyCandidates.ungrouped(prefabs, grouping))
        .isEmpty()
    assertWithMessage("notFamilies names prefabs the game doesn't have")
        .that(grouping.notFamilies.flatMap { it.prefabs } - prefabs.map { it.name }.toSet())
        .isEmpty()
    assertWithMessage("notFamilies groups prefabs together that aren't one family")
        .that(FamilyCandidates.groupsOutsideAFamily(grouping))
        .isEmpty()
    assertWithMessage("notFamilies puts members of one family in different groups")
        .that(FamilyCandidates.familiesSplitByAnEntry(grouping))
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
    assertThat(FamilyCandidates.baseName("Battery_Wireless_cell_Big"))
        .isEqualTo("BatteryWirelessCell")
    assertThat(FamilyCandidates.baseName("StructureChuteDigitalValveLeft"))
        .isEqualTo("ChuteDigitalValve")
    // A word inside another isn't a variant: Gasket isn't Gas + ket, Tomato isn't Toma + to.
    assertThat(FamilyCandidates.baseName("StructureGasket")).isEqualTo("Gasket")
    assertThat(FamilyCandidates.baseName("ItemTomato")).isEqualTo("Tomato")
    assertThat(FamilyCandidates.baseName("StructureAutoMinerSmall")).isEqualTo("AutoMiner")
  }

  @Test
  fun `prefabs with the same logic look alike when one name has every word of the other`() {
    val long = prefab("StructureLightLong", "On", "Power")
    val wide = prefab("StructureLightLongWide", "On", "Power")
    val floodLight = prefab("StructureFloodLight", "On", "Power")
    val otherLogic = prefab("StructureLightLongTall", "On")

    assertThat(FamilyCandidates.lookAlike(long, wide)).isTrue()
    assertThat(FamilyCandidates.lookAlike(long, floodLight)).isFalse()
    assertThat(FamilyCandidates.lookAlike(long, otherLogic)).isFalse()
  }

  @Test
  fun `prefabs with the same kinds of slot look alike however many slots they have`() {
    fun locker(name: String, slots: Int, kind: String = "DataDisk") =
        Prefab(
            name,
            0,
            mapOf("Open" to Access.parse("rw")),
            (0 until slots).associateWith {
              SlotInfo.parse(kind)
            },
        )

    assertThat(
            FamilyCandidates.lookAlike(
                locker("StructureStorageLocker", 30),
                locker("StructureLockerSmall", 4),
            )
        )
        .isTrue()
    assertThat(
            FamilyCandidates.lookAlike(
                locker("StructureStorageLocker", 30),
                locker("StructureLockerSmall", 4, "Battery"),
            )
        )
        .isFalse()
  }

  @Test
  fun `prefabs with the same rare logic types look alike, whatever their names`() {
    // Twenty prefabs, so a logic type on two of them is on a tenth: rare.
    val others = ('A'..'R').map { prefab("StructureThing$it", "On") }
    val gas = prefab("StructureGasGenerator", "On", "PowerGeneration")
    val wind = prefab("StructureWindTurbine", "PowerGeneration")
    val prefabs = others + gas + wind

    assertThat(FamilyCandidates.rareLogicTypes(prefabs)).containsExactly("PowerGeneration")
    assertThat(FamilyCandidates.ungrouped(prefabs, Grouping(emptyMap(), emptyList())))
        .containsExactly(listOf("StructureGasGenerator", "StructureWindTurbine"))
  }

  @Test
  fun `prefabs with different rare logic types, or none, don't look alike by them`() {
    val rare = setOf("PowerGeneration", "Output", "RatioOxygen")

    assertThat(
            FamilyCandidates.lookAlike(
                prefab("StructureGasGenerator", "PowerGeneration"),
                prefab("StructureSorter", "Output"),
                rare,
            )
        )
        .isFalse()
    assertThat(
            FamilyCandidates.lookAlike(
                prefab("StructureGasGenerator", "PowerGeneration"),
                prefab("StructureWindTurbine", "PowerGeneration", "Output"),
                rare,
            )
        )
        .isFalse()
    assertThat(
            FamilyCandidates.lookAlike(
                prefab("StructureVent", "On"),
                prefab("StructureFan", "On"),
                rare,
            )
        )
        .isFalse()
  }

  @Test
  fun `a family and one that extends it are decided`() {
    // Shafts and levels share ElevatorSpeed, which the other eighteen prefabs lack.
    val others = ('A'..'R').map { prefab("StructureThing$it", "On") }
    val shaft = prefab("StructureElevatorShaft", "ElevatorSpeed")
    val level = prefab("StructureElevatorLevelFront", "ElevatorSpeed")
    val shafts = Grouping.Family("ElevatorShafts", mapOf("Cabled" to "StructureElevatorShaft"))
    val levels = Grouping.Family("ElevatorLevels", mapOf("Cabled" to "StructureElevatorLevelFront"))

    assertThat(
            FamilyCandidates.ungrouped(
                others + shaft + level,
                Grouping(
                    mapOf(
                        "ElevatorShaft" to shafts,
                        "ElevatorLevel" to levels.copy(extends = listOf("ElevatorShaft")),
                    ),
                    emptyList(),
                ),
            )
        )
        .isEmpty()
    assertThat(
            FamilyCandidates.ungrouped(
                others + shaft + level,
                Grouping(mapOf("ElevatorShaft" to shafts, "ElevatorLevel" to levels), emptyList()),
            )
        )
        .containsExactly(listOf("StructureElevatorLevelFront", "StructureElevatorShaft"))
  }

  @Test
  fun `gas ratios are never rare`() {
    val prefabs =
        ('A'..'T').map { prefab("StructureThing$it", "On") } +
            prefab("StructureGasSensor", "RatioOxygen")

    assertThat(FamilyCandidates.rareLogicTypes(prefabs)).isEmpty()
  }

  private val valves = listOf(prefab("StructureValve"), prefab("StructureLiquidValve"))
  private val gasValves = Grouping.Family("Valves", mapOf("Gas" to "StructureValve"))

  @Test
  fun `a group split across families is still a candidate`() {
    val grouping =
        Grouping(
            mapOf(
                "Valve" to gasValves,
                "LiquidValve" to
                    Grouping.Family("LiquidValves", mapOf("Liquid" to "StructureLiquidValve")),
            ),
            emptyList(),
        )

    assertThat(FamilyCandidates.ungrouped(valves, grouping))
        .containsExactly(listOf("StructureLiquidValve", "StructureValve"))
  }

  @Test
  fun `a group partly outside a family is still a candidate`() {
    val grouping = Grouping(mapOf("Valve" to gasValves), emptyList())

    assertThat(FamilyCandidates.ungrouped(valves, grouping))
        .containsExactly(listOf("StructureLiquidValve", "StructureValve"))
  }

  @Test
  fun `a not-family entry decides only prefabs in different groups of it`() {
    val valvesTogether =
        Grouping(
            emptyMap(),
            emptyList(),
            listOf(
                Grouping.NotFamily(
                    listOf(listOf("StructureValve", "StructureLiquidValve")),
                    "different",
                )
            ),
        )
    val valvesApart =
        Grouping(
            emptyMap(),
            emptyList(),
            listOf(
                Grouping.NotFamily(
                    listOf(listOf("StructureValve"), listOf("StructureLiquidValve")),
                    "different",
                )
            ),
        )

    assertThat(FamilyCandidates.ungrouped(valves, valvesTogether))
        .containsExactly(listOf("StructureLiquidValve", "StructureValve"))
    assertThat(FamilyCandidates.groupsOutsideAFamily(valvesTogether))
        .containsExactly(listOf("StructureValve", "StructureLiquidValve"))
    assertThat(FamilyCandidates.ungrouped(valves, valvesApart)).isEmpty()
  }

  @Test
  fun `a not-family entry naming a family covers the families that extend it`() {
    // The doors and the roll cover share Idle, which the other thirty-seven prefabs lack.
    val others =
        ('A'..'Z').map { prefab("StructureThing$it", "On") } +
            ('A'..'K').map { prefab("StructureOther$it", "On") }
    val doors =
        listOf(
            prefab("StructureGlassDoor", "Idle"),
            prefab("StructureInteriorDoorPadded", "Idle"),
            prefab("CompositeRollCover", "Idle"),
        )
    val grouping =
        Grouping(
            mapOf(
                "Door" to Grouping.Family("Doors", mapOf("Glass" to "StructureGlassDoor")),
                "InteriorDoor" to
                    Grouping.Family(
                        "InteriorDoors",
                        mapOf("Padded" to "StructureInteriorDoorPadded"),
                        extends = listOf("Door"),
                    ),
            ),
            emptyList(),
            listOf(
                Grouping.NotFamily(
                    listOf(listOf("StructureGlassDoor"), listOf("CompositeRollCover")),
                    "no power",
                )
            ),
        )

    assertThat(FamilyCandidates.ungrouped(others + doors, grouping)).isEmpty()
    assertThat(
            FamilyCandidates.ungrouped(
                others + doors,
                grouping.copy(families = grouping.families - "InteriorDoor"),
            )
        )
        .containsExactly(
            listOf("CompositeRollCover", "StructureGlassDoor", "StructureInteriorDoorPadded")
        )
  }

  @Test
  fun `a not-family entry can't put one family's members in different groups`() {
    val valveFamily =
        Grouping.Family(
            "Valves",
            mapOf("Gas" to "StructureValve", "Liquid" to "StructureLiquidValve"),
        )
    fun grouping(vararg groups: List<String>) =
        Grouping(
            mapOf("Valve" to valveFamily),
            emptyList(),
            listOf(Grouping.NotFamily(groups.toList(), "different")),
        )

    assertThat(
            FamilyCandidates.familiesSplitByAnEntry(
                grouping(listOf("StructureValve"), listOf("StructureLiquidValve"))
            )
        )
        .containsExactly("Valve" to "different")
    assertThat(
            FamilyCandidates.familiesSplitByAnEntry(
                grouping(
                    listOf("StructureValve", "StructureLiquidValve"),
                    listOf("StructureGasket"),
                )
            )
        )
        .isEmpty()
  }

  @Test
  fun `a not-family entry covers only the prefabs it lists`() {
    val switches = listOf("StructureLogicSwitch", "StructureLogicSwitch2")
    val grouping =
        Grouping(
            emptyMap(),
            emptyList(),
            listOf(Grouping.NotFamily(switches.map { listOf(it) }, "different")),
        )

    assertThat(FamilyCandidates.ungrouped(switches.map { prefab(it) }, grouping)).isEmpty()
    assertThat(
            FamilyCandidates.ungrouped(
                (switches + "StructureLogicSwitch3").map { prefab(it) },
                grouping,
            )
        )
        .containsExactly(switches + "StructureLogicSwitch3")
  }
}
