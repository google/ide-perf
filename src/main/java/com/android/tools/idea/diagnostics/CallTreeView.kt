package com.android.tools.idea.diagnostics

import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import sun.swing.table.DefaultTableCellHeaderRenderer
import java.awt.Font
import javax.swing.ListSelectionModel
import javax.swing.SortOrder
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
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
    var trees: List<CallTree>? = null
        private set

    fun setCallTrees(newTrees: List<CallTree>?) {
        trees = newTrees
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

    override fun getRowCount(): Int = trees?.size ?: 0

    override fun getValueAt(row: Int, col: Int): Any {
        val callTree = trees!![row]
        return when (col) {
            TRACEPOINT -> callTree.tracepoint.toString()
            CALLS -> callTree.callCount
            WALL_TIME -> callTree.wallTime
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

            // Column headers.
            tableColumn.headerRenderer = DefaultTableCellHeaderRenderer().apply {
                horizontalAlignment = SwingConstants.CENTER
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
                val alreadySorted = sortKeys.any { it.column == col && it.sortOrder != SortOrder.UNSORTED }
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

    fun setCallTrees(newTrees: List<CallTree>?) {
        // Changing table data clears the current row selection, so we have to restore it manually.
        val selection = selectionModel.leadSelectionIndex
        model.setCallTrees(newTrees)
        if (selection != -1 && selection < model.rowCount) {
            selectionModel.setSelectionInterval(selection, selection)
        }
    }
}
