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

class MatchResult(val source: String, val formattedSource: String) {
    companion object {
        const val MATCHED_RANGE_OPEN_TOKEN = '{'
        const val MATCHED_RANGE_CLOSE_TOKEN = '}'
    }
}

/** Searches on a list of strings based on an approximate pattern. */
fun fuzzySearch(
    sources: Collection<String>,
    pattern: String,
    cancellationCheck: () -> Unit
): List<MatchResult> {
    val results = ArrayList<MatchDetails>()

    for ((index, source) in sources.withIndex()) {
        val match = fuzzyMatchImpl(source, pattern)
        if (match.matchedChars.size >= pattern.length) {
            results.add(match)
        }

        if (index % 256 == 0) {
            cancellationCheck()
        }
    }

    results.sortByDescending { it.score }
    return results.map {
        val builder = StringBuilder(it.source.length * 2)
        var isMatched = false
        var isPrevMatched = isMatched

        for ((index, char) in it.source.withIndex()) {
            isMatched = it.matchedChars.contains(index)
            if (!isPrevMatched && isMatched) {
                builder.append(MatchResult.MATCHED_RANGE_OPEN_TOKEN)
            }
            else if (isPrevMatched && !isMatched) {
                builder.append(MatchResult.MATCHED_RANGE_CLOSE_TOKEN)
            }

            builder.append(char)
            isPrevMatched = isMatched
        }

        if (isPrevMatched) {
            builder.append(MatchResult.MATCHED_RANGE_CLOSE_TOKEN)
        }

        MatchResult(it.source, builder.toString())
    }
}

/**
 * Checks if two strings approximately match with each other. This method is based off of the
 * Smith-Waterman algorithm.
 *
 * @see <a href="https://en.wikipedia.org/wiki/Smith%E2%80%93Waterman_algorithm">Smith-Waterman algorithm</a>
 */
fun fuzzyMatch(source: String, pattern: String): Boolean {
    return fuzzyMatchImpl(source, pattern).matchedChars.size >= pattern.length
}

/*
 * Implementation details
 *
 * Assuming all strings are ASCII, case-insensitive matching should work fine.
 */

/* Base scores */
private const val GAP_SCORE = -1
private const val MATCH_SCORE = -GAP_SCORE * 8
private const val MISMATCH_SCORE = -MATCH_SCORE

/* Constants for super scores */
private const val GAP_RECOVERY_SCORE = MATCH_SCORE * 2

/*
 * Super scores
 *
 * These coefficients represent how many normal matches are required to surpass these super scores.
 * If a super score is MATCH_SCORE*N, then N+1 normal-matched characters are required to surpass the
 * super score.
 */
private const val FIRST_CHAR_SCORE = MATCH_SCORE * 16
private const val DELIMITER_SCORE = MATCH_SCORE * 16
private const val POST_DELIMITER_SCORE = MATCH_SCORE * 4
private const val CAMEL_CASE_SCORE = GAP_RECOVERY_SCORE

private const val ROOT: Byte = 0
private const val LEFT: Byte = 1
private const val UP: Byte = 2
private const val DIAGONAL: Byte = 3

private fun isDelimiter(c: Char) = c == '.' || c == '$' || c == '/'

private fun charEquals(c1: Char, c2: Char) = c1.toLowerCase() == c2.toLowerCase()

private class MatchDetails(val source: String, val matchedChars: List<Int>, val score: Int)

private fun fuzzyMatchImpl(source: String, pattern: String): MatchDetails {
    val scoreMatrix = Array(pattern.length + 1) { IntArray(source.length + 1) }
    val parentMatrix = Array(pattern.length + 1) { ByteArray(source.length + 1) }

    // Construct score and parent matrix
    fun getMatchScore(row: Int, column: Int): Int {
        val char = source[column - 1]
        val patternChar = pattern[row - 1]
        val prevChar = source.getOrElse(column - 2) { ' ' }
        val matchesCase = char == patternChar

        return when {
            !charEquals(char, patternChar) -> MISMATCH_SCORE
            matchesCase && row == 1 && column == 1 -> FIRST_CHAR_SCORE
            prevChar.isLowerCase() && char.isUpperCase() -> CAMEL_CASE_SCORE
            isDelimiter(char) -> DELIMITER_SCORE
            matchesCase && isDelimiter(prevChar) -> POST_DELIMITER_SCORE
            else -> MATCH_SCORE
        }
    }

    for (r in 1..pattern.length) {
        for (c in 1..source.length) {
            val leftScore = scoreMatrix[r][c - 1] + GAP_SCORE
            val upScore = scoreMatrix[r - 1][c] + GAP_SCORE
            val diagonalScore = scoreMatrix[r - 1][c - 1] + getMatchScore(r, c)

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
                if (charEquals(pattern[row], source[column])) {
                    matchedChars.add(column)
                }
            }
        }
    }

    matchedChars.reverse()

    return MatchDetails(source, matchedChars, maxScore)
}
