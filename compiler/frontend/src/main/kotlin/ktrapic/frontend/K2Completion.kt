@file:OptIn(org.jetbrains.kotlin.fir.symbols.SymbolInternals::class)

package ktrapic.frontend

import java.io.File
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirImport
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.FirWhenExpression
import org.jetbrains.kotlin.fir.pipeline.SingleModuleFrontendOutput
import org.jetbrains.kotlin.fir.references.FirNamedReference
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.resolve.scope
import org.jetbrains.kotlin.fir.scopes.CallableCopyTypeCalculator
import org.jetbrains.kotlin.fir.scopes.kotlinScopeProvider
import org.jetbrains.kotlin.fir.scopes.processAllCallables
import org.jetbrains.kotlin.fir.scopes.processAllClassifiers
import org.jetbrains.kotlin.fir.scopes.unsubstitutedScope
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassifierSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirEnumEntrySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeAliasSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirVariableSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeDefinitelyNotNullType
import org.jetbrains.kotlin.fir.types.ConeFlexibleType
import org.jetbrains.kotlin.fir.types.ConeIntegerLiteralType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeStarProjection
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
import org.jetbrains.kotlin.fir.types.ConeTypeProjection
import org.jetbrains.kotlin.fir.types.constructClassType
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.fir.types.typeContext
import org.jetbrains.kotlin.fir.unwrapFakeOverrides
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.name.isSubpackageOf
import org.jetbrains.kotlin.types.AbstractTypeChecker

/** A name that could go at the caret, with what kind of thing it names. */
data class Completion(val label: String, val kind: Kind, val detail: String) {
  enum class Kind {
    LOCAL,
    PROPERTY,
    FUNCTION,
    CLASS,
    INTERFACE,
    OBJECT,
    ENUM,
    ENUM_ENTRY,
    KEYWORD,
  }
}

/**
 * Lists what could be written at a position in a script-style program, from the Kotlin K2
 * compiler's analysis of it: after `receiver.`, the members and extensions of the receiver's type
 * (or, after a class or object name, what it holds); anywhere else, the locals, parameters and
 * members in scope there, the SDK's top-level names, and keywords. Of the standard library's
 * declarations, only those KTrapIC compiles are offered, on the receivers it compiles them on
 * ([SUPPORTED_STDLIB]); of the keywords, only those a program can use ([KEYWORDS]). A name shadowed
 * by one declared nearer the caret is offered once.
 *
 * The program is analysed with a placeholder name in place of the word at the caret, so the
 * reference there is the only one the analysis need not resolve. Errors elsewhere in the program
 * don't stop it: K2 still types what it can.
 */
object K2Completion {
  /** The packages whose top-level names a program sees, as `ktrapic.*` imports them. */
  private val IMPORTED_PACKAGES = listOf(FqName("ktrapic"))

  /** The standard library's packages: `kotlin` and those under it. */
  private val KOTLIN = FqName("kotlin")

  /**
   * The standard library packages whose extensions KTrapIC compiles some of. `kotlin.ranges` and
   * `kotlin.collections` are imported by default; `kotlin.math` only where the program imports it.
   */
  private val EXTENSION_PACKAGES =
      listOf("kotlin.math", "kotlin.ranges", "kotlin.collections").map(::FqName)

  private val DEFAULT_IMPORTED = setOf(FqName("kotlin.ranges"), FqName("kotlin.collections"))

  /** Int and Double arithmetic, which takes either type. */
  private val ARITHMETIC =
      listOf("plus", "minus", "times", "div", "rem").flatMap { listOf("$it(Int)", "$it(Double)") }

  /** What a static set offers, as its members are declared on [owner]. */
  private fun staticSet(owner: String) =
      listOf("$owner.contains", "$owner.isEmpty", "$owner.size") +
          listOf("plus(T)", "plus(Iterable<T>)", "minus(T)", "minus(Iterable<T>)", "intersect")
              .map { "kotlin.collections.$it" }

