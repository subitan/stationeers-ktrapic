package ktrapic.datagen

/**
 * Generates `docs/DEVICES.md`, the API reference of what programs can name, written as Kotlin
 * declarations: every typed device (its logic types as `var` or `val`, its slots, and in a comment
 * its prefab and entry point), the device families, the slot types, and the game's enums. It reads
 * the generated runtime sources, so it lists what the compiler accepts, and fails if a typed device
 * there goes unlisted.
 */
object DeviceReferenceGenerator {
  /** Where the file goes, relative to the workspace root. */
  const val PATH = "docs/DEVICES.md"

  private val DECLARATION =
      Regex("""^(\s*)(?:abstract |sealed |open |data |enum )*(class|object|interface) (\w+)(.*)$""")
  private val PROPERTY = Regex("""^\s*(override )?(var|val) (\w+): (.+)$""")
  private val PREFAB_NAME = Regex("""@PrefabName\("([^"]+)"\)""")
  private val SLOT_NUMBER = Regex("""^\s*@SlotNumber\((\d+)\)$""")
  private val LOGIC_TYPES = Regex("""^\s*@LogicTypes\(read = "([^"]*)", written = "([^"]*)"\)$""")

  /** A view's `@LogicTypes` as ktfmt breaks a long one over lines, to be joined into one. */
  private val BROKEN_LOGIC_TYPES =
      Regex("""@LogicTypes\(\s*read =\s*"([^"]*)",\s*written =\s*"([^"]*)",\s*\)""")

  /**
   * A logic type, a view (with [reach], the logic types it reaches on the device), or with a [slot]
   * number one of the slots the game names.
   */
  private class Property(
      val name: String,
      val type: String,
      val writable: Boolean,
      val inherited: Boolean,
      val slot: Int? = null,
      val reach: Reach? = null,
  ) {
    override fun toString() =
        "${if (writable) "var" else "val"} $name: $type${slot?.let { " // slots[$it]" } ?: ""}"
  }

  /** The logic types a device's view reaches: those it can read and those it can write. */
  private class Reach(val read: List<String>, val written: List<String>)

  private class Declaration(
      val name: String,
      val properties: List<Property>,
      val prefab: String? = null,
      /** For an interface, the interfaces it extends: `" : ElevatorShaft"`, or empty. */
      val supertypes: String = "",
  )

