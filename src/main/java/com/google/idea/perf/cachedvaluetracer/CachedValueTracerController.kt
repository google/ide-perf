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

package com.google.idea.perf.cachedvaluetracer

import com.google.idea.perf.CommandCompletionProvider
import com.google.idea.perf.TracerController
import com.google.idea.perf.fuzzyMatch
import com.google.idea.perf.methodtracer.AgentLoader
import com.google.idea.perf.util.sumByLong
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager.getApplication
import com.intellij.openapi.rd.attachChild
import com.intellij.psi.util.CachedValueProfiler
import java.util.*

class CachedValueTracerController(
    private val view: CachedValueTracerView,
    parentDisposable: Disposable
): TracerController("Cached Value Tracer", view) {
    private val filteredStats = ArrayList<StackTraceElement>()
    private var groupMode = GroupOption.CLASS

    private val predictor = CachedValueTracerCommandPredictor()
    val autocomplete = CommandCompletionProvider(predictor)

    init {
        parentDisposable.attachChild(this)
        reloadAutocompleteClasses()
        CachedValueProfiler.getInstance().isEnabled = true
    }

    override fun dispose() {
        super.dispose()
        CachedValueProfiler.getInstance().isEnabled = false
    }

    override fun updateModel(): Boolean {
        return true
    }

    override fun updateUi() {
        val stats = getStats()

        getApplication().invokeAndWait {
            view.listView.setStats(stats)
        }
    }

    override fun handleRawCommandFromEdt(text: String) {
        executor.execute { handleCommand(text) }
    }

    private fun handleCommand(text: String) {
        when (val command = parseTracerCommand(text)) {
            is TracerCommand.Clear -> {
                filteredStats.clear()
                updateUi()
            }
            is TracerCommand.Reset -> {
                CachedValueProfiler.getInstance().isEnabled = false
                CachedValueProfiler.getInstance().isEnabled = true
                filteredStats.clear()
                updateUi()
            }
            is TracerCommand.Filter -> {
                if (command.pattern != null) {
                    filterStats(command.pattern)
                }
                updateUi()
            }
            is TracerCommand.GroupBy -> {
                if (command.groupOption != null) {
                    groupMode = command.groupOption
                }
                updateUi()
            }
            else -> {
                LOG.warn("Unknown command: $text")
            }
        }
    }

    private fun getStats(): List<CachedValueStats> {
        val snapshot = CachedValueProfiler.getInstance().storageSnapshot

        if (filteredStats.isNotEmpty()) {
            val iterator = snapshot.entrySet().iterator()
            while (iterator.hasNext()) {
                val (key, _) = iterator.next()
                if (!filteredStats.contains(key)) {
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
                CachedValueStats(
                    it.key,
                    values.sumByLong { it.lifetime },
                    values.sumByLong { it.useCount },
                    values.size.toLong()
                )
            }
    }

    private fun filterStats(pattern: String) {
        if (filteredStats.isEmpty()) {
            val snapshot = CachedValueProfiler.getInstance().storageSnapshot

            when (groupMode) {
                GroupOption.CLASS -> {
                    filteredStats.addAll(snapshot.keySet().filter {
                        fuzzyMatch(it.className, pattern) != null
                    })
                }
                GroupOption.STACK_TRACE -> {
                    filteredStats.addAll(snapshot.keySet().filter {
                        fuzzyMatch(getStackTraceName(it), pattern) != null
                    })
                }
            }
        }
        else {
            when (groupMode) {
                GroupOption.CLASS -> {
                    filteredStats.removeIf { fuzzyMatch(it.className, pattern) == null }
                }
                GroupOption.STACK_TRACE -> {
                    filteredStats.removeIf {
                        fuzzyMatch(getStackTraceName(it), pattern) == null
                    }
                }
            }
        }
    }

    private fun getStackTraceName(element: StackTraceElement): String =
        "${element.className}#${element.methodName}(${element.lineNumber})"

    private fun reloadAutocompleteClasses() {
        val instrumentation = AgentLoader.instrumentation

        if (instrumentation != null) {
            predictor.setClasses(instrumentation.allLoadedClasses.filter {
                it.canonicalName != null
            }.sortedBy { it.canonicalName })
        }
        else {
            LOG.warn("Cannot reload classes.")
        }
    }
}
