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

package com.google.idea.perf.cachedvaluetracer

import org.junit.Test
import kotlin.test.assertEquals

private fun assertCommand(expected: CachedValueTracerCommand?, actual: String) {
    assertEquals(expected, parseCachedValueTracerCommand(actual))
}

class CachedValueTracerCommandTest {
    @Test
    fun testCommandParser() {
        assertCommand(null, "")
        assertCommand(null, "not-a-command")

        assertCommand(CachedValueTracerCommand.Clear, "clear")
        assertCommand(CachedValueTracerCommand.Reset, "reset")

        assertCommand(
            CachedValueTracerCommand.Filter("com.intellij.openapi"),
            "filter com.intellij.openapi"
        )
        assertCommand(
            CachedValueTracerCommand.Filter(null),
            "filter"
        )

        assertCommand(
            CachedValueTracerCommand.GroupBy(GroupOption.CLASS),
            "group-by class"
        )
        assertCommand(
            CachedValueTracerCommand.GroupBy(GroupOption.STACK_TRACE),
            "group-by stack-trace"
        )
        assertCommand(
            CachedValueTracerCommand.GroupBy(null),
            "group-by"
        )
    }
}
