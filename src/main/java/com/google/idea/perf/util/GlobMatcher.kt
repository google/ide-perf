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

import com.intellij.util.PatternUtil
import com.intellij.util.text.Matcher

object GlobMatcher {

    /**
     * Returns a string matcher for [pattern], where [pattern] may contain '*' and '?' wildcards.
     * The matcher can be 10x faster than regex matching for certain kinds of patterns.
     */
    fun create(pattern: String): Matcher {
        val stars = pattern.count { it == '*' }
        return when {
            pattern == "*" -> AnyMatcher
            pattern.contains('?') -> RegexMatcher(pattern)
            stars == 0 -> PlainMatcher(pattern)
            stars == 1 -> when {
                pattern.startsWith('*') -> SuffixMatcher(pattern.removePrefix("*"))
                pattern.endsWith('*') -> PrefixMatcher(pattern.removeSuffix("*"))
                else -> {
                    val (prefix, suffix) = pattern.split('*')
                    PrefixSuffixMatcher(prefix, suffix)
                }
            }
            stars == 2 && pattern.startsWith('*') && pattern.endsWith('*') -> {
                InfixMatcher(pattern.removePrefix("*").removeSuffix("*"))
            }
            else -> RegexMatcher(pattern)
        }
    }

    private object AnyMatcher : Matcher {
        override fun matches(s: String): Boolean = true
    }

    private class PlainMatcher(private val pattern: String) : Matcher {
        override fun matches(s: String): Boolean = s == pattern
    }

    private class PrefixMatcher(private val prefix: String) : Matcher {
        override fun matches(s: String): Boolean = s.startsWith(prefix)
    }

    private class SuffixMatcher(private val suffix: String) : Matcher {
        override fun matches(s: String): Boolean = s.endsWith(suffix)
    }

    private class PrefixSuffixMatcher(
        private val prefix: String,
        private val suffix: String
    ) : Matcher {
        override fun matches(s: String): Boolean = s.startsWith(prefix) && s.endsWith(suffix)
    }

    private class InfixMatcher(private val infix: String): Matcher {
        override fun matches(s: String): Boolean = s.contains(infix)
    }

    private class RegexMatcher(pattern: String) : Matcher {
        private val regexMatcher = PatternUtil.fromMask(pattern).matcher("")

        @Synchronized // regexMatcher is not thread safe.
        override fun matches(s: String): Boolean = regexMatcher.reset(s).matches()
    }
}