  /**
   * The reference for [prefabsSource] and [viewsSource], the generated `GeneratedPrefabs.kt` and
   * `GeneratedViews.kt`, and [enums].
   */
  fun generate(
      prefabsSource: String,
      enums: LogicEnums,
      typing: Typing,
      viewsSource: String = "",
  ): String {
    val lines =
        prefabsSource
            .replace(BROKEN_LOGIC_TYPES) {
              "@LogicTypes(read = \"${it.groupValues[1]}\", written = \"${it.groupValues[2]}\")"
            }
            .lines()
    val substances = LogicViews.substances(enums.enums["LogicType"].orEmpty().keys)
    val devices = mutableListOf<Declaration>()
    val entryPoints = mutableMapOf<String, String>()
    val families = mutableListOf<Declaration>()
    val slots = mutableListOf<Declaration>()
    // The declarations enclosing the current line, by indentation.
    val enclosing = ArrayDeque<Pair<Int, String>>()
    var prefab: String? = null
    for ((index, line) in lines.withIndex()) {
      PREFAB_NAME.find(line)?.let { prefab = it.groupValues[1] }
      val match = DECLARATION.find(line) ?: continue
      val indent = match.groupValues[1].length
      while (enclosing.isNotEmpty() && enclosing.last().first >= indent) enclosing.removeLast()
      val name = (enclosing.map { it.second } + match.groupValues[3]).joinToString(".")
      enclosing.addLast(indent to match.groupValues[3])
      val kind = match.groupValues[2]
      val properties = properties(members(lines, index, indent))
      val named = prefab
      prefab = null
      when {
        kind == "interface" ->
            families +=
                Declaration(
                    name,
                    properties,
                    supertypes = match.groupValues[4].substringBefore(" {").trimEnd(),
                )
        kind == "class" && named != null -> devices += Declaration(name, properties, named)
        kind == "class" && "DeviceSlot()" in match.groupValues[4] ->
            slots += Declaration(name, properties)
        kind == "object" && named != null -> entryPoints[named] = name
      }
    }
    // Counted from the raw source, not from the declarations parsed above, so a typed device whose
    // header or prefab name went unread fails here instead of being left out silently.
    val typedDevices = lines.count { "TypedDevice()" in it }
    require(typedDevices == devices.size) {
      "the source has $typedDevices typed device(s) but ${devices.size} were read with a " +
          "recognized @PrefabName"
    }
    val missing = devices.mapNotNull { it.prefab } - entryPoints.keys
    require(missing.isEmpty()) { "prefabs without an entry point: $missing" }
    fun entryPoint(device: Declaration) = entryPoints.getValue(device.prefab!!)

    val topLevel = devices.filter { '.' !in it.name }.sortedBy { it.name.lowercase() }
    val byLetter = topLevel.groupBy { it.name.first().uppercaseChar() }.toSortedMap()

    return buildString {
      append(
          "<!-- Generated by //tools/datagen from runtime/data. Do not edit; regenerate instead. -->\n"
      )
      append("\n")
      append("# Devices\n")
      append("\n")
      append("The typed devices, slots and enums a program can name, as Kotlin declarations: ")
      append(
          "${devices.size} prefab${if (devices.size == 1) "" else "s"} in all. The [language guide](LANGUAGE.md#devices) says how "
      )
      append("to use them. Search this page for a game prefab name (`StructureGrowLight`) to find ")
      append("its type.\n")
      append("\n")
      append("- [Using a device](#using-a-device)\n")
      if (viewsSource.isNotEmpty()) append("- [Views](#views)\n")
      append("- [Families](#families)\n")
      append("- Devices: ")
      append(byLetter.keys.joinToString(" · ") { "[$it](#${it.lowercaseChar()})" })
      append("\n")
      append("- [Slots](#slots)\n")
      append("- [Enums](#enums)\n")
      append("\n")
      append("## Using a device\n")
      append("\n")
      append("Each prefab has a device type, such as `GrowLight`, and an entry point named after ")
      append("it in the plural, such as `GrowLights`, which every device below names in its ")
      append("comment. Every entry point has the same three members:\n")
      append("\n")
      append("```kotlin\n")
      append("object GrowLights {\n")
      append("  /** The device on [pin], as a GrowLight. */\n")
      append("  fun at(pin: Device): GrowLight\n")
      append("\n")
      append("  /** Every one on the chip's network. */\n")
      append("  val all: DeviceBatch<GrowLight>\n")
      append("\n")
      append("  /** Every one on the network named [name], which must be a constant. */\n")
      append("  fun named(name: String): DeviceBatch<GrowLight>\n")
      append("}\n")
      append("```\n")
      append("\n")
      append("For a device with slots, the batch from `all` or `named` also has `slots`, a slot ")
      append("of every one in the batch: `BatteryChargers.Standard.all.slots[0]`.\n")
      append("\n")
      append("A device's logic types are its properties: a `var` can be written, a `val` only ")
      append("read. Every typed device also has `val PrefabHash: Prefab`, `val NameHash: Double` ")
      append("and `val ReferenceId: Double`. Any device can also be used untyped, as `d0` to ")
      append("`d5` or `db`, with every logic type.\n")
      append("\n")
      if (viewsSource.isNotEmpty()) {
        append("## Views\n")
        append("\n")
        append("Some logic types come in families, which a device groups as views: ")
        append("`filtration.Ports.Output.Pressure` is `PressureOutput`, ")
        append("`sensor.Ratios[Substance.Oxygen]` is `RatioOxygen`, `robot.Position.X` is ")
        append(
            "`PositionX`, and `hopper.Import.Count` is `ImportCount`. A view compiles to the same "
        )
        append("one instruction as the logic type it names, and can be held in a `val` or passed ")
        append("to an inline function like a device. A key, such as `Substance.Oxygen`, must be ")
        append("written out, held in a `val` or passed to an inline function.\n")
        append("\n")
        append("A typed device has only the views its prefab has, and the comment by each says ")
        append("what it reaches there; naming anything else is an error saying what the device ")
        append("lacks. An untyped pin (`d0`) has every view, checked only against the logic ")
        append("types the game has. A batch reads and writes ")
        append("views too: `GasSensors.all.map { it.Ratios[Substance.Oxygen] }.average()`.\n")
        append("\n")
        append("```kotlin\n")
        append(viewDeclarations(viewsSource))
        append("```\n")
        append("\n")
      }
      append("## Families\n")
      append("\n")
      append("A family is a device that comes as several prefabs. Its interface holds the logic ")
      append("types every member has, so a function can take any of them; each member is a ")
      append("nested class, with an entry point nested the same way (`Batteries.Small`). A family ")
      append("that is a special case of another extends its interface, so a function taking the ")
      append("other takes it too: an `ElevatorLevel` is an `ElevatorShaft`.\n")
      for (family in families.sortedBy { it.name }) {
        val members = devices.filter { it.name.startsWith("${family.name}.") }
        append("\n")
        append("### ${family.name}\n")
        append("\n")
        append("```kotlin\n")
        append("interface ${family.name}${family.supertypes} {\n")
        for (property in family.properties) append("  $property\n")
        for ((i, member) in members.withIndex()) {
          val simple = member.name.substringAfter('.')
          val extra = member.properties.filter { !it.inherited }
          // A blank line between declarations, but not at the top of an interface that adds none.
          if (i > 0 || family.properties.isNotEmpty()) append("\n")
          append("  /** ${member.prefab}. Entry point: ${entryPoint(member)}. */\n")
          if (extra.isEmpty()) {
            append("  class $simple : TypedDevice, ${family.name}\n")
          } else {
            append("  class $simple : TypedDevice, ${family.name} {\n")
            for (property in extra) append("    $property\n")
            append("  }\n")
          }
        }
        append("}\n")
        append("```\n")
      }
      append("\n")
      append("## Devices\n")
      for ((letter, group) in byLetter) {
        append("\n")
        append("### $letter\n")
        append("\n")
        append("```kotlin\n")
        for ((i, device) in group.withIndex()) {
          if (i > 0) append("\n")
          append("/** ${device.prefab}. Entry point: ${entryPoint(device)}. */\n")
          declaration("class ${device.name} : TypedDevice", device.properties, substances)
        }
        append("```\n")
      }
      append("\n")
      append("## Slots\n")
      append("\n")
      append("A device's `slots` are numbered from 0: `d0.slots[0].Occupied`. Slots the game ")
      append("names are also properties of the device, listed after its logic types with their ")
      append("number in a comment (`// slots[0]`). A slot type has the slot logic types that kind ")
      append("of slot can read, and `var` for those a program can write.\n")
      append("\n")
      append("```kotlin\n")
      for ((i, slot) in slots.sortedBy { it.name.lowercase() }.withIndex()) {
        if (i > 0) append("\n")
        declaration("class ${slot.name} : DeviceSlot", slot.properties)
      }
      append("```\n")
      append("\n")
      append("## Enums\n")
      append("\n")
      append("Operand enums name logic types and batch methods, as in `d0[LogicType.Setting]`; ")
      val nones =
          enums.enums
              .filter { (name, values) -> name in typing.operandEnums && "None" in values }
              .keys
              .sorted()
      if (nones.isNotEmpty()) {
        append(nones.joinToString(" and ") { "`$it.None`" })
        append(", which name${if (nones.size == 1) "s" else ""} nothing, ")
        append("${if (nones.size == 1) "is" else "are"} not listed. ")
      }
      append("Value enums are `Double` constants that can be written to a device, as in ")
      append("`db.Color = Color.Red`.\n")
      append("\n")
      append("```kotlin\n")
      for ((i, entry) in enums.enums.toSortedMap().entries.withIndex()) {
        val (name, values) = entry
        if (i > 0) append("\n")
        if (name in typing.operandEnums) {
          append("enum class $name {\n")
          for (row in values.keys.filter { it != "None" }.sorted().chunked(6)) {
            append("  ${row.joinToString(", ")},\n")
          }
        } else {
          append("object $name {\n")
          for ((value, number) in values) append("  const val $value: Double = $number.0\n")
        }
        append("}\n")
      }
      append("```\n")
    }
  }

