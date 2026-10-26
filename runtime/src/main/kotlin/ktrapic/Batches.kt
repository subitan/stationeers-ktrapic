package ktrapic

/**
 * Every device of one prefab on the chip's network (`GrowLights.all`), every one with a name
 * (`GrowLights.named("Potatoes")`), or one slot of each of them (`BatteryChargers.all.slots[0]`).
 *
 * A batch is read through Kotlin's collection operations, each of which compiles to one batch
 * instruction (`lb`, `lbn`, `lbs`, `lbns`): `all.map { it.Charge }.average()` is the average
 * charge, and `all.count { it.On }` how many are on. It is written with `forEach`, which writes
 * every device (`sb`, `sbn`, `sbs`): `all.forEach { it.On = true }`.
 *
 * A reading lambda may only pick a logic type, as `{ it.X }` or a property reference such as
 * `GrowLight::On`; a writing one may only assign logic types, as `it.X = value`, with values that
 * don't depend on `it`.
 *
 * Unlike Kotlin's, `minOf` and `maxOf` don't throw for an empty batch: `maxOf` gives negative
 * infinity, and `minOf` and `sumOf` give 0.
 */
abstract class Batch<out T> internal constructor() {
  /**
   * The batch's logic type picked by [selector] (`{ it.Charge }`), for one of [average] and others.
   */
  fun <R> map(selector: (T) -> R): Mapped<R> = intrinsic()

  /** The sum of [selector]'s logic type over the batch (`Sum`). */
  fun sumOf(selector: (T) -> Double): Double = intrinsic()

  /** The least of [selector]'s logic type over the batch (`Minimum`); 0 for no devices. */
  fun minOf(selector: (T) -> Double): Double = intrinsic()

  /** The greatest of [selector]'s logic type over the batch (`Maximum`); -∞ for no devices. */
  fun maxOf(selector: (T) -> Double): Double = intrinsic()

  /** How many of the batch have [predicate]'s logic type on: its `Sum`. */
  fun count(predicate: (T) -> Boolean): Int = intrinsic()

  /** Writes the logic types [action] assigns (`it.On = true`) to every one of the batch. */
  fun forEach(action: (T) -> Unit): Unit = intrinsic()
}

/** A batch of devices, which can also be counted. */
open class DeviceBatch<out T> internal constructor() : Batch<T>() {
  /** How many devices of the prefab are on the network (`Count`), whatever their names. */
  fun count(): Int = intrinsic()
}

/** One slot of every device in a batch: `BatteryChargers.all.slots[0]`. */
class SlotBatch<out S> internal constructor() : Batch<S>()

/** A batch's logic type, picked by `map`, to be ended with [average], [sum], [min] or [max]. */
class Mapped<out R> internal constructor()

/** The average over the batch (`Average`); NaN for no devices. */
fun Mapped<Double>.average(): Double = intrinsic()

/** The sum over the batch (`Sum`). */
fun Mapped<Double>.sum(): Double = intrinsic()

/** The least over the batch (`Minimum`); 0 for no devices, where Kotlin's `min()` throws. */
fun Mapped<Double>.min(): Double = intrinsic()

/** The greatest over the batch (`Maximum`); -∞ for no devices, where Kotlin's `max()` throws. */
fun Mapped<Double>.max(): Double = intrinsic()
