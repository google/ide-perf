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

private fun assertMatch(expectMatch: Boolean, source: String, pattern: String) {
    assertEquals(expectMatch, fuzzyMatch(source, pattern))
}

class MatchingTest {
    @Suppress("SpellCheckingInspection")
    @Test
    fun testFuzzyMatcher() {
        assertMatch(true, "java.lang.String", "java.lang.String")
        assertMatch(true, "java.lang.String", "javalangString")
        assertMatch(true, "java.lang.String", "String")
        assertMatch(true, "java.lang.String", "Strng")
        assertMatch(true, "java.lang.String", "Str")
        assertMatch(false, "java.lang.String", "$$$$$$$$$$")
        assertMatch(false, "java.lang.String", "          ")
    }
}
