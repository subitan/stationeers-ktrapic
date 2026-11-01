package ktrapic.frontend

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import ktrapic.Device
import org.junit.jupiter.api.Test

class K2CompletionTest {
  private val sdkJar = File(Device::class.java.protectionDomain.codeSource.location.toURI())

  /** Completes at the `|` in [source]. */
  private fun complete(source: String): List<Completion> {
    val offset = source.indexOf('|')
    return K2Completion.complete(
        source.removeRange(offset, offset + 1),
        offset,
        listOf(sdkJar),
        listOf("ktrapic.*"),
    )
  }

  private fun labels(source: String) = complete(source).map { it.label }

  @Test
  fun `after a device, its logic types`() {
    val items = complete("d0.|\n")

    assertThat(items.map { it.label }).containsAtLeast("Setting", "On", "Pressure")
    assertThat(items.first { it.label == "Setting" }.detail).isEqualTo("var Setting: Double")
    assertThat(items.map { it.label }).containsNoneOf("equals", "hashCode", "toString")
  }

  @Test
  fun `after a device with a word started, the same list`() {
    assertThat(labels("d0.Sett|\n")).contains("Setting")
  }

  @Test
  fun `after a local holding a typed device`() {
    val items = labels("val sensor = PresenceSensor.Motion.at(d0)\nif (sensor.|) yield()\n")

    assertThat(items).containsAtLeast("Activate", "Quantity", "On")
  }

  @Test
  fun `after a family, its members`() {
    assertThat(labels("PresenceSensor.|")).containsAtLeast("Motion", "Proximity", "Occupancy")
  }

  @Test
  fun `after an LED display, its write-only Text`() {
    assertThat(labels("val sign = LedDisplay.Small.at(d5)\nsign.|\n")).contains("Text")
  }

  @Test
  fun `after a dot in the middle of a line, what goes there`() {
    assertThat(labels("val s = PresenceSensor.|.at(d0)\n"))
        .containsAtLeast("Motion", "Proximity", "Occupancy")
  }

  @Test
  fun `after a family's member, its entry points`() {
    assertThat(labels("PresenceSensor.Motion.|")).containsAtLeast("at", "all", "named")
  }

  @Test
  fun `after a typed device, its prefab's entry points and nothing else`() {
    val items = complete("GrowLight.|\n")

    assertThat(items.map { it.label }).containsExactly("at", "all", "named")
    assertThat(items.single { it.label == "at" }.detail).isEqualTo("fun at(pin: Device): GrowLight")
    assertThat(items.single { it.label == "all" }.detail)
        .isEqualTo("val all: DeviceBatch<GrowLight>")
  }

  @Test
  fun `after a family with an LED display's sizes, its members`() {
    val items = labels("LedDisplay.|\n")

    assertThat(items).containsExactly("Small", "Medium", "Large")
  }

  @Test
  fun `after a family's member, its entry points typed by it`() {
    val items = complete("LedDisplay.Small.|\n")

    assertThat(items.map { it.label }).containsExactly("at", "all", "named")
    assertThat(items.single { it.label == "named" }.detail)
        .isEqualTo("fun named(name: String): DeviceBatch<LedDisplay.Small>")
  }

  @Test
  fun `after a prefab with slots, entry points whose batches have slots, not the batch type`() {
    // `Batch` is a class with no companion, so it is never a value after
    // `BatteryCharger.Standard.`.
    val items = complete("val b = BatteryCharger.Standard.|\n")

    assertThat(items.map { it.label }).containsExactly("at", "all", "named")
    assertThat(items.single { it.label == "all" }.detail)
        .isEqualTo("val all: BatteryCharger.Standard.Batch")
  }

  @Test
  fun `after a name, the classes nested in it that an expression can use`() {
    assertThat(labels("LedDisplay.|\n")).contains("Small")
  }

  @Test
  fun `a val holding a prefab is described by the Prefab it is`() {
    val items = complete("val p = GrowLight\nval q = |\n")

    assertThat(items.single { it.label == "p" }.detail).isEqualTo("val p: Prefab<GrowLight>")
  }

  @Test
  fun `after a val holding a prefab, its entry points`() {
    assertThat(labels("val p = GrowLight\np.|\n")).containsAtLeast("at", "all", "named")
  }

