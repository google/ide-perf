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

data class SamplingInfo(
    val className: String,
    val allocationCount: Long,
    val allocatedSize: Long
)

class AllocationSamplingTableModel : DataTableModel<SamplingInfo>() {
    enum class AllocationSamplingTableColumn(val column: Column<SamplingInfo>) {
        CLASS(Column("class", String::class.java, SamplingInfo::className)),
        ALLOCATION_COUNT(Column("allocation count", Long::class.java, SamplingInfo::allocationCount)),
        ALLOCATED_SIZE(Column("total allocation size", Long::class.java, SamplingInfo::allocatedSize));
    }

    init {
        columns.addAll(AllocationSamplingTableColumn.values().map(AllocationSamplingTableColumn::column))
    }

    fun setSamplingInfo(samplingInfoList: List<SamplingInfo>) = setNewData(samplingInfoList)
}
