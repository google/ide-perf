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

import com.google.idea.perf.AgentLoader
import com.google.idea.perf.CommandCompletionProvider
import com.google.idea.perf.TracerController
import com.google.idea.perf.util.fuzzyMatch
import com.google.idea.perf.util.shouldHideClassFromCompletionResults
import com.google.idea.perf.util.sumByLong
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager.getApplication
import com.intellij.openapi.util.Disposer
import com.intellij.psi.util.CachedValueProfiler
import java.util.*
import java.util.concurrent.TimeUnit.SECONDS

class CachedValueTracerController(
    private val view: CachedValueTracerView,
    parentDisposable: Disposable
): TracerController("Cached Value Tracer", view) {
    companion object {
        const val AUTOCOMPLETE_RELOAD_INTERVAL = 120L
    }

    private val currentStats = mutableMapOf<String, MutableCachedValueStats>()
    private val filteredStatKeys = ArrayList<StackTraceElement>()
    private var groupMode = GroupOption.CLASS

    private val predictor = CachedValueTracerCommandPredictor()
    val autocomplete = CommandCompletionProvider(predictor)

    init {
        Disposer.register(parentDisposable, this)
        CachedValueProfiler.getInstance().isEnabled = true
    }

    override fun dispose() {
        super.dispose()
        CachedValueProfiler.getInstance().isEnabled = false
    }

    override fun onControllerInitialize() {
        executor.scheduleWithFixedDelay(
            this::reloadAutocompleteClasses, 0L, AUTOCOMPLETE_RELOAD_INTERVAL, SECONDS
        )
    }

    override fun updateModel(): Boolean {
        return true
    }

    override fun updateUi() {
        val newStats = getNewStats()
        for (stat in newStats) {
            currentStats[stat.name] = stat
        }

        getApplication().invokeAndWait {
            view.listView.setStats(currentStats.values.toList())
        }
    }

    override fun handleRawCommandFromEdt(text: String) {
        executor.execute { handleCommand(text) }
    }

    private fun handleCommand(text: String) {
        when (val command = parseCachedValueTracerCommand(text)) {
            is CachedValueTracerCommand.Clear -> {
                CachedValueProfiler.getInstance().isEnabled = false
                CachedValueProfiler.getInstance().isEnabled = true
                for ((_, stat) in currentStats) {
                    stat.lifetime = 0L
                    stat.hits = 0L
                    stat.misses = 0L
                }
                updateUi()
            }
            is CachedValueTracerCommand.Reset -> {
                CachedValueProfiler.getInstance().isEnabled = false
                CachedValueProfiler.getInstance().isEnabled = true
                currentStats.clear()
                filteredStatKeys.clear()
                updateUi()
            }
            is CachedValueTracerCommand.Filter -> {
                if (command.pattern != null) {
                    filterStats(command.pattern)
                    currentStats.clear()
                    updateUi()
                }
            }
            is CachedValueTracerCommand.ClearFilters -> {
                filteredStatKeys.clear()
                currentStats.clear()
                updateUi()
            }
            is CachedValueTracerCommand.GroupBy -> {
                if (command.groupOption != null) {
                    groupMode = command.groupOption
                    currentStats.clear()
                    updateUi()
                }
            }
            else -> {
                LOG.warn("Unknown command: $text")
            }
        }
    }

    private fun getNewStats(): List<MutableCachedValueStats> {
        val snapshot = CachedValueProfiler.getInstance().storageSnapshot

        if (filteredStatKeys.isNotEmpty()) {
            val iterator = snapshot.entrySet().iterator()
            while (iterator.hasNext()) {
                val (key, _) = iterator.next()
                if (!filteredStatKeys.contains(key)) {
                    iterator.remove()
                }
            }
        }

        val groupedSnapshot = if (groupMode == GroupOption.CLASS) {
            snapshot.entrySet().groupBy({ it.key.className }, { it.value })
        }
        else {
            snapshot.entrySet().groupBy({ getStackTraceName(it.key) }, { it.value })
        }

        return groupedSnapshot
            .map { it ->
                val values = it.value.flatten()
                MutableCachedValueStats(
                    it.key,
                    values.sumByLong { it.lifetime },
                    values.sumByLong { it.useCount },
                    values.size.toLong()
                )
            }
    }

    private fun filterStats(pattern: String) {
        if (filteredStatKeys.isEmpty()) {
            val snapshot = CachedValueProfiler.getInstance().storageSnapshot

            when (groupMode) {
                GroupOption.CLASS -> {
                    filteredStatKeys.addAll(snapshot.keySet().filter {
                        fuzzyMatch(it.className, pattern) != null
                    })
                }
                GroupOption.STACK_TRACE -> {
                    filteredStatKeys.addAll(snapshot.keySet().filter {
                        fuzzyMatch(getStackTraceName(it), pattern) != null
                    })
                }
            }
        }
        else {
            when (groupMode) {
                GroupOption.CLASS -> {
                    filteredStatKeys.removeIf { fuzzyMatch(it.className, pattern) == null }
                }
                GroupOption.STACK_TRACE -> {
                    filteredStatKeys.removeIf {
                        fuzzyMatch(getStackTraceName(it), pattern) == null
                    }
                }
            }
        }
    }

    private fun getStackTraceName(element: StackTraceElement): String =
        "${element.className}#${element.methodName}(${element.lineNumber})"

    private fun reloadAutocompleteClasses() {
        val instrumentation = AgentLoader.instrumentation ?: return
        val allClasses = instrumentation.allLoadedClasses
        val visibleClasses = allClasses.filterNot(::shouldHideClassFromCompletionResults)
        predictor.setClasses(visibleClasses)
    }
}