  @Test
  fun `after a device's prefab, whose device isn't known, no entry points`() {
    assertThat(labels("d0.PrefabHash.|\n")).containsNoneOf("at", "all", "named")
    assertThat(labels("val p = d0.PrefabHash\np.|\n")).containsNoneOf("at", "all", "named")
    assertThat(labels("val p = GrowLight.at(d0).PrefabHash\np.|\n"))
        .containsNoneOf("at", "all", "named")
    assertThat(labels("val p: SlottedPrefab<*, *> = BatteryCharger.Standard\np.|\n"))
        .containsNoneOf("at", "all", "named")
  }

  @Test
  fun `a val holding a prefab with slots is described by the SlottedPrefab it is`() {
    val items = complete("val p = BatteryCharger.Standard\nval q = |\n")

    assertThat(items.single { it.label == "p" }.detail)
        .isEqualTo("val p: SlottedPrefab<BatteryCharger.Standard, BatteryCharger.Standard.Batch>")
    assertThat(labels("val p = BatteryCharger.Standard\np.|\n"))
        .containsAtLeast("at", "all", "named")
  }

  @Test
  fun `nothing completion shows for a prefab says Companion`() {
    // A flat prefab and a family member, each with and without slots.
    for (prefab in
        listOf("GrowLight", "LedDisplay.Small", "ActiveVent", "BatteryCharger.Standard")) {
      val items =
          complete("$prefab.|\n") +
              complete("val p = $prefab\nval x = |\n") +
              complete("val p = $prefab\np.|\n") +
              complete("fun f() = $prefab\nval x = f().|\n")

      assertWithMessage(prefab).that(items).isNotEmpty()
      for (item in items) {
        assertWithMessage(prefab).that(item.label).doesNotContain("Companion")
        assertWithMessage(prefab).that(item.detail).doesNotContain("Companion")
      }
    }
  }

  @Test
  fun `after an enum, its entries`() {
    val items = labels("d0[LogicType.|] = 1.0")

    assertThat(items).containsAtLeast("Setting", "On")
    assertThat(items).containsNoneOf("values", "valueOf", "entries")
  }

  @Test
  fun `after Substance, its entries and the static set of them`() {
    val items = labels("val s = Substance.|\n")

    assertThat(items).containsAtLeast("Oxygen", "entries")
    assertThat(items).containsNoneOf("values", "valueOf")
  }

  @Test
  fun `after a Ratios loop's entry, its key and value`() {
    assertThat(labels("for (e in GasSensor.at(d0).Ratios) {\n  db.Setting = e.|\n}\n"))
        .containsExactly("key", "value")
  }

  @Test
  fun `after a range, only its step`() {
    assertThat(labels("for (i in (0..10).|) yield()\n")).containsExactly("step")
    assertThat(labels("for (i in (10 downTo 0).|) yield()\n")).containsExactly("step")
  }

  @Test
  fun `overloads merged into one item are described by one KTrapIC compiles`() {
    val int = complete("val x = 2\nval y = x.|\n")
    val double = complete("val x = 2.0\nval y = x.|\n")

    assertThat(int.single { it.label == "until" }.detail).isEqualTo("fun until(to: Int): IntRange")
    assertThat(int.single { it.label == "plus" }.detail).isEqualTo("fun plus(other: Int): Int")
    assertThat(double.single { it.label == "plus" }.detail)
        .isEqualTo("fun plus(other: Double): Double")
    assertThat(double.single { it.label == "coerceIn" }.detail)
        .isEqualTo("fun coerceIn(minimumValue: Double, maximumValue: Double): Double")
  }

  @Test
  fun `only the keywords a program can use`() {
    val keywords = complete("val a = 1\n|\n").filter { it.kind == Completion.Kind.KEYWORD }

    assertThat(keywords.map { it.label }).containsAtLeast("val", "fun", "for", "in", "while")
    assertThat(keywords.map { it.label })
        .containsNoneOf("class", "interface", "object", "enum", "is")
  }

  @Test
  fun `not a property in its own initializer, nor a function in its own body`() {
    assertThat(labels("val a = 1\nval b = |\n")).doesNotContain("b")
    assertThat(labels("fun f(): Int {\n  return |\n}\n")).doesNotContain("f")
  }

