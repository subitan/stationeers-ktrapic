package ktrapic.ic10

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ProgramTest {
  @Test
  fun compactResolvesLabelsAndDropsComments() {
    val program =
        Program.parse(
            """
            |# loop forever
            |start:
            |add r0 r0 1 # step
            |
            |bgt r0 10 done
            |j start
            |done:
            |s db Setting r0
            """
                .trimMargin()
        )

    assertThat(program.compacted().print())
        .isEqualTo(
            """
            |add r0 r0 1
            |bgt r0 10 3
            |j 0
            |s db Setting r0
            """
                .trimMargin()
        )
  }

  @Test
  fun compactRenumbersLiteralJumps() {
    val program =
        Program.parse(
            """
            |move r0 0
            |# skip the comment
            |brgtz r0 3
            |move r1 1
            |# here
            |move r2 2
            |j 5
            |jr -6
            """
                .trimMargin()
        )

    assertThat(program.compacted().print())
        .isEqualTo(
            """
            |move r0 0
            |brgtz r0 2
            |move r1 1
            |move r2 2
            |j 3
            |jr -4
            """
                .trimMargin()
        )
  }

  @Test
  fun compactLeavesComputedTargetsAlone() {
    val program = Program.parse("# x\nj ra\nmove r0 99\njr r0")

    assertThat(program.compacted().print()).isEqualTo("j ra\nmove r0 99\njr r0")
  }

  @Test
  fun jumpPastTheEndStaysPastTheEnd() {
    val program = Program.parse("j end\n# trailing\nend:")

    assertThat(program.compacted().print()).isEqualTo("j 1")
  }

  @Test
  fun rejectsUndefinedLabelReferences() {
    assertThrows<IllegalArgumentException> { Program(Line.Instruction("j", LabelRef("nowhere"))) }
  }

  @Test
  fun rejectsLabelReferenceInANameOperand() {
    assertThrows<IllegalArgumentException> {
      Program(Line.Label("On"), Line.Instruction("s", Device("d0"), LabelRef("On"), Number(1.0)))
    }
  }

  @Test
  fun rejectsLabelReferenceAsADefinedValue() {
    assertThrows<IllegalArgumentException> {
      Program(Line.Label("foo"), Line.Instruction("define", Name("T"), LabelRef("foo")))
    }
  }

  @Test
  fun rejectsLabelsThatClashWithOtherNames() {
    for (name in listOf("r0", "sp", "d1", "db", "pi", "nan")) {
      assertThrows<IllegalArgumentException>(name) { Program(Line.Label(name)) }
    }
    assertThrows<IllegalArgumentException> {
      Program(Line.Instruction("define", Name("Limit"), Number(3.0)), Line.Label("Limit"))
    }
    assertThrows<IllegalArgumentException> {
      Program(Line.Label("Sensor"), Line.Instruction("alias", Name("Sensor"), Device("d0")))
    }
    assertThrows<IllegalArgumentException> {
      Program(Line.Label("Sensor"), Line.Instruction("label", Device("d0"), Name("Sensor")))
    }
  }

  @Test
  fun rejectsANameDefinedTwice() {
    assertThrows<IllegalArgumentException> {
      Program(
          Line.Instruction("define", Name("x"), Number(1.0)),
          Line.Instruction("define", Name("x"), Number(2.0)),
      )
    }
  }

  @Test
  fun stringConstantsAreNonEmptyAscii() {
    assertThat(Hashes.str("A")).isEqualTo(65.0)
    assertThat(Hashes.str("ABCDEF")).isEqualTo(0x414243444546L.toDouble())
    assertThrows<IllegalArgumentException> { Hashes.str("") }
    assertThrows<IllegalArgumentException> { Hashes.str("é") }
    assertThrows<IllegalArgumentException> { StringConstant(StringConstant.Kind.HASH, "") }
    assertThrows<IllegalArgumentException> { StringConstant(StringConstant.Kind.STR, "a#b") }
    for (text in listOf("a\nb", "a\rb")) {
      assertThrows<IllegalArgumentException>(text) {
        StringConstant(StringConstant.Kind.HASH, text)
      }
    }
    // The game takes any other control character.
    assertThat(StringConstant(StringConstant.Kind.STR, "a\tb").numericValue)
        .isEqualTo(0x610962L.toDouble())
    assertThat(StringConstant(StringConstant.Kind.HASH, "a\u0000").text())
        .isEqualTo("HASH(\"a\u0000\")")
  }

  @Test
  fun builtInConstantsHaveTheGameValues() {
    assertThat(Constants.VALUES)
        .containsExactly(
            "nan",
            Double.NaN,
            "pinf",
            Double.POSITIVE_INFINITY,
            "ninf",
            Double.NEGATIVE_INFINITY,
            "pi",
            3.141592653589793,
            "tau",
            6.283185307179586,
            "deg2rad",
            0.01745329238474369,
            "rad2deg",
            57.295780181884766,
            "epsilon",
            4.9E-324,
            "rgas",
            8.31446261815324,
        )
    assertThat(Constants.lookup("PI")).isEqualTo(Math.PI)
    assertThat(Constants.lookup("Tau")).isEqualTo(Math.PI * 2.0)
    assertThat(Constants.lookup("pie")).isNull()
  }

  @Test
  fun rejectsLabelNamesTheParserWouldNotRead() {
    for (name in listOf("", "1x", "a b", "x:", "x#y", "x-y")) {
      assertThrows<IllegalArgumentException>(name) { Program(Line.Label(name)) }
    }
  }

  @Test
  fun rejectsDuplicateLabels() {
    assertThrows<IllegalArgumentException> { Program(Line.Label("a"), Line.Label("a")) }
  }

  @Test
  fun numbersPrintWithoutExponentsOrTrailingZeros() {
    assertThat(Number(3.0).text()).isEqualTo("3")
    assertThat(Number(-0.25).text()).isEqualTo("-0.25")
    assertThat(Number(1e20).text()).isEqualTo("100000000000000000000")
    assertThat(Number(1e-7).text()).isEqualTo("0.0000001")
    assertThat(Number(Double.NEGATIVE_INFINITY).text()).isEqualTo("ninf")
  }

  @Test
  fun hashIsSignedCrc32() {
    // The standard CRC-32 check value: 0xCBF43926.
    assertThat(Hashes.hash("123456789")).isEqualTo(0xCBF43926.toInt())
    assertThat(StringConstant(StringConstant.Kind.HASH, "123456789").numericValue)
        .isEqualTo(-873187034.0)
  }

  @Test
  fun strPacksBytesHighFirst() {
    assertThat(Hashes.str("AB")).isEqualTo((0x41 * 256 + 0x42).toDouble())
    assertThrows<IllegalArgumentException> { Hashes.str("TOOLONG") }
  }
}
