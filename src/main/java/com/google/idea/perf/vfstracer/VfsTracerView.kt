/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.idea.perf.vfstracer

import com.google.idea.perf.tracer.ui.TracerUIUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.rd.attach
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.util.PopupUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.textCompletion.DefaultTextCompletionValueDescriptor
import com.intellij.util.textCompletion.TextFieldWithCompletion
import com.intellij.util.textCompletion.ValuesCompletionProvider.ValuesCompletionProviderDumbAware
import java.awt.Dimension
import javax.swing.Action
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.border.Border

class VfsTracerAction: DumbAwareAction() {
    private var currentTracer: VfsTracerDialog? = null

    override fun actionPerformed(e: AnActionEvent) {
        val tracer = currentTracer
        if (tracer != null) {
            check(!tracer.isDisposed)
            tracer.toFront()
        }
        else {
            val newTracer = VfsTracerDialog()
            currentTracer = newTracer
            newTracer.disposable.attach { currentTracer = null }
            newTracer.show()
        }
    }
}

class VfsTracerDialog: DialogWrapper(null, null, false, IdeModalityType.IDE, false) {
    init {
        title = "VFS Tracer"
        isModal = false
        init()
    }

    override fun createCenterPanel(): JComponent = VfsTracerView(disposable)
    override fun createContentPaneBorder(): Border? = null
    override fun getDimensionServiceKey(): String = "com.google.idea.perf.vfstracer.VfsTracer"
    override fun createActions(): Array<Action> = emptyArray()
}

class VfsTracerView(parentDisposable: Disposable) : JBPanel<VfsTracerView>() {
    private val controller = VfsTracerController(this, parentDisposable)
    private val commandLine: TextFieldWithCompletion
    val refreshTimeLabel: JBLabel
    private val tabs: JBTabbedPane
    val listView = VfsStatTable(VfsStatTableModel())
    val treeView = VfsStatTreeTable(VfsStatTreeTableModel())

    fun showCommandBalloon(message: String, type: MessageType) {
        PopupUtil.showBalloonForComponent(commandLine, message, type, true, null)
    }

    init {
        preferredSize = Dimension(500, 500)
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        // Command line.
        val completionProvider = ValuesCompletionProviderDumbAware(
            DefaultTextCompletionValueDescriptor.StringValueDescriptor(),
            listOf("start", "stop", "clear", "reset")
        )
        commandLine = TextFieldWithCompletion(
            ProjectManager.getInstance().defaultProject,
            completionProvider, "", true, true, false
        )
        TracerUIUtil.addEnterKeyAction(commandLine) {
            controller.handleRawCommandFromEdt(commandLine.text)
            commandLine.text = ""
        }
        TracerUIUtil.reinstallCompletionProviderAsNeeded(commandLine, completionProvider)
        commandLine.maximumSize = Dimension(Integer.MAX_VALUE, commandLine.minimumSize.height)
        add(commandLine)

        // Tabs.
        tabs = JBTabbedPane()
        tabs.addTab("List", JBScrollPane(listView))
        tabs.addTab("Tree", JBScrollPane(treeView))
        tabs.tabLayoutPolicy = JBTabbedPane.SCROLL_TAB_LAYOUT
        add(tabs)

        // Render time label.
        refreshTimeLabel = JBLabel().apply {
            font = EditorUtil.getEditorFont()
        }
        add(JPanel().apply {
            maximumSize = Dimension(Integer.MAX_VALUE, minimumSize.height)
            add(refreshTimeLabel)
        })

        // Start data trace collection.
        controller.startDataRefreshLoop()
    }
}
