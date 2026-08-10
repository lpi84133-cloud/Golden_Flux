package com.goldenflux.goldenfluxgame.util

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/** Compact number formatting: 1.2K, 3.4M, 5.6B ... */
fun formatCompact(value: Double): String {
    val v = if (value < 0) 0.0 else value
    if (v < 1000) {
        return if (v < 10 && v % 1.0 != 0.0) String.format("%.1f", v) else v.toLong().toString()
    }
    val units = arrayOf("", "K", "M", "B", "T", "aa", "bb", "cc", "dd")
    val digitGroups = (ln(v) / ln(1000.0)).toInt().coerceIn(0, units.size - 1)
    val scaled = v / 1000.0.pow(digitGroups.toDouble())
    return String.format("%.1f%s", scaled, units[digitGroups])
}

fun formatCompact(value: Long): String = formatCompact(value.toDouble())

fun formatDuration(ms: Long): String {
    val totalSec = abs(ms) / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}
