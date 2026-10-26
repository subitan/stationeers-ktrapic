package ktrapic.datagen

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PrefabGeneratorTest {
  private fun hash(name: String) = PyTrapicStructures.gameHash(name)

  private val structures =
      """
      |class _On:
      |    @property
      |    def On(self) -> float:
      |        return _DeviceLogicType(self, _LT.On)
      |
      |    @On.setter
      |    def On(self, value: int | float):
      |        pass
      |
      |
      |class _Ons:
      |    @property
      |    def On(self) -> _DevicesLogicType:
      |        return _DevicesLogicType(self, _LT.On)
      |
      |
      |class _Charge:
      |    @property
      |    def Charge(self) -> float:
      |        return _DeviceLogicType(self, _LT.Charge)
      |
      |
      |class GrowLight(_BaseStructure, _On):
      |    _hash: int = ${hash("StructureGrowLight")}
      |    _prefab_name: int = "StructureGrowLight"
      |
      |    @property
      |    def slot0(self) -> _SlotTypePlant:
      |        return _SlotTypePlant(self, 0)
      |
      |
      |class _GrowLights(_BaseStructures, _Ons):
      |    _hash: int = ${hash("StructureGrowLight")}
      |    _prefab_name: int = "StructureGrowLight"
      |
      |
      |class BatterySmall(
      |    _BaseStructure,
      |    _Charge,
      |    _On,
      |):
      |    _hash: int = ${hash("StructureBatterySmall")}
      |    _prefab_name: int = "StructureBatterySmall"
      |
      |
      |class BatteryLarge(_BaseStructure, _Charge):
      |    _hash: int = ${hash("StructureBatteryLarge")}
      |    _prefab_name: int = "StructureBatteryLarge"
      |
      |    @property
      |    def Mode(self) -> float:
      |        return _DeviceLogicType(self, _LT.Mode)
      |
      |    @Mode.setter
      |    def Mode(self, value: int | float):
      |        pass
      |
      |
      |BatteryLarges: _BatteryLarges = _BatteryLarges()
      """
          .trimMargin()

  private val prefabs = PyTrapicStructures.prefabs(structures, "test")

  private val grouping =
      Grouping(
          mapOf(
              "Battery" to
                  Grouping.Family(
                      "Batteries",
                      mapOf("Small" to "StructureBatterySmall", "Large" to "StructureBatteryLarge"),
                  )
          ),
          emptyList(),
      )

  private fun generate(grouping: Grouping = this.grouping) =
      PrefabGenerator.generate(prefabs, grouping, setOf("LogicType"), setOf("On")).second

  @Test
  fun `imports prefabs with their own and inherited logic types`() {
    assertThat(prefabs.prefabs.map { it.name })
        .containsExactly("StructureBatteryLarge", "StructureBatterySmall", "StructureGrowLight")
        .inOrder()
    val byName = prefabs.prefabs.associateBy { it.name }
    assertThat(byName.getValue("StructureGrowLight").logic)
        .containsExactly("On", Access(read = true, write = true))
    assertThat(byName.getValue("StructureBatterySmall").logic)
        .containsExactly("Charge", Access(true, false), "On", Access(true, true))
    assertThat(byName.getValue("StructureBatteryLarge").logic)
        .containsExactly("Charge", Access(true, false), "Mode", Access(true, true))
  }

  @Test
  fun `rejects a hash that is not the game's hash of the prefab name`() {
    val bad = structures.replace("${hash("StructureGrowLight")}", "1")
    assertThrows<IllegalArgumentException> { PyTrapicStructures.prefabs(bad, "test") }
  }

  @Test
  fun `json round-trips`() {
    assertThat(Prefabs.fromJson(prefabs.toJson())).isEqualTo(prefabs)
  }

  @Test
  fun `a flat prefab gets a device class and an entry point`() {
    val code = generate()

    assertThat(code).contains("@PrefabName(\"StructureGrowLight\")\nclass GrowLight internal")
    assertThat(code).contains("  var On: Boolean\n    get() = intrinsic()\n")
    assertThat(code).contains("  companion object : Prefab()\n")
    assertThat(code).contains("@PrefabName(\"StructureGrowLight\")\nobject GrowLights {")
    // A batch only writes; reading goes through a batch method, all as Double.
    assertThat(code)
        .contains(
            "    var On: Boolean\n" +
                "      @Deprecated(PICK_A_BATCH_METHOD, level = DeprecationLevel.ERROR) " +
                "get() = intrinsic()\n"
        )
    assertThat(code).contains("    val average: Reading\n")
    assertThat(code)
        .contains("  class Reading internal constructor() : BatchReading() {\n    val On: Double\n")
  }

  @Test
  fun `a family nests its members and declares what they can all read`() {
    val code = generate()

    assertThat(code).contains("interface Battery {\n  val Charge: Double\n\n  /**")
    assertThat(code).contains("  class Small internal constructor() : TypedDevice(), Battery {")
    assertThat(code).contains("    override val Charge: Double\n")
    assertThat(code).contains("object Batteries {")
    assertThat(code)
        .contains("  object Small {\n    /** The device on [pin], as a StructureBatterySmall. */")
    assertThat(code).contains("    fun at(pin: Device): Battery.Small = intrinsic()")
    assertThat(code).doesNotContain("class BatterySmall")
  }

  @Test
  fun `rejects groupings that name missing prefabs or clash`() {
    for (bad in
        listOf(
            Grouping(mapOf("X" to Grouping.Family("Xs", mapOf("A" to "Missing"))), emptyList()),
            Grouping(mapOf("LogicType" to Grouping.Family("Ls", emptyMap())), emptyList()),
            Grouping(emptyMap(), listOf("StructureGrowLight")),
        )) {
      assertThrows<IllegalArgumentException>(bad.toString()) { generate(bad) }
    }
  }

  @Test
  fun `plurals`() {
    assertThat(PrefabGenerator.plural("Battery")).isEqualTo("Batteries")
    assertThat(PrefabGenerator.plural("Relay")).isEqualTo("Relays")
    assertThat(PrefabGenerator.plural("Glass")).isEqualTo("Glasses")
    assertThat(PrefabGenerator.plural("GrowLight")).isEqualTo("GrowLights")
  }
}
