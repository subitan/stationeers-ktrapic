package ktrapic.intellij

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

/**
 * Marks KTrapIC's errors and warnings in a program's editor. It compiles the program with the
 * compile daemon in the background (through [KtrapicCompileService]) and marks each problem at its
 * position, leaving out the Kotlin compiler's own unless the settings ask for them (see
 * [ProblemMarks.of]).
 */
class KtrapicAnnotator :
    ExternalAnnotator<KtrapicAnnotator.Info, KtrapicAnnotator.Result>(), DumbAware {
  class Info(val project: Project, val request: CompileRequest)

  class Result(val text: String, val response: CompileResponse)

  override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): Info? =
      collectInformation(file)

  override fun collectInformation(file: PsiFile): Info? {
    if (!KtrapicPaths.isProgram(file.name)) return null
    val text = file.viewProvider.document?.text ?: file.text
    val path = file.viewProvider.virtualFile.path
    return Info(
        file.project,
        CompileRequest(path, text, KtrapicSettings.getInstance().options),
    )
  }

  override fun doAnnotate(info: Info): Result? {
    val service = KtrapicCompileService.getInstance(info.project)
    val response =
        service.await(service.compile(info.request)) { ProgressManager.checkCanceled() }
            ?: return null
    return Result(info.request.text, response)
  }

  override fun apply(file: PsiFile, result: Result, holder: AnnotationHolder) {
    val text = file.viewProvider.document?.immutableCharSequence ?: file.text
    // The program has changed since it was compiled: its next compile marks it.
    if (text.length != result.text.length || text.toString() != result.text) return
    val showKotlin = KtrapicSettings.getInstance().state.showKotlinProblems
    for (mark in ProblemMarks.of(result.response, text, showKotlin)) {
      val severity = if (mark.error) HighlightSeverity.ERROR else HighlightSeverity.WARNING
      val builder = holder.newAnnotation(severity, mark.message).tooltip(tooltip(mark.message))
      when {
        mark.fileLevel -> builder.fileLevel()
        mark.start == mark.end -> builder.range(TextRange(mark.start, mark.end)).afterEndOfLine()
        else -> builder.range(TextRange(mark.start, mark.end))
      }.create()
    }
  }

  private fun tooltip(message: String): String =
      "<html>" +
          message
              .replace("&", "&amp;")
              .replace("<", "&lt;")
              .replace(">", "&gt;")
              .replace("\n", "<br>") +
          "</html>"
}
