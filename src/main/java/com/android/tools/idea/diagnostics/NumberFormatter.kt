package com.android.tools.idea.diagnostics

import java.text.NumberFormat

/** Helper methods for locale-aware number rendering. */
object NumberFormatter {
    private val formatter = NumberFormat.getInstance()

    fun formatNum(num: Long): String = formatter.format(num)

    fun formatNum(num: Long, unit: String): String = "${formatNum(num)} $unit"

    fun formatNsInMs(ns: Long): String = formatNum(ns / 1_000_000, "ms")
}
