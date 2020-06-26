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

class ArgSetStats(
    val args: ArgSet,
    var callCount: Long = 0L,
    var wallTime: Long = 0L,
    var maxWallTime: Long = 0L
)

class ArgStatMap private constructor(
    val tracepoints: Map<Tracepoint, List<ArgSetStats>>
) {
    companion object {
        fun fromCallTree(root: CallTree): ArgStatMap {
            val allStats = mutableMapOf<Tracepoint, MutableMap<ArgSet, ArgSetStats>>()
            val ancestors = mutableSetOf<Pair<Tracepoint, ArgSet>>()

            fun dfs(node: CallTree) {
                val scopedAncestors = mutableSetOf<Pair<Tracepoint, ArgSet>>()

                if (node.argSetStats.isNotEmpty()) {
                    val argSetStats = allStats.getOrPut(node.tracepoint) { mutableMapOf() }

                    for ((args, stats) in node.argSetStats) {
                        val nonRecursive = node.tracepoint to args !in ancestors
                        val accumulatedStats = argSetStats.getOrPut(args) { ArgSetStats(args) }
                        accumulatedStats.callCount += stats.callCount

                        if (nonRecursive) {
                            accumulatedStats.wallTime += stats.wallTime
                            accumulatedStats.maxWallTime = maxOf(
                                accumulatedStats.maxWallTime, stats.maxWallTime
                            )
                            scopedAncestors.add(node.tracepoint to args)
                        }
                    }
                }

                ancestors.addAll(scopedAncestors)

                for (child in node.children.values) {
                    dfs(child)
                }

                ancestors.removeAll(scopedAncestors)
            }

            dfs(root)
            assert(ancestors.isEmpty())

            return ArgStatMap(allStats.mapValues { it.value.values.toList() })
        }
    }
}
