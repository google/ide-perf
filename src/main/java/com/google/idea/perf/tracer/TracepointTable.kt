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

import com.google.idea.perf.util.formatNsInBestUnit
import com.google.idea.perf.util.formatNsInMs
import com.google.idea.perf.util.formatNum
import com.intellij.openapi.rd.attach
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBEmptyBorder
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Font
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JTextArea
import javax.swing.ListSelectionModel
import javax.swing.SortOrder
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.JTableHeader
import javax.swing.table.TableRowSorter
import javax.swing.text.DefaultCaret
import kotlin.math.min

// Things to improve:
// - Update column width if numbers get too large.
// - Change font color based on how recently a number has changed.

// Table columns.
private const val TRACEPOINT = 0
private const val CALLS = 1
private const val WALL_TIME = 2
private const val MAX_WALL_TIME = 3
private const val COL_COUNT = 4

/** The table model for [TracepointTable]. */
class TracepointTableModel: AbstractTableModel() {
    var data: List<TracepointStats>? = null

    override fun getColumnCount(): Int = COL_COUNT

    override fun getColumnName(col: Int): String = when (col) {
        TRACEPOINT -> "tracepoint"
        CALLS -> "calls"
        WALL_TIME -> "wall time"
        MAX_WALL_TIME -> "max wall time"
        else -> error(col)
    }

    override fun getColumnClass(col: Int): Class<*> = when (col) {
        TRACEPOINT -> java.lang.String::class.java
        CALLS, WALL_TIME, MAX_WALL_TIME -> java.lang.Long::class.java
        else -> error(col)
    }

    override fun getRowCount(): Int = data?.size ?: 0

    override fun getValueAt(row: Int, col: Int): Any {
        val stats = data!![row]
        return when (col) {
            TRACEPOINT -> stats.tracepoint.displayName
            CALLS -> stats.callCount
            WALL_TIME -> stats.wallTime
            MAX_WALL_TIME -> stats.maxWallTime
            else -> error(col)
        }
    }

    override fun isCellEditable(row: Int, column: Int): Boolean = false

    fun setTracepointStats(newStats: List<TracepointStats>) {
        val oldStats = data
        if (oldStats == null) {
            data = newStats
            fireTableDataChanged()
            return
        }

        // We try to make newStats look "similar" to oldStats by reordering its elements.
        // That way we preserve the current row selection in the common case.
        val newStatsMap = newStats.associateByTo(LinkedHashMap(newStats.size)) { it.tracepoint }
        val newStatsOrdered = ArrayList<TracepointStats>(newStats.size)
        for (oldStat in oldStats) {
            val newStat = newStatsMap.remove(oldStat.tracepoint) ?: continue
            newStatsOrdered.add(newStat)
        }
        newStatsOrdered.addAll(newStatsMap.values) // New insertions.
        check(newStatsOrdered.size == newStats.size)

        data = newStatsOrdered

        // Generate table model events.
        val oldRows = oldStats.size
        val newRows = newStats.size
        when {
            newRows > oldRows -> fireTableRowsInserted(oldRows, newRows - 1)
            newRows < oldRows -> fireTableRowsDeleted(newRows, oldRows - 1)
        }
        val modifiedRows = min(oldRows, newRows)
        if (modifiedRows > 0) {
            fireTableRowsUpdated(0, modifiedRows - 1)
        }
    }
}

/** Displays a list of tracepoints alongside their call counts and timing measurements. */
class TracepointTable(private val model: TracepointTableModel): JBTable(model) {
    private val tracepointDetailsManager = TracepointDetailsManager(this)

    init {
        font = JBUI.Fonts.create(Font.MONOSPACED, font.size)
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        setShowGrid(false)

        // Column rendering.
        val columnModel = columnModel
        for (col in 0 until COL_COUNT) {
            val tableColumn = columnModel.getColumn(col)

            // Hide some less-important columns for now.
            // Eventually we should give the user the ability to choose which columns are visible.
            when (col) {
                MAX_WALL_TIME -> removeColumn(tableColumn)
            }

            // Column widths.
            tableColumn.minWidth = 100
            tableColumn.preferredWidth = when (col) {
                TRACEPOINT -> Integer.MAX_VALUE
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

        // Row sorter.
        rowSorter = object: TableRowSorter<TracepointTableModel>(model) {
            init {
                sortsOnUpdates = true
                toggleSortOrder(WALL_TIME)
            }

            // Limit sorting directions.
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

        fun showTracepointDetailsForRow(row: Int) {
            val modelRow = rowSorter.convertRowIndexToModel(row)
            val data = model.data?.get(modelRow) ?: return
            tracepointDetailsManager.showTracepointDetails(data)
        }

        // Show the tracepoint details popup upon double-click.
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount < 2) return
                val row = rowAtPoint(e.point)
                if (row == -1) return
                showTracepointDetailsForRow(row)
            }
        })

