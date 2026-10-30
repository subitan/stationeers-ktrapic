package ktrapic.script

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.function.Supplier
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.dependencies
import kotlin.script.experimental.jvm.JvmDependency
import ktrapic.Device
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

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

  @Test
  fun `the definition compiles against the SDK where no jar is named kotlin-stdlib`(
      @TempDir dir: Path
  ) {
    // The standard library under another name, as when a project has the SDK and script jars as a
    // library and the class loader the IDE loads them with shows no kotlin-stdlib jar.
    val stdlib = jarOf(Unit::class.java).copyTo(dir.resolve("runtime.jar").toFile())
    val jars =
        listOf(stdlib) +
            listOf(
                    KotlinScript::class.java,
                    JvmDependency::class.java,
                    Device::class.java,
                    Ic10Script::class.java,
                    DefinitionClasspath::class.java,
                )
                .map(::jarOf)
                .distinct()
    // The definition falls back on the JVM's classpath too, which here has the standard library.
    val classpathProperty = System.getProperty("java.class.path")
    System.setProperty("java.class.path", jars.joinToString(File.pathSeparator))
    try {
      URLClassLoader(
              jars.map { it.toURI().toURL() }.toTypedArray(),
              ClassLoader.getPlatformClassLoader(),
          )
          .use { loader ->
            @Suppress("UNCHECKED_CAST")
            val probe =
                loader
                    .loadClass(DefinitionClasspath::class.java.name)
                    .getConstructor()
                    .newInstance() as Supplier<List<String>>

            assertThat(probe.get()).containsExactly(jarOf(Device::class.java).name)
          }
    } finally {
      System.setProperty("java.class.path", classpathProperty)
    }
  }

  private fun jarOf(type: Class<*>) = File(type.protectionDomain.codeSource.location.toURI())
}

/**
 * The names of the jars [Ic10ScriptConfiguration] compiles against, for a test to read from a class
 * loader of its own.
 */
class DefinitionClasspath : Supplier<List<String>> {
  override fun get(): List<String> =
      Ic10ScriptConfiguration[ScriptCompilationConfiguration.dependencies].orEmpty()
          .filterIsInstance<JvmDependency>()
          .flatMap { it.classpath }
          .map { it.name }
}
