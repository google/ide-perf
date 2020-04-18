package com.android.tools.idea.diagnostics

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
}
