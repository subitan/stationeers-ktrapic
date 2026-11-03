package ktrapic.daemon

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.util.zip.ZipFile
import org.junit.jupiter.api.Test

/**
 * Checks that the deploy jar carries a license notice for everything it bundles: the files in
 * third_party/licenses ship unchanged, and every path in the jar is covered by an entry in
 * [COVERAGE]. When a dependency change adds a package to the jar, this fails until the package is
 * added to [COVERAGE] (with its notice in third_party/licenses, if no notice there covers it).
 */
class ThirdPartyNoticesTest {
  private val workspace = File(System.getenv("TEST_SRCDIR"), System.getenv("TEST_WORKSPACE"))
  private val licenses = workspace.resolve("third_party/licenses")

  @Test
  fun `the deploy jar ships every file in third_party-licenses unchanged`() {
    val sources = licenses.walkTopDown().filter { it.isFile && it.name != "BUILD.bazel" }.toList()
    assertWithMessage("files in %s", licenses).that(sources).isNotEmpty()
    ZipFile(jar()).use { jar ->
      for (source in sources) {
        val entry = THIRD_PARTY + source.relativeTo(licenses).invariantSeparatorsPath
        val shipped = jar.getEntry(entry)?.let { jar.getInputStream(it).use { it.readBytes() } }
        assertWithMessage("%s in the deploy jar", entry).that(shipped).isEqualTo(source.readBytes())
      }
    }
  }

  @Test
  fun `every path in the deploy jar is covered by a notice that ships in it`() {
    ZipFile(jar()).use { jar ->
      val entries = jar.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
      val names = entries.toSet()

      assertWithMessage("notices that COVERAGE names but the deploy jar lacks")
          .that(COVERAGE.values.flatten().map { NOTICES + it }.toSortedSet() - names)
          .isEmpty()

      val used = mutableSetOf<String>()
      val uncovered = sortedSetOf<String>()
      for (entry in entries) {
        val path = entry.replaceFirst(MULTI_RELEASE, "")
        if (path.startsWith("META-INF/")) continue
        val key = COVERAGE.keys.filter { covers(it, path) }.maxByOrNull { it.length }
        if (key == null) uncovered += path.substringBeforeLast('/', "(top level) $path")
        else used += key
      }
      assertWithMessage(
              "packages in the deploy jar that no COVERAGE entry covers (add them, with their notices)"
          )
          .that(uncovered)
          .isEmpty()
      assertWithMessage("COVERAGE entries that cover nothing in the deploy jar (remove them)")
          .that(COVERAGE.keys - used)
          .isEmpty()
    }
  }

  private fun jar() = workspace.resolve("daemon/ktrapic-daemon_deploy.jar")

