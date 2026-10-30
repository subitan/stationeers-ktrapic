package ktrapic.script

import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptAcceptedLocation
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.acceptedLocations
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.displayName
import kotlin.script.experimental.api.ide
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.updateClasspath
import kotlin.script.experimental.jvm.util.scriptCompilationClasspathFromContext
import kotlin.script.experimental.jvm.util.scriptCompilationClasspathFromContextOrNull

/**
 * The Kotlin script definition for KTrapIC programs (`*.ic10.kts`), so an IDE checks them as the
 * compiler does: against the `ktrapic` API, with `import ktrapic.*` implied.
 *
 * KTrapIC's IntelliJ plugin bundles the definition and hands it to the Kotlin plugin. Without that
 * plugin, the Kotlin plugin finds it through the marker file
 * `META-INF/kotlin/script/templates/ktrapic.script.Ic10Script` in this jar, when the jar (and the
 * SDK jar beside it) is a dependency of the project. Only the IDE uses it: the KTrapIC compiler
 * runs these files itself and never through Kotlin scripting.
 */
@KotlinScript(
    displayName = "KTrapIC program",
    fileExtension = "ic10.kts",
    compilationConfiguration = Ic10ScriptConfiguration::class,
)
abstract class Ic10Script

/**
 * What a KTrapIC program is compiled against: the `ktrapic` API, imported by default, and the
 * Kotlin standard library, where the definition is loaded with one (as the IntelliJ plugin loads
 * it).
 */
object Ic10ScriptConfiguration :
    ScriptCompilationConfiguration({
      displayName("KTrapIC program")
      defaultImports("ktrapic.*")
      jvm {
        // Picking jars by name yields nothing unless every name matches, so the SDK and the
        // standard library are asked for together and, where the definition's class loader shows
        // no kotlin-stdlib jar (a project with only the SDK and script jars as a library), the SDK
        // alone.
        val classLoader = Ic10Script::class.java.classLoader
        updateClasspath(
            scriptCompilationClasspathFromContextOrNull(
                "sdk",
                "kotlin-stdlib",
                classLoader = classLoader,
            ) ?: scriptCompilationClasspathFromContext("sdk", classLoader = classLoader)
        )
      }
      ide { acceptedLocations(ScriptAcceptedLocation.Everywhere) }
    }) {
  private fun readResolve(): Any = Ic10ScriptConfiguration
}
