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
import ktrapic.ic10.Program
import org.junit.jupiter.api.Test

/**
 * Runs `test/cases/satellite_tracker.ic10.kts`, a port of a chip from the game, against a simulated
 * dish: its signal strength falls off with the angle between where it points and the satellite, and
 * reads -1 from each move until the end of the next tick, as a moving dish does.
 */
class SatelliteTrackerTest {
  private val source =
      File(
              System.getenv("TEST_SRCDIR"),
              System.getenv("TEST_WORKSPACE") + "/test/cases/satellite_tracker.ic10.kts",
          )
          .readText()

  private class Dish(val satelliteH: Double, val satelliteV: Double) {
    val device = FakeDevice()
    private var movedThisTick = false

    init {
      device.onWrite = { type, _ ->
        if (type == "Horizontal" || type == "Vertical") {
          movedThisTick = true
          device["SignalStrength"] = -1.0
        }
      }
    }

    fun strength(): Double {
      val dh = device["Horizontal"]!! - satelliteH
      val dv = device["Vertical"]!! - satelliteV
      return max(0.0, 100.0 - 3.0 * sqrt(dh * dh + dv * dv))
    }

    /** Called between ticks: a dish the program turned this tick still reads -1 for the next. */
    fun update() {
      device["SignalStrength"] = if (movedThisTick) -1.0 else strength()
      movedThisTick = false
    }
  }

  @Test
  fun `the tracker finds the satellite`() {
    for ((compact, stackCalls) in
        listOf(false, true).flatMap { c -> listOf(c to true, c to false) }) {
      val result = Compiler.compile(source, CompileOptions(compact, stackCalls))
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
              Program.parse(result.code),
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

      assertWithMessage("halted (compact=$compact, stackCalls=$stackCalls)")
          .that(emulator.halted)
          .isTrue()
      assertThat(dish.strength()).isAtLeast(97.0)
      assertThat(console["Setting"]).isEqualTo(Hashes.str("Done!"))
      assertThat(console["Mode"]).isEqualTo(10.0)
      // moveDish waited for at least one move to finish.
      assertThat(console.writes).contains("Setting" to Hashes.str("move."))
    }
  }
}
