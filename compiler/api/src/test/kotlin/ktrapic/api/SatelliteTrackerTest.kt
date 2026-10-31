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
 * - Setting is the dish's power in whole watts, kept within the dish's range (see [MAX_WATTS]).
 * - SignalStrength is the angle, in degrees, between where the dish points and the satellite. It
 *   reads -1 after any move or change of Setting until the dish has resolved the satellite again.
 *   Resolving starts once the dish stops and takes longer the further off it points and the less
 *   power it has, by the game's formula (see [resolveTicks]).
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

    /** Each heading and tilt the dish has been turned to, in order. */
    val targets = mutableListOf<Pair<Double, Double>>()

    /** For each of [targets], the offset where the dish pointed when it was told to turn there. */
    val offsetsBefore = mutableListOf<Double>()

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
          targets += targetH to targetV
          offsetsBefore += offset()
          resolving = TURNING
        }
        // A write sets the target; reads still give where the dish points.
        show()
      }
    }

    /** The angle between where the dish points and the satellite, in degrees. */
    fun offset(): Double = offsetAt(h, v)

    /** The angle between heading [h] and tilt [v] and the satellite, in degrees. */
    fun offsetAt(h: Double, v: Double): Double {
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
      device["SignalStrength"] = if (lost || resolving != 0) -1.0 else offset()
    }

    companion object {
      // The figures below are the game's defaults for a dish and a trader contact, which a prefab
      // or a contact's data may override.

      /** Degrees per tick: about 1.8 a second horizontally and 0.9 vertically. */
      const val H_PER_TICK = 0.9
      const val V_PER_TICK = 0.45
      /**
       * The range the dish keeps its Setting within, in watts. The class's default range tops out
       * at 200 W, but the medium dish's prefab evidently overrides it: in game, the maintainer's
       * medium dish took 7500 W when told 10000. So this assumes 7500, which is all the program's
       * POWER of 2000 needs to know: that the dish takes it as written.
       */
      const val MIN_WATTS = 50.0
      const val MAX_WATTS = 7500.0
      /** A contact resolves in the dish's base time when this many watts reach it. */
      const val WATTS_TO_RESOLVE = 100.0
      const val BASE_SECONDS = 10.0
      const val TURNING = -1

      /**
       * The share of its power the dish brings to bear on a contact [offset] degrees off: all of it
       * within 2 degrees, then falling in straight lines through 0.9 at 5, 0.1 at 22.5, 0.05 at 45,
       * 0.01 at 90 and 0.001 at 180, as the game's dish has it. At the program's POWER, that lets
       * the dish resolve a contact up to about 60 degrees off within the program's wait.
       */
      fun falloff(offset: Double): Double {
        val points =
            listOf(
                0.0 to 1.0,
                2.0 to 1.0,
                5.0 to 0.9,
                22.5 to 0.1,
                45.0 to 0.05,
                90.0 to 0.01,
                180.0 to 0.001,
            )
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
      assertWithMessage("tuned ($options)").that(texts).contains(HUNT)
      assertWithMessage("no sweep after tuning ($options)")
          .that(texts.subList(texts.indexOf(HUNT), texts.size))
          .doesNotContain(SWEEP)
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
      // The sweep first finds the satellite near the edge of what the dish can resolve in the
      // program's wait, so the hunt starts with 8 degree steps.
      val run = Run(options, Dish(satelliteH = 130.0, satelliteV = 2.0, h = 300.0, v = 10.0))
      val dish = run.dish

      run.runUntil(100_000) { run.console["Setting"] == DONE }
      run.assertDone()
      assertThat(run.console["Mode"]).isEqualTo(10.0)
      assertWithMessage("power ($options)").that(dish.power).isEqualTo(POWER)
      assertThat(run.texts()).contains(SWEEP)
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
    // Near the horizon; on it, so that moves down are clamped; near the zenith; and low, between
    // two of the sweep's tilts.
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

      run.runUntil(100_000) { run.console["Setting"] == HUNT }
      assertWithMessage("tuning ($options)").that(run.console["Setting"]).isEqualTo(HUNT)
      dish.lost = true
      val tuningAt = run.texts().size
      run.runUntil(100_000) { run.texts().drop(tuningAt).contains(SWEEP) }
      val afterLoss = run.texts().drop(tuningAt)
      assertWithMessage("sweeps again ($options)").that(afterLoss).contains(SWEEP)
      assertWithMessage("not done ($options)").that(afterLoss).doesNotContain(DONE)

      dish.lost = false
      run.runUntil(100_000) { run.console["Setting"] == DONE }
      run.assertDone()
    }
  }

  @Test
  fun `a move that resolves where the dish had yet to is kept`() {
    for (options in allOptions()) {
      // Losing the contact over the hunt's first move, up, and its undo leaves the dish reading -1
      // where the next line search, down, starts. Its first move resolves, and must count as
      // closer: the search goes on from there rather than back to where the dish could not resolve.
      val run = Run(options, Dish(satelliteH = 140.0, satelliteV = 3.0, h = 0.0, v = 45.0))
      val dish = run.dish

      run.runUntil(100_000) { run.console["Setting"] == HUNT }
      assertWithMessage("hunting ($options)").that(run.console["Setting"]).isEqualTo(HUNT)
      // The hunt's first move is made in the same tick as it says so.
      val start = dish.targets.size - 1
      dish.lost = true
      run.runUntil(1_000) { dish.targets.size > start + 2 }
      dish.lost = false
      val (from, up, undo, down) = dish.targets.subList(start - 1, start + 3).toList()
      assertWithMessage("up ($options)").that(up.first).isEqualTo(from.first)
      assertWithMessage("up ($options)").that(up.second).isGreaterThan(from.second)
      assertWithMessage("undone ($options)").that(undo).isEqualTo(from)
      assertWithMessage("down ($options)").that(down.first).isEqualTo(from.first)
      assertWithMessage("down ($options)").that(down.second).isLessThan(from.second)

      run.runUntil(1_000) { dish.targets.size > start + 3 }
      assertWithMessage("kept ($options)").that(dish.targets[start + 3]).isNotEqualTo(from)

      run.runUntil(100_000) { run.console["Setting"] == DONE }
      run.assertDone()
    }
  }

  @Test
  fun `a dish that lands close steps by at most half the offset and stops within a degree`() {
    for (options in allOptions()) {
      // The sweep's first position, 0, 12, is about 5 degrees from the satellite.
      val run = Run(options, Dish(satelliteH = 4.0, satelliteV = 15.5, h = 0.0, v = 45.0))
      val dish = run.dish

      run.runUntil(100_000) { run.console["Setting"] == DONE }
      run.assertDone()
      assertWithMessage("no sweep ($options)").that(run.texts()).doesNotContain(SWEEP)
      // The first target is the sweep's; the hunt's moves follow it. The hunt sizes its step from
      // the offset where it starts, at most half of it, rather than probing 8 degrees, and the
      // step never grows from there.
      assertWithMessage("hunted ($options)").that(dish.targets.size).isGreaterThan(1)
      val steps =
          (1 until dish.targets.size).map { i ->
            val (h0, v0) = dish.targets[i - 1]
            val (h1, v1) = dish.targets[i]
            maxOf(abs(((h1 - h0 + 540.0) % 360.0) - 180.0), abs(v1 - v0))
          }
      assertWithMessage("first step ($options)")
          .that(steps.first())
          .isAtMost(maxOf(1.0, dish.offsetsBefore[1] / 2.0))
      assertWithMessage("steps ($options)").that(steps.max()).isEqualTo(steps.first())
      // Once it reaches within a degree, it only finishes the line it is on: it goes on along it,
      // and back, but probes no other direction.
      val within = dish.targets.indexOfFirst { (h, v) -> dish.offsetAt(h, v) <= 1.0 }
      assertWithMessage("reached within a degree ($options)").that(within).isGreaterThan(0)
      val (h0, _) = dish.targets[within - 1]
      val (h1, v1) = dish.targets[within]
      val horizontal = h0 != h1
      for ((h, v) in dish.targets.drop(within)) {
        assertWithMessage("same line after ${dish.targets[within]} ($options)")
            .that(if (horizontal) v else h)
            .isEqualTo(if (horizontal) v1 else h1)
      }
    }
  }

  @Test
  fun `a step over the zenith that goes further off is undone to where the dish was`() {
    for (options in allOptions()) {
      // The contact appears 14 degrees below the dish as the sweep reaches 0, 84, so the first
      // move, up 7 (half the offset), carries over the zenith to 180, 89, which is further off,
      // so it is undone.
      val run = Run(options, Dish(satelliteH = 0.0, satelliteV = 70.0, h = 0.0, v = 45.0))
      val dish = run.dish
      dish.lost = true

      run.runUntil(100_000) { dish.targets.lastOrNull() == (0.0 to 84.0) }
      dish.lost = false
      run.runUntil(100_000) { run.console["Setting"] == DONE }
      run.assertDone()
      // The program writes the heading, then the tilt, so a move that changes both passes through
      // a target with only the heading changed.
      val over = dish.targets.indexOf(180.0 to 89.0)
      assertWithMessage("over the zenith ($options)").that(over).isGreaterThan(1)
      assertWithMessage("from ($options)")
          .that(dish.targets.subList(over - 2, over))
          .containsExactly(0.0 to 84.0, 180.0 to 84.0)
          .inOrder()
      assertWithMessage("undone to ($options)")
          .that(dish.targets.subList(over + 1, over + 3))
          .containsExactly(0.0 to 89.0, 0.0 to 84.0)
          .inOrder()
    }
  }

  @Test
  fun `a satellite just past the zenith, on the far side, is tuned in over the top`() {
    // The sweep resolves these from the near side of the zenith, so the hunt climbs towards it.
    for (options in allOptions()) {
      for ((h, v) in listOf(180.0 to 83.0, 180.0 to 85.0)) {
        val run = Run(options, Dish(satelliteH = h, satelliteV = v, h = 0.0, v = 45.0))
        run.runUntil(100_000) { run.console["Setting"] == DONE }
        assertWithMessage("satellite at $h, $v").that(run.console["Setting"]).isEqualTo(DONE)
        run.assertDone()
        run.assertNoSweepAfterTuning()
      }
    }
  }

  @Test
  fun `a dish that climbs to the zenith turns its heading towards the satellite`() {
    for (options in allOptions()) {
      // The contact appears as the sweep reaches 0, 84, a quarter turn of heading from it, so the
      // hunt climbs as high as the dish may tilt, where moving up or down along either side of the
      // zenith is further off. Only heading steps there, which still move the dish below the
      // zenith, bring it round to the satellite.
      val run = Run(options, Dish(satelliteH = 270.0, satelliteV = 88.0, h = 0.0, v = 45.0))
      val dish = run.dish
      dish.lost = true

      run.runUntil(100_000) { dish.targets.lastOrNull() == (0.0 to 84.0) }
      dish.lost = false
      run.runUntil(100_000) { run.console["Setting"] == DONE }
      run.assertDone()
      assertWithMessage("highest tilt ($options)")
          .that(dish.targets.maxOf { it.second })
          .isEqualTo(89.5)
    }
  }

  private companion object {
    /** The program's POWER, in watts. */
    const val POWER = 2000.0
    val DONE = Hashes.str("Done!")
    val SWEEP = Hashes.str("Sweep")
    val HUNT = Hashes.str("Hunt")
  }
}
