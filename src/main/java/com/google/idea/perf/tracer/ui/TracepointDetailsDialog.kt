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

import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBEmptyBorder
import java.awt.Component
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.text.DefaultCaret

/**
 * A popup showing details for a specific tracepoint.
 * Manged by [TracepointDetailsManager].
 */
class TracepointDetailsDialog(parent: Component, text: String) : DialogWrapper(parent, false) {
    val textArea: JBTextArea

    init {
        title = "Tracepoint Details"
        isModal = false

        // Text area.
        textArea = JBTextArea(text)
        textArea.font = EditorUtil.getEditorFont()
        textArea.isEditable = false
        textArea.border = JBEmptyBorder(5)
        val caret = textArea.caret
        if (caret is DefaultCaret) {
            // Disable caret movement so that changing the text does not affect scroll position.
            caret.updatePolicy = DefaultCaret.NEVER_UPDATE
        }

        init()
    }

    override fun createCenterPanel(): JComponent = JBScrollPane(textArea)
    override fun getDimensionServiceKey(): String = "${javaClass.packageName}.TracepointDetails"
    override fun createActions(): Array<Action> = arrayOf(okAction)
}
