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

package com.google.idea.perf.tracer.ui

import com.google.idea.perf.TracerViewBase
import com.google.idea.perf.tracer.MethodTracerController
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.textCompletion.TextFieldWithCompletion
import java.awt.Dimension
import java.awt.KeyboardFocusManager
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.JProgressBar

/** The top-level panel for the tracer, displayed via the [TracerDialog]. */
class TracerPanel(parentDisposable: Disposable) : TracerViewBase() {
    override val controller = MethodTracerController(this, parentDisposable)
    override val commandLine: TextFieldWithCompletion
    override val progressBar: JProgressBar
    override val refreshTimeLabel: JBLabel
    val listView = TracerTable(TracerTableModel())
    val treeView = TracerTree(TracerTreeModel())

    init {
        preferredSize = Dimension(500, 500) // Only applies to first open.
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        // Command line.
        commandLine = TextFieldWithCompletion(
            ProjectManager.getInstance().defaultProject,
            controller.autocomplete, "", true, true, true
        ).apply {
            font = EditorUtil.getEditorFont()
            maximumSize = Dimension(Integer.MAX_VALUE, minimumSize.height)
            // Prevent accidental focus loss due to <tab> key.
            setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, emptySet())
            setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, emptySet())
        }
        add(commandLine)

        // Progress bar.
        progressBar = JProgressBar().apply {
            isVisible = false
            maximumSize = Dimension(Integer.MAX_VALUE, minimumSize.height)
        }
        add(progressBar)

        // Tabs.
        val tabs = JBTabbedPane().apply {
            addTab("List", null, JBScrollPane(listView), "Flat list of all tracepoints")
            addTab("Tree", null, JBScrollPane(treeView), "Call tree")
        }
        add(tabs)

        // Render time label.
        refreshTimeLabel = JBLabel().apply {
            font = EditorUtil.getEditorFont()
        }
        add(JPanel().apply {
            maximumSize = Dimension(Integer.MAX_VALUE, minimumSize.height)
            add(refreshTimeLabel)
        })

        // Start trace data collection.
        controller.startDataRefreshLoop()
    }
}
