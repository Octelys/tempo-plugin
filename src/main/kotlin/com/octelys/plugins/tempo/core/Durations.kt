package com.octelys.plugins.tempo.core

object Durations {
    /**
     * Formats seconds as `1h 05m`, `25m` or `0m`, rounded to the nearest minute like the time
     * logged in Tempo, so a duration shown in the IDE matches what gets submitted.
     */
    fun format(seconds: Long): String {
        val totalMinutes = (seconds.coerceAtLeast(0) + 30) / 60
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}h %02dm".format(minutes) else "${minutes}m"
    }
}
