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

import com.google.idea.perf.tracer.CallTree
import com.google.idea.perf.tracer.TracepointStats
import com.google.idea.perf.tracer.TracerCompletionProvider
import com.google.idea.perf.tracer.TracerController
import com.google.idea.perf.util.formatNsInMs
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager.getApplication
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.util.PopupUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.textCompletion.TextFieldWithCompletion
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.JProgressBar

// Things to improve:
// * Optimization: only update the tracer tab that is currently visible.

/** The top-level panel for the tracer, displayed via the [TracerDialog]. */
class TracerPanel(private val parentDisposable: Disposable) : JBPanel<TracerPanel>() {
    val controller = TracerController(this, parentDisposable)
    private val commandLine: TextFieldWithCompletion
    private val progressBar: JProgressBar
    private val tabs: JBTabbedPane
    private val listView: TracerTable
    private val treeView: TracerTree
    private val refreshTimeLabel: JBLabel

    init {
        preferredSize = Dimension(500, 500) // Only applies to first open.
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        // Command line.
        val completionProvider = TracerCompletionProvider()
        commandLine = TracerCommandLine(completionProvider, controller::handleRawCommandFromEdt)
        add(commandLine)

        // Progress bar.
        progressBar = JProgressBar()
        progressBar.isVisible = false
        progressBar.maximumSize = Dimension(Integer.MAX_VALUE, minimumSize.height)
        add(progressBar)

        // Call tree tabs.
        tabs = JBTabbedPane()
        listView = TracerTable(TracerTableModel())
        treeView = TracerTree(TracerTreeModel())
        tabs.addTab("List", JBScrollPane(listView))
        tabs.addTab("Tree", JBScrollPane(treeView))
        add(tabs)

        // Render time label.
        refreshTimeLabel = JBLabel()
        refreshTimeLabel.font = EditorUtil.getEditorFont()
        val refreshTimePanel = JPanel()
        refreshTimePanel.maximumSize = Dimension(Integer.MAX_VALUE, minimumSize.height)
        refreshTimePanel.add(refreshTimeLabel)
        add(refreshTimePanel)

        // Start tracepoint data collection.
        controller.startDataRefreshLoop()
    }

    fun refreshCallTreeData(callTree: CallTree, flatStats: List<TracepointStats>) {
        getApplication().assertIsDispatchThread()
        listView.setTracepointStats(flatStats)
        treeView.setCallTree(callTree)
    }

    fun setRefreshTime(refreshTime: Long) {
        getApplication().assertIsDispatchThread()
        val timeText = formatNsInMs(refreshTime)
        refreshTimeLabel.text = "Refresh Time: %9s".format(timeText)
    }

    fun showCommandLinePopup(message: String, type: MessageType) {
        getApplication().assertIsDispatchThread()
        PopupUtil.showBalloonForComponent(commandLine, message, type, true, parentDisposable)
    }

    fun createProgressIndicator(): ProgressIndicator {
        return object : ProgressIndicatorBase() {
            override fun onRunningChange(): Unit = onChange()

            override fun onProgressChange(): Unit = onChange()

            private fun onChange() {
                invokeLater {
                    progressBar.isVisible = isRunning
                    progressBar.isIndeterminate = isIndeterminate
                    progressBar.minimum = 0
                    progressBar.maximum = 100
                    progressBar.value = (fraction * 100).toInt().coerceIn(0, 100)
                }
            }
        }
    }
}
