package ktrapic

/**
 * A prefab: the game's hash of its name, and the way to its devices. Each typed device's companion
 * object is one, so `GrowLight` is the grow light's prefab. As a value, `d0.PrefabHash ==
 * GrowLight` compares a device's prefab with it; it is not a number, so it can't be used in
 * arithmetic. Its devices are `GrowLight.at(d0)`, `GrowLight.all` and
 * `GrowLight.named("Potatoes")`, and a family member's are its nested class's, as
 * `LedDisplay.Small.all`.
 */
abstract class Prefab<T : TypedDevice> internal constructor() {
  /** The device on [pin], as one of this prefab. */
  fun at(pin: Device): T = intrinsic()

  /** Every device of this prefab on the chip's network. */
  open val all: DeviceBatch<T>
    get() = intrinsic()

  /** Every device of this prefab on the network named [name], which must be a constant. */
  open fun named(name: String): DeviceBatch<T> = intrinsic()
}

/**
 * A prefab with slots, whose devices' batch is its own type [B]: `BatteryCharger.Standard.all` is a
 * `BatteryCharger.Standard.Batch`, which reaches the slots of every device in it, as
 * `BatteryCharger.Standard.all.slots[0]`. Its companion object is one, so a val holding the prefab
 * is a `SlottedPrefab<BatteryCharger.Standard, BatteryCharger.Standard.Batch>`.
 */
abstract class SlottedPrefab<T : TypedDevice, B : DeviceBatch<T>> internal constructor() :
    Prefab<T>() {
  override val all: B
    get() = intrinsic()

  override fun named(name: String): B = intrinsic()
}

/**
 * A device whose prefab is known, such as `GrowLight.at(d0)`. It has only the logic types its
 * prefab has, `val` where they can only be read.
 */
abstract class TypedDevice internal constructor() {
  /** The device's prefab. */
  val PrefabHash: Prefab<*>
    get() = intrinsic()

  /** The game's hash of the device's name. */
  val NameHash: Double
    get() = intrinsic()

  /** The device's ID. */
  val ReferenceId: Double
    get() = intrinsic()
}

/** The game's hash of this text, such as a prefab or device name. The text must be a constant. */
fun String.hash(): Double = intrinsic()

internal const val WRITE_ONLY = "this logic type can only be written"

/**
 * The logic types a typed device's view (`Ports`, `Ratios`, `Position`, ...) reaches on its prefab,
 * comma-separated: those it can [read], and those it can be [written]. The compiler checks each use
 * of the view against them, as Kotlin's types can't say which keys a device has.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class LogicTypes(val read: String, val written: String)

/** The game's name of the prefab a generated typed device stands for. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class PrefabName(val name: String)
