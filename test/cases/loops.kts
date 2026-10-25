import ktrapic.*

// Sums 1..n for the n set on the housing, counting down the even numbers too.
val n = db.Setting.toInt()
var sum = 0
for (i in 1..n) sum += i
var evens = 0
for (i in n downTo 0 step 2) {
  if (i == 0) break
  evens++
}
db.Ratio = sum.toDouble()
db.Mode = evens.toDouble()
