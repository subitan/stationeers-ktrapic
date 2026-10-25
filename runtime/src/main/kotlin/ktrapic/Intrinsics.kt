package ktrapic

/**
 * The body of everything in this package. The package declares what programs may call; the compiler
 * translates those calls to IC10, so nothing here runs on the JVM.
 */
internal fun intrinsic(): Nothing =
    throw UnsupportedOperationException("The ktrapic API only runs on an IC10 chip")

/** Pauses the chip for [seconds] (IC10 `sleep`). */
fun sleep(seconds: Double): Unit = intrinsic()

/** Pauses the chip for [seconds] (IC10 `sleep`). */
fun sleep(seconds: Int): Unit = intrinsic()

/** Ends the chip's work for this tick (IC10 `yield`). */
fun yield(): Unit = intrinsic()

/** The game's hash of [text], such as a prefab or device name. [text] must be a constant. */
fun hash(text: String): Double = intrinsic()

/**
 * [text], of up to six ASCII characters, packed into a number, for displays that show text. [text]
 * must be a constant.
 */
fun str(text: String): Double = intrinsic()
