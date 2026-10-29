@file:OptIn(org.jetbrains.kotlin.fir.symbols.SymbolInternals::class)

package ktrapic.frontend

import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
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
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression

/** [type] as a program would write it: classes by their name within the package, `Device?`. */
internal fun render(type: ConeKotlinType): String {
  val text =
      when (type) {
        is ConeClassLikeType -> {
          val arguments = type.typeArguments.mapNotNull { it.type }
          type.lookupTag.classId.relativeClassName.asString() +
              if (arguments.isEmpty()) "" else arguments.joinToString(", ", "<", ">") { render(it) }
        }
        is ConeTypeParameterType -> type.lookupTag.name.asString()
        else -> type.toString()
      }
  return if (type.isMarkedNullable) "$text?" else text
}

/** A function's parameters as declared: `pin: Device`, one per parameter. */
internal fun parametersOf(symbol: FirFunctionSymbol<*>): List<String> =
    symbol.valueParameterSymbols.map { "${it.name}: ${render(it.resolvedReturnType)}" }

/**
 * A callable's declaration, as written in Kotlin without its body: `var Setting: Double`, `fun
 * at(pin: Device): LedDisplay.Small`, or an enum entry's name. A local variable or a parameter
 * reads as a `val`.
 */
internal fun declarationOf(symbol: FirCallableSymbol<*>): String {
  val name = symbol.name.asString()
  return when (symbol) {
    is FirEnumEntrySymbol -> name
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
  val name = symbol.classId.relativeClassName.asString()
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
