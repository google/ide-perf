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

/** A call tree, represented recursively. */
interface CallTree {
    val tracepoint: Tracepoint
    val callCount: Long
    val wallTime: Long
    val maxCallTime: Long
    val children: Map<Tracepoint, CallTree>
}

/** A mutable call tree implementation. */
class MutableCallTree(
    override val tracepoint: Tracepoint
) : CallTree {
    override var callCount: Long = 0
    override var wallTime: Long = 0
    override var maxCallTime: Long = 0
    override val children: MutableMap<Tracepoint, MutableCallTree> = LinkedHashMap()

    /** Accumulates the data from another call tree into this one. */
    fun accumulate(other: CallTree) {
        require(other.tracepoint == tracepoint) {
            "Doesn't make sense to sum call tree nodes representing different tracepoints"
        }

        callCount += other.callCount
        wallTime += other.wallTime
        maxCallTime = maxCallTime.coerceAtLeast(other.maxCallTime)

        for ((childTracepoint, otherChild) in other.children) {
            val child = children.getOrPut(childTracepoint) { MutableCallTree(childTracepoint) }
            child.accumulate(otherChild)
        }
    }

    fun clear() {
        callCount = 0
        wallTime = 0
        maxCallTime = 0
        children.values.forEach(MutableCallTree::clear)
    }
}

/** Encapsulates aggregate statistic for a single tracepoint. */
class TracepointStats(
    val tracepoint: Tracepoint,
    var callCount: Long = 0,
    var wallTime: Long = 0,
    var maxCallTime: Long = 0
)

object TreeAlgorithms {
    /**
     * Computes aggregate statistics for each tracepoint,
     * being careful not to double-count the time spent in recursive calls.
     */
    fun computeFlatTracepointStats(root: CallTree): List<TracepointStats> {
        val allStats = mutableMapOf<Tracepoint, TracepointStats>()
        val ancestors = mutableSetOf<Tracepoint>()

        fun bfs(node: CallTree) {
            val nonRecursive = node.tracepoint !in ancestors
            val stats = allStats.getOrPut(node.tracepoint) { TracepointStats(node.tracepoint) }
            stats.callCount += node.callCount
            if (nonRecursive) {
                stats.wallTime += node.wallTime
                stats.maxCallTime = stats.maxCallTime.coerceAtLeast(node.maxCallTime)
                ancestors.add(node.tracepoint)
            }
            for (child in node.children.values) {
                bfs(child)
            }
            if (nonRecursive) {
                ancestors.remove(node.tracepoint)
            }
        }

        bfs(root)
        assert(ancestors.isEmpty())

        return allStats.values.toList()
    }
}
