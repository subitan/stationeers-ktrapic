package ktrapic.api

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import ktrapic.emu.Emulator
import ktrapic.emu.FakeDevice
import ktrapic.ic10.Hashes
import ktrapic.ic10.Ic10Limits
import ktrapic.ic10.Program
import org.junit.jupiter.api.Test

/**
 * Runs `test/cases/satellite_tracker.ic10.kts` against a simulated dish that behaves as the game's
 * does:
 * - Writing Horizontal and Vertical sets where the dish turns to: Horizontal wraps into 0 to 360,
 *   Vertical is kept within 0 to 90. Reading them gives where it points now, which follows the
 *   target the short way round, a limited number of degrees each tick.
 * - SignalStrength is the angle, in degrees, between where the dish points and the satellite. It
 *   reads -1 while the satellite is too far off to find, and after any move until the dish has
 *   resolved it again, a few ticks after it stops.
 */
class SatelliteTrackerTest {
  private val source =
      File(
              System.getenv("TEST_SRCDIR"),
              System.getenv("TEST_WORKSPACE") + "/test/cases/satellite_tracker.ic10.kts",
          )
          .readText()

  private class Dish(val satelliteH: Double, val satelliteV: Double, h: Double, v: Double) {
    val device = FakeDevice()
    private var targetH = h
    private var targetV = v
    private var h = h
    private var v = v
    private var resolving = RESOLVE_TICKS
    var moves = 0
      private set

    init {
      device["Setting"] = 1000.0
      show()
      device.onWrite = { type, value ->
        when (type) {
          "Horizontal" -> targetH = ((value % 360.0) + 360.0) % 360.0
          "Vertical" -> targetV = value.coerceIn(0.0, 90.0)
        }
        if (type == "Horizontal" || type == "Vertical") {
          moves++
          resolving = RESOLVE_TICKS
        }
        // A write sets the target; reads still give where the dish points.
        show()
      }
    }

    /** The angle between where the dish points and the satellite, in degrees. */
    fun offset(): Double {
      fun direction(h: Double, v: Double): DoubleArray {
        val (hr, vr) = Math.toRadians(h) to Math.toRadians(v)
        return doubleArrayOf(cos(vr) * cos(hr), cos(vr) * sin(hr), sin(vr))
      }
      val a = direction(h, v)
      val b = direction(satelliteH, satelliteV)
      val dot = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).coerceIn(-1.0, 1.0)
      return Math.toDegrees(acos(dot))
    }

    /** Called between ticks: the dish turns toward its target, and resolves once it stops. */
    fun update() {
      val dh = ((targetH - h + 540.0) % 360.0) - 180.0 // the short way round
      val dv = targetV - v
      val moving = dh != 0.0 || dv != 0.0
      h = (h + Math.signum(dh) * min(abs(dh), H_PER_TICK) + 360.0) % 360.0
      v += Math.signum(dv) * min(abs(dv), V_PER_TICK)
      if (moving) resolving = RESOLVE_TICKS else if (resolving > 0) resolving--
      show()
    }

    private fun show() {
      device["Horizontal"] = h
      device["Vertical"] = v
      device["SignalStrength"] = if (resolving > 0 || offset() > FIND_WITHIN) -1.0 else offset()
    }

    companion object {
      const val H_PER_TICK = 30.0
      const val V_PER_TICK = 15.0
      const val RESOLVE_TICKS = 4
      /** How close the dish must point to find the satellite at all. */
      const val FIND_WITHIN = 45.0
    }
  }

  @Test
  fun `the tracker finds the satellite, and starts again when the lever is flipped`() {
    for ((compact, stackCalls) in
        listOf(false, true).flatMap { c -> listOf(c to true, c to false) }) {
      val options = "compact=$compact, stackCalls=$stackCalls"
      val result = Compiler.compile(source, CompileOptions(compact, stackCalls))
      assertWithMessage("compile ($options)")
          .that(result)
          .isInstanceOf(CompileResult.Success::class.java)
      result as CompileResult.Success
      assertWithMessage("lines ($options)").that(result.lines).isAtMost(Ic10Limits.MAX_LINES)

      val dish = Dish(satelliteH = 137.0, satelliteV = 52.0, h = 300.0, v = 10.0)
      val lever = FakeDevice().apply { this["Open"] = 0.0 }
      val console = FakeDevice()
      val displays = List(3) { FakeDevice() }
      val emulator =
          Emulator(
              Program.parse(result.code),
              mapOf(
                  "d0" to dish.device,
                  "d1" to lever,
                  "d2" to displays[0],
                  "d3" to displays[1],
                  "d4" to displays[2],
                  "d5" to console,
              ),
          )
      val done = Hashes.str("Done!")
      fun runUntil(limit: Int, condition: () -> Boolean) {
        val until = emulator.ticks + limit
        while (!condition() && !emulator.halted && emulator.ticks < until) {
          emulator.tick()
          dish.update()
        }
      }

      runUntil(20_000) { console["Setting"] == done }
      assertWithMessage("done ($options)").that(console["Setting"]).isEqualTo(done)
      assertWithMessage("offset ($options)").that(dish.offset()).isAtMost(1.0)
      assertThat(console["Mode"]).isEqualTo(10.0)
      assertThat(console.writes).contains("Setting" to Hashes.str("Scan"))
      assertThat(console.writes).contains("Setting" to Hashes.str("Tuning"))
      // The offset display shows what the dish reads.
      assertThat(displays[0]["Setting"]).isAtMost(1.0)

      // It waits on the lever, and turns it back off before waiting.
      assertThat(lever.writes).contains("Open" to 0.0)
      val movesWhenDone = dish.moves
      runUntil(200) { false }
      assertWithMessage("waits ($options)").that(dish.moves).isEqualTo(movesWhenDone)

      lever["Open"] = 1.0
      runUntil(200) { dish.moves > movesWhenDone }
      assertWithMessage("starts again ($options)").that(dish.moves).isGreaterThan(movesWhenDone)
    }
  }
}
