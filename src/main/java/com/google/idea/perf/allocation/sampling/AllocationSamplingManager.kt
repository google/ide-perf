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

package com.google.idea.perf.allocation.sampling

import com.intellij.memory.agent.AllocationListener
import com.intellij.memory.agent.MemoryAgent
import java.util.concurrent.ConcurrentHashMap

class AllocationSamplingManager(private val agent: MemoryAgent) {
    data class AllocationInfo(var allocationCount: Long = 0, var totalAllocationSize: Long = 0) {
        fun increment(allocationSize: Long): AllocationInfo {
            allocationCount += 1
            totalAllocationSize += allocationSize
            return this
        }
    }

    val classNameToAllocationInfo: MutableMap<String, AllocationInfo> = ConcurrentHashMap()
    private val classNameToAllocationListener: MutableMap<String, AllocationListener> = HashMap()

    fun resetSampledClassesList() {
        for (listener in classNameToAllocationListener.values) {
            agent.removeAllocationListener(listener)
        }
        classNameToAllocationInfo.clear()
        classNameToAllocationListener.clear()
    }

    fun clearSampledClassesList() =
        classNameToAllocationInfo.replaceAll { _, _ -> AllocationInfo() }

    fun removeAllocationSamplingListener(sampledClass: Class<*>) {
        val className = sampledClass.name
        val listener = classNameToAllocationListener[className] ?: return
        classNameToAllocationInfo.remove(className)
        agent.removeAllocationListener(listener)
    }

    fun addAllocationSamplingListener(sampledClass: Class<*>) {
        val className = sampledClass.name
        if (classNameToAllocationInfo.containsKey(className)) {
            return
        }

        val listener = createAllocationListener(className)
        classNameToAllocationInfo[className] = AllocationInfo()
        classNameToAllocationListener[className] = listener
        agent.addAllocationListener(sampledClass, listener)
    }

    private fun createAllocationListener(className: String) =
        AllocationListener { info ->
            classNameToAllocationInfo.compute(className) { _, currentCount ->
                currentCount?.increment(info.size) ?: AllocationInfo(1, info.size)
            }
        }
}
