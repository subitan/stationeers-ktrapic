package ktrapic.intellij

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.io.File
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.jvm.defaultJvmScriptingHostConfiguration
import org.jetbrains.kotlin.idea.base.plugin.artifacts.KotlinArtifacts
import org.jetbrains.kotlin.scripting.definitions.ScriptDefinition
import org.jetbrains.kotlin.scripting.definitions.ScriptDefinitionsFromClasspathDiscoverySource
import org.jetbrains.kotlin.scripting.definitions.ScriptDefinitionsSource

/**
 * KTrapIC's script definition for `*.ic10.kts` files, from the jars the plugin bundles, so the
 * Kotlin plugin completes and checks programs against the `ktrapic` API, with `import ktrapic.*`
 * implied, in any project. The Kotlin plugin loads the definition from the jars as it does its own
 * for `*.main.kts`: the script definition's jar, the SDK's, which the definition compiles programs
 * against, and the IDE's Kotlin standard library.
 */
class KtrapicScriptDefinitionsSource(@Suppress("unused") private val project: Project) :
    ScriptDefinitionsSource {
  override val definitions: Sequence<ScriptDefinition>
    get() = loaded

  private companion object {
    private val LOG = logger<KtrapicScriptDefinitionsSource>()

    /** The definitions, loaded once for every project. */
    val loaded: Sequence<ScriptDefinition> by lazy {
      val jars = KtrapicPlugin.scriptJars
      if (jars == null) {
        LOG.warn("KTrapIC's script definition jars aren't in the plugin's directory")
        emptySequence()
      } else {
        // Ahead of the definition for every other *.kts file, which would otherwise be tried
        // first, as the Kotlin plugin puts its own *.main.kts definition.
        load(jars + KotlinArtifacts.kotlinStdlib)
            .onEach { it.order = Int.MIN_VALUE }
            .toList()
            .asSequence()
      }
    }

    fun load(classpath: List<File>): Sequence<ScriptDefinition> =
        ScriptDefinitionsFromClasspathDiscoverySource(
                classpath,
                defaultJvmScriptingHostConfiguration,
            ) { severity, message ->
              if (severity >= ScriptDiagnostic.Severity.WARNING) {
                LOG.warn("KTrapIC's script definition: $message")
              } else {
                LOG.info("KTrapIC's script definition: $message")
              }
            }
            .definitions
  }
}
