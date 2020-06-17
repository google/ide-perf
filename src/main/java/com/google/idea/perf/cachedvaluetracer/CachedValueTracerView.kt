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

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.rd.attach
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import java.awt.Dimension
import javax.swing.Action
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.border.Border

class CachedValueTracerAction: DumbAwareAction() {
    private var currentTracer: CachedValueTracerDialog? = null

    override fun actionPerformed(e: AnActionEvent) {
        val tracer = currentTracer
        if (tracer != null) {
            check(!tracer.isDisposed)
            tracer.toFront()
        }
        else {
            val newTracer = CachedValueTracerDialog()
            currentTracer = newTracer
            newTracer.disposable.attach { currentTracer = null }
            newTracer.show()
        }
    }
}

class CachedValueTracerDialog: DialogWrapper(null, null, false, IdeModalityType.IDE, false) {
    init {
        title = "Cached Value Tracer"
        isModal = false
        init()
    }

    override fun createCenterPanel(): JComponent? = CachedValueTracerView(disposable)
    override fun createContentPaneBorder(): Border? = null
    override fun getDimensionServiceKey(): String? =
        "com.google.idea.perf.cachedvaluetracer.CachedValueTracer"
    override fun createActions(): Array<Action> = emptyArray()
}

class CachedValueTracerView(parentDisposable: Disposable): JBPanel<CachedValueTracerView>() {
    private val commandLine: JBTextField

    init {
        preferredSize = Dimension(500, 500)
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        // Command line.
        commandLine = JBTextField().apply {
            maximumSize = Dimension(Int.MAX_VALUE, minimumSize.height)
        }
        add(commandLine)

        // List view.
        add(JBScrollPane(JBTable()))
    }
}
