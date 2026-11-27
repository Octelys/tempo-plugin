package com.octelys.plugins.tempo.core

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Finds where a worklog goes in Tempo so it overlaps no other block there.
 *
 * A worklog is one block, from its start for its whole rounded duration, even when the tracked
 * time behind it was spread over the day. Starting it at the first tracked minute could therefore
 * run it into a meeting or another worklog.
 */
object WorklogPlacement {

    /**
     * The start of a block of [durationSeconds] on [date] that overlaps none of [taken]:
     * [preferred] itself when free, else the first free multiple of [stepMinutes] past the hour
     * after it, else the last free one before it. `null` when the day has no such gap.
     */
    fun place(
        date: LocalDate,
        preferred: LocalTime,
        durationSeconds: Long,
        taken: Collection<BusySpan>,
        zone: ZoneId,
        stepMinutes: Int = WorklogPlanner.START_STEP_MINUTES,
    ): LocalTime? {
        val durationMillis = durationSeconds.coerceAtLeast(0) * 1000
        fun fits(start: ZonedDateTime): Boolean {
            val from = start.toInstant().toEpochMilli()
            val to = from + durationMillis
            return taken.none { it.startMillis < to && it.endMillis > from }
        }

        val wanted = date.atTime(preferred).atZone(zone)
        if (fits(wanted)) return preferred

        val free = generateSequence(date.atStartOfDay(zone)) { it.plusMinutes(stepMinutes.coerceAtLeast(1).toLong()) }
            .takeWhile { it.toLocalDate() == date }
            .filter(::fits)
            .toList()
        return (free.firstOrNull { it.isAfter(wanted) } ?: free.lastOrNull())?.toLocalTime()
    }
}
