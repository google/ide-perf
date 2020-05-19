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

// Things to improve:
// - Think about the behavior we want for recursive calls.
// - Keep track of CPU time too by using ManagementFactory.getThreadMXBean().
// - Consider cheaper alternatives to LinkedHashMap (e.g., use list if few elements).
// - Consider caching recent call paths since they are likely to be repeated.
// - Return an object from push() so that we can have better assertions in pop().
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
        root.continuedWallTime = clock.sample()
    }

    interface Clock {
        fun sample(): Long
    }

    object SystemClock : Clock {
        override fun sample(): Long = System.nanoTime()
    }

    private class Tree(
        override val tracepoint: Tracepoint,
        val parent: Tree?
    ) : CallTree {
        override var callCount: Long = 0
        override var wallTime: Long = 0
        override var maxCallTime: Long = 0
        override val children: MutableMap<Tracepoint, Tree> = LinkedHashMap()

        var startWallTime: Long = 0
        var continuedWallTime: Long = 0

        init {
            require(parent != null || tracepoint == Tracepoint.ROOT) {
                "Only the root node can have a null parent"
            }
        }
    }

    fun push(tracepoint: Tracepoint) {
        val parent = currentNode
        val child = parent.children.getOrPut(tracepoint) { Tree(tracepoint, parent) }
        val now = clock.sample()

        child.callCount++
        child.startWallTime = now
        child.continuedWallTime = now
        currentNode = child
    }

    fun pop(tracepoint: Tracepoint) {
        val child = currentNode
        val parent = child.parent

        check(parent != null) {
            "The root node should never be popped"
        }

        check(tracepoint == child.tracepoint) {
            """
            This pop() call does not match the current tracepoint on the stack.
            Did someone call push() without later calling pop()?
            Tracepoint passed to pop(): $tracepoint
            Current tracepoint on the stack: ${child.tracepoint}
            """.trimIndent()
        }

        val now = clock.sample()
        child.wallTime += now - child.continuedWallTime
        child.maxCallTime = child.maxCallTime.coerceAtLeast(now - child.startWallTime)

        currentNode = parent
    }

    fun buildAndReset(): CallTree {
        // Update timing data for nodes still on the stack.
        val now = clock.sample()
        val stack = generateSequence(currentNode, Tree::parent).toList().asReversed()
        for (node in stack) {
            val elapsedTime = now - node.continuedWallTime
            node.wallTime += elapsedTime
            node.continuedWallTime = now
            node.maxCallTime = node.maxCallTime.coerceAtLeast(now - node.startWallTime)
        }

        // Reset to a new tree and copy over the current stack.
        val oldRoot = root
        root = Tree(Tracepoint.ROOT, parent = null)
        root.startWallTime = oldRoot.startWallTime
        root.continuedWallTime = oldRoot.continuedWallTime
        currentNode = root
        for (node in stack.subList(1, stack.size)) {
            val copy = Tree(node.tracepoint, parent = currentNode)
            copy.startWallTime = node.startWallTime
            copy.continuedWallTime = node.continuedWallTime
            currentNode.children[node.tracepoint] = copy
            currentNode = copy
        }

        return oldRoot
    }
}
