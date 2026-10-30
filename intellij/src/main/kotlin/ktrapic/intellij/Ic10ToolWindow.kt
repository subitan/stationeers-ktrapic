package ktrapic.intellij

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LineNumberConverter
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import javax.swing.JPanel

/** The IC10 tool window: the selected program's IC10, its size, and the compiler's warnings. */
class Ic10ToolWindowFactory : ToolWindowFactory, DumbAware {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val panel = Ic10Panel(project)
    val content = ContentFactory.getInstance().createContent(panel.component, "", false)
    content.setDisposer(panel)
    toolWindow.contentManager.addContent(content)
  }
}

/**
 * Shows the IC10 of the program in the selected editor. It shows each compile of the program's
 * current text that the editor's annotator finishes, and compiles the program itself a moment after
 * it is changed, which shares the annotator's compile of the same text rather than repeating it.
 */
class Ic10Panel(private val project: Project) : Disposable {
  private val service = KtrapicCompileService.getInstance(project)
  private val document: Document = EditorFactory.getInstance().createDocument("")
  private val viewer = EditorFactory.getInstance().createViewer(document, project) as EditorEx
  private val counts = JBLabel()
  private val problems =
      JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(4)
      }
  private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
  private var view: Ic10View? = null

  val component: SimpleToolWindowPanel = SimpleToolWindowPanel(true, true)

  init {
    viewer.settings.apply {
      isLineNumbersShown = true
      isFoldingOutlineShown = false
      additionalLinesCount = 0
      isCaretRowShown = false
    }
    // The chip numbers its lines from 0, as jumps in compact output count them.
    viewer.gutterComponentEx.setLineNumberConverter(
        LineNumberConverter.Increasing { _, line -> line - 1 }
    )
    Disposer.register(this) { EditorFactory.getInstance().releaseEditor(viewer) }

    val splitter =
        JBSplitter(true, 0.8f).apply {
          firstComponent = viewer.component
          secondComponent = JBScrollPane(problems)
        }
    val content =
        JPanel(BorderLayout()).apply {
          counts.border = JBUI.Borders.empty(4, 6)
          add(counts, BorderLayout.NORTH)
          add(splitter, BorderLayout.CENTER)
        }
    component.setContent(content)
    val toolbar =
        ActionManager.getInstance()
            .createActionToolbar(
                "KTrapIC.IC10",
                DefaultActionGroup(CompactAction(), CopyAction(), RecompileAction()),
                true,
            )
    toolbar.targetComponent = content
    component.toolbar = toolbar.component

    val connection = project.messageBus.connect(this)
    connection.subscribe(
        FileEditorManagerListener.FILE_EDITOR_MANAGER,
        object : FileEditorManagerListener {
          override fun selectionChanged(event: FileEditorManagerEvent) = refresh()
        },
    )
    connection.subscribe(
        CompileListener.TOPIC,
        CompileListener { compiled ->
          ApplicationManager.getApplication()
              .invokeLater({ showIfCurrent(compiled) }, project.disposed)
        },
    )
    EditorFactory.getInstance()
        .eventMulticaster
        .addDocumentListener(
            object : DocumentListener {
              override fun documentChanged(event: DocumentEvent) {
                if (event.document == programDocument()) {
                  alarm.cancelAllRequests()
                  alarm.addRequest({ compile() }, DEBOUNCE_MILLIS)
                }
              }
            },
            this,
        )
    refresh()
  }

  /** Shows the selected program's last compile, or compiles it. */
  private fun refresh() {
    alarm.cancelAllRequests()
    val request = request()
    if (request == null) {
      show(null, "Open a KTrapIC program (.ic10.kts) to see its IC10.")
      return
    }
    val last = service.lastCompiled(request.path)
    if (last != null && last.request == request) show(last.response) else compile()
  }

  /** Compiles the selected program's current text, unless that compile is already under way. */
  private fun compile() {
    val request = request() ?: return
    val future = service.compile(request)
    if (!future.isDone) counts.text = "Compiling..."
    future.whenComplete { response, _ ->
      if (response != null) {
        ApplicationManager.getApplication()
            .invokeLater({ showIfCurrent(Compiled(request, response)) }, project.disposed)
      }
    }
  }

  private fun showIfCurrent(compiled: Compiled) {
    if (compiled.request == request()) show(compiled.response)
  }

  private fun programFile(): VirtualFile? =
      FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.takeIf {
        KtrapicPaths.isProgram(it.name)
      }

  private fun programDocument(): Document? =
      programFile()?.let { FileDocumentManager.getInstance().getDocument(it) }

  /** What compiling the selected program now asks for, or null if no program is selected. */
  private fun request(): CompileRequest? {
    val file = programFile() ?: return null
    val text = FileDocumentManager.getInstance().getDocument(file)?.text ?: return null
    return CompileRequest(file.path, text, KtrapicSettings.getInstance().options)
  }

  private fun show(response: CompileResponse) {
    val shown = Ic10Format.view(response)
    show(shown, if (shown.errors.isEmpty()) null else "Doesn't compile")
  }

  private fun show(shown: Ic10View?, status: String?) {
    view = shown
    ApplicationManager.getApplication().runWriteAction {
      document.setText(shown?.code.orEmpty().replace("\r", ""))
    }
    counts.text = status ?: shown?.let { countsHtml(it) }.orEmpty()
    val lines = shown?.errors.orEmpty() + shown?.warnings.orEmpty()
    problems.text =
        when {
          shown == null -> ""
          shown.errors.isNotEmpty() -> lines.joinToString("\n")
          lines.isEmpty() -> "No warnings."
          else -> lines.joinToString("\n")
        }
  }

  private fun countsHtml(shown: Ic10View): String {
    val error = ColorUtil.toHtmlColor(NamedColorUtil.getErrorForeground())
    return "<html>" +
        shown.counts.joinToString("&nbsp;&nbsp;&nbsp;") {
          if (it.over) "<b><font color='$error'>$it (over)</font></b>" else it.toString()
        } +
        "</html>"
  }

  override fun dispose() {}

  private inner class CompactAction :
      ToggleAction(
          "Compact",
          "Resolve labels to line numbers, to save lines",
          AllIcons.Actions.Collapseall,
      ),
      DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun isSelected(e: AnActionEvent): Boolean = KtrapicSettings.getInstance().state.compact

    override fun setSelected(e: AnActionEvent, state: Boolean) {
      KtrapicSettings.getInstance().state.compact = state
      // The editors' marks depend on the options too (a program over the chip's lines may fit
      // compact), so they are compiled again.
      for (open in ProjectManager.getInstance().openProjects) DaemonCodeAnalyzer.getInstance(open)
          .restart()
      refresh()
    }
  }

  private inner class CopyAction :
      AnAction("Copy IC10", "Copy the IC10 to the clipboard", AllIcons.Actions.Copy), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
      e.presentation.isEnabled = !view?.code.isNullOrEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
      view
          ?.code
          ?.takeIf { it.isNotEmpty() }
          ?.let {
            CopyPasteManager.getInstance().setContents(StringSelection(it))
          }
    }
  }

  private inner class RecompileAction :
      AnAction(
          "Restart Daemon and Recompile",
          "Start the compile daemon again, as after rebuilding its jar, and compile the program",
          AllIcons.Actions.Restart,
      ),
      DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
      service.restart()
      DaemonCodeAnalyzer.getInstance(project).restart()
      refresh()
    }
  }

  private companion object {
    const val DEBOUNCE_MILLIS = 600
  }
}
