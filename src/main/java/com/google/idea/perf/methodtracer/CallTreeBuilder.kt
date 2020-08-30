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

// Things to improve:
// - Think about the behavior we want for recursive calls.
// - Keep track of CPU time too by using ManagementFactory.getThreadMXBean().
// - Consider cheaper alternatives to LinkedHashMap (e.g., use list if few elements).
// - Consider caching recent call paths since they are likely to be repeated.
// - Return an object from push() that the caller passes to pop() so that we can assert stack integrity.
// - Consider logging errors instead of throwing exceptions.
// - Consider subtracting buildAndReset() overhead from time measurements.
// - Reduce allocations by keeping old tree nodes around and using a 'dirty' flag.

/** Builds a call tree for a single thread from a sequence of push() and pop() calls. */
class CallTreeBuilder(
    private val clock: Clock = SystemClock
) {
    private var root = Tree(Tracepoint.ROOT, parent = null)
    private var currentNode = root

    init {
        val now = clock.sample()
        root.startWallTime = now
        root.continuedWallTime = now
        root.tracepointFlags = TracepointFlags.TRACE_WALL_TIME
    }

    interface Clock {
        fun sample(): Long
    }

    object SystemClock: Clock {
        override fun sample(): Long = System.nanoTime()
    }

    private class Tree(
        override val tracepoint: Tracepoint,
        val parent: Tree?
    ): CallTree {
        override var callCount: Long = 0L
        override var wallTime: Long = 0L
        override var maxWallTime: Long = 0L
        override val children: MutableMap<Tracepoint, Tree> = LinkedHashMap()

        var startWallTime: Long = 0
        var continuedWallTime: Long = 0
        var tracepointFlags: Int = 0

        init {
            require(parent != null || tracepoint == Tracepoint.ROOT) {
                "Only the root node can have a null parent"
            }
        }
    }

    fun push(tracepoint: Tracepoint) {
        val parent = currentNode
        val child = parent.children.getOrPut(tracepoint) { Tree(tracepoint, parent) }
        val flags = tracepoint.flags.get()

        child.tracepointFlags = flags

        if ((flags and TracepointFlags.TRACE_CALL_COUNT) != 0) {
            child.callCount++
        }

        if ((flags and TracepointFlags.TRACE_WALL_TIME) != 0) {
            val now = clock.sample()
            child.startWallTime = now
            child.continuedWallTime = now
        }

        currentNode = child
    }

    fun pop() {
        val child = currentNode
        val parent = child.parent

        check(parent != null) {
            "The root node should never be popped"
        }

        if ((child.tracepointFlags and TracepointFlags.TRACE_WALL_TIME) != 0) {
            val now = clock.sample()
            val elapsedTime = now - child.continuedWallTime
            val elapsedTimeFromStart = now - child.startWallTime
            child.wallTime += elapsedTime
            child.maxWallTime = maxOf(child.maxWallTime, elapsedTimeFromStart)
        }

        currentNode = parent
    }

    fun buildAndReset(): CallTree {
        // Update timing data for nodes still on the stack.
        val now = clock.sample()
        val stack = generateSequence(currentNode, Tree::parent).toList().asReversed()
        for (node in stack) {
            val elapsedTime = now - node.continuedWallTime
            val elapsedTimeFromStart = now - node.startWallTime

            if ((node.tracepointFlags and TracepointFlags.TRACE_WALL_TIME) != 0) {
                node.wallTime += elapsedTime
                node.maxWallTime = maxOf(node.maxWallTime, elapsedTimeFromStart)
                node.continuedWallTime = now
            }
        }

        // Reset to a new tree and copy over the current stack.
        val oldRoot = root
        root = Tree(Tracepoint.ROOT, parent = null)
        root.startWallTime = oldRoot.startWallTime
        root.continuedWallTime = oldRoot.continuedWallTime
        root.tracepointFlags = oldRoot.tracepointFlags
        currentNode = root
        for (node in stack.subList(1, stack.size)) {
            val copy = Tree(node.tracepoint, parent = currentNode)
            copy.startWallTime = node.startWallTime
            copy.continuedWallTime = node.continuedWallTime
            copy.tracepointFlags = node.tracepointFlags
            currentNode.children[node.tracepoint] = copy
            currentNode = copy
        }

        return oldRoot
    }
}
