package ktrapic.api

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sign
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
 *   turn rate. Idle reads 1 once it has stopped, and 0 from a write that moves it until then.
 * - Setting is the dish's power in whole watts, kept within the dish's range.
 * - SignalStrength is the angle, in degrees, between where the dish points and the satellite. It
 *   reads -1 while the satellite is too far off to find, and after any move or change of Setting
 *   until the dish has resolved it again. Resolving starts once the dish stops and takes longer the
 *   further off it points and the less power it has, by the game's formula (see [resolveTicks]).
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
      setting: Double = MIN_WATTS,
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

    val power: Double
      get() = device["Setting"]!!

    init {
      device["Setting"] = setting
      resolving = resolveTicks()
      show()
      device.onWrite = { type, value ->
        val (oldH, oldV) = targetH to targetV
        when (type) {
          "Horizontal" -> targetH = ((value % 360.0) + 360.0) % 360.0
          "Vertical" -> targetV = value.coerceIn(0.0, 90.0)
          "Setting" -> {
            device["Setting"] = value.toInt().toDouble().coerceIn(MIN_WATTS, MAX_WATTS)
            if (resolving != TURNING) resolving = resolveTicks()
          }
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
     * How many ticks the dish takes to resolve the satellite from where it points. In the game a
     * contact's resolve timer starts at the dish's base time scaled by the contact's watts to
     * resolve over the watts the dish brings to bear, and then counts down at that same ratio per
     * second, so the time goes with the square of the ratio. The watts brought to bear are the
     * Setting times [falloff].
     */
    private fun resolveTicks(): Int {
      val ratio = WATTS_TO_RESOLVE / (power * falloff(offset()))
      return ceil(BASE_SECONDS * ratio * ratio / Emulator.SECONDS_PER_TICK).toInt()
    }

    /** Called between ticks: the dish turns toward its target, and resolves once it stops. */
    fun update() {
      val moving = h != targetH || v != targetV
      val dh = ((targetH - h + 540.0) % 360.0) - 180.0 // the short way round
      val dv = targetV - v
      h = if (abs(dh) <= H_PER_TICK) targetH else (h + sign(dh) * H_PER_TICK + 360.0) % 360.0
      v = if (abs(dv) <= V_PER_TICK) targetV else v + sign(dv) * V_PER_TICK
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
      device["Idle"] = if (h == targetH && v == targetV) 1.0 else 0.0
      device["SignalStrength"] =
          if (lost || resolving != 0 || offset() > FIND_WITHIN) -1.0 else offset()
    }

    companion object {
      // The figures below are the game's defaults for a dish and a trader contact, which a prefab
      // or a contact's data may override.

      /** Degrees per tick: about 1.8 a second horizontally and 0.9 vertically. */
      const val H_PER_TICK = 0.9
      const val V_PER_TICK = 0.45
      /** The range the dish keeps its Setting within, in watts. */
      const val MIN_WATTS = 50.0
      const val MAX_WATTS = 200.0
      /** A contact resolves in the dish's base time when this many watts reach it. */
      const val WATTS_TO_RESOLVE = 100.0
      const val BASE_SECONDS = 10.0
      const val TURNING = -1
      /** How close the dish must point to find the satellite at all. */
      const val FIND_WITHIN = 45.0

      /**
       * The share of its power the dish brings to bear on a contact [offset] degrees off: all of it
       * within 2 degrees, then falling in straight lines through 0.9 at 5, 0.1 at 22.5 and 0.05 at
       * 45, as the game's dish has it.
       */
      fun falloff(offset: Double): Double {
        val points = listOf(0.0 to 1.0, 2.0 to 1.0, 5.0 to 0.9, 22.5 to 0.1, 45.0 to 0.05)
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

    /** Checks that, once it found the satellite, it tuned in without sweeping again. */
    fun assertNoSweepAfterTuning() {
      val texts = texts()
      assertWithMessage("tuned ($options)").that(texts).contains(TUNING)
      assertWithMessage("no sweep after tuning ($options)")
          .that(texts.subList(texts.indexOf(TUNING), texts.size))
          .doesNotContain(SCAN)
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
      // The sweep first finds the satellite about 15 degrees off, near the edge of what the dish
      // can resolve in the program's wait, so its first nudge down takes it out of reach and has
      // to be undone.
      val run = Run(options, Dish(satelliteH = 130.0, satelliteV = 2.0, h = 300.0, v = 10.0))
      val dish = run.dish

      run.runUntil(100_000) { run.console["Setting"] == DONE }
      run.assertDone()
      assertThat(run.console["Mode"]).isEqualTo(10.0)
      assertWithMessage("full power ($options)").that(dish.power).isEqualTo(Dish.MAX_WATTS)
      assertThat(run.texts()).contains(SCAN)
      run.assertNoSweepAfterTuning()

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
  fun `the sweep finds a satellite low or high in the sky`() {
    // Near the horizon; on it, straight below a sweep position, so that the first nudge, up, loses
    // the contact; near the zenith; and low where a sweep at one tilt would miss it.
    val satellites = listOf(200.0 to 3.0, 120.0 to 0.0, 75.0 to 85.0, 353.0 to 18.5)
    for (options in allOptions()) {
      for ((h, v) in satellites) {
        val run = Run(options, Dish(satelliteH = h, satelliteV = v, h = 0.0, v = 45.0))
        run.runUntil(100_000) { run.console["Setting"] == DONE }
        assertWithMessage("satellite at $h, $v").that(run.console["Setting"]).isEqualTo(DONE)
        run.assertDone()
        run.assertNoSweepAfterTuning()
      }
    }
  }

  @Test
  fun `a contact lost while tuning sends it back to sweeping, not to done`() {
    for (options in allOptions()) {
      val run = Run(options, Dish(satelliteH = 137.0, satelliteV = 52.0, h = 300.0, v = 10.0))
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
