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

/** Encapsulates aggregate statistic for a single tracepoint. */
class TracepointStats(
    val tracepoint: Tracepoint,
    var callCount: Long = 0L,
    var wallTime: Long = 0L,
    var maxWallTime: Long = 0L
)

object TreeAlgorithms {
    /**
     * Computes aggregate statistics for each tracepoint,
     * being careful not to double-count the time spent in recursive calls.
     */
    fun computeFlatTracepointStats(root: CallTree): List<TracepointStats> {
        val allStats = mutableMapOf<Tracepoint, TracepointStats>()
        val ancestors = mutableSetOf<Tracepoint>()

        fun dfs(node: CallTree) {
            val nonRecursive = node.tracepoint !in ancestors
            val stats = allStats.getOrPut(node.tracepoint) { TracepointStats(node.tracepoint) }
            stats.callCount += node.callCount
            if (nonRecursive) {
                stats.wallTime += node.wallTime
                stats.maxWallTime = maxOf(stats.maxWallTime, node.maxWallTime)
                ancestors.add(node.tracepoint)
            }
            for (child in node.children.values) {
                dfs(child)
            }
            if (nonRecursive) {
                ancestors.remove(node.tracepoint)
            }
        }

        dfs(root)
        assert(ancestors.isEmpty())

        return allStats.values.toList()
    }
}
