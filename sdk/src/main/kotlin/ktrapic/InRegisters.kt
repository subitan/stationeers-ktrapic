package ktrapic

/**
 * Passes this function's arguments and result in registers of its own, rather than on the chip's
 * stack.
 *
 * A function the program calls more than once, and doesn't mark `inline`, has one body that each
 * call reaches with `jal`. By default the caller pushes the arguments on the stack, and the
 * function pops them and pushes its result for the caller to pop; the caller also pushes the
 * registers it still needs after the call, and pops them back. The function's registers are then
 * its own, and it may call itself, directly or through others.
 *
 * Marked `@InRegisters`, the function instead takes its arguments in registers kept for them, and
 * gives its result in one, as with every such function when stack calls are turned off. That saves
 * the pushes and pops, but every value the caller needs after the call keeps its register
 * throughout the function, and the function can't be in a cycle of calls.
 *
 * It changes nothing for a function that is inlined: one marked `inline`, or called only once.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class InRegisters
