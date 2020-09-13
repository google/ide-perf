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

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.UIUtil
import java.awt.AWTEvent
import java.awt.Dialog
import java.awt.event.KeyEvent
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.KeyStroke
import javax.swing.border.Border

// Things to improve:
// * DialogWrapper is tied to a specific project window, which is not ideal.

/** The dialog window that pops up via the "Trace" action. It displays the [TracerPanel]. */
class TracerDialog : DialogWrapper(null, null, false, IdeModalityType.IDE, false) {
    init {
        init()
        title = "Tracer"
        isModal = false
        val window = peer.window
        if (window is Dialog) {
            // Ensure this dialog can be the parent of other dialogs.
            UIUtil.markAsPossibleOwner(window)
        }
    }

    override fun createCenterPanel(): JComponent = TracerPanel(disposable)
    override fun createContentPaneBorder(): Border? = null // No border.
    override fun getDimensionServiceKey(): String = "${javaClass.packageName}.Tracer"
    override fun createActions(): Array<Action> = emptyArray()

    override fun show() {
        super.show()
        // Do not let <Esc> close the tracer (see DialogWrapper.registerKeyboardShortcuts).
        rootPane.unregisterKeyboardAction(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0))
    }

    override fun doCancelAction(source: AWTEvent) {
        if (source is KeyEvent && source.keyCode == KeyEvent.VK_ESCAPE) {
            return // Do not let <Esc> close the tracer (see DialogWrapperPeerImpl.AnCancelAction).
        }
        super.doCancelAction(source)
    }
}
