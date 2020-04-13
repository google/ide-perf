package com.android.tools.idea.diagnostics

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import java.awt.Dimension
import javax.swing.BoxLayout

// TODO: Reconcile the fact that DiagnosticsController is application-level, whereas tool windows are project-level.

// Things to improve:
// - Reconsider whether this needs to be a tool window. It could instead be an internal action, for example.
// - Add a tool window icon.

class DiagnosticsWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentManager = toolWindow.contentManager
        contentManager.removeAllContents(true)
        val panel = DiagnosticsView()
        val content = contentManager.factory.createContent(panel, null, false)
        contentManager.addContent(content)
    }
}

class DiagnosticsView : JBPanel<DiagnosticsView>() {
    private val controller = DiagnosticsController(this)
    val listView = CallTableView(CallTableModel())

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        // Command line.
        val commandLine = JBTextField().apply {
            maximumSize = Dimension(Integer.MAX_VALUE, minimumSize.height)
            addActionListener { e ->
                text = ""
                controller.handleRawCommandFromEdt(e.actionCommand)
            }
        }
        add(commandLine)

        // Call list.
        add(JBScrollPane(listView))
    }
}