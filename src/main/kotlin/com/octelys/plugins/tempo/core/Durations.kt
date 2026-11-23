package com.octelys.plugins.tempo.core

object Durations {
    /** Formats seconds as `1h 05m`, `25m` or `0m`. */
    fun format(seconds: Long): String {
        val totalMinutes = seconds.coerceAtLeast(0) / 60
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}h %02dm".format(minutes) else "${minutes}m"
    }
}
