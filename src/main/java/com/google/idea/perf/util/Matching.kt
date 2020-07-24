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

import com.intellij.util.containers.SLRUMap

class MatchResult(val source: String, val formattedSource: String) {
    companion object {
        const val MATCHED_RANGE_OPEN_TOKEN = '{'
        const val MATCHED_RANGE_CLOSE_TOKEN = '}'
    }
}

class FuzzySearcher {
    private val impl = FuzzySearcherImpl()

    /** Searches on a list of strings based on an approximate pattern. */
    fun search(
        sources: Collection<String>,
        pattern: String,
        maxResults: Int,
        cancellationCheck: () -> Unit
    ): List<MatchResult> {
        var results = impl.search(sources, pattern, cancellationCheck)

        if (maxResults >= 0) {
            results = results.take(maxResults)
        }

        return results.map { getMatchResult(it) }
    }
}

/**
 * Checks if two strings approximately match with each other. This method is based off of the
 * Smith-Waterman algorithm.
 *
 * @see <a href="https://en.wikipedia.org/wiki/Smith%E2%80%93Waterman_algorithm">Smith-Waterman algorithm</a>
 */
fun fuzzyMatch(source: String, pattern: String): MatchResult? {
    val details = fuzzyMatchImpl(source, pattern)
    if (details.matchedChars.size < pattern.length) {
        return null
    }
    return getMatchResult(details)
}