  /**
   * The standard library's declarations KTrapIC compiles, by the class of the receiver they are
   * offered on. Each is a declaration's fully qualified name, a member's (`kotlin.Int.toDouble`) or
   * an extension's (`kotlin.math.roundToInt`), followed by the parameter types of the one overload
   * meant where only some of them compile (`kotlin.ranges.until(Int)`, not `until(Byte)`). Of the
   * standard library's declarations, only these are offered, and only on these receivers; see
   * docs/LANGUAGE.md.
   */
  val SUPPORTED_STDLIB: Map<String, Set<String>> =
      mapOf(
              "kotlin.Int" to
                  listOf(
                          "and",
                          "dec",
                          "inc",
                          "inv",
                          "or",
                          "rangeTo(Int)",
                          "rangeUntil(Int)",
                          "shl",
                          "shr",
                          "toDouble",
                          "toInt",
                          "unaryMinus",
                          "unaryPlus",
                          "ushr",
                          "xor",
                      )
                      .map { "kotlin.Int.$it" } +
                      ARITHMETIC.map { "kotlin.Int.$it" } +
                      listOf(
                          "kotlin.math.absoluteValue",
                          "kotlin.ranges.coerceAtLeast(Int)",
                          "kotlin.ranges.coerceAtMost(Int)",
                          "kotlin.ranges.coerceIn(Int, Int)",
                          "kotlin.ranges.downTo(Int)",
                          "kotlin.ranges.until(Int)",
                      ),
              "kotlin.Double" to
                  listOf("dec", "inc", "toDouble", "toInt", "unaryMinus", "unaryPlus").map {
                    "kotlin.Double.$it"
                  } +
                      ARITHMETIC.map { "kotlin.Double.$it" } +
                      listOf(
                          "kotlin.math.absoluteValue",
                          "kotlin.math.pow(Double)",
                          "kotlin.math.pow(Int)",
                          "kotlin.math.roundToInt",
                          "kotlin.ranges.coerceAtLeast(Double)",
                          "kotlin.ranges.coerceAtMost(Double)",
                          "kotlin.ranges.coerceIn(Double, Double)",
                      ),
              "kotlin.Boolean" to listOf("kotlin.Boolean.not"),
              "kotlin.collections.Set" to staticSet("kotlin.collections.Set"),
              "kotlin.collections.List" to staticSet("kotlin.collections.List"),
              "kotlin.enums.EnumEntries" to staticSet("kotlin.collections.List"),
              // A range can only be written in a `for`, where only a step can follow it.
              "kotlin.ranges.IntRange" to listOf("kotlin.ranges.step"),
              "kotlin.ranges.IntProgression" to listOf("kotlin.ranges.step"),
              // A `Ratios` loop's element.
              "kotlin.collections.Map.Entry" to
                  listOf("kotlin.collections.Map.Entry.key", "kotlin.collections.Map.Entry.value"),
          )
          .mapValues { (_, declarations) -> declarations.toSet() }

  /**
   * The standard library's top-level functions KTrapIC compiles, written as [SUPPORTED_STDLIB]'s
   * are: the static sets' builders and the maths functions (docs/LANGUAGE.md, "Static sets" and
   * "Intrinsics and maths"), which the backend's `Translator` compiles in `staticSetCall` and
   * `math`. Of the standard library's top-level functions, only these are offered. Completion lists
   * none of the standard library's top-level functions by name; signature help lists these.
   */
  val TOP_LEVEL_STDLIB: Set<String> =
      (listOf("emptyList", "emptySet", "listOf", "setOf").map { "kotlin.collections.$it" } +
              listOf(
                      "acos",
                      "asin",
                      "atan",
                      "ceil",
                      "cos",
                      "exp",
                      "floor",
                      "ln",
                      "round",
                      "sin",
                      "sqrt",
                      "tan",
                      "truncate",
                  )
                  .map { "kotlin.math.$it(Double)" } +
              listOf(
                      "abs(Double)",
                      "abs(Int)",
                      "atan2(Double, Double)",
                      "max(Double, Double)",
                      "max(Int, Int)",
                      "min(Double, Double)",
                      "min(Int, Int)",
                  )
                  .map { "kotlin.math.$it" })
          .toSet()

