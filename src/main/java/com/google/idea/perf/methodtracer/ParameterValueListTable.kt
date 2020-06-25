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

package com.google.idea.perf.methodtracer

import com.google.idea.perf.util.formatNsInMs
import com.google.idea.perf.util.formatNum
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

private const val PARAMETER_VALUE_LIST = 0
private const val CALLS = 1
private const val WALL_TIME = 2
private const val MAX_WALL_TIME = 3
private const val COL_COUNT = 4

/** Table model for [ParameterValueListTable]. */
class ParameterValueListTableModel: AbstractTableModel() {
    private var data: List<ParameterValueListStats>? = null

    fun setStats(newStats: List<ParameterValueListStats>?) {
        data = newStats
        fireTableDataChanged()
    }

    override fun getRowCount(): Int = data?.size ?: 0

    override fun getColumnCount(): Int = COL_COUNT

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val stats = data!![rowIndex]
        return when (columnIndex) {
            PARAMETER_VALUE_LIST -> stats.args.toString()
            CALLS -> stats.callCount
            WALL_TIME -> stats.wallTime
            MAX_WALL_TIME -> stats.maxWallTime
            else -> error(columnIndex)
        }
    }

    override fun getColumnName(column: Int): String = when (column) {
        PARAMETER_VALUE_LIST -> "arguments"
        CALLS -> "calls"
        WALL_TIME -> "wall time"
        MAX_WALL_TIME -> "max wall time"
        else -> error(column)
    }

    override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
        PARAMETER_VALUE_LIST -> java.lang.String::class.java
        CALLS, WALL_TIME, MAX_WALL_TIME -> java.lang.Long::class.java
        else -> error(columnIndex)
    }

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false
}

class ParameterValueListTable(private val model: ParameterValueListTableModel): JBTable(model) {
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
                PARAMETER_VALUE_LIST -> Integer.MAX_VALUE
                CALLS, WALL_TIME, MAX_WALL_TIME -> 100
                else -> tableColumn.preferredWidth
            }

            // Locale-aware and unit-aware rendering for numbers.
            when (col) {
                CALLS, WALL_TIME, MAX_WALL_TIME -> {
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
                                MAX_WALL_TIME -> formatNsInMs(value)
                                else -> formatNum(value)
                            }
                            super.setValue(formatted)
                        }
                    }
                }
            }
        }

        // Limit sorting directions.
        rowSorter = object : TableRowSorter<ParameterValueListTableModel>(model) {
            override fun toggleSortOrder(col: Int) {
                val alreadySorted = sortKeys.any {
                    it.column == col && it.sortOrder != SortOrder.UNSORTED
                }
                if (alreadySorted) return
                val order = when (col) {
                    PARAMETER_VALUE_LIST -> SortOrder.ASCENDING
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

    fun setStats(newStats: List<ParameterValueListStats>?) {
        // Changing table data clears the current row selection, so we have to restore it manually.
        val selection = selectionModel.leadSelectionIndex
        model.setStats(newStats)
        if (selection != -1 && selection < model.rowCount) {
            selectionModel.setSelectionInterval(selection, selection)
        }
    }
}
