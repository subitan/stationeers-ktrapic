package ktrapic.emu

import com.google.common.truth.Truth.assertThat
import ktrapic.ic10.Program
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EmulatorTest {
  private fun run(text: String, vararg pins: Pair<String, FakeDevice>): Emulator =
      Emulator(Program.parse(text), pins.toMap()).runUntilHalt()

  @Test
  fun loopSumsOneToTen() {
    val emu =
        run(
            """
            |move r0 0 # sum
            |move r1 1 # i
            |loop:
            |add r0 r0 r1
            |add r1 r1 1
            |ble r1 10 loop
            """
                .trimMargin()
        )

    assertThat(emu.register("r0")).isEqualTo(55.0)
  }

  @Test
  fun callsReturnThroughRa() {
    val emu =
        run(
            """
            |move r0 3
            |jal square
            |move r2 r1
            |move r0 4
            |jal square
            |add r2 r2 r1
            |j end
            |square:
            |mul r1 r0 r0
            |j ra
            |end:
            """
                .trimMargin()
        )

    assertThat(emu.register("r2")).isEqualTo(25.0)
  }

  @Test
  fun recursiveCallSavesRaOnTheStack() {
    // factorial(5) with the argument in r0 and the result in r1.
    val emu =
        run(
            """
            |move r0 5
            |jal fact
            |j end
            |fact:
            |bgtz r0 recurse
            |move r1 1
            |j ra
            |recurse:
            |push ra
            |push r0
            |sub r0 r0 1
            |jal fact
            |pop r0
            |pop ra
            |mul r1 r1 r0
            |j ra
            |end:
            """
                .trimMargin()
        )

    assertThat(emu.register("r1")).isEqualTo(120.0)
    assertThat(emu.register("sp")).isEqualTo(0.0)
  }

  @Test
  fun stackPushPopPeekAndPoke() {
    val emu = run("push 1\npush 2\npeek r0\npop r1\npop r2\npoke 7 9\nget r3 db 7")

    assertThat(emu.register("r0")).isEqualTo(2.0)
    assertThat(emu.register("r1")).isEqualTo(2.0)
    assertThat(emu.register("r2")).isEqualTo(1.0)
    assertThat(emu.register("r3")).isEqualTo(9.0)
    assertThat(emu.stack[7]).isEqualTo(9.0)
  }

  @Test
  fun stackUnderflowIsARuntimeError() {
    val e = assertThrows<Ic10RuntimeException> { run("yield\npop r0") }
    assertThat(e.line).isEqualTo(1)
    assertThat(e).hasMessageThat().contains("stack underflow")
  }

  @Test
  fun readsAndWritesDevicesThroughAliases() {
    val sensor = FakeDevice().apply { this["Temperature"] = 300.0 }
    val heater = FakeDevice()
    val emu =
        run(
            """
            |alias sensor d0
            |alias heater d1
            |define TARGET 293
            |l r0 sensor Temperature
            |slt r1 r0 TARGET
            |s heater On r1
            |sub r2 TARGET 3
            |s heater Setting r2
            """
                .trimMargin(),
            "d0" to sensor,
            "d1" to heater,
        )

    assertThat(emu.register("r1")).isEqualTo(0.0)
    assertThat(heater.writes).containsExactly("On" to 0.0, "Setting" to 290.0).inOrder()
  }

  @Test
  fun slotsReadAndWrite() {
    val sorter = FakeDevice().apply { slot(0)["Occupied"] = 1.0 }
    val emu = run("ls r0 d0 0 Occupied\nss d0 1 Lock 1", "d0" to sorter)

    assertThat(emu.register("r0")).isEqualTo(1.0)
    assertThat(sorter.slot(1)["Lock"]).isEqualTo(1.0)
  }

  @Test
  fun missingDeviceIsAnErrorAndBranchable() {
    val emu = run("bdns d2 skip\nmove r0 1\nskip:\nsdse r1 d2")
    assertThat(emu.register("r0")).isEqualTo(0.0)
    assertThat(emu.register("r1")).isEqualTo(0.0)

    assertThrows<Ic10RuntimeException> { run("l r0 d2 Setting") }
  }

  @Test
  fun batchReadsAndWritesByHash() {
    val a = FakeDevice("StructureWallLight", "Hall").apply { this["Power"] = 10.0 }
    val b = FakeDevice("StructureWallLight", "Kitchen").apply { this["Power"] = 30.0 }
    val other = FakeDevice("StructureBattery").apply { this["Power"] = 99.0 }
    val emu =
        Emulator(
                Program.parse(
                    """
                    |define LIGHT HASH("StructureWallLight")
                    |lb r0 LIGHT Power Average
                    |lb r1 LIGHT Power Sum
                    |lbn r2 LIGHT HASH("Kitchen") Power Maximum
                    |sb LIGHT On 1
                    |sbn LIGHT HASH("Hall") On 0
                    """
                        .trimMargin()
                ),
                network = listOf(a, b, other),
            )
            .runUntilHalt()

    assertThat(emu.register("r0")).isEqualTo(20.0)
    assertThat(emu.register("r1")).isEqualTo(40.0)
    assertThat(emu.register("r2")).isEqualTo(30.0)
    assertThat(a["On"]).isEqualTo(0.0)
    assertThat(b["On"]).isEqualTo(1.0)
    assertThat(other["On"]).isNull()
  }

  @Test
  fun yieldEndsTheTick() {
    val emu = Emulator(Program.parse("loop:\nadd r0 r0 1\nyield\nj loop"))

    emu.runTicks(5)

    assertThat(emu.register("r0")).isEqualTo(5.0)
    assertThat(emu.halted).isFalse()
  }

  @Test
  fun tickRunsAtMost128Lines() {
    // Without a yield the chip still stops after 128 lines, labels included.
    val emu = Emulator(Program.parse("loop:\nadd r0 r0 1\nj loop"))

    emu.tick()

    assertThat(emu.steps).isEqualTo(128)
    // 128 lines: 43 passes of label, add, j, minus the final j.
    assertThat(emu.register("r0")).isEqualTo(43.0)
  }

  @Test
  fun sleepSkipsTicks() {
    val emu = Emulator(Program.parse("loop:\nadd r0 r0 1\nsleep 1\nj loop"))

    emu.runTicks(1)
    assertThat(emu.register("r0")).isEqualTo(1.0)
    emu.runTicks(2)
    assertThat(emu.register("r0")).isEqualTo(2.0)
    emu.runTicks(2)
    assertThat(emu.register("r0")).isEqualTo(3.0)
  }

  @Test
  fun relativeBranches() {
    val emu = run("move r0 2\nbrgtz r0 2\nmove r1 1\nmove r2 1\njr 2\nmove r3 1")

    assertThat(emu.register("r1")).isEqualTo(0.0)
    assertThat(emu.register("r2")).isEqualTo(1.0)
    assertThat(emu.register("r3")).isEqualTo(0.0)
  }

  @Test
  fun branchAndLinkSetsRa() {
    val emu = run("move r0 1\nbeqal r0 1 sub\nj end\nsub:\nmove r1 ra\nj ra\nend:")

    assertThat(emu.register("r1")).isEqualTo(2.0)
  }

  @Test
  fun maths() {
    val emu =
        run(
            """
            |mod r0 -1 3
            |round r1 2.5
            |round r2 3.5
            |trunc r3 -1.7
            |select r4 0 10 20
            |sap r5 1 1.0000001 0.001
            |and r6 12 10
            |sll r7 1 4
            |ext r8 %110100 2 3
            |div r9 1 0
            """
                .trimMargin()
        )

    assertThat(emu.register("r0")).isEqualTo(2.0)
    assertThat(emu.register("r1")).isEqualTo(2.0)
    assertThat(emu.register("r2")).isEqualTo(4.0)
    assertThat(emu.register("r3")).isEqualTo(-1.0)
    assertThat(emu.register("r4")).isEqualTo(20.0)
    assertThat(emu.register("r5")).isEqualTo(1.0)
    assertThat(emu.register("r6")).isEqualTo(8.0)
    assertThat(emu.register("r7")).isEqualTo(16.0)
    assertThat(emu.register("r8")).isEqualTo(5.0)
    assertThat(emu.register("r9")).isEqualTo(Double.POSITIVE_INFINITY)
  }

  @Test
  fun hcfHalts() {
    val emu = Emulator(Program.parse("hcf"))
    assertThrows<Ic10RuntimeException> { emu.tick() }
    assertThat(emu.halted).isTrue()
  }

  @Test
  fun compactedProgramBehavesTheSame() {
    val text =
        """
        |# counts down from 5, storing into db Setting each tick
        |alias display db
        |move r0 5
        |top:
        |s display Setting r0 # show it
        |yield
        |
        |sub r0 r0 1
        |brgtz r0 -4
        |s display On 0
        """
            .trimMargin()
    fun writes(program: Program): List<Pair<String, Double>> {
      val db = FakeDevice()
      Emulator(program, mapOf("db" to db)).runUntilHalt()
      return db.writes
    }

    val program = Program.parse(text)
    assertThat(writes(program.compacted())).isEqualTo(writes(program))
    assertThat(writes(program))
        .containsExactly(
            "Setting" to 5.0,
            "Setting" to 4.0,
            "Setting" to 3.0,
            "Setting" to 2.0,
            "Setting" to 1.0,
            "On" to 0.0,
        )
        .inOrder()
  }
}
