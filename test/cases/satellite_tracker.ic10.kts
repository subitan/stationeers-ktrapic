@file:Compact
import ktrapic.*
import kotlin.math.*

// Satellite Signal Tracker: points a medium satellite dish (d0) at a satellite.
//
// The dish's SignalStrength is how far off, in degrees, it points from the best-aligned contact
// it has resolved: lower is better. It reads -1 while the dish has no contact. After every move it
// reads -1 again until the dish, once stopped, resolves its contacts afresh. That takes longer the
// further off it points and the less power its Setting gives it, so the program sets it to POWER
// and waits up to WAIT seconds at each position. POWER stays within what standard cable carries
// (5000 W): the medium dish accepts more than that, and a larger Setting can burn its cables.
//
// The program sweeps the sky in 20 degree steps of heading at each of four tilts until the dish
// resolves a contact. At the game's turn rate, a full pass of the sky takes roughly 40 minutes, by
// estimate rather than timing; a smaller WAIT shortens it, at the cost of missing contacts further
// off each position. Once the dish has a contact, the program line-searches along each axis: it
// keeps stepping one way while each step brings the offset down, then goes back to the best
// position. Steps start at 8 degrees, are never more than half the offset, and halve whenever no
// direction helps. A step up over the zenith carries on down the far side; the tilt stops at 89.5
// degrees, short of the zenith, where heading steps would not move the dish. It stops once the
// dish is within a degree, or once no step of a degree or less helps.
// Status text goes to a console on d5; the offset, heading and elevation go to displays on d2, d3
// and d4. Flip the lever on d1 to start again.

val dish = SatelliteDish.Medium.at(d0)
val reset = Switch.Lever.at(d1)

val offset = LedDisplay.Small.at(d2)
val azimuth = LedDisplay.Small.at(d3)
val elevation = LedDisplay.Small.at(d4)

val console = LedDisplay.Small.at(d5)
console.Mode = DisplayMode.String

/**
 * The dish's power, in watts. More resolves contacts further off, and faster, but the dish draws
 * all of it while it interrogates a contact: keep it within what the network's cables carry (5000 W
 * for standard cable, less whatever else draws on that network). The medium dish takes far more
 * than that, so writing a large value here can burn its cables.
 */
val POWER = 2000.0

dish.Setting = POWER

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
@InRegisters
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
 * Moves the dish by [dh] and [dv] again and again while each move brings the offset down, then
 * back to the last position that did. Returns whether it got any closer. Losing the contact
 * counts as worse; if the dish had yet to resolve where it started, any position that resolves
 * is closer.
 */
@InRegisters
fun lineSearch(dh: Double, dv: Double): Boolean {
  var closer = false
  while (true) {
    val before = dish.SignalStrength
    val oldHeading = heading
    val oldTilt = tilt
    heading += dh
    tilt = max(0.0, tilt + dv)
    // A move up over the zenith carries on down the far side. The tilt stops short of 90, where a
    // heading step would not move the beam, so that heading steps can still bring the dish round.
    if (tilt > 90.0) {
      tilt = 180.0 - tilt
      heading += 180.0
    }
    tilt = min(89.5, tilt)
    aim()
    val after = dish.SignalStrength
    if (after == -1.0 || (before != -1.0 && after >= before)) {
      heading = oldHeading
      tilt = oldTilt
      aim()
      return closer
    }
    closer = true
  }
}
/** Steps the dish around the sky, from low to high, until it resolves a contact. */
inline fun sweep() {
  heading = 0.0
  tilt = 12.0
  aim()
  while (dish.SignalStrength == -1.0) {
    debug("Sweep")
    heading += 20.0
    if (heading == 360.0) {
      heading = 0.0
      // Tilts 12, 36, 60 and 84, then back to 12.
      tilt += 24.0
      if (tilt > 90.0) tilt = 12.0
    }
    aim()
  }
}

/**
 * Searches along each axis in turn, in steps of at most 8 degrees and at most half the offset,
 * halving the step once no direction gets the dish any closer. Ends once the dish is within a
 * degree, or once no step of a degree or less gets it closer, or once it has lost the contact.
 */
fun adjust() {
  debug("Hunt")
  var step = 8.0
  while (dish.SignalStrength > 1.0) {
    step = min(step, max(1.0, floor(dish.SignalStrength / 2.0)))
    // Up, or else down; then right, or else left (both always searched); halve once none helps.
    val vertical = lineSearch(0.0, step) || lineSearch(0.0, -step)
    if (!(lineSearch(step, 0.0) || lineSearch(-step, 0.0) || vertical)) {
      if (step <= 1.0) return
      step /= 2.0
    }
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
