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

/** Represents a method (usually) for which we are gathering call counts and timing information. */
class Tracepoint(val displayName: String, val description: String? = null) {

    override fun toString(): String = displayName

    companion object {
        /** A special tracepoint representing the root of a call tree. */
        val ROOT = Tracepoint("[root]")
    }
}
