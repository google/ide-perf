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

import com.google.idea.perf.tracer.CallTree
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.ui.treeStructure.treetable.TreeTable
import javax.swing.table.JTableHeader
import javax.swing.tree.TreeSelectionModel

// Things to improve:
// * Add a tree row sorter.
// * Fix quirks with tree rendering: font color for selected cells, unfocused selection color, etc.

class TracerTree(private val model: TracerTreeModel) : TreeTable(model) {
    init {
        TracerTable.configureTracerTableOrTree(this)
        tree.font = EditorUtil.getEditorFont()
        tree.isRootVisible = false
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
    }

    override fun createDefaultTableHeader(): JTableHeader {
        return object: JBTableHeader() {
            init {
                // Override the renderer that JBTableHeader sets.
                // The default, center-aligned renderer looks better.
                defaultRenderer = createDefaultRenderer()
            }
        }
    }

    fun setCallTree(callTree: CallTree) {
        model.setCallTree(callTree)
    }
}
