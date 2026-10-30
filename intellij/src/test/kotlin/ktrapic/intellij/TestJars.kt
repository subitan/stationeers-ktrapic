package ktrapic.intellij

import java.io.File
import java.nio.file.Files
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/** Runnable jars of the stand-in daemons, for tests. */
object TestJars {
  /** A jar at [jar] that runs [main], holding it and the classes nested in it. */
  fun daemon(jar: File, main: Class<*>): File {
    val manifest =
        Manifest().apply {
          mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
          mainAttributes[Attributes.Name.MAIN_CLASS] = main.name
        }
    JarOutputStream(Files.newOutputStream(jar.toPath()), manifest).use { out ->
      for (type in listOf(main) + main.declaredClasses) {
        val path = type.name.replace('.', '/') + ".class"
        val bytes = type.classLoader.getResourceAsStream(path)!!.use { it.readBytes() }
        out.putNextEntry(JarEntry(path))
        out.write(bytes)
        out.closeEntry()
      }
    }
    return jar
  }

  val windows: Boolean
    get() = System.getProperty("os.name").startsWith("Windows")
}
