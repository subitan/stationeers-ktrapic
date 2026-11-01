package ktrapic.api

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import ktrapic.frontend.Completion
import org.junit.jupiter.api.Test

/**
 * What completion offers compiles, and what docs/LANGUAGE.md documents is offered. For each kind of
 * receiver a program has, and for bare names, every item offered there is used in a program, which
 * must compile; the few offered items no use of which compiles are listed with the reason.
 */
class OfferedCompilesTest {
  /**
   * A place to complete: [line], between [before] and [after], with `|` at the caret. An item is
   * used by putting its use at the caret: its name, called with an argument of each parameter's
   * type for a function (see [ARGUMENTS]). [uses] gives the whole line for an item whose use needs
   * more than that, and [unusable] says why an item offered here can't be used. A `#` in a line is
   * replaced by the item's index, so the lines of several items can declare `val`s side by side.
   * [classifiers], if given, says why the classes, interfaces, objects and enums offered here are
   * not used.
   */
  private class Context(
      val name: String,
      val before: String,
      val line: String,
      val after: String = "",
      val uses: Map<String, String> = emptyMap(),
      val unusable: Map<String, String> = emptyMap(),
      val classifiers: String? = null,
  ) {
    fun complete(): List<Completion> {
      val source = before + line.replace("#", "0") + after
      val offset = source.indexOf('|')
      return Compiler.complete(source.removeRange(offset, offset + 1), offset).items
    }
  }

  /** Uses of what a static set offers, for a set [set] of elements such as [element]. */
  private fun staticSetUses(set: String, element: String) =
      mapOf(
          "contains" to "db.On = $set.contains($element)",
          "isEmpty" to "db.On = $set.isEmpty()",
          "size" to "db.Setting = $set.size.toDouble()",
          "plus" to "for (e in $set.plus($element)) db.Setting = 1.0",
          "minus" to "for (e in $set.minus($element)) db.Setting = 1.0",
          "intersect" to "for (e in $set.intersect(setOf($element))) db.Setting = 1.0",
      )

  /** Uses of what a batch [batch] of motion sensors offers. */
  private fun batchUses(batch: String) =
      mapOf(
          "all" to "db.On = $batch.all { it.Activate }",
          "any" to "db.On = $batch.any { it.Activate }",
          "none" to "db.On = $batch.none { it.Activate }",
          "count" to "db.Setting = $batch.count { it.Activate }.toDouble()",
          "forEach" to "$batch.forEach { it.On = true }",
          "map" to "db.Setting = $batch.map { it.Quantity }.average()",
          "maxOf" to "db.Setting = $batch.maxOf { it.Quantity }",
          "minOf" to "db.Setting = $batch.minOf { it.Quantity }",
          "sumOf" to "db.Setting = $batch.sumOf { it.Quantity }",
      )

  private fun ranges(name: String, range: String, unusable: Map<String, String> = emptyMap()) =
      Context(
          name,
          "",
          "for (i in ($range).|) db.Setting = i.toDouble()",
          unusable = unusable,
      )

  /** Uses of the functions that make a range from Int [receiver]. */
  private fun forRanges(receiver: String) =
      listOf("rangeTo", "rangeUntil", "until", "downTo").associateWith {
        "for (i in $receiver.$it(3)) db.Setting = i.toDouble()"
      }

