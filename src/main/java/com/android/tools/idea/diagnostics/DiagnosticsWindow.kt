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

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        // Command line.
        val commandLine = JBTextField().apply {
            maximumSize = Dimension(Integer.MAX_VALUE, minimumSize.height)
        }
        add(commandLine)
    }
}
