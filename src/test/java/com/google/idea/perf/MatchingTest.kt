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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun assertMatch(expectMatch: Boolean, source: String, pattern: String) {
    val result = fuzzyMatch(source, pattern)
    if (expectMatch) {
        assertNotNull(result)
    }
    else {
        assertNull(result)
    }
}

private fun assertSearch(
    patterns: List<String>,
    expectedMatches: List<String>,
    items: List<String>
) {
    for (pattern in patterns) {
        val matches = fuzzySearch(items, pattern, -1) {}.map { it.source }
        assertTrue(matches.containsAll(expectedMatches))
    }
}

private val idePerfClasses = listOf(
    "com.google.idea.perf.AgentLoader",
    "com.google.idea.perf.CallTreeBuilder",
    "com.google.idea.perf.CallTreeManager",
    "com.google.idea.perf.TracerConfig",
    "com.google.idea.perf.TracerController",
    "com.google.idea.perf.TracerMethodTransformer"
)

private val javaLangClasses = listOf(
    "java.lang.Boolean",
    "java.lang.Byte",
    "java.lang.Character",
    "java.lang.Character\$Subset",
    "java.lang.Character\$UnicodeBlock",
    "java.lang.Class",
    "java.lang.ClassLoader",
    "java.lang.ClassValue",
    "java.lang.Compiler",
    "java.lang.Double",
    "java.lang.Enum",
    "java.lang.Float",
    "java.lang.InheritableThreadLocal",
    "java.lang.Integer",
    "java.lang.Long",
    "java.lang.Math",
    "java.lang.Number",
    "java.lang.Object",
    "java.lang.Package",
    "java.lang.Process",
    "java.lang.ProcessBuilder",
    "java.lang.ProcessBuilder\$Redirect",
    "java.lang.Runtime",
    "java.lang.RuntimePermission",
    "java.lang.SecurityManager",
    "java.lang.Short",
    "java.lang.StackTraceElement",
    "java.lang.StrictMath",
    "java.lang.String",
    "java.lang.StringBuffer",
    "java.lang.StringBuilder",
    "java.lang.System",
    "java.lang.Thread",
    "java.lang.ThreadGroup",
    "java.lang.ThreadLocal",
    "java.lang.Throwable",
    "java.lang.Void"
)

private val allSampleClasses = idePerfClasses + javaLangClasses

@Suppress("SpellCheckingInspection")
class MatchingTest {
    @Test
    fun testFuzzyMatch() {
        assertMatch(true, "java.lang.String", "java.lang.String")
        assertMatch(true, "java.lang.String", "javalangString")
        assertMatch(true, "java.lang.String", "String")
        assertMatch(true, "java.lang.String", "Str")
        assertMatch(true, "java.lang.String", "tring")
        assertMatch(true, "java.lang.String", "ing")
        assertMatch(false, "java.lang.String", "$$$$$$$$$$")
        assertMatch(false, "java.lang.String", "          ")
    }

    @Test
    fun testFuzzySearch() {
        // Package search
        assertSearch(
            listOf(
                "com.google.idea.perf.",
                "com.google.idea.perf",
                "com.google.idea.",
                "com.google.idea",
                "com.google.",
                "com.google"
            ),
            idePerfClasses,
            allSampleClasses
        )

        assertSearch(
            listOf("java.lang.", "java.lang", "java.", "java"),
            javaLangClasses,
            allSampleClasses
        )

        // Class search
        assertSearch(
            listOf("Tracer"),
            listOf(
                "com.google.idea.perf.TracerConfig",
                "com.google.idea.perf.TracerController",
                "com.google.idea.perf.TracerMethodTransformer"
            ),
            allSampleClasses
        )

        assertSearch(
            listOf(
                "java.lang.String",
                "String",
                "Strin",
                "Str",
                "tring",
                "ing"
            ),
            listOf(
                "java.lang.String",
                "java.lang.StringBuffer",
                "java.lang.StringBuilder"
            ),
            allSampleClasses
        )
    }
}
