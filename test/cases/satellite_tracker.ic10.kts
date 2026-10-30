import ktrapic.*
import kotlin.math.*

// Satellite Signal Tracker: points a medium satellite dish (d0) at a satellite.
//
// The dish's SignalStrength is how far off, in degrees, it points from the best-aligned contact
// it has resolved: lower is better. It reads -1 while the dish has no contact. After every move it
// reads -1 again until the dish resolves its contacts afresh, which takes a few seconds, longer the
// further off it points. The dish's power Setting decides how far off and how fast: the more power,
// the further off it can point and still resolve a contact within the five seconds this program
// waits, and the sooner it does. The program leaves the Setting as it finds it.
//
// The program sweeps the horizon in 20 degree steps until the dish resolves a contact. Then it
// nudges the dish, in steps that shrink as it gets closer, keeping each nudge that brings the
// offset down, until the dish is within a degree. Status text goes to a console on d5; the
// offset, heading and elevation go to displays on d2, d3 and d4. Flip the lever on d1 to start
// again.

val dish = SatelliteDish.Medium.at(d0)
val reset = Switch.Lever.at(d1)

val offset = LedDisplay.Small.at(d2)
val azimuth = LedDisplay.Small.at(d3)
val elevation = LedDisplay.Small.at(d4)

val console = LedDisplay.Small.at(d5)
console.Mode = DisplayMode.String

// Where the dish is told to point. Reading the dish's Horizontal and Vertical gives where it points
// now, which lags behind while it turns, so the program keeps its own target. The game wraps
// Horizontal into 0 to 360 and keeps Vertical within 0 to 90; these do the same.
var heading = 0.0
var tilt = 45.0

inline fun debug(text: String) {
  console.Text = text
}

fun display() {
  offset.Setting = dish.SignalStrength
  azimuth.Setting = heading
  elevation.Setting = tilt
}

/**
 * Points the dish at [heading] and [tilt], waits until it gets there, then gives it up to five
 * seconds to resolve a contact.
 */
fun aim() {
  dish.Horizontal = heading
  dish.Vertical = tilt
  while (abs(dish.Horizontal - heading) > 0.5 || abs(dish.Vertical - tilt) > 0.5) yield()
  var waited = 0
  while (dish.SignalStrength == -1.0 && waited < 5) {
    sleep(1)
    waited++
  }
  display()
}

/**
 * Moves the dish by [dh] and [dv], and back again if that took it further off the signal. Its
 * arguments go in registers, which saves the lines four stack calls would take.
 */
@InRegisters
fun fineTune(dh: Double, dv: Double) {
  val before = dish.SignalStrength
  heading = (heading + dh + 360.0) % 360.0
  tilt = min(90.0, max(0.0, tilt + dv))
  aim()
  val after = dish.SignalStrength
  if (after == -1.0 || after > before) {
    heading = (heading - dh + 360.0) % 360.0
    tilt = min(90.0, max(0.0, tilt - dv))
    aim()
  }
}

inline fun sweep() {
  heading = 0.0
  tilt = 45.0
  aim()
  while (dish.SignalStrength == -1.0) {
    debug("Scan")
    heading = (heading + 20.0) % 360.0
    aim()
  }
}

fun adjust() {
  while (dish.SignalStrength > 1.0) {
    debug("Tuning")
    // Big steps while far off, down to a degree.
    val step = max(1.0, floor(dish.SignalStrength / 2.0))
    fineTune(0.0, step)
    fineTune(0.0, -step)
    fineTune(step, 0.0)
    fineTune(-step, 0.0)
  }
}

while (true) {
  sweep()
  adjust()
  // A contact can go away mid-tune; then sweep for one again rather than stop.
  if (dish.SignalStrength == -1.0) continue
  debug("Done!")
  reset.Open = false
  while (!reset.Open) sleep(2)
}
