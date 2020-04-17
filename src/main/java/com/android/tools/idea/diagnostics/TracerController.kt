package com.android.tools.idea.diagnostics

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.rd.attachChild
import com.intellij.psi.PsiElementFinder
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.TimeUnit
import kotlin.reflect.jvm.javaMethod

// Things to improve:
// - Audit overall overhead and memory usage.
// - Make sure CPU overhead is minimal when the diagnostics window is not showing.
// - Make sure UI updates finish before scheduling new ones.
// - Add some logging.
// - Detect repeated instrumentation requests with the same spec.
// - Add visual indicator for the 'paused' state.
// - More principled command parsing.
// - Add a proper progress indicator (subclass of ProgressIndicator) which displays text status.

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
        private const val SAMPLING_PERIOD_MS: Long = 30
        private val LOG = Logger.getInstance(TracerController::class.java)
    }

    init {
        CallTreeManager.swapBuffers() // Clear any call trees that have accumulated since the tracer was last closed.
        executor.scheduleWithFixedDelay(this::dataRefreshLoop, 0, SAMPLING_PERIOD_MS, TimeUnit.MILLISECONDS)
        parentDisposable.attachChild(this)
    }

    override fun dispose() {
        executor.shutdownNow()
    }

    private fun dataRefreshLoop() {
        val treeDeltas = CallTreeManager.swapBuffers()
        if (treeDeltas.isNotEmpty() && !paused) {
            treeDeltas.forEach(callTree::accumulate)
            updateUi()
        }
    }

    private fun updateUi() {
        val treeSnapshot = callTree.snapshot()

        val mergedByTracepoint = TreeAlgorithms.allNonRecursiveNodes(treeSnapshot)
            .filter { it.tracepoint != Tracepoint.ROOT }
            .groupBy(CallTree::tracepoint)
            .map { (_, group) -> group.singleOrNull() ?: MergedCallTree(group) }

        invokeLater {
            view.listView.setCallTrees(mergedByTracepoint)
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
