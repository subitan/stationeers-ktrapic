@file:OptIn(
    org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class,
    org.jetbrains.kotlin.CoreEnvironmentDeprecation::class,
    org.jetbrains.kotlin.config.CompilerConfiguration.Internals::class,
    org.jetbrains.kotlin.config.MessageCollectorAccess::class,
    org.jetbrains.kotlin.cli.pipeline.PipelineArtifact.CliPipelineInternals::class,
)

package ktrapic.frontend

import java.io.File
import java.nio.file.Files
import org.jetbrains.kotlin.backend.common.extensions.DeclarationFinder
import org.jetbrains.kotlin.backend.common.phaser.then
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.pipeline.AbstractCliPipeline
import org.jetbrains.kotlin.cli.pipeline.CheckCompilationErrors
import org.jetbrains.kotlin.cli.pipeline.ConfigurationPipelineArtifact
import org.jetbrains.kotlin.cli.pipeline.PerformanceNotifications
import org.jetbrains.kotlin.cli.pipeline.PipelineArtifact
import org.jetbrains.kotlin.cli.pipeline.PipelineArtifactWithExitCode
import org.jetbrains.kotlin.cli.pipeline.PipelinePhase
import org.jetbrains.kotlin.cli.pipeline.jvm.JvmConfigurationPipelinePhase
import org.jetbrains.kotlin.cli.pipeline.jvm.JvmFir2IrPipelineArtifact
import org.jetbrains.kotlin.cli.pipeline.jvm.JvmFir2IrPipelinePhase
import org.jetbrains.kotlin.cli.pipeline.jvm.JvmFrontendPipelineArtifact
import org.jetbrains.kotlin.cli.pipeline.jvm.JvmFrontendPipelinePhase
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.psi.KtPsiFactory

/** A problem the Kotlin compiler reported, at a position in the user's source (1-based). */
data class Diagnostic(val severity: Severity, val message: String, val line: Int, val column: Int) {
  enum class Severity {
    ERROR,
    WARNING,
  }
}

/** The outcome of running the front end on a program. */
sealed interface FrontendResult<out T> {
  val diagnostics: List<Diagnostic>

  data class Success<T>(val value: T, override val diagnostics: List<Diagnostic>) :
      FrontendResult<T>

  data class Failure(override val diagnostics: List<Diagnostic>) : FrontendResult<Nothing>
}

/**
 * Runs the Kotlin K2 compiler's JVM front end (FIR analysis, then fir2ir) on a script-style program
 * and hands the resulting backend IR to a callback. The JVM backend never runs.
 *
 * Everything here uses compiler-internal APIs, which change between Kotlin releases; it is pinned
 * to the kotlin-compiler-embeddable version in MODULE.bazel.
 */
object K2Frontend {
  /**
   * Compiles [source] against kotlin-stdlib and the jars in [classpath], with [defaultImports]
   * imported as if written at its start, and, if it has no errors, calls [use] with its IR. The IR
   * is only valid inside [use]: the compiler environment behind it is disposed afterwards.
   */
  fun <T> compile(
      source: String,
      classpath: List<File> = emptyList(),
      defaultImports: List<String> = emptyList(),
      use: (FrontendIr) -> T,
  ): FrontendResult<T> {
    val workDir = Files.createTempDirectory("ktrapic-frontend").toFile()
    val disposable = Disposer.newDisposable("ktrapic-frontend")
    try {
      val script = parseScript(source, disposable)
      val rejected =
          (ScriptWrapper.syntaxErrors(script) + ScriptWrapper.unsupported(script)).sortedWith(
              compareBy({ it.line }, { it.column })
          )
      if (rejected.isNotEmpty()) return FrontendResult.Failure(rejected)
      val wrapped = ScriptWrapper.wrap(script, defaultImports)
      val sourceFile = File(workDir, "$PROGRAM_OBJECT.kt").apply { writeText(wrapped.text) }
      val messages = CollectingMessageCollector()
      var session: FirSession? = null
      val captured =
          IrCapturePipeline(
                  K2JVMCompiler().defaultPerformanceManager,
                  FrontendPhase { session = it },
              )
              .executeAndReturnPipeLineArtifact(
                  arguments(sourceFile, classpath, workDir),
                  Services.EMPTY,
                  messages,
                  disposable,
              )
      val diagnostics = messages.diagnostics(wrapped, session)
      if (captured !is CapturedIr || diagnostics.any { it.severity == Diagnostic.Severity.ERROR }) {
        return FrontendResult.Failure(diagnostics)
      }
      val ir =
          FrontendIr(
              captured.fir2ir.result.irModuleFragment,
              // Despite its name, the builtins finder searches every class on the classpath (it is
              // the source finder with no file to record lookups against), so it also finds the
              // SDK's classes such as ktrapic.LogicType.
              captured.fir2ir.result.pluginContext.finderForBuiltins(),
              wrapped,
          )
      return FrontendResult.Success(use(ir), diagnostics)
    } finally {
      Disposer.dispose(disposable)
      workDir.deleteRecursively()
    }
  }

