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

package com.google.idea.perf.cvtracer

import com.google.idea.perf.util.ExecutorWithExceptionLogging
import com.google.idea.perf.util.GlobMatcher
import com.google.idea.perf.util.formatNsInMs
import com.google.idea.perf.util.sumByLong
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager.getApplication
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.intellij.util.text.Matcher
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.measureNanoTime

class CachedValueTracerController(
    private val view: CachedValueTracerView,
    parentDisposable: Disposable
) : Disposable {
    companion object {
        private val LOG = Logger.getInstance(CachedValueTracerController::class.java)
        private const val REFRESH_DELAY_MS = 100L
    }

    // For simplicity we run all tasks on a single-thread executor.
    // Most data structures below are assumed to be accessed only from this executor.
    private val executor = ExecutorWithExceptionLogging("CachedValue Tracer", 1)
    private val dataRefreshLoopStarted = AtomicBoolean()
    private var filter: Matcher = GlobMatcher.create("*")
    private var groupMode = GroupOption.CLASS
    private val eventConsumer = CachedValueEventConsumer()

    init {
        Disposer.register(parentDisposable, this)
        eventConsumer.install()
    }

    override fun dispose() {
        executor.shutdownNow()
        eventConsumer.uninstall()
    }

    fun startDataRefreshLoop() {
        check(dataRefreshLoopStarted.compareAndSet(false, true))
        val refreshLoop = { updateRefreshTimeUi(measureNanoTime(::updateUi)) }
        executor.scheduleWithFixedDelay(refreshLoop, 0L, REFRESH_DELAY_MS, MILLISECONDS)
    }

    private fun updateUi() {
        val newStats = getNewStats()
        getApplication().invokeAndWait {
            view.listView.setStats(newStats)
        }
    }

    private fun updateRefreshTimeUi(refreshTime: Long) {
        getApplication().invokeAndWait {
            val timeText = formatNsInMs(refreshTime)
            view.refreshTimeLabel.text = "Refresh Time: %9s".format(timeText)
        }
    }

    fun handleRawCommandFromEdt(text: String) {
        executor.execute { handleCommand(text) }
    }

    private fun handleCommand(text: String) {
        when (val command = parseCachedValueTracerCommand(text)) {
            is CachedValueTracerCommand.Clear -> {
                eventConsumer.clear()
                updateUi()
            }
            is CachedValueTracerCommand.Reset -> {
                eventConsumer.clear()
                filter = GlobMatcher.create("*")
                updateUi()
            }
            is CachedValueTracerCommand.Filter -> {
                val pattern = command.pattern
                if (pattern != null) {
                    filter = GlobMatcher.create("*$pattern*")
                    updateUi()
                }
            }
            is CachedValueTracerCommand.ClearFilters -> {
                filter = GlobMatcher.create("*")
                updateUi()
            }
            is CachedValueTracerCommand.GroupBy -> {
                if (command.groupOption != null) {
                    groupMode = command.groupOption
                    updateUi()
                }
            }
            else -> {
                LOG.warn("Unknown command: $text")
            }
        }
    }

    private fun getNewStats(): List<MutableCachedValueStats> {
        return eventConsumer.getSnapshot()
            .filter { filter.matches(it.location.className) }
            .groupBy { describeStackFrame(it.location) }
            .map { (description, stats) ->
                MutableCachedValueStats(
                    description,
                    computeTimeNs = stats.sumByLong { it.computeTimeNs },
                    hits = stats.sumByLong { it.hits },
                    misses = stats.sumByLong { it.computeCount }
                )
            }
    }

    private fun describeStackFrame(f: StackTraceElement): String {
        return when (groupMode) {
            GroupOption.CLASS -> f.className
            GroupOption.STACK_TRACE -> "${f.className}#${f.methodName}(${f.lineNumber})"
        }
    }
}
