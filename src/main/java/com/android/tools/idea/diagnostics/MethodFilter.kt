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

package com.android.tools.idea.diagnostics

import com.android.tools.idea.diagnostics.agent.MethodListener

/**
 * Used by [MethodTracingTransformer] to decide which classes and methods to instrument.
 * Implementations must be thread-safe because class loading happens in parallel.
 */
interface MethodFilter {
    /** Returns true if the given class should be instrumented. */
    fun shouldInstrumentClass(classJvmName: String): Boolean

    /**
     * Returns a method id if the given method should be instrumented, otherwise returns null.
     * The integer id will later be passed to [MethodListener] events.
     */
    fun getMethodId(classJvmName: String, methodName: String, methodDesc: String): Int?
}
