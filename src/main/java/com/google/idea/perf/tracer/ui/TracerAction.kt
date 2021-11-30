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

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.ProjectManager
import com.google.idea.perf.util.onDispose

/** Invoked by the user via the "Trace" action. Pulls up the [TracerDialog]. */
class TracerAction : DumbAwareAction() {
    private var currentTracer: TracerDialog? = null

    override fun actionPerformed(e: AnActionEvent) {
        val tracer = currentTracer
        if (tracer != null) {
            check(!tracer.isDisposed)
            tracer.toFront()
        }
        else {
            val project = e.project ?: ProjectManager.getInstance().defaultProject
            val newTracer = TracerDialog(project)
            currentTracer = newTracer
            newTracer.disposable.onDispose { currentTracer = null }
            newTracer.show()
        }
    }
}
