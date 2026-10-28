package ktrapic.script

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.dependencies
import kotlin.script.experimental.jvm.JvmDependency
import ktrapic.Device
import org.junit.jupiter.api.Test

class Ic10ScriptTest {
  @Test
  fun `the definition covers ic10 kts files and imports the ktrapic API`() {
    val annotation = Ic10Script::class.java.getAnnotation(KotlinScript::class.java)

    assertThat(annotation.fileExtension).isEqualTo("ic10.kts")
    assertThat(Ic10ScriptConfiguration[ScriptCompilationConfiguration.defaultImports])
        .containsExactly("ktrapic.*")
  }

  @Test
  fun `the jar carries the marker IntelliJ discovers it by`() {
    val marker = "META-INF/kotlin/script/templates/${Ic10Script::class.java.name}"

    assertThat(Ic10Script::class.java.classLoader.getResource(marker)).isNotNull()
  }

  @Test
  fun `the definition compiles against the jar holding the ktrapic API`() {
    val sdkJar = File(Device::class.java.protectionDomain.codeSource.location.toURI())

    val classpath =
        Ic10ScriptConfiguration[ScriptCompilationConfiguration.dependencies].orEmpty()
            .filterIsInstance<JvmDependency>()
            .flatMap { it.classpath }

    assertThat(classpath).contains(sdkJar)
  }
}
