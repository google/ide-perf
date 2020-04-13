package com.android.tools.idea.diagnostics

import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.project.ProjectManager
import com.intellij.psi.PsiElementFinder
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.TimeUnit

// Things to improve:
// - Make sure CPU overhead is minimal when the diagnostics window is not showing.
// - Make sure UI updates finish before scheduling new ones.
// - Add some logging.
// - Detect repeated instrumentation requests with the same spec.
// - Add visual indicator for the 'paused' state.
// - More principled command parsing.
// - Add a proper progress indicator (subclass of ProgressIndicator) which displays text status.

private const val SAMPLING_PERIOD_MS: Long = 30

class DiagnosticsController(
    private val view: DiagnosticsView // Access from EDT.
) {
    // For simplicity we run all tasks on a single-threaded executor.
    private val executor = AppExecutorUtil.createBoundedScheduledExecutorService("DiagnosticsController", 1)
    private var callTree = MutableCallTree(Tracepoint.ROOT) // Access from [executor].
    private var paused = false // Access from [executor].
    private var tasksInProgress = 0

    init {
        executor.scheduleWithFixedDelay(this::dataRefreshLoop, 0, SAMPLING_PERIOD_MS, TimeUnit.MILLISECONDS)
        // TODO: Call executor.shutdownNow() at some point.
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

        val mergedByTracepoint = treeSnapshot.children.values.asSequence()
            .flatMap(CallTree::allNodesInSubtree)
            .groupBy(CallTree::tracepoint)
            .map { (_, group) -> group.singleOrNull() ?: MergedCallTree(group) }

        invokeLater {
            view.listView.setCallTrees(mergedByTracepoint)
        }
    }

    // Invoked by the view when the user enters a command.
    fun handleRawCommandFromEdt(rawCmd: String) {
        if (rawCmd.isBlank()) return
        val cmd = rawCmd.trim()
        executor.execute { handleCommandInBackground(cmd) }
    }

    private fun handleCommandInBackground(cmd: String) {
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
                for (psiFinder in psiFinders) {
                    handleCommandInBackground("trace ${psiFinder.javaClass.name}#findClass")
                }
            }
        }
        else if (cmd.startsWith("trace")) {
            // Trace the given method via bytecode instrumentation.
            val spec = cmd.substringAfter("trace").trim()
            val (className, methodName) = spec.split('#').takeIf { it.size == 2 } ?: return // TODO
            val classShortName = className.substringAfterLast('.')
            val displayName = "$classShortName.$methodName()"
            val tracepoint = Tracepoint(displayName)
            runWithProgressBar {
                InstrumentationController.instrumentMethod(className, methodName, tracepoint)
            }
        }
        else if (cmd.contains('#')) {
            // Implicit trace command.
            handleCommandInBackground("trace $cmd")
        }
        else {
            println("Unknown command: $cmd") // TODO
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
