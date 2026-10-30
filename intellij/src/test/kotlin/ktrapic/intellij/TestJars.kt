package ktrapic.intellij

import java.io.File
import java.nio.file.Files
import java.util.Random
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.CRC32
import java.util.zip.ZipEntry

/** Runnable jars of the stand-in daemons, for tests. */
object TestJars {
  /**
   * A jar at [jar] that runs [main], holding it and the classes nested in it, and [padding] bytes
   * of random data stored uncompressed, to make the jar as slow to copy as a real daemon's.
   */
  fun daemon(jar: File, main: Class<*>, padding: Int = 0): File {
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
      if (padding > 0) {
        val bytes = ByteArray(padding).also { Random().nextBytes(it) }
        val entry =
            JarEntry("padding").apply {
              method = ZipEntry.STORED
              size = bytes.size.toLong()
              crc = CRC32().apply { update(bytes) }.value
            }
        out.putNextEntry(entry)
        out.write(bytes)
        out.closeEntry()
      }
    }
    return jar
  }

  val windows: Boolean
    get() = System.getProperty("os.name").startsWith("Windows")
}
