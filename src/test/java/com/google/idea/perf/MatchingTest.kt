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

import org.junit.Test
import kotlin.test.assertEquals

private fun assertMatch(matchedChars: List<Int>, source: String, pattern: String) {
    val match = fuzzyMatch(source, pattern)
    assertEquals(matchedChars, match.matchedChars)
}

class MatchingTest {
    @Suppress("SpellCheckingInspection")
    @Test
    fun testFuzzyMatcher() {
        // DNA sequences
        assertMatch(listOf(0, 2, 3), "gcatgcu", "gattaca")
        assertMatch(listOf(3, 4, 5, 6), "accgtga", "gtgaata")
        assertMatch(listOf(0), "agct", "a")
        assertMatch(listOf(1), "agct", "gg")
        assertMatch(listOf(2), "agct", "ccc")
        assertMatch(listOf(3), "agct", "tttt")
        assertMatch(listOf(), "aaaa", "tttt")

        // Java classes
        assertMatch(listOf(10, 11, 12, 13, 14, 15), "java.lang.String", "String")
        assertMatch(listOf(10, 11, 12, 14, 15), "java.lang.String", "Strng")
        assertMatch(listOf(10, 11, 12), "java.lang.String", "Str")

        // Corner cases
        assertMatch(listOf(), "", "")
        assertMatch(listOf(), "a", "")
        assertMatch(listOf(), "", "a")
    }
}
