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
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeStarProjection
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
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
 * compiler's analysis of it: after `receiver.`, the members and extensions of the receiver's type
 * (or, after a class or object name, what it holds); anywhere else, the locals, parameters and
 * members in scope there, the SDK's top-level names, and keywords. Of the standard library's
 * declarations, only those KTrapIC compiles are offered ([SUPPORTED_STDLIB]), and a name shadowed
 * by one declared nearer the caret is offered once.
 *
 * The program is analysed with a placeholder name in place of the word at the caret, so the
 * reference there is the only one the analysis need not resolve. Errors elsewhere in the program
 * don't stop it: K2 still types what it can.
 */
object K2Completion {
  /** The packages whose top-level names a program sees, as `ktrapic.*` imports them. */
  private val IMPORTED_PACKAGES = listOf(FqName("ktrapic"))

  /**
   * The kotlin-stdlib extensions KTrapIC compiles, by package. `kotlin.ranges` and
   * `kotlin.collections` are imported by default; `kotlin.math` only where the program imports it.
   */
  private val STDLIB_EXTENSIONS =
      mapOf(
          FqName("kotlin.math") to listOf("absoluteValue", "pow", "roundToInt"),
          FqName("kotlin.ranges") to
              listOf("coerceAtLeast", "coerceAtMost", "coerceIn", "downTo", "step", "until"),
          FqName("kotlin.collections") to listOf("intersect", "minus", "plus"),
      )

  /** The standard library's packages: `kotlin` and those under it. */
  private val KOTLIN = FqName("kotlin")

  private val DEFAULT_IMPORTED = setOf(FqName("kotlin.ranges"), FqName("kotlin.collections"))

  /**
   * The declarations of the `kotlin` packages that KTrapIC compiles, by fully qualified name: a
   * member (`kotlin.Int.plus`) or a top-level extension (`kotlin.math.roundToInt`). Of the standard
   * library's declarations, only these are offered; see docs/LANGUAGE.md.
   */
  val SUPPORTED_STDLIB: Set<String> =
      (listOf(
              "kotlin.Int" to
                  listOf(
                      "and",
                      "dec",
                      "div",
                      "inc",
                      "inv",
                      "minus",
                      "or",
                      "plus",
                      "rangeTo",
                      "rangeUntil",
                      "rem",
                      "shl",
                      "shr",
                      "times",
                      "toDouble",
                      "toInt",
                      "unaryMinus",
                      "unaryPlus",
                      "ushr",
                      "xor",
                  ),
              "kotlin.Double" to
                  listOf(
                      "dec",
                      "div",
                      "inc",
                      "minus",
                      "plus",
                      "rem",
                      "times",
                      "toDouble",
                      "toInt",
                      "unaryMinus",
                      "unaryPlus",
                  ),
              "kotlin.Boolean" to listOf("not"),
              "kotlin.collections.Set" to listOf("contains", "isEmpty", "size"),
              "kotlin.collections.List" to listOf("contains", "isEmpty", "size"),
          ) + STDLIB_EXTENSIONS.map { (pkg, names) -> pkg.asString() to names })
          .flatMap { (owner, names) -> names.map { "$owner.$it" } }
          .toSet()

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

    /**
     * The extensions whose receiver [type] can be: the imported packages' and the supported
     * standard library extensions the program sees.
     */
    private fun extensions(type: ConeKotlinType): List<Completion> {
      val candidates =
          IMPORTED_PACKAGES.flatMap { pkg -> names(pkg).callables.map { pkg to it } } +
              STDLIB_EXTENSIONS.flatMap { (pkg, names) ->
                names.map { pkg to Name.identifier(it) }.filter { (_, name) -> sees(pkg, name) }
              }
      return candidates
          .flatMap { (pkg, name) ->
            session.symbolProvider.getTopLevelCallableSymbols(pkg, name).filter {
              shown(it) && accepts(it.resolvedReceiverType ?: return@filter false, type)
            }
          }
          .map { callable(it, local = false) }
    }

    /**
     * Whether the program sees [pkg]'s [name] unqualified: imported by default or by the program.
     */
    private fun sees(pkg: FqName, name: Name): Boolean =
        pkg in DEFAULT_IMPORTED ||
            imports.any {
              it.aliasName == null &&
                  (it.isAllUnder && it.importedFqName == pkg ||
                      !it.isAllUnder && it.importedFqName == pkg.child(name))
            }

    private fun accepts(receiver: ConeKotlinType, type: ConeKotlinType): Boolean {
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

    /**
     * A local declaration or parameter, or null for one the compiler made, such as a `for` loop's
     * `<iterator>` or a destructuring declaration's `<destruct>`, which have special names.
     */
    private fun local(symbol: FirCallableSymbol<*>): Completion? =
        if (symbol.name.isSpecial) null else callable(symbol, local = true)

    /**
     * Whether a member or a top-level declaration is offered: one a program can use, which is
     * public, not deprecated, not one of the SDK's internals (named with a leading `_`) nor of
     * [ANY_MEMBERS], and, if the standard library declares it, in [SUPPORTED_STDLIB].
     */
    private fun shown(symbol: FirCallableSymbol<*>): Boolean {
      val name = symbol.name
      if (name.isSpecial || name.asString().startsWith("_") || name.asString() in ANY_MEMBERS) {
        return false
      }
      val declared = symbol.fir.unwrapFakeOverrides().symbol.callableId ?: return false
      if (
          declared.packageName.isSubpackageOf(KOTLIN) &&
              declared.asSingleFqName().asString() !in SUPPORTED_STDLIB
      ) {
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