  @Test
  fun `inside a lambda over a batch`() {
    assertThat(labels("PresenceSensor.Motion.all.forEach { it.| }"))
        .containsAtLeast("Activate", "Quantity")
  }

  @Test
  fun `SDK extensions on a mapped batch`() {
    assertThat(labels("val q = PresenceSensor.Motion.all.map { it.Quantity }.|\n"))
        .containsAtLeast("sum", "average", "min", "max")
  }

  @Test
  fun `bare names are locals, parameters, members and the SDK`() {
    val source =
        """
        val limit = 10
        fun square(x: Int): Int {
          val y = x * x
          return |
        }
        """
            .trimIndent()
    val items = labels(source)

    assertThat(items).containsAtLeast("x", "y", "limit", "d0", "sleep", "PresenceSensor")
    assertThat(items).contains("if")
  }

  private fun locals(source: String) =
      complete(source).filter { it.kind == Completion.Kind.LOCAL }.map { it.label }

  @Test
  fun `a loop variable is in scope in the loop`() {
    assertThat(locals("for (i in 0..3) {\n  d0.Setting = |\n}\n")).containsExactly("i")
  }

  @Test
  fun `the variables a destructuring declaration makes, and not the compiler's`() {
    assertThat(locals("fun f() {\n  val (a, b) = Pair(1, 2)\n  val c = |\n}\n"))
        .containsExactly("a", "b")
    assertThat(locals("for ((gas, ratio) in GasSensor.at(d0).Ratios) {\n  d0.Setting = |\n}\n"))
        .containsExactly("gas", "ratio")
  }

  @Test
  fun `a when subject's variable is in scope in the branches`() {
    val source = "when (val v = d0.Setting) {\n  1.0 -> d0.Setting = |\n  else -> {}\n}\n"

    assertThat(locals(source)).containsExactly("v")
  }

  @Test
  fun `a shadowed name is offered once, as the nearer declaration`() {
    val items = complete("val x = 1\nfun f() {\n  val x = 2.0\n  val y = |\n}\n")

    val xs = items.filter { it.label == "x" }
    assertThat(xs).hasSize(1)
    assertThat(xs.single().kind).isEqualTo(Completion.Kind.LOCAL)
    assertThat(xs.single().detail).isEqualTo("val x: Double")
  }

  @Test
  fun `names that need backticks are not offered`() {
    assertThat(labels("val `my val` = 1\nval y = |\n")).doesNotContain("my val")
  }

  @Test
  fun `after an Int, only the members and extensions KTrapIC compiles`() {
    val items = labels("val x = 1\nval y = x.|\n")

    assertThat(items).containsAtLeast("plus", "toDouble", "toInt", "shl", "coerceIn", "until")
    assertThat(items)
        .containsNoneOf("toLong", "toFloat", "toByte", "toShort", "toChar", "compareTo")
    assertThat(
            K2Completion.SUPPORTED_STDLIB.getValue("kotlin.Int").map {
              it.substringBefore('(').substringAfterLast('.')
            }
        )
        .containsAtLeastElementsIn(items)
  }

  @Test
  fun `after a Double, kotlin-math's extensions once imported`() {
    val plain = labels("val x = 2.0\nval y = x.|\n")
    val withMath = labels("import kotlin.math.*\nval x = 2.0\nval y = x.|\n")
    val withOne = labels("import kotlin.math.roundToInt\nval x = 2.0\nval y = x.|\n")

    assertThat(plain).containsAtLeast("toInt", "coerceAtLeast")
    assertThat(plain).containsNoneOf("roundToInt", "toLong", "toFloat", "compareTo")
    assertThat(withMath).containsAtLeast("roundToInt", "absoluteValue", "pow")
    assertThat(withMath).containsNoneOf("roundToLong", "sign", "ulp", "withSign")
    assertThat(withOne).contains("roundToInt")
    assertThat(withOne).doesNotContain("pow")
  }

  @Test
  fun `no top-level function of the standard library by name, even imported`() {
    val names = listOf("max", "abs", "sqrt", "setOf", "listOf")

    assertThat(labels("val y = |\n")).containsNoneIn(names)
    assertThat(labels("import kotlin.math.*\nval y = |\n")).containsNoneIn(names)
    assertThat(labels("import kotlin.math.max\nval y = ma|\n")).doesNotContain("max")
  }

