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
      |class _SlotTypeCommon(_BaseSlotType):
      |    @property
      |    def Quantity(self) -> float:
      |        return _DeviceSlotType(self, _LST.Quantity)
      |
      |    @Quantity.setter
      |    def Quantity(self, value: int | float):
      |        pass
      |
      |
      |class _SlotTypeCommons(_BaseSlotTypes):
      |    @property
      |    def Quantity(self) -> _DevicesSlotType:
      |        return _DevicesSlotType(self, _LST.Quantity)
      |
      |
      |class _SlotTypePlant(_SlotTypeCommon):
      |    @property
      |    def Growth(self) -> float:
      |        return _DeviceSlotType(self, _LST.Growth)
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
      |    @property
      |    def Tray(self) -> _SlotTypePlant:
      |        return self.slot0
      |
      |    @property
      |    def slot255(self) -> _SlotTypeCommon:
      |        return _SlotTypeCommon(self, 255)
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
      PrefabGenerator.generate(
              prefabs,
              grouping,
              setOf("LogicType"),
              typing,
          )
          .second

  private val typing =
      Typing(
          operandEnums = emptyList(),
          booleanLogicTypes = listOf("On"),
          booleanSlotLogicTypes = listOf("Occupied"),
          slotReadsAlways = listOf("Occupied"),
          slotWrites = mapOf("Plant" to listOf("Open")),
      )

  @Test
  fun `imports slots by number with their names, and what each kind of slot reads`() {
    val light = prefabs.prefabs.single { it.name == "StructureGrowLight" }

    assertThat(light.slots)
        .containsExactly(0, SlotInfo("Plant", "Tray"), 255, SlotInfo("Common"))
        .inOrder()
    // PyTrapIC's setters on slot logic types are ignored: the game decides what slots write.
    assertThat(prefabs.slotKinds)
        .containsExactly("Common", listOf("Quantity"), "Plant", listOf("Growth", "Quantity"))
  }

  @Test
  fun `slots are typed by kind, named where the game names them, and batched`() {
    val code = generate()

    assertThat(code).contains("class PlantSlot internal constructor() : DeviceSlot() {")
    // Reads come from the data plus the always-readable set; writes only from the game's rule.
    assertThat(code).contains("  val Growth: Double\n")
    assertThat(code).contains("  val Occupied: Boolean\n")
    assertThat(code).contains("  var Open: Double\n    @Deprecated(WRITE_ONLY")
    assertThat(code).contains("  val Quantity: Double\n")
    assertThat(code).contains("class CommonSlot internal constructor() : DeviceSlot() {")
    // The grow light's slots are of two kinds, so slots[n] has what every slot reads.
    assertThat(code).contains("  val slots: Slots<CommonSlot>\n")
    assertThat(code).contains("  @SlotNumber(0)\n  val Tray: PlantSlot\n")
    // A batch of grow lights has its own type, to give it slots.
    assertThat(code).contains("  val all: Batch\n")
    assertThat(code)
        .contains(
            "  class Batch internal constructor() : DeviceBatch<GrowLight>() {\n" +
                "    /** A slot of every one in the batch, by number. */\n" +
                "    val slots: Slots<SlotBatch<CommonSlot>>\n"
        )
  }

  @Test
  fun `a slot name that isn't an identifier, is given twice or clashes is left out`() {
    val tray =
        Prefab(
            "StructureTray",
            hash("StructureTray"),
            mapOf("On" to Access(read = true, write = true)),
            mapOf(
                0 to SlotInfo("Plant", "Tray"),
                1 to SlotInfo("Plant", "Spare"),
                2 to SlotInfo("Plant", "Spare"),
                3 to SlotInfo("Plant", "On"),
                4 to SlotInfo("Plant", "slots"),
                5 to SlotInfo("Plant", "Not a name"),
            ),
        )
    val code =
        PrefabGenerator.generate(
                Prefabs("test", listOf(tray), prefabs.slotKinds),
                Grouping(emptyMap(), emptyList()),
                setOf("LogicType"),
                typing,
            )
            .second

    assertThat(code).contains("  @SlotNumber(0)\n  val Tray: PlantSlot\n")
    assertThat(code).doesNotContain("val Spare")
    assertThat(code).doesNotContain("val On: PlantSlot")
    assertThat(code).doesNotContain("val slots: PlantSlot")
    assertThat(code).doesNotContain("Not a name")
    for (number in 1..5) assertThat(code).doesNotContain("@SlotNumber($number)")
  }

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
    // A batch is a collection of the prefab's typed devices; a prefab without slots needs no
    // batch type of its own.
    assertThat(code).contains("    val all: DeviceBatch<Battery.Small>\n")
    assertThat(code).doesNotContain("Reading")
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
  fun `a family that extends another declares only what it adds or makes writable`() {
    val rw = Access(read = true, write = true)
    val r = Access(read = true, write = false)
    fun prefab(name: String, vararg logic: Pair<String, Access>) =
        Prefab(name, hash(name), mapOf(*logic), emptyMap())
    val prefabs =
        Prefabs(
            "test",
            listOf(
                prefab("StructureShaft", "Level" to rw, "Speed" to r),
                prefab("StructureShaftCabled", "Level" to rw, "Speed" to rw),
                prefab("StructureFloor", "Level" to rw, "Speed" to rw, "Open" to rw),
                prefab("StructureFloorCabled", "Level" to rw, "Speed" to rw, "Open" to rw),
                prefab("StructureLift", "Level" to rw, "Speed" to rw),
                prefab("StructureLiftCabled", "Level" to rw, "Speed" to rw),
            ),
            emptyMap(),
        )
    fun generate(vararg families: Pair<String, Grouping.Family>) =
        PrefabGenerator.generate(
                prefabs,
                Grouping(mapOf(*families), emptyList()),
                setOf("LogicType"),
                typing,
            )
            .second
    val shaft =
        Grouping.Family("Shafts", mapOf("A" to "StructureShaft", "B" to "StructureShaftCabled"))
    val floor =
        Grouping.Family(
            "Floors",
            mapOf("A" to "StructureFloor", "B" to "StructureFloorCabled"),
            extends = listOf("Shaft"),
        )

    val code = generate("Shaft" to shaft, "Floor" to floor)

    // Level is the shaft's as it is; Speed is a val there and a var on every floor.
    assertThat(code)
        .contains(
            "interface Floor : Shaft {\n  override var Speed: Double\n\n  var Open: Double\n\n  /**"
        )
    assertThat(code).contains("interface Shaft {\n  var Level: Double\n\n  val Speed: Double\n")
    assertThat(code).contains("  class A internal constructor() : TypedDevice(), Floor {")
    assertThat(code).contains("    override var Level: Double\n")

    for (bad in
        listOf(
            // A parent that is no family, or the family itself.
            arrayOf("Floor" to floor.copy(extends = listOf("Missing"))),
            arrayOf("Shaft" to shaft.copy(extends = listOf("Shaft"))),
            // A cycle.
            arrayOf("Shaft" to shaft.copy(extends = listOf("Floor")), "Floor" to floor),
            // A family whose members can't all read what the parent declares.
            arrayOf(
                "Floor" to floor.copy(extends = emptyList()),
                "Shaft" to shaft.copy(extends = listOf("Floor")),
            ),
        )) {
      assertThrows<IllegalArgumentException>(bad.toList().toString()) { generate(*bad) }
    }

    // A family whose members can all read what the parent declares a var, but not all write it.
    val lift = Grouping.Family("Lifts", mapOf("A" to "StructureLift", "B" to "StructureLiftCabled"))
    val error =
        assertThrows<IllegalArgumentException> {
          generate("Lift" to lift, "Shaft" to shaft.copy(extends = listOf("Lift")))
        }
    assertThat(error).hasMessageThat().contains("can write Speed")
  }

  @Test
  fun `the consoles must be exactly the prefabs with Circuitboard slots`() {
    fun prefab(name: String, vararg kinds: String) =
        Prefab(
            name,
            hash(name),
            mapOf("On" to Access(read = true, write = true)),
            kinds.withIndex().associate { (number, kind) -> number to SlotInfo(kind) },
        )
    val kinds = mapOf("Circuitboard" to listOf("Quantity"), "Plant" to listOf("Quantity"))
    fun generate(consoles: List<String>, vararg prefabs: Prefab) =
        PrefabGenerator.generate(
            Prefabs("test", prefabs.toList(), kinds),
            Grouping(emptyMap(), emptyList()),
            setOf("LogicType"),
            typing.copy(consoles = consoles),
        )

    generate(
        listOf("StructureConsole"),
        prefab("StructureConsole", "Circuitboard"),
        prefab("StructureTray", "Plant"),
    )
    for ((consoles, prefabs) in
        listOf(
            // A Circuitboard slot on a prefab that isn't a console.
            emptyList<String>() to listOf(prefab("StructureConsole", "Circuitboard")),
            // A console with a slot of another kind.
            listOf("StructureConsole") to
                listOf(prefab("StructureConsole", "Circuitboard", "Plant")),
            // A console with no slots, or that isn't a prefab.
            listOf("StructureConsole") to listOf(prefab("StructureConsole")),
            listOf("Missing") to emptyList(),
        )) {
      assertThrows<IllegalArgumentException>("$consoles $prefabs") {
        generate(consoles, *prefabs.toTypedArray())
      }
    }
  }

  @Test
  fun `a thing's slots follow the game's rules for things, and every slotted non-structure is listed`() {
    val tablet =
        Prefab(
            "ItemTablet",
            hash("ItemTablet"),
            mapOf("On" to Access(true, true)),
            mapOf(0 to SlotInfo("Battery", "Battery"), 1 to SlotInfo("Common")),
        )
    // A prefab named as a structure, which can't be listed as a thing.
    val dock =
        Prefab(
            "StructureDock",
            hash("StructureDock"),
            mapOf("On" to Access(true, true)),
            mapOf(0 to SlotInfo("Common")),
        )
    fun generate(
        things: List<String>,
        structures: List<String> = emptyList(),
        prefabs: List<Prefab> = listOf(tablet),
    ) =
        PrefabGenerator.generate(
                Prefabs(
                    "test",
                    prefabs,
                    mapOf(
                        "Battery" to listOf("Charge", "Quantity"),
                        "Common" to listOf("Quantity"),
                    ),
                ),
                Grouping(emptyMap(), emptyList()),
                setOf("LogicType"),
                typing.copy(
                    slotReadsAlways = listOf("Occupied", "SortingClass"),
                    slotWrites = mapOf("Battery" to listOf("Open")),
                    thingSlotReadsAlways = listOf("Occupied"),
                    thingSlotReadsByKind = mapOf("Battery" to listOf("Charge")),
                    slottedThings = things,
                    slottedStructures = structures,
                ),
            )
            .second

    val code = generate(listOf("ItemTablet"))

    // A thing's battery slot reads only the thing rules' types, and writes nothing.
    assertThat(code)
        .contains(
            "class ItemBatterySlot internal constructor() : DeviceSlot() {\n" +
                "  val Charge: Double\n    get() = intrinsic()\n\n" +
                "  val Occupied: Boolean\n    get() = intrinsic()\n}\n"
        )
    assertThat(code).contains("  val slots: Slots<ItemCommonSlot>\n")
    assertThat(code).contains("  @SlotNumber(0)\n  val Battery: ItemBatterySlot\n")
    // A batch of tablets reads its slots as things' slots too.
    assertThat(code).contains("    val slots: Slots<SlotBatch<ItemCommonSlot>>\n")
    // A structure among the prefabs keeps the structure rules.
    assertThat(generate(listOf("ItemTablet"), prefabs = listOf(tablet, dock)))
        .contains("    val slots: Slots<SlotBatch<CommonSlot>>\n")
    // Unlisted, listed twice, listed without slots, or named as a structure, the generator
    // refuses.
    for (bad in
        listOf(
            { generate(emptyList()) },
            { generate(listOf("ItemTablet"), listOf("ItemTablet")) },
            { generate(listOf("ItemTablet", "ItemMissing")) },
            { generate(listOf("ItemTablet", "StructureDock"), prefabs = listOf(tablet, dock)) },
        )) {
      assertThrows<IllegalArgumentException> { bad() }
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
