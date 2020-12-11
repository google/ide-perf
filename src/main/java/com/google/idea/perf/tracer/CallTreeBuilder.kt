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

// Things to improve:
// - Think about the behavior we want for recursive calls.
// - Keep track of CPU time too by using ManagementFactory.getThreadMXBean().
// - Consider cheaper alternatives to LinkedHashMap (e.g., use list if few elements).
// - Consider caching recent call paths since they are likely to be repeated.
// - Return an object from push() that the caller passes to pop() so that we can assert stack integrity.
// - Consider logging errors instead of throwing exceptions.

/** Builds a call tree for a single thread from a sequence of [push] and [pop] calls. */
class CallTreeBuilder(clock: Clock = SystemClock) {
    private val clock = ClockWithOverheadAdjustment(clock)
    private var root = Tree(Tracepoint.ROOT, parent = null)
    private var currentNode = root

    interface Clock {
        fun sample(): Long
    }

    private object SystemClock : Clock {
        override fun sample(): Long = System.nanoTime()
    }

    private class ClockWithOverheadAdjustment(val delegate: Clock) : Clock {
        var overhead: Long = 0
        override fun sample(): Long = delegate.sample() - overhead
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
        var continueWallTime: Long = 0
        var wallTimeMeasured: Boolean = false

        init {
            require(parent != null || tracepoint == Tracepoint.ROOT) {
                "Only the root node can have a null parent"
            }
        }
    }

    fun push(tracepoint: Tracepoint) {
        val parent = currentNode
        val child = parent.children.getOrPut(tracepoint) { Tree(tracepoint, parent) }

        child.callCount++

        if (tracepoint.measureWallTime) {
            val now = clock.sample()
            child.startWallTime = now
            child.continueWallTime = now
            child.wallTimeMeasured = true
        } else {
            child.wallTimeMeasured = false
        }

        currentNode = child
    }

    fun pop() {
        val child = currentNode
        val parent = child.parent

        check(parent != null) { "The root node should never be popped" }

        if (child.wallTimeMeasured) {
            val now = clock.sample()
            child.wallTime += now - child.continueWallTime
            child.maxWallTime = maxOf(child.maxWallTime, now - child.startWallTime)
        }

        currentNode = parent
    }

    fun subtractOverhead(overhead: Long) {
        clock.overhead += overhead
    }

    // Returns the current call tree. Careful: it is mutable (not copied).
    fun borrowUpToDateTree(): CallTree {

        // Update timing data for nodes still on the stack.
        val now = clock.sample()
        val stack = generateSequence(currentNode, Tree::parent)
        for (node in stack) {
            if (node.tracepoint != Tracepoint.ROOT && node.wallTimeMeasured) {
                node.wallTime += now - node.continueWallTime
                node.maxWallTime = maxOf(node.maxWallTime, now - node.startWallTime)
                node.continueWallTime = now
            }
        }

        return root
    }

    // Resets to an empty call tree (but keeps the current call stack).
    fun clear() {
        val stack = generateSequence(currentNode, Tree::parent)
        val pathFromRoot = stack.toList().asReversed()
        val tracepoints = pathFromRoot.drop(1).map(Tree::tracepoint) // Excludes the root.

        root = Tree(Tracepoint.ROOT, parent = null)
        currentNode = root
        tracepoints.forEach(::push)
    }
}