  /** The extensions named in [SUPPORTED_STDLIB], by package. */
  private val STDLIB_EXTENSIONS: Map<FqName, Set<Name>> =
      SUPPORTED_STDLIB.values
          .flatten()
          .map { FqName(it.substringBefore('(')) }
          .filter { it.parent() in EXTENSION_PACKAGES }
          .groupBy({ it.parent() }, { it.shortName() })
          .mapValues { (_, names) -> names.toSet() }

  /** The enums' static members the compiler makes, of which only `Substance.entries` compiles. */
  private val ENUM_STATICS = setOf("entries", "values", "valueOf")

  private val SUBSTANCE = ClassId.fromString("ktrapic/Substance")

  private const val PLACEHOLDER = "ktrapicCompletionPlaceholder"

  /** The keywords a KTrapIC program can use. */
  val KEYWORDS =
      listOf(
          "val",
          "var",
          "fun",
          "if",
          "else",
          "when",
          "while",
          "for",
          "do",
          "in",
          "break",
          "continue",
          "return",
          "true",
          "false",
      )

  /** `Any`'s members, which every type has and no program needs offered. */
  private val ANY_MEMBERS = setOf("equals", "hashCode", "toString")

  /** Kotlin's hard keywords, which can name a declaration only in backticks. */
  private val HARD_KEYWORDS =
      setOf(
          "as",
          "break",
          "class",
          "continue",
          "do",
          "else",
          "false",
          "for",
          "fun",
          "if",
          "in",
          "interface",
          "is",
          "null",
          "object",
          "package",
          "return",
          "super",
          "this",
          "throw",
          "true",
          "try",
          "typealias",
          "typeof",
          "val",
          "var",
          "when",
          "while",
      )

  /**
   * Whether [name] can be written without backticks. A name that needs them is not offered: the
   * editors complete the word before the caret, which never starts with a backtick.
   */
  private fun isPlainIdentifier(name: String): Boolean =
      name.isNotEmpty() &&
          (name[0].isLetter() || name[0] == '_') &&
          name.all { it.isLetterOrDigit() || it == '_' } &&
          name !in HARD_KEYWORDS

  /**
   * Whether a member or a top-level declaration is offered, on [receiver] if it follows one: one a
   * program can use, which is public, not deprecated, not one of the SDK's internals (named with a
   * leading `_`) nor of [ANY_MEMBERS], not an `iterator` operator, and, if the standard library
   * declares it, in [TOP_LEVEL_STDLIB] for a top-level function, else in [SUPPORTED_STDLIB] for
   * [receiver]'s class.
   */
  internal fun shown(
      symbol: FirCallableSymbol<*>,
      receiver: ConeKotlinType?,
      session: FirSession,
  ): Boolean {
    val name = symbol.name
    if (name.isSpecial || name.asString().startsWith("_") || name.asString() in ANY_MEMBERS) {
      return false
    }
    // What a `for` loop calls to go over a value, which a program can't call itself.
    if (name.asString() == "iterator" && symbol.resolvedStatus.isOperator) return false
    val declared = symbol.fir.unwrapFakeOverrides().symbol.callableId ?: return false
    if (declared.packageName.isSubpackageOf(KOTLIN)) {
      val topLevel =
          symbol is FirFunctionSymbol<*> &&
              declared.classId == null &&
              symbol.resolvedReceiverType == null
      val supported =
          if (topLevel) TOP_LEVEL_STDLIB
          else SUPPORTED_STDLIB[receiver?.let(::classOf)] ?: return false
      val fqName = declared.asSingleFqName().asString()
      val parameters =
          (symbol as? FirFunctionSymbol<*>)?.valueParameterSymbols?.joinToString(", ") {
            render(it.resolvedReturnType, session)
          }
      if (fqName !in supported && "$fqName($parameters)" !in supported) return false
    }
    if (symbol.resolvedStatus.visibility != Visibilities.Public) return false
    return symbol.resolvedAnnotationClassIds.none { it == StandardClassIds.Annotations.Deprecated }
  }

