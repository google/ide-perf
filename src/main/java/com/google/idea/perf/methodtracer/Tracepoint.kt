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

import java.util.concurrent.atomic.AtomicInteger

object TracepointFlags {
    const val TRACE_CALL_COUNT = 0x1
    const val TRACE_WALL_TIME = 0x2
    const val TRACE_ALL = TRACE_CALL_COUNT or TRACE_WALL_TIME
    const val MASK = TRACE_ALL
}

/** Represents a method (usually) for which we are gathering call counts and timing information. */
class Tracepoint(
    val displayName: String,
    val description: String? = null,
    flags: Int = TracepointFlags.TRACE_ALL
) {
    val flags: AtomicInteger

    companion object {
        /** A special tracepoint representing the root of a call tree. */
        val ROOT = Tracepoint("[root]")
    }

    init {
        check((flags and TracepointFlags.MASK.inv()) == 0) {
            "Invalid tracepoint flags."
        }

        this.flags = AtomicInteger(flags)
    }

    fun hasFlags(flags: Int): Boolean {
        return (this.flags.get() and flags) != 0
    }

    fun setFlags(flags: Int) {
        this.flags.updateAndGet { it or flags }
    }

    fun unsetFlags(flags: Int) {
        this.flags.updateAndGet { it and flags.inv() }
    }

    val isEnabled: Boolean
        get() = (this.flags.get() and TracepointFlags.TRACE_ALL) != 0

    override fun toString(): String = displayName
}
