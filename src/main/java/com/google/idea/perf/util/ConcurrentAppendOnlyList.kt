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

package com.google.idea.perf.util

/**
 * A list supporting only two operations: [append] and [get].
 *
 * For memory consistency, the index passed to [get] must come from a prior call to [append].
 *
 * Calls to [get] are O(1) and lock-free.
 * Calls to [append] are amortized O(1) but require locking.
 */
class ConcurrentAppendOnlyList<T : Any>(initialCapacity: Int = 0) {

    @Volatile
    private var data = arrayOfNulls<Any?>(initialCapacity)

    private var tail = 0

    /**
     * Retrieves the element at the specified index.
     * For memory consistency, [index] must come from a prior call to [append].
     */
    fun get(index: Int): T {
        @Suppress("UNCHECKED_CAST")
        return data[index] as T
    }

    /** Appends [element] and returns its index. */
    @Synchronized
    fun append(element: T): Int {
        val index = tail++

        if (index >= data.size) {
            val newCapacity = if (data.isEmpty()) 1 else 2 * data.size
            val newData = arrayOfNulls<Any?>(newCapacity)
            System.arraycopy(data, 0, newData, 0, data.size)
            data = newData // Must come last so readers don't see uninitialized array elements.
        }

        data[index] = element
        return index
    }
}