  /**
   * [type] as a program could declare it: an integer literal's type (`3`, `-3`, which K2 types as
   * any integer type the value fits until something picks one) as `Int`, a flexible type as its
   * lower bound, and a definitely-non-null type (`T & Any`) as `T`. The supported declarations are
   * keyed by such a type's class.
   */
  internal fun declarable(type: ConeKotlinType): ConeKotlinType =
      when (type) {
        is ConeIntegerLiteralType -> type.getApproximatedType()
        is ConeFlexibleType -> declarable(type.lowerBound)
        is ConeDefinitelyNotNullType -> declarable(type.original)
        else -> type
      }

  /**
   * Whether a program with [imports] sees [pkg]'s [name] unqualified: imported by default or by the
   * program.
   */
  private fun sees(imports: List<FirImport>, pkg: FqName, name: Name): Boolean =
      pkg in DEFAULT_IMPORTED ||
          imports.any {
            it.aliasName == null &&
                (it.isAllUnder && it.importedFqName == pkg ||
                    !it.isAllUnder && it.importedFqName == pkg.child(name))
          }

  /**
   * The packages, of the SDK's and those in [EXTENSION_PACKAGES], whose top-level functions and
   * extensions named [name] a program with [imports] calls unqualified.
   */
  internal fun packagesSeen(imports: List<FirImport>, name: Name): List<FqName> =
      IMPORTED_PACKAGES + EXTENSION_PACKAGES.filter { sees(imports, it, name) }

  /** Whether an extension of [receiver] can be called on a value of [type]. */
  internal fun accepts(
      session: FirSession,
      receiver: ConeKotlinType,
      type: ConeKotlinType,
  ): Boolean {
    val context = session.typeContext
    if (AbstractTypeChecker.isSubtypeOf(context, type, receiver)) return true
    // A generic receiver, such as `Iterable<T>`: any type of its class, or of a subclass, will
    // do.
    if (receiver !is ConeClassLikeType) return false
    if (receiver.typeArguments.none { it.type is ConeTypeParameterType }) return false
    val anyArguments =
        receiver.lookupTag.constructClassType(
            Array(receiver.typeArguments.size) { ConeStarProjection },
            receiver.isMarkedNullable,
        )
    return AbstractTypeChecker.isSubtypeOf(context, type, anyArguments)
  }

  /** The fully qualified name of [type]'s class, or null for a type that has none. */
  private fun classOf(type: ConeKotlinType): String? =
      (type as? ConeClassLikeType)?.lookupTag?.classId?.asFqNameString()

  /**
   * What could be written at [offset] (a `String` index) in [source], compiled against
   * kotlin-stdlib and the jars in [classpath] with [defaultImports]. Returns an empty list when
   * nothing applies, such as inside a comment or a string.
   */
  fun complete(
      source: String,
      offset: Int,
      classpath: List<File> = emptyList(),
      defaultImports: List<String> = emptyList(),
  ): List<Completion> {
    val caret = offset.coerceIn(0, source.length)
    var start = caret
    while (start > 0 && Character.isJavaIdentifierPart(source[start - 1])) start--
    var end = caret
    while (end < source.length && Character.isJavaIdentifierPart(source[end])) end++
    if (start < source.length && start < end && Character.isDigit(source[start])) return emptyList()
    val edited = source.substring(0, start) + PLACEHOLDER + source.substring(end)

    return withScript(edited, classpath, defaultImports) { parsed ->
      val script = parsed.script
      if (inCommentOrString(script.findElementAt(script.text.indexOf(PLACEHOLDER)))) {
        return@withScript emptyList()
      }
      val at = parsed.wrapped.text.indexOf(PLACEHOLDER)
      if (at < 0) return@withScript emptyList()
      parsed.analyse { Analysis(it, at).completions() }.orEmpty()
    }
  }