  private companion object {
    const val NOTICES = "META-INF/ktrapic/"
    const val THIRD_PARTY = NOTICES + "third-party/"

    /** Classes for newer JVMs, in a multi-release jar: checked as if they were at the top. */
    val MULTI_RELEASE = Regex("^META-INF/versions/\\d+/")

    /**
     * Whether [key] covers [path]: a key names a file, a directory with everything under it, or,
     * ending in a slash and an asterisk, only the files directly in a directory. A path is covered
     * by the longest key that covers it. META-INF/ holds manifests, service registrations, Kotlin
     * module metadata, build metadata and the notices themselves, not packages, so the test leaves
     * it out (apart from multi-release classes).
     */
    fun covers(key: String, path: String) =
        if (key.endsWith("/*")) path.substringBeforeLast('/', "") == key.removeSuffix("/*")
        else path == key || path.startsWith("$key/")

    val KTRAPIC = listOf("LICENSE", "PYTRAPIC-LICENSE")
    val KOTLIN =
        listOf(
            "third-party/kotlin/LICENSE.txt",
            "third-party/kotlin/NOTICE.txt",
            "third-party/kotlin/README.md",
        )
    val INTELLIJ = listOf("third-party/intellij/LICENSE.txt", "third-party/intellij/NOTICE.txt")
    val COROUTINES =
        listOf(
            "third-party/kotlinx-coroutines/LICENSE.txt",
            "third-party/kotlinx-coroutines/NOTICE.txt",
        )
    val ANNOTATIONS = listOf("third-party/jetbrains-annotations/LICENSE.txt")

    /** The Kotlin compiler's own packages under org/jetbrains/kotlin/, apart from shaded code. */
    val KOTLIN_COMPILER_PACKAGES =
        listOf(
            "*",
            "analyzer",
            "asJava",
            "backend",
            "build",
            "buildtools",
            "builtins",
            "cfg",
            "checkers",
            "cli",
            "codegen",
            "compiler",
            "compilerRunner",
            "config",
            "constant",
            "container",
            "context",
            "contracts",
            "coroutines",
            "daemon",
            "descriptors",
            "diagnostics",
            "extensions",
            "fileClasses",
            "fir",
            "frontend",
            "idea",
            "incremental",
            "inline",
            "io/*",
            "ir",
            "javac",
            "js",
            "kapt3",
            "kdoc",
            "konan",
            "lang",
            "lexer",
            "library",
            "load",
            "metadata",
            "modules",
            "mpp",
            "name",
            "native",
            "parsing",
            "platform",
            "progress",
            "psi",
            "psi2ir",
            "references",
            "renderer",
            "resolution",
            "resolve",
            "serialization",
            "stats",
            "storage",
            "synthetic",
            "type",
            "types",
            "util",
            "utils",
            "wasm",
        )

    /** Paths in the jar (see [covers]) to the notices covering them, relative to [NOTICES]. */
    val COVERAGE: Map<String, List<String>> =
        mapOf(
            // KTrapIC: its classes (the SDK's made from PyTrapIC's data) and Bazel's build data.
            "ktrapic" to KTRAPIC,
            "build-data.properties" to KTRAPIC,
            // Kotlin: the standard library, kotlin-reflect, the script runtime, and the compiler.
            "kotlin" to KOTLIN,
            "kotlin/reflect/jvm/internal/impl/protobuf" to
                KOTLIN + "third-party/kotlin/third_party/protobuf_license.txt",
            "custom-formatters.js" to KOTLIN,
            "kotlinManifest.properties" to KOTLIN,
            "org/jetbrains/kotlin/protobuf" to
                KOTLIN + "third-party/kotlin/third_party/protobuf_license.txt",
            // IntelliJ Platform core, shaded into the compiler, and what it bundles.
            "org/jetbrains/kotlin/com/intellij" to INTELLIJ,
            "org/jetbrains/concurrency" to INTELLIJ,
            "messages" to INTELLIJ,
            "misc" to INTELLIJ,
            "pluginsCompatibleWithK2Mode.txt" to INTELLIJ,
            "org/jetbrains/kotlin/org/jdom" to listOf("third-party/jdom/LICENSE.txt"),
            "org/jetbrains/kotlin/org/picocontainer" to
                listOf("third-party/picocontainer/LICENSE.txt"),
            // Other libraries shaded into the compiler.
            "org/jetbrains/org/objectweb/asm" to
                listOf(
                    "third-party/asm/LICENSE.txt",
                    "third-party/kotlin/third_party/asm_license.txt",
                ),
            "org/jetbrains/kotlin/it/unimi/dsi/fastutil" to
                listOf("third-party/kotlin/third_party/fastutil_licence"),
            "org/jetbrains/kotlin/com/google/common" to
                listOf("third-party/kotlin/third_party/guava_license.txt"),
            "org/jetbrains/kotlin/com/fasterxml/aalto" to
                listOf("third-party/kotlin/third_party/aalto_xml_licence.txt"),
            "org/jetbrains/kotlin/org/codehaus/stax2" to
                listOf("third-party/kotlin/third_party/stax2-api.txt"),
            "org/jetbrains/kotlin/io/opentelemetry" to
                listOf("third-party/kotlin/third_party/opentelemetry_license.txt"),
            "org/jetbrains/kotlin/org/antlr/v4" to listOf("third-party/antlr4/LICENSE.txt"),
            "org/jetbrains/kotlin/org/apache/log4j" to
                listOf("third-party/log4j/LICENSE", "third-party/log4j/NOTICE"),
            "org/jetbrains/kotlin/org/fusesource/jansi" to listOf("third-party/jansi/license.txt"),
            "org/jetbrains/kotlin/org/jline" to listOf("third-party/jline/LICENSE.txt"),
            "org/jetbrains/kotlin/javax/inject" to
                listOf("third-party/javax-inject/LICENSE-2.0.txt"),
            "org/jetbrains/kotlin/kotlinx/collections/immutable" to
                listOf("third-party/kotlinx-collections-immutable/LICENSE.txt"),
            "org/jetbrains/kotlin/one/util/streamex" to listOf("third-party/streamex/LICENSE"),
            "org/jetbrains/kotlin/net/jpountz" to
                listOf("third-party/lz4-java/LICENSE.txt", "third-party/lz4-java/lz4-lib-LICENSE"),
            "org/jetbrains/kotlin/io/vavr" to listOf("third-party/vavr/LICENSE"),
            // Libraries the jar bundles directly.
            "kotlinx/coroutines" to COROUTINES,
            "_COROUTINE" to COROUTINES,
            "DebugProbesKt.bin" to COROUTINES,
            "com/google/gson" to listOf("third-party/gson/LICENSE"),
            "org/jetbrains/annotations" to ANNOTATIONS,
            "org/intellij/lang/annotations" to ANNOTATIONS,
        ) + KOTLIN_COMPILER_PACKAGES.associate { "org/jetbrains/kotlin/$it" to KOTLIN }
  }
}
