package ktrapic

/**
 * A device's slots, or a batch's, by number from 0: `d0.slots[0].Quantity`. The number may be
 * computed, such as a loop's variable.
 */
class Slots<out S> internal constructor() {
  operator fun get(number: Int): S = intrinsic()
}

/**
 * A slot of one device. Its logic types load with `ls` and store with `ss`; the game lets a program
 * write only a few of them (`On`, `Open`, `Lock` and, on consoles, `Mode`), depending on the slot.
 */
abstract class DeviceSlot internal constructor()

/**
 * A slot of every device in a batch. Writing one of its logic types writes it to all of them
 * (`sbs`); reading goes through a batch method, as in `all.average.slots[0].Charge`.
 */
abstract class SlotBatch internal constructor()

/** A slot of a batch read through a batch method (`lbs`, `lbns`), all as `Double`. */
abstract class SlotReading internal constructor()

/** The number of the slot a generated named slot property, such as a charger's `Battery`, is. */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class SlotNumber(val number: Int)
