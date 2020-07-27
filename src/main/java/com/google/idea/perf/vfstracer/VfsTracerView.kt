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

import com.google.idea.perf.TracerView
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.rd.attach
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.textCompletion.DefaultTextCompletionValueDescriptor
import com.intellij.util.textCompletion.TextFieldWithCompletion
import com.intellij.util.textCompletion.ValuesCompletionProvider
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import javax.swing.Action
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JProgressBar
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

            val view = newTracer.view!!
            view.initEvents(view.controller)
        }
    }
}

class VfsTracerDialog: DialogWrapper(null, null, false, IdeModalityType.IDE, false) {
    var view: VfsTracerView? = null; private set

    init {
        title = "VFS Tracer"
        isModal = false
        init()
    }

    override fun createCenterPanel(): JComponent {
        view = VfsTracerView(disposable)
        return view!!
    }
    override fun createContentPaneBorder(): Border? = null
    override fun getDimensionServiceKey(): String = "com.google.idea.perf.vfstracer.VfsTracer"
    override fun createActions(): Array<Action> = emptyArray()
}

class VfsTracerView(parentDisposable: Disposable): TracerView() {
    override val controller = VfsTracerController(this, parentDisposable)
    override val commandLine: TextFieldWithCompletion
    override val progressBar: JProgressBar
    override val refreshTimeLabel: JBLabel
    private val tabs: JBTabbedPane
    val listView = VfsStatTable(VfsStatTableModel())
    val treeView = VfsStatTreeTable(VfsStatTreeTableModel())

    init {
        preferredSize = Dimension(500, 500)
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        // Command line.
        commandLine = TextFieldWithCompletion(
            ProjectManager.getInstance().defaultProject,
            ValuesCompletionProvider(
                DefaultTextCompletionValueDescriptor.StringValueDescriptor(),
                listOf("start", "stop", "clear", "reset")
            ),
            "", true, true, true
        ).apply {
            maximumSize = Dimension(Integer.MAX_VALUE, minimumSize.height)
        }
        add(commandLine)

        // Progress bar.
        progressBar = JProgressBar().apply {
            isVisible = false
            maximumSize = Dimension(Integer.MAX_VALUE, minimumSize.height)
        }
        add(progressBar)

        // Tabs.
        tabs = JBTabbedPane()
        tabs.tabLayoutPolicy = JBTabbedPane.SCROLL_TAB_LAYOUT
        add(tabs)

        // List view.
        tabs.add("List View", JBScrollPane(listView))
        tabs.add("Tree View", JBScrollPane(treeView))

        // Render time label.
        refreshTimeLabel = JBLabel().apply {
            font = JBUI.Fonts.create(JBFont.MONOSPACED, font.size)
        }
        add(JPanel().apply {
            maximumSize = Dimension(Integer.MAX_VALUE, minimumSize.height)
            add(refreshTimeLabel)
        })

        // Start data trace collection.
        controller.startDataRefreshLoop()
    }
}
