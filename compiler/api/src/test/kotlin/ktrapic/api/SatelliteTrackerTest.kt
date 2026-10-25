package ktrapic.api

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import kotlin.math.max
import kotlin.math.sqrt
import ktrapic.emu.Emulator
import ktrapic.emu.FakeDevice
import ktrapic.ic10.Hashes
import ktrapic.ic10.Ic10Limits
import org.junit.jupiter.api.Test

/**
 * Runs `test/cases/satellite_tracker.kts`, a port of a chip from the game, against a simulated
 * dish: its signal strength falls off with the angle between where it points and the satellite, and
 * reads -1 for a tick after each move, as a moving dish does.
 */
class SatelliteTrackerTest {
  private val source =
      File(
              System.getenv("TEST_SRCDIR"),
              System.getenv("TEST_WORKSPACE") + "/test/cases/satellite_tracker.kts",
          )
          .readText()

  private class Dish(val satelliteH: Double, val satelliteV: Double) {
    val device = FakeDevice()
    private var seenWrites = 0

    fun strength(): Double {
      val dh = device["Horizontal"]!! - satelliteH
      val dv = device["Vertical"]!! - satelliteV
      return max(0.0, 100.0 - 3.0 * sqrt(dh * dh + dv * dv))
    }

    /** Called between ticks: a dish the program just turned reports -1 for one tick. */
    fun update() {
      val moved = device.writes.size > seenWrites
      seenWrites = device.writes.size
      device["SignalStrength"] = if (moved) -1.0 else strength()
    }
  }

  @Test
  fun `the tracker finds the satellite`() {
    for (compact in listOf(false, true)) {
      val result = Compiler.compile(source, CompileOptions(compact = compact))
      assertWithMessage("compile").that(result).isInstanceOf(CompileResult.Success::class.java)
      result as CompileResult.Success
      if (compact) assertThat(result.lines).isAtMost(Ic10Limits.MAX_LINES)

      val dish = Dish(satelliteH = 137.0, satelliteV = 52.0)
      dish.device["Horizontal"] = 300.0
      dish.device["Vertical"] = 10.0
      dish.device["SignalStrength"] = -1.0
      val console = FakeDevice()
      val emulator =
          Emulator(
              result.program,
              mapOf(
                  "d0" to dish.device,
                  "d2" to FakeDevice(),
                  "d3" to FakeDevice(),
                  "d5" to console,
              ),
          )
      while (!emulator.halted && emulator.ticks < 20_000) {
        emulator.tick()
        dish.update()
      }

      assertWithMessage("halted (compact=$compact)").that(emulator.halted).isTrue()
      assertThat(dish.strength()).isAtLeast(97.0)
      assertThat(console["Setting"]).isEqualTo(Hashes.str("Done!"))
      assertThat(console["Mode"]).isEqualTo(10.0)
    }
  }
}
