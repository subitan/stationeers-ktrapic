@file:OptIn(org.jetbrains.kotlin.fir.symbols.SymbolInternals::class)

package ktrapic.frontend

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.scopes.kotlinScopeProvider
import org.jetbrains.kotlin.fir.scopes.processAllClassifiers
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

/**
 * The SDK's families (interfaces in `ktrapic` whose nested classes are the prefabs that do one kind
 * of job, `Generator.StirlingEngine`) and the game's names of the prefabs their members stand for,
 * as the SDK's `@PrefabName` gives them.
 */
internal object SdkFamilies {
  private val SDK_PACKAGE = FqName("ktrapic")

  /** The SDK's annotation giving a typed device's game prefab name. */
  private val PREFAB_NAME = ClassId.topLevel(FqName("ktrapic.PrefabName"))

  /** How many family members [withFamilyMembersNamed] suggests at most. */
  private const val MAX_SUGGESTED = 5

  /** The SDK's families: its top-level interfaces. */
  fun families(session: FirSession): List<FirRegularClassSymbol> =
      session.symbolProvider.symbolNamesProvider
          .getTopLevelClassifierNamesInPackage(SDK_PACKAGE)
          .orEmpty()
          .mapNotNull {
            session.symbolProvider.getClassLikeSymbolByClassId(ClassId(SDK_PACKAGE, it))
                as? FirRegularClassSymbol
          }
          .filter { it.classKind == ClassKind.INTERFACE }

  /** [family]'s nested classes. */
  fun members(
      family: FirRegularClassSymbol,
      session: FirSession,
      scopeSession: ScopeSession,
  ): List<FirRegularClassSymbol> {
    val members = mutableListOf<FirRegularClassSymbol>()
    session.kotlinScopeProvider
        .getNestedClassifierScope(family.fir, session, scopeSession)
        ?.processAllClassifiers { (it as? FirRegularClassSymbol)?.let(members::add) }
    return members
  }

  /** The game's name of the prefab [symbol] stands for, from its `@PrefabName`, if it has one. */
  fun prefabNameOf(symbol: FirRegularClassSymbol, session: FirSession): String? =
      symbol.resolvedAnnotationsWithArguments
          .firstOrNull { it.toAnnotationClassId(session) == PREFAB_NAME }
          ?.argumentMapping
          ?.mapping
          ?.values
          ?.firstOrNull()
          ?.let { (it as? FirLiteralExpression)?.value as? String }

  /**
   * The family members each name could mean, by their qualified names (`Generator.StirlingEngine`),
   * sorted: a member is under its own name, its game prefab name and that name's
   * [K2Completion.gameNameWords] (`StirlingEngine`, `StructureStirlingEngine`).
   */
  fun membersByName(session: FirSession): Map<String, List<String>> {
    val scopeSession = ScopeSession()
    val byName = mutableMapOf<String, MutableSet<String>>()
    for (family in families(session)) {
      for (member in members(family, session, scopeSession)) {
        val qualified = nameOf(member.classId)
        val prefab = prefabNameOf(member, session)
        val names =
            listOfNotNull(
                member.classId.shortClassName.asString(),
                prefab,
                prefab?.let(K2Completion::gameNameWords),
            )
        for (name in names) byName.getOrPut(name) { sortedSetOf() } += qualified
      }
    }
    return byName.mapValues { it.value.toList() }
  }

  /**
   * [message], with the family members its unresolved name could mean ([membersNamed], as
   * [membersByName] gives them) suggested after it, when it is K2's `Unresolved reference
   * 'StirlingEngine'.` and there are any: `Did you mean Generator.StirlingEngine?`. A family's
   * member is written with the family's name, so a program that writes it alone, as the game's name
   * suggests, gets told where it is. [membersNamed] is only called for such a message.
   */
  fun withFamilyMembersNamed(message: String, membersNamed: (String) -> List<String>): String {
    val name = UNRESOLVED.matchEntire(message)?.groupValues?.get(1) ?: return message
    val members = membersNamed(name)
    if (members.isEmpty()) return message
    val shown = members.take(MAX_SUGGESTED)
    val choices =
        when {
          members.size > shown.size ->
              shown.joinToString(", ") + " or one of ${members.size - shown.size} more"
          shown.size == 1 -> shown.single()
          else -> shown.dropLast(1).joinToString(", ") + " or " + shown.last()
        }
    return "$message Did you mean $choices?"
  }

  private val UNRESOLVED = Regex("""Unresolved reference '([A-Za-z_]\w*)'\.""")
}
