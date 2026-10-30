package ktrapic.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.dependencies
import kotlin.script.experimental.jvm.JvmDependency
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.scripting.definitions.findScriptDefinition
import org.jetbrains.kotlin.scripting.resolve.KtFileScriptSource

/** The script definition the plugin bundles, as the Kotlin plugin loads it. */
class KtrapicScriptDefinitionsSourceTest : BasePlatformTestCase() {
  fun `test programs get KTrapIC's definition`() {
    val file = myFixture.configureByText("pump.ic10.kts", "d0.On = 1\n")

    assertEquals(
        "KTrapIC program",
        findScriptDefinition(project, KtFileScriptSource(file as KtFile)).name,
    )
  }

  fun `test other scripts don't`() {
    val file = myFixture.configureByText("build.gradle.kts", "println(1)\n")

    assertFalse(
        findScriptDefinition(project, KtFileScriptSource(file as KtFile)).name == "KTrapIC program"
    )
  }

  fun `test programs compile against the bundled SDK and the standard library`() {
    val definition = KtrapicScriptDefinitionsSource(project).definitions.single()
    val configuration = definition.compilationConfiguration

    val classpath =
        configuration[ScriptCompilationConfiguration.dependencies]
            .orEmpty()
            .filterIsInstance<JvmDependency>()
            .flatMap { it.classpath }
    assertEquals(
        listOf("sdk.jar", "kotlin-stdlib.jar"),
        classpath.map { it.name }.filter { it == "sdk.jar" || it.startsWith("kotlin-stdlib") },
    )
    assertTrue(classpath.first { it.name == "sdk.jar" }.startsWith(KtrapicPlugin.dir!!))
    assertEquals(
        listOf("ktrapic.*"),
        configuration[ScriptCompilationConfiguration.defaultImports],
    )
  }
}
