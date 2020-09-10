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

import com.google.idea.perf.tracer.TracepointStats
import javax.swing.table.AbstractTableModel
import kotlin.math.min

/** The table model for [TracerTable]. */
class TracerTableModel : AbstractTableModel() {
    var data: List<TracepointStats>? = null

    /** Table columns. */
    enum class Column(val displayName: String, val type: Class<*>) {
        TRACEPOINT("tracepoint", String::class.java),
        CALLS("calls", Long::class.javaObjectType),
        WALL_TIME("wall time", Long::class.javaObjectType),
        MAX_WALL_TIME("max wall time", Long::class.javaObjectType);

        companion object {
            val values = values()
            val count = values.size
            fun valueOf(col: Int): Column = values[col]
        }
    }

    override fun getColumnCount(): Int = Column.count

    override fun getColumnName(col: Int): String = Column.valueOf(col).displayName

    override fun getColumnClass(col: Int): Class<*> = Column.valueOf(col).type

    override fun getRowCount(): Int = data?.size ?: 0

    override fun getValueAt(row: Int, col: Int): Any {
        val stats = data!![row]
        return when (Column.valueOf(col)) {
            Column.TRACEPOINT -> stats.tracepoint.displayName
            Column.CALLS -> stats.callCount
            Column.WALL_TIME -> stats.wallTime
            Column.MAX_WALL_TIME -> stats.maxWallTime
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
