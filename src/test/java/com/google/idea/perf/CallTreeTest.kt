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

import org.junit.Assert.assertEquals
import org.junit.Test

class CallTreeTest {

    private class Tree(
        override val tracepoint: Tracepoint,
        override val callCount: Long,
        override val wallTime: Long,
        childrenList: List<Tree> = emptyList()
    ) : CallTree {
        override val children = childrenList.associateBy { it.tracepoint }
    }

    @Test
    fun testFlatTracepointStats() {
        val simple1 = Tracepoint("simple1")
        val simple2 = Tracepoint("simple2")
        val simple3 = Tracepoint("simple3")

        val selfRecursion = Tracepoint("selfRecursion")

        val mutualRecursion1 = Tracepoint("mutualRecursion1")
        val mutualRecursion2 = Tracepoint("mutualRecursion2")

        val tree = Tree(Tracepoint.ROOT, 0, 0, listOf(
            // Simple.
            Tree(simple1, 16, 1600, listOf(
                Tree(simple2, 8, 800, listOf(
                    Tree(simple3, 2, 200)
                )),
                Tree(simple3, 4, 400, listOf(
                    Tree(simple2, 1, 100)
                ))
            )),

            // Self recursion.
            Tree(selfRecursion, 4, 400, listOf(
                Tree(selfRecursion, 2, 200, listOf(
                    Tree(selfRecursion, 1, 100)
                ))
            )),

            // Mutual recursion.
            Tree(mutualRecursion1, 1, 800, listOf(
                Tree(mutualRecursion2, 2, 400, listOf(
                    Tree(mutualRecursion1, 4, 200, listOf(
                        Tree(mutualRecursion2, 8, 100)
                    ))
                ))
            ))
        ))

        val allStats = TreeAlgorithms.computeFlatTracepointStats(tree)
            .sortedBy { it.tracepoint.displayName }
            .joinToString(separator = "\n") { stats ->
                with(stats) {
                    "$tracepoint: $callCount calls, $wallTime ns"
                }
            }

        val expected = """
            [root]: 0 calls, 0 ns
            mutualRecursion1: 5 calls, 800 ns
            mutualRecursion2: 10 calls, 400 ns
            selfRecursion: 7 calls, 400 ns
            simple1: 16 calls, 1600 ns
            simple2: 9 calls, 900 ns
            simple3: 6 calls, 600 ns
        """.trimIndent()

        assertEquals(expected, allStats)
    }

    @Test
    fun testCallTreeBuilder() {
        class TestClock : CallTreeBuilder.Clock {
            var time = 0L
            override fun sample(): Long = time
        }

        val clock = TestClock()
        val builder = CallTreeBuilder(clock)

        val simple1 = Tracepoint("simple1")
        val simple2 = Tracepoint("simple2")
        val simple3 = Tracepoint("simple3")

        val selfRecursion = Tracepoint("selfRecursion")

        val mutualRecursion1 = Tracepoint("mutualRecursion1")
        val mutualRecursion2 = Tracepoint("mutualRecursion2")

        // Simple.
        builder.push(simple1)
        builder.push(simple2)
        builder.push(simple3); clock.time++
        builder.pop(simple3)
        builder.pop(simple2); clock.time++
        builder.pop(simple1)

        // Self recursion.
        builder.push(selfRecursion); clock.time++
        builder.push(selfRecursion); clock.time++
        builder.push(selfRecursion); clock.time++
        builder.pop(selfRecursion); clock.time++
        builder.pop(selfRecursion); clock.time++
        builder.pop(selfRecursion)

        // Mutual recursion.
        builder.push(mutualRecursion1); clock.time++
        builder.push(mutualRecursion2); clock.time++
        builder.push(mutualRecursion1); clock.time++
        builder.push(mutualRecursion2); clock.time++
        builder.pop(mutualRecursion2)
        builder.pop(mutualRecursion1)
        builder.pop(mutualRecursion2);
        builder.pop(mutualRecursion1)

        fun StringBuilder.printTree(node: CallTree, indent: String) {
            with(node) {
                appendln("$indent$tracepoint: $callCount calls, $wallTime ns")
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
            [root]: 0 calls, 11 ns
              simple1: 1 calls, 2 ns
                simple2: 1 calls, 1 ns
                  simple3: 1 calls, 1 ns
              selfRecursion: 1 calls, 5 ns
                selfRecursion: 1 calls, 3 ns
                  selfRecursion: 1 calls, 1 ns
              mutualRecursion1: 1 calls, 4 ns
                mutualRecursion2: 1 calls, 3 ns
                  mutualRecursion1: 1 calls, 2 ns
                    mutualRecursion2: 1 calls, 1 ns
            """.trimIndent()
        )

        builder.push(simple1); clock.time++
        builder.push(simple2); clock.time++
        builder.push(simple3); clock.time++
        buildAndCheckTree(
            """
            [root]: 0 calls, 3 ns
              simple1: 1 calls, 3 ns
                simple2: 1 calls, 2 ns
                  simple3: 1 calls, 1 ns
            """.trimIndent()
        )

        clock.time += 10
        buildAndCheckTree(
            """
            [root]: 0 calls, 10 ns
              simple1: 0 calls, 10 ns
                simple2: 0 calls, 10 ns
                  simple3: 0 calls, 10 ns
            """.trimIndent()
        )
    }
}
