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

package com.google.idea.perf.tracer

/** A call tree, represented recursively. Also see [CallTreeUtil]. */
interface CallTree {
    val tracepoint: Tracepoint
    val callCount: Long
    val wallTime: Long
    val maxWallTime: Long
    val children: Map<Tracepoint, CallTree>

    fun forEachNodeInSubtree(action: (CallTree) -> Unit) {
        action(this)
        for (child in children.values) {
            child.forEachNodeInSubtree(action)
        }
    }

    fun allNodesInSubtree(): Sequence<CallTree> {
        val nodes = mutableListOf<CallTree>()
        forEachNodeInSubtree { nodes.add(it) }
        return nodes.asSequence()
    }

    fun copy(): CallTree {
        val copy = MutableCallTree(Tracepoint.ROOT)
        copy.accumulate(this)
        return copy
    }
}

/** A mutable call tree implementation. */
class MutableCallTree(
    override val tracepoint: Tracepoint
): CallTree {
    override var callCount: Long = 0L
    override var wallTime: Long = 0L
    override var maxWallTime: Long = 0L
    override val children: MutableMap<Tracepoint, MutableCallTree> = LinkedHashMap()

    /** Accumulates the data from another call tree into this one. */
    fun accumulate(other: CallTree) {
        require(other.tracepoint == tracepoint) {
            "Doesn't make sense to sum call tree nodes representing different tracepoints"
        }

        callCount += other.callCount
        wallTime += other.wallTime
        maxWallTime = maxOf(maxWallTime, other.maxWallTime)

        for ((childTracepoint, otherChild) in other.children) {
            val child = children.getOrPut(childTracepoint) { MutableCallTree(childTracepoint) }
            child.accumulate(otherChild)
        }
    }

    /** Clear data for tree tracepoint: counters and children. */
    fun clear() {
        callCount = 0L
        wallTime = 0L
        maxWallTime = 0L
        children.clear()
    }

    /** Reset counters recursively - for this tracepoint and all children */
    fun resetCounters() {
        callCount = 0L
        wallTime = 0L
        maxWallTime = 0L
        for ((_, child) in children) {
            child.resetCounters()
        }
    }
}
