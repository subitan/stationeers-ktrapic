@file:OptIn(org.jetbrains.kotlin.fir.symbols.SymbolInternals::class)

package ktrapic.frontend

import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.descriptors.ClassKind
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
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression

/**
 * [classId]'s name within its package, as a program writes it: `LedDisplay.Small`, and a class the
 * program declares without the object the program is wrapped in (`P`, not `Program.P`). A companion
 * object goes unnamed, as a program refers to it by its class: a typed device's prefab is
 * `GrowLight`, not `GrowLight.Companion`, and the batch type of a prefab with slots is
 * `BatteryCharger.Standard.Batch`.
 */
internal fun nameOf(classId: ClassId): String {
  var segments = classId.relativeClassName.pathSegments().map { it.asString() }
  if (segments.size > 1 && segments.first() == PROGRAM_OBJECT) segments = segments.drop(1)
  segments = listOf(segments.first()) + segments.drop(1).filter { it != COMPANION }
  return segments.joinToString(".")
}

private val COMPANION = SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT.asString()

/** [classId]'s package and [nameOf]: `ktrapic.LedDisplay.Small`, or `P` in no package. */
internal fun qualifiedNameOf(classId: ClassId): String =
    if (classId.packageFqName.isRoot) nameOf(classId)
    else "${classId.packageFqName.asString()}.${nameOf(classId)}"

private val FUNCTION_TYPE = Regex("""Function\d+""")

/**
 * [type] as a program would write it: classes by [nameOf], `Device?`, and a function type as
 * `(Device) -> Double`.
 *
 * An extension function type renders as a plain one (`Int.() -> Int` as `(Int) -> Int`), and a
 * `suspend` function type as its class (`SuspendFunction1<Int, Int>`): the SDK declares neither and
 * KTrapIC compiles neither, so only the standard library's scope functions and a program's own
 * declarations would show them.
 */
internal fun render(type: ConeKotlinType): String {
  if (type is ConeClassLikeType) {
    val classId = type.lookupTag.classId
    val arguments = type.typeArguments.mapNotNull { it.type }
    // A function type is the class `kotlin.FunctionN`, whose last type argument is its result.
    if (
        classId.packageFqName == StandardNames.BUILT_INS_PACKAGE_FQ_NAME &&
            FUNCTION_TYPE.matches(classId.relativeClassName.asString()) &&
            arguments.isNotEmpty() &&
            arguments.size == type.typeArguments.size
    ) {
      val text =
          arguments.dropLast(1).joinToString(", ", "(", ")") { render(it) } +
              " -> " +
              render(arguments.last())
      return if (type.isMarkedNullable) "($text)?" else text
    }
    val text =
        nameOf(classId) +
            if (arguments.isEmpty()) "" else arguments.joinToString(", ", "<", ">") { render(it) }
    return if (type.isMarkedNullable) "$text?" else text
  }
  val text = if (type is ConeTypeParameterType) type.lookupTag.name.asString() else type.toString()
  return if (type.isMarkedNullable) "$text?" else text
}

/**
 * A function's parameters as declared: `pin: Device`, one per parameter.
 *
 * A `vararg` parameter renders as its array type (`xs: IntArray`), and a default value is not
 * shown: KTrapIC programs and the SDK use no varargs, and the SDK no defaults.
 */
internal fun parametersOf(symbol: FirFunctionSymbol<*>): List<String> =
    symbol.valueParameterSymbols.map { "${it.name}: ${render(it.resolvedReturnType)}" }

/**
 * A callable's declaration, as written in Kotlin without its body: `var Setting: Double`, `fun
 * at(pin: Device): LedDisplay.Small`, a constructor as its class and parameters (`P(a: Int)`), or
 * an enum entry's name. A local variable or a parameter reads as a `val`.
 */
internal fun declarationOf(symbol: FirCallableSymbol<*>): String {
  val name = symbol.name.asString()
  return when (symbol) {
    is FirEnumEntrySymbol -> name
    is FirConstructorSymbol ->
        "${render(symbol.resolvedReturnType)}(${parametersOf(symbol).joinToString(", ")})"
    is FirFunctionSymbol<*> ->
        "fun $name(${parametersOf(symbol).joinToString(", ")}): ${render(symbol.resolvedReturnType)}"
    is FirVariableSymbol<*> -> {
      val keyword = if (symbol is FirPropertySymbol && !symbol.isVal) "var" else "val"
      "$keyword $name: ${render(symbol.resolvedReturnType)}"
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
