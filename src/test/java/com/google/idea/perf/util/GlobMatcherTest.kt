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

import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests [GlobMatcher]. */
class GlobMatcherTest {
    companion object {
        @Suppress("SpellCheckingInspection")
        const val STRING = "12345"
    }

    @Test
    fun testPlain() {
        checkMatches("12345")

        checkDoesNotMatch("")
        checkDoesNotMatch("123")
        checkDoesNotMatch("345")
        checkDoesNotMatch("123456")
    }

    @Test
    fun testGlob() {
        checkMatches("*")
        checkMatches("?????")
        checkMatches("12*")
        checkMatches("*45")
        checkMatches("*34*")
        checkMatches("1*5")
        checkMatches("*2*4*")

        checkDoesNotMatch("11*")
        checkDoesNotMatch("*55")
        checkDoesNotMatch("*33*")
        checkDoesNotMatch("11*55")
        checkDoesNotMatch("5*5*5")
    }

    private fun checkMatches(pattern: String): Unit = checkMatch(pattern, true)

    private fun checkDoesNotMatch(pattern: String): Unit = checkMatch(pattern, false)

    private fun checkMatch(pattern: String, expected: Boolean) {
        assertTrue(GlobMatcher.create(pattern).matches(STRING) == expected)
    }
}
