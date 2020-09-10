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
import com.google.idea.perf.tracer.ui.TracerTableModel.Column
import com.intellij.ui.treeStructure.treetable.TreeTableModel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/** Tree model for [TracerTree]. */
class TracerTreeModel : DefaultTreeModel(null), TreeTableModel {

    override fun getColumnCount(): Int = Column.count

    override fun getColumnName(col: Int): String = Column.valueOf(col).displayName

    override fun getColumnClass(col: Int): Class<*> {
        return when (col) {
            0 -> TreeTableModel::class.java // A quirky requirement of TreeTable.
            else -> Column.valueOf(col).type
        }
    }

    override fun getValueAt(uiNode: Any, col: Int): Any {
        check(uiNode is CallNode)
        val callTree = uiNode.callTree
        return when (Column.valueOf(col)) {
            Column.TRACEPOINT -> callTree.tracepoint.displayName
            Column.CALLS -> callTree.callCount
            Column.WALL_TIME -> callTree.wallTime
            Column.MAX_WALL_TIME -> callTree.maxWallTime
        }
    }

    override fun isCellEditable(uiNode: Any, column: Int): Boolean = false

    override fun setValueAt(value: Any?, uiNode: Any, column: Int) {}

    override fun setTree(tree: JTree?) {}

    class CallNode(var callTree: CallTree) : DefaultMutableTreeNode() {
        init {
            // Callee nodes.
            val callees = callTree.children.values
            for ((i, callee) in callees.withIndex()) {
                val child = CallNode(callee)
                insert(child, i)
            }
        }

        // This method controls the text in the tracepoint column.
        override fun toString(): String = callTree.tracepoint.displayName
    }

    fun setCallTree(callTree: CallTree) {
        when {
            root == null -> setRoot(CallNode(callTree))
            root.childCount == 0 && callTree.children.isNotEmpty() -> {
                // If isRootVisible=false and the initial root has no children, then the root will
                // remain unexpanded even when children are added later (likely a bug in Swing).
                // This is the workaround.
                setRoot(CallNode(callTree))
            }
            else -> {
                updateIncrementally(root as CallNode, callTree)
                nodeChanged(root)
            }
        }
    }

    /**
     * Incrementally mutates the current UI tree to match the given call tree.
     * The main benefit of this is preserving the selection and expansion state of the tree.
     * For best results we hope that the call tree grows monotonically with stable iteration order.
     */
    private fun updateIncrementally(uiNode: CallNode, callTree: CallTree) {
        uiNode.callTree = callTree

        while (uiNode.childCount > callTree.children.size) {
            removeNodeFromParent(uiNode.lastChild as CallNode)
        }

        for ((i, callee) in callTree.children.values.withIndex()) {
            if (i < uiNode.childCount) {
                val uiChild = uiNode.getChildAt(i) as CallNode
                updateIncrementally(uiChild, callee)
            } else {
                val newUiChild = CallNode(callee)
                insertNodeInto(newUiChild, uiNode, i)
            }
        }
    }
}
