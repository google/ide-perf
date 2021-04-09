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

package com.google.idea.perf.tracer.ui

import com.google.idea.perf.tracer.Tracepoint
import com.google.idea.perf.tracer.TracepointStats
import com.google.idea.perf.util.formatNsInBestUnit
import com.google.idea.perf.util.formatNum
import com.intellij.openapi.rd.attach
import javax.swing.JTextArea

/** Manages the tracepoint details shown via [TracepointDetailsDialog]. */
class TracepointDetailsManager(private val table: TracerTable) {
    private var currentTracepoint: Tracepoint? = null
    private var currentDialog: TracepointDetailsDialog? = null

    fun showTracepointDetails(data: TracepointStats) {
        currentTracepoint = data.tracepoint
        val detailText = buildDetailString(data)
        val dialog = currentDialog
        if (dialog != null) {
            dialog.textArea.text = detailText
        } else {
            val newDialog = TracepointDetailsDialog(table, detailText)
            currentDialog = newDialog
            newDialog.disposable.attach { currentDialog = null }
            newDialog.show()
        }
    }

    fun updateTracepointDetails(allData: List<TracepointStats>) {
        val dialog = currentDialog ?: return
        val tracepoint = currentTracepoint ?: return
        val textArea = dialog.textArea
        val data = allData.firstOrNull { it.tracepoint == tracepoint }
        if (data == null) {
            val detailText = "${tracepoint.displayName}\n\n(no data)"
            if (detailText != textArea.text) {
                textArea.text = detailText
            }
        } else {
            val detailText = buildDetailString(data)
            if (detailText != textArea.text) {
                setTextPreservingSelection(textArea, detailText)
            }
        }
    }

    private fun setTextPreservingSelection(textArea: JTextArea, text: String) {
        val caret = textArea.caret
        val dot = caret.dot
        val mark = caret.mark

        textArea.text = text // We hope the text did not change very much.

        caret.dot = mark
        caret.moveDot(dot)
    }

    private fun buildDetailString(data: TracepointStats): String {
        return buildString {
            appendLine(data.tracepoint.displayName)

            val callCount = formatNum(data.callCount)
            val wallTime = formatNsInBestUnit(data.wallTime)
            appendLine()
            appendLine("Call count: $callCount")
            appendLine("Total wall time: $wallTime")

            if (data.callCount > 0) {
                val maxWallTime = formatNsInBestUnit(data.maxWallTime)
                val avgWallTime = formatNsInBestUnit(data.wallTime / data.callCount)
                appendLine()
                appendLine("Average wall time: $avgWallTime")
                appendLine("Max wall time: $maxWallTime")
            }

            appendLine()
            append(data.tracepoint.detailedName)
        }
    }
}
