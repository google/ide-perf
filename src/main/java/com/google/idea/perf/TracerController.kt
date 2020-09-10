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

package com.google.idea.perf

import com.google.idea.perf.util.ExecutorWithExceptionLogging
import com.google.idea.perf.util.formatNsInMs
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager.getApplication
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.util.Computable
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.atomic.AtomicBoolean

abstract class TracerController(
    name: String,
    private val view: TracerViewBase
): Disposable {
    companion object {
        val LOG = Logger.getInstance(TracerController::class.java)
        const val REFRESH_DELAY_MS = 30L
    }

    // For simplicity we run all tasks on a single-thread executor.
    // Most data structures below are assumed to be accessed only from this executor.
    protected val executor = ExecutorWithExceptionLogging(name, 1)
    private val dataRefreshLoopStarted = AtomicBoolean()

    override fun dispose() {
        executor.shutdownNow()
    }

    fun startDataRefreshLoop() {
        check(dataRefreshLoopStarted.compareAndSet(false, true))
        executor.scheduleWithFixedDelay(
            this::dataRefreshLoop, 0, REFRESH_DELAY_MS, MILLISECONDS
        )
        onControllerInitialize()
    }

    /** Called after the controller has been initialized. */
    abstract fun onControllerInitialize()

    protected fun dataRefreshLoop() {
        val startTime = System.nanoTime()

        if (updateModel()) {
            updateUi()
        }

        val endTime = System.nanoTime()
        val elapsedNanos = endTime - startTime
        updateRefreshTimeUi(elapsedNanos)
    }

    protected abstract fun updateModel(): Boolean
    protected abstract fun updateUi()

    private fun updateRefreshTimeUi(refreshTime: Long) {
        getApplication().invokeAndWait {
            val timeText = formatNsInMs(refreshTime)
            view.refreshTimeLabel.text = "Refresh Time: %9s".format(timeText)
        }
    }

    abstract fun handleRawCommandFromEdt(text: String)

    protected fun <T> runWithProgress(action: (ProgressIndicator) -> T): T {
        val progress = MyProgressIndicator(view)
        val computable = Computable { action(progress) }
        return ProgressManager.getInstance().runProcess(computable, progress)
    }

    protected class MyProgressIndicator(private val view: TracerViewBase): ProgressIndicatorBase() {
        override fun onRunningChange(): Unit = onChange()

        override fun onProgressChange(): Unit = onChange()

        private fun onChange() {
            invokeLater {
                view.progressBar.isVisible = isRunning
                view.progressBar.isIndeterminate = isIndeterminate
                view.progressBar.minimum = 0
                view.progressBar.maximum = 100
                view.progressBar.value = (fraction * 100).toInt().coerceIn(0, 100)
            }
        }
    }
}
