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
        assertCommand(Trace(false, null), "untrace")

        // Wildcard untrace commands.
        assertCommand(Trace(false, TraceTarget.All), "untrace *")
        assertCommand(Trace(false, TraceTarget.Method("Test*", "*")), "untrace Test*")
        assertCommand(Trace(false, TraceTarget.Method("Test", "*")), "untrace Test#*")
        assertCommand(Trace(false, TraceTarget.Method("Test", "get*")), "untrace Test#get*")

        // Method untrace commands.
        assertCommand(
            Trace(false, TraceTarget.Method("com.example.MyAction", "actionPerformed", emptyList())),
            "untrace com.example.MyAction#actionPerformed"
        )
        assertCommand(
            Trace(false, TraceTarget.Method("com.example.MyAction", "actionPerformed", listOf(0))),
            "untrace com.example.MyAction#actionPerformed[0]"
        )
        assertCommand(
            Trace(false, TraceTarget.Method("com.example.MyAction", "actionPerformed", listOf(0, 1))),
            "untrace com.example.MyAction#actionPerformed[0,1]"
        )

        // Basic trace commands.
        assertCommand(Trace(true, null), "trace")

        // Wildcard trace commands.
        assertCommand(Trace(true, TraceTarget.All), "trace *")
        assertCommand(Trace(true, TraceTarget.Method("*Test", "*")), "trace *Test")
        assertCommand(Trace(true, TraceTarget.Method("Test*", "*")), "trace Test*")
        assertCommand(Trace(true, TraceTarget.Method("Test", "*")), "trace Test#*")
        assertCommand(Trace(true, TraceTarget.Method("Test", "*Value")), "trace Test#*Value")
        assertCommand(Trace(true, TraceTarget.Method("Test", "get*")), "trace Test#get*")

        // Method trace commands.
        assertCommand(
            Trace(true, TraceTarget.Method("com.example.MyAction", "*")),
            "trace com.example.MyAction"
        )
        assertCommand(
            Trace(true, TraceTarget.Method("com.example.MyAction", "")),
            "trace com.example.MyAction#"
        )
        assertCommand(
            Trace(true, TraceTarget.Method("com.example.MyAction", "actionPerformed", emptyList())),
            "trace com.example.MyAction#actionPerformed"
        )
        assertCommand(
            Trace(true, TraceTarget.Method("com.example.MyAction", "*")),
            "trace com.example.MyAction"
        )
        assertCommand(
            Trace(true, TraceTarget.Method("com.example.MyAction", "")),
            "trace com.example.MyAction#"
        )
        assertCommand(
            Trace(true, TraceTarget.Method("com.example.MyAction", "actionPerformed")),
            "trace com.example.MyAction#actionPerformed"
        )
        assertCommand(
            Trace(true, TraceTarget.Method("com.example.MyAction", "actionPerformed", null)),
            "trace com.example.MyAction#actionPerformed["
        )
        assertCommand(
            Trace(true, TraceTarget.Method("com.example.MyAction", "actionPerformed", null)),
            "trace com.example.MyAction#actionPerformed[0"
        )
        assertCommand(
            Trace(true, TraceTarget.Method("com.example.MyAction", "actionPerformed", listOf(0))),
            "trace com.example.MyAction#actionPerformed[0]"
        )
        assertCommand(
            Trace(true, TraceTarget.Method("com.example.MyAction", "actionPerformed", null)),
            "trace com.example.MyAction#actionPerformed[0,"
        )
        assertCommand(
            Trace(true, TraceTarget.Method("com.example.MyAction", "actionPerformed", null)),
            "trace com.example.MyAction#actionPerformed[0,1"
        )
        assertCommand(
            Trace(true, TraceTarget.Method("com.example.MyAction", "actionPerformed", listOf(0, 1))),
            "trace com.example.MyAction#actionPerformed[0,1]"
        )
        assertCommand(
            Trace(true, TraceTarget.Method("com.example.MyAction", "actionPerformed", listOf(0, 1))),
            "trace com.example.MyAction#actionPerformed[0, 1]"
        )
        assertCommand(
            Trace(true, TraceTarget.Method("com.example.MyAction", "actionPerformed", listOf(0, 1))),
            "trace com.example.MyAction#actionPerformed[0 , 1]"
        )
        assertCommand(
            Trace(true, TraceTarget.Method("com.example.MyAction", "actionPerformed", listOf(0, 1))),
            "trace com.example.MyAction#actionPerformed[ 0,1 ]"
        )
        assertCommand(
            Trace(true, TraceTarget.Method("com.example.MyAction", "actionPerformed", listOf(0, 1))),
            "trace com.example.MyAction#actionPerformed [0,1]"
        )
    }
}
