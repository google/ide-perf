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

package com.google.idea.perf.util

import java.nio.file.Files
import java.nio.file.Paths

private const val NUM_ITERATIONS = 1000

private class TimeSpan(totalNanos: Long) {
    val averageMillis = totalNanos / 1_000_000.0 / NUM_ITERATIONS
}

private typealias ResultSet = List<TimeSpan>

private typealias Benchmark = ArrayList<ResultSet>

private val PATTERNS = listOf(
    // Class names
    "String",
    "ProjectManagerImpl",
    "CachedValue",

    // Canonical class names
    "java.lang.String",
    "com.intellij.openapi.project.impl.ProjectManagerImpl",
    "com.intellij.util.CachedValueBase"
)

private fun FuzzySearcher.benchmarkPattern(
    items: Collection<String>, pattern: String
): List<TimeSpan> {
    val resultSet = ArrayList<TimeSpan>(pattern.length)

    for (i in 1..pattern.length) {
        val subPattern = pattern.substring(0, i)

        val startTime = System.nanoTime()
        for (j in 0 until NUM_ITERATIONS) {
            search(items, subPattern, -1) {}
        }
        val elapsedTime = System.nanoTime() - startTime

        resultSet.add(TimeSpan(elapsedTime))
    }

    return resultSet
}

private fun buildCsv(results: Benchmark): String? {
    val builder = StringBuilder()
    for ((index, pattern) in PATTERNS.withIndex()) {
        builder.append(pattern)
        if (index != PATTERNS.lastIndex) {
            builder.append(',')
        }
    }
    builder.appendln()

    val largestResultSet = results.maxBy { it.size } ?: return null
    for (i in largestResultSet.indices) {
        for (j in PATTERNS.indices) {
            val result = results[j].getOrNull(i)
            if (result != null) {
                builder.append(result.averageMillis)
            }
            if (j != PATTERNS.lastIndex) {
                builder.append(',')
            }
        }
        builder.appendln()
    }

    return builder.toString()
}

fun main() {
    val items = Files.readAllLines(Paths.get("testData", "sampleClasses.txt"))
    val results = Benchmark()

    println("0.0%")

    for ((index, pattern) in PATTERNS.withIndex()) {
        val searcher = FuzzySearcher()
        results.add(searcher.benchmarkPattern(items, pattern))

        val progress = ((index + 1).toDouble() / PATTERNS.size) * 100.0
        println("%.1f%%".format(progress))
    }

    println("--------------------------------------------------")

    val csvContents = buildCsv(results)
    if (csvContents != null) {
        print(csvContents)
    }
    else {
        println("FAILED")
    }
}
