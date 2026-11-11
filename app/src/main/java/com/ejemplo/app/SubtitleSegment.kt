package com.ejemplo.app

import java.util.Locale

data class SubtitleSegment(
    val id: Int,
    val start: Double,
    val end: Double,
    var text: String
) {
    fun formatSrtTime(seconds: Double): String {
        val totalMs = (seconds * 1000).toLong().coerceAtLeast(0)
        val hours = totalMs / 3600000
        val minutes = (totalMs % 3600000) / 60000
        val secs = (totalMs % 60000) / 1000
        val millis = totalMs % 1000
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, secs, millis)
    }

    fun formatVttTime(seconds: Double): String {
        val totalMs = (seconds * 1000).toLong().coerceAtLeast(0)
        val hours = totalMs / 3600000
        val minutes = (totalMs % 3600000) / 60000
        val secs = (totalMs % 60000) / 1000
        val millis = totalMs % 1000
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, secs, millis)
    }

    fun timeRangeLabel(): String {
        return "${formatSrtTime(start)} ➔ ${formatSrtTime(end)}"
    }
}
