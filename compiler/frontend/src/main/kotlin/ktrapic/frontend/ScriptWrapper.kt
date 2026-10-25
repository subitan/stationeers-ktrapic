package ktrapic.frontend

import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtScriptInitializer

/** The name of the object a script-style program is wrapped in. */
const val PROGRAM_OBJECT = "Program"

/**
 * Script-style source rewritten as an ordinary Kotlin file, plus what is needed to map positions in
 * [text] back to the user's source.
 */
class WrappedSource(val text: String, private val insertions: Map<Int, List<Insertion>>) {
  /** Text inserted at [column] (0-based, in the original line) on a line. */
  data class Insertion(val column: Int, val length: Int)

  /**
   * Maps a 1-based [line]/[column] in [text] to the user's source. Lines are never added or removed
   * by wrapping, so only columns on lines that received insertions move.
   */
  fun originalPosition(line: Int, column: Int): Pair<Int, Int> {
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
 * Insertions never add lines, so line numbers in diagnostics need no mapping.
 */
object ScriptWrapper {
  fun wrap(script: KtFile): WrappedSource {
    val source = script.text
    val body =
        requireNotNull(script.script) { "${script.name} did not parse as a script" }.blockExpression
    val edits = mutableListOf<Pair<Int, String>>()
    val statements = body.statements.ifEmpty { null }
    if (statements != null) {
      edits += statements.first().textRange.startOffset to "object $PROGRAM_OBJECT { "
    }
    // A script's top-level children are declarations (kept as members) or KtScriptInitializers,
    // which hold its plain statements.
    for (statement in statements.orEmpty().filterIsInstance<KtScriptInitializer>()) {
      edits += statement.textRange.startOffset to "init { "
      edits += statement.textRange.endOffset to " }"
    }
    val closing = if (statements != null) "\n}\n" else ""

    val text = StringBuilder()
    val insertions = mutableMapOf<Int, MutableList<WrappedSource.Insertion>>()
    var cursor = 0
    for ((offset, insert) in edits.sortedBy { it.first }) {
      text.append(source, cursor, offset)
      cursor = offset
      val line = source.substring(0, offset).count { it == '\n' } + 1
      val column = offset - (source.lastIndexOf('\n', offset - 1) + 1)
      insertions.getOrPut(line) { mutableListOf() } +=
          WrappedSource.Insertion(column, insert.length)
      text.append(insert)
    }
    text.append(source, cursor, source.length).append(closing)
    return WrappedSource(text.toString(), insertions)
  }
}