private fun getMatchResult(details: MatchDetails): MatchResult {
    val builder = StringBuilder(details.source.length * 2)
    var isMatched = false
    var isPrevMatched = isMatched

    for ((index, char) in details.source.withIndex()) {
        isMatched = details.matchedChars.contains(index)
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

    return MatchResult(details.source, builder.toString())
}

/*
 * Fuzzy matcher implementation
 *
 * Assuming all strings are ASCII, case-insensitive matching should work fine.
 */

/* Base scores */
private const val GAP_SCORE = -1
private const val MATCH_SCORE = -GAP_SCORE * 4
private const val MISMATCH_SCORE = -MATCH_SCORE

/* Constants for super scores */
private const val GAP_RECOVERY_SCORE = MATCH_SCORE

/*
 * Super scores
 *
 * These coefficients represent how many normal matches are required to surpass these super scores.
 * If a super score is MATCH_SCORE*N, then N+1 normal-matched characters are required to surpass the
 * super score.
 */
private const val POST_DELIMITER_SCORE = MATCH_SCORE * 32
private const val FIRST_CHAR_SCORE = MATCH_SCORE * 16
private const val DELIMITER_SCORE = MATCH_SCORE * 16
private const val CAMEL_CASE_SCORE = GAP_RECOVERY_SCORE

/*
 * Minimum number of characters required to use smart match.
 * Usually, users start making typos no earlier than the third character.
 */
private const val SMART_MATCH_MIN_LENGTH = 3

private const val ROOT: Byte = 0
private const val LEFT: Byte = 1
private const val UP: Byte = 2
private const val DIAGONAL: Byte = 3

private fun isDelimiter(c: Char) = c == '.' || c == '$' || c == '/'

private fun charEquals(c1: Char, c2: Char) = c1.toLowerCase() == c2.toLowerCase()

private class MatchDetails(val source: String, val matchedChars: List<Int>, val score: Int)

private fun fuzzyMatchImpl(source: String, pattern: String): MatchDetails {
    if (pattern.isEmpty()) {
        return MatchDetails(source, emptyList(), 0)
    }

    if (pattern.length < SMART_MATCH_MIN_LENGTH) {
        return fastMatch(source, pattern)
    }

    return smartMatch(source, pattern)
}

private fun getCharacterScore(
    source: String, pattern: String, sourceIndex: Int, patternIndex: Int
): Int {
    val char = source[sourceIndex]
    val patternChar = pattern[patternIndex]
    val prevChar = source.getOrElse(sourceIndex - 1) { ' ' }
    val matchesCase = char == patternChar

    return when {
        !charEquals(char, patternChar) -> MISMATCH_SCORE
        matchesCase && patternIndex == 0 && isDelimiter(prevChar) -> POST_DELIMITER_SCORE
        matchesCase && patternIndex == 0 && sourceIndex == 0 -> FIRST_CHAR_SCORE
        prevChar.isLowerCase() && char.isUpperCase() -> CAMEL_CASE_SCORE
        isDelimiter(char) -> DELIMITER_SCORE
        else -> MATCH_SCORE
    }
}

private fun fastMatch(source: String, pattern: String): MatchDetails {
    if (pattern.length > source.length) {
        return MatchDetails(source, emptyList(), 0)
    }

    val firstOffset = source.indexOfFirst { charEquals(it, pattern[0]) }
    val lastOffset = source.indexOfLast { !charEquals(it, pattern[0]) }

    fun getMatch(offset: Int): Pair<Int, Int> {
        if (offset < 0) {
            return Pair(0, 0)
        }

        var totalScore = 0
        var numMatches = 0

        while (numMatches < pattern.length && offset + numMatches < source.length) {
            val score = getCharacterScore(source, pattern, offset + numMatches, numMatches)
            if (score < 0) {
                break
            }
            totalScore += score
            numMatches++
        }

        return Pair(totalScore, numMatches)
    }

    val firstMatch = getMatch(firstOffset)
    val lastMatch = getMatch(lastOffset)
    val bestScore = maxOf(firstMatch.first, lastMatch.first)
    val bestMatch = if (bestScore == firstMatch.first) firstMatch else lastMatch
    val bestStart = if (bestScore == firstMatch.first) firstOffset else lastOffset
    val matchedChars = (bestStart..(bestStart + bestMatch.second)).toList()

    return MatchDetails(source, matchedChars, bestScore)
}

private fun smartMatch(source: String, pattern: String): MatchDetails {
    val scoreMatrix = Array(pattern.length + 1) { IntArray(source.length + 1) }
    val parentMatrix = Array(pattern.length + 1) { ByteArray(source.length + 1) }

    // Construct score and parent matrix
    fun getMatchScore(row: Int, column: Int): Int =
        getCharacterScore(source, pattern, column - 1, row - 1)

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

/*
 * Fuzzy searcher implementation.
 */

private const val PATTERN_CACHE_SIZE = 16
private const val RESULT_CACHE_SIZE = 256
private const val PRUNE_FACTOR = 0.78

private fun fuzzySearchUncached(
    sources: Collection<String>,
    pattern: String,
    cancellationCheck: () -> Unit
): List<MatchDetails> {
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
    return results
}

private typealias ResultCache = SLRUMap<String, List<String>>
private typealias PatternCache = SLRUMap<Collection<String>, ResultCache>

private class FuzzySearcherImpl {
    private val patternCache = PatternCache(
        PATTERN_CACHE_SIZE / 2, PATTERN_CACHE_SIZE - PATTERN_CACHE_SIZE / 2
    )
    private val patternCacheLock = Any()

    fun search(
        sources: Collection<String>,
        pattern: String,
        cancellationCheck: () -> Unit
    ): List<MatchDetails> {
        var selectedSources = sources

        synchronized(patternCacheLock) {
            val cachedPatterns = patternCache[sources]

            if (cachedPatterns != null) {
                for (i in pattern.lastIndex downTo 1) {
                    val patternPrefix = pattern.substring(0, i)
                    val cachedResults = cachedPatterns[patternPrefix]
                    if (cachedResults != null) {
                        selectedSources = cachedResults
                        break
                    }
                }
            }
        }

        val results = fuzzySearchUncached(selectedSources, pattern, cancellationCheck)

        synchronized(patternCacheLock) {
            var cachedPatterns = patternCache[sources]
            if (cachedPatterns == null) {
                cachedPatterns = ResultCache(
                    RESULT_CACHE_SIZE / 2, RESULT_CACHE_SIZE - RESULT_CACHE_SIZE / 2
                )
                patternCache.put(sources, cachedPatterns)
            }

            if (results.size.toDouble() / selectedSources.size < PRUNE_FACTOR) {
                val cachedResults = cachedPatterns[pattern]
                if (cachedResults == null) {
                    cachedPatterns.put(pattern, results.map { it.source })
                }
            }
        }

        return results
    }
}
