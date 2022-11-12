/*
 * Copyright 2021 Google LLC
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

package com.google.idea.perf.tracer.ui

import com.google.idea.perf.tracer.TraceOption
import com.google.idea.perf.tracer.TraceTarget
import com.intellij.ui.components.JBTextArea

/** Displays a list of trace/untrace commands as plain text. */
class TracerConfigTab : JBTextArea() {

    private var previousCommandsList: List<TraceTarget.Method> = emptyList()

    fun setTracingConfig(newStats: List<TraceTarget.Method>) {
        if (previousCommandsList == newStats) {
            return
        }
        previousCommandsList = newStats
        document.remove(0, document.length)
        val tmp = newStats.joinToString(
            separator = "\n",
            transform = TracerConfigTab::methodToString
        )
        append(tmp)
    }

    companion object {
        private fun methodToString(method: TraceTarget.Method): String {
            val option = when (method.traceOption) {
                TraceOption.COUNT_AND_WALL_TIME -> "trace"
                TraceOption.COUNT_ONLY -> "trace count"
                TraceOption.UNTRACE -> "untrace"
            }
            if (method.methodName == "*") {
                return "$option ${method.className}"
            } else {
                return "$option ${method.className}::${method.methodName}"
            }
        }
    }
}
