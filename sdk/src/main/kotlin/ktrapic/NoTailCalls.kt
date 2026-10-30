package ktrapic

/**
 * Compiles a function's calls to itself in tail position as ordinary calls, rather than as loops.
 * Written once, as `@file:NoTailCalls`, at the top of the program.
 *
 * By default, a function called on the stack whose call to itself is its result (or, in a function
 * with no result, its last statement) moves its arguments into its parameters and jumps back to its
 * top, pushing nothing, so it can recurse without limit. With this annotation that call pushes its
 * arguments, calls with `jal` and pops its result, as any other call does, and a deep recursion can
 * overflow the chip's stack.
 *
 * It rarely changes the program's length: a loop moves its arguments and jumps, where a call
 * pushes, calls and pops. Arguments that swap cost a line more as a loop, and the loop's label a
 * line in output that isn't compact. It also takes away the warning for a program that only fits in
 * the chip's registers with its tail calls made as calls.
 */
@Target(AnnotationTarget.FILE) @Retention(AnnotationRetention.BINARY) annotation class NoTailCalls
