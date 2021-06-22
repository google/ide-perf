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

import com.google.common.truth.Truth.assertThat
import com.intellij.memory.agent.AllocationListener
import com.intellij.memory.agent.MemoryAgent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test
import kotlin.math.abs

class AllocationSamplingTest : BasePlatformTestCase() {
    private lateinit var agent: MemoryAgent

    override fun setUp() {
        super.setUp()
        agent = MemoryAgent.get()
        assert(this::agent.isInitialized)
        agent.setHeapSamplingInterval(1024)
    }

    @Test
    fun testSmallAllocations() =
        testLongArrayAllocations(1024, 1024)

    @Test
    fun testLargeAllocations() =
        testLongArrayAllocations(3, 1024 * 1024)

    private fun testLongArrayAllocations(times: Int, size: Int) {
        val sampledSize = allocateLongArrayAndGetSampledSize(times, size)
        assertThat(sampledSize).isGreaterThan(0)
        assertThat(computeError(8L * times * size, sampledSize)).isAtMost(0.5)
    }

    private fun allocateLongArrayAndGetSampledSize(times: Int, size: Int): Long {
        var totalAllocatedSize = 0L
        val listener = AllocationListener { totalAllocatedSize += it.size }
        agent.addAllocationListener(listener)
        repeat(times) { LongArray(size) }
        agent.removeAllocationListener(listener)
        return totalAllocatedSize
    }

    private fun computeError(expected: Long, actual: Long) =
        abs(actual - expected).toDouble() / expected
}
