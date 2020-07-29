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

import org.junit.Assert.assertEquals
import org.junit.Test

class CallTreeTest {

    private class Tree(
        override val methodCall: MethodCall,
        override val callCount: Long,
        override val wallTime: Long,
        override val maxWallTime: Long,
        childrenList: List<Tree> = emptyList()
    ): CallTree {
        override val children = childrenList.associateBy { it.methodCall }
    }

    class TestClock: CallTreeBuilder.Clock {
        var time = 0L
        override fun sample(): Long = time
    }

    init {
        // Enforce LF line endings for developers on Windows.
        System.setProperty("line.separator", "\n")
    }

    @Test
    fun testFlatTracepointStats() {
        val simple1 = MethodCall(Tracepoint("simple1"), null)
        val simple2 = MethodCall(Tracepoint("simple2"), null)
        val simple3 = MethodCall(Tracepoint("simple3"), null)

        val withArgumentTracepoint = Tracepoint("withArgument")
        val withArgument1 = MethodCall(withArgumentTracepoint, "100")
        val withArgument2 = MethodCall(withArgumentTracepoint, "200")

        val selfRecursion = MethodCall(Tracepoint("selfRecursion"), null)

        val mutualRecursion1 = MethodCall(Tracepoint("mutualRecursion1"), null)
        val mutualRecursion2 = MethodCall(Tracepoint("mutualRecursion2"), null)

        val tree = Tree(MethodCall.ROOT, 0, 0, 0, listOf(
            // Simple.
            Tree(simple1, 16, 1600, 100, listOf(
                Tree(simple2, 8, 800, 100, listOf(
                    Tree(simple3, 2, 200, 150)
                )),
                Tree(simple3, 4, 400, 100, listOf(
                    Tree(simple2, 1, 100, 100)
                ))
            )),

            // With arguments.
            Tree(withArgument1, 10, 600, 100, listOf(
                Tree(withArgument2, 5, 400, 100)
            )),
            Tree(withArgument2, 10, 800, 100),

            // Self recursion.
            Tree(selfRecursion, 4, 400, 200, listOf(
                Tree(selfRecursion, 2, 200, 100, listOf(
                    Tree(selfRecursion, 1, 100, 100)
                ))
            )),

            // Mutual recursion.
            Tree(mutualRecursion1, 1, 800, 800, listOf(
                Tree(mutualRecursion2, 2, 400, 200, listOf(
                    Tree(mutualRecursion1, 4, 200, 50, listOf(
                        Tree(mutualRecursion2, 8, 100, 13)
                    ))
                ))
            ))
        ))

        val allStats = TreeAlgorithms.computeFlatTracepointStats(tree)
            .sortedBy { it.tracepoint.displayName }
            .joinToString(separator = "\n") { stats ->
                with(stats) {
                    "$tracepoint: $callCount calls, $wallTime ns, $maxWallTime ns"
                }
            }

        val expected = """
            [root]: 0 calls, 0 ns, 0 ns
            mutualRecursion1: 5 calls, 800 ns, 800 ns
            mutualRecursion2: 10 calls, 400 ns, 200 ns
            selfRecursion: 7 calls, 400 ns, 200 ns
            simple1: 16 calls, 1600 ns, 100 ns
            simple2: 9 calls, 900 ns, 100 ns
            simple3: 6 calls, 600 ns, 150 ns
            withArgument: 25 calls, 1400 ns, 100 ns
        """.trimIndent()

        assertEquals(expected, allStats)

        val argStats = ArgStatMap.fromCallTree(tree).tracepoints.toList()
            .flatMap { pair -> pair.second.map { pair.first to it } }
            .sortedBy { it.first.displayName }
            .joinToString(separator = "\n") { (tracepoint, stats) ->
                with(stats) {
                    "$tracepoint(${args ?: ""}): $callCount calls, $wallTime ns, $maxWallTime ns"
                }
            }

        val expectedArgStats = """
            [root](): 0 calls, 0 ns, 0 ns
            mutualRecursion1(): 5 calls, 800 ns, 800 ns
            mutualRecursion2(): 10 calls, 400 ns, 200 ns
            selfRecursion(): 7 calls, 400 ns, 200 ns
            simple1(): 16 calls, 1600 ns, 100 ns
            simple2(): 9 calls, 900 ns, 100 ns
            simple3(): 6 calls, 600 ns, 150 ns
            withArgument(100): 10 calls, 600 ns, 100 ns
            withArgument(200): 15 calls, 1200 ns, 100 ns
        """.trimIndent()

        assertEquals(expectedArgStats, argStats)
    }

