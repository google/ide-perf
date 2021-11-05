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

package com.google.idea.perf.vfstracer

import com.google.idea.perf.util.ExecutorWithExceptionLogging
import com.google.idea.perf.util.formatNsInMs
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager.getApplication
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.util.Disposer
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.measureNanoTime

class VfsTracerController(
    private val view: VfsTracerView, // Access only on EDT.
    parentDisposable: Disposable
) : Disposable {
    companion object {
        private val LOG = Logger.getInstance(VfsTracerController::class.java)
        private const val REFRESH_DELAY_MS = 100L
    }

    // For simplicity we run all tasks on a single-thread executor.
    // The data structures below are assumed to be accessed only from this executor.
    private val executor = ExecutorWithExceptionLogging("VFS Tracer", 1)
    private val dataRefreshLoopStarted = AtomicBoolean()
    private var accumulatedStats = MutableVirtualFileTree.createRoot()

    init {
        Disposer.register(parentDisposable, this)
    }

    override fun dispose() {
        executor.shutdownNow()
    }

    fun startDataRefreshLoop() {
        check(dataRefreshLoopStarted.compareAndSet(false, true))
        val refreshLoop = { updateRefreshTimeUi(measureNanoTime(::updateUi)) }
        executor.scheduleWithFixedDelay(refreshLoop, 0, REFRESH_DELAY_MS, MILLISECONDS)
    }

    private fun updateUi() {
        val collectedStats = VirtualFileTracer.collectAndReset()
        accumulatedStats.accumulate(collectedStats)
        val listStats = accumulatedStats.flattenedList()

        getApplication().invokeAndWait {
            view.listView.setStats(listStats)
            view.treeView.setStats(accumulatedStats)
        }
    }

    private fun updateRefreshTimeUi(refreshTime: Long) {
        getApplication().invokeAndWait {
            val timeText = formatNsInMs(refreshTime)
            view.refreshTimeLabel.text = "Refresh Time: %9s".format(timeText)
        }
    }

    fun handleRawCommandFromEdt(text: String) {
        executor.execute { handleCommand(text.trim()) }
    }

    private fun handleCommand(command: String) {
        when (command) {
            "start" -> {
                val errors = VirtualFileTracer.startVfsTracing()
                if (errors.isNotEmpty()) {
                    val errorString = errors.joinToString("\n\n")
                    LOG.error(errorString)
                    view.showCommandBalloon(errorString, MessageType.ERROR)
                }
            }
            "stop" -> VirtualFileTracer.stopVfsTracing()
            "clear" -> {
                accumulatedStats.clear()
                updateUi()
            }
            "reset" -> {
                accumulatedStats = MutableVirtualFileTree.createRoot()
                updateUi()
            }
        }
    }
}
