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

import java.text.NumberFormat
import kotlin.math.absoluteValue

// A peculiar omission from the Kotlin standard library.
inline fun <T> Iterable<T>.sumByLong(selector: (T) -> Long): Long {
    var sum = 0L
    for (element in this) {
        sum += selector(element)
    }
    return sum
}

inline fun <T> Sequence<T>.sumByLong(selector: (T) -> Long): Long = asIterable().sumByLong(selector)

// TODO: Remove this when we no longer need to support JDK 8.
val Class<*>.packageName: String
    get() = `package`?.name ?: ""

// Helper methods for locale-aware number rendering.
private val formatter = NumberFormat.getInstance()
fun formatNum(num: Long): String = formatter.format(num)
fun formatNum(num: Long, unit: String): String = "${formatNum(num)} $unit"
fun formatNum(num: Double): String = formatter.format(num)
fun formatNsInMs(ns: Long): String = formatNum(ns / 1_000_000, "ms")
fun formatMsInSeconds(ms: Long): String = formatNum(ms / 1_000, "s")

fun formatNsInBestUnit(ns: Long): String {
    return when (ns.absoluteValue) {
        in 0 until 10_000 -> formatNum(ns, "ns")
        in 10_000 until 10_000_000 -> formatNum(ns / 1_000, "μs")
        else -> formatNum(ns / 1_000_000, "ms")
    }
}

fun shouldHideClassFromCompletionResults(c: Class<*>): Boolean {
    return try {
        c.isArray ||
                c.isAnonymousClass ||
                c.isLocalClass ||
                c.isSynthetic ||
                c.name.startsWith("java.lang.invoke.") ||
                c.name.startsWith("com.sun.proxy.") ||
                c.name.startsWith("jdk.internal.reflect.") ||
                c.name.contains("$$")
    } catch (e: Throwable) {
        // We are inspecting arbitrary user classes, so it is possible to hit exceptions
        // like NoClassDefFoundError when calling methods like isAnonymousClass().
        false
    }
}
