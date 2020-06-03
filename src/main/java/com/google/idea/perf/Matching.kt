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

class MatchResult(
    val source: String,
    val matchedChars: List<Int>,
    val score: Int
)

fun fuzzyMatchMany(sources: Collection<String>, pattern: String): List<MatchResult> {
    val matches = ArrayList<MatchResult>(sources.size)

    for (source in sources) {
        matches.add(fuzzyMatch(source, pattern))
    }

    matches.sortByDescending { it.score }
    return matches
}

const val ROOT: Byte = 0
const val LEFT: Byte = 1
const val UP: Byte = 2
const val DIAGONAL: Byte = 3

const val MATCH_SCORE = 1
const val MISMATCH_SCORE = -1
const val GAP_SCORE = -1

fun fuzzyMatch(source: String, pattern: String): MatchResult {
    val scoreMatrix = Array(pattern.length + 1) { IntArray(source.length + 1) }
    val parentMatrix = Array(pattern.length + 1) { ByteArray(source.length + 1) }

    // Construct score and parent matrix
    for (r in 1..pattern.length) {
        for (c in 1..source.length) {
            val leftScore = scoreMatrix[r][c - 1] + GAP_SCORE
            val upScore = scoreMatrix[r - 1][c] + GAP_SCORE
            var diagonalScore = scoreMatrix[r - 1][c - 1]
            diagonalScore += if (pattern[r - 1] == source[c - 1]) MATCH_SCORE else MISMATCH_SCORE

            val maxScore = maxOf(leftScore, upScore, diagonalScore)
            scoreMatrix[r][c] = maxScore

            if (maxScore >= 0) {
                when (maxScore) {
                    diagonalScore -> parentMatrix[r][c] = DIAGONAL
                    upScore -> parentMatrix[r][c] = UP
                    leftScore -> parentMatrix[r][c] = LEFT
                }
            }
        }
    }

    // Get maximum score
    var row = 0
    var column = 0
    var maxScore = Int.MIN_VALUE

    for (r in 0..pattern.length) {
        for (c in 0..source.length) {
            if (scoreMatrix[r][c] > maxScore) {
                maxScore = scoreMatrix[r][c]
                row = r
                column = c
            }
        }
    }

    // Gather matched characters
    val matchedChars = ArrayList<Int>(source.length)

    while (parentMatrix[row][column] != ROOT) {
        when (parentMatrix[row][column]) {
            LEFT -> column--
            UP -> row--
            DIAGONAL -> {
                row--
                column--
                if (pattern[row] == source[column]) {
                    matchedChars.add(column)
                }
            }
        }
    }

    matchedChars.reverse()

    return MatchResult(source, matchedChars, maxScore)
}
