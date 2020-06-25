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

class ParameterValueListStats(
    val args: ParameterValueList,
    var callCount: Long = 0L,
    var wallTime: Long = 0L,
    var maxWallTime: Long = 0L
)

class ParameterValueStatMap private constructor(
    val tracepoints: Map<Tracepoint, List<ParameterValueListStats>>
) {
    companion object {
        fun fromCallTree(root: CallTree): ParameterValueStatMap {
            val allStats = mutableMapOf<Tracepoint, MutableMap<ParameterValueList, ParameterValueListStats>>()
            val ancestors = mutableSetOf<Tracepoint>()

            fun dfs(node: CallTree) {
                val nonRecursive = node.tracepoint !in ancestors

                if (node.parameterValues.isNotEmpty()) {
                    val pvStats = allStats.getOrPut(node.tracepoint) { mutableMapOf() }

                    for ((args, stats) in node.parameterValues) {
                        val accumulatedStats = pvStats.getOrPut(args) {
                            ParameterValueListStats(args)
                        }
                        accumulatedStats.callCount += stats.callCount

                        if (nonRecursive) {
                            accumulatedStats.wallTime += stats.wallTime
                            accumulatedStats.maxWallTime = maxOf(
                                accumulatedStats.maxWallTime, stats.maxWallTime
                            )
                        }
                    }
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

            return ParameterValueStatMap(allStats.mapValues { it.value.values.toList() })
        }
    }
}