  private fun StringBuilder.declaration(
      header: String,
      properties: List<Property>,
      substances: List<String> = emptyList(),
  ) {
    if (properties.isEmpty()) {
      append("$header\n")
      return
    }
    append("$header {\n")
    for (property in properties) {
      append("  $property")
      val reach = property.reach
      if (reach == null) {
        append("\n")
        continue
      }
      val lines = reachLines(property.name, reach, substances)
      if (lines.size == 1) {
        append(" // ${lines.single()}\n")
      } else {
        append("\n")
        for (line in lines) append("  //   $line\n")
      }
    }
    append("}\n")
  }

  /**
   * What a view reaches on a device, in the view's own terms: a ratio by its substance, a port's
   * members, a vector's axes, an import's or export's members; `var` marks what can be written.
   */
  private fun reachLines(view: String, reach: Reach, substances: List<String>): List<String> {
    fun ratios(names: List<String>, suffix: String): String {
      val keys = names.map { it.removePrefix("Ratio").removeSuffix(suffix) }
      return if (keys.toSet() == substances.toSet()) "every Substance" else keys.joinToString(", ")
    }
    fun members(names: List<String>, prefix: String, suffix: String) = names.map {
      val member = it.removePrefix(prefix).removeSuffix(suffix)
      if (it in reach.written) "var $member" else member
    }
    return when (view) {
      "Ratios" -> listOf(ratios(reach.read, ""))
      "Ports" ->
          LogicViews.PORTS.mapNotNull { port ->
            // `PressureInput2` doesn't end with `Input`, so each name falls to one port.
            val names = (reach.read + reach.written).distinct().filter { it.endsWith(port) }
            if (names.isEmpty()) return@mapNotNull null
            val (ratioNames, others) = names.partition { it.startsWith("Ratio") }
            val parts =
                members(others.sorted(), "", port) +
                    if (ratioNames.isEmpty()) emptyList()
                    else listOf("Ratios[${ratios(ratioNames, port)}]")
            "$port: ${parts.joinToString(", ")}"
          }
      else -> {
        val prefix = LogicViews.VECTORS[view] ?: view
        listOf(
            members((reach.read + reach.written).distinct().sorted(), prefix, "").joinToString(", ")
        )
      }
    }
  }

