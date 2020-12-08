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

import com.google.idea.perf.agent.TracerHook
import com.google.idea.perf.tracer.TracerConfig.getMethodTraceData
import com.google.idea.perf.tracer.TracerConfig.getMethodTracepoint
import com.google.idea.perf.tracer.TracerConfig.shouldInstrumentClass
import com.google.idea.perf.util.ConcurrentCopyOnGrowList
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * [TracerConfig] essentially keeps track of which methods should be traced.
 * See also [TracerConfigUtil].
 *
 * When the user adds a new trace request, it ends up here. [TracerClassFileTransformer]
 * finds out about the request by calling [shouldInstrumentClass] and [getMethodTraceData].
 *
 * [TracerConfig] also keeps track of an integer method ID for each traced method.
 * The method ID is injected into the method bytecode and passed to the [TracerHook],
 * which then uses [getMethodTracepoint] to retrieve the corresponding tracepoint.
 * It is important for [getMethodTracepoint] to be fast and lock free.
 *
 * Note: there may be multiple tracing requests which apply to a given method.
 * For simplicity, only the most recent applicable trace request is honored.
 */
object TracerConfig {
    private val tracepoints = ConcurrentCopyOnGrowList<MethodTracepoint>()
    private val lock = ReentrantLock()
    private val methodIds = mutableMapOf<MethodFqName, Int>()
    private val traceRequests = mutableListOf<TraceRequest>()

    fun appendTraceRequest(request: TraceRequest) {
        lock.withLock {
            traceRequests.add(request)
        }
    }

    fun getAllRequests(): List<TraceRequest> {
        lock.withLock {
            return ArrayList(traceRequests)
        }
    }

    fun clearAllRequests(): List<TraceRequest> {
        lock.withLock {
            val copy = ArrayList(traceRequests)
            traceRequests.clear()
            return copy
        }
    }

    fun getMethodTracepoint(methodId: Int): MethodTracepoint = tracepoints.get(methodId)

    /** Returns true if the given class might have methods that need to be traced. */
    fun shouldInstrumentClass(clazz: String): Boolean {
        lock.withLock {
            // Currently O(n) in the number of trace requests---could be optimized if needed.
            return traceRequests.any { request ->
                request.config.enabled && request.matcher.mightMatchMethodInClass(clazz)
            }
        }
    }

    /** Returns [MethodTraceData] based on the most recent matching [TraceRequest]. */
    fun getMethodTraceData(m: MethodFqName): MethodTraceData? {
        lock.withLock {
            val recentMatch = traceRequests.asReversed().firstOrNull { it.matcher.matches(m) }
                ?: return null

            val methodId = methodIds.getOrPut(m) {
                tracepoints.append(MethodTracepoint(m))
            }

            // Sync tracepoint flags.
            val config = recentMatch.config
            val tracepoint = getMethodTracepoint(methodId)
            tracepoint.measureWallTime = !config.countOnly

            return MethodTraceData(methodId, config)
        }
    }
}
