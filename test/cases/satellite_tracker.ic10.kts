import ktrapic.*
import kotlin.math.*

// Satellite Signal Tracker: points a medium satellite dish (d0) at a satellite.
//
// The dish's SignalStrength is how far off, in degrees, it points from the best-aligned contact
// it has resolved: lower is better. It reads -1 while the dish has no contact. After every move it
// reads -1 again until the dish, once stopped, resolves its contacts afresh. That takes longer the
// further off it points and the less power its Setting gives it, so the program turns the Setting
// up as far as the dish allows, and waits up to WAIT seconds at each position.
//
// The program sweeps the sky in 20 degree steps of heading at each of four tilts until the dish
// resolves a contact. At the game's turn rate, a full pass of the sky takes roughly 40 minutes, by
// estimate rather than timing; a smaller WAIT shortens it, at the cost of missing contacts further
// off each position. Once the dish has a contact, the program nudges it, in steps that shrink as
// it gets closer, keeping each nudge that brings the offset down, until the dish is within a
// degree. Status text goes to a console on d5; the offset, heading and elevation go to displays on
// d2, d3 and d4. Flip the lever on d1 to start again.

val dish = SatelliteDish.Medium.at(d0)
val reset = Switch.Lever.at(d1)

val offset = LedDisplay.Small.at(d2)
val azimuth = LedDisplay.Small.at(d3)
val elevation = LedDisplay.Small.at(d4)

val console = LedDisplay.Small.at(d5)
console.Mode = DisplayMode.String

// The dish keeps its Setting within its own range of watts, so this gives it its most.
dish.Setting = 10000.0

/**
 * The longest the program waits, in seconds, for the dish to resolve a contact where it points. At
 * full power, with the game's default figures for a dish and a contact, that is enough to resolve
 * one about 17 degrees off, which covers the gaps between the sweep's positions.
 */
val WAIT = 20

// Where the dish is told to point. The dish wraps Horizontal into 0 to 360 itself.
var heading = 0.0
var tilt = 0.0

inline fun debug(text: String) {
  console.Text = text
}

inline fun display() {
  offset.Setting = dish.SignalStrength
  azimuth.Setting = dish.Horizontal
  elevation.Setting = dish.Vertical
}

/**
 * Points the dish at [heading] and [tilt], waits until it stops, then gives it up to [WAIT] seconds
 * to resolve a contact.
 */
fun aim() {
  dish.Horizontal = heading
  dish.Vertical = tilt
  while (!dish.Idle) yield()
  var waited = 0
  while (dish.SignalStrength == -1.0 && waited < WAIT) {
    sleep(1)
    waited++
  }
  display()
}

/**
 * Moves the dish by [dh] and [dv], and back to where it was if that lost the signal or took it
 * further off. If the dish had yet to resolve where it was, any position that resolves is better.
 * Its arguments go in registers, which saves the lines four stack calls would take.
 */
@InRegisters
fun fineTune(dh: Double, dv: Double) {
  val before = dish.SignalStrength
  val oldHeading = heading
  val oldTilt = tilt
  heading += dh
  tilt = min(90.0, max(0.0, tilt + dv))
  aim()
  val after = dish.SignalStrength
  if (after == -1.0 || (before != -1.0 && after > before)) {
    heading = oldHeading
    tilt = oldTilt
    aim()
  }
}

/** Steps the dish around the sky, from low to high, until it resolves a contact. */
inline fun sweep() {
  heading = 0.0
  tilt = 12.0
  aim()
  while (dish.SignalStrength == -1.0) {
    debug("Scan")
    heading += 20.0
    if (heading == 360.0) {
      heading = 0.0
      // Tilts 12, 36, 60 and 84, then back to 12.
      tilt = if (tilt > 80.0) 12.0 else tilt + 24.0
    }
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
