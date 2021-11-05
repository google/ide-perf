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

import com.google.idea.perf.tracer.CallTreeManager
import com.google.idea.perf.tracer.CallTreeUtil
import com.google.idea.perf.tracer.TracerController
import com.google.idea.perf.util.formatNsInMs
import com.intellij.CommonBundle
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager.getApplication
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.project.Project
import com.intellij.openapi.rd.attach
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.util.PopupUtil
import com.intellij.ui.components.JBBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.tabs.JBTabs
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.impl.JBTabsImpl
import com.intellij.util.concurrency.EdtExecutorService
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.util.concurrent.TimeUnit.MILLISECONDS
import javax.swing.AbstractAction
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JProgressBar
import javax.swing.JSeparator
import javax.swing.SwingConstants.HORIZONTAL

// Things to improve:
// * Optimization: only update the tracer tab that is currently visible.
// * Optimization: fast path in updateCallTree() if the tree did not change.
// * Reset UI overhead to 0 after 'clear' command.
// * Focus traversal.

/**
 * This is the main tracer panel containing the command line, call tree view,
 * overhead labels, etc. It also polls for new call tree data in [updateCallTree].
 *
 * This panel is displayed via the [TracerDialog].
 */
class TracerPanel(
    project: Project,
    private val parentDisposable: Disposable
) : JBPanel<TracerPanel>() {
    val controller = TracerController(this, parentDisposable)
    private val commandLine: TracerCommandLine
    private val progressBar: JProgressBar
    private var showingEdtOnly = false
    private val listView: TracerTable
    private val treeView: TracerTree
    private val tracingOverheadLabel: JBLabel
    private val uiOverheadLabel: JBLabel
    private var uiOverhead = 0L

    companion object {
        private const val REFRESH_DELAY_MS = 100L
    }

    init {
        preferredSize = Dimension(500, 500) // Only applies to first open.
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        // Command line.
        commandLine = TracerCommandLine(project, controller)
        add(commandLine.component)

        // Progress bar.
        progressBar = JProgressBar()
        progressBar.isVisible = false
        add(progressBar)

        // Thread chooser.
        val allThreadsStr = "All Threads Merged"
        val edtStr = "AWT-EventQueue"
        val threadChooserModel = DefaultComboBoxModel(arrayOf(allThreadsStr, edtStr))
        fun createThreadChooser(): ComboBox<String> {
            val threadChooser = ComboBox(threadChooserModel)
            threadChooser.maximumSize = threadChooser.minimumSize
            threadChooser.addActionListener {
                val item = threadChooser.selectedItem as String
                val choseEdt = item == edtStr
                if (choseEdt != showingEdtOnly) {
                    showingEdtOnly = choseEdt
                    updateCallTree()
                }
            }
            return threadChooser
        }

        // Help button.
        fun createHelpButton(): JButton {
            val helpAction = object : AbstractAction(CommonBundle.getHelpButtonText()) {
                override fun actionPerformed(e: ActionEvent) {
                    val link = "https://github.com/google/ide-perf/blob/master/docs/user-guide.md"
                    BrowserUtil.browse(link)
                }
            }
            val helpButton = JButton(helpAction)
            helpButton.putClientProperty("JButton.buttonType", "help")
            helpButton.toolTipText = "Open user guide"
            return helpButton
        }

        // Tabs.
        val tabs: JBTabs = JBTabsImpl(null, null, parentDisposable)
        add(tabs.component)
        // Each tab has its own side component, so to display a component on *all*
        // tabs we have to duplicate it several times. (Is there a better way?)
        fun createTabSideComponent(): JComponent {
            val box = JBBox.createHorizontalBox()
            box.add(createThreadChooser())
            box.add(createHelpButton())
            box.add(JBBox.createHorizontalGlue())
            return box
        }

        // List view.
        listView = TracerTable(TracerTableModel())
        val listTab = TabInfo(JBScrollPane(listView))
            .setText("List")
            .setSideComponent(createTabSideComponent())
        tabs.addTab(listTab)

        // Tree view.
        treeView = TracerTree(TracerTreeModel())
        val treeTab = TabInfo(JBScrollPane(treeView))
            .setText("Tree")
            .setSideComponent(createTabSideComponent())
        tabs.addTab(treeTab)

        // Tracing overhead label.
        val overheadFont = JBFont
            .create(EditorUtil.getEditorFont())
            .deriveFont(UIUtil.getFontSize(UIUtil.FontSize.SMALL))
        tracingOverheadLabel = JBLabel()
        tracingOverheadLabel.font = overheadFont
        updateTracingOverhead(0L)

        // UI overhead label.
        uiOverheadLabel = JBLabel()
        uiOverheadLabel.font = overheadFont
        updateUiOverhead()

        // Bottom panel.
        add(JSeparator(HORIZONTAL))
        val bottomPanel = JBUI.Panels.simplePanel()
            .addToLeft(tracingOverheadLabel)
            .addToRight(uiOverheadLabel)
            .withBorder(JBUI.Borders.empty(3, 8, 6, 8))
        add(bottomPanel)

        // Schedule tree data updates.
        val refreshFuture = EdtExecutorService.getScheduledExecutorInstance()
            .scheduleWithFixedDelay(::updateCallTree, 0, REFRESH_DELAY_MS, MILLISECONDS)
        parentDisposable.attach { refreshFuture.cancel(false) }

        // Condense all components except the tab view.
        for (child in components) {
            if (child !== tabs) {
                child.maximumSize = Dimension(Integer.MAX_VALUE, 0)
            }
        }
    }

    fun showCommandLinePopup(message: String, type: MessageType) {
        getApplication().assertIsDispatchThread()
        PopupUtil.showBalloonForComponent(
            commandLine.component, message, type, true, parentDisposable
        )
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

    private fun updateCallTree() {
        // In order to measure tracer UI overhead we need to measure the time it
        // takes to update the tree model *and* the time it takes to run all the
        // invokeLater tasks generated by the update (but, exclude the time for
        // unrelated tasks that happen to be queued in-between). See
        // TreeTableModelAdapter.delayedFireTableDataChanged for an example of why
        // this is necessary. Note: hopefully the generated invokeLater tasks do not
        // themselves trigger additional nested invokeLater tasks...
        val start = System.nanoTime()
        var invokeLaterStart = 0L
        invokeLater {
            invokeLaterStart = System.nanoTime()
        }

        // Compute the new call tree.
        val treeSnapshot = when {
            showingEdtOnly -> CallTreeManager.getCallTreeSnapshotEdtOnly()
            else -> CallTreeManager.getCallTreeSnapshotAllThreadsMerged()
        }
        val stats = CallTreeUtil.computeFlatTracepointStats(treeSnapshot)
        listView.setTracepointStats(stats)
        treeView.setCallTree(treeSnapshot)

        // Estimate tracing overhead.
        val tracingOverhead = CallTreeUtil.estimateTracingOverhead(treeSnapshot)
        updateTracingOverhead(tracingOverhead)

        // Compute UI overhead.
        val elapsedDirectly = System.nanoTime() - start
        invokeLater {
            val elapsedIndirectly = System.nanoTime() - invokeLaterStart
            uiOverhead += elapsedDirectly + elapsedIndirectly
            updateUiOverhead()
        }
    }

    private fun updateTracingOverhead(tracingOverhead: Long) {
        val text = "Estimated tracing overhead: ${formatNsInMs(tracingOverhead)}"
        if (text != tracingOverheadLabel.text) {
            tracingOverheadLabel.text = text
        }
    }

    private fun updateUiOverhead() {
        val text = "Tracer UI overhead: ${formatNsInMs(uiOverhead)}"
        if (text != uiOverheadLabel.text) {
            uiOverheadLabel.text = text
        }
    }
}