  /**
   * Parses [source] as a Kotlin script; the PSI lives until [disposable] is disposed.
   *
   * The PSI parser expects `\n` line separators and no byte-order mark, so `\r\n` becomes `\n` and
   * a leading BOM is dropped first. Neither moves a line, and positions on line 1 are counted
   * without the BOM, as an editor shows them.
   */
  internal fun parseScript(
      source: String,
      disposable: org.jetbrains.kotlin.com.intellij.openapi.Disposable,
  ) =
      psiFactory(disposable)
          .createFile(
              "$PROGRAM_OBJECT.kts",
              source.removePrefix(BYTE_ORDER_MARK.toString()).replace("\r\n", "\n"),
          )

  /**
   * The offset in the text [parseScript] parses of the character at [offset] in [source]: before a
   * leading byte-order mark and each `\r` of a `\r\n` are dropped.
   */
  internal fun parsedOffset(source: String, offset: Int): Int {
    val at = offset.coerceIn(0, source.length)
    val dropped = (0 until at).count { source[it] == '\r' && source.getOrNull(it + 1) == '\n' }
    val mark = if (at > 0 && source[0] == BYTE_ORDER_MARK) 1 else 0
    return at - dropped - mark
  }

  private const val BYTE_ORDER_MARK = '﻿'

  /** Makes PSI from Kotlin source; the PSI lives until [disposable] is disposed. */
  internal fun psiFactory(disposable: org.jetbrains.kotlin.com.intellij.openapi.Disposable) =
      KotlinCoreEnvironment.createForProduction(
              disposable,
              CompilerConfiguration().apply {
                put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
                extensionsStorage = CompilerPluginRegistrar.ExtensionStorage()
              },
              EnvironmentConfigFiles.JVM_CONFIG_FILES,
          )
          .let { KtPsiFactory(it.project, false) }

  /**
   * The compiler arguments for [sourceFile], compiled against kotlin-stdlib and [classpath], with
   * any output under [workDir].
   */
  internal fun arguments(sourceFile: File, classpath: List<File>, workDir: File) =
      K2JVMCompilerArguments().apply {
        freeArgs = listOf(sourceFile.absolutePath)
        this.classpath =
            (listOf(kotlinStdlibJar()) + classpath).joinToString(File.pathSeparator) {
              it.absolutePath
            }
        noStdlib = true
        noReflect = true
        jdkHome = System.getProperty("java.home")
        moduleName = "ktrapic"
        destination = File(workDir, "out").absolutePath
      }

  /** The kotlin-stdlib jar this process runs with, which the program is compiled against. */
  private fun kotlinStdlibJar(): File =
      File(KotlinVersion::class.java.protectionDomain.codeSource.location.toURI())
}

/** The final pipeline artifact: the fir2ir output, captured instead of generating bytecode. */
private class CapturedIr(val fir2ir: JvmFir2IrPipelineArtifact) : PipelineArtifactWithExitCode() {
  override val exitCode: ExitCode
    get() = ExitCode.OK

  override val configuration: CompilerConfiguration
    get() = fir2ir.configuration

