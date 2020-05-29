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

private fun assertCommand(expected: TracerCommand, actual: String) {
    assertEquals(expected, parseTracerCommand(actual))
}

class TracerCommandTest {
    @Test
    fun testCommandParser() {
        assertCommand(TracerCommand.Clear, "clear")
        assertCommand(TracerCommand.Reset, "reset")

        assertCommand(
            TracerCommand.Trace(false, TraceOption.All, TraceTarget.PsiFinders),
            "untrace psi-finders"
        )

        assertCommand(
            TracerCommand.Trace(false, TraceOption.All, TraceTarget.Tracer),
            "untrace tracer"
        )

        assertCommand(
            TracerCommand.Trace(
                false,
                TraceOption.All,
                TraceTarget.Method("com.example.MyAction", "actionPerformed")
            ),
            "untrace com.example.MyAction#actionPerformed"
        )

        assertCommand(
            TracerCommand.Trace(true, TraceOption.All, TraceTarget.PsiFinders),
            "trace psi-finders"
        )

        assertCommand(
            TracerCommand.Trace(true, TraceOption.All, TraceTarget.Tracer),
            "trace tracer"
        )

        assertCommand(
            TracerCommand.Trace(
                true,
                TraceOption.All,
                TraceTarget.Method("com.example.MyAction", "actionPerformed")
            ),
            "trace com.example.MyAction#actionPerformed"
        )
    }
}
