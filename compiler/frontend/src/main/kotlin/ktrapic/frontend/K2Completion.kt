@file:OptIn(
    org.jetbrains.kotlin.cli.pipeline.PipelineArtifact.CliPipelineInternals::class,
    org.jetbrains.kotlin.fir.symbols.SymbolInternals::class,
)

package ktrapic.frontend

import java.io.File
import java.nio.file.Files
import org.jetbrains.kotlin.backend.common.phaser.then
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.cli.pipeline.AbstractCliPipeline
import org.jetbrains.kotlin.cli.pipeline.ConfigurationPipelineArtifact
import org.jetbrains.kotlin.cli.pipeline.PipelineArtifact
import org.jetbrains.kotlin.cli.pipeline.PipelineArtifactWithExitCode
import org.jetbrains.kotlin.cli.pipeline.PipelinePhase
import org.jetbrains.kotlin.cli.pipeline.jvm.JvmConfigurationPipelinePhase
import org.jetbrains.kotlin.cli.pipeline.jvm.JvmFrontendPipelinePhase
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
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
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.fir.types.typeContext
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression
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
 * compiler's analysis of it: after `receiver.`, the members and SDK extensions of the receiver's
 * type (or, after a class or object name, what it holds); anywhere else, the locals, parameters and
 * members in scope there, the SDK's top-level names, and keywords.
 *
 * The program is analysed with a placeholder name in place of the word at the caret, so the
 * reference there is the only one the analysis need not resolve. Errors elsewhere in the program
 * don't stop it: K2 still types what it can.
 */
object K2Completion {
  /** The packages whose top-level names a program sees, as `ktrapic.*` imports them. */
  private val IMPORTED_PACKAGES = listOf(FqName("ktrapic"))

  private const val PLACEHOLDER = "ktrapicCompletionPlaceholder"