        // Show the tracepoint details popup upon hitting <enter>.
        addKeyListener(object : KeyAdapter() {
            override fun keyTyped(e: KeyEvent) {
                if (e.keyCode != KeyEvent.VK_ENTER) return
                if (selectionModel.isSelectionEmpty) return
                val row = selectionModel.leadSelectionIndex
                showTracepointDetailsForRow(row)
            }
        })
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

    fun setTracepointStats(newStats: List<TracepointStats>) {
        model.setTracepointStats(newStats)
        tracepointDetailsManager.updateTracepointDetails(newStats)
    }
}

/** Manages the tracepoint details shown via [TracepointDetailsDialog]. */
class TracepointDetailsManager(private val table: TracepointTable) {
    private var currentTracepoint: Tracepoint? = null
    private var currentDialog: TracepointDetailsDialog? = null

    fun showTracepointDetails(data: TracepointStats) {
        currentTracepoint = data.tracepoint
        val detailText = buildDetailString(data)
        val dialog = currentDialog
        if (dialog != null) {
            dialog.textArea.text = detailText
        } else {
            val newDialog = TracepointDetailsDialog(table, detailText)
            currentDialog = newDialog
            newDialog.disposable.attach { currentDialog = null }
            newDialog.show()
        }
    }

    fun updateTracepointDetails(allData: List<TracepointStats>) {
        val dialog = currentDialog ?: return
        val tracepoint = currentTracepoint ?: return
        val data = allData.firstOrNull { it.tracepoint == tracepoint } ?: return
        val detailText = buildDetailString(data)
        val textArea = dialog.textArea
        if (detailText != textArea.text) {
            setTextPreservingSelection(textArea, detailText)
        }
    }

    private fun setTextPreservingSelection(textArea: JTextArea, text: String) {
        val caret = textArea.caret
        val dot = caret.dot
        val mark = caret.mark

        textArea.text = text // We hope the text did not change very much.

        if (dot != mark) {
            caret.dot = mark
            caret.moveDot(dot)
        }
    }

    private fun buildDetailString(data: TracepointStats): String {
        return buildString {
            appendln(data.tracepoint.displayName)

            val callCount = formatNum(data.callCount)
            val wallTime = formatNsInBestUnit(data.wallTime)
            appendln()
            appendln("Call count: $callCount")
            appendln("Total wall time: $wallTime")

            if (data.callCount > 0) {
                val maxWallTime = formatNsInBestUnit(data.maxWallTime)
                val avgWallTime = formatNsInBestUnit(data.wallTime / data.callCount)
                appendln()
                appendln("Average wall time: $avgWallTime")
                appendln("Max wall time: $maxWallTime")
            }

            appendln()
            append(data.tracepoint.detailedName)
        }
    }
}

/** A popup showing details for a specific tracepoint. */
class TracepointDetailsDialog(parent: Component, text: String) : DialogWrapper(parent, false) {
    val textArea = JBTextArea(text).apply {
        font = JBUI.Fonts.create(Font.SANS_SERIF, font.size)
        isEditable = false
        border = JBEmptyBorder(5)
        val caret = caret
        if (caret is DefaultCaret) {
            // Disable caret movement so that changing text does not affect scroll position.
            caret.updatePolicy = DefaultCaret.NEVER_UPDATE
        }
    }

    init {
        title = "Tracepoint Details"
        isModal = false
        init()
    }

    override fun createCenterPanel(): JComponent = JBScrollPane(textArea)
    override fun getDimensionServiceKey(): String = "${javaClass.packageName}.TracepointDetails"
    override fun createActions(): Array<Action> = arrayOf(okAction)
}
