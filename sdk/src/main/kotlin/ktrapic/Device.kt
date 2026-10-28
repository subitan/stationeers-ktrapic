package ktrapic

/**
 * A device a chip can reach: one on a pin (`d0`..`d5`), or the chip's own housing (`db`).
 *
 * Its logic types are properties named as in the game (`d0.Setting`). Logic types that are on or
 * off (`On`, `Open`, `Lock`, ...) are `Boolean`, where any value but 0 reads as `true`; the rest
 * are `Double`. Indexing by [LogicType] reads or writes any logic type as a `Double`.
 */
class Device internal constructor() : GenericDevice() {
  operator fun get(logicType: LogicType): Double = intrinsic()

  operator fun set(logicType: LogicType, value: Double): Unit = intrinsic()
}

/** The device on pin 0. */
val d0: Device
  get() = intrinsic()

/** The device on pin 1. */
val d1: Device
  get() = intrinsic()

/** The device on pin 2. */
val d2: Device
  get() = intrinsic()

/** The device on pin 3. */
val d3: Device
  get() = intrinsic()

/** The device on pin 4. */
val d4: Device
  get() = intrinsic()

/** The device on pin 5. */
val d5: Device
  get() = intrinsic()

/** The housing the chip is in. */
val db: Device
  get() = intrinsic()
