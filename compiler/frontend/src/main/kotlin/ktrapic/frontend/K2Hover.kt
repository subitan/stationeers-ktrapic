@file:OptIn(org.jetbrains.kotlin.fir.symbols.SymbolInternals::class)

package ktrapic.frontend

import java.io.File
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtRealSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.containingClassLookupTag
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.pipeline.SingleModuleFrontendOutput
import org.jetbrains.kotlin.fir.references.FirErrorNamedReference
import org.jetbrains.kotlin.fir.references.FirNamedReference
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.diagnostics.ConeDiagnosticWithCandidates
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.resolve.scope
import org.jetbrains.kotlin.fir.scopes.CallableCopyTypeCalculator
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.unwrapFakeOverrides
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.StandardClassIds

/**
 * What a name in a program is, for a hover: its [declaration] (`var Text: String`), the class it is
 * a member of (`LedDisplay.Small`), whether a property can only be read or only be written, and its
 * KDoc, if it is the SDK's.
 */
data class Hover(
    val declaration: String,
    val container: String?,
    val access: Access?,
    val documentation: String?,
) {
  enum class Access(val words: String) {
    READ_ONLY("read-only"),
    WRITE_ONLY("write-only"),
  }

  /** As plain text: the class, the declaration and its access, then the KDoc. */
  fun text(): String = buildString {
    container?.let { append(it).append('\n') }
    append(declaration)
    access?.let { append(" (").append(it.words).append(')') }
    documentation?.let { append("\n\n").append(it) }
  }
}

/** A function a call could be to, for signature help: its declaration, parameters and KDoc. */
data class Signature(val label: String, val parameters: List<String>, val documentation: String?)

/**
 * The functions the call around the caret could be to, and which of its arguments the caret is in
 * (0 for the first).
 */
data class SignatureHelp(val signatures: List<Signature>, val activeParameter: Int)

/**
 * Hover and signature help in a script-style program, from the Kotlin K2 compiler's analysis of it,
 * with the KDoc the classpath's sources carry ([KDocIndex]). The program can be mid-edit: K2 types
 * what it can around errors.
 */
object K2Hover {
  /**
   * What the name at [offset] (a `String` index, in the name or right after it) in [source] is, or
   * null where there is none, or nothing is known of it.
   */
  fun hover(
      source: String,
      offset: Int,
      classpath: List<File> = emptyList(),
      defaultImports: List<String> = emptyList(),
  ): Hover? =
      withScript(source, classpath, defaultImports) { parsed ->
        val text = parsed.script.text
        val caret = K2Frontend.parsedOffset(source, offset)
        var start = caret
        while (start > 0 && Character.isJavaIdentifierPart(text[start - 1])) start--
        var end = caret
        while (end < text.length && Character.isJavaIdentifierPart(text[end])) end++
        if (start == end || Character.isDigit(text[start])) return@withScript null
        if (inCommentOrString(parsed.script.findElementAt(start))) return@withScript null
        val at = parsed.wrapped.wrappedOffset(start)
        parsed.analyse { Lookup(it, classpath).hover(at, text.substring(start, end)) }
      }

  /**
   * The functions the call whose parentheses hold [offset] in [source] could be to, or null outside
   * any call's parentheses, or where no function is known.
   */
  fun signatureHelp(
      source: String,
      offset: Int,
      classpath: List<File> = emptyList(),
      defaultImports: List<String> = emptyList(),
  ): SignatureHelp? =
      withScript(source, classpath, defaultImports) { parsed ->
        val caret = K2Frontend.parsedOffset(source, offset)
        if (inCommentOrString(parsed.script.findElementAt(caret - 1))) return@withScript null
        val wrapped = parsed.wrapped.text
        // The caret stays right after the character before it: text the wrapper inserts at the
        // caret (the ` }` closing a top-level statement's `init {`) comes after it.
        val at = if (caret == 0) 0 else parsed.wrapped.wrappedOffset(caret - 1) + 1
        val open = openParenthesis(wrapped, at) ?: return@withScript null
        var calleeEnd = open.index
        while (calleeEnd > 0 && wrapped[calleeEnd - 1].isWhitespace()) calleeEnd--
        parsed.analyse { Lookup(it, classpath).signatures(calleeEnd, open.commas) }
      }

  private class Open(val index: Int, val commas: Int)

  /**
   * The `(` before [at] that is not yet closed, and how many commas lie between them outside
   * brackets: the index of the argument [at] is in. Null if a block (`{`) or a statement's end
   * (`;`) comes first, or no such `(` does. A string's text is skipped, and a `"` in it ends it.
   *
   * This reads the text, not the syntax tree, so some valid calls give null: a string with an
   * escaped quote (`"\""`), a char literal holding a bracket or a quote (`')'`, `'"'`), a comment
   * among the arguments holding a bracket, and a call with explicit type arguments
   * (`listOf<Int>(`), whose callee ends before the `<`.
   */
  private fun openParenthesis(text: String, at: Int): Open? {
    var depth = 0
    var commas = 0
    var inString = false
    for (i in (0 until at.coerceAtMost(text.length)).reversed()) {
      val c = text[i]
      if (c == '"') inString = !inString
      if (inString) continue
      when (c) {
        ')',
        ']',
        '}' -> depth++
        '[' -> depth--
        '{',
        ';' -> if (depth == 0) return null else if (c == '{') depth--
        '(' -> if (depth == 0) return Open(i, commas) else depth--
        ',' -> if (depth == 0) commas++
      }
    }
    return null
  }

