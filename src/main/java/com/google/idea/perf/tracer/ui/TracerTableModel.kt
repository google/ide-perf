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

/** The table model for [TracerTable]. */
class TracerTableModel : DataTableModel<TracepointStats>() {
    /** Table columns. */
    enum class TracerTableColumn(val column: Column<TracepointStats>) {
        TRACEPOINT(Column("tracepoint", String::class.java) { it.tracepoint.displayName }),
        CALLS(Column("calls", Long::class.javaObjectType, TracepointStats::callCount)),
        WALL_TIME(Column("wall time", Long::class.javaObjectType, TracepointStats::wallTime)),
        MAX_WALL_TIME(Column("max wall time", Long::class.javaObjectType, TracepointStats::maxWallTime));

        companion object {
            val values = values()
            val count = values.size
            fun valueOf(col: Int): TracerTableColumn = values[col]
        }
    }

    init {
        columns.addAll(TracerTableColumn.values.map(TracerTableColumn::column))
    }

    fun setTracepointStats(newStats: List<TracepointStats>) = setNewData(newStats)
}
