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

package com.android.tools.idea.diagnostics

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager.getApplication
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.rd.attachChild
import com.intellij.psi.PsiElementFinder
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.UIUtil
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import kotlin.reflect.jvm.javaMethod

// Things to improve:
// - Audit overall overhead and memory usage.
// - Make sure CPU overhead is minimal when the diagnostics window is not showing.
// - Add some logging.
// - Detect repeated instrumentation requests with the same spec.
// - More principled command parsing.
// - Add a proper progress indicator (subclass of ProgressIndicator) which displays text status.
// - Consider moving callTree to CallTreeManager so that it persists after the Tracer window closes.

class TracerController(
    private val view: TracerView, // Access from EDT only.
    parentDisposable: Disposable
): Disposable {
    // For simplicity we run all tasks on a single-thread executor.
    // Most data structures below are assumed to be accessed only from this executor.
    private val executor = AppExecutorUtil.createBoundedScheduledExecutorService("Tracer", 1)
    private var callTree = MutableCallTree(Tracepoint.ROOT)
    private var tasksInProgress = 0
    private val dataRefreshLoopStarted = AtomicBoolean()

    companion object {
        private val LOG = Logger.getInstance(TracerController::class.java)
        private const val REFRESH_DELAY_MS: Long = 30
    }

    init {
        CallTreeManager.collectAndReset() // Clear trees accumulated while the tracer was closed.
        parentDisposable.attachChild(this)
    }

    override fun dispose() {
        executor.shutdownNow()
    }

    fun startDataRefreshLoop() {
        check(dataRefreshLoopStarted.compareAndSet(false, true))
        executor.scheduleWithFixedDelay(this::dataRefreshLoop, 0, REFRESH_DELAY_MS, MILLISECONDS)
    }

    private fun dataRefreshLoop() {
        val treeDeltas = CallTreeManager.collectAndReset()
        if (treeDeltas.isNotEmpty()) {
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
        if (cmd.startsWith("save")) {
            // Special case: handle this command while we're still on the EDT.
            val path = cmd.substringAfter("save").trim()
            savePngFromEdt(path)
        } else {
            executor.execute { handleCommand(cmd) }
        }
    }

    private fun handleCommand(cmd: String) {
        if (cmd == "c" || cmd == "clear") {
            callTree.clear()
            updateUi()
        }
        else if (cmd == "reset") {
            callTree = MutableCallTree(Tracepoint.ROOT)
            updateUi()
        }
        else if (cmd == "trace psi finders" || cmd == "psi finders") {
            runWithProgressBar {
                val defaultProject = ProjectManager.getInstance().defaultProject
                val psiFinders = PsiElementFinder.EP.getExtensions(defaultProject)
                val base = PsiElementFinder::findClass.javaMethod!!
                for (psiFinder in psiFinders) {
                    val method = psiFinder.javaClass.getMethod(base.name, *base.parameterTypes)
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

    /** Saves a png of the current view. */
    private fun savePngFromEdt(path: String) {
        if (!path.endsWith(".png")) {
            LOG.warn("Destination file must be a .png file; instead got $path")
            return
        }
        val file = File(path)
        if (!file.isAbsolute) {
            LOG.warn("Destination file must be specified with an absolute path; instead got $path")
            return
        }
        val img = UIUtil.createImage(view, view.width, view.height, BufferedImage.TYPE_INT_RGB)
        view.paintAll(img.createGraphics())
        executor.execute {
            runWithProgressBar {
                try {
                    ImageIO.write(img, "png", file)
                } catch (e: IOException) {
                    LOG.warn("Failed to write png to $path", e)
                }
            }
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
