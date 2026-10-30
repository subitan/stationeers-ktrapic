package ktrapic.api

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
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
 * Runs `test/cases/satellite_tracker.ic10.kts` against a simulated dish that models the parts of
 * the game's dish the program depends on:
 * - Writing Horizontal and Vertical sets where the dish turns to: Horizontal wraps into 0 to 360,
 *   Vertical is kept within 0 to 90. Writing the target it already has does nothing. Reading them
 *   gives where it points now, which follows the target the short way round at about the game's
 *   turn rate.
 * - SignalStrength is the angle, in degrees, between where the dish points and the satellite. It
 *   reads -1 while the satellite is too far off to find, and after any move until the dish has
 *   resolved it again. Resolving starts once the dish stops and takes longer the further off it
 *   points and the less power its Setting gives it, roughly as in the game.
 * - The contact can be lost, after which SignalStrength reads -1 until it comes back.
 */
class SatelliteTrackerTest {
  private val source =
      File(
              System.getenv("TEST_SRCDIR"),
              System.getenv("TEST_WORKSPACE") + "/test/cases/satellite_tracker.ic10.kts",
          )
          .readText()

  private class Dish(
      val satelliteH: Double,
      val satelliteV: Double,
      h: Double,
      v: Double,
      val power: Double,
  ) {
    val device = FakeDevice()
    private var targetH = h
    private var targetV = v
    private var h = h
    private var v = v
    /** Ticks left until the dish resolves; [TURNING] while it has yet to stop. */
    private var resolving = 0
    /** The number of writes that changed where the dish turns to. */
    var moves = 0
      private set

    /** While true, the dish has no contact to resolve. */
    var lost = false
      set(value) {
        field = value
        show()
      }

    init {
      device["Setting"] = power
      resolving = resolveTicks()
      show()
      device.onWrite = { type, value ->
        val (oldH, oldV) = targetH to targetV
        when (type) {
          "Horizontal" -> targetH = ((value % 360.0) + 360.0) % 360.0
          "Vertical" -> targetV = value.coerceIn(0.0, 90.0)
        }
        if (targetH != oldH || targetV != oldV) {
          moves++
          resolving = TURNING
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

    /**
     * How many ticks the dish takes to resolve the satellite from where it points: about ten
     * seconds times the square of 100 W over the power it brings to bear, which is its Setting
     * scaled down the further off it points.
     */
    private fun resolveTicks(): Int {
      val brought = power * falloff(offset())
      val seconds = 10.0 * (100.0 / brought) * (100.0 / brought)
      return ceil(seconds / Emulator.SECONDS_PER_TICK).toInt()
    }

    /** Called between ticks: the dish turns toward its target, and resolves once it stops. */
    fun update() {
      val dh = ((targetH - h + 540.0) % 360.0) - 180.0 // the short way round
      val dv = targetV - v
      val moving = dh != 0.0 || dv != 0.0
      h = (h + Math.signum(dh) * min(abs(dh), H_PER_TICK) + 360.0) % 360.0
      v += Math.signum(dv) * min(abs(dv), V_PER_TICK)
      resolving =
          when {
            moving -> TURNING
            resolving == TURNING -> resolveTicks()
            else -> maxOf(0, resolving - 1)
          }
      show()
    }

    private fun show() {
      device["Horizontal"] = h
      device["Vertical"] = v
      device["SignalStrength"] =
          if (lost || resolving != 0 || offset() > FIND_WITHIN) -1.0 else offset()
    }

    companion object {
      /** Degrees per tick: about 1.8 a second horizontally and 0.9 vertically. */
      const val H_PER_TICK = 0.9
      const val V_PER_TICK = 0.45
      const val TURNING = -1
      /** How close the dish must point to find the satellite at all. */
      const val FIND_WITHIN = 45.0

      /** The share of its power the dish brings to bear on a contact [offset] degrees off. */
      fun falloff(offset: Double): Double {
        val points = listOf(0.0 to 1.0, 2.0 to 1.0, 10.0 to 0.67, 20.0 to 0.2, 45.0 to 0.05)
        if (offset >= points.last().first) return points.last().second
        val i = points.indexOfFirst { it.first > offset }
        val (x0, y0) = points[i - 1]
        val (x1, y1) = points[i]
        return y0 + (y1 - y0) * (offset - x0) / (x1 - x0)
      }
    }
  }

  private inner class Run(val options: CompileOptions, val dish: Dish) {
    val lever = FakeDevice().apply { this["Open"] = 0.0 }
    val console = FakeDevice()
    val displays = List(3) { FakeDevice() }
    val emulator: Emulator

    init {
      val result = Compiler.compile(source, options)
      assertWithMessage("compile ($options)")
          .that(result)
          .isInstanceOf(CompileResult.Success::class.java)
      result as CompileResult.Success
      assertWithMessage("lines ($options)").that(result.lines).isAtMost(Ic10Limits.MAX_LINES)
      emulator =
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
    }

    fun runUntil(limit: Int, condition: () -> Boolean) {
      val until = emulator.ticks + limit
      while (!condition() && !emulator.halted && emulator.ticks < until) {
        emulator.tick()
        dish.update()
      }
    }

    /** The texts written to the console, in order. */
    fun texts(): List<Double> = console.writes.filter { it.first == "Setting" }.map { it.second }

    /** Checks the program has said it is done, with the dish tuned to within a degree. */
    fun assertDone() {
      assertWithMessage("done ($options)").that(console["Setting"]).isEqualTo(DONE)
      assertWithMessage("offset ($options)").that(dish.offset()).isAtMost(1.0)
      // The offset display shows what the dish reads: a real offset, not -1.
      assertWithMessage("offset display ($options)").that(displays[0]["Setting"]).isAtLeast(0.0)
      assertWithMessage("offset display ($options)").that(displays[0]["Setting"]).isAtMost(1.0)
    }
  }

  private fun allOptions() =
      listOf(false, true)
          .flatMap { c -> listOf(c to true, c to false) }
          .map { (c, s) ->
            CompileOptions(c, s)
          }

  @Test
  fun `the tracker finds the satellite, and starts again when the lever is flipped`() {
    for (options in allOptions()) {
      // Pointing below the satellite with this little power, the dish resolves it only within
      // about 10 degrees, so some of the program's first nudges take it out of reach and it has
      // to move back.
      val run =
          Run(
              options,
              Dish(satelliteH = 125.0, satelliteV = 38.0, h = 300.0, v = 10.0, power = 230.0),
          )
      val dish = run.dish

      run.runUntil(100_000) { run.console["Setting"] == DONE }
      run.assertDone()
      assertThat(run.console["Mode"]).isEqualTo(10.0)
      val texts = run.texts()
      assertThat(texts).contains(SCAN)
      assertThat(texts).contains(TUNING)
      // Once it has found the satellite it tunes in without sweeping again.
      assertWithMessage("no sweep after tuning ($options)")
          .that(texts.subList(texts.indexOf(TUNING), texts.size))
          .doesNotContain(SCAN)

      // It waits on the lever, and turns it back off before waiting.
      assertThat(run.lever.writes).contains("Open" to 0.0)
      val movesWhenDone = dish.moves
      run.runUntil(200) { false }
      assertWithMessage("waits ($options)").that(dish.moves).isEqualTo(movesWhenDone)

      run.lever["Open"] = 1.0
      run.runUntil(200) { dish.moves > movesWhenDone }
      assertWithMessage("starts again ($options)").that(dish.moves).isGreaterThan(movesWhenDone)
    }
  }

  @Test
  fun `a contact lost while tuning sends it back to sweeping, not to done`() {
    for (options in allOptions()) {
      val run =
          Run(
              options,
              Dish(satelliteH = 137.0, satelliteV = 52.0, h = 300.0, v = 10.0, power = 1000.0),
          )
      val dish = run.dish

      run.runUntil(100_000) { run.console["Setting"] == TUNING }
      assertWithMessage("tuning ($options)").that(run.console["Setting"]).isEqualTo(TUNING)
      dish.lost = true
      val tuningAt = run.texts().size
      run.runUntil(100_000) { run.texts().drop(tuningAt).contains(SCAN) }
      val afterLoss = run.texts().drop(tuningAt)
      assertWithMessage("sweeps again ($options)").that(afterLoss).contains(SCAN)
      assertWithMessage("not done ($options)").that(afterLoss).doesNotContain(DONE)

      dish.lost = false
      run.runUntil(100_000) { run.console["Setting"] == DONE }
      run.assertDone()
    }
  }

  private companion object {
    val DONE = Hashes.str("Done!")
    val SCAN = Hashes.str("Scan")
    val TUNING = Hashes.str("Tuning")
  }
}