  private class Analysis(output: SingleModuleFrontendOutput, private val at: Int) {
    private val session: FirSession = output.session
    private val scopeSession: ScopeSession = output.scopeSession
    private val path = mutableListOf<FirElement>()
    private var access: FirQualifiedAccessExpression? = null
    private val imports = output.fir.flatMap { it.imports }

    init {
      val visitor =
          object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
              val source = element.source
              if (source != null && source.startOffset <= at && at <= source.endOffset) {
                path += element
              }
              if (
                  element is FirQualifiedAccessExpression &&
                      (element.calleeReference as? FirNamedReference)?.name?.asString() ==
                          PLACEHOLDER
              ) {
                access = element
              }
              element.acceptChildren(this)
            }
          }
      output.fir.forEach { it.accept(visitor) }
    }

    fun completions(): List<Completion> {
      val receiver = access?.explicitReceiver
      val items = if (receiver != null) members(receiver) else inScope() + topLevel() + keywords()
      // One item per name: overloads share one, and a name declared nearer the caret shadows
      // the same name declared further out, which comes later in the list.
      return items
          .filter { it.kind == Completion.Kind.KEYWORD || isPlainIdentifier(it.label) }
          .distinctBy { it.label }
    }

    /** What can follow `receiver.`. */
    private fun members(
        receiver: org.jetbrains.kotlin.fir.expressions.FirExpression
    ): List<Completion> {
      if (receiver is FirResolvedQualifier) {
        val symbol = receiver.qualifierSymbol as? FirRegularClassSymbol ?: return emptyList()
        return statics(symbol).sortedBy { it.label.lowercase() }
      }
      val type = declarable(receiver.resolvedType)
      // An enum's entry is used only as it is: KTrapIC compiles none of its members, such as an
      // SDK enum's `value`, the game's number for the entry, which the compiler writes itself.
      val classId = (type as? ConeClassLikeType)?.lookupTag?.classId
      val klass = classId?.let { session.symbolProvider.getClassLikeSymbolByClassId(it) }
      if ((klass as? FirRegularClassSymbol)?.classKind == ClassKind.ENUM_CLASS) return emptyList()
      val found = mutableListOf<FirCallableSymbol<*>>()
      val scope =
          type.scope(
              session,
              scopeSession,
              CallableCopyTypeCalculator.DoNothing,
              FirResolvePhase.STATUS,
          )
      // A prefab whose device isn't known when compiling, such as a device's `PrefabHash`
      // (`Prefab<*>`), only compares: `at`, `all` and `named` need a typed device's prefab.
      val unknownPrefab =
          classId == PREFAB && !isTypedDevice((type as ConeClassLikeType).typeArguments.first())
      scope?.processAllCallables {
        val onPrefab = it.fir.unwrapFakeOverrides().symbol.callableId?.classId == PREFAB
        if (shown(it, type, session) && !(unknownPrefab && onPrefab)) found += it
      }
      found += extensions(type)
      // Overloads share one item, whose detail is the first's: the one that fits [type] best.
      return found
          .sortedBy { fit(it, type) }
          .map { callable(it, local = false) }
          .sortedBy { it.label.lowercase() }
    }

    /** Whether [argument] is a typed device's class, such as `GrowLight`: a final class. */
    private fun isTypedDevice(argument: ConeTypeProjection): Boolean {
      val classId = (argument.type as? ConeClassLikeType)?.lookupTag?.classId ?: return false
      val klass =
          session.symbolProvider.getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol
      return klass?.classKind == ClassKind.CLASS && klass.resolvedStatus.modality == Modality.FINAL
    }

    /**
     * How well an overload fits a call on [type], lowest best: one taking only [type] itself
     * (`Double.plus(Double)` on a `Double`) before one taking anything else, and an extension of
     * [type]'s own class (`Set.plus` on a `Set`) before one of a supertype (`Iterable.plus`).
     */
    private fun fit(symbol: FirCallableSymbol<*>, type: ConeKotlinType): Int {
      val own = render(type, session)
      val parameters = (symbol as? FirFunctionSymbol<*>)?.valueParameterSymbols.orEmpty()
      val byParameters =
          if (parameters.all { render(it.resolvedReturnType, session) == own }) 0 else 2
      val extended = symbol.resolvedReceiverType ?: return byParameters
      return byParameters + if (classOf(extended) == classOf(type)) 0 else 1
    }

    /** What a class or object's name gives: its nested classes, static members and own members. */
    private fun statics(symbol: FirRegularClassSymbol): List<Completion> {
      val klass = symbol.fir
      val provider = session.kotlinScopeProvider
      val items = mutableListOf<Completion>()
      provider.getNestedClassifierScope(klass, session, scopeSession)?.processAllClassifiers {
        classifier(it)?.let(items::add)
      }
      val enum = klass.classKind == ClassKind.ENUM_CLASS
      provider.getStaticCallableMemberScope(klass, session, scopeSession)?.processAllCallables {
        val name = it.name.asString()
        val made = enum && name in ENUM_STATICS
        if (
            shown(it, null, session) && (!made || symbol.classId == SUBSTANCE && name == "entries")
        ) {
          items += callable(it, local = false)
        }
      }
      val companion = klass.companionObjectSymbol
      val holder = if (klass.classKind == ClassKind.OBJECT) symbol else companion
      holder
          ?.unsubstitutedScope(session, scopeSession, false, FirResolvePhase.STATUS)
          ?.processAllCallables {
            if (shown(it, null, session)) items += callable(it, local = false)
          }
      return items
    }

    /**
     * The extensions whose receiver [type] can be: the imported packages' and the supported
     * standard library extensions the program sees.
     */
    private fun extensions(type: ConeKotlinType): List<FirCallableSymbol<*>> {
      val candidates =
          IMPORTED_PACKAGES.flatMap { pkg -> names(pkg).callables.map { pkg to it } } +
              STDLIB_EXTENSIONS.flatMap { (pkg, names) ->
                names.map { pkg to it }.filter { (_, name) -> sees(pkg, name) }
              }
      return candidates.flatMap { (pkg, name) ->
        session.symbolProvider.getTopLevelCallableSymbols(pkg, name).filter {
          shown(it, type, session) &&
              accepts(session, it.resolvedReceiverType ?: return@filter false, type)
        }
      }
    }

    private fun sees(pkg: FqName, name: Name): Boolean = sees(imports, pkg, name)

    /** The locals, parameters and members in scope at the caret. */
    private fun inScope(): List<Completion> {
      val items = mutableListOf<Completion>()
      // The program's top-level code runs in order, as a script's does, so there a top-level
      // property declared after the caret isn't set yet. A function's body, or a class's, runs
      // when the function is called or the class made, and may read any of them.
      val inOrder = path.none { it is FirNamedFunction || it is FirRegularClass && !isProgram(it) }
      for (element in path.asReversed()) {
        when (element) {
          is FirBlock ->
              for (statement in element.statements) {
                val source = statement.source ?: continue
                if (source.startOffset >= at || statement in path) continue
                when (statement) {
                  is FirProperty -> local(statement.symbol)?.let(items::add)
                  is FirNamedFunction -> local(statement.symbol)?.let(items::add)
                  is FirRegularClass -> classifier(statement.symbol)?.let(items::add)
                  else -> {}
                }
              }
          is FirFunction ->
              for (parameter in element.valueParameters) {
                local(parameter.symbol)?.let(items::add)
              }
          // `when (val v = ...)`: the subject's variable is in scope in the branches.
          is FirWhenExpression ->
              element.subjectVariable
                  ?.takeIf { it !in path }
                  ?.let { local(it.symbol) }
                  ?.let(items::add)
          is FirRegularClass -> {
            val program = isProgram(element)
            element.symbol
                .unsubstitutedScope(session, scopeSession, false, FirResolvePhase.STATUS)
                .processAllCallables {
                  val later = it is FirPropertySymbol && (it.source?.startOffset ?: 0) > at
                  // The caret is in its own initializer or body: a property can't read itself,
                  // and a function can't call itself. (A function that calls it is still offered,
                  // though calling that one there is recursion too, which doesn't compile.)
                  val around = it.fir in path
                  if (shown(it, null, session) && !(program && inOrder && later) && !around) {
                    items += callable(it, local = false)
                  }
                }
            session.kotlinScopeProvider
                .getNestedClassifierScope(element, session, scopeSession)
                ?.processAllClassifiers { classifier(it)?.let(items::add) }
          }
          else -> {}
        }
      }
      return items
    }

    /**
     * Whether [klass] is the object the program's top-level declarations and code are wrapped in.
     */
    private fun isProgram(klass: FirRegularClass): Boolean =
        klass.symbol.classId.shortClassName.asString() == PROGRAM_OBJECT

    /** The imported packages' classes, objects, functions and properties. */
    private fun topLevel(): List<Completion> =
        IMPORTED_PACKAGES.flatMap { pkg ->
              val names = names(pkg)
              names.classifiers.mapNotNull {
                session.symbolProvider
                    .getClassLikeSymbolByClassId(ClassId(pkg, it))
                    ?.let(::classifier)
              } +
                  names.callables.flatMap { name ->
                    session.symbolProvider
                        .getTopLevelCallableSymbols(pkg, name)
                        .filter { shown(it, null, session) && it.resolvedReceiverType == null }
                        .map { callable(it, local = false) }
                  }
            }
            .sortedBy { it.label.lowercase() }

    private fun keywords() = KEYWORDS.map { Completion(it, Completion.Kind.KEYWORD, "") }

    private class Names(
        val classifiers: Set<org.jetbrains.kotlin.name.Name>,
        val callables: Set<org.jetbrains.kotlin.name.Name>,
    )

    private fun names(pkg: FqName): Names {
      val provider = session.symbolProvider.symbolNamesProvider
      return Names(
          provider.getTopLevelClassifierNamesInPackage(pkg).orEmpty(),
          provider.getTopLevelCallableNamesInPackage(pkg).orEmpty(),
      )
    }

    /**
     * A local declaration or parameter, or null for one the compiler made, such as a `for` loop's
     * `<iterator>` or a destructuring declaration's `<destruct>`, which have special names.
     */
    private fun local(symbol: FirCallableSymbol<*>): Completion? =
        if (symbol.name.isSpecial) null else callable(symbol, local = true)

    private fun callable(symbol: FirCallableSymbol<*>, local: Boolean): Completion {
      val kind =
          when (symbol) {
            is FirEnumEntrySymbol -> Completion.Kind.ENUM_ENTRY
            is FirFunctionSymbol<*> -> Completion.Kind.FUNCTION
            is FirVariableSymbol<*> ->
                if (local) Completion.Kind.LOCAL else Completion.Kind.PROPERTY
            else -> Completion.Kind.PROPERTY
          }
      return Completion(symbol.name.asString(), kind, declarationOf(symbol, session))
    }

    private fun classifier(symbol: FirClassifierSymbol<*>): Completion? {
      val classLike = symbol as? FirClassLikeSymbol<*> ?: return null
      val name = classLike.classId.shortClassName
      if (name.isSpecial || name.asString().startsWith("_")) return null
      if (classLike.resolvedStatus.visibility != Visibilities.Public) return null
      // A companion is used through its class's name (`GrowLight.all`), never named itself.
      if (classLike.resolvedStatus.isCompanion) return null
      val label = name.asString()
      val kind =
          when ((classLike as? FirRegularClassSymbol)?.classKind) {
            ClassKind.INTERFACE -> Completion.Kind.INTERFACE
            ClassKind.OBJECT -> Completion.Kind.OBJECT
            ClassKind.ENUM_CLASS -> Completion.Kind.ENUM
            ClassKind.ANNOTATION_CLASS -> return null
            else ->
                if (classLike is FirTypeAliasSymbol) Completion.Kind.CLASS
                else Completion.Kind.CLASS
          }
      return Completion(label, kind, qualifiedNameOf(classLike.classId))
    }
  }
}
