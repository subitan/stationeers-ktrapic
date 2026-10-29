@file:OptIn(org.jetbrains.kotlin.cli.pipeline.PipelineArtifact.CliPipelineInternals::class)

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
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.fir.pipeline.SingleModuleFrontendOutput
import org.jetbrains.kotlin.psi.KtFile

/**
 * A script-style program parsed and wrapped, ready for the K2 front end's analysis, which
 * completion, hover and signature help run on the program as it is being edited. Made by
 * [withScript], and only valid inside it.
 */
internal class ParsedScript(
    val script: KtFile,
    private val defaultImports: List<String>,
    private val classpath: List<File>,
    private val workDir: File,
    private val disposable: Disposable,
) {
  val wrapped: WrappedSource by lazy { ScriptWrapper.wrap(script, defaultImports) }

  /**
   * Runs the stock JVM front end on [wrapped], against kotlin-stdlib and the classpath, and returns
   * what [use] makes of its output, whatever errors it found: a program being edited often has
   * some. Null if the front end gave no output.
   */
  fun <T> analyse(use: (SingleModuleFrontendOutput) -> T): T? {
    val sourceFile = File(workDir, "$PROGRAM_OBJECT.kt").apply { writeText(wrapped.text) }
    var result: T? = null
    AnalysePipeline(
            K2JVMCompiler().defaultPerformanceManager,
            AnalysePhase { result = use(it) },
        )
        .executeAndReturnPipeLineArtifact(
            K2Frontend.arguments(sourceFile, classpath, workDir),
            Services.EMPTY,
            MessageCollector.NONE,
            disposable,
        )
    return result
  }
}

/**
 * Parses [source] as a script, to be wrapped with [defaultImports] and analysed against
 * [classpath], and returns what [use] makes of it. The PSI and the analysis are only valid inside
 * [use].
 */
internal fun <T> withScript(
    source: String,
    classpath: List<File>,
    defaultImports: List<String>,
    use: (ParsedScript) -> T,
): T {
  val workDir = Files.createTempDirectory("ktrapic-analysis").toFile()
  val disposable = Disposer.newDisposable("ktrapic-analysis")
  try {
    val script = K2Frontend.parseScript(source, disposable)
    return use(ParsedScript(script, defaultImports, classpath, workDir, disposable))
  } finally {
    Disposer.dispose(disposable)
    workDir.deleteRecursively()
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
 * Runs the stock JVM front end and hands its output to [use], whatever errors it found. The
 * pipeline reports those errors afterwards and drops the artifact, so the output is used here,
 * inside the phase.
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
