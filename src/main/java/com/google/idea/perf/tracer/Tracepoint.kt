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

import com.google.idea.perf.tracer.TracepointFlags.TRACE_ALL
import org.objectweb.asm.Type
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.LazyThreadSafetyMode.PUBLICATION

object TracepointFlags {
    const val TRACE_CALL_COUNT = 0x1
    const val TRACE_WALL_TIME = 0x2
    const val TRACE_ALL = TRACE_CALL_COUNT or TRACE_WALL_TIME
    const val MASK = TRACE_ALL
}

/** Represents a method (usually) for which we are gathering call counts and timing information. */
interface Tracepoint {
    val displayName: String
    val detailedName: String
    val flags: AtomicInteger

    fun setFlags(flags: Int) {
        this.flags.updateAndGet { it or flags }
    }

    fun unsetFlags(flags: Int) {
        this.flags.updateAndGet { it and flags.inv() }
    }

    companion object {
        val ROOT = SimpleTracepoint("[root]", "the synthetic root of the call tree")
    }
}

/** A basic implementation of [Tracepoint] useful for synthetic tracepoints and tests. */
class SimpleTracepoint(
    override val displayName: String,
    override val detailedName: String = displayName
) : Tracepoint {
    override val flags = AtomicInteger(TRACE_ALL)
    override fun toString(): String = displayName
}

/** A [Tracepoint] representing an individual method. */
class MethodTracepoint(
    private val fqName: MethodFqName,
    flags: Int = TRACE_ALL,
) : Tracepoint {
    override val flags = AtomicInteger(flags)
    override val displayName = "${fqName.clazz.substringAfterLast('.')}.${fqName.method}"

    override val detailedName by lazy(PUBLICATION) {
        buildString {
            val argTypes = Type.getArgumentTypes(fqName.desc)
            val argString = argTypes.joinToString { it.className.substringAfterLast('.') }
            appendln("Class: ${fqName.clazz}")
            append("Method: ${fqName.method}($argString)")
        }
    }

    init {
        check((flags and TracepointFlags.MASK.inv()) == 0) { "invalid tracepoint flags" }
    }

    // Note: reference equality is sufficient currently because TracerConfig maintains
    // a single canonical MethodTracepoint per traced method.

    override fun toString(): String = displayName
}

/**
 * A [Tracepoint] representing a method call with a specific set of arguments.
 * Two instances with the same backing [method] and the same [argStrings] are considered equal.
 */
class MethodTracepointWithArgs(
    private val method: Tracepoint,
    private val argStrings: Array<String>
) : Tracepoint by method {
    override val displayName = "${method.displayName}: ${argStrings.joinToString(", ")}"

    override val detailedName by lazy(PUBLICATION) {
        buildString {
            append(method.detailedName)
            for (arg in argStrings) {
                append("\nArg: $arg")
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (other !is MethodTracepointWithArgs) return false
        return method == other.method && argStrings.contentEquals(other.argStrings)
    }

    override fun hashCode(): Int = Objects.hash(method, argStrings.contentDeepHashCode())

    override fun toString(): String = displayName
}
