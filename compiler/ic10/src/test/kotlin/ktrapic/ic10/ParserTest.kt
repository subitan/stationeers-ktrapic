package ktrapic.ic10

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ParserTest {
  @Test
  fun parsesLabelsInstructionsAndComments() {
    val program =
        Program.parse(
            """
            |# counts up
            |start: # top
            |add r0 r0 1 # step
            |
            |s d0 Setting r0
            |j start
            """
                .trimMargin()
        )

    assertThat(program.lines)
        .containsExactly(
            Line.Comment("counts up"),
            Line.Label("start", "top"),
            Line.Instruction("add", listOf(Register.r(0), Register.r(0), Number(1.0)), "step"),
            Line.Comment(null),
            Line.Instruction("s", Device("d0"), Name("Setting"), Register.r(0)),
            Line.Instruction("j", LabelRef("start")),
        )
        .inOrder()
    assertThat(program.labels).containsExactly("start", 1)
  }

  @Test
  fun parsesNumberForms() {
    val program =
        Program.parse("move r0 -1.5\nmove r1 \$FF\nmove r2 %101\nmove r3 nan\nmove r4 2e3")

    assertThat(program.lines.map { (it as Line.Instruction).operands[1] })
        .containsExactly(
            Number(-1.5),
            Number(255.0),
            Number(5.0),
            Number(Double.NaN),
            Number(2000.0),
        )
        .inOrder()
  }

  @Test
  fun parsesStringConstantsWithSpaces() {
    val program = Program.parse("""sb HASH("Wall Light") On STR("ON") # note""")

    val line = program.lines.single() as Line.Instruction
    assertThat(line.operands)
        .containsExactly(
            StringConstant(StringConstant.Kind.HASH, "Wall Light"),
            Name("On"),
            StringConstant(StringConstant.Kind.STR, "ON"),
        )
        .inOrder()
    assertThat(line.comment).isEqualTo("note")
  }

  @Test
  fun labelsAreReferencesOnlyWhenDefined() {
    val program = Program.parse("loop:\nj loop\nl r0 d0 Temperature")

    assertThat((program.lines[1] as Line.Instruction).operands).containsExactly(LabelRef("loop"))
    assertThat((program.lines[2] as Line.Instruction).operands[2]).isEqualTo(Name("Temperature"))
  }

  @Test
  fun rejectsUnknownInstructions() {
    val e = assertThrows<Ic10ParseException> { Program.parse("yield\nfrob r0") }
    assertThat(e.line).isEqualTo(2)
    assertThat(e).hasMessageThat().contains("frob")
  }

  @Test
  fun rejectsWrongOperandCount() {
    val e = assertThrows<Ic10ParseException> { Program.parse("add r0 1") }
    assertThat(e).hasMessageThat().contains("add takes 3 operands, not 2")
  }

  @Test
  fun rejectsDuplicateLabels() {
    val e = assertThrows<Ic10ParseException> { Program.parse("a:\nyield\na:") }
    assertThat(e.line).isEqualTo(3)
  }

  @Test
  fun rejectsLabelAsRelativeTarget() {
    assertThrows<Ic10ParseException> { Program.parse("a:\nbrgtz r0 a") }
  }

  @Test
  fun rejectsUnterminatedString() {
    assertThrows<Ic10ParseException> { Program.parse("""sb HASH("oops On 1""") }
  }

  @Test
  fun roundTripsCanonicalText() {
    val text =
        """
        |# header
        |alias sensor d0
        |define LIMIT 10.5
        |main:
        |l r0 sensor Temperature
        |bgt r0 LIMIT hot # too hot
        |sb HASH("Wall Light") On 0
        |yield
        |j main
        |hot:
        |sb HASH("Wall Light") On 1
        |#
        |brnez r0 -2
        |j ra
        """
            .trimMargin()

    assertThat(Program.parse(text).print()).isEqualTo(text)
  }

  @Test
  fun everyBranchFamilyMemberIsKnown() {
    for (name in listOf("beq", "breq", "beqal", "bdns", "brdse", "bnan", "brnan", "bapzal")) {
      assertThat(Opcodes[name]).isNotNull()
    }
    assertThat(Opcodes["bnanal"]).isNull()
    assertThat(Opcodes["beq"]!!.arity).isEqualTo(3)
    assertThat(Opcodes["bap"]!!.arity).isEqualTo(4)
    assertThat(Opcodes["bdnvl"]!!.targetIndex).isEqualTo(2)
    assertThat(Opcodes["jr"]!!.jump).isEqualTo(JumpKind.RELATIVE)
    assertThat(Opcodes["bgtal"]!!.links).isTrue()
    assertThat(Opcodes["jal"]!!.links).isTrue()
    assertThat(Opcodes["bgt"]!!.links).isFalse()
  }
}
