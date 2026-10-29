@file:OptIn(org.jetbrains.kotlin.fir.symbols.SymbolInternals::class)

package ktrapic.frontend

import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirEnumEntrySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirVariableSymbol
import org.jetbrains.kotlin.fir.types.ConeCapturedType
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
import org.jetbrains.kotlin.fir.types.ConeTypeProjection
import org.jetbrains.kotlin.fir.types.ProjectionKind
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.fir.types.typeApproximator
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression
import org.jetbrains.kotlin.types.TypeApproximatorConfiguration

/**
 * [classId]'s name within its package, as a program names it in code: `LedDisplay.Small`, and a
 * class the program declares without the object the program is wrapped in (`P`, not `Program.P`). A
 * companion object is named by its class, as a program refers to it: a typed device's prefab is
 * `GrowLight`, not `GrowLight.Companion`. As a type, a companion is written otherwise (see
 * [render]).
 */
internal fun nameOf(classId: ClassId): String {
  val segments = writtenSegments(classId)
  return (if (segments.size > 1 && segments.last() == COMPANION) segments.dropLast(1) else segments)
      .joinToString(".")
}

/** [classId]'s names within its package, without the object the program is wrapped in. */
private fun writtenSegments(classId: ClassId): List<String> {
  val segments = classId.relativeClassName.pathSegments().map { it.asString() }
  return if (segments.size > 1 && segments.first() == PROGRAM_OBJECT) segments.drop(1) else segments
}

private val COMPANION = SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT.asString()

/** The SDK's `Prefab<T>`, which each typed device's companion object is. */
internal val PREFAB = ClassId.topLevel(FqName("ktrapic.Prefab"))

/** The SDK's `SlottedPrefab<T, B>`, which a prefab with slots's companion object is. */
internal val SLOTTED_PREFAB = ClassId.topLevel(FqName("ktrapic.SlottedPrefab"))

/**
 * The superclass a companion object ([classId]) is written as, as a program never writes the
 * companion itself: a typed device's prefab is the `Prefab<GrowLight>` or `SlottedPrefab<...>` it
 * is. Null for a companion whose only superclass is `Any`, which is written as its class's name
 * instead ([render]).
 */
private fun companionSuperclass(classId: ClassId, session: FirSession): ConeClassLikeType? {
  val symbol =
      session.symbolProvider.getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol
          ?: return null
  return symbol.resolvedSuperTypes.firstNotNullOfOrNull { supertype ->
    (supertype as? ConeClassLikeType)?.takeIf {
      val superclass =
          session.symbolProvider.getClassLikeSymbolByClassId(it.lookupTag.classId)
              as? FirRegularClassSymbol
      superclass?.classKind == ClassKind.CLASS && it.lookupTag.classId != StandardClassIds.Any
    }
  }
}

/** [classId]'s package and [nameOf]: `ktrapic.LedDisplay.Small`, or `P` in no package. */
internal fun qualifiedNameOf(classId: ClassId): String =
    if (classId.packageFqName.isRoot) nameOf(classId)
    else "${classId.packageFqName.asString()}.${nameOf(classId)}"

private val FUNCTION_TYPE = Regex("""Function\d+""")

/**
 * [type] as a program would write it: classes by their names (`LedDisplay.Small`), `Device?`, a
 * star projection as `Prefab<*>`, and a function type as `(Device) -> Double`. A companion object
 * is never written as `Companion`, which a program never writes: it is its superclass, so a typed
 * device's prefab is the `Prefab` it is (`Prefab<GrowLight>`), and a prefab with slots's the
 * `SlottedPrefab` (`SlottedPrefab<BatteryCharger.Standard, BatteryCharger.Standard.Batch>`). A
 * program's companion with no superclass but `Any` is its class's name (`P`), as the program refers
 * to it, though as a type that name is the class's. A captured type (what a star projection stands
 * for in one expression, as `at` on a `Prefab<*>` gives) is its approximation (`TypedDevice`).
 *
 * An extension function type renders as a plain one (`Int.() -> Int` as `(Int) -> Int`), and a
 * `suspend` function type as its class (`SuspendFunction1<Int, Int>`): the SDK declares neither and
 * KTrapIC compiles neither, so only the standard library's scope functions and a program's own
 * declarations would show them.
 */