  /**
   * The view classes and the `Substance` enum of `GeneratedViews.kt`, as declarations: without the
   * file header, bodies, constructors or blank lines inside a class, and an enum's entries in rows.
   */
  private fun viewDeclarations(source: String): String {
    val lines =
        source
            .lines()
            .dropWhile { !it.startsWith("/**") }
            .filter { it.trim() !in setOf("get() = intrinsic()", "set(value) = intrinsic()") }
            .map { it.replace(" internal constructor()", "").replace(" = intrinsic()", "") }
    val out = StringBuilder()
    var depth = 0
    val entries = mutableListOf<String>()
    for (line in lines) {
      if (line.isBlank()) {
        if (depth == 0 && out.isNotEmpty() && !out.endsWith("\n\n")) out.append("\n")
        continue
      }
      if (depth > 0 && line.trim().matches(Regex("""\w+,"""))) {
        entries += line.trim().removeSuffix(",")
        continue
      }
      if (line == "}" && entries.isNotEmpty()) {
        for (row in entries.chunked(6)) out.append("  ${row.joinToString(", ")},\n")
        entries.clear()
      }
      out.append(line).append("\n")
      depth += line.count { it == '{' } - line.count { it == '}' }
    }
    return out.toString().trimEnd('\n') + "\n"
  }

  /** The lines declared directly inside the declaration on line [start], indented [indent]. */
  private fun members(lines: List<String>, start: Int, indent: Int): List<String> {
    val members = mutableListOf<String>()
    var i = start + 1
    while (i < lines.size && (lines[i].isBlank() || indentOf(lines[i]) > indent)) {
      if (indentOf(lines[i]) == indent + 2) members += lines[i]
      i++
    }
    return members
  }

  private fun indentOf(line: String) = line.length - line.trimStart().length

  /**
   * The properties among [members]: logic types by name, then the slots the game names by number,
   * then `slots`.
   */
  private fun properties(members: List<String>): List<Property> {
    val properties = mutableListOf<Property>()
    var slot: Int? = null
    var reach: Reach? = null
    for (member in members) {
      val number = SLOT_NUMBER.find(member)
      if (number != null) {
        slot = number.groupValues[1].toInt()
        continue
      }
      val logicTypes = LOGIC_TYPES.find(member)
      if (logicTypes != null) {
        fun names(group: Int) = logicTypes.groupValues[group].split(',').filter { it.isNotEmpty() }
        reach = Reach(names(1), names(2))
        continue
      }
      val match = PROPERTY.find(member) ?: continue
      properties +=
          Property(
              match.groupValues[3],
              match.groupValues[4].trim(),
              match.groupValues[2] == "var",
              match.groupValues[1].isNotEmpty(),
              slot,
              reach,
          )
      slot = null
      reach = null
    }
    return properties.sortedWith(
        compareBy({ it.name == "slots" }, { it.slot != null }, { it.slot }, { it.name })
    )
  }
}