  private val KEYWORDS =
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
          "is",
          "break",
          "continue",
          "return",
          "true",
          "false",
          "class",
          "interface",
          "object",
          "enum",
      )

  /** `Any`'s members, which every type has and no program needs offered. */
  private val ANY_MEMBERS = setOf("equals", "hashCode", "toString")

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

    val workDir = Files.createTempDirectory("ktrapic-completion").toFile()
    val disposable = Disposer.newDisposable("ktrapic-completion")
    try {
      val script = K2Frontend.parseScript(edited, disposable)
      if (inCommentOrString(script.findElementAt(script.text.indexOf(PLACEHOLDER)))) {
        return emptyList()
      }
      val wrapped = ScriptWrapper.wrap(script, defaultImports)
      val at = wrapped.text.indexOf(PLACEHOLDER)
      if (at < 0) return emptyList()
      val sourceFile = File(workDir, "$PROGRAM_OBJECT.kt").apply { writeText(wrapped.text) }
      var completions = emptyList<Completion>()
      AnalysePipeline(
              K2JVMCompiler().defaultPerformanceManager,
              AnalysePhase { completions = Analysis(it, at).completions() },
          )
          .executeAndReturnPipeLineArtifact(
              K2Frontend.arguments(sourceFile, classpath, workDir),
              Services.EMPTY,
              MessageCollector.NONE,
              disposable,
          )
      return completions
    } finally {
      Disposer.dispose(disposable)
      workDir.deleteRecursively()
    }
  }

  /** Whether [leaf] is in a comment or in a string's text (not in a `${...}` inside it). */
  private fun inCommentOrString(leaf: PsiElement?): Boolean {
    var element = leaf
    while (element != null && element !is KtFile) {
      if (element is PsiComment || element is KtLiteralStringTemplateEntry) return true
      if (element is KtStringTemplateEntryWithExpression) return false
      element = element.parent
    }
    return false
  }

  private class Analysis(output: SingleModuleFrontendOutput, private val at: Int) {
    private val session: FirSession = output.session
    private val scopeSession: ScopeSession = output.scopeSession
    private val path = mutableListOf<FirElement>()
    private var access: FirQualifiedAccessExpression? = null

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
      return items.distinctBy { it.label to it.kind }
    }

    /** What can follow `receiver.`. */
    private fun members(
        receiver: org.jetbrains.kotlin.fir.expressions.FirExpression
    ): List<Completion> {
      if (receiver is FirResolvedQualifier) {
        val symbol = receiver.qualifierSymbol as? FirRegularClassSymbol ?: return emptyList()
        return statics(symbol).sortedBy { it.label.lowercase() }
      }
      val type = receiver.resolvedType
      val items = mutableListOf<Completion>()
      val scope =
          type.scope(
              session,
              scopeSession,
              CallableCopyTypeCalculator.DoNothing,
              FirResolvePhase.STATUS,
          )
      scope?.processAllCallables { if (shown(it)) items += callable(it, local = false) }
      items += extensions(type)
      return items.sortedBy { it.label.lowercase() }
    }

    /** What a class or object's name gives: its nested classes, static members and own members. */
    private fun statics(symbol: FirRegularClassSymbol): List<Completion> {
      val klass = symbol.fir
      val provider = session.kotlinScopeProvider
      val items = mutableListOf<Completion>()
      provider.getNestedClassifierScope(klass, session, scopeSession)?.processAllClassifiers {
        classifier(it)?.let(items::add)
      }
      provider.getStaticCallableMemberScope(klass, session, scopeSession)?.processAllCallables {
        if (shown(it)) items += callable(it, local = false)
      }
      val companion = klass.companionObjectSymbol
      val holder = if (klass.classKind == ClassKind.OBJECT) symbol else companion
      holder
          ?.unsubstitutedScope(session, scopeSession, false, FirResolvePhase.STATUS)
          ?.processAllCallables { if (shown(it)) items += callable(it, local = false) }
      return items
    }

    /** The imported packages' extensions whose receiver [type] can be. */
    private fun extensions(type: ConeKotlinType): List<Completion> =
        IMPORTED_PACKAGES.flatMap { pkg ->
              names(pkg).callables.flatMap { name ->
                session.symbolProvider.getTopLevelCallableSymbols(pkg, name).filter {
                  shown(it) && accepts(it.resolvedReceiverType ?: return@filter false, type)
                }
              }
            }
            .map { callable(it, local = false) }

    private fun accepts(receiver: ConeKotlinType, type: ConeKotlinType): Boolean {
      val context = session.typeContext
      if (AbstractTypeChecker.isSubtypeOf(context, type, receiver)) return true
      // A generic receiver: compare the classes alone.
      val receiverClass = (receiver as? ConeClassLikeType)?.lookupTag?.classId ?: return false
      if (receiver.typeArguments.none { it.type is ConeTypeParameterType }) return false
      return (type as? ConeClassLikeType)?.lookupTag?.classId == receiverClass
    }

    /** The locals, parameters and members in scope at the caret. */
    private fun inScope(): List<Completion> {
      val items = mutableListOf<Completion>()
      for (element in path.asReversed()) {
        when (element) {
          is FirBlock ->
              for (statement in element.statements) {
                val source = statement.source ?: continue
                if (source.startOffset >= at || statement in path) continue
                when (statement) {
                  is FirProperty -> items += callable(statement.symbol, local = true)
                  is FirNamedFunction -> items += callable(statement.symbol, local = true)
                  is FirRegularClass -> classifier(statement.symbol)?.let(items::add)
                  else -> {}
                }
              }
          is FirFunction ->
              for (parameter in element.valueParameters) {
                items += callable(parameter.symbol, local = true)
              }
          is FirRegularClass -> {
            // The program's top-level properties are run in order, as a script's are, so one
            // declared after the caret isn't set yet there.
            val program = element.symbol.classId.shortClassName.asString() == PROGRAM_OBJECT
            element.symbol
                .unsubstitutedScope(session, scopeSession, false, FirResolvePhase.STATUS)
                .processAllCallables {
                  val later = it is FirPropertySymbol && (it.source?.startOffset ?: 0) > at
                  if (shown(it) && !(program && later)) items += callable(it, local = false)
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
                        .filter { shown(it) && it.resolvedReceiverType == null }
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

    private fun shown(symbol: FirCallableSymbol<*>): Boolean {
      val name = symbol.name
      if (name.isSpecial || name.asString().startsWith("_") || name.asString() in ANY_MEMBERS) {
        return false
      }
      if (symbol.resolvedStatus.visibility != Visibilities.Public) return false
      return symbol.resolvedAnnotationClassIds.none {
        it == StandardClassIds.Annotations.Deprecated
      }
    }

    private fun callable(symbol: FirCallableSymbol<*>, local: Boolean): Completion {
      val name = symbol.name.asString()
      val returns = render(symbol.resolvedReturnType)
      return when (symbol) {
        is FirEnumEntrySymbol -> Completion(name, Completion.Kind.ENUM_ENTRY, name)
        is FirFunctionSymbol<*> -> {
          val parameters =
              symbol.valueParameterSymbols.joinToString(", ") {
                "${it.name}: ${render(it.resolvedReturnType)}"
              }
          Completion(name, Completion.Kind.FUNCTION, "fun $name($parameters): $returns")
        }
        is FirVariableSymbol<*> -> {
          val keyword =
              if (symbol is FirPropertySymbol && symbol.isVal || symbol !is FirPropertySymbol) "val"
              else "var"
          Completion(
              name,
              if (local) Completion.Kind.LOCAL else Completion.Kind.PROPERTY,
              "$keyword $name: $returns",
          )
        }
        else -> Completion(name, Completion.Kind.PROPERTY, name)
      }
    }

    private fun classifier(symbol: FirClassifierSymbol<*>): Completion? {
      val classLike = symbol as? FirClassLikeSymbol<*> ?: return null
      val name = classLike.classId.shortClassName
      if (name.isSpecial || name.asString().startsWith("_")) return null
      if (classLike.resolvedStatus.visibility != Visibilities.Public) return null
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
      return Completion(label, kind, classLike.classId.asFqNameString())
    }

    private fun render(type: ConeKotlinType): String {
      val text =
          when (type) {
            is ConeClassLikeType -> {
              val arguments = type.typeArguments.mapNotNull { it.type }
              type.lookupTag.classId.relativeClassName.asString() +
                  if (arguments.isEmpty()) ""
                  else arguments.joinToString(", ", "<", ">") { render(it) }
            }
            is ConeTypeParameterType -> type.lookupTag.name.asString()
            else -> type.toString()
          }
      return if (type.isMarkedNullable) "$text?" else text
    }
  }
}

/**
 * The pipeline's final artifact here, which carries nothing: [AnalysePhase] hands its output on.
 */
private class Analysed(override val configuration: CompilerConfiguration) :
    PipelineArtifactWithExitCode() {
  override val exitCode: ExitCode
    get() = ExitCode.OK

  override fun withCompilerConfiguration(
      newConfiguration: CompilerConfiguration
  ): PipelineArtifact = Analysed(newConfiguration)
}

/**
 * Runs the stock JVM front end and hands its output to [use], whatever errors it found: the program
 * being completed always has one, at the caret. The pipeline reports those errors afterwards and
 * drops the artifact, so the output is used here, inside the phase.
 */
private class AnalysePhase(private val use: (SingleModuleFrontendOutput) -> Unit) :
    PipelinePhase<ConfigurationPipelineArtifact, Analysed>(
        name = "KtrapicAnalyse",
        preActions = emptySet(),
        postActions = emptySet(),
    ) {
  override fun executePhase(input: ConfigurationPipelineArtifact): Analysed? {
    val frontend = JvmFrontendPipelinePhase.executePhase(input) ?: return null
    frontend.frontendOutput.outputs.singleOrNull()?.let(use)
    return Analysed(frontend.configuration)
  }
}

/** The stock K2 JVM pipeline's configuration and front end, ending in [analyse]. */
private class AnalysePipeline(
    override val defaultPerformanceManager: org.jetbrains.kotlin.util.PerformanceManager,
    private val analyse: AnalysePhase,
) : AbstractCliPipeline<K2JVMCompilerArguments>() {
  override fun createCompoundPhase(arguments: K2JVMCompilerArguments) =
      JvmConfigurationPipelinePhase then analyse
}
