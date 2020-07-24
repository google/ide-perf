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

import com.google.idea.perf.TracerController
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager.getApplication
import com.intellij.openapi.rd.attachChild
import com.intellij.openapi.ui.MessageType

class VfsTracerController(
    private val view: VfsTracerView,
    parentDisposable: Disposable
): TracerController("VFS Tracer", view) {
    private var accumulatedStats = MutableVirtualFileTree.createRoot()
    private var collectedStats = VirtualFileTree.EMPTY

    init {
        parentDisposable.attachChild(this)
    }

    override fun onControllerInitialize() {}

    override fun updateModel(): Boolean {
        collectedStats = VirtualFileTracer.collectAndReset()
        return collectedStats.children.isNotEmpty()
    }

    override fun updateUi() {
        accumulatedStats.accumulate(collectedStats)
        val listStats = accumulatedStats.flattenedList()

        getApplication().invokeAndWait {
            view.listView.setStats(listStats)
            view.treeView.setStats(accumulatedStats)
        }
    }

    override fun handleRawCommandFromEdt(text: String) {
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
