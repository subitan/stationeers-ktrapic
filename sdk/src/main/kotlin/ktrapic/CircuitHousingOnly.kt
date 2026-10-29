package ktrapic

/**
 * Declares that this program runs only where `db` is the chip's own stack memory: in an IC housing,
 * a rocket circuit housing or a suit, and not in a machine with a chip slot, where `db` is the
 * machine. Written once, as `@file:CircuitHousingOnly`, at the top of the program.
 *
 * The compiler may then keep values that don't fit in the chip's 16 registers in memory at the top
 * of its stack, at addresses 511, 510 and down, read and written with `get` and `put` on `db`. It
 * does so only when the program would otherwise need more than 16 registers; a program that fits
 * compiles exactly as it would without the annotation.
 *
 * A program that keeps values in memory reads one of those addresses on its first line, so on any
 * other host the chip stops there with an error, rather than partway through. A recursive function
 * in such a program checks the stack's depth when it is called, and stops the chip with an error
 * before its calls could reach the memory in use.
 */
@Target(AnnotationTarget.FILE)
@Retention(AnnotationRetention.BINARY)
annotation class CircuitHousingOnly
