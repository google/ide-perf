package com.android.tools.idea.diagnostics

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextField
import java.awt.Dimension
import javax.swing.BoxLayout

class DiagnosticsWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentManager = toolWindow.contentManager
        contentManager.removeAllContents(true)
        val panel = DiagnosticsWindow()
        val content = contentManager.factory.createContent(panel, null, false)
        contentManager.addContent(content)
    }
}

class DiagnosticsWindow : JBPanel<DiagnosticsWindow>() {
    private val listView = CallTableView(CallTableModel())

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        // Command line.
        val commandLine = JBTextField().apply {
            maximumSize = Dimension(Integer.MAX_VALUE, minimumSize.height)
        }
        add(commandLine)

        // TODO: remove this example code.
        // val child = ImmutableCallTree(Tracepoint("child"), 1, 10, emptyMap())
        // val root = ImmutableCallTree(Tracepoint.ROOT, 1, 10, mapOf(child.tracepoint to child))
        // val trees = mutableListOf(root, child)
        // listView.setCallTrees(trees)

        // Call list.
        add(listView)
    }
}
