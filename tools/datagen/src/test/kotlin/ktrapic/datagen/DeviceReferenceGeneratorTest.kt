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
      |}
      |
      |@PrefabName("$prefab")
      |object ${type}s {
      |  fun at(pin: Device): $type = intrinsic()
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
            "/** StructureTransformerMedium(Reversed). Entry point: TransformerMedium_Reverseds. */\n" +
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

  @Test
  fun `shows a logic type a family member can write where its family only reads it`() {
    val source =
        """
        |interface Sensor {
        |  val Activate: Boolean
        |
        |  val Quantity: Double
        |
        |  @PrefabName("StructureMotionSensor")
        |  class Motion internal constructor() : TypedDevice(), Sensor {
        |    override var Activate: Boolean
        |      get() = intrinsic()
        |      set(value) = intrinsic()
        |
        |    override val Quantity: Double
        |      get() = intrinsic()
        |  }
        |}
        |
        |object Sensors {
        |  @PrefabName("StructureMotionSensor")
        |  object Motion {
        |    fun at(pin: Device): Sensor.Motion = intrinsic()
        |  }
        |}
        |"""
            .trimMargin()

    val reference = DeviceReferenceGenerator.generate(source, enums, typing)

    assertThat(reference)
        .contains(
            "  class Motion : TypedDevice, Sensor {\n    override var Activate: Boolean\n  }\n"
        )
  }

  @Test
  fun `lists a typed device whose header is wrapped`() {
    val source =
        device("StructureHidden", "Hidden").replace(": TypedDevice() {", ":\n    TypedDevice() {")

    val reference = DeviceReferenceGenerator.generate(source, enums, typing)

    assertThat(reference).contains("/** StructureHidden. Entry point: Hiddens. */\n")
  }
}
