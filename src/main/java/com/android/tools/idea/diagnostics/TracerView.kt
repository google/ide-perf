package com.android.tools.idea.diagnostics

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import java.awt.Dimension
import java.lang.ref.WeakReference
import javax.swing.Action
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JProgressBar
import javax.swing.border.Border

/** Invoked by the user via the "Trace" action. */
class TracerAction : DumbAwareAction() {
    private var currentTracer: WeakReference<TracerDialog>? = null

    override fun actionPerformed(e: AnActionEvent) {
        val tracer = currentTracer?.get()
        if (tracer != null && !tracer.isDisposed) {
            tracer.toFront()
        } else {
            val newTracer = TracerDialog()
            newTracer.show()
            currentTracer = WeakReference(newTracer)
        }
    }
}

/** The dialog window that pops up via the "Trace" action. */
class TracerDialog : DialogWrapper(null, null, false, IdeModalityType.IDE, false) {
    init {
        title = "Tracer"
        isModal = false
        init()
    }

    override fun createCenterPanel(): JComponent = TracerView(disposable)
    override fun createContentPaneBorder(): Border? = null // No border.
    override fun getDimensionServiceKey(): String = "com.android.tools.idea.diagnostics.Tracer"
    override fun createActions(): Array<Action> = emptyArray()
}

/** The content filling the tracer dialog window. */
class TracerView(parentDisposable: Disposable) : JBPanel<TracerView>() {
    private val controller: TracerController = TracerController(this, parentDisposable)
    val progressBar: JProgressBar
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

        // Progress bar.
        progressBar = JProgressBar().apply {
            isIndeterminate = true
            isVisible = false
            maximumSize = Dimension(Integer.MAX_VALUE, minimumSize.height)
        }
        add(progressBar)

        // Call list.
        add(JBScrollPane(listView))

        // Start trace data collection.
        controller.startDataRefreshLoop()
    }
}
