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

object AllocationSampling {
    /**
     * Estimates total memory allocations for the current thread, in bytes.
     *
     * Important: you must invoke [AgentLoader.ensureNativeAgentLoaded] before calling
     * this method, otherwise there maybe be linkage errors at runtime.
     *
     * Allocations are tracked using a native JVMTI agent subscribing to the
     * JVMTI_EVENT_SAMPLED_OBJECT_ALLOC event. The allocation count is only an estimate;
     * its accuracy depends on the heap sampling rate set by the agent.
     */
    external fun countAllocationsForCurrentThread(): Long
}