  @Test
  fun `after an enum entry or a static set, only what KTrapIC compiles`() {
    assertThat(labels("val s = setOf(1, 2)\nval n = s.|\n"))
        .containsExactly("contains", "intersect", "isEmpty", "minus", "plus", "size")
    assertThat(labels("val o = Substance.Oxygen.|\n"))
        .containsNoneOf("name", "ordinal", "compareTo")
  }

  @Test
  fun `a local declared after the caret is not offered`() {
    assertThat(labels("val a = 1\nval b = |\nval c = 3\n")).doesNotContain("c")
  }

  @Test
  fun `errors elsewhere in the program don't stop completion`() {
    assertThat(labels("val broken: Int = \"x\"\nd0.|\nwhile (\n")).containsAtLeast("Setting", "On")
  }

  @Test
  fun `nothing in a comment or a string`() {
    assertThat(labels("// d0.|\n")).isEmpty()
    assertThat(labels("/* d|")).isEmpty()
    assertThat(labels("val s = \"d0.|\"\n")).isEmpty()
  }

  @Test
  fun `in a string template's expression`() {
    assertThat(labels("val s = \"\${d0.|}\"\n")).contains("Setting")
  }

  @Test
  fun `in a function's body, a property declared after it is offered`() {
    assertThat(labels("fun f() {\n  db.Setting = |\n}\nval late = 1.0\nf()\n")).contains("late")
    assertThat(labels("db.Setting = |\nval late = 1.0\n")).doesNotContain("late")
    assertThat(labels("PresenceSensor.Motion.all.forEach { db.Setting = | }\nval late = 1.0\n"))
        .doesNotContain("late")
  }

  @Test
  fun `after a number literal, its type's members and extensions`() {
    assertThat(labels("val y = 3.|\n")).containsAtLeast("toDouble", "until", "plus", "shl")
    assertThat(labels("val y = 3.t|\n")).contains("toDouble")
    assertThat(labels("val y = (-3).|\n")).containsAtLeast("toDouble", "coerceIn")
    assertThat(labels("val y = 1.5.|\n")).containsAtLeast("toInt", "coerceIn")
  }

  @Test
  fun `a function declared later in the program is offered`() {
    assertThat(labels("val a = |\nfun later() = 1\n")).contains("later")
  }

  @Test
  fun `positions count Windows line endings`() {
    assertThat(labels("val a = 1\r\nd0.|\r\n")).contains("Setting")
  }

  @Test
  fun `the program's own classes are described without the object it is wrapped in`() {
    val items = complete("class P(val a: Int)\nfun mk(): P = P(1)\nval z = |\n")

    assertThat(items.single { it.label == "P" }.detail).isEqualTo("P")
    assertThat(items.single { it.label == "mk" }.detail).isEqualTo("fun mk(): P")
  }

  @Test
  fun `internal SDK declarations are not offered`() {
    assertThat(labels("|")).doesNotContain("intrinsic")
  }

  @Test
  fun `an SDK type carries its prefab's game name, and a family its members'`() {
    val items = complete("val a = |\n")

    assertThat(items.single { it.label == "GrowLight" }.prefabs)
        .containsExactly("StructureGrowLight")
    val led = items.single { it.label == "LedDisplay" }
    assertThat(led.prefabs)
        .containsExactly("StructureConsoleLED5", "StructureConsoleLED1x2", "StructureConsoleLED1x3")
    assertThat(led.filterText).isEqualTo("LedDisplay ConsoleLED5 ConsoleLED1x2 ConsoleLED1x3")
    assertThat(led.detail).isEqualTo("ktrapic.LedDisplay")
    assertThat(items.single { it.label == "Color" }.prefabs).isEmpty()
    assertThat(items.single { it.label == "Color" }.filterText).isNull()
  }

  @Test
  fun `after a family, each member carries its own game name`() {
    val items = complete("LedDisplay.|\n")

    assertThat(items.single { it.label == "Small" }.prefabs).containsExactly("StructureConsoleLED5")
  }