  private class Lookup(output: SingleModuleFrontendOutput, private val classpath: List<File>) {
    private val session: FirSession = output.session
    private val scopeSession: ScopeSession = output.scopeSession
    private val elements = mutableListOf<FirElement>()

    init {
      val visitor =
          object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
              elements += element
              element.acceptChildren(this)
            }
          }
      output.fir.forEach { it.accept(visitor) }
    }

    private val docs by lazy { KDocIndex.of(classpath) }

    fun hover(at: Int, word: String): Hover? {
      val end = at + word.length
      // Only what the user wrote: not the compiler's implicit types, constructors and the like,
      // which share the source of what they were made for. A reference to a property (`d0.Setting`,
      // `x`) is the name the user wrote, though its source is marked as made.
      fun KtSourceElement?.covers() =
          this != null &&
              (kind is KtRealSourceElementKind ||
                  kind == KtFakeSourceElementKind.ReferenceInAtomicQualifiedAccess) &&
              startOffset <= at &&
              end <= endOffset

      fun smallest(candidates: List<Pair<KtSourceElement, FirBasedSymbol<*>>>) =
          candidates.minByOrNull { (source, _) -> source.endOffset - source.startOffset }?.second

      // The class of what a member was referred to on (`d0.`, `LedDisplays.Small.`), by member.
      val receivers = mutableMapOf<FirBasedSymbol<*>, ClassId>()
      val referenced =
          elements.filterIsInstance<FirQualifiedAccessExpression>().mapNotNull { access ->
            val reference = access.calleeReference as? FirNamedReference ?: return@mapNotNull null
            val source = reference.source
            if (!source.covers()) return@mapNotNull null
            // A call that resolved to none of several overloads is to no one of them yet.
            val symbol = symbolsOf(reference).singleOrNull() ?: return@mapNotNull null
            receiverClass(access)?.let { receivers[symbol] = it }
            source!! to symbol
          }
      val classes = elements.mapNotNull { element ->
        val classId =
            when (element) {
              is FirResolvedQualifier -> element.qualifierSymbol?.classId
              is FirResolvedTypeRef -> (element.coneType as? ConeClassLikeType)?.lookupTag?.classId
              else -> null
            }
        if (classId == null || !element.source.covers()) return@mapNotNull null
        // A qualifier or a type names its outer classes too: `LedDisplays.Small`.
        generateSequence(classId) { it.outerClassId }
            .firstOrNull { it.shortClassName.asString() == word }
            ?.let { session.symbolProvider.getClassLikeSymbolByClassId(it) }
            ?.let { element.source!! to it }
      }
      val declared = elements.mapNotNull { element ->
        val symbol =
            when (element) {
              is FirCallableDeclaration -> element.symbol
              is FirRegularClass -> element.symbol
              else -> null
            }
        val name =
            when (symbol) {
              is FirCallableSymbol<*> -> symbol.name
              is FirClassLikeSymbol<*> -> symbol.classId.shortClassName
              else -> null
            }
        if (name?.asString() != word || !element.source.covers()) return@mapNotNull null
        element.source!! to symbol!!
      }
      val symbol = smallest(referenced) ?: smallest(classes) ?: smallest(declared) ?: return null
      return when (symbol) {
        is FirCallableSymbol<*> -> hoverOf(symbol, receivers[symbol])
        is FirClassLikeSymbol<*> ->
            if (isProgram(symbol.classId)) null
            else Hover(declarationOf(symbol), null, null, docs[symbol.classId.asFqNameString()])
        else -> null
      }
    }

    /** The functions a call whose callee ends at [calleeEnd] could be to. */
    fun signatures(calleeEnd: Int, activeParameter: Int): SignatureHelp? {
      val call =
          elements.filterIsInstance<FirFunctionCall>().firstOrNull {
            val source = it.calleeReference.source
            source != null &&
                source.kind is KtRealSourceElementKind &&
                source.endOffset == calleeEnd
          } ?: return null
      val symbols = symbolsOf(call.calleeReference)
      val signatures =
          (symbols + symbols.singleOrNull()?.let { overloadsOf(call, it) }.orEmpty())
              .filterIsInstance<FirFunctionSymbol<*>>()
              .map { Signature(declarationOf(it), parametersOf(it), documentationOf(it)) }
              .distinctBy { it.label }
      return if (signatures.isEmpty()) null else SignatureHelp(signatures, activeParameter)
    }

    /**
     * The other functions a call that resolved to [resolved] could be to as its arguments are
     * written: those of the same name that its explicit receiver's type has, or, for a top-level
     * function called without one, those of the same name in its package. A call with no arguments
     * yet resolves to an overload that takes none, though the user may mean another.
     */
    private fun overloadsOf(
        call: FirFunctionCall,
        resolved: FirBasedSymbol<*>,
    ): List<FirBasedSymbol<*>> {
      val function = resolved as? FirNamedFunctionSymbol ?: return emptyList()
      if (function.resolvedReceiverType != null) return emptyList()
      val receiver = call.explicitReceiver
      val found = mutableListOf<FirNamedFunctionSymbol>()
      if (receiver != null) {
        receiver.resolvedType
            .scope(
                session,
                scopeSession,
                CallableCopyTypeCalculator.DoNothing,
                FirResolvePhase.STATUS,
            )
            ?.processFunctionsByName(function.name) { found += it }
      } else if (function.callableId.classId == null) {
        found +=
            session.symbolProvider
                .getTopLevelFunctionSymbols(function.callableId.packageName, function.name)
                .filter { it.resolvedReceiverType == null }
      }
      return found.filter { candidate ->
        candidate.resolvedStatus.visibility == Visibilities.Public &&
            candidate.resolvedAnnotationClassIds.none {
              it == StandardClassIds.Annotations.Deprecated
            }
      }
    }

    /**
     * What a reference is to: its symbol if it resolved, else the candidates the compiler
     * considered (the overloads of a call whose arguments are not all written yet).
     */
    private fun symbolsOf(reference: FirNamedReference): List<FirBasedSymbol<*>> =
        when (reference) {
          is FirResolvedNamedReference -> listOf(reference.resolvedSymbol)
          is FirErrorNamedReference ->
              (reference.diagnostic as? ConeDiagnosticWithCandidates)
                  ?.candidateSymbols
                  ?.toList()
                  .orEmpty()
          else -> emptyList()
        }

    /**
     * The class of [access]'s explicit receiver: of the object or class it names, or of its type.
     */
    private fun receiverClass(access: FirQualifiedAccessExpression): ClassId? {
      val receiver = access.explicitReceiver ?: return null
      if (receiver is FirResolvedQualifier) return receiver.qualifierSymbol?.classId
      return (receiver.resolvedType as? ConeClassLikeType)?.lookupTag?.classId
    }

    /**
     * The hover of a callable, as a member of [receiver]'s class if it was referred to on one, else
     * of the class that declares it. A constructor's declaration names its class, so it has none.
     */
    private fun hoverOf(symbol: FirCallableSymbol<*>, receiver: ClassId?): Hover {
      val owner =
          if (symbol is FirConstructorSymbol) null
          else (receiver ?: symbol.containingClassLookupTag()?.classId)?.takeUnless(::isProgram)
      val access =
          when {
            owner == null || symbol !is FirPropertySymbol -> null
            symbol.isVal -> Hover.Access.READ_ONLY
            symbol.getterSymbol?.resolvedAnnotationClassIds.orEmpty().any {
              it == StandardClassIds.Annotations.Deprecated
            } -> Hover.Access.WRITE_ONLY
            else -> null
          }
      return Hover(
          declarationOf(symbol),
          owner?.let(::nameOf),
          access,
          documentationOf(symbol),
      )
    }

    /**
     * The KDoc of [symbol], or of the declaration it overrides, if one has some: an SDK class's
     * logic types override its family interface's, where the KDoc may be.
     */
    private fun documentationOf(symbol: FirCallableSymbol<*>): String? {
      val declared = symbol.fir.unwrapFakeOverrides().symbol
      val callableId = declared.callableId ?: return null
      val receiver = declared.resolvedReceiverType?.let(::render)
      docs[callableId.asSingleFqName().asString(), receiver]?.let {
        return it
      }
      val owner = callableId.classId ?: return null
      val name = callableId.callableName.asString()
      return supertypesOf(owner).firstNotNullOfOrNull {
        docs["${it.asFqNameString()}.$name", receiver]
      }
    }

    /** [classId]'s supertypes, nearest first. */
    private fun supertypesOf(classId: ClassId): List<ClassId> {
      val found = mutableListOf<ClassId>()
      val queue = ArrayDeque(listOf(classId))
      while (queue.isNotEmpty()) {
        val klass =
            session.symbolProvider.getClassLikeSymbolByClassId(queue.removeFirst())
                as? FirRegularClassSymbol ?: continue
        for (supertype in klass.resolvedSuperTypes) {
          val id = (supertype as? ConeClassLikeType)?.lookupTag?.classId ?: continue
          if (id !in found) {
            found += id
            queue += id
          }
        }
      }
      return found
    }

    /** Whether [classId] is the object the program is wrapped in, which the user never wrote. */
    private fun isProgram(classId: ClassId) =
        classId.outerClassId == null && classId.shortClassName.asString() == PROGRAM_OBJECT
  }
}
