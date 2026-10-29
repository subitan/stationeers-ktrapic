package ktrapic.datagen

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DeviceReferenceGeneratorTest {
  private val enums = LogicEnums("test", mapOf("LogicType" to mapOf("None" to 0, "On" to 28)))
  private val typing = Typing(listOf("LogicType"), listOf("On"))

  private fun device(prefab: String, type: String) =
      """
      |@PrefabName("$prefab")
      |class $type internal constructor() : TypedDevice() {
      |  var On: Boolean
      |    get() = intrinsic()
      |    set(value) = intrinsic()
      |
      |  @SlotNumber(0)
      |  val DataDisk: DataDiskSlot
      |    get() = intrinsic()
      |
      |  companion object : Prefab<$type>()
      |}
      |"""
          .trimMargin()

  @Test
  fun `lists a prefab whose name is not an identifier`() {
    val reference =
        DeviceReferenceGenerator.generate(
            device("StructureTransformerMedium(Reversed)", "TransformerMedium_Reversed"),
            enums,
            typing,
        )

    assertThat(reference)
        .contains(
            "/** StructureTransformerMedium(Reversed). */\n" +
                "class TransformerMedium_Reversed : TypedDevice {\n"
        )
    assertThat(reference).contains("1 prefab in all")
  }

  @Test
  fun `numbers the slots the game names, after the logic types`() {
    val reference =
        DeviceReferenceGenerator.generate(device("StructureVent", "Vent"), enums, typing)

    assertThat(reference)
        .contains("  var On: Boolean\n  val DataDisk: DataDiskSlot // slots[0]\n}\n")
  }

  @Test
  fun `rejects a typed device it cannot attribute to a prefab`() {
    val source = "class Unnamed internal constructor() : TypedDevice() {\n}\n"

    val error =
        assertThrows<IllegalArgumentException> {
          DeviceReferenceGenerator.generate(source, enums, typing)
        }
    assertThat(error).hasMessageThat().contains("recognized @PrefabName")
  }

  @Test
  fun `rejects a typed device whose header it cannot read`() {
    val source =
        device("StructureVent", "Vent") +
            device("StructureHidden", "Hidden")
                .replace("class Hidden internal", "internal class Hidden internal")

    val error =
        assertThrows<IllegalArgumentException> {
          DeviceReferenceGenerator.generate(source, enums, typing)
        }
    assertThat(error).hasMessageThat().contains("2 typed device(s) but 1 were read")
  }

  /**
   * A `Sensor` family extending `Detector`, which writes `Mode`; `Sensor` writes `Lock` and only
   * reads `Activate` and `Quantity`. Its one member, `Motion`, redeclares all four, writing every
   * one but `Quantity`.
   */
  private val sensorFamily =
      """
      |interface Detector {
      |  var Mode: Double
      |}
      |
      |interface Sensor : Detector {
      |  val Activate: Boolean
      |
      |  var Lock: Boolean
      |
      |  val Quantity: Double
      |
      |  @PrefabName("StructureMotionSensor")
      |  class Motion internal constructor() : TypedDevice(), Sensor {
      |    override var Activate: Boolean
      |      get() = intrinsic()
      |      set(value) = intrinsic()
      |
      |    override var Lock: Boolean
      |      get() = intrinsic()
      |      set(value) = intrinsic()
      |
      |    override var Mode: Double
      |      get() = intrinsic()
      |      set(value) = intrinsic()
      |
      |    override val Quantity: Double
      |      get() = intrinsic()
      |
      |    companion object : Prefab<Sensor.Motion>()
      |  }
      |}
      |"""
          .trimMargin()

  @Test
  fun `shows a logic type a family member can write where its family only reads it`() {
    val reference = DeviceReferenceGenerator.generate(sensorFamily, enums, typing)

    assertThat(reference)
        .contains(
            "  class Motion : TypedDevice, Sensor {\n    override var Activate: Boolean\n  }\n"
        )
  }

  @Test
  fun `does not show a logic type a family member writes where its family writes it too`() {
    val reference = DeviceReferenceGenerator.generate(sensorFamily, enums, typing)

    assertThat(reference).doesNotContain("override var Lock")
  }

  @Test
  fun `does not show a logic type a family member writes where a family it extends writes it`() {
    val reference = DeviceReferenceGenerator.generate(sensorFamily, enums, typing)

    assertThat(reference).doesNotContain("override var Mode")
  }

  @Test
  fun `rejects a family extending an interface the source does not declare`() {
    val source = sensorFamily.replace("interface Detector {", "interface Unknown {")

    val error =
        assertThrows<IllegalArgumentException> {
          DeviceReferenceGenerator.generate(source, enums, typing)
        }
    assertThat(error).hasMessageThat().contains("family Sensor extends Detector")
  }

  @Test
  fun `lists a typed device whose header is wrapped`() {
    val source =
        device("StructureHidden", "Hidden").replace(": TypedDevice() {", ":\n    TypedDevice() {")

    val reference = DeviceReferenceGenerator.generate(source, enums, typing)

    assertThat(reference).contains("/** StructureHidden. */\n")
  }

  @Test
  fun `rejects a typed device without a prefab companion object`() {
    val source =
        device("StructureVent", "Vent").replace("  companion object : Prefab<Vent>()\n", "")

    val error =
        assertThrows<IllegalArgumentException> {
          DeviceReferenceGenerator.generate(source, enums, typing)
        }
    assertThat(error).hasMessageThat().contains("without a prefab companion object: [Vent]")
  }
}
