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

package com.google.idea.perf.vfstracer

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

private const val COL_COUNT = 3

// Table columns.
private const val FILE_NAME = 0
private const val STUB_INDEX_ACCESSES = 1
private const val PSI_WRAPS = 2

class VfsStatTableModel: AbstractTableModel() {
    private var data: List<VirtualFileStats>? = null

    fun setData(newData: List<VirtualFileStats>?) {
        data = newData
        fireTableDataChanged()
    }

    override fun getColumnCount(): Int = COL_COUNT

    override fun getColumnName(column: Int): String = when (column) {
        FILE_NAME -> "file name"
        STUB_INDEX_ACCESSES -> "stub index accesses"
        PSI_WRAPS -> "psi wraps"
        else -> error(column)
    }

    override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
        FILE_NAME -> java.lang.String::class.java
        STUB_INDEX_ACCESSES, PSI_WRAPS -> java.lang.Integer::class.java
        else -> error(columnIndex)
    }

    override fun getRowCount(): Int = data?.size ?: 0

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val stats = data!![rowIndex]
        return when (columnIndex) {
            FILE_NAME -> stats.fileName
            STUB_INDEX_ACCESSES -> stats.stubIndexAccesses
            PSI_WRAPS -> stats.psiElementWraps
            else -> error(columnIndex)
        }
    }

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false
}

class VfsStatTable(private val model: VfsStatTableModel): JBTable(model) {
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
                FILE_NAME -> Int.MAX_VALUE
                STUB_INDEX_ACCESSES, PSI_WRAPS -> 100
                else -> tableColumn.preferredWidth
            }

            // Locale-aware and unit-aware rendering for numbers.
            when (col) {
                STUB_INDEX_ACCESSES, PSI_WRAPS -> {
                    tableColumn.cellRenderer = object: DefaultTableCellRenderer() {
                        init {
                            horizontalAlignment = SwingConstants.RIGHT
                        }
                    }
                }
            }
        }

        // Limit sorting directions.
        rowSorter = object: TableRowSorter<VfsStatTableModel>(model) {
            override fun toggleSortOrder(col: Int) {
                val alreadySorted = sortKeys.any {
                    it.column == col && it.sortOrder != SortOrder.UNSORTED
                }
                if (alreadySorted) return
                val order = when (col) {
                    FILE_NAME -> SortOrder.ASCENDING
                    else -> SortOrder.DESCENDING
                }
                sortKeys = listOf(SortKey(col, order))
            }
        }
        rowSorter.toggleSortOrder(PSI_WRAPS)
    }

    override fun createDefaultTableHeader(): JTableHeader {
        return object: JBTableHeader() {
            init {
                defaultRenderer = createDefaultRenderer()
            }
        }
    }

    fun setStats(newStats: List<VirtualFileStats>?) {
        val selection = selectionModel.leadSelectionIndex
        model.setData(newStats)
        if (selection != -1 && selection < model.rowCount) {
            selectionModel.setSelectionInterval(selection, selection)
        }
    }
}
