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
import org.jetbrains.kotlin.cli.pipeline.PipelineArtifact
import org.jetbrains.kotlin.cli.pipeline.PipelineArtifactWithExitCode
import org.jetbrains.kotlin.cli.pipeline.PipelinePhase
import org.jetbrains.kotlin.cli.pipeline.jvm.JvmConfigurationPipelinePhase
import org.jetbrains.kotlin.cli.pipeline.jvm.JvmFir2IrPipelineArtifact
import org.jetbrains.kotlin.cli.pipeline.jvm.JvmFir2IrPipelinePhase
import org.jetbrains.kotlin.cli.pipeline.jvm.JvmFrontendPipelinePhase
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.Services
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
   * Compiles [source] and, if it has no errors, calls [use] with its IR. The IR is only valid
   * inside [use]: the compiler environment behind it is disposed afterwards.
   */
  fun <T> compile(source: String, use: (IrModuleFragment) -> T): FrontendResult<T> {
    val workDir = Files.createTempDirectory("ktrapic-frontend").toFile()
    val disposable = Disposer.newDisposable("ktrapic-frontend")
    try {
      val wrapped = ScriptWrapper.wrap(parseScript(source, disposable))
      val sourceFile = File(workDir, "$PROGRAM_OBJECT.kt").apply { writeText(wrapped.text) }
      val messages = CollectingMessageCollector()
      val arguments =
          K2JVMCompilerArguments().apply {
            freeArgs = listOf(sourceFile.absolutePath)
            classpath = kotlinStdlibJar().absolutePath
            noStdlib = true
            noReflect = true
            jdkHome = System.getProperty("java.home")
            moduleName = "ktrapic"
            destination = File(workDir, "out").absolutePath
          }
      val captured =
          IrCapturePipeline(K2JVMCompiler().defaultPerformanceManager)
              .executeAndReturnPipeLineArtifact(arguments, Services.EMPTY, messages, disposable)
      val diagnostics = messages.diagnostics(wrapped)
      if (captured !is CapturedIr || diagnostics.any { it.severity == Diagnostic.Severity.ERROR }) {
        return FrontendResult.Failure(diagnostics)
      }
      return FrontendResult.Success(use(captured.fir2ir.result.irModuleFragment), diagnostics)
    } finally {
      Disposer.dispose(disposable)
      workDir.deleteRecursively()
    }
  }

  private fun parseScript(
      source: String,
      disposable: org.jetbrains.kotlin.com.intellij.openapi.Disposable,
  ) =
      KotlinCoreEnvironment.createForProduction(
              disposable,
              CompilerConfiguration().apply {
                put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
                extensionsStorage = CompilerPluginRegistrar.ExtensionStorage()
              },
              EnvironmentConfigFiles.JVM_CONFIG_FILES,
          )
          .let { KtPsiFactory(it.project, false).createFile("$PROGRAM_OBJECT.kts", source) }

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

/** The stock K2 JVM pipeline up to fir2ir, ending in [CaptureIrPhase] instead of the backend. */
private class IrCapturePipeline(
    override val defaultPerformanceManager: org.jetbrains.kotlin.util.PerformanceManager
) : AbstractCliPipeline<K2JVMCompilerArguments>() {
  override fun createCompoundPhase(arguments: K2JVMCompilerArguments) =
      JvmConfigurationPipelinePhase then
          JvmFrontendPipelinePhase then
          JvmFir2IrPipelinePhase then
          CaptureIrPhase
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

  /** Errors and warnings, positioned in the user's source; compiler chatter is dropped. */
  fun diagnostics(wrapped: WrappedSource): List<Diagnostic> = messages.mapNotNull { message ->
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
    Diagnostic(severity, message.text, line, column)
  }
}
