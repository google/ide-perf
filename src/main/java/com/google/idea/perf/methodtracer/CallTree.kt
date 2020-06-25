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

/** A call tree, represented recursively. */
interface CallTree {
    interface Stats {
        val callCount: Long
        val wallTime: Long
        val maxWallTime: Long
    }

    val tracepoint: Tracepoint
    val stats: Stats
    val argSetStats: Map<ArgSet, Stats>
    val children: Map<Tracepoint, CallTree>
}

/** A mutable call tree implementation. */
class MutableCallTree(
    override val tracepoint: Tracepoint
): CallTree {
    class MutableStats: CallTree.Stats {
        override var callCount: Long = 0L
        override var wallTime: Long = 0L
        override var maxWallTime: Long = 0L

        fun accumulate(other: CallTree.Stats) {
            callCount += other.callCount
            wallTime += other.wallTime
            maxWallTime = maxOf(wallTime, other.maxWallTime)
        }

        fun clear() {
            callCount = 0L
            wallTime = 0L
            maxWallTime = 0L
        }
    }

    override val stats = MutableStats()
    override val argSetStats: MutableMap<ArgSet, MutableStats> = LinkedHashMap()
    override val children: MutableMap<Tracepoint, MutableCallTree> = LinkedHashMap()

    /** Accumulates the data from another call tree into this one. */
    fun accumulate(other: CallTree) {
        require(other.tracepoint == tracepoint) {
            "Doesn't make sense to sum call tree nodes representing different tracepoints"
        }

        stats.accumulate(other.stats)

        for ((argSet, stats) in other.argSetStats) {
            val thisStats = argSetStats.getOrPut(argSet) { MutableStats() }
            thisStats.accumulate(stats)
        }

        for ((childTracepoint, otherChild) in other.children) {
            val child = children.getOrPut(childTracepoint) { MutableCallTree(childTracepoint) }
            child.accumulate(otherChild)
        }
    }

    fun clear() {
        stats.clear()
        children.values.forEach(MutableCallTree::clear)
        argSetStats.forEach { (_, stats) -> stats.clear() }
    }
}