  private val contexts =
      listOf(
          Context(
              "an Int",
              "import kotlin.math.*\nval n = d0.Setting.toInt()\n",
              "n.|",
              uses = forRanges("n"),
          ),
          Context("an Int literal", "import kotlin.math.*\n", "3.|", uses = forRanges("3")),
          Context(
              "a negative Int literal",
              "import kotlin.math.*\n",
              "(-3).|",
              // A constant negative Int shifted without its sign differs on the chip and the
              // JVM, so the compiler rejects one; a shift not known when compiling compiles.
              uses = forRanges("(-3)") + ("ushr" to "(-3).ushr(d0.Setting.toInt())"),
          ),
          Context("a Double", "val x = d0.Setting\n", "x.|"),
          Context("a Double literal", "import kotlin.math.*\n", "1.5.|"),
          Context("a Double with kotlin.math", "import kotlin.math.*\nval x = d0.Setting\n", "x.|"),
          Context("a Boolean", "val b = d0.On\n", "b.|"),
          Context("a String", "", "db.Setting = \"abc\".|"),
          ranges("a range", "0..10"),
          ranges("a range up to", "0..<10"),
          ranges("an until range", "0 until 10"),
          ranges("a downTo range", "10 downTo 0"),
          ranges(
              "a stepped range",
              "0..10 step 2",
              mapOf(
                  "step" to
                      "a step of a stepped range isn't compiled, and the type, IntProgression, " +
                          "doesn't say whether a range is stepped"
              ),
          ),
          Context(
              "a static set",
              "val s = setOf(1, 2)\n",
              "s.|",
              uses = staticSetUses("s", "1"),
          ),
          Context(
              "a static list",
              "val s = listOf(1, 2)\n",
              "s.|",
              uses = staticSetUses("s", "1"),
          ),
          Context(
              "Substance.entries",
              "",
              "Substance.entries.|",
              uses = staticSetUses("Substance.entries", "Substance.Oxygen"),
          ),
          Context(
              "a Ratios view's keys",
              "val k = GasSensor.at(d0).Ratios.keys\n",
              "k.|",
              uses = staticSetUses("k", "Substance.Oxygen"),
          ),
          Context(
              "a Ratios loop's entry",
              "for (e in GasSensor.at(d0).Ratios) {\n",
              "  e.|",
              "\n}\n",
              uses =
                  mapOf(
                      "key" to "  if (e.key == Substance.Oxygen) db.Setting = 1.0",
                      "value" to "  db.Setting = e.value",
                  ),
          ),
          Context("an SDK enum", "", "db.Setting = d0[LogicType.|]"),
          Context(
              "Substance",
              "",
              "val s# = Substance.|",
              uses = mapOf("entries" to "for (s in Substance.entries) db.Setting = 1.0"),
          ),
          Context("an SDK enum's entry", "", "val s = Substance.Oxygen.|"),
          Context("a logic type", "", "val t = LogicType.Setting.|"),
          Context("a value enum", "", "db.Color = Color.|"),
          Context(
              "a pin",
              "",
              "val v# = d0.|",
              uses =
                  mapOf(
                      "get" to "val v = d0.get(LogicType.Setting)",
                      "set" to "d0.set(LogicType.Setting, 1.0)",
                  ),
          ),
          Context("a typed device", "val g = GasSensor.at(d0)\n", "val v# = g.|"),
          Context("a family's member", "val m = PresenceSensor.Motion.at(d0)\n", "val v# = m.|"),
          Context(
              "a family's interface",
              "inline fun f(b: Battery) {\n",
              "  val v# = b.|",
              "\n}\nf(Battery.Small.at(d0))\n",
          ),
          Context("a view", "", "val v# = d0.Ports.|"),
          Context("a port", "", "val v# = d0.Ports.Input.|"),
          Context("a vector", "", "val v# = d0.Position.|"),
          Context("a Ratios view", "val r = GasSensor.at(d0).Ratios\n", "val v# = r.|"),
          Context("a device's slots", "", "val v# = d0.slots.|"),
          Context("a slot", "", "val v# = d0.slots[0].|"),
          Context(
              "a batch",
              "",
              "PresenceSensor.Motion.all.|",
              uses = batchUses("PresenceSensor.Motion.all"),
          ),
          Context(
              "a named batch",
              "",
              "PresenceSensor.Motion.named(\"x\").|",
              uses = batchUses("PresenceSensor.Motion.named(\"x\")"),
          ),
          Context(
              "a mapped batch",
              "",
              "db.Setting = PresenceSensor.Motion.all.map { it.Quantity }.|",
          ),
          Context("a family", "", "val v# = PresenceSensor.|.all"),
          Context("a family's member's entry point", "", "val v# = PresenceSensor.Motion.|"),
          Context(
              "bare names at the top level",
              "val a = 1\n",
              "|",
              classifiers = CLASSIFIERS_NOT_VALUES,
          ),
          Context(
              "bare names in a function",
              "val a = 1\nfun f(p: Int): Int {\n  val q = p\n",
              "  |",
              "\n  return q\n}\nval late = 2\ndb.Setting = f(1).toDouble()\n",
              classifiers = CLASSIFIERS_NOT_VALUES,
          ),
      )

  private fun compiles(source: String): List<CompileError> =
      (Compiler.compile(source) as? CompileResult.Failure)?.errors.orEmpty()

  /** The use of [item] at a caret, or null if it takes an argument of a type not in [ARGUMENTS]. */
  private fun use(item: Completion): String? {
    if (item.kind != Completion.Kind.FUNCTION) return item.label
    val open = item.detail.indexOf('(')
    var depth = 0
    var close = open
    while (close < item.detail.length) {
      when (item.detail[close]) {
        '(',
        '<' -> depth++
        ')',
        '>' -> depth--
      }
      if (depth == 0) break
      close++
    }
    val parameters = mutableListOf<String>()
    var start = open + 1
    depth = 0
    for (i in start until close) {
      when (item.detail[i]) {
        '<' -> depth++
        '>' -> depth--
        ',' ->
            if (depth == 0) {
              parameters += item.detail.substring(start, i)
              start = i + 1
            }
      }
    }
    if (close > open + 1) parameters += item.detail.substring(start, close)
    val arguments = parameters.map { ARGUMENTS[it.substringAfter(": ").trim()] ?: return null }
    return "${item.label}(${arguments.joinToString(", ")})"
  }

