package ktrapic.intellij

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel

/** Settings > Tools > KTrapIC. */
class KtrapicConfigurable : BoundConfigurable("KTrapIC") {
  private val settings = KtrapicSettings.getInstance().state

  override fun createPanel(): DialogPanel = panel {
    group("Compile Daemon") {
      row("Daemon jar:") {
        textFieldWithBrowseButton(
                FileChooserDescriptorFactory.createSingleFileDescriptor("jar")
                    .withTitle("KTrapIC Daemon Jar")
            )
            .bindText({ settings.daemonJar.orEmpty() }, { settings.daemonJar = it })
            .align(AlignX.FILL)
            .comment(
                "Blank: ${KtrapicPaths.BUILT_DAEMON_JAR} in the project, if it exists, or else " +
                    "the daemon the plugin comes with"
            )
      }
      row("Java:") {
        textFieldWithBrowseButton(
                FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                    .withTitle("Java Executable")
            )
            .bindText({ settings.javaPath.orEmpty() }, { settings.javaPath = it })
            .align(AlignX.FILL)
            .comment("Blank: the IDE's own Java runtime (${KtrapicPaths.java(null)})")
      }
    }
    group("Editor") {
      row {
        checkBox("Show the Kotlin compiler's own problems too")
            .bindSelected(
                { settings.showKotlinProblems },
                { settings.showKotlinProblems = it },
            )
            .comment(
                "The Kotlin plugin shows them already, once the KTrapIC script definition is set up"
            )
      }
    }
  }

  override fun apply() {
    super.apply()
    // The editors' marks come from compiles with these settings: compile again.
    for (project in ProjectManager.getInstance().openProjects) {
      DaemonCodeAnalyzer.getInstance(project).restart()
    }
  }
}
