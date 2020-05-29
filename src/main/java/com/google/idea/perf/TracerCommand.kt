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

/** A tracer CLI command */
sealed class TracerCommand {
    /** Zero out all tracepoint data, but keep call tree. */
    object Clear: TracerCommand()

    /** Zero out all tracepoint data and reset the call tree. */
    object Reset: TracerCommand()

    /** Trace or untrace a set of methods. */
    data class Trace(
        val enable: Boolean,
        val traceOption: TraceOption?,
        val target: TraceTarget?
    ): TracerCommand()
}

/** Represents what to trace */
sealed class TraceOption {
    /** Option to trace all aspects of a method's execution. */
    object All: TraceOption()

    /** Option to trace the number of calls to a method. */
    object CallCount: TraceOption()

    /** Option to trace the execution time of a method. */
    object WallTime: TraceOption()
}

/** A set of methods that the tracer will trace. */
sealed class TraceTarget {
    /** Trace the tracer's own internal methods. */
    object Tracer: TraceTarget()

    /** Trace some important methods of the PSI. */
    object PsiFinders: TraceTarget()

    /**
     * Trace a specific method.
     * @param className The Java class that contains the desired method
     * @param methodName The method to trace
     */
    data class Method(val className: String, val methodName: String): TraceTarget()
}

fun parseTracerCommand(text: String): TracerCommand? {
    // Grammar is simple enough for a basic split() parser.
    val tokens = text.split(' ')
    if (tokens.isEmpty()) {
        return null
    }

    return when (tokens.first()) {
        "clear" -> if (tokens.size <= 1) TracerCommand.Clear else null
        "reset" -> if (tokens.size <= 1) TracerCommand.Reset else null
        "trace" -> parseTraceCommand(tokens.advance(), true)
        "untrace" -> parseTraceCommand(tokens.advance(), false)
        else -> null
    }
}

private fun parseTraceCommand(tokens: List<String>, enable: Boolean): TracerCommand? {
    return when (tokens.size) {
        0 -> TracerCommand.Trace(enable, null, null)
        1 -> TracerCommand.Trace(enable, TraceOption.All, parseTraceTarget(tokens))
        else -> {
            val option = parseTraceOption(tokens)
            val target = parseTraceTarget(tokens.advance())
            TracerCommand.Trace(enable, option, target)
        }
    }
}

private fun parseTraceOption(tokens: List<String>): TraceOption? {
    return when (tokens.first()) {
        "all" -> TraceOption.All
        "count" -> TraceOption.CallCount
        "wall-time" -> TraceOption.WallTime
        else -> null
    }
}

private fun parseTraceTarget(tokens: List<String>): TraceTarget? {
    return when (val token = tokens.first()) {
        "psi-finders" -> TraceTarget.PsiFinders
        "tracer" -> TraceTarget.Tracer
        else -> {
            val splitIndex = token.indexOf('#')
            return if (splitIndex != -1) {
                val className = token.substring(0, splitIndex)
                val methodName = token.substring(splitIndex + 1)
                TraceTarget.Method(className, methodName)
            }
            else {
                null
            }
        }
    }
}

private fun <E> List<E>.advance(): List<E> {
    return this.subList(1, this.size)
}
