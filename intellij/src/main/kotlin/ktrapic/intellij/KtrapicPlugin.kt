package ktrapic.intellij

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import java.io.File

/**
 * The files the plugin bundles beside its own classes (see build.gradle.kts): the compile daemon's
 * jar, and the SDK and script definition jars the Kotlin plugin checks programs against.
 */
object KtrapicPlugin {
  const val ID = "com.github.subitan.ktrapic"

  /** The plugin's directory, or null if it isn't installed (as in some tests). */
  val dir: File?
    get() = PluginManagerCore.getPlugin(PluginId.getId(ID))?.pluginPath?.toFile()

  /** The bundled compile daemon's jar, if there is one. */
  val daemonJar: File?
    get() = dir?.let { File(it, "daemon/ktrapic-daemon.jar") }?.takeIf { it.isFile }

  /** The SDK's and script definition's jars, if they are there. */
  val scriptJars: List<File>?
    get() {
      val dir = File(dir ?: return null, "script")
      val jars = listOf(File(dir, "ktrapic-script.jar"), File(dir, "sdk.jar"))
      return jars.takeIf { it.all(File::isFile) }
    }
}
