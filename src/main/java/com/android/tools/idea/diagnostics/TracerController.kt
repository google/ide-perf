package com.android.tools.idea.diagnostics

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager.getApplication
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.rd.attachChild
import com.intellij.psi.PsiElementFinder
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.TimeUnit.MILLISECONDS
import kotlin.reflect.jvm.javaMethod

// Things to improve:
// - Audit overall overhead and memory usage.
// - Make sure CPU overhead is minimal when the diagnostics window is not showing.
// - Add some logging.
// - Detect repeated instrumentation requests with the same spec.
// - Add visual indicator for the 'paused' state.
// - More principled command parsing.
// - Add a proper progress indicator (subclass of ProgressIndicator) which displays text status.
// - Consider moving callTree to CallTreeManager so that it persists after the Tracer window closes.

class TracerController(
    private val view: TracerView, // Access from EDT only.
    parentDisposable: Disposable
): Disposable {
    // For simplicity we run all tasks on a single-thread executor.
    // The data structures below are assumed to be accessed only from this executor.
    private val executor = AppExecutorUtil.createBoundedScheduledExecutorService("Tracer", 1)
    private var callTree = MutableCallTree(Tracepoint.ROOT)
    private var paused = false
    private var tasksInProgress = 0

    companion object {
        private val LOG = Logger.getInstance(TracerController::class.java)
        private const val REFRESH_DELAY_MS: Long = 30
    }

    init {
        CallTreeManager.collectAndReset() // Clear any call trees that have accumulated while the tracer was closed.
        // TODO: Do not schedule anything until this object (and the TracerView) is fully constructed.
        executor.scheduleWithFixedDelay(this::dataRefreshLoop, 0, REFRESH_DELAY_MS, MILLISECONDS)
        parentDisposable.attachChild(this)
    }

    override fun dispose() {
        executor.shutdownNow()
    }

    private fun dataRefreshLoop() {
        val treeDeltas = CallTreeManager.collectAndReset()
        if (treeDeltas.isNotEmpty() && !paused) {
            treeDeltas.forEach(callTree::accumulate)
            updateUi()
        }
    }

    /** Refreshes the UI with the current state of [callTree]. */
    private fun updateUi() {
        val allStats = TreeAlgorithms.computeFlatTracepointStats(callTree)
        val visibleStats = allStats.filter { it.tracepoint != Tracepoint.ROOT }
        // We use invokeAndWait to ensure proper backpressure for the data refresh loop.
        getApplication().invokeAndWait {
            view.listView.setTracepointStats(visibleStats)
        }
    }

    fun handleRawCommandFromEdt(rawCmd: String) {
        if (rawCmd.isBlank()) return
        val cmd = rawCmd.trim()
        executor.execute { handleCommand(cmd) }
    }

    private fun handleCommand(cmd: String) {
        if (cmd == "p" || cmd == "pause") {
            paused = true
        }
        else if (cmd == "r" || cmd == "resume") {
            paused = false
        }
        else if (cmd == "c" || cmd == "clear") {
            callTree.clear()
            updateUi()
        }
        else if (cmd == "reset") {
            callTree = MutableCallTree(Tracepoint.ROOT)
            updateUi()
        }
        else if (cmd == "trace psi finders" || cmd == "psi finders") {
            runWithProgressBar {
                val psiFinders = PsiElementFinder.EP.getExtensions(ProjectManager.getInstance().defaultProject)
                val baseMethod = PsiElementFinder::findClass.javaMethod!!
                for (psiFinder in psiFinders) {
                    val method = psiFinder.javaClass.getMethod(baseMethod.name, *baseMethod.parameterTypes)
                    InstrumentationController.instrumentMethod(method)
                }
            }
        }
        else if (cmd.startsWith("trace")) {
            val spec = cmd.substringAfter("trace").trim()
            val split = spec.split('#')
            if (split.size != 2) {
                LOG.warn("Invalid trace request format; expected com.example.Class#method")
                return
            }
            val (className, methodName) = split
            runWithProgressBar {
                InstrumentationController.instrumentMethod(className, methodName)
            }
        }
        else if (cmd.contains('#')) {
            // Implicit trace command.
            handleCommand("trace $cmd")
        }
        else {
            LOG.warn("Unknown command: $cmd")
        }
    }

    private fun <T> runWithProgressBar(action: () -> T) {
        if (++tasksInProgress == 1) {
            invokeLater { view.progressBar.isVisible = true }
        }
        try {
            action()
        } finally {
            if (--tasksInProgress == 0) {
                invokeLater { view.progressBar.isVisible = false }
            }
        }
    }
}
