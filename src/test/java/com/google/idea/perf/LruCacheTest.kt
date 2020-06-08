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

import com.google.idea.perf.util.LruCache
import org.junit.Test
import kotlin.test.assertEquals

class LruCacheTest {
    @Test
    fun testCache() {
        // Cache with a max capacity of one element.
        var cache = LruCache<String, String>(1)
        cache["A"] = "100"
        for (i in 0..1) {
            assertEquals("100", cache["A"])
            assertEquals(null, cache["B"])
        }

        // Query cache below maximum capacity
        cache = LruCache(4)
        cache["A"] = "100"
        cache["B"] = "200"
        cache["C"] = "300"
        for (i in 0..1) {
            assertEquals("100", cache["A"])
            assertEquals("200", cache["B"])
            assertEquals("300", cache["C"])
            assertEquals(null, cache["D"])
        }

        // Query cache at maximum capacity
        cache = LruCache(4)
        cache["A"] = "100"
        cache["B"] = "200"
        cache["C"] = "300"
        cache["D"] = "400"
        for (i in 0..1) {
            assertEquals("100", cache["A"])
            assertEquals("200", cache["B"])
            assertEquals("300", cache["C"])
            assertEquals("400", cache["D"])
            assertEquals(null, cache["E"])
        }

        // Query cache beyond maximum capacity
        cache = LruCache(4)
        cache["A"] = "100"
        cache["B"] = "200"
        cache["C"] = "300"
        cache["D"] = "400"
        cache["E"] = "500"
        assertEquals(null, cache["A"])
        assertEquals("200", cache["B"])
        assertEquals("300", cache["C"])
        assertEquals("400", cache["D"])
        assertEquals("500", cache["E"])

        cache["A"] = "100"
        assertEquals("100", cache["A"])
        assertEquals(null, cache["B"])
        assertEquals("300", cache["C"])
        assertEquals("400", cache["D"])
        assertEquals("500", cache["E"])

        cache["D"] = "400"
        assertEquals(null, cache["A"])
        assertEquals(null, cache["B"])
        assertEquals("300", cache["C"])
        assertEquals("400", cache["D"])
        assertEquals("500", cache["E"])
    }
}
