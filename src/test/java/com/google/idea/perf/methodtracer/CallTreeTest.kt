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
        override val tracepoint: Tracepoint,
        callCount: Long,
        wallTime: Long,
        maxWallTime: Long,
        override val argSetStats: Map<ArgSet, Stats> = emptyMap(),
        childrenList: List<Tree> = emptyList()
    ) : CallTree {

        override val stats = Stats(callCount, wallTime, maxWallTime)
        override val children = childrenList.associateBy { it.tracepoint }
    }

    data class Stats(
        override val callCount: Long,
        override val wallTime: Long,
        override val maxWallTime: Long
    ): CallTree.Stats

    private fun newArgSet(vararg values: Any?): ArgSet =
        ArgSet(values.mapIndexed { i, value -> Argument(value, i.toByte()) }.toTypedArray())

    class TestClock : CallTreeBuilder.Clock {
        var time = 0L
        override fun sample(): Long = time
    }

    init {
        // Enforce LF line endings for developers on Windows.
        System.setProperty("line.separator", "\n")
    }

    @Test
    fun testFlatTracepointStats() {
        val simple1 = Tracepoint("simple1")
        val simple2 = Tracepoint("simple2")
        val simple3 = Tracepoint("simple3")

        val selfRecursion = Tracepoint("selfRecursion")

        val mutualRecursion1 = Tracepoint("mutualRecursion1")
        val mutualRecursion2 = Tracepoint("mutualRecursion2")

        val tree = Tree(Tracepoint.ROOT, 0, 0, 0, emptyMap(), listOf(
            // Simple.
            Tree(simple1, 16, 1600, 100,
                mapOf(newArgSet(null) to Stats(8, 800, 100)),
                listOf(
                    Tree(simple2, 8, 800, 100,
                        mapOf(
                            newArgSet("arg0", "foo") to Stats(4, 600, 20),
                            newArgSet("arg0", "bar") to Stats(8, 400, 40)
                        ),
                        listOf(
                            Tree(simple3, 2, 200, 150)
                        )
                    ),
                    Tree(simple3, 4, 400, 100, emptyMap(),
                        listOf(
                            Tree(simple2, 1, 100, 100,
                                mapOf(newArgSet("arg0", "foo") to Stats(4, 600, 40))
                            )
                        )
                    )
                )
            ),

            // Self recursion.
            Tree(selfRecursion, 4, 400, 200,
                mapOf(
                    newArgSet("arg0", "foo") to Stats(4, 600, 20),
                    newArgSet("arg0", "bar") to Stats(8, 400, 40)
                ),
                listOf(
                    Tree(selfRecursion, 2, 200, 100,
                        mapOf(newArgSet("arg0", "bar") to Stats(8, 200, 20)),
                        listOf(
                            Tree(selfRecursion, 1, 100, 100)
                        )
                    )
                )
            ),

            // Mutual recursion.
            Tree(mutualRecursion1, 1, 800, 800,
                mapOf(
                    newArgSet("arg0", "foo") to Stats(4, 600, 20),
                    newArgSet("arg0", "bar") to Stats(8, 400, 40)
                ),
                listOf(
                    Tree(mutualRecursion2, 2, 400, 200, emptyMap(),
                        listOf(
                            Tree(mutualRecursion1, 4, 200, 50,
                                mapOf(newArgSet("arg0", "bar") to Stats(8, 200, 20)),
                                listOf(
                                    Tree(mutualRecursion2, 8, 100, 13)
                                )
                            )
                        )
                    )
                )
            )
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
        """.trimIndent()

        assertEquals(expected, allStats)

        val argSetStats = ArgStatMap.fromCallTree(tree).tracepoints.toList()
            .flatMap { pair -> pair.second.map { pair.first to it } }
            .sortedBy { it.first.displayName }
            .joinToString(separator = "\n") { (tracepoint, stats) ->
                with (stats) {
                    "$tracepoint($args): $callCount calls, $wallTime ns, $maxWallTime ns"
                }
            }

        val expectedArgStats = """
            mutualRecursion1(arg0, foo): 4 calls, 600 ns, 20 ns
            mutualRecursion1(arg0, bar): 16 calls, 400 ns, 40 ns
            selfRecursion(arg0, foo): 4 calls, 600 ns, 20 ns
            selfRecursion(arg0, bar): 16 calls, 400 ns, 40 ns
            simple1(null): 8 calls, 800 ns, 100 ns
            simple2(arg0, foo): 8 calls, 1200 ns, 40 ns
            simple2(arg0, bar): 8 calls, 400 ns, 40 ns
        """.trimIndent()

        assertEquals(expectedArgStats, argSetStats)
    }

    @Test
    fun testCallTreeBuilder() {
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

        // Simple (longer).
        builder.push(simple1)
        builder.push(simple2); clock.time++
        builder.push(simple3); clock.time++
        builder.pop(simple3); clock.time++
        builder.pop(simple2)
        builder.push(simple2); clock.time++
        builder.pop(simple2)
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
        builder.pop(mutualRecursion2)
        builder.pop(mutualRecursion1)

        fun StringBuilder.printTree(node: CallTree, indent: String) {
            val tracepoint = node.tracepoint
            with(node.stats) {
                appendln("$indent$tracepoint: $callCount calls, $wallTime ns, $maxWallTime ns")
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
            [root]: 0 calls, 15 ns, 15 ns
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
            [root]: 0 calls, 3 ns, 18 ns
              simple1: 1 calls, 3 ns, 3 ns
                simple2: 1 calls, 2 ns, 2 ns
                  simple3: 1 calls, 1 ns, 1 ns
            """.trimIndent()
        )

        clock.time += 10
        buildAndCheckTree(
            """
            [root]: 0 calls, 10 ns, 28 ns
              simple1: 0 calls, 10 ns, 13 ns
                simple2: 0 calls, 10 ns, 12 ns
                  simple3: 0 calls, 10 ns, 11 ns
            """.trimIndent()
        )

        builder.pop(simple3); clock.time++
        builder.pop(simple2); clock.time++
        builder.pop(simple1)
        buildAndCheckTree(
            """
            [root]: 0 calls, 2 ns, 30 ns
              simple1: 0 calls, 2 ns, 15 ns
                simple2: 0 calls, 1 ns, 13 ns
                  simple3: 0 calls, 0 ns, 11 ns
            """.trimIndent()
        )
    }

    @Test
    fun testConcurrentTracepointModification() {
        val clock = TestClock()
        val builder = CallTreeBuilder(clock)
        val simple = Tracepoint("simple1", null, TracepointFlags.TRACE_ALL)

        fun StringBuilder.printTree(node: CallTree, indent: String) {
            val tracepoint = node.tracepoint
            with(node.stats) {
                appendln("$indent$tracepoint: $callCount calls, $wallTime ns, $maxWallTime ns")
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
        builder.push(simple)
        clock.time++
        simple.unsetFlags(TracepointFlags.TRACE_ALL)
        builder.pop(simple)

        buildAndCheckTree(
            """
            [root]: 0 calls, 1 ns, 1 ns
              simple1: 1 calls, 1 ns, 1 ns
            """.trimIndent()
        )

        // Given a disabled tracepoint, enable tracepoint midway.
        builder.push(simple)
        clock.time++
        simple.setFlags(TracepointFlags.TRACE_ALL)
        builder.pop(simple)

        buildAndCheckTree(
            """
            [root]: 0 calls, 1 ns, 2 ns
              simple1: 0 calls, 0 ns, 0 ns
            """.trimIndent()
        )

        // Given an enabled tracepoint, disable tracepoint and build tree midway.
        builder.push(simple)
        clock.time++
        simple.unsetFlags(TracepointFlags.TRACE_ALL)
        buildAndCheckTree(
            """
            [root]: 0 calls, 1 ns, 3 ns
              simple1: 1 calls, 1 ns, 1 ns
            """.trimIndent()
        )

        clock.time += 10
        buildAndCheckTree(
            """
            [root]: 0 calls, 10 ns, 13 ns
              simple1: 0 calls, 10 ns, 11 ns
            """.trimIndent()
        )

        clock.time++
        builder.pop(simple)
        buildAndCheckTree(
            """
            [root]: 0 calls, 1 ns, 14 ns
              simple1: 0 calls, 1 ns, 12 ns
            """.trimIndent()
        )
    }

    @Test
    fun testCallTreeBuilderWithArgs() {
        fun CallTreeBuilder.push(tracepoint: Tracepoint, vararg args: Any?) {
            push(
                tracepoint,
                args.mapIndexed { i, value -> Argument(value, i.toByte()) }.toTypedArray()
            )
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
        builder.push(simple2, "arg0", "foo"); clock.time++
        builder.push(simple3); clock.time++
        builder.pop(simple3); clock.time++
        builder.pop(simple2)
        builder.push(simple2, "arg0", "foo"); clock.time++
        builder.pop(simple2)
        builder.push(simple2, "arg0", "bar"); clock.time++
        builder.pop(simple2)
        builder.pop(simple1)

        // Self recursion.
        builder.push(selfRecursion, "myArg"); clock.time++
        builder.push(selfRecursion, "myArg"); clock.time++
        builder.push(selfRecursion, "myArg"); clock.time++
        builder.pop(selfRecursion); clock.time++
        builder.pop(selfRecursion); clock.time++
        builder.pop(selfRecursion)

        // Mutual recursion.
        builder.push(mutualRecursion1, "myArg"); clock.time++
        builder.push(mutualRecursion2); clock.time++
        builder.push(mutualRecursion1, "myArg"); clock.time++
        builder.push(mutualRecursion2); clock.time++
        builder.pop(mutualRecursion2)
        builder.pop(mutualRecursion1)
        builder.pop(mutualRecursion2)
        builder.pop(mutualRecursion1)

        fun CallTree.get(firstTracepoint: Tracepoint, vararg tracepoints: Tracepoint): CallTree {
            var child = this.children[firstTracepoint] ?: error("$firstTracepoint does not exist.")

            for (tracepoint in tracepoints) {
                child = child.children[tracepoint] ?: error("$tracepoint does not exist.")
            }

            return child
        }

        fun assertStats(
            callCount: Long, wallTime: Long, maxWallTime: Long, tree: CallTree, vararg args: Any?
        ) {
            val actualStats = tree.argSetStats[newArgSet(*args)]
            assertEquals(callCount, actualStats?.callCount)
            assertEquals(wallTime, actualStats?.wallTime)
            assertEquals(maxWallTime, actualStats?.maxWallTime)
        }

        val tree = builder.buildAndReset()

        assertStats(2L, 4L, 3L, tree.get(simple1, simple2), "arg0", "foo")
        assertStats(1L, 1L, 1L, tree.get(simple1, simple2), "arg0", "bar")

        assertStats(1L, 5L, 5L, tree.get(selfRecursion), "myArg")
        assertStats(1L, 3L, 3L, tree.get(selfRecursion, selfRecursion), "myArg")
        assertStats(1L, 1L, 1L, tree.get(selfRecursion, selfRecursion, selfRecursion), "myArg")

        assertStats(1L, 4L, 4L, tree.get(mutualRecursion1), "myArg")
        assertStats(1L, 2L, 2L, tree.get(mutualRecursion1, mutualRecursion2, mutualRecursion1), "myArg")
    }
}
