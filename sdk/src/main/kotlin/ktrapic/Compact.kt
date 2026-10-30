package ktrapic

/**
 * Compiles this program compact: its labels are resolved to line numbers and dropped. Written once,
 * as `@file:Compact`, at the top of the program.
 *
 * Each label is a line of its own, and each jump names one, so compact output saves a line for
 * every label: often 5 to 20 lines on a program with loops, branches and functions. It costs
 * nothing on the chip, but the code is harder to read, as jumps go to line numbers rather than
 * names, and changing a line before a jump's target moves it.
 */
@Target(AnnotationTarget.FILE) @Retention(AnnotationRetention.BINARY) annotation class Compact
