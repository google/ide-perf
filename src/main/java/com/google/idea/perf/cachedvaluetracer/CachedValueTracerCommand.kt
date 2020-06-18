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
sealed class CachedValueTracerCommand {
    /** Zero out all cached value stats, but keep filters. */
    object Clear: CachedValueTracerCommand()

    /** Zero out all cached value stats, and clear filters. */
    object Reset: CachedValueTracerCommand()

    /** Filter cached values by class name. */
    data class Filter(val pattern: String?): CachedValueTracerCommand()

    /** Group cached values by a specific field. */
    data class GroupBy(val groupOption: GroupOption?): CachedValueTracerCommand()
}

enum class GroupOption {
    /** Group cached values by their class names. */
    CLASS,
    /** Group cached values by their stack trace (class, method, and line number). */
    STACK_TRACE
}

fun parseCachedValueTracerCommand(text: String): CachedValueTracerCommand? {
    val tokens = text.split(' ')

    return when(tokens.firstOrNull()) {
        "clear" -> CachedValueTracerCommand.Clear
        "reset" -> CachedValueTracerCommand.Reset
        "filter" -> parseFilterCommand(tokens.advance())
        "group-by" -> parseGroupByCommand(tokens.advance())
        else -> null
    }
}

private fun parseFilterCommand(tokens: List<String>): CachedValueTracerCommand? {
    return CachedValueTracerCommand.Filter(tokens.firstOrNull())
}

private fun parseGroupByCommand(tokens: List<String>): CachedValueTracerCommand? {
    return when (tokens.firstOrNull()) {
        "class" -> CachedValueTracerCommand.GroupBy(GroupOption.CLASS)
        "stack-trace" -> CachedValueTracerCommand.GroupBy(GroupOption.STACK_TRACE)
        else -> CachedValueTracerCommand.GroupBy(null)
    }
}

private fun <E> List<E>.advance(): List<E> {
    return this.subList(1, this.size)
}
