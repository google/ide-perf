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

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.BulkAwareDocumentListener
import com.intellij.openapi.project.ProjectManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.ui.EditorTextField
import com.intellij.util.textCompletion.TextCompletionProvider
import com.intellij.util.textCompletion.TextCompletionUtil
import com.intellij.util.textCompletion.TextFieldWithCompletion
import java.awt.KeyboardFocusManager
import java.awt.event.ActionListener
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.JComponent.WHEN_FOCUSED
import javax.swing.KeyStroke

object TracerUIUtil {

    fun addEnterKeyAction(textField: EditorTextField, action: () -> Unit) {
        check(textField.editor == null) { "Must be called before the editor is initialized" }
        textField.addSettingsProvider { editor ->
            val enter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)
            val enterHandler = ActionListener { action() }
            editor.contentComponent.registerKeyboardAction(enterHandler, enter, WHEN_FOCUSED)
        }
    }

    // TODO: This is a workaround for https://youtrack.jetbrains.com/issue/IDEA-248576.
    //  (Needed when the text field is backed by the 'default' project.)
    fun reinstallCompletionProviderAsNeeded(
        textField: TextFieldWithCompletion,
        completionProvider: TextCompletionProvider
    ) {
        check(textField.editor == null) { "Must be called before the editor is initialized" }
        textField.addSettingsProvider { editor ->
            editor.document.addDocumentListener(object : BulkAwareDocumentListener.Simple {
                override fun beforeDocumentChange(document: Document) {
                    ApplicationManager.getApplication().assertReadAccessAllowed()
                    val project = textField.project ?: ProjectManager.getInstance().defaultProject
                    val psiDocumentManager = PsiDocumentManager.getInstance(project)
                    val psiFile = psiDocumentManager.getPsiFile(document) ?: return
                    val currentProvider = TextCompletionUtil.getProvider(psiFile)
                    if (currentProvider == null) {
                        val logger = Logger.getInstance(TracerUIUtil::class.java)
                        logger.warn("Reinstalling TextCompletionProvider (see IDEA-248576)")
                        TextCompletionUtil.installProvider(psiFile, completionProvider, true)
                    }
                }
            })
        }
    }
}