  @Test
  fun `a family's members are offered by a word of their game names`() {
    val items = complete("val a = Console|\n")

    val small = items.single { it.label == "LedDisplay.Small" }
    assertThat(small.kind).isEqualTo(Completion.Kind.CLASS)
    assertThat(small.detail).isEqualTo("ktrapic.LedDisplay.Small")
    assertThat(small.prefabs).containsExactly("StructureConsoleLED5")
    assertThat(items.map { it.label }).containsAtLeast("LedDisplay.Medium", "LedDisplay.Large")
    assertThat(labels("val a = ConsoleLED|\n"))
        .containsAtLeast("LedDisplay.Small", "LedDisplay.Medium", "LedDisplay.Large")
    assertThat(labels("val a = consoleled|\n")).contains("LedDisplay.Small")
  }

  @Test
  fun `a family's members are not offered by game names for a short word, a structure, or the family's own name`() {
    assertThat(labels("val a = Co|\n").filter { '.' in it }).isEmpty()
    assertThat(labels("val a = Structure|\n").filter { '.' in it }).isEmpty()
    assertThat(labels("val a = Led|\n")).doesNotContain("LedDisplay.Small")
    assertThat(labels("d0.Console|\n")).doesNotContain("LedDisplay.Small")
  }

  @Test
  fun `a longer word brings members its shorter start withheld for the family's own name`() {
    assertThat(labels("val a = Bat|\n")).doesNotContain("Battery.Large")
    assertThat(labels("val a = BatteryL|\n")).contains("Battery.Large")
  }

  @Test
  fun `a family's members are offered by the word after an acronym in their game names`() {
    assertThat(labels("val a = Silo|\n")).contains("ItemStorage.SdbSilo")
    assertThat(labels("val a = Hopper|\n"))
        .containsAtLeast("SDBHopper.Standard", "SDBHopper.Advanced")
  }

  @Test
  fun `the start of a game name that says it is a structure or an item finds nothing`() {
    /** The items a client finds for [word] by their filterText and not by their label. */
    fun found(word: String) =
        complete("val a = $word|\n")
            .filter { item ->
              !item.label.startsWith(word) &&
                  item.filterText?.let { K2Completion.wordStarts(it, word) } == true
            }
            .map { it.label }

    for (word in listOf("Str", "Struct", "Ite")) {
      assertWithMessage(word).that(found(word)).isEmpty()
    }
    // `St` finds the few types with a word such as Storage or Station in a game name, not the
    // hundreds whose game names start with Structure.
    assertThat(found("St")).containsAtLeast("ItemStorage", "WeatherStation")
    assertThat(found("St").size).isLessThan(20)
  }

  @Test
  fun `a game name's words leave out the word saying it is a structure or an item`() {
    assertThat(K2Completion.gameNameWords("StructureConsoleLED5")).isEqualTo("ConsoleLED5")
    assertThat(K2Completion.gameNameWords("StructureSDBSilo")).isEqualTo("SDBSilo")
    assertThat(K2Completion.gameNameWords("ItemBatteryCellLarge")).isEqualTo("BatteryCellLarge")
    assertThat(K2Completion.gameNameWords("Landingpad_GasConnectorInwardPiece"))
        .isEqualTo("Landingpad_GasConnectorInwardPiece")
    assertThat(K2Completion.gameNameWords("Itemised")).isEqualTo("Itemised")
  }

  /** The same examples as the mod's `CompletionsTest.WordStartsAtACapitalADigitOrASeparator`. */
  @Test
  fun `a word starts at a capital, a digit or after a separator`() {
    val examples =
        listOf(
            Triple("LedDisplay ConsoleLED5", "Console", true),
            Triple("LedDisplay ConsoleLED5", "consoleLED", true),
            Triple("LedDisplay ConsoleLED5", "led", true),
            Triple("LedDisplay ConsoleLED5", "display", true),
            Triple("LedDisplay.Small ConsoleLED5", "Small", true),
            Triple("LedDisplay ConsoleLED1x2", "1x2", true),
            Triple("ItemStorage.SdbSilo SDBSilo", "Silo", true),
            Triple("SDBHopper.Standard SDBHopper", "Hopper", true),
            Triple("LedDisplay ConsoleLED5", "onsole", false),
            Triple("LedDisplay ConsoleLED5", "ED5", false),
            Triple("LedDisplay ConsoleLED5", "Display ConsoleX", false),
            Triple("LedDisplay ConsoleLED5", "", false),
        )
    for ((text, word, starts) in examples) {
      assertWithMessage("'$word' in '$text'")
          .that(K2Completion.wordStarts(text, word))
          .isEqualTo(starts)
    }
  }
}
