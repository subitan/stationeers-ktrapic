package ktrapic.frontend

import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtScriptInitializer

/** The name of the object a script-style program is wrapped in. */
const val PROGRAM_OBJECT = "Program"

/**
 * Script-style source rewritten as an ordinary Kotlin file, plus what is needed to map positions in
 * [text] back to the user's source, [original].
 */
class WrappedSource(
    val text: String,
    original: String,
    private val insertions: Map<Int, List<Insertion>>,
) {
  /** Text inserted at [column] (0-based, in the original line) on a line. */
  data class Insertion(val column: Int, val length: Int)

  private val lastLine = original.count { it == '\n' } + 1
  private val lastLineLength = original.length - (original.lastIndexOf('\n') + 1)

  /**
   * Maps a 1-based [line]/[column] in [text] to the user's source. Wrapping adds no lines inside
   * the user's source, so only columns on lines that received insertions move. Positions in the
   * closing text appended after the source map to the end of the source.
   */
  fun originalPosition(line: Int, column: Int): Pair<Int, Int> {
    if (line > lastLine) return lastLine to lastLineLength + 1
    var original = column
    var shifted = 0
    for (insertion in insertions[line].orEmpty().sortedBy { it.column }) {
      val generatedStart = insertion.column + shifted + 1
      if (column < generatedStart) break
      if (column < generatedStart + insertion.length) return line to insertion.column + 1
      original -= insertion.length
      shifted += insertion.length
    }
    return line to original
  }
}

/**
 * Wraps a script-style program (top-level statements alongside declarations) into `object Program {
 * ... }`, turning each top-level statement into an `init { ... }` block.
 *
 * Kotlin runs an object's property initializers and `init` blocks in source order, so the wrapped
 * program keeps script semantics, and functions declared at the top level can still use top-level
 * properties. Classes, interfaces, enums and inline functions are all legal members of an object.
 * The one top-level construct that cannot become a member is a destructuring declaration, which
 * [unsupported] reports. Insertions are made within the user's lines; the closing brace goes on a
 * line of its own after the source, which [WrappedSource.originalPosition] maps to the end of it.
 *
 * This relies on the PSI shape kotlin-compiler-embeddable gives a parsed script: its block's
 * statements are declarations, with plain statements each held in a [KtScriptInitializer].
 */
object ScriptWrapper {
  /** Top-level constructs [wrap] cannot keep, as errors positioned in the user's source. */
  fun unsupported(script: KtFile): List<Diagnostic> =
      statements(script).filterIsInstance<KtDestructuringDeclaration>().map {
        val (line, column) = lineAndColumn(script.text, it.textRange.startOffset)
        Diagnostic(
            Diagnostic.Severity.ERROR,
            "Top-level destructuring declarations are not supported.",
            line,
            column + 1,
        )
      }

  fun wrap(script: KtFile): WrappedSource {
    val source = script.text
    val edits = mutableListOf<Pair<Int, String>>()
    val statements = statements(script).ifEmpty { null }
    if (statements != null) {
      edits += statements.first().textRange.startOffset to "object $PROGRAM_OBJECT { "
    }
    // A script's top-level children are declarations (kept as members) or KtScriptInitializers,
    // which hold its plain statements.
    for (statement in statements.orEmpty().filterIsInstance<KtScriptInitializer>()) {
      edits += statement.textRange.startOffset to "init { "
      edits += statement.textRange.endOffset to " }"
    }
    // The newline before the brace keeps a final `// comment` from swallowing it.
    val closing = if (statements != null) "\n}\n" else ""

    val text = StringBuilder()
    val insertions = mutableMapOf<Int, MutableList<WrappedSource.Insertion>>()
    var cursor = 0
    for ((offset, insert) in edits.sortedBy { it.first }) {
      text.append(source, cursor, offset)
      cursor = offset
      val (line, column) = lineAndColumn(source, offset)
      insertions.getOrPut(line) { mutableListOf() } +=
          WrappedSource.Insertion(column, insert.length)
      text.append(insert)
    }
    text.append(source, cursor, source.length).append(closing)
    return WrappedSource(text.toString(), source, insertions)
  }

  private fun statements(script: KtFile) =
      requireNotNull(script.script) { "${script.name} did not parse as a script" }
          .blockExpression
          .statements

  /** The 1-based line and 0-based column of [offset] in [source]. */
  private fun lineAndColumn(source: String, offset: Int): Pair<Int, Int> =
      source.substring(0, offset).count { it == '\n' } + 1 to
          offset - (source.lastIndexOf('\n', offset - 1) + 1)
}
