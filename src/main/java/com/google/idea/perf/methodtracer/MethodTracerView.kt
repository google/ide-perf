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

package com.google.idea.perf.methodtracer

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

// Things to improve:
// - Add UI indicator for fps or something similar.
// - DialogWrapper wrapper is still tied to a specific project window.

/** Invoked by the user via the "Trace" action. */
class MethodTracerAction : DumbAwareAction() {
    private var currentTracer: MethodTracerDialog? = null

    override fun actionPerformed(e: AnActionEvent) {
        val tracer = currentTracer
        if (tracer != null) {
            check(!tracer.isDisposed)
            tracer.toFront()
        } else {
            val newTracer = MethodTracerDialog()
            currentTracer = newTracer
            newTracer.disposable.attach { currentTracer = null }
            newTracer.show()
        }
    }
}

/** The dialog window that pops up via the "Trace" action. */
class MethodTracerDialog : DialogWrapper(null, null, false, IdeModalityType.IDE, false) {
    init {
        title = "Tracer"
        isModal = false
        init()
    }

    override fun createCenterPanel(): JComponent = MethodTracerView(disposable)
    override fun createContentPaneBorder(): Border? = null // No border.
    override fun getDimensionServiceKey(): String = "com.google.idea.perf.methodtracer.Tracer"
    override fun createActions(): Array<Action> = emptyArray()
}

/** The content filling the tracer dialog window. */
class MethodTracerView(parentDisposable: Disposable) : TracerView() {
    private val controller = MethodTracerController(this, parentDisposable)
    override val commandLine: TextFieldWithCompletion
    override val progressBar: JProgressBar
    override val refreshTimeLabel: JBLabel
    val listView = TracepointTable(TracepointTableModel())

    init {
        preferredSize = Dimension(500, 500) // Only applies to first open.
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

        // Call list.
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