internal fun render(type: ConeKotlinType, session: FirSession): String {
  if (hasCapturedType(type)) {
    session.typeApproximator
        .approximateToSuperType(
            type,
            TypeApproximatorConfiguration.FinalApproximationAfterResolutionAndInference,
        )
        ?.takeIf { !hasCapturedType(it) }
        ?.let {
          return render(it, session)
        }
  }
  if (type is ConeClassLikeType) {
    val classId = type.lookupTag.classId
    if (classId.shortClassName == SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT) {
      val text =
          companionSuperclass(classId, session)?.let { render(it, session) } ?: nameOf(classId)
      return if (type.isMarkedNullable) "$text?" else text
    }
    val arguments = type.typeArguments.mapNotNull { it.type }
    // A function type is the class `kotlin.FunctionN`, whose last type argument is its result.
    if (
        classId.packageFqName == StandardNames.BUILT_INS_PACKAGE_FQ_NAME &&
            FUNCTION_TYPE.matches(classId.relativeClassName.asString()) &&
            arguments.isNotEmpty() &&
            arguments.size == type.typeArguments.size
    ) {
      val text =
          arguments.dropLast(1).joinToString(", ", "(", ")") { render(it, session) } +
              " -> " +
              render(arguments.last(), session)
      return if (type.isMarkedNullable) "($text)?" else text
    }
    val projections = type.typeArguments.map { render(it, session) }
    val text =
        writtenSegments(classId).joinToString(".") +
            if (projections.isEmpty()) "" else projections.joinToString(", ", "<", ">")
    return if (type.isMarkedNullable) "$text?" else text
  }
  val text =
      when (type) {
        is ConeTypeParameterType -> type.lookupTag.name.asString()
        is ConeCapturedType ->
            return type.constructor.supertypes?.firstOrNull()?.let { render(it, session) } ?: "Any?"
        else -> type.toString()
      }
  return if (type.isMarkedNullable) "$text?" else text
}

/**
 * Whether [type] is or has as an argument a captured type, the compiler's own type for what a star
 * or variance projection stands for in one expression, which a program can't write.
 */
private fun hasCapturedType(type: ConeKotlinType): Boolean =
    type is ConeCapturedType || type.typeArguments.any { it.type?.let(::hasCapturedType) == true }

/** A type argument as a program would write it: `*`, `out Device`, or its type. */
private fun render(projection: ConeTypeProjection, session: FirSession): String {
  val type = projection.type ?: return "*"
  return when (projection.kind) {
    ProjectionKind.IN -> "in ${render(type, session)}"
    ProjectionKind.OUT -> "out ${render(type, session)}"
    else -> render(type, session)
  }
}

/**
 * A function's parameters as declared: `pin: Device`, one per parameter.
 *
 * A `vararg` parameter renders as its array type (`xs: IntArray`), and a default value is not
 * shown: KTrapIC programs and the SDK use no varargs, and the SDK no defaults.
 */
internal fun parametersOf(symbol: FirFunctionSymbol<*>, session: FirSession): List<String> =
    symbol.valueParameterSymbols.map { "${it.name}: ${render(it.resolvedReturnType, session)}" }

/**
 * A callable's declaration, as written in Kotlin without its body: `var Setting: Double`, `fun
 * at(pin: Device): LedDisplay.Small`, a constructor as its class and parameters (`P(a: Int)`), or
 * an enum entry's name. A local variable or a parameter reads as a `val`.
 */
internal fun declarationOf(symbol: FirCallableSymbol<*>, session: FirSession): String {
  val name = symbol.name.asString()
  fun parameters() = parametersOf(symbol as FirFunctionSymbol<*>, session).joinToString(", ")
  val type = render(symbol.resolvedReturnType, session)
  return when (symbol) {
    is FirEnumEntrySymbol -> name
    is FirConstructorSymbol -> "$type(${parameters()})"
    is FirFunctionSymbol<*> -> "fun $name(${parameters()}): $type"
    is FirVariableSymbol<*> -> {
      val keyword = if (symbol is FirPropertySymbol && !symbol.isVal) "var" else "val"
      "$keyword $name: $type"
    }
    else -> name
  }
}

/** A class's declaration: `interface LedDisplay`, `class LedDisplay.Small`, `enum class Color`. */
internal fun declarationOf(symbol: FirClassLikeSymbol<*>): String {
  val name = nameOf(symbol.classId)
  val keyword =
      when ((symbol as? FirRegularClassSymbol)?.classKind) {
        ClassKind.INTERFACE -> "interface"
        ClassKind.OBJECT -> if (symbol.resolvedStatus.isCompanion) "companion object" else "object"
        ClassKind.ENUM_CLASS -> "enum class"
        ClassKind.ANNOTATION_CLASS -> "annotation class"
        null -> "typealias"
        else -> "class"
      }
  return "$keyword $name"
}

/** Whether [leaf] is in a comment or in a string's text (not in a `${...}` inside it). */
internal fun inCommentOrString(leaf: PsiElement?): Boolean {
  var element = leaf
  while (element != null && element !is KtFile) {
    if (element is PsiComment || element is KtLiteralStringTemplateEntry) return true
    if (element is KtStringTemplateEntryWithExpression) return false
    element = element.parent
  }
  return false
}
