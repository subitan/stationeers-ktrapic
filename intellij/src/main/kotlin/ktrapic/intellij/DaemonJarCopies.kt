package ktrapic.intellij

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.jar.Attributes
import java.util.jar.JarFile

/**
 * Copies of the daemon's jar in [dir], for the daemon to run from. A running JVM keeps its jar
 * open, and on Windows that stops the jar from being replaced, so the daemon runs from a copy and
 * the jar itself can be rebuilt while it runs.
 *
 * A copy is named by its jar's name, size and modification time, so a rebuilt jar gets a new copy,
 * and every project whose daemon runs the same jar shares one copy of it. The directory is shared
 * by every project, in every IDE, with no lock between them; [removeAllBut] leaves recent files
 * alone so as not to delete a copy another project is making or is about to run.
 */
class DaemonJarCopies(val dir: File) {
  /**
   * The copy of [jar] to run, made if there isn't one yet. Its modification time is when it was
   * last handed out; when that can't be set (the copy has just been deleted, say), the jar is
   * copied again, so a copy is never handed out looking old enough to clean up. Throws if [jar]
   * changes while it is copied or isn't a complete runnable jar (as while Bazel is writing it), so
   * a copy is never a part of a jar.
   */
  fun copyOf(jar: File): File {
    val before = Stamp.of(jar) ?: throw IOException("There is no file $jar")
    val copy = File(dir, nameOf(jar, before))
    if (
        copy.isFile &&
            copy.length() == before.size &&
            copy.setLastModified(System.currentTimeMillis())
    ) {
      return copy
    }
    dir.mkdirs()
    val partial = File.createTempFile(PARTIAL_PREFIX, ".jar", dir)
    try {
      // Written rather than copied with the file system's copy, which on Windows gives the copy
      // the jar's modification time; the copy's should be when it was made.
      Files.newOutputStream(partial.toPath()).use { Files.copy(jar.toPath(), it) }
      if (Stamp.of(jar) != before) throw IOException("$jar changed while it was being copied")
      checkRunnable(partial, jar)
      Files.move(partial.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING)
    } finally {
      partial.delete()
    }
    return copy
  }

  /** Where [copyOf] puts [jar]'s copy, or null if there is no [jar]. */
  fun copyPathOf(jar: File): File? = Stamp.of(jar)?.let { File(dir, nameOf(jar, it)) }

  /**
   * Deletes every copy but [keep], and any copy left partly made, that was last modified more than
   * [MIN_AGE_MILLIS] before [now] and can be deleted. A newer file may be a copy another project is
   * making, or has just been handed and is starting a daemon from, so it is left for a later call.
   * On Windows a copy a running daemon has open (another project's, say) can't be deleted, and
   * stays until a later call once that daemon has stopped. Elsewhere it is deleted, and the daemon
   * running from it is unaffected, as it keeps the file open; its project copies the jar again the
   * next time it needs the copy.
   */
  fun removeAllBut(keep: File?, now: Long = System.currentTimeMillis()) {
    dir.listFiles()?.forEach {
      if (
          it != keep &&
              it.isFile &&
              it.name.endsWith(".jar") &&
              now - it.lastModified() > MIN_AGE_MILLIS
      ) {
        it.delete()
      }
    }
  }

  private data class Stamp(val size: Long, val modified: Long) {
    companion object {
      fun of(file: File): Stamp? =
          if (file.isFile) Stamp(file.length(), file.lastModified()) else null
    }
  }

  companion object {
    /** How long [removeAllBut] leaves a file alone after it was last modified. */
    val MIN_AGE_MILLIS = TimeUnit.MINUTES.toMillis(10)

    private const val PARTIAL_PREFIX = "partial-"

    private fun nameOf(jar: File, stamp: Stamp): String =
        "${jar.nameWithoutExtension}-${stamp.size}-${stamp.modified}.jar"

    /** Throws unless [copy], a copy of [jar], is a complete jar naming a main class. */
    private fun checkRunnable(copy: File, jar: File) {
      val main =
          try {
            JarFile(copy).use { it.manifest?.mainAttributes?.getValue(Attributes.Name.MAIN_CLASS) }
          } catch (e: IOException) {
            throw IOException("$jar isn't a complete jar: ${e.message}", e)
          }
      if (main == null) throw IOException("$jar names no main class")
    }
  }
}
