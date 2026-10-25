package ktrapic.ic10

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
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
  fun labelsNeverStandInForDeclaredNamesLogicTypesOrBatchModes() {
    val program = Program.parse("On:\nSum:\ns d0 On 1\nlb r0 1 On Sum\nj On")

    assertThat((program.lines[2] as Line.Instruction).operands[1]).isEqualTo(Name("On"))
    assertThat((program.lines[3] as Line.Instruction).operands.drop(2))
        .containsExactly(Name("On"), Name("Sum"))
        .inOrder()
    assertThat((program.lines[4] as Line.Instruction).operands).containsExactly(LabelRef("On"))
  }

  @Test
  fun rejectsLabelsThatClashWithOtherNames() {
    for ((text, line) in
        listOf(
            "foo:\ndefine foo 5" to 1,
            "alias x r1\nx:" to 2,
            "yield\nr0:" to 2,
            "db:" to 1,
            "nan:" to 1,
            "yield\npi:" to 2,
            "deg2rad:" to 1,
            "rad2deg:" to 1,
            "epsilon:" to 1,
        )) {
      val e = assertThrows<Ic10ParseException>(text) { Program.parse(text) }
      assertThat(e.line).isEqualTo(line)
    }
  }

  @Test
  fun everyNameOperandIgnoresLabels() {
    // define and alias are left out: a label sharing their declared name is rejected outright.
    val opcodes =
        Opcodes.all.values.filter {
          it.nameIndices.isNotEmpty() && it.name != "define" && it.name != "alias"
        }
    assertThat(opcodes).isNotEmpty()
    for (opcode in opcodes) {
      val operands = List(opcode.arity) { if (it in opcode.nameIndices) "foo" else "r0" }
      val text = "foo:\n${opcode.name} ${operands.joinToString(" ")}"

      val parsed = (Program.parse(text).lines[1] as Line.Instruction).operands

      for (index in opcode.nameIndices) {
        assertWithMessage("$text, operand $index").that(parsed[index]).isEqualTo(Name("foo"))
      }
    }
  }

  @Test
  fun nameOperandsMatchTheGameSignatures() {
    // Every instruction with a name-typed operand (listed in nameTyped below), with its operands in
    // order. The instructions, arities and name-typed positions follow the instruction list at
    // https://stationeers-wiki.com/IC10/instructions, which spells some operands differently (for
    // example deviceHash for prefabHash, logicSlotType for slotLogicType).
    val signatures =
        listOf(
            "alias name target",
            "define name value",
            "bdnvl device logicType line",
            "bdnvs device logicType line",
            "l r device logicType",
            "lb r prefabHash logicType batchMode",
            "lbn r prefabHash nameHash logicType batchMode",
            "lbns r prefabHash nameHash slot slotLogicType batchMode",
            "lbs r prefabHash slot slotLogicType batchMode",
            "lr r device reagentMode reagent",
            "ls r device slot slotLogicType",
            "s device logicType value",
            "sb prefabHash logicType value",
            "sbn prefabHash nameHash logicType value",
            "sbs prefabHash slot slotLogicType value",
            "ss device slot slotLogicType value",
        )
    val nameTyped = setOf("name", "logicType", "slotLogicType", "batchMode", "reagentMode")
    val expected = signatures.associate { signature ->
      val words = signature.split(" ")
      words[0] to words.drop(1).indices.filter { words[it + 1] in nameTyped }.toSet()
    }

    val actual =
        Opcodes.all.values
            .filter { it.nameIndices.isNotEmpty() }
            .associate { it.name to it.nameIndices }
    assertThat(actual).isEqualTo(expected)
    for ((name, signature) in expected.keys.zip(signatures)) {
      assertWithMessage(signature)
          .that(Opcodes[name]!!.arity)
          .isEqualTo(signature.split(" ").size - 1)
    }
  }

  @Test
  fun builtInConstantsOtherThanNumbersStayNames() {
    val line = Program.parse("move r0 pi").lines.single() as Line.Instruction
    assertThat(line.operands[1]).isEqualTo(Name("pi"))
  }

  @Test
  fun rejectsLabelAsADefinedValue() {
    val e = assertThrows<Ic10ParseException> { Program.parse("foo:\ndefine T foo") }
    assertThat(e.line).isEqualTo(2)
  }

  @Test
  fun rejectsDefineOrAliasWithoutAName() {
    val e = assertThrows<Ic10ParseException> { Program.parse("yield\ndefine r0 5") }
    assertThat(e.line).isEqualTo(2)
    assertThrows<Ic10ParseException> { Program.parse("alias 3 d0") }
  }

  @Test
  fun rejectsMalformedHexAndBinary() {
    for (token in listOf("\$FFFFFFFFFFFFFFFFF", "\$_", "%_")) {
      val e = assertThrows<Ic10ParseException>(token) { Program.parse("yield\nmove r0 $token") }
      assertThat(e.line).isEqualTo(2)
      assertThat(e).hasMessageThat().contains("bad number")
    }
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