  override fun withCompilerConfiguration(
      newConfiguration: CompilerConfiguration
  ): PipelineArtifact = CapturedIr(fir2ir.withCompilerConfiguration(newConfiguration))
}

private object CaptureIrPhase :
    PipelinePhase<JvmFir2IrPipelineArtifact, CapturedIr>(
        name = "KtrapicCaptureIr",
        preActions = emptySet(),
        postActions = emptySet(),
    ) {
  override fun executePhase(input: JvmFir2IrPipelineArtifact): CapturedIr = CapturedIr(input)
}

/**
 * The stock JVM front end, with the same actions after it, which also hands its session to [keep],
 * whatever errors it found: the session is what the program's diagnostics are rendered with.
 */
private class FrontendPhase(private val keep: (FirSession) -> Unit) :
    PipelinePhase<ConfigurationPipelineArtifact, JvmFrontendPipelineArtifact>(
        name = "KtrapicFrontend",
        preActions = emptySet(),
        postActions =
            setOf(
                PerformanceNotifications.AnalysisFinished,
                CheckCompilationErrors.CheckDiagnosticCollector,
            ),
    ) {
  override fun executePhase(input: ConfigurationPipelineArtifact): JvmFrontendPipelineArtifact? =
      JvmFrontendPipelinePhase.executePhase(input)?.also { frontend ->
        frontend.frontendOutput.outputs.singleOrNull()?.let { keep(it.session) }
      }
}

/**
 * The stock K2 JVM pipeline up to fir2ir, with [frontend] for its front end, ending in
 * [CaptureIrPhase] instead of the backend.
 */
private class IrCapturePipeline(
    override val defaultPerformanceManager: org.jetbrains.kotlin.util.PerformanceManager,
    private val frontend: FrontendPhase,
) : AbstractCliPipeline<K2JVMCompilerArguments>() {
  override fun createCompoundPhase(arguments: K2JVMCompilerArguments) =
      JvmConfigurationPipelinePhase then frontend then JvmFir2IrPipelinePhase then CaptureIrPhase
}

private class CollectingMessageCollector : MessageCollector {
  private class Message(
      val severity: CompilerMessageSeverity,
      val text: String,
      val location: CompilerMessageSourceLocation?,
  )

  private val messages = mutableListOf<Message>()

  override fun clear() = messages.clear()

  override fun hasErrors() = messages.any { it.severity.isError }

  override fun report(
      severity: CompilerMessageSeverity,
      message: String,
      location: CompilerMessageSourceLocation?,
  ) {
    messages += Message(severity, message, location)
  }

  /**
   * Errors and warnings, positioned in the user's source, with the SDK's prefab types written as a
   * program writes them (see [withCompanionsRendered]) when there is a front end [session];
   * compiler chatter is dropped.
   */
  fun diagnostics(wrapped: WrappedSource, session: FirSession?): List<Diagnostic> =
      messages.mapNotNull { message ->
        val severity =
            when {
              message.severity.isError -> Diagnostic.Severity.ERROR
              message.severity.isWarning -> Diagnostic.Severity.WARNING
              else -> return@mapNotNull null
            }
        val location = message.location
        val (line, column) =
            if (location == null || location.line <= 0) 0 to 0
            else wrapped.originalPosition(location.line, location.column)
        val text = session?.let { withCompanionsRendered(message.text, it) } ?: message.text
        Diagnostic(severity, text, line, column)
      }
}

/**
 * A program's IR, and how to find where its elements are in the user's source. [symbols] finds
 * declarations by name in the libraries the program compiles against, such as the SDK.
 */
class FrontendIr
internal constructor(
    val module: IrModuleFragment,
    val symbols: DeclarationFinder,
    private val wrapped: WrappedSource,
) {
  /**
   * The 1-based line and column, in the user's source, of an IR element's [offset] (its
   * `startOffset`), or null for an offset that is not in the source.
   */
  fun position(offset: Int): Pair<Int, Int>? {
    if (offset < 0) return null
    val entry = module.files.single().fileEntry
    return wrapped.originalPosition(
        entry.getLineNumber(offset) + 1,
        entry.getColumnNumber(offset) + 1,
    )
  }
}
