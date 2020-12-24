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

package com.google.idea.perf.tracer

import org.junit.Test
import kotlin.test.assertEquals

private typealias Unknown = TracerCommand.Unknown
private typealias Clear = TracerCommand.Clear
private typealias Reset = TracerCommand.Reset
private typealias Trace = TracerCommand.Trace

private fun assertCommand(expected: TracerCommand, actual: String) {
    assertEquals(expected, parseMethodTracerCommand(actual))
}

class TracerCommandParserTest {
    @Test
    fun testCommandParser() {
        // Basic commands.
        assertCommand(Unknown, "unknown-command")
        assertCommand(Clear, "clear")
        assertCommand(Reset, "reset")

        // Corner case: Leading and trailing whitespace.
        assertCommand(Clear, "clear  ")
        assertCommand(Clear, "  clear")
        assertCommand(Clear, "  clear  ")

        // Basic untrace commands.
        assertCommand(Trace(false, TraceOption.COUNT_AND_WALL_TIME, null), "untrace")
        assertCommand(Trace(false, TraceOption.COUNT_ONLY, null), "untrace count")

        assertCommand(Trace(false, TraceOption.COUNT_AND_WALL_TIME, TraceTarget.PsiFinders), "untrace psi-finders")
        assertCommand(Trace(false, TraceOption.COUNT_AND_WALL_TIME, TraceTarget.PsiFinders), "untrace all psi-finders")

        // Wildcard untrace commands.
        assertCommand(Trace(false, TraceOption.COUNT_AND_WALL_TIME, TraceTarget.All), "untrace *")
        assertCommand(Trace(false, TraceOption.COUNT_AND_WALL_TIME, TraceTarget.Method("Test*", "*")), "untrace Test*")
        assertCommand(Trace(false, TraceOption.COUNT_AND_WALL_TIME, TraceTarget.Method("Test", "*")), "untrace Test#*")
        assertCommand(Trace(false, TraceOption.COUNT_AND_WALL_TIME, TraceTarget.Method("Test", "get*")), "untrace Test#get*")

        // Method untrace commands.
        assertCommand(
            Trace(false, TraceOption.COUNT_AND_WALL_TIME, TraceTarget.Method("com.example.MyAction", "actionPerformed", emptyList())),
            "untrace com.example.MyAction#actionPerformed"
        )
        assertCommand(
            Trace(
                false,
                TraceOption.COUNT_ONLY,
                TraceTarget.Method("com.example.MyAction", "actionPerformed", emptyList())
            ),
            "untrace count com.example.MyAction#actionPerformed"
        )
        assertCommand(
            Trace(false, TraceOption.COUNT_AND_WALL_TIME, TraceTarget.Method("com.example.MyAction", "actionPerformed", listOf(0))),
            "untrace all com.example.MyAction#actionPerformed[0]"
        )
        assertCommand(
            Trace(false, TraceOption.COUNT_AND_WALL_TIME, TraceTarget.Method("com.example.MyAction", "actionPerformed", listOf(0, 1))),
            "untrace all com.example.MyAction#actionPerformed[0,1]"
        )

        // Basic trace commands.
        assertCommand(Trace(true, TraceOption.COUNT_AND_WALL_TIME, null), "trace")
        assertCommand(Trace(true, TraceOption.COUNT_ONLY, null), "trace count")
        assertCommand(Trace(true, TraceOption.COUNT_AND_WALL_TIME, TraceTarget.PsiFinders), "trace psi-finders")
        assertCommand(Trace(true, TraceOption.COUNT_ONLY, TraceTarget.PsiFinders), "trace count psi-finders")

        // Wildcard trace commands.
        assertCommand(Trace(true, TraceOption.COUNT_AND_WALL_TIME, TraceTarget.All), "trace *")
        assertCommand(Trace(true, TraceOption.COUNT_AND_WALL_TIME, TraceTarget.Method("*Test", "*")), "trace *Test")
        assertCommand(Trace(true, TraceOption.COUNT_AND_WALL_TIME, TraceTarget.Method("Test*", "*")), "trace Test*")
        assertCommand(Trace(true, TraceOption.COUNT_AND_WALL_TIME, TraceTarget.Method("Test", "*")), "trace Test#*")
        assertCommand(Trace(true, TraceOption.COUNT_AND_WALL_TIME, TraceTarget.Method("Test", "*Value")), "trace Test#*Value")
        assertCommand(Trace(true, TraceOption.COUNT_AND_WALL_TIME, TraceTarget.Method("Test", "get*")), "trace Test#get*")

        // Method trace commands.
        assertCommand(
            Trace(true, TraceOption.COUNT_AND_WALL_TIME, TraceTarget.Method("com.example.MyAction", null, null)),
            "trace com.example.MyAction"
        )
        assertCommand(
            Trace(true, TraceOption.COUNT_AND_WALL_TIME, TraceTarget.Method("com.example.MyAction", "", null)),
            "trace com.example.MyAction#"
        )
        assertCommand(
            Trace(true, TraceOption.COUNT_AND_WALL_TIME, TraceTarget.Method("com.example.MyAction", "actionPerformed", emptyList())),
            "trace com.example.MyAction#actionPerformed"
        )
        assertCommand(
            Trace(true, TraceOption.COUNT_AND_WALL_TIME, TraceTarget.Method("com.example.MyAction", null, null)),
            "trace all com.example.MyAction"
        )
        assertCommand(
            Trace(true, TraceOption.COUNT_AND_WALL_TIME, TraceTarget.Method("com.example.MyAction", "", null)),
            "trace all com.example.MyAction#"
        )
        assertCommand(
            Trace(true, TraceOption.COUNT_AND_WALL_TIME, TraceTarget.Method("com.example.MyAction", "actionPerformed", emptyList())),
            "trace all com.example.MyAction#actionPerformed"
        )
        assertCommand(
            Trace(true, TraceOption.COUNT_AND_WALL_TIME, TraceTarget.Method("com.example.MyAction", "actionPerformed", null)),
            "trace all com.example.MyAction#actionPerformed["
        )
        assertCommand(
            Trace(true, TraceOption.COUNT_AND_WALL_TIME, TraceTarget.Method("com.example.MyAction", "actionPerformed", null)),
            "trace all com.example.MyAction#actionPerformed[0"
        )
        assertCommand(
            Trace(true, TraceOption.COUNT_AND_WALL_TIME, TraceTarget.Method("com.example.MyAction", "actionPerformed", listOf(0))),
            "trace all com.example.MyAction#actionPerformed[0]"
        )
        assertCommand(
            Trace(true, TraceOption.COUNT_AND_WALL_TIME, TraceTarget.Method("com.example.MyAction", "actionPerformed", null)),
            "trace all com.example.MyAction#actionPerformed[0,"
        )
        assertCommand(
            Trace(true, TraceOption.COUNT_AND_WALL_TIME, TraceTarget.Method("com.example.MyAction", "actionPerformed", null)),
            "trace all com.example.MyAction#actionPerformed[0,1"
        )
        assertCommand(
            Trace(true, TraceOption.COUNT_AND_WALL_TIME, TraceTarget.Method("com.example.MyAction", "actionPerformed", listOf(0, 1))),
            "trace all com.example.MyAction#actionPerformed[0,1]"
        )
        assertCommand(
            Trace(true, TraceOption.COUNT_AND_WALL_TIME, TraceTarget.Method("com.example.MyAction", "actionPerformed", listOf(0, 1))),
            "trace all com.example.MyAction#actionPerformed[0, 1]"
        )
        assertCommand(
            Trace(true, TraceOption.COUNT_AND_WALL_TIME, TraceTarget.Method("com.example.MyAction", "actionPerformed", listOf(0, 1))),
            "trace all com.example.MyAction#actionPerformed[0 , 1]"
        )
        assertCommand(
            Trace(true, TraceOption.COUNT_AND_WALL_TIME, TraceTarget.Method("com.example.MyAction", "actionPerformed", listOf(0, 1))),
            "trace all com.example.MyAction#actionPerformed[ 0,1 ]"
        )
        assertCommand(
            Trace(true, TraceOption.COUNT_AND_WALL_TIME, TraceTarget.Method("com.example.MyAction", "actionPerformed", listOf(0, 1))),
            "trace all com.example.MyAction#actionPerformed [0,1]"
        )
    }
}
