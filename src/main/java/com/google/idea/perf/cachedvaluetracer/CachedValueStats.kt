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

package com.google.idea.perf.cachedvaluetracer

/**
 * Represents an aggregation of cached values properties.
 * @property name a name that represents the cached value aggregation
 * @property lifetime the aggregate lifetime of cached values
 * @property hits the number of times a cached value was reused
 * @property misses the number of times a cached value was created or invalidated
 */
data class CachedValueStats(
    val name: String,
    val lifetime: Long,
    val hits: Long,
    val misses: Long
) {
    val hitRatio: Double
        get() = hits.toDouble() / (hits + misses)
}
