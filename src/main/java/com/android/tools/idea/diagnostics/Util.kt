package com.android.tools.idea.diagnostics

import java.text.NumberFormat

// A peculiar omission from the Kotlin standard library.
inline fun <T> Iterable<T>.sumByLong(selector: (T) -> Long): Long {
    var sum = 0L
    for (element in this) {
        sum += selector(element)
    }
    return sum
}

// Helper methods for locale-aware number rendering.
private val formatter = NumberFormat.getInstance()
fun formatNum(num: Long): String = formatter.format(num)
fun formatNum(num: Long, unit: String): String = "${formatNum(num)} $unit"
fun formatNsInMs(ns: Long): String = formatNum(ns / 1_000_000, "ms")
