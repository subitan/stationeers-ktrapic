package ktrapic.datagen

/**
 * Prefabs that look like one device in several kinds: names that are the same once sizes, fluids
 * and variants are taken out (`StructureLiquidValve` and `StructureValve` are both `Valve`). Such a
 * group should be a family in `grouping.json`, or be listed there as not one, with the reason: the
 * check that keeps new game prefabs from being left out of a family by oversight.
 */
object FamilyCandidates {
  private val PREFIXES = listOf("Structure", "Item", "Landingpad_")

  /** The words a family's members differ by. */
  private val VARIANT_WORDS =
      listOf(
          "SuperLarge",
          "Small",
          "Medium",
          "Large",
          "Big",
          "Gas",
          "Liquid",
          "Heavy",
          "Reversed",
          "Advanced",
          "Reinforced",
          "Refrigerated",
          "Angled",
          "Flat",
          "Dual",
          "Corner",
          "Short",
          "Overhead",
          "Nuclear",
          "to",
          "To",
      )

  /** A prefab's name with its prefix, variant words, digits and punctuation taken out. */
  fun baseName(prefab: String): String {
    var name =
        PREFIXES.firstOrNull { prefab.startsWith(it) }?.let { prefab.removePrefix(it) } ?: prefab
    name = name.replace(Regex("[^A-Za-z]"), "")
    // Some prefabs write "GastoLiquid": make "to" a word of its own, so the words around it are
    // too.
    name = name.replace(Regex("(?<=[a-z])to(?=[A-Z])"), "To")
    for (word in VARIANT_WORDS) name = name.replace(Regex("$word(?=[A-Z]|$)"), "")
    return name
  }

  /**
   * The groups of [prefabs] sharing a base name, two or more, that [grouping] neither makes one
   * family of nor lists as not one: by base name, with their prefabs.
   */
  fun ungrouped(prefabs: Collection<String>, grouping: Grouping): Map<String, List<String>> {
    val familyOf =
        grouping.families
            .flatMap { (family, members) -> members.members.values.map { it to family } }
            .toMap()
    return prefabs
        .groupBy { baseName(it) }
        .filterValues { it.size > 1 }
        .filterKeys { it !in grouping.notFamilies }
        .filterValues { group ->
          group.map { familyOf[it] }.distinct().let { it.size != 1 || it[0] == null }
        }
        .toSortedMap()
  }
}
