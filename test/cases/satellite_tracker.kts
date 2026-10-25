import ktrapic.*

// Satellite Signal Tracker: points a dish (d0) at a satellite. It scans the horizon in 20 degree
// steps until it hears a signal, then nudges the dish a degree at a time while that improves the
// signal, until it is strong. Status text goes to a console on d5, and the signal strength and
// heading to displays on d2 and d3. While the dish moves, its SignalStrength reads -1.

val dish = d0
val console = d5

fun debug(text: Double) {
  console.Mode = DisplayMode.String
  console.Setting = text
  sleep(2)
}

fun display() {
  d2.Setting = dish.SignalStrength
  d3.Setting = dish.Horizontal
}

fun moveDish(dh: Double, dv: Double) {
  dish.Horizontal = dish.Horizontal + dh
  dish.Vertical = dish.Vertical + dv
  while (dish.SignalStrength == -1.0) {
    display()
    debug(str("move."))
    sleep(0.25)
    debug(str("move "))
  }
  debug(str("stop"))
  sleep(1)
  display()
}

/** Moves the dish by [dh] and [dv], and back again if that made the signal weaker. */
fun adjust(dh: Double, dv: Double) {
  val before = dish.SignalStrength
  moveDish(dh, dv)
  if (dish.SignalStrength < before) moveDish(-dh, -dv)
}

dish.Vertical = 45.0
dish.Horizontal = 0.0
while (dish.SignalStrength == -1.0) {
  debug(str("init."))
  sleep(0.25)
  debug(str("init "))
  display()
}

while (dish.SignalStrength < 2.0) {
  debug(str("Scan"))
  moveDish(20.0, 0.0)
}

while (dish.SignalStrength < 97.0) {
  debug(str("Fine"))
  adjust(0.0, 1.0)
  adjust(0.0, -1.0)
  adjust(1.0, 0.0)
  adjust(-1.0, 0.0)
  display()
}

debug(str("Done!"))
