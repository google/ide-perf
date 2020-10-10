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

package com.google.idea.perf

import com.google.common.truth.Truth.assertThat
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test
import kotlin.math.abs

/** Tests [AllocationSampling]. */
class AllocationSamplingTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        check(AgentLoader.ensureNativeAgentLoaded)
    }

    @Test
    fun testNoAllocations() {
        fun doNothing() = Unit
        val allocations = countAllocations(::doNothing)
        assertThat(allocations).isEqualTo(0)
    }

    @Test
    fun testSmallAllocations() {
        val allocations = countAllocations { repeat(1024) { LongArray(1024) } }
        assertThat(allocations).isGreaterThan(0)

        val error = computeError(8 * 1024 * 1024, allocations)
        println("Error for small allocations: $error")
        assertThat(error).isAtMost(0.5) // Lenient because heap sampling is pseudo-random.
    }

    @Test
    fun testLargeAllocations() {
        val allocations = countAllocations { repeat(3) { LongArray(1024 * 1024) } }
        assertThat(allocations).isGreaterThan(0)

        val error = computeError(3 * 8 * 1024 * 1024, allocations)
        println("Error for large allocations: $error")
        assertThat(error).isAtMost(0.5) // Lenient because heap sampling is pseudo-random.
    }

    private inline fun countAllocations(action: () -> Unit): Long {
        val start = AllocationSampling.countAllocationsForCurrentThread()
        action()
        val end = AllocationSampling.countAllocationsForCurrentThread()
        return end - start
    }

    private fun computeError(expected: Long, actual: Long): Double {
        return abs(actual - expected).toDouble() / expected
    }
}
