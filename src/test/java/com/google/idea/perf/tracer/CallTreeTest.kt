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

import org.junit.Assert.assertEquals
import org.junit.Test

class CallTreeTest {

    private class Tree(
        override val tracepoint: Tracepoint,
        override val callCount: Long,
        override val wallTime: Long,
        override val maxWallTime: Long,
        childrenList: List<Tree> = emptyList()
    ): CallTree {
        override val children = childrenList.associateBy { it.tracepoint }
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
        val simple1 = SimpleTracepoint("simple1")
        val simple2 = SimpleTracepoint("simple2")
        val simple3 = SimpleTracepoint("simple3")

        val withArgsMethod = SimpleTracepoint("withArgument")
        val withArg1 = MethodTracepointWithArgs(withArgsMethod, arrayOf("100"))
        val withArg2 = MethodTracepointWithArgs(withArgsMethod, arrayOf("200"))

        val selfRecursion = SimpleTracepoint("selfRecursion")

        val mutualRecursion1 = SimpleTracepoint("mutualRecursion1")
        val mutualRecursion2 = SimpleTracepoint("mutualRecursion2")

        val tree = Tree(Tracepoint.ROOT, 0, 0, 0, listOf(
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
            Tree(withArg1, 10, 600, 100, listOf(
                Tree(withArg2, 5, 400, 100)
            )),
            Tree(withArg2, 10, 800, 100),

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

        val allStats = CallTreeUtil.computeFlatTracepointStats(tree)
            .sortedBy { it.tracepoint.displayName }
            .joinToString(separator = "\n") { stats ->
                with(stats) {
                    "${tracepoint.displayName}: $callCount calls, $wallTime ns, $maxWallTime ns"
                }
            }

        val expected = """
            mutualRecursion1: 5 calls, 800 ns, 800 ns
            mutualRecursion2: 10 calls, 400 ns, 200 ns
            selfRecursion: 7 calls, 400 ns, 200 ns
            simple1: 16 calls, 1600 ns, 100 ns
            simple2: 9 calls, 900 ns, 100 ns
            simple3: 6 calls, 600 ns, 150 ns
            withArgument: 100: 10 calls, 600 ns, 100 ns
            withArgument: 200: 15 calls, 1200 ns, 100 ns
        """.trimIndent()

        assertEquals(expected, allStats)
    }

    @Test
    fun testCallTreeBuilder() {
        val clock = TestClock()
        val builder = CallTreeBuilder(clock)

        val simple1 = SimpleTracepoint("simple1")
        val simple2 = SimpleTracepoint("simple2")
        val simple3 = SimpleTracepoint("simple3")

        val selfRecursion = SimpleTracepoint("selfRecursion")

        val mutualRecursion1 = SimpleTracepoint("mutualRecursion1")
        val mutualRecursion2 = SimpleTracepoint("mutualRecursion2")

        // Simple.
        builder.push(simple1)
        builder.push(simple2)
        builder.push(simple3); clock.time++
        builder.pop()
        builder.pop(); clock.time++
        builder.pop()

        // Simple (longer).
        builder.push(simple1)
        builder.push(simple2); clock.time++
        builder.push(simple3); clock.time++
        builder.pop(); clock.time++
        builder.pop()
        builder.push(simple2); clock.time++
        builder.pop()
        builder.pop()

        // Self recursion.
        builder.push(selfRecursion); clock.time++
        builder.push(selfRecursion); clock.time++
        builder.push(selfRecursion); clock.time++
        builder.pop(); clock.time++
        builder.pop(); clock.time++
        builder.pop()

        // Mutual recursion.
        builder.push(mutualRecursion1); clock.time++
        builder.push(mutualRecursion2); clock.time++
        builder.push(mutualRecursion1); clock.time++
        builder.push(mutualRecursion2); clock.time++
        builder.pop()
        builder.pop()
        builder.pop()
        builder.pop()

        fun buildAndCheckTree(expected: String) = buildAndCheckTree(builder, expected)

        buildAndCheckTree(
            """
            [root]: 0 calls, 0 ns, 0 ns
              simple1: 2 calls, 6 ns, 4 ns
                simple2: 3 calls, 5 ns, 3 ns
                  simple3: 2 calls, 2 ns, 1 ns
              selfRecursion: 1 calls, 5 ns, 5 ns
                selfRecursion: 1 calls, 3 ns, 3 ns
                  selfRecursion: 1 calls, 1 ns, 1 ns
              mutualRecursion1: 1 calls, 4 ns, 4 ns
                mutualRecursion2: 1 calls, 3 ns, 3 ns
                  mutualRecursion1: 1 calls, 2 ns, 2 ns
                    mutualRecursion2: 1 calls, 1 ns, 1 ns
            """.trimIndent()
        )

        builder.push(simple1); clock.time++
        builder.push(simple2); clock.time++
        builder.push(simple3); clock.time++
        buildAndCheckTree(
            """
            [root]: 0 calls, 0 ns, 0 ns
              simple1: 1 calls, 3 ns, 3 ns
                simple2: 1 calls, 2 ns, 2 ns
                  simple3: 1 calls, 1 ns, 1 ns
            """.trimIndent()
        )

        clock.time += 10
        buildAndCheckTree(
            """
            [root]: 0 calls, 0 ns, 0 ns
              simple1: 1 calls, 10 ns, 10 ns
                simple2: 1 calls, 10 ns, 10 ns
                  simple3: 1 calls, 10 ns, 10 ns
            """.trimIndent()
        )

        clock.time += 10
        builder.subtractOverhead(5)
        buildAndCheckTree(
            """
            [root]: 0 calls, 0 ns, 0 ns
              simple1: 1 calls, 5 ns, 5 ns
                simple2: 1 calls, 5 ns, 5 ns
                  simple3: 1 calls, 5 ns, 5 ns
            """.trimIndent()
        )

        builder.pop(); clock.time++
        builder.pop(); clock.time++
        builder.pop()
        buildAndCheckTree(
            """
            [root]: 0 calls, 0 ns, 0 ns
              simple1: 1 calls, 2 ns, 2 ns
                simple2: 1 calls, 1 ns, 1 ns
                  simple3: 1 calls, 0 ns, 0 ns
            """.trimIndent()
        )
    }

    private fun StringBuilder.printTree(node: CallTree, indent: String) {
        with(node) {
            appendLine("$indent$tracepoint: $callCount calls, $wallTime ns, $maxWallTime ns")
        }
        for (child in node.children.values) {
            printTree(child, "$indent  ")
        }
    }

    private fun buildAndCheckTree(builder: CallTreeBuilder, expected: String) {
        val tree = builder.borrowUpToDateTree()
        val treeStr = buildString { printTree(tree, "") }
        assertEquals(expected, treeStr.trim())
        builder.clear()
    }
}