    @Test
    fun testCallTreeBuilder() {
        val clock = TestClock()
        val builder = CallTreeBuilder(clock)

        val simple1 = MethodCall(Tracepoint("simple1"), null)
        val simple2 = MethodCall(Tracepoint("simple2"), null)
        val simple3 = MethodCall(Tracepoint("simple3"), null)

        val selfRecursion = MethodCall(Tracepoint("selfRecursion"), null)

        val mutualRecursion1 = MethodCall(Tracepoint("mutualRecursion1"), null)
        val mutualRecursion2 = MethodCall(Tracepoint("mutualRecursion2"), null)

        // Simple.
        builder.push(simple1)
        builder.push(simple2)
        builder.push(simple3); clock.time++
        builder.pop(simple3.tracepoint)
        builder.pop(simple2.tracepoint); clock.time++
        builder.pop(simple1.tracepoint)

        // Simple (longer).
        builder.push(simple1)
        builder.push(simple2); clock.time++
        builder.push(simple3); clock.time++
        builder.pop(simple3.tracepoint); clock.time++
        builder.pop(simple2.tracepoint)
        builder.push(simple2); clock.time++
        builder.pop(simple2.tracepoint)
        builder.pop(simple1.tracepoint)

        // Self recursion.
        builder.push(selfRecursion); clock.time++
        builder.push(selfRecursion); clock.time++
        builder.push(selfRecursion); clock.time++
        builder.pop(selfRecursion.tracepoint); clock.time++
        builder.pop(selfRecursion.tracepoint); clock.time++
        builder.pop(selfRecursion.tracepoint)

        // Mutual recursion.
        builder.push(mutualRecursion1); clock.time++
        builder.push(mutualRecursion2); clock.time++
        builder.push(mutualRecursion1); clock.time++
        builder.push(mutualRecursion2); clock.time++
        builder.pop(mutualRecursion2.tracepoint)
        builder.pop(mutualRecursion1.tracepoint)
        builder.pop(mutualRecursion2.tracepoint)
        builder.pop(mutualRecursion1.tracepoint)

        fun StringBuilder.printTree(node: CallTree, indent: String) {
            with(node) {
                appendln("$indent$methodCall: $callCount calls, $wallTime ns, $maxWallTime ns")
            }
            for (child in node.children.values) {
                printTree(child, "$indent  ")
            }
        }

        fun buildAndCheckTree(expected: String) {
            val tree = builder.buildAndReset()
            val treeStr = buildString { printTree(tree, "") }
            assertEquals(expected, treeStr.trim())
        }

        buildAndCheckTree(
            """
            [root](): 0 calls, 15 ns, 15 ns
              simple1(): 2 calls, 6 ns, 4 ns
                simple2(): 3 calls, 5 ns, 3 ns
                  simple3(): 2 calls, 2 ns, 1 ns
              selfRecursion(): 1 calls, 5 ns, 5 ns
                selfRecursion(): 1 calls, 3 ns, 3 ns
                  selfRecursion(): 1 calls, 1 ns, 1 ns
              mutualRecursion1(): 1 calls, 4 ns, 4 ns
                mutualRecursion2(): 1 calls, 3 ns, 3 ns
                  mutualRecursion1(): 1 calls, 2 ns, 2 ns
                    mutualRecursion2(): 1 calls, 1 ns, 1 ns
            """.trimIndent()
        )

        builder.push(simple1); clock.time++
        builder.push(simple2); clock.time++
        builder.push(simple3); clock.time++
        buildAndCheckTree(
            """
            [root](): 0 calls, 3 ns, 18 ns
              simple1(): 1 calls, 3 ns, 3 ns
                simple2(): 1 calls, 2 ns, 2 ns
                  simple3(): 1 calls, 1 ns, 1 ns
            """.trimIndent()
        )

        clock.time += 10
        buildAndCheckTree(
            """
            [root](): 0 calls, 10 ns, 28 ns
              simple1(): 0 calls, 10 ns, 13 ns
                simple2(): 0 calls, 10 ns, 12 ns
                  simple3(): 0 calls, 10 ns, 11 ns
            """.trimIndent()
        )

        builder.pop(simple3.tracepoint); clock.time++
        builder.pop(simple2.tracepoint); clock.time++
        builder.pop(simple1.tracepoint)
        buildAndCheckTree(
            """
            [root](): 0 calls, 2 ns, 30 ns
              simple1(): 0 calls, 2 ns, 15 ns
                simple2(): 0 calls, 1 ns, 13 ns
                  simple3(): 0 calls, 0 ns, 11 ns
            """.trimIndent()
        )
    }

