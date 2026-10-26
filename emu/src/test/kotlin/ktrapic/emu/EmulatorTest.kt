package ktrapic.emu

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import ktrapic.ic10.Constants
import ktrapic.ic10.Line
import ktrapic.ic10.Name
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
  fun modAddsTheDivisorOnceToANegativeRemainder() {
    val emu = run("mod r0 5 -3\nmod r1 -1 -3\nmod r2 -1 pinf\nmod r3 7 3")

    assertThat(emu.register("r0")).isEqualTo(2.0)
    assertThat(emu.register("r1")).isEqualTo(-4.0)
    assertThat(emu.register("r2")).isEqualTo(Double.POSITIVE_INFINITY)
    assertThat(emu.register("r3")).isEqualTo(1.0)
  }

  @Test
  fun maxAndMinReturnTheSecondOperandForZerosOfDifferentSign() {
    val emu =
        run(
            """
            |mul r1 0 -1
            |max r2 0 r1
            |max r3 r1 0
            |min r4 r1 0
            |min r5 0 r1
            |clamp r6 r1 0 1
            |clamp r7 0 -1 r1
            |div r8 0 0
            |max r9 1 r8
            |min r10 r8 1
            """
                .trimMargin()
        )

    assertThat(emu.register("r2")).isEqualTo(-0.0)
    assertThat(emu.register("r3")).isEqualTo(0.0)
    assertThat(emu.register("r4")).isEqualTo(0.0)
    assertThat(emu.register("r5")).isEqualTo(-0.0)
    assertThat(emu.register("r6")).isEqualTo(0.0)
    assertThat(emu.register("r7")).isEqualTo(-0.0)
    assertThat(emu.register("r9")).isNaN()
    assertThat(emu.register("r10")).isNaN()
  }

  @Test
  fun bitwiseOperationsWorkOn53BitIntegers() {
    val emu =
        run(
            """
            |srl r0 -1 1
            |sll r1 1 53
            |sll r2 1 63
            |not r3 0
            |rol r4 1 1
            |ror r5 1 1
            |ins r6 5 0 3
            |move r7 -1
            |ins r7 0 0 4
            |clamp r8 15 0 10
            |sgn r9 -2.5
            """
                .trimMargin()
        )

    assertThat(emu.register("r0")).isEqualTo(9007199254740991.0)
    assertThat(emu.register("r1")).isEqualTo(-9007199254740992.0)
    assertThat(emu.register("r2")).isEqualTo(0.0)
    assertThat(emu.register("r3")).isEqualTo(-1.0)
    assertThat(emu.register("r4")).isEqualTo(2.0)
    assertThat(emu.register("r5")).isEqualTo(Math.pow(2.0, 53.0) * -1)
    assertThat(emu.register("r6")).isEqualTo(5.0)
    // ins works on the unsigned 53-bit value, so the result is never negative.
    assertThat(emu.register("r7")).isEqualTo(9007199254740976.0)
    assertThat(emu.register("r8")).isEqualTo(10.0)
    assertThat(emu.register("r9")).isEqualTo(-1.0)
    assertThrows<Ic10RuntimeException> { run("and r0 pinf 1") }
    assertThrows<Ic10RuntimeException> { run("ext r0 1 0 54") }
  }

  @Test
  fun notApproximatelyIsFalseForNaN() {
    val emu = run("div r9 0 0\nsna r0 r9 1 0.1\nsap r1 r9 1 0.1\nsnaz r2 r9 0.1")

    assertThat(emu.register("r0")).isEqualTo(0.0)
    assertThat(emu.register("r1")).isEqualTo(0.0)
    assertThat(emu.register("r2")).isEqualTo(0.0)
  }

  @Test
  fun emptyBatchesReadAsTheGameComputesThem() {
    val battery = FakeDevice(prefab = "StructureBattery", name = null).also { it["Charge"] = 5.0 }
    val emu =
        Emulator(
                Program.parse(
                    """
                    |define Missing HASH("Nothing")
                    |lb r0 Missing Charge Average
                    |lb r1 Missing Charge Sum
                    |lb r2 Missing Charge Minimum
                    |lb r3 Missing Charge Maximum
                    |lb r4 HASH("StructureBattery") Charge Count
                    |lbn r5 HASH("StructureBattery") HASH("Other") Charge Count
                    |lbns r6 HASH("StructureBattery") HASH("Other") 0 Quantity Count
                    """
                        .trimMargin()
                ),
                network = listOf(battery),
            )
            .runUntilHalt()

    assertThat(emu.register("r0")).isNaN()
    assertThat(emu.register("r1")).isEqualTo(0.0)
    assertThat(emu.register("r2")).isEqualTo(0.0)
    assertThat(emu.register("r3")).isEqualTo(Double.NEGATIVE_INFINITY)
    assertThat(emu.register("r4")).isEqualTo(1.0)
    // The game counts by prefab alone for lbn ... Count and lbns ... Count.
    assertThat(emu.register("r5")).isEqualTo(1.0)
    assertThat(emu.register("r6")).isEqualTo(1.0)
  }

  @Test
  fun aNegativeJumpTargetPausesThenContinuesThere() {
    val emu = Emulator(Program.parse("move r0 1\nj -3\nmove r0 2\nmove r1 3"))

    emu.tick()
    assertThat(emu.register("r1")).isEqualTo(0.0)
    emu.tick()
    assertThat(emu.register("r0")).isEqualTo(1.0)
    assertThat(emu.register("r1")).isEqualTo(3.0)
  }

  @Test
  fun aJumpTargetInARegisterIsRoundedHalfToEven() {
    // 3.5 rounds to 4 (the move of 7); truncating would give 3, which also sets r2.
    val emu = run("move r1 3.5\nj r1\nmove r0 1\nmove r2 2\nmove r0 7")

    assertThat(emu.register("r0")).isEqualTo(7.0)
    assertThat(emu.register("r2")).isEqualTo(0.0)
  }

  @Test
  fun branchAndLinkReadsItsTargetBeforeSettingRa() {
    val emu = run("move ra 4\nbeqal 0 0 ra\nhcf\nhcf\nmove r0 1")

    assertThat(emu.register("r0")).isEqualTo(1.0)
    assertThat(emu.register("ra")).isEqualTo(2.0)
  }

  @Test
  fun aFailedPopStillLowersSp() {
    val emu = Emulator(Program.parse("pop r0"))
    assertThrows<Ic10RuntimeException> { emu.tick() }
    assertThat(emu.register("sp")).isEqualTo(-1.0)
  }

  @Test
  fun sleepingForNaNNeverEnds() {
    val emu = Emulator(Program.parse("div r0 0 0\nsleep r0\nmove r1 1")).runTicks(50)

    assertThat(emu.halted).isFalse()
    assertThat(emu.register("r1")).isEqualTo(0.0)
  }

  @Test
  fun deviceValidityBranchesNeedADevice() {
    assertThrows<Ic10RuntimeException> { run("bdnvl d0 Setting 0") }
    val d0 = FakeDevice().also { it["Setting"] = 1.0 }
    assertThat(run("bdnvl d0 Setting 2\nmove r0 1", "d0" to d0).register("r0")).isEqualTo(1.0)
  }

  @Test
  fun labelIsAnOlderSpellingOfAliasWithTheDeviceFirst() {
    val d0 = FakeDevice()
    run("label d0 Sensor\ns Sensor Setting 3", "d0" to d0)

    assertThat(d0["Setting"]).isEqualTo(3.0)
  }

  @Test
  fun defineOfAnotherNameIsNaN() {
    assertThat(run("define x y\nmove r0 x").register("r0")).isNaN()
  }

  @Test
  fun constantsIgnoreCase() {
    val emu = run("define x PI\nmove r0 x\nmove r1 Tau\nmove r2 PINF")

    assertThat(emu.register("r0")).isEqualTo(Math.PI)
    assertThat(emu.register("r1")).isEqualTo(Math.PI * 2.0)
    assertThat(emu.register("r2")).isEqualTo(Double.POSITIVE_INFINITY)
  }

  @Test
  fun nanIsAnErrorAsAnOperandButNotAsADefine() {
    for (text in listOf("yield\nmove r0 nan", "yield\nmove r0 NaN", "yield\nbeq r0 nan 0")) {
      val e = assertThrows<Ic10RuntimeException>(text) { run(text) }
      assertThat(e.line).isEqualTo(1)
    }
    assertThat(run("define x nan\nmove r0 x").register("r0")).isNaN()
    assertThat(run("define x NaN\nmove r0 x").register("r0")).isNaN()
  }

  @Test
  fun raAndSpTruncateAsJumpTargetsAndAddresses() {
    // ra and sp are aliases in the game, so 3.5 truncates to 3, where a plain register rounds to 4.
    val jump = run("move ra 3.5\nj ra\nmove r0 1\nmove r0 2\nmove r1 9")
    assertThat(jump.register("r0")).isEqualTo(2.0)
    assertThat(jump.register("r1")).isEqualTo(9.0)

    val poke = run("move sp 3.5\npoke sp 7\nmove sp 0\nget r0 db 3\nget r1 db 4")
    assertThat(poke.register("r0")).isEqualTo(7.0)
    assertThat(poke.register("r1")).isEqualTo(0.0)
  }

  @Test
  fun aNaNShiftCountCountsAsZero() {
    val emu = run("div r9 0 0\nsll r0 3 r9\nsra r1 -8 r9\nrol r2 5 r9")

    assertThat(emu.register("r0")).isEqualTo(3.0)
    assertThat(emu.register("r1")).isEqualTo(-8.0)
    assertThat(emu.register("r2")).isEqualTo(5.0)
  }

  @Test
  fun batchReadsVisitDevicesLastToFirst() {
    val nan = FakeDevice("StructureBattery").also { it["Charge"] = Double.NaN }
    val three = FakeDevice("StructureBattery").also { it["Charge"] = 3.0 }
    fun read(mode: String, vararg network: FakeDevice) =
        Emulator(
                Program.parse("lb r0 HASH(\"StructureBattery\") Charge $mode"),
                network = network.toList(),
            )
            .runUntilHalt()
            .register("r0")

    // A NaN reading replaces the running minimum or maximum, and a later number replaces NaN.
    assertThat(read("Minimum", nan, three)).isNaN()
    assertThat(read("Minimum", three, nan)).isEqualTo(3.0)
    assertThat(read("Maximum", nan, three)).isNaN()
    assertThat(read("Maximum", three, nan)).isEqualTo(3.0)
  }

  @Test
  fun batchReadsCheckEveryDeviceCanBeReadEvenForCount() {
    val battery = FakeDevice("StructureBattery").also { it["Charge"] = 5.0 }
    for (text in
        listOf(
            "lb r0 HASH(\"StructureBattery\") Bogus Count",
            "lbn r0 HASH(\"StructureBattery\") 0 Bogus Count",
        )) {
      assertThrows<Ic10RuntimeException>(text) {
        Emulator(Program.parse(text), network = listOf(battery)).runUntilHalt()
      }
    }
  }

  @Test
  fun batchHashesAreIntegerOperands() {
    fun lb(text: String, vararg network: FakeDevice) =
        Emulator(Program.parse(text), network = network.toList()).runUntilHalt().register("r1")
    fun dev(hash: Int, charge: Double) = FakeDevice(hash).also { it["Charge"] = charge }

    // An alias wins over a define of the same name.
    assertThat(
            lb("define h 3\nmove r0 5\nalias h r0\nlb r1 h Charge Sum", dev(3, 1.0), dev(5, 10.0))
        )
        .isEqualTo(10.0)
    // A register rounds half to even; an alias of it truncates.
    assertThat(lb("move r0 5.6\nlb r1 r0 Charge Sum", dev(6, 10.0))).isEqualTo(10.0)
    assertThat(lb("move r0 5.6\nalias h r0\nlb r1 h Charge Sum", dev(5, 10.0), dev(6, 100.0)))
        .isEqualTo(10.0)
    // A built-in constant isn't an integer, for a read or a write.
    for (text in listOf("lb r1 pi Charge Sum", "lb r1 PI Charge Sum", "sb pi Charge 7")) {
      assertThrows<Ic10RuntimeException>(text) { lb(text, dev(3, 1.0)) }
    }
  }

  @Test
  fun aBatchModeReadsAnAliasBeforeADefine() {
    val emu =
        Emulator(
                Program.parse("define m 1\nmove r0 3\nalias m r0\nlb r1 5 Charge m"),
                network =
                    listOf(
                        FakeDevice(5).also { it["Charge"] = 1.0 },
                        FakeDevice(5).also { it["Charge"] = 10.0 },
                    ),
            )
            .runUntilHalt()

    // 3 is Maximum; the define's 1 would be Sum.
    assertThat(emu.register("r1")).isEqualTo(10.0)
  }

  @Test
  fun anIntegerOperandPassesOverADeviceAliasToADefine() {
    val emu = run("define x 4\nalias x d0\nj x\nmove r0 1\nmove r1 9", "d0" to FakeDevice())

    assertThat(emu.register("r0")).isEqualTo(0.0)
    assertThat(emu.register("r1")).isEqualTo(9.0)
  }

  @Test
  fun defineOfANonConstantFailsAtItsLine() {
    val e = assertThrows<Ic10RuntimeException> { Emulator(Program.parse("yield\ndefine x r0")) }
    assertThat(e.line).isEqualTo(1)
  }

  @Test
  fun defineWithoutAValueFailsAtItsLine() {
    val program = Program(Line.Comment(null), Line.Instruction("define", Name("x")))
    val e = assertThrows<Ic10RuntimeException> { Emulator(program) }
    assertThat(e.line).isEqualTo(1)
  }

  @Test
  fun defineOfAnOverlongStrFailsAtItsLine() {
    val e =
        assertThrows<Ic10RuntimeException> {
          Emulator(Program.parse("yield\ndefine x STR(\"ABCDEFG\")"))
        }
    assertThat(e.line).isEqualTo(1)
  }

  @Test
  fun definesTakeEveryBuiltInConstant() {
    for ((name, value) in Constants.VALUES) {
      val emu = run("define x $name\nmove r0 x")
      assertWithMessage(name).that(emu.register("r0")).isEqualTo(value)
    }
  }

  @Test
  fun constantsAreNotIntegers() {
    for (text in
        listOf(
            "j pi\nmove r0 1",
            "j PI\nmove r0 1",
            "beqz 0 Tau\nmove r0 1",
            "poke Pi 7",
            "get r0 db pi",
            "ls r0 d0 PI Occupied",
        )) {
      val e = assertThrows<Ic10RuntimeException>(text) { run(text, "d0" to FakeDevice()) }
      assertWithMessage(text).that(e.line).isEqualTo(0)
    }
  }

  @Test
  fun aValueReadsADefineBeforeAnAliasOfTheSameName() {
    val emu = run("define x 5\nalias x r0\nmove r1 x\nmove x 7")
    assertThat(emu.register("r1")).isEqualTo(5.0)
    assertThat(emu.register("r0")).isEqualTo(7.0)
  }

  @Test
  fun anIntegerReadsAnAliasBeforeADefineOfTheSameName() {
    val emu = run("define x 5\nmove r0 1\nalias x r0\npoke x 9\nget r1 db 1\nget r2 db 5")
    assertThat(emu.register("r1")).isEqualTo(9.0)
    assertThat(emu.register("r2")).isEqualTo(0.0)
  }

  @Test
  fun selectReadsBothArms() {
    for (text in listOf("select r0 0 nan 3", "select r0 1 3 NaN", "select r0 0 bogus 3")) {
      val e = assertThrows<Ic10RuntimeException>(text) { run(text) }
      assertWithMessage(text).that(e.line).isEqualTo(0)
    }
    val emu = run("select r0 0 1 2\nselect r1 7 1 2")
    assertThat(emu.register("r0")).isEqualTo(2.0)
    assertThat(emu.register("r1")).isEqualTo(1.0)
  }

  @Test
  fun compactedProgramsKeepTheirRegisters() {
    val programs =
        listOf(
            // A call through ra, and a label used as a value.
            "move r0 3\njal sq\n# back\nmove r2 next\nj r2\nsq:\nmul r1 r0 r0\nj ra\nnext:\nmove r2 0\nadd r3 r1 1",
            // Literal absolute and relative jumps over comments and blank lines.
            "move r0 1\n\nbrgtz r0 3\n# skipped\nmove r1 1\nmove r2 2\nj 9\nmove r3 3\n# end\n",
            // A define, an alias, and a loop.
            "define N 4\nalias i r0\nloop:\nadd r1 r1 i\nadd i i 1\n\nblt i N loop",
        )
    fun registers(program: Program): List<Double> {
      val emu = Emulator(program).runUntilHalt()
      return (0..15).map { emu.register("r$it") } + emu.register("sp")
    }

    for (text in programs) {
      val program = Program.parse(text)
      assertThat(registers(program.compacted())).isEqualTo(registers(program))
    }
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
