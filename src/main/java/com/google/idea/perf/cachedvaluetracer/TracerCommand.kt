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

/** A cached value tracer CLI command */
sealed class TracerCommand {
    /** Zero out all cached value stats, but keep filters. */
    object Clear: TracerCommand()

    /** Zero out all cached value stats, and clear filters. */
    object Reset: TracerCommand()

    /** Filter cached values by class name. */
    data class Filter(val pattern: String?): TracerCommand()

    /** Group cached values by a specific field. */
    data class GroupBy(val groupOption: GroupOption?): TracerCommand()
}

enum class GroupOption {
    /** Group cached values by their class names. */
    CLASS,
    /** Group cached values by their stack trace (class, method, and line number). */
    STACK_TRACE
}

fun parseTracerCommand(text: String): TracerCommand? {
    val tokens = text.split(' ')

    return when(tokens.firstOrNull()) {
        "clear" -> TracerCommand.Clear
        "reset" -> TracerCommand.Reset
        "filter" -> parseFilterCommand(tokens.advance())
        "group-by" -> parseGroupByCommand(tokens.advance())
        else -> null
    }
}

private fun parseFilterCommand(tokens: List<String>): TracerCommand? {
    return TracerCommand.Filter(tokens.firstOrNull())
}

private fun parseGroupByCommand(tokens: List<String>): TracerCommand? {
    return when (tokens.firstOrNull()) {
        "class" -> TracerCommand.GroupBy(GroupOption.CLASS)
        "stack-trace" -> TracerCommand.GroupBy(GroupOption.STACK_TRACE)
        else -> TracerCommand.GroupBy(null)
    }
}

private fun <E> List<E>.advance(): List<E> {
    return this.subList(1, this.size)
}
