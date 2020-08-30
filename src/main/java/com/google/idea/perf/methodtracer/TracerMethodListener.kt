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

/** Dispatches method entry/exit events to the [CallTreeManager]. */
class TracerMethodListener: MethodTracerHook {

    override fun enter(methodId: Int, args: Array<Any>?) {
        var tracepoint = TracerConfig.getMethodTracepoint(methodId)
        if (args != null) {
            val argStrings = Array(args.size) { args[it].toString() }
            tracepoint = MethodTracepointWithArgs(tracepoint, argStrings)
        }
        CallTreeManager.enter(tracepoint)
    }

    override fun leave() {
        CallTreeManager.leave()
    }

    companion object {
        init {
            // Trigger class loading for CallTreeManager early so that it doesn't happen
            // during tracing. This reduces the chance of invoking an instrumented method
            // from a tracing hook (causing infinite recursion).
            CallTreeManager.enter(Tracepoint.ROOT)
            CallTreeManager.leave()
            CallTreeManager.collectAndReset()
        }
    }
}
