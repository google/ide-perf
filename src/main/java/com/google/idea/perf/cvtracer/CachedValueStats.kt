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

package com.google.idea.perf.cvtracer

/**
 * Statistics for CachedValue computations, usually grouped by the name of the
 * class that created the CachedValue.
 */
interface CachedValueStats {
    val description: String
    val computeTimeNs: Long
    val hits: Long // Number of times a value was reused, without recomputing it.
    val misses: Long // Number of times a value had to be recomputed.
    val hitRatio: Double get() = hits.toDouble() / maxOf(1L, hits + misses)
}

class MutableCachedValueStats(
    override val description: String,
    override var computeTimeNs: Long,
    override var hits: Long,
    override var misses: Long
): CachedValueStats
