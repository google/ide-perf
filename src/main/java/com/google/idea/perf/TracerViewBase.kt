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

package com.google.idea.perf

import com.intellij.openapi.application.ApplicationManager.getApplication
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.util.PopupUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.textCompletion.TextFieldWithCompletion
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import javax.swing.JProgressBar

/** The content for filling the tracer dialog window. */
abstract class TracerView: JBPanel<TracerView>() {
    companion object {
        const val COMMAND_HISTORY_LIMIT = 20
    }

    abstract val controller: TracerController
    abstract val commandLine: TextFieldWithCompletion
    abstract val progressBar: JProgressBar
    abstract val refreshTimeLabel: JBLabel

    private val commandHistory = ArrayList<String>(COMMAND_HISTORY_LIMIT)
    private var commandHistoryIndex = -1

    fun showCommandBalloon(message: String, type: MessageType) {
        PopupUtil.showBalloonForComponent(commandLine, message, type, true, null)
    }

    fun initEvents(controller: TracerController) {
        val textComponent = commandLine.editor!!.contentComponent
        textComponent.addKeyListener(object: KeyListener {
            override fun keyTyped(e: KeyEvent) {}

            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    controller.handleRawCommandFromEdt(commandLine.text)

                    if (commandHistoryIndex != -1) {
                        if (commandHistory.size >= COMMAND_HISTORY_LIMIT) {
                            commandHistory.removeAt(0)
                        }
                        while (commandHistory.lastIndex > commandHistoryIndex) {
                            commandHistory.removeAt(commandHistory.lastIndex)
                        }
                    }

                    if (commandHistory.isEmpty() || commandHistory.last() != commandLine.text) {
                        commandHistory.add(commandLine.text)
                        commandHistoryIndex = commandHistory.lastIndex
                    }

                    commandLine.text = ""
                }
                else if (e.keyCode == KeyEvent.VK_UP) {
                    if (commandHistoryIndex != -1) {
                        if (commandHistoryIndex > 0 && commandLine.text.isNotEmpty()) {
                            commandHistoryIndex--
                        }
                        val command = commandHistory[commandHistoryIndex]
                        commandLine.text = command
                    }
                }
                else if (e.keyCode == KeyEvent.VK_DOWN) {
                    if (commandHistoryIndex != -1) {
                        if (commandHistoryIndex < commandHistory.lastIndex) {
                            commandHistoryIndex++
                        }
                        val command = commandHistory[commandHistoryIndex]
                        commandLine.text = command
                    }
                }
            }

            override fun keyReleased(e: KeyEvent) {}
        })
    }
}
