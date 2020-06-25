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

package com.google.idea.perf.cachedvaluetracer

import com.google.idea.perf.TracerView
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.rd.attach
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.textCompletion.TextFieldWithCompletion
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import javax.swing.Action
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.border.Border

class CachedValueTracerAction: DumbAwareAction() {
    private var currentTracer: CachedValueTracerDialog? = null

    override fun actionPerformed(e: AnActionEvent) {
        val tracer = currentTracer
        if (tracer != null) {
            check(!tracer.isDisposed)
            tracer.toFront()
        }
        else {
            val newTracer = CachedValueTracerDialog()
            currentTracer = newTracer
            newTracer.disposable.attach { currentTracer = null }
            newTracer.show()
        }
    }
}

class CachedValueTracerDialog: DialogWrapper(null, null, false, IdeModalityType.IDE, false) {
    init {
        title = "Cached Value Tracer"
        isModal = false
        init()
    }

    override fun createCenterPanel(): JComponent? = CachedValueTracerView(disposable)
    override fun createContentPaneBorder(): Border? = null
    override fun getDimensionServiceKey(): String? =
        "com.google.idea.perf.cachedvaluetracer.CachedValueTracer"
    override fun createActions(): Array<Action> = emptyArray()
}

class CachedValueTracerView(parentDisposable: Disposable): TracerView() {
    private val controller = CachedValueTracerController(this, parentDisposable)
    override val commandLine: TextFieldWithCompletion
    override val progressBar: JProgressBar
    override val refreshTimeLabel: JBLabel
    val listView = CachedValueTable(CachedValueTableModel())

    init {
        preferredSize = Dimension(500, 500)
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        // Command line.
        commandLine = TextFieldWithCompletion(
            ProjectManager.getInstance().defaultProject,
            controller.autocomplete,
            "",
            false,
            true,
            true
        ).apply {
            maximumSize = Dimension(Integer.MAX_VALUE, minimumSize.height)
            addDocumentListener(object: DocumentListener {
                override fun beforeDocumentChange(event: DocumentEvent) {
                    if (event.newFragment.contains('\n')) {
                        controller.handleRawCommandFromEdt(text)
                        ApplicationManager.getApplication().invokeLater {
                            this@apply.text = ""
                        }
                    }
                }
            })
        }
        add(commandLine)

        // Progress bar.
        progressBar = JProgressBar().apply {
            isVisible = false
            maximumSize = Dimension(Integer.MAX_VALUE, minimumSize.height)
        }
        add(progressBar)

        // List view.
        add(JBScrollPane(listView))

        // Render time label.
        refreshTimeLabel = JBLabel().apply {
            font = JBUI.Fonts.create(JBFont.MONOSPACED, font.size)
        }
        add(JPanel().apply {
            maximumSize = Dimension(Integer.MAX_VALUE, minimumSize.height)
            add(refreshTimeLabel)
        })

        // Start trace data collection.
        controller.startDataRefreshLoop()
    }
}
