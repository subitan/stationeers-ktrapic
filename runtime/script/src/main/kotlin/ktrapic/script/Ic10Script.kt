package ktrapic.script

import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptAcceptedLocation
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.acceptedLocations
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.displayName
import kotlin.script.experimental.api.ide
import kotlin.script.experimental.jvm.dependenciesFromClassContext
import kotlin.script.experimental.jvm.jvm

/**
 * The Kotlin script definition for KTrapIC programs (`*.ic10.kts`), so an IDE checks them as the
 * compiler does: against the `ktrapic` API, with `import ktrapic.*` implied.
 *
 * IntelliJ's Kotlin plugin finds the definition through the marker file
 * `META-INF/kotlin/script/templates/ktrapic.script.Ic10Script` in this jar, when the jar (and the
 * runtime jar beside it) is a dependency of the project. Only the IDE uses it: the KTrapIC compiler
 * runs these files itself and never through Kotlin scripting.
 */
@KotlinScript(
    displayName = "KTrapIC program",
    fileExtension = "ic10.kts",
    compilationConfiguration = Ic10ScriptConfiguration::class,
)
abstract class Ic10Script

/** What a KTrapIC program is compiled against: the `ktrapic` API, imported by default. */
object Ic10ScriptConfiguration :
    ScriptCompilationConfiguration({
      displayName("KTrapIC program")
      defaultImports("ktrapic.*")
      jvm { dependenciesFromClassContext(Ic10Script::class, "runtime", wholeClasspath = false) }
      ide { acceptedLocations(ScriptAcceptedLocation.Everywhere) }
    }) {
  private fun readResolve(): Any = Ic10ScriptConfiguration
}
