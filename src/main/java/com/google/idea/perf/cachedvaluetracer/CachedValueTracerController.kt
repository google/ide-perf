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

import com.google.idea.perf.TracerController
import com.google.idea.perf.util.sumByLong
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager.getApplication
import com.intellij.openapi.rd.attachChild
import com.intellij.openapi.ui.Messages
import com.intellij.psi.util.CachedValueProfiler

class CachedValueTracerController(
    private val view: CachedValueTracerView,
    parentDisposable: Disposable
): TracerController("Cached Value Tracer", view) {
    init {
        parentDisposable.attachChild(this)
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
        Messages.showMessageDialog(
            view, text, "Cached Value Tracer", Messages.getInformationIcon()
        )
    }

    private fun getStats(): List<CachedValueStats> {
        val snapshot = CachedValueProfiler.getInstance().storageSnapshot
        return snapshot.entrySet()
            .groupBy({ it.key.className }, { it.value })
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
}
