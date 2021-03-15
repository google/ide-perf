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

import com.google.idea.perf.tracer.ui.AllocationSamplingTableModel.AllocationSamplingTableColumn
import com.google.idea.perf.util.formatNum
import com.google.idea.perf.util.formatSize
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.JTableHeader

class AllocationSamplingTable(val model: AllocationSamplingTableModel) : JBTable(model) {
    init {
        font = EditorUtil.getEditorFont()
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        setShowGrid(false)
        rowHeight = JBUI.scale(22)
        for (col in AllocationSamplingTableColumn.values()) {
            val tableColumn = columnModel.getColumn(col.ordinal)

            tableColumn.minWidth = 100
            tableColumn.preferredWidth = when (col) {
                AllocationSamplingTableColumn.CLASS -> Integer.MAX_VALUE
                else -> 100
            }

            if (col == AllocationSamplingTableColumn.ALLOCATED_SIZE ||
                col == AllocationSamplingTableColumn.ALLOCATION_COUNT
            ) {
                tableColumn.cellRenderer = object : DefaultTableCellRenderer() {
                    init {
                        horizontalAlignment = SwingConstants.RIGHT
                    }

                    override fun setValue(value: Any?) {
                        if (value == null) return super.setValue(value)
                        val formatted = when (col) {
                            AllocationSamplingTableColumn.ALLOCATION_COUNT -> formatNum(value as Long)
                            AllocationSamplingTableColumn.ALLOCATED_SIZE -> formatSize(value as Long)
                            else -> error("number value required")
                        }
                        super.setValue(formatted)
                    }
                }
            }
        }
    }

    override fun createDefaultTableHeader(): JTableHeader {
        return object : JBTableHeader() {
            init {
                defaultRenderer = createDefaultRenderer()
            }
        }
    }

    fun setSamplingInfo(samplingInfoList: List<SamplingInfo>) =
        model.setSamplingInfo(samplingInfoList)
}
