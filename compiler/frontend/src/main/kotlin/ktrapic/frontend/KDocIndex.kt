package ktrapic.frontend

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

/**
 * The KDoc of the declarations whose Kotlin sources the classpath carries (the SDK's jar holds its
 * own, as Kotlin files under `ktrapic/`), by fully qualified name: `ktrapic.LedDisplay.Text`.
 * Overloads share a name, and the first one's KDoc is kept.
 *
 * Each KDoc is kept as text: without the comment's opening and closing marks and leading stars, a
 * paragraph's lines joined into one, and a link (`[Device]`) as the name it links to.
 */
internal class KDocIndex private constructor(private val docs: Map<String, String>) {
  operator fun get(fqName: String): String? = docs[fqName]

  companion object {
    private val indexes = ConcurrentHashMap<List<File>, KDocIndex>()

    /** The index of [classpath]'s sources, read the first time it is asked for. */
    fun of(classpath: List<File>): KDocIndex = indexes.computeIfAbsent(classpath, ::read)

    private fun read(classpath: List<File>): KDocIndex {
      val disposable = Disposer.newDisposable("ktrapic-kdoc")
      try {
        val factory = K2Frontend.psiFactory(disposable)
        val docs = mutableMapOf<String, String>()
        for ((name, text) in classpath.flatMap(::sourcesIn)) {
          factory
              .createFile(name, text.replace("\r\n", "\n"))
              .accept(
                  object : KtTreeVisitorVoid() {
                    override fun visitDeclaration(dcl: KtDeclaration) {
                      val doc = dcl.docComment
                      val fqName = (dcl as? KtNamedDeclaration)?.fqName
                      if (doc != null && fqName != null) {
                        docs.putIfAbsent(fqName.asString(), clean(doc.text))
                      }
                      super.visitDeclaration(dcl)
                    }
                  }
              )
        }
        return KDocIndex(docs)
      } finally {
        Disposer.dispose(disposable)
      }
    }

    /** The Kotlin sources in a jar or a directory on the classpath: each one's name and text. */
    private fun sourcesIn(entry: File): List<Pair<String, String>> =
        when {
          entry.isDirectory ->
              entry
                  .walk()
                  .filter { it.isFile && it.extension == "kt" }
                  .map {
                    it.name to it.readText()
                  }
                  .toList()
          entry.isFile && entry.extension == "jar" ->
              JarFile(entry).use { jar ->
                jar.entries()
                    .asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".kt") }
                    .map {
                      it.name.substringAfterLast('/') to jar.getInputStream(it).reader().readText()
                    }
                    .toList()
              }
          else -> emptyList()
        }

    private val LINK = Regex("""\[([^\[\]]+)](?!\()""")

    /** A paragraph's lines that start a line of their own: a list item or a tag. */
    private val OWN_LINE = Regex("""^(- |\d+\. |@|```)""")

    internal fun clean(comment: String): String {
      val lines =
          comment.removePrefix("/**").removeSuffix("*/").lines().map {
            it.trim().removePrefix("*").removePrefix(" ").trimEnd()
          }
      val text = StringBuilder()
      for (line in lines) {
        when {
          line.isEmpty() -> if (text.isNotEmpty() && !text.endsWith("\n\n")) text.append("\n\n")
          text.isEmpty() || text.endsWith("\n") -> text.append(line)
          OWN_LINE.containsMatchIn(line) -> text.append('\n').append(line)
          else -> text.append(' ').append(line)
        }
      }
      return LINK.replace(text.toString().trim(), "$1")
    }
  }
}
