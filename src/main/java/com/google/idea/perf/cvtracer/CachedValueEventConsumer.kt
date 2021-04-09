/*
 * Copyright 2021 Google LLC
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

package com.google.idea.perf.cvtracer

import com.intellij.psi.util.CachedValueProfiler
import com.intellij.psi.util.CachedValueProfiler.EventPlace
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// This class depends on CachedValueProfiler, which is an *unstable* IntelliJ API.
// Ideally a CachedValue tracer should be implemented upstream instead, and then this
// code could be removed. See https://youtrack.jetbrains.com/issue/IDEA-261466.
@Suppress("UnstableApiUsage")
class CachedValueEventConsumer : CachedValueProfiler.EventConsumer {
    // TODO: Audit the overhead of (1) lock contention and (2) EventPlace.stackFrame computation.
    private val lock = ReentrantLock()
    private val data = mutableMapOf<StackTraceElement, RawCachedValueStats>()

    data class RawCachedValueStats(
        val location: StackTraceElement,
        var computeTimeNs: Long = 0,
        var computeCount: Long = 0,
        var useCount: Long = 0,
    ) {
        val hits: Long get() = maxOf(0L, useCount - computeCount)
    }

    fun install() {
        CachedValueProfiler.setEventConsumer(this)
    }

    fun uninstall() {
        CachedValueProfiler.setEventConsumer(null)
    }

    fun getSnapshot(): List<RawCachedValueStats> {
        lock.withLock {
            return data.values.map { it.copy() }
        }
    }

    fun clear() {
        lock.withLock(data::clear)
    }

    override fun onValueComputed(frameId: Long, place: EventPlace, start: Long, time: Long) {
        val location = place.stackFrame ?: return
        val elapsedNs = time - start
        lock.withLock {
            val stats = data.getOrPut(location) { RawCachedValueStats(location) }
            stats.computeTimeNs += elapsedNs
            stats.computeCount++
        }
    }

    override fun onValueUsed(frameId: Long, place: EventPlace, computed: Long, time: Long) {
        val location = place.stackFrame ?: return
        lock.withLock {
            val stats = data.getOrPut(location) { RawCachedValueStats(location) }
            stats.useCount++
        }
    }

    override fun onFrameEnter(frameId: Long, place: EventPlace, parentId: Long, time: Long) {}
    override fun onFrameExit(frameId: Long, start: Long, computed: Long, time: Long) {}
    override fun onValueInvalidated(frameId: Long, place: EventPlace, used: Long, time: Long) {}
    override fun onValueRejected(frameId: Long, place: EventPlace,
                                 start: Long, computed: Long, time: Long) {}
}