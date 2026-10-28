package ktrapic.datagen

/**
 * Prefabs that look like one family, which should be a family in `grouping.json` or be listed there
 * as not one, with the reason: the check that keeps new game prefabs from being left out of a
 * family by oversight.
 *
 * Two prefabs look alike when
 * - their names are the same once sizes, fluids and variants are taken out ([baseName]:
 *   `StructureLiquidValve` and `StructureValve` are both `Valve`),
 * - they have the same logic types and the same kinds of slot, though maybe not as many, and one's
 *   name has every word of the other's ([words]: `StructureLightLongWide` has those of
 *   `StructureLightLong`, `StructureStorageLocker` those of `StructureLockerSmall`). This finds
 *   kinds told apart by a word not yet in [VARIANT_WORDS], such as `Wide` or `MKII`, or
 * - they have the same, and at least one, of the logic types few prefabs have ([rareLogicTypes]):
 *   the shape of a job, whatever the devices are called. `StructureSorter` and `StructureUnloader` both
 *   send items to an `Output`, and the generators all report `PowerGeneration`.
 *
 * The first two don't see kinds whose prefab names don't share their words
 * (`StructureCapsuleTankGas` and `StructureMediumRocketGasFuelTank` are the game's "Gas Capsule
 * Tank Small" and "... Medium"; `StructureWallLight` and `StructureLightLong` are "Wall Light" and
 * "Wall Light (Long)"; `StructureElevatorLevelIndustrial` and `StructureElevatorLevelFront` are
 * "Elevator Level" and "Elevator Level (Cabled)"; the game misspells `StructurePipeAnalysizer`, the
 * gas twin of `StructureLiquidPipeAnalyzer`), nor kinds whose slots hold the same thing but are of
 * different kinds (`StructureCircuitHousing`'s chip slot is `Common`,
 * `StructureCircuitHousingCompact`'s is `ProgrammableChip`): those families come from the game's
 * English names.
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
          "Floor",
          "Dual",
          "Corner",
          "Short",
          "Overhead",
          "Nuclear",
          "Left",
          "Right",
          "To",
      )

  /** A prefab's name with its prefix, variant words, digits and punctuation taken out. */
  fun baseName(prefab: String): String {
    var name =
        PREFIXES.firstOrNull { prefab.startsWith(it) }?.let { prefab.removePrefix(it) } ?: prefab
    // A word after punctuation starts one, as in "Battery_Wireless_cell".
    name = name.replace(Regex("[^A-Za-z]+([a-z])")) { it.groupValues[1].uppercase() }
    name = name.replace(Regex("[^A-Za-z]"), "")
    // Some prefabs write "GastoLiquid": make that "to" a word of its own, so the fluids around it
    // are too. Only between fluids: "AutoMiner" keeps its "Auto".
    name = name.replace(Regex("(?<=Gas|Liquid)to(?=Gas|Liquid)"), "To")
    for (word in VARIANT_WORDS) name = name.replace(Regex("$word(?=[A-Z]|$)"), "")
    return name
  }

  /** The words of a prefab's [baseName]: `MKIIMiningDrill` is `MKII`, `Mining` and `Drill`. */
  fun words(prefab: String): Set<String> =
      Regex("[A-Z]+(?![a-z])|[A-Z][a-z]*").findAll(baseName(prefab)).map { it.value }.toSet()

  /**
   * The largest share of prefabs a logic type can be on and still mark out a kind of device:
   * `PowerGeneration` is on a few prefabs, `On` on most.
   */
  private const val RARE_SHARE = 0.1

  /**
   * The logic types few of [prefabs] have, leaving out the gas ratios, which come with any gas a
   * device holds rather than with what it does.
   */
  fun rareLogicTypes(prefabs: List<Prefab>): Set<String> =
      prefabs
          .flatMap { it.logic.keys }
          .filterNot { it.startsWith("Ratio") }
          .groupingBy { it }
          .eachCount()
          .filterValues { it <= prefabs.size * RARE_SHARE }
          .keys

  /** Whether [a] and [b] look like one family, given the [rare] logic types of all prefabs. */
  fun lookAlike(a: Prefab, b: Prefab, rare: Set<String> = emptySet()): Boolean {
    if (baseName(a.name) == baseName(b.name)) return true
    val shape = a.logic.keys intersect rare
    if (shape.isNotEmpty() && shape == b.logic.keys intersect rare) return true
    if (a.logic != b.logic || a.slots.values.toSet() != b.slots.values.toSet()) return false
    val (wordsA, wordsB) = words(a.name) to words(b.name)
    return wordsA.containsAll(wordsB) || wordsB.containsAll(wordsA)
  }

  /**
   * The groups of [prefabs] that look alike but that [grouping] neither puts in one family (or in
   * families one of which extends the other) nor lists in different groups of a not-family entry:
   * each group's prefab names, sorted. Two prefabs are in a group when a chain of such look-alike
   * pairs links them.
   */
  fun ungrouped(prefabs: List<Prefab>, grouping: Grouping): List<List<String>> {
    val familyOf = familyOf(grouping)
    // A family and every family it extends, directly or not.
    fun lineage(family: String): Set<String> =
        setOf(family) + grouping.families[family]?.extends.orEmpty().flatMap { lineage(it) }
    fun related(a: String?, b: String?) =
        a != null && b != null && (a in lineage(b) || b in lineage(a))
    // The group of a not-family entry that [prefab] is in, or else that lists a family it extends:
    // an entry naming the doors covers every kind of door.
    fun groupOf(notFamily: Grouping.NotFamily, prefab: String): Int {
      val listed = notFamily.groups.indexOfFirst { prefab in it }
      if (listed >= 0) return listed
      val ancestors = familyOf[prefab]?.let { lineage(it) }.orEmpty()
      return notFamily.groups.indexOfFirst { group -> group.any { familyOf[it] in ancestors } }
    }
    fun decided(a: String, b: String) =
        related(familyOf[a], familyOf[b]) ||
            grouping.notFamilies.any { notFamily ->
              val groupOfA = groupOf(notFamily, a)
              val groupOfB = groupOf(notFamily, b)
              groupOfA >= 0 && groupOfB >= 0 && groupOfA != groupOfB
            }

    val rare = rareLogicTypes(prefabs)
    val group = prefabs.associate { it.name to mutableSetOf(it.name) }.toMutableMap()
    for ((i, a) in prefabs.withIndex()) {
      for (b in prefabs.subList(i + 1, prefabs.size)) {
        if (!lookAlike(a, b, rare) || decided(a.name, b.name)) continue
        val merged = group.getValue(a.name).apply { addAll(group.getValue(b.name)) }
        for (name in merged) group[name] = merged
      }
    }
    return group.values.distinct().filter { it.size > 1 }.map { it.sorted() }.sortedBy { it[0] }
  }

  /** The groups of prefabs in [grouping]'s not-family entries that aren't all in one family. */
  fun groupsOutsideAFamily(grouping: Grouping): List<List<String>> {
    val familyOf = familyOf(grouping)
    return grouping.notFamilies
        .flatMap { it.groups }
        .filter { group ->
          val families = group.map { familyOf[it] }.distinct()
          group.size > 1 && (families.size != 1 || families[0] == null)
        }
  }

  /**
   * The families whose members [grouping] puts in different groups of one not-family entry, which
   * says they are different devices: each with the entry's reason.
   */
  fun familiesSplitByAnEntry(grouping: Grouping): List<Pair<String, String>> {
    val familyOf = familyOf(grouping)
    return grouping.notFamilies.flatMap { notFamily ->
      notFamily.groups
          .flatMap { group -> group.mapNotNull { familyOf[it] }.distinct() }
          .groupingBy { it }
          .eachCount()
          .filterValues { it > 1 }
          .keys
          .map { it to notFamily.reason }
    }
  }

  private fun familyOf(grouping: Grouping): Map<String, String> =
      grouping.families
          .flatMap { (family, members) -> members.members.values.map { it to family } }
          .toMap()
}
