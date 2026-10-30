package ktrapic.frontend

import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtScriptInitializer

/** The name of the object a script-style program is wrapped in. */
const val PROGRAM_OBJECT = "Program"

/**
 * Script-style source rewritten as an ordinary Kotlin file, plus what is needed to map positions in
 * [text] back to the user's source, [original].
 */
class WrappedSource(
    val text: String,
    private val original: String,
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

  /**
   * Whether a 1-based [line]/[column] in [text] is a character of the user's source, rather than
   * one of the text wrapping added: the default imports, `object Program {`, `init {`, a
   * statement's closing ` }` or the program's closing brace, on the lines after the source.
   */
  fun isInSource(line: Int, column: Int): Boolean {
    if (line < 1 || line > lastLine || column < 1) return false
    var shifted = 0
    for (insertion in insertions[line].orEmpty().sortedBy { it.column }) {
      val generatedStart = insertion.column + shifted + 1
      if (column < generatedStart) return true
      if (column < generatedStart + insertion.length) return false
      shifted += insertion.length
    }
    return true
  }

  /**
   * Maps an [offset] in the user's source to the offset of the same character in [text]: past the
   * text inserted before it, including any inserted right at it.
   */
  fun wrappedOffset(offset: Int): Int {
    val at = offset.coerceIn(0, original.length)
    val line = original.substring(0, at).count { it == '\n' } + 1
    val column = at - (original.lastIndexOf('\n', at - 1) + 1)
    val before =
        insertions.entries.sumOf { (insertedLine, onLine) ->
          when {
            insertedLine < line -> onLine.sumOf { it.length }
            insertedLine == line -> onLine.filter { it.column <= column }.sumOf { it.length }
            else -> 0
          }
        }
    return at + before
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
  /**
   * The script's syntax errors, positioned in the user's source. Checking these before [wrap] keeps
   * an unbalanced brace from being reported in terms of the wrapper it would otherwise close.
   */
  fun syntaxErrors(script: KtFile): List<Diagnostic> =
      PsiTreeUtil.collectElementsOfType(script, PsiErrorElement::class.java)
          .sortedBy { it.textRange.startOffset }
          .map {
            val (line, column) = lineAndColumn(script.text, it.textRange.startOffset)
            Diagnostic(
                Diagnostic.Severity.ERROR,
                "Syntax error: ${it.errorDescription}",
                line,
                column + 1,
            )
          }

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

  /**
   * Warnings for the script's top-level properties that nothing reads: "Variable is unused." for
   * one never named again, and "Variable is never read." for a `var` only ever assigned with `=`.
   * K2's extra checkers warn of these in a block, but the script's top-level properties are members
   * of the wrapper's object, which they don't check, though in a script they are its variables. A
   * name used anywhere else in the script counts as a use, even where a local of the same name
   * hides the property, so this never warns of a property the program uses. As with K2's own,
   * `@Suppress` on the property or the file, naming the diagnostic (`UNUSED_VARIABLE` or
   * `VARIABLE_NEVER_READ`), turns the warning off.
   */
  fun unreadProperties(script: KtFile): List<Diagnostic> {
    val properties = statements(script).filterIsInstance<KtProperty>()
    if (properties.isEmpty()) return emptyList()
    val uses =
        PsiTreeUtil.collectElementsOfType(script, KtNameReferenceExpression::class.java).groupBy {
          it.getReferencedName()
        }
    val fileSuppressed = suppressed(script.fileAnnotationList?.annotationEntries.orEmpty())
    return properties.mapNotNull { property ->
      val name = property.nameIdentifier ?: return@mapNotNull null
      val named = uses[property.name].orEmpty()
      val (id, message) =
          when {
            named.isEmpty() -> "UNUSED_VARIABLE" to "Variable is unused."
            property.isVar && named.all { it.isAssignedTo() } ->
                "VARIABLE_NEVER_READ" to "Variable is never read."
            else -> return@mapNotNull null
          }
      if (id in fileSuppressed || id in suppressed(property.annotationEntries)) {
        return@mapNotNull null
      }
      val (line, column) = lineAndColumn(script.text, name.textRange.startOffset)
      Diagnostic(Diagnostic.Severity.WARNING, message, line, column + 1)
    }
  }

  /** Whether this name is the target of a plain `=` assignment, which writes it without reading. */
  private fun KtNameReferenceExpression.isAssignedTo(): Boolean {
    val assignment = parent as? KtBinaryExpression ?: return false
    return assignment.operationToken == KtTokens.EQ && assignment.left == this
  }

  /** The diagnostics the `@Suppress` annotations among [annotations] name. */
  private fun suppressed(annotations: List<KtAnnotationEntry>): Set<String> =
      annotations
          .filter { it.shortName?.asString() == "Suppress" }
          .flatMap { it.valueArguments }
          .mapNotNull { it.getArgumentExpression()?.text?.removeSurrounding("\"") }
          .toSet()

  /**
   * The script as a file. Each of [defaultImports] (such as `ktrapic.*`) is imported as if it were
   * the script's first import. The imports go at the start of the script's import list, which the
   * parser places after any shebang, `@file:` annotations and `package` line, and before anything
   * in the script's body, including a stray leading `;`. A space separates them from a `package`
   * line or annotation they would otherwise run into. They share a line with the user's text, so no
   * line moves. A script with no imports and no statements has nothing that could use them, and
   * gets none.
   */
  fun wrap(script: KtFile, defaultImports: List<String> = emptyList()): WrappedSource {
    val source = script.text
    val edits = mutableListOf<Pair<Int, String>>()
    val statements = statements(script).ifEmpty { null }
    if (
        defaultImports.isNotEmpty() && (script.importDirectives.isNotEmpty() || statements != null)
    ) {
      val importsAt =
          requireNotNull(script.importList) { "${script.name} has no import list" }
              .textRange
              .startOffset
      val separator = if (importsAt > 0 && !source[importsAt - 1].isWhitespace()) " " else ""
      edits += importsAt to separator + defaultImports.joinToString("") { "import $it; " }
    }
    if (statements != null) {
      edits += statements.first().textRange.startOffset to "object $PROGRAM_OBJECT { "
    }
    // A script's top-level children are declarations (kept as members) or KtScriptInitializers,
    // which hold its plain statements.
    for (statement in statements.orEmpty().filterIsInstance<KtScriptInitializer>()) {
      edits += statement.textRange.startOffset to "init { "
      edits += statement.textRange.endOffset to " }"
    }
    // The newline before the brace keeps a final line comment from swallowing it. An unterminated
    // block comment still swallows it, adding a "Missing '}'" error beside "Unclosed comment".
    val closing = if (statements != null) "\n}\n" else ""

    val text = StringBuilder()
    val insertions = mutableMapOf<Int, MutableList<WrappedSource.Insertion>>()
    var cursor = 0
    // sortedBy is stable, so the imports stay ahead of `object Program {` at the same offset.
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
