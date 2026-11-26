package com.octelys.plugins.tempo.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** A worklog already sent to Tempo, kept so later time on the same issue and day updates it. */
data class SubmittedWorklog(
    val issueKey: String,
    val date: LocalDate,
    val startTime: LocalTime,
    val tempoWorklogId: Long,
    val rawSeconds: Long,
    /** The time logged in Tempo after rounding; 0 for records made before it was kept. */
    val loggedSeconds: Long = 0,
)

/** One worklog to create (when [existingWorklogId] is `null`) or update in Tempo. */
data class PlannedWorklog(
    val issueKey: String,
    val date: LocalDate,
    val startTime: LocalTime,
    /** Untrimmed tracked time, including what earlier submissions already logged. */
    val totalRawSeconds: Long,
    /** The part of [totalRawSeconds] coming from [sessions], outside busy spans. */
    val pendingSeconds: Long,
    /** The time to log in Tempo after rounding. */
    val roundedSeconds: Long,
    val existingWorklogId: Long?,
    val sessions: List<WorkSession>,
)

data class WorklogPlan(
    val worklogs: List<PlannedWorklog>,
    /** Sessions too short to ever be logged; safe to drop. */
    val discarded: List<WorkSession>,
)

/**
 * Groups pending sessions into one worklog per issue and day, so repeated submissions update a
 * single Tempo entry rather than adding a new one each time.
 *
 * Rounding goes to the nearest increment. For today, totals that still round to zero stay pending,
 * since more time may follow. For past days, they round up to one increment, unless less than a
 * minute was tracked, in which case they are discarded.
 *
 * A new worklog starts on a multiple of five minutes past the hour (see [startTimeOf]).
 *
 * Tracked time inside a [BusySpan] is not counted, and a new worklog never starts inside one, so time
 * already logged in Tempo, such as a meeting, is not logged twice.
 */
object WorklogPlanner {
    private const val SECONDS_PER_MINUTE = 60L

    /** Worklogs start on a multiple of this many minutes past the hour. */
    internal const val START_STEP_MINUTES = 5

    fun plan(
        pending: List<WorkSession>,
        submitted: List<SubmittedWorklog>,
        roundingMinutes: Int,
        today: LocalDate,
        zone: ZoneId,
        busy: List<BusySpan> = emptyList(),
    ): WorklogPlan {
        val incrementSeconds = roundingMinutes.coerceAtLeast(1) * SECONDS_PER_MINUTE
        val submittedByKey = submitted.associateBy { it.issueKey to it.date }

        val worklogs = mutableListOf<PlannedWorklog>()
        val discarded = mutableListOf<WorkSession>()

        pending
            .groupBy { it.issueKey to Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate() }
            .forEach { (key, sessions) ->
                val (issueKey, date) = key
                val existing = submittedByKey[key]
                val free = sessions.flatMap { it.outside(busy) }
                val pendingSeconds = free.sumOf { it.lengthMillis } / 1000
                val totalRawSeconds = (existing?.rawSeconds ?: 0) + pendingSeconds
                val rounded = round(totalRawSeconds, incrementSeconds, isPastDay = date.isBefore(today))

                when {
                    rounded > 0 -> worklogs += PlannedWorklog(
                        issueKey = issueKey,
                        date = date,
                        startTime = existing?.startTime ?: startTimeOf(free, zone, busy),
                        totalRawSeconds = totalRawSeconds,
                        pendingSeconds = pendingSeconds,
                        roundedSeconds = rounded,
                        existingWorklogId = existing?.tempoWorklogId,
                        sessions = sessions,
                    )
                    date.isBefore(today) -> discarded += sessions
                    // Today and still under one increment: leave it pending.
                }
            }

        return WorklogPlan(worklogs.sortedWith(compareBy({ it.date }, { it.startTime })), discarded)
    }

    internal fun round(rawSeconds: Long, incrementSeconds: Long, isPastDay: Boolean): Long {
        val nearest = ((rawSeconds + incrementSeconds / 2) / incrementSeconds) * incrementSeconds
        if (nearest > 0) return nearest
        return if (isPastDay && rawSeconds >= SECONDS_PER_MINUTE) incrementSeconds else 0
    }

    /**
     * The first free instant, rounded down to a multiple of [START_STEP_MINUTES] past the hour. When
     * that falls inside a busy span it rounds up instead, and when neither stays outside busy time on
     * the same day the exact instant is kept.
     */
    internal fun startTimeOf(free: List<LongRange>, zone: ZoneId, busy: List<BusySpan>): LocalTime {
        val first = Instant.ofEpochMilli(free.minOf { it.first }).atZone(zone).truncatedTo(ChronoUnit.SECONDS)
        val floor = first.truncatedTo(ChronoUnit.MINUTES).withMinute(first.minute - first.minute % START_STEP_MINUTES)
        val ceil = if (floor == first) floor else floor.plusMinutes(START_STEP_MINUTES.toLong())

        fun isBusy(millis: Long) = busy.any { millis >= it.startMillis && millis < it.endMillis }
        return listOf(floor, ceil)
            .firstOrNull { it.toLocalDate() == first.toLocalDate() && !isBusy(it.toInstant().toEpochMilli()) }
            ?.toLocalTime()
            ?: first.toLocalTime()
    }
}
