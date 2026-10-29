import ktrapic.*

// Counts down from the n set on the housing by a call in tail position, which loops rather than
// takes stack, so n may be far deeper than the stack. The gcd of 1071 and 462 recurses the same
// way; the sum of 1..10 adds to its call's result, so it recurses on the stack.
fun countdown(n: Int, steps: Int): Int = if (n == 0) steps else countdown(n - 1, steps + 1)

fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

fun sum(n: Int): Int = if (n == 0) 0 else n + sum(n - 1)

db.Ratio = countdown(db.Setting.toInt(), 0).toDouble()
db.Mode = gcd(1071, 462).toDouble()
db.On = sum(10) == 55
