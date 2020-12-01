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

package com.google.idea.perf.cvtracer

import com.google.idea.perf.util.formatMsInSeconds
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

private const val COL_COUNT = 5

// Table columns.
private const val NAME = 0
private const val LIFETIME = 1
private const val HITS = 2
private const val MISSES = 3
private const val HIT_MISS_RATIO = 4

class CachedValueTableModel: AbstractTableModel() {
    private var data: List<CachedValueStats>? = null

    fun setData(newData: List<CachedValueStats>?) {
        data = newData
        fireTableDataChanged()
    }

    override fun getColumnCount(): Int = 5

    override fun getColumnName(column: Int): String = when (column) {
        NAME -> "name"
        LIFETIME -> "lifetime"
        HITS -> "hits"
        MISSES -> "misses"
        HIT_MISS_RATIO -> "hit/miss ratio"
        else -> error(column)
    }

    override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
        NAME -> java.lang.String::class.java
        LIFETIME, HITS, MISSES -> java.lang.Long::class.java
        HIT_MISS_RATIO -> java.lang.Double::class.java
        else -> error(columnIndex)
    }

    override fun getRowCount(): Int = data?.size ?: 0

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val stats = data!![rowIndex]
        return when (columnIndex) {
            NAME -> stats.name
            LIFETIME -> formatMsInSeconds(stats.lifetime)
            HITS -> stats.hits
            MISSES -> stats.misses
            HIT_MISS_RATIO -> formatNum(stats.hitRatio)
            else -> error(columnIndex)
        }
    }

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false
}

class CachedValueTable(private val model: CachedValueTableModel): JBTable(model) {
    init {
        font = JBUI.Fonts.create(Font.MONOSPACED, font.size)
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        setShowGrid(false)

        // Column rendering.
        val tableColumns = columnModel.columns.toList()
        for (tableColumn in tableColumns) {
            val col = tableColumn.modelIndex

            if (col == LIFETIME) {
                // TODO: Probably remove the "lifetime" column entirely (it does not seem useful).
                removeColumn(tableColumn)
            }

            // Column widths.
            tableColumn.minWidth = 100
            tableColumn.preferredWidth = when (col) {
                NAME -> Int.MAX_VALUE
                LIFETIME, HITS, MISSES, HIT_MISS_RATIO -> 100
                else -> tableColumn.preferredWidth
            }

            // Locale-aware and unit-aware rendering for numbers.
            when (col) {
                LIFETIME, HITS, MISSES, HIT_MISS_RATIO -> {
                    tableColumn.cellRenderer = object: DefaultTableCellRenderer() {
                        init {
                            horizontalAlignment = SwingConstants.RIGHT
                        }
                    }
                }
            }
        }

        // Limit sorting directions.
        rowSorter = object: TableRowSorter<CachedValueTableModel>(model) {
            override fun toggleSortOrder(col: Int) {
                val alreadySorted = sortKeys.any {
                    it.column == col && it.sortOrder != SortOrder.UNSORTED
                }
                if (alreadySorted) return
                val order = when (col) {
                    NAME, HIT_MISS_RATIO -> SortOrder.ASCENDING
                    else -> SortOrder.DESCENDING
                }
                sortKeys = listOf(SortKey(col, order))
            }
        }
        rowSorter.toggleSortOrder(HIT_MISS_RATIO)
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

    fun setStats(newStats: List<CachedValueStats>?) {
        // Changing the table clears the current row selection, so we have to restore it manually.
        val selection = selectionModel.leadSelectionIndex
        model.setData(newStats)
        if (selection != -1 && selection < model.rowCount) {
            selectionModel.setSelectionInterval(selection, selection)
        }
    }
}
