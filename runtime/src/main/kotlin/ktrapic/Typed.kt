package ktrapic

/**
 * A prefab, as a value: the game's hash of its name. Each typed device's companion object is one,
 * so `d0.PrefabHash == GrowLight` compares a device's prefab with the grow light's. It is not a
 * number, so it can't be used in arithmetic.
 */
abstract class Prefab internal constructor()

/**
 * A device whose prefab is known, such as `GrowLights.at(d0)`. It has only the logic types its
 * prefab has, `val` where they can only be read.
 */
abstract class TypedDevice internal constructor() {
  /** The device's prefab. */
  val PrefabHash: Prefab
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

/** The game's name of the prefab a generated typed device or entry point stands for. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class PrefabName(val name: String)