    @Test
    fun testConcurrentTracepointModification() {
        val clock = TestClock()
        val builder = CallTreeBuilder(clock)
        val simple = Tracepoint("simple1", null, TracepointFlags.TRACE_ALL)
        val simpleInstance = MethodCall(simple, null)

        fun StringBuilder.printTree(node: CallTree, indent: String) {
            with(node) {
                appendln("$indent$methodCall: $callCount calls, $wallTime ns, $maxWallTime ns")
            }
            for (child in node.children.values) {
                printTree(child, "$indent  ")
            }
        }

        fun buildAndCheckTree(expected: String) {
            val tree = builder.buildAndReset()
            val treeStr = buildString { printTree(tree, "") }
            assertEquals(expected, treeStr.trim())
        }

        // Given an enabled tracepoint, disable tracepoint midway.
        builder.push(simpleInstance)
        clock.time++
        simple.unsetFlags(TracepointFlags.TRACE_ALL)
        builder.pop(simple)

        buildAndCheckTree(
            """
            [root](): 0 calls, 1 ns, 1 ns
              simple1(): 1 calls, 1 ns, 1 ns
            """.trimIndent()
        )

        // Given a disabled tracepoint, enable tracepoint midway.
        builder.push(simpleInstance)
        clock.time++
        simple.setFlags(TracepointFlags.TRACE_ALL)
        builder.pop(simple)

        buildAndCheckTree(
            """
            [root](): 0 calls, 1 ns, 2 ns
              simple1(): 0 calls, 0 ns, 0 ns
            """.trimIndent()
        )

        // Given an enabled tracepoint, disable tracepoint and build tree midway.
        builder.push(simpleInstance)
        clock.time++
        simple.unsetFlags(TracepointFlags.TRACE_ALL)
        buildAndCheckTree(
            """
            [root](): 0 calls, 1 ns, 3 ns
              simple1(): 1 calls, 1 ns, 1 ns
            """.trimIndent()
        )

        clock.time += 10
        buildAndCheckTree(
            """
            [root](): 0 calls, 10 ns, 13 ns
              simple1(): 0 calls, 10 ns, 11 ns
            """.trimIndent()
        )

        clock.time++
        builder.pop(simple)
        buildAndCheckTree(
            """
            [root](): 0 calls, 1 ns, 14 ns
              simple1(): 0 calls, 1 ns, 12 ns
            """.trimIndent()
        )
    }

    @Test
    fun testCallTreeBuilderWithArgs() {
        val clock = TestClock()
        val builder = CallTreeBuilder(clock)

        val simpleTracepoint = Tracepoint("simple")
        val simple1 = MethodCall(simpleTracepoint, "arg1")
        val simple2 = MethodCall(simpleTracepoint, "arg2")
        val simple3 = MethodCall(simpleTracepoint, "arg3")

        val selfRecursion = MethodCall(Tracepoint("selfRecursion"), "myArg")

        val mutualRecursion1 = MethodCall(Tracepoint("mutualRecursion1"), "myArg")
        val mutualRecursion2 = MethodCall(Tracepoint("mutualRecursion2"), "myArg")

        // Simple.
        builder.push(simple1)
        builder.push(simple2); clock.time++
        builder.push(simple3); clock.time++
        builder.pop(simple3.tracepoint); clock.time++
        builder.pop(simple2.tracepoint)
        builder.push(simple2); clock.time++
        builder.pop(simple2.tracepoint)
        builder.push(simple2); clock.time++
        builder.pop(simple2.tracepoint)
        builder.pop(simple1.tracepoint)

        // Self recursion.
        builder.push(selfRecursion); clock.time++
        builder.push(selfRecursion); clock.time++
        builder.push(selfRecursion); clock.time++
        builder.pop(selfRecursion.tracepoint); clock.time++
        builder.pop(selfRecursion.tracepoint); clock.time++
        builder.pop(selfRecursion.tracepoint)

        // Mutual recursion.
        builder.push(mutualRecursion1); clock.time++
        builder.push(mutualRecursion2); clock.time++
        builder.push(mutualRecursion1); clock.time++
        builder.push(mutualRecursion2); clock.time++
        builder.pop(mutualRecursion2.tracepoint)
        builder.pop(mutualRecursion1.tracepoint)
        builder.pop(mutualRecursion2.tracepoint)
        builder.pop(mutualRecursion1.tracepoint)

        fun CallTree.get(head: MethodCall, vararg tail: MethodCall): CallTree {
            var child = this.children[head] ?: error("$head does not exist.")

            for (methodCall in tail) {
                child = child.children[methodCall] ?: error("$methodCall does not exist.")
            }

            return child
        }

        fun assertStats(
            callCount: Long, wallTime: Long, maxWallTime: Long, tree: CallTree
        ) {
            assertEquals(callCount, tree.callCount)
            assertEquals(wallTime, tree.wallTime)
            assertEquals(maxWallTime, tree.maxWallTime)
        }

        val tree = builder.buildAndReset()

        assertStats(1L, 5L, 5L, tree.get(simple1))
        assertStats(3L, 5L, 3L, tree.get(simple1, simple2))
        assertStats(1L, 1L, 1L, tree.get(simple1, simple2, simple3))

        assertStats(1L, 5L, 5L, tree.get(selfRecursion))
        assertStats(1L, 3L, 3L, tree.get(selfRecursion, selfRecursion))
        assertStats(1L, 1L, 1L, tree.get(selfRecursion, selfRecursion, selfRecursion))

        assertStats(1L, 4L, 4L, tree.get(mutualRecursion1))
        assertStats(1L, 2L, 2L, tree.get(mutualRecursion1, mutualRecursion2, mutualRecursion1))
    }
}
