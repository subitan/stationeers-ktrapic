package ktrapic.intellij

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import java.io.File

/**
 * KTrapIC's settings, for the whole IDE: where the compile daemon is, the Java that runs it, and
 * whether to show the Kotlin compiler's own problems. How a program compiles is up to the program,
 * with file annotations such as `@file:Compact`, so there are no compile options here.
 */
@Service(Service.Level.APP)
@State(name = "KtrapicSettings", storages = [Storage("ktrapic.xml")])
class KtrapicSettings : SimplePersistentStateComponent<KtrapicSettings.Options>(Options()) {
  class Options : BaseState() {
    /**
     * The daemon's deploy jar; blank for the one the project builds, or else the plugin's own (see
     * [KtrapicPaths.daemonJar]).
     */
    var daemonJar by string("")
    /** The `java` that runs the daemon; blank for the IDE's own runtime. */
    var javaPath by string("")
    /** Also show the Kotlin compiler's own problems, which the Kotlin plugin normally shows. */
    var showKotlinProblems by property(false)
  }

  companion object {
    fun getInstance(): KtrapicSettings = service()
  }
}

/** Where the daemon and the Java that runs it are, from the settings. */
object KtrapicPaths {
  /** Where the daemon's deploy jar is built, relative to a checkout of the repository. */
  const val BUILT_DAEMON_JAR = "bazel-bin/daemon/ktrapic-daemon_deploy.jar"

  /**
   * The daemon jar: [setting] if it isn't blank, or else the jar Bazel builds under [projectBase]
   * if it exists (in a checkout of the repository, where it is the daemon being worked on), or else
   * [bundled], the plugin's own, if there is one.
   */
  fun daemonJar(setting: String?, projectBase: String?, bundled: File?): String? {
    if (!setting.isNullOrBlank()) return setting.trim()
    val built = projectBase?.let { File(it, BUILT_DAEMON_JAR) }
    if (built != null && built.isFile) return built.path
    return bundled?.takeIf { it.isFile }?.path
  }

  /** The `java` to run: [setting] if it isn't blank, or else the one in [javaHome]. */
  fun java(setting: String?, javaHome: String = System.getProperty("java.home")): String {
    if (!setting.isNullOrBlank()) return setting.trim()
    val windows = System.getProperty("os.name").startsWith("Windows")
    return File(javaHome, if (windows) "bin/java.exe" else "bin/java").path
  }

  /** Whether [name] is a KTrapIC program's file name. */
  fun isProgram(name: String): Boolean = name.endsWith(".ic10.kts")
}
