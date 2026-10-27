package ktrapic.datagen

/**
 * The families of logic types the device API shows as views rather than one property each:
 * `Ports.Input.Pressure` for `PressureInput`, `Ratios[Substance.Oxygen]` for `RatioOxygen`,
 * `Position.X` for `PositionX`, `Import.Count` for `ImportCount`.
 *
 * A view is erased: the compiler resolves a chain of them to the one logic type it names, so a view
 * read is the same `l` as the flat property was. Each device-level view is a property whose name
 * and class are listed here; which logic types it reaches on a given prefab is generated as that
 * property's `@LogicTypes`.
 */
object LogicViews {
  /** The device's own ports, as `Ports.Input` and so on, by the suffix their logic types take. */
  val PORTS = listOf("Input", "Input2", "Output", "Output2")

  /** The vector views, by property name, with the prefix of their `X`/`Y`/`Z` logic types. */
  val VECTORS =
      linkedMapOf(
          "Position" to "Position",
          "Velocity" to "Velocity",
          "VelocityRelative" to "VelocityRelative",
          "Forward" to "Forward",
          "Dimensions" to "Size",
          "Target" to "Target",
      )

  /** The import and export views, whose logic types are the property name and a member. */
  val TRANSFERS = listOf("Import", "Export")
  val TRANSFER_MEMBERS = listOf("Count", "Quantity", "SlotHash", "SlotOccupant")

  /** The device-level view a logic type belongs to, as its property name, or null if none. */
  fun viewOf(logicType: String, substances: Set<String>): String? {
    val port = PORTS.sortedByDescending { it.length }.firstOrNull { logicType.endsWith(it) }
    if (port != null) {
      val base = logicType.removeSuffix(port)
      if (base in PORT_MEMBERS || base.removePrefix("Ratio") in substances) return "Ports"
    }
    if (logicType.startsWith("Ratio") && logicType.removePrefix("Ratio") in substances) {
      return "Ratios"
    }
    for ((view, prefix) in VECTORS) {
      if (logicType in listOf("X", "Y", "Z").map { prefix + it }) return view
    }
    for (view in TRANSFERS) {
      if (logicType in TRANSFER_MEMBERS.map { view + it }) return view
    }
    return null
  }

  /** The members a port has besides its ratios, by the logic type's name before the port. */
  val PORT_MEMBERS = listOf("Pressure", "Temperature", "TotalMoles", "Combustion", "Setting")

  /**
   * The substances with ratios: each `RatioX` logic type that also comes for a port
   * (`RatioXInput`), which leaves out the plain `Ratio` some devices have.
   */
  fun substances(logicTypes: Collection<String>): List<String> =
      logicTypes
          .filter { it.startsWith("Ratio") && "${it}Input" in logicTypes }
          .map { it.removePrefix("Ratio") }
}