  @Test
  fun `every item offered compiles`() {
    val problems = mutableListOf<String>()
    for (context in contexts) {
      val items = context.complete().filter { it.kind != Completion.Kind.KEYWORD }
      val offered = items.map { it.label }
      if (items.isEmpty() && context.name !in OFFERS_NOTHING) {
        problems += "${context.name}: nothing offered"
      }
      for ((label, why) in context.unusable) {
        if (label !in offered) problems += "${context.name}: $label isn't offered ($why)"
      }
      val lines =
          items.withIndex().mapNotNull { (index, item) ->
            if (item.label in context.unusable) return@mapNotNull null
            if (context.classifiers != null && item.kind in CLASSIFIERS) return@mapNotNull null
            val line =
                context.uses[item.label]
                    ?: use(item)?.let { context.line.replace("|", it) }
                    ?: run {
                      problems += "${context.name}: no use for ${item.label} [${item.detail}]"
                      return@mapNotNull null
                    }
            item.label to line.replace("#", index.toString())
          }
      if (lines.isEmpty()) continue
      // All at once, and one at a time only to find those that don't compile.
      if (
          compiles(context.before + lines.joinToString("\n") { it.second } + context.after)
              .isEmpty()
      ) {
        continue
      }
      for ((label, line) in lines) {
        val errors = compiles(context.before + line + context.after)
        if (errors.isNotEmpty()) problems += "${context.name}: $label, as `$line`: $errors"
      }
    }

    assertWithMessage("items offered whose use doesn't compile").that(problems).isEmpty()
  }

  /** A program using each keyword, by the keyword. */
  private val keywordUses =
      mapOf(
          "val" to "val a = 1.0\ndb.Setting = a",
          "var" to "var a = 1.0\na += d0.Setting\ndb.Setting = a",
          "fun" to "fun f(): Int = 1\ndb.Setting = f().toDouble()",
          "if" to "if (d0.On) db.On = true",
          "else" to "if (d0.On) db.On = true else db.On = false",
          "when" to "when { d0.On -> db.Setting = 1.0 }",
          "while" to "while (d0.On) yield()",
          "for" to "for (i in 0..3) db.Setting = i.toDouble()",
          "do" to "do yield() while (d0.On)",
          "in" to "for (i in 0..3) db.Setting = i.toDouble()",
          "break" to "while (true) {\n  if (d0.On) break\n  yield()\n}",
          "continue" to "for (i in 0..3) {\n  if (i == 1) continue\n  db.Setting = i.toDouble()\n}",
          "return" to "fun f(): Int {\n  return 1\n}\ndb.Setting = f().toDouble()",
          "true" to "db.On = true",
          "false" to "db.On = false",
      )

  @Test
  fun `every keyword offered compiles`() {
    val offered =
        Compiler.complete("val a = 1\n\n", 10)
            .items
            .filter { it.kind == Completion.Kind.KEYWORD }
            .map { it.label }

    assertThat(keywordUses.keys).containsExactlyElementsIn(offered)
    val failures = keywordUses.mapNotNull { (keyword, use) ->
      compiles(use + "\n").takeIf { it.isNotEmpty() }?.let { "$keyword: $it" }
    }
    assertWithMessage("keywords whose use doesn't compile").that(failures).isEmpty()
  }

  private fun offered(source: String): List<String> {
    val offset = source.indexOf('|')
    return Compiler.complete(source.removeRange(offset, offset + 1), offset).items.map {
      it.label
    }
  }

  @Test
  fun `what LANGUAGE-md documents is offered`() {
    assertThat(offered("for (e in GasSensor.at(d0).Ratios) {\n  db.Setting = e.|\n}\n"))
        .containsExactly("key", "value")
    assertThat(offered("import kotlin.math.*\nval x = d0.Setting\nx.|\n"))
        .containsAtLeast("roundToInt", "absoluteValue", "pow", "coerceIn", "toInt")
    assertThat(offered("val n = 1\nn.|\n"))
        .containsAtLeast("until", "downTo", "rangeTo", "rangeUntil", "toDouble", "shl", "inv")
    assertThat(offered("for (i in (0..10).|) yield()\n")).containsExactly("step")
    assertThat(offered("val s = Substance.|\n")).contains("entries")
    assertThat(offered("val k = GasSensor.at(d0).Ratios.|\n")).contains("keys")
    assertThat(offered("val s = setOf(1, 2)\ns.|\n"))
        .containsExactly("contains", "intersect", "isEmpty", "minus", "plus", "size")
    assertThat(offered("val b = d0.On\nb.|\n")).containsExactly("not")
  }

  private companion object {
    /** An argument of each parameter type an offered function takes. */
    val ARGUMENTS =
        mapOf(
            "Int" to "1",
            "Double" to "1.0",
            "Boolean" to "true",
            "String" to "\"x\"",
            "Device" to "d0",
            "Substance" to "Substance.Oxygen",
        )

    val CLASSIFIERS =
        setOf(
            Completion.Kind.CLASS,
            Completion.Kind.INTERFACE,
            Completion.Kind.OBJECT,
            Completion.Kind.ENUM,
        )

    const val CLASSIFIERS_NOT_VALUES =
        "a class, interface, object or enum name is a type or what follows it is; what follows " +
            "is covered by the contexts after such a name"

    /** The contexts where nothing is offered. */
    val OFFERS_NOTHING = setOf("an SDK enum's entry", "a logic type")
  }
}
