package ktrapic.emu

import ktrapic.ic10.Hashes
import ktrapic.ic10.Ic10Limits

/**
 * A device for the emulator: logic and slot values tests set and read, plus a stack-like memory for
 * `get`/`put`. Logic types are kept by name (`"Setting"`), as IC10 text spells them.
 */
class FakeDevice(val prefabHash: Int = 0, val nameHash: Int = 0) {
  constructor(
      prefab: String,
      name: String? = null,
  ) : this(Hashes.hash(prefab), name?.let(Hashes::hash) ?: 0)

  val logic = mutableMapOf<String, Double>()
  val slots = mutableMapOf<Int, MutableMap<String, Double>>()
  val memory = DoubleArray(Ic10Limits.STACK_SIZE)

  /** Every logic write the program made, in order, as `type` to `value`. */
  val writes = mutableListOf<Pair<String, Double>>()

  /**
   * Called after each logic write the program makes, with its type and value, so a test can model a
   * device whose other values change at once.
   */
  var onWrite: (type: String, value: Double) -> Unit = { _, _ -> }

  operator fun get(type: String): Double? = logic[type]

  operator fun set(type: String, value: Double) {
    logic[type] = value
  }

  fun slot(index: Int): MutableMap<String, Double> = slots.getOrPut(index) { mutableMapOf() }

  internal fun read(type: String): Double =
      logic[type] ?: throw IllegalArgumentException("device has no $type")

  internal fun write(type: String, value: Double) {
    logic[type] = value
    writes += type to value
    onWrite(type, value)
  }

  internal fun readSlot(index: Int, type: String): Double =
      slots[index]?.get(type) ?: throw IllegalArgumentException("device slot $index has no $type")

  internal fun writeSlot(index: Int, type: String, value: Double) {
    slot(index)[type] = value
  }
}
