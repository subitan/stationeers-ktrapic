package ktrapic

/**
 * Passes this function's arguments and result in registers of its own, rather than on the chip's
 * stack. Written as `@file:InRegisters` at the top of the program, it does so for every function.
 *
 * A function the program calls more than once, and doesn't mark `inline`, has one body that each
 * call reaches with `jal`. By default the caller pushes the arguments on the stack, and the
 * function pops them and pushes its result for the caller to pop; the caller also pushes the
 * registers it still needs after the call, and pops them back. The function's registers are then
 * its own, and it may call itself, directly or through others.
 *
 * Marked `@InRegisters`, the function instead takes its arguments in registers kept for them, and
 * gives its result in one. That saves the pushes and pops, a line for each argument, result and
 * saved register at each call, and as many in the function, but every value the caller needs after
 * the call keeps its register throughout the function, so the program may need more registers, and
 * the function can't be in a cycle of calls.
 *
 * It changes nothing for a function that is inlined: one marked `inline`, or called only once.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.FILE)
@Retention(AnnotationRetention.BINARY)
annotation class InRegisters
