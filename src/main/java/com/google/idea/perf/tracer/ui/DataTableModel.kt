/*
 * Copyright 2021 Google LLC
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

import javax.swing.table.AbstractTableModel
import kotlin.math.min

open class DataTableModel<T> : AbstractTableModel() {
    class Column<T>(
        val displayName: String,
        val type: Class<*>,
        val valueSelector: (T) -> Any
    )

    val columns: MutableList<Column<T>> = ArrayList()
    var data: List<T>? = null

    override fun getColumnCount(): Int = columns.size

    override fun getColumnName(col: Int): String = columns[col].displayName

    override fun getColumnClass(col: Int): Class<*> = columns[col].type

    override fun getRowCount(): Int = data?.size ?: 0

    override fun getValueAt(row: Int, col: Int) = getValueAtCol(data!![row], col)

    override fun isCellEditable(row: Int, column: Int): Boolean = false

    private fun getValueAtCol(item: T, col: Int) = columns[col].valueSelector(item)

    protected fun setNewData(newData: List<T>, col: Int = 0) {
        val oldStats = data
        if (oldStats == null) {
            data = newData
            fireTableDataChanged()
            return
        }

        // We try to make newData look "similar" to oldData by reordering its elements.
        // That way we preserve the current row selection in the common case.
        val newStatsMap = newData.associateByTo(LinkedHashMap(newData.size)) {
            getValueAtCol(it, col)
        }

        val newStatsOrdered = ArrayList<T>(newData.size)
        for (oldStat in oldStats) {
            val newStat = newStatsMap.remove(getValueAtCol(oldStat, col)) ?: continue
            newStatsOrdered.add(newStat)
        }
        newStatsOrdered.addAll(newStatsMap.values) // New insertions.
        check(newStatsOrdered.size == newData.size)

        data = newStatsOrdered

        // Generate table model events.
        val oldRows = oldStats.size
        val newRows = newData.size
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
