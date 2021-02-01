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

import com.google.idea.perf.tracer.TracerCompletionProvider
import com.google.idea.perf.tracer.TracerController
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.ui.EditorTextField
import com.intellij.util.textCompletion.TextFieldWithCompletion
import java.awt.event.ActionListener
import javax.swing.ComboBoxEditor
import javax.swing.DefaultComboBoxModel

/**
 * This is the command line at the top of the tracer panel.
 * It delegates commands to [TracerController], keeps track of command history, etc.
 */
class TracerCommandLine(project: Project, private val tracerController: TracerController) {
    private val textField: EditorTextField
    private val comboBox = ComboBox<String>()
    private var history = emptyArray<String>()
    val component get() = comboBox

    companion object {
        private const val HISTORY_SIZE = 20
        private const val HISTORY_KEY = "com.google.idea.perf.tracer.command.history"
    }

    init {
        // Text field with completion.
        val completionProvider = TracerCompletionProvider()
        textField = TextFieldWithCompletion(project, completionProvider, "", true, true, false)
        textField.font = EditorUtil.getEditorFont()
        textField.setPreferredWidth(0) // Otherwise the minimum width is determined by the text.
        TracerUIUtil.reinstallCompletionProviderAsNeeded(textField, completionProvider)
        TracerUIUtil.addEnterKeyAction(textField, ::handleEnterKey)

        // Combo box.
        comboBox.editor = MyComboBoxEditor(textField)
        comboBox.font = EditorUtil.getEditorFont()
        comboBox.isEditable = true
        comboBox.prototypeDisplayValue = ""

        // Initial command history.
        val restoredHistory = PropertiesComponent.getInstance().getValues(HISTORY_KEY)
        if (restoredHistory != null) {
            setHistory(restoredHistory)
        } else {
            // Just add some sample tracing commands.
            setHistory(
                arrayOf(
                    "trace com.intellij.openapi.progress.ProgressManager#checkCanceled",
                    "trace com.intellij.openapi.progress.ProgressManager#*",
                    "trace com.intellij.openapi.progress.*",
                )
            )
        }
    }

    private fun handleEnterKey() {
        if (comboBox.isPopupVisible) {
            comboBox.hidePopup()
        } else {
            val text = textField.text
            tracerController.handleRawCommandFromEdt(text)
            if (text.isNotBlank()) setHistory(arrayOf(text, *history))
            textField.text = ""
        }
    }

    private fun setHistory(items: Array<String>) {
        history = items.toSet().take(HISTORY_SIZE).toTypedArray()

        val model = DefaultComboBoxModel(history)
        model.selectedItem = null // Otherwise the first item is selected automatically.
        comboBox.model = model

        PropertiesComponent.getInstance().setValues(HISTORY_KEY, history)
    }

    private class MyComboBoxEditor(private val textField: EditorTextField) : ComboBoxEditor {

        override fun selectAll() {
            // Inspired by EditorComboBoxEditor.
            textField.selectAll()
            IdeFocusManager.getGlobalInstance().doWhenFocusSettlesDown {
                IdeFocusManager.getGlobalInstance().requestFocus(textField, true)
            }
        }

        override fun getEditorComponent(): EditorTextField = textField

        override fun getItem() = textField.text

        override fun setItem(item: Any?) {
            if (item is String) {
                textField.text = item
                textField.setCaretPosition(textField.document.textLength)
            }
        }

        override fun addActionListener(l: ActionListener) {}

        override fun removeActionListener(l: ActionListener) {}
    }
}