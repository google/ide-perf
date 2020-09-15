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

import com.intellij.openapi.application.ApplicationManager.getApplication
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.BulkAwareDocumentListener
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.project.ProjectManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.util.textCompletion.TextCompletionProvider
import com.intellij.util.textCompletion.TextCompletionUtil
import com.intellij.util.textCompletion.TextFieldWithCompletion
import java.awt.Dimension
import java.awt.KeyboardFocusManager
import java.awt.event.ActionListener
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

class TracerCommandLine(
    completionProvider: TextCompletionProvider,
    commandHandler: (String) -> Unit
) : TextFieldWithCompletion(
    ProjectManager.getInstance().defaultProject,
    completionProvider, "", true, true, true
) {
    init {
        font = EditorUtil.getEditorFont()
        maximumSize = Dimension(Integer.MAX_VALUE, minimumSize.height)

        // Prevent accidental focus loss due to <tab> key.
        setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, emptySet())
        setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, emptySet())

        addSettingsProvider { editor ->
            // Register the command handler.
            val enter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)
            val enterHandler = ActionListener { commandHandler(text); text = "" }
            editor.contentComponent.registerKeyboardAction(enterHandler, enter, WHEN_FOCUSED)

            // TODO: This is a workaround for IDEA-248576.
            editor.document.addDocumentListener(object : BulkAwareDocumentListener.Simple {
                override fun beforeDocumentChange(document: Document) {
                    getApplication().assertReadAccessAllowed()
                    val psiDocumentManager = PsiDocumentManager.getInstance(project)
                    val psiFile = psiDocumentManager.getPsiFile(document) ?: return
                    val provider = TextCompletionUtil.getProvider(psiFile)
                    if (provider == null) {
                        val logger = Logger.getInstance(TracerCommandLine::class.java)
                        logger.warn("Reinstalling TextCompletionProvider (see IDEA-248576)")
                        TextCompletionUtil.installProvider(psiFile, completionProvider, true)
                    }
                }
            })
        }
    }
}
