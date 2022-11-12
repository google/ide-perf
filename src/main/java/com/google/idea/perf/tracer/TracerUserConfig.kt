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

package com.google.idea.perf.tracer

import java.util.*

/**
 * [TracerUserConfig] keeps track of which methods should be traced and `untrace`d.
 * When a user `untrace`s some request, it will be removed if it was created before.
 */
object TracerUserConfig {

    private val userTraceRequests = LinkedHashMap<String, TraceTarget.Method>()

    @Synchronized
    fun cloneUserTraceRequests(): List<TraceTarget.Method> {
        return userTraceRequests.values.toList()
    }

    @Synchronized
    fun addUserTraceRequest(entry: TraceTarget.Method) {
        val plainTextKey = concatClassAndMethod(entry)
        userTraceRequests[plainTextKey] = entry
    }

    @Synchronized
    fun addUserUntraceRequest(entry: TraceTarget.Method) {
        val classAndMethod = concatClassAndMethod(entry)
        val oldValue = userTraceRequests[classAndMethod]
        if (oldValue != null && oldValue.traceOption != TraceOption.UNTRACE) {
            userTraceRequests.remove(classAndMethod)
        } else {
            userTraceRequests[classAndMethod] = entry
        }
    }

    @Synchronized
    fun resetAll() {
        userTraceRequests.clear()
    }

    private fun concatClassAndMethod(entry: TraceTarget.Method): String {
        return "${entry.className}#${entry.methodName ?: ""}"
    }

}
