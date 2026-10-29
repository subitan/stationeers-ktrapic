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
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
import org.jetbrains.kotlin.fir.types.ConeTypeProjection
import org.jetbrains.kotlin.fir.types.ProjectionKind
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression

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

/**
 * The `Prefab<T>` a typed device's companion object ([classId]) is, which a program writes as its
 * type (`Prefab<GrowLight>`); null for any other class.
 */
private fun prefabOf(classId: ClassId, session: FirSession): ConeClassLikeType? {
  if (classId.shortClassName != SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT) return null
  val symbol = session.symbolProvider.getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol
  return symbol?.resolvedSuperTypes?.firstNotNullOfOrNull { supertype ->
    (supertype as? ConeClassLikeType)?.takeIf { it.lookupTag.classId == PREFAB }
  }
}

/** [classId]'s package and [nameOf]: `ktrapic.LedDisplay.Small`, or `P` in no package. */
internal fun qualifiedNameOf(classId: ClassId): String =
    if (classId.packageFqName.isRoot) nameOf(classId)
    else "${classId.packageFqName.asString()}.${nameOf(classId)}"

private val FUNCTION_TYPE = Regex("""Function\d+""")

/**
 * [type] as a program would write it: classes by their names (`LedDisplay.Small`), `Device?`, a
 * star projection as `Prefab<*>`, and a function type as `(Device) -> Double`. A typed device's
 * companion object, its prefab, is the `Prefab` it is (`Prefab<GrowLight>`), as the companion's own
 * type can't be told from the device's by name; another companion is `P.Companion`.
 *
 * An extension function type renders as a plain one (`Int.() -> Int` as `(Int) -> Int`), and a
 * `suspend` function type as its class (`SuspendFunction1<Int, Int>`): the SDK declares neither and
 * KTrapIC compiles neither, so only the standard library's scope functions and a program's own
 * declarations would show them.
 */
internal fun render(type: ConeKotlinType, session: FirSession): String {
  if (type is ConeClassLikeType) {
    val classId = type.lookupTag.classId
    prefabOf(classId, session)?.let { prefab ->
      val text = render(prefab, session)
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
  val text = if (type is ConeTypeParameterType) type.lookupTag.name.asString() else type.toString()
  return if (type.isMarkedNullable) "$text?" else text
}

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
