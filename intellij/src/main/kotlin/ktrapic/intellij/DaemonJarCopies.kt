package ktrapic.intellij

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Copies of the daemon's jar in [dir], for the daemon to run from. A running JVM keeps its jar
 * open, and on Windows that stops the jar from being replaced, so the daemon runs from a copy and
 * the jar itself can be rebuilt while it runs.
 *
 * A copy is named by its jar's size and modification time, so a rebuilt jar gets a new copy.
 */
class DaemonJarCopies(private val dir: File) {
  /** The copy of [jar] to run, made if there isn't one yet. */
  fun copyOf(jar: File): File {
    val before = Stamp.of(jar) ?: throw IOException("There is no file $jar")
    val copy = File(dir, nameOf(jar, before))
    if (copy.isFile && copy.length() == before.size) return copy
    dir.mkdirs()
    val partial = File.createTempFile(PARTIAL_PREFIX, ".jar", dir)
    try {
      Files.copy(jar.toPath(), partial.toPath(), StandardCopyOption.REPLACE_EXISTING)
      if (Stamp.of(jar) != before) throw IOException("$jar changed while it was being copied")
      Files.move(partial.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING)
    } finally {
      partial.delete()
    }
    return copy
  }

  /**
   * Deletes every copy but [keep], and any copy left partly made. A copy a running daemon still has
   * open may not be deleted (on Windows); a later call deletes it.
   */
  fun removeAllBut(keep: File?) {
    dir.listFiles()?.forEach { if (it != keep && it.name.endsWith(".jar")) it.delete() }
  }

  private data class Stamp(val size: Long, val modified: Long) {
    companion object {
      fun of(file: File): Stamp? =
          if (file.isFile) Stamp(file.length(), file.lastModified()) else null
    }
  }

  private companion object {
    const val PARTIAL_PREFIX = "partial-"

    fun nameOf(jar: File, stamp: Stamp): String =
        "${jar.nameWithoutExtension}-${stamp.size}-${stamp.modified}.jar"
  }
}
