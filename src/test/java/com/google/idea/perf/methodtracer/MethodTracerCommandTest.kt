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

package com.google.idea.perf.methodtracer

import org.junit.Test
import kotlin.test.assertEquals

private typealias Unknown = MethodTracerCommand.Unknown
private typealias Clear = MethodTracerCommand.Clear
private typealias Reset = MethodTracerCommand.Reset
private typealias Trace = MethodTracerCommand.Trace

private fun assertCommand(expected: MethodTracerCommand, actual: String) {
    assertEquals(expected, parseMethodTracerCommand(actual))
}

class MethodTracerCommandTest {
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
        assertCommand(Trace(false, TraceOption.ALL, null), "untrace")
        assertCommand(Trace(false, TraceOption.WALL_TIME, null), "untrace wall-time")

        assertCommand(Trace(false, TraceOption.ALL, TraceTarget.PsiFinders), "untrace psi-finders")
        assertCommand(Trace(false, TraceOption.ALL, TraceTarget.PsiFinders), "untrace all psi-finders")
        assertCommand(Trace(false, TraceOption.ALL, TraceTarget.Tracer), "untrace tracer")
        assertCommand(Trace(false, TraceOption.CALL_COUNT, TraceTarget.Tracer), "untrace count tracer")

        // Wildcard untrace commands.
        assertCommand(Trace(false, TraceOption.ALL, TraceTarget.All), "untrace *")
        assertCommand(Trace(false, TraceOption.ALL, TraceTarget.ClassPattern("Test*")), "untrace Test*")
        assertCommand(Trace(false, TraceOption.ALL, TraceTarget.MethodPattern("Test", "*")), "untrace Test#*")
        assertCommand(Trace(false, TraceOption.ALL, TraceTarget.MethodPattern("Test", "get*")), "untrace Test#get*")

        // Method untrace commands.
        assertCommand(
            Trace(false, TraceOption.ALL, TraceTarget.Method("com.example.MyAction", "actionPerformed", emptyList())),
            "untrace com.example.MyAction#actionPerformed"
        )
        assertCommand(
            Trace(
                false,
                TraceOption.WALL_TIME,
                TraceTarget.Method("com.example.MyAction", "actionPerformed", emptyList())
            ),
            "untrace wall-time com.example.MyAction#actionPerformed"
        )
        assertCommand(
            Trace(false, TraceOption.ALL, TraceTarget.Method("com.example.MyAction", "actionPerformed", listOf(0))),
            "untrace all com.example.MyAction#actionPerformed[0]"
        )
        assertCommand(
            Trace(false, TraceOption.ALL, TraceTarget.Method("com.example.MyAction", "actionPerformed", listOf(0, 1))),
            "untrace all com.example.MyAction#actionPerformed[0,1]"
        )

        // Basic trace commands.
        assertCommand(Trace(true, TraceOption.ALL, null), "trace")
        assertCommand(Trace(true, TraceOption.WALL_TIME, null), "trace wall-time")
        assertCommand(Trace(true, TraceOption.ALL, TraceTarget.PsiFinders), "trace psi-finders")
        assertCommand(Trace(true, TraceOption.WALL_TIME, TraceTarget.PsiFinders), "trace wall-time psi-finders")
        assertCommand(Trace(true, TraceOption.ALL, TraceTarget.Tracer), "trace tracer")
        assertCommand(Trace(true, TraceOption.CALL_COUNT, TraceTarget.Tracer), "trace count tracer")

        // Wildcard trace commands.
        assertCommand(Trace(true, TraceOption.ALL, TraceTarget.All), "trace *")
        assertCommand(Trace(true, TraceOption.ALL, TraceTarget.ClassPattern("*Test")), "trace *Test")
        assertCommand(Trace(true, TraceOption.ALL, TraceTarget.ClassPattern("Test*")), "trace Test*")
        assertCommand(Trace(true, TraceOption.ALL, TraceTarget.MethodPattern("Test", "*")), "trace Test#*")
        assertCommand(Trace(true, TraceOption.ALL, TraceTarget.MethodPattern("Test", "*Value")), "trace Test#*Value")
        assertCommand(Trace(true, TraceOption.ALL, TraceTarget.MethodPattern("Test", "get*")), "trace Test#get*")

        // Method trace commands.
        assertCommand(
            Trace(true, TraceOption.ALL, TraceTarget.Method("com.example.MyAction", null, null)),
            "trace com.example.MyAction"
        )
        assertCommand(
            Trace(true, TraceOption.ALL, TraceTarget.Method("com.example.MyAction", "", null)),
            "trace com.example.MyAction#"
        )
        assertCommand(
            Trace(true, TraceOption.ALL, TraceTarget.Method("com.example.MyAction", "actionPerformed", emptyList())),
            "trace com.example.MyAction#actionPerformed"
        )
        assertCommand(
            Trace(true, TraceOption.ALL, TraceTarget.Method("com.example.MyAction", null, null)),
            "trace all com.example.MyAction"
        )
        assertCommand(
            Trace(true, TraceOption.ALL, TraceTarget.Method("com.example.MyAction", "", null)),
            "trace all com.example.MyAction#"
        )
        assertCommand(
            Trace(true, TraceOption.ALL, TraceTarget.Method("com.example.MyAction", "actionPerformed", emptyList())),
            "trace all com.example.MyAction#actionPerformed"
        )
        assertCommand(
            Trace(true, TraceOption.ALL, TraceTarget.Method("com.example.MyAction", "actionPerformed", null)),
            "trace all com.example.MyAction#actionPerformed["
        )
        assertCommand(
            Trace(true, TraceOption.ALL, TraceTarget.Method("com.example.MyAction", "actionPerformed", null)),
            "trace all com.example.MyAction#actionPerformed[0"
        )
        assertCommand(
            Trace(true, TraceOption.ALL, TraceTarget.Method("com.example.MyAction", "actionPerformed", listOf(0))),
            "trace all com.example.MyAction#actionPerformed[0]"
        )
        assertCommand(
            Trace(true, TraceOption.ALL, TraceTarget.Method("com.example.MyAction", "actionPerformed", null)),
            "trace all com.example.MyAction#actionPerformed[0,"
        )
        assertCommand(
            Trace(true, TraceOption.ALL, TraceTarget.Method("com.example.MyAction", "actionPerformed", null)),
            "trace all com.example.MyAction#actionPerformed[0,1"
        )
        assertCommand(
            Trace(true, TraceOption.ALL, TraceTarget.Method("com.example.MyAction", "actionPerformed", listOf(0, 1))),
            "trace all com.example.MyAction#actionPerformed[0,1]"
        )
        assertCommand(
            Trace(true, TraceOption.ALL, TraceTarget.Method("com.example.MyAction", "actionPerformed", listOf(0, 1))),
            "trace all com.example.MyAction#actionPerformed[0, 1]"
        )
        assertCommand(
            Trace(true, TraceOption.ALL, TraceTarget.Method("com.example.MyAction", "actionPerformed", listOf(0, 1))),
            "trace all com.example.MyAction#actionPerformed[0 , 1]"
        )
        assertCommand(
            Trace(true, TraceOption.ALL, TraceTarget.Method("com.example.MyAction", "actionPerformed", listOf(0, 1))),
            "trace all com.example.MyAction#actionPerformed[ 0,1 ]"
        )
        assertCommand(
            Trace(true, TraceOption.ALL, TraceTarget.Method("com.example.MyAction", "actionPerformed", listOf(0, 1))),
            "trace all com.example.MyAction#actionPerformed [0,1]"
        )
    }
}
