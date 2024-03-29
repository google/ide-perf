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

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import java.text.NumberFormat
import kotlin.math.absoluteValue

// Helper methods for locale-aware number rendering.
private val formatter = NumberFormat.getInstance()
fun formatNum(num: Long): String = formatter.format(num)
fun formatNum(num: Long, unit: String): String = "${formatNum(num)} $unit"
fun formatNum(num: Double): String = formatter.format(num)
fun formatNsInMs(ns: Long): String = formatNum(ns / 1_000_000, "ms")

fun formatNsInBestUnit(ns: Long): String {
    return when (ns.absoluteValue) {
        in 0 until 10_000 -> formatNum(ns, "ns")
        in 10_000 until 10_000_000 -> formatNum(ns / 1_000, "μs")
        else -> formatNum(ns / 1_000_000, "ms")
    }
}

inline fun Disposable.onDispose(crossinline disposable: () -> Unit) {
    @Suppress("ObjectLiteralToLambda") // Disposer cares about object identity.
    Disposer.register(this, object : Disposable {
        override fun dispose() {
            disposable()
        }
    })
}
