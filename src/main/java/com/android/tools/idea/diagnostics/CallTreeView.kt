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

package com.android.tools.idea.diagnostics

import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.Font
import javax.swing.ListSelectionModel
import javax.swing.SortOrder
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.JTableHeader
import javax.swing.table.TableRowSorter

// Things to improve:
// - Update column width if numbers get too large.
// - Change font color based on how recently a number has changed.

// Table columns.
private const val TRACEPOINT = 0
private const val CALLS = 1
private const val WALL_TIME = 2
private const val COL_COUNT = 3

/** Table model for a flat list of methods. */
class CallTableModel : AbstractTableModel() {
    private var data: List<TracepointStats>? = null

    fun setTracepointStats(newStats: List<TracepointStats>?) {
        data = newStats
        fireTableDataChanged()
    }

    override fun getColumnCount(): Int = COL_COUNT

    override fun getColumnName(col: Int): String = when (col) {
        TRACEPOINT -> "tracepoint"
        CALLS -> "calls"
        WALL_TIME -> "wall time"
        else -> error(col)
    }

    override fun getColumnClass(col: Int): Class<*> = when (col) {
        TRACEPOINT -> java.lang.String::class.java
        CALLS, WALL_TIME -> java.lang.Long::class.java
        else -> error(col)
    }

    override fun getRowCount(): Int = data?.size ?: 0

    override fun getValueAt(row: Int, col: Int): Any {
        val stats = data!![row]
        return when (col) {
            TRACEPOINT -> stats.tracepoint.displayName
            CALLS -> stats.callCount
            WALL_TIME -> stats.wallTime
            else -> error(col)
        }
    }

    override fun isCellEditable(row: Int, column: Int): Boolean = false
}

class CallTableView(private val model: CallTableModel) : JBTable(model) {

    init {
        font = JBUI.Fonts.create(Font.MONOSPACED, font.size)
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        setShowGrid(false)

        // Column rendering.
        val columnModel = columnModel
        for (col in 0 until COL_COUNT) {
            val tableColumn = columnModel.getColumn(col)

            // Column widths.
            tableColumn.minWidth = 100
            tableColumn.preferredWidth = when (col) {
                TRACEPOINT -> Integer.MAX_VALUE
                CALLS, WALL_TIME -> 100
                else -> tableColumn.preferredWidth
            }

            // Locale-aware and unit-aware rendering for numbers.
            when (col) {
                CALLS, WALL_TIME -> {
                    tableColumn.cellRenderer = object : DefaultTableCellRenderer() {
                        init {
                            horizontalAlignment = SwingConstants.RIGHT
                        }

                        override fun setValue(value: Any?) {
                            if (value !is Long) {
                                return super.setValue(value)
                            }
                            val formatted = when (col) {
                                WALL_TIME -> formatNsInMs(value)
                                else -> formatNum(value)
                            }
                            super.setValue(formatted)
                        }
                    }
                }
            }
        }

        // Limit sorting directions.
        rowSorter = object : TableRowSorter<CallTableModel>(model) {
            override fun toggleSortOrder(col: Int) {
                val alreadySorted = sortKeys.any {
                    it.column == col && it.sortOrder != SortOrder.UNSORTED
                }
                if (alreadySorted) return
                val order = when (col) {
                    TRACEPOINT -> SortOrder.ASCENDING
                    else -> SortOrder.DESCENDING
                }
                sortKeys = listOf(SortKey(col, order))
            }
        }
        rowSorter.toggleSortOrder(WALL_TIME)
    }

    override fun createDefaultTableHeader(): JTableHeader {
        return object : JBTableHeader() {
            init {
                // Override the renderer that JBTableHeader sets.
                // The default, center-aligned renderer looks better.
                defaultRenderer = createDefaultRenderer()
            }
        }
    }

    fun setTracepointStats(newStats: List<TracepointStats>?) {
        // Changing table data clears the current row selection, so we have to restore it manually.
        val selection = selectionModel.leadSelectionIndex
        model.setTracepointStats(newStats)
        if (selection != -1 && selection < model.rowCount) {
            selectionModel.setSelectionInterval(selection, selection)
        }
    }
}
