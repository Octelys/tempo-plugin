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
)

/** One worklog to create (when [existingWorklogId] is `null`) or update in Tempo. */
data class PlannedWorklog(
    val issueKey: String,
    val date: LocalDate,
    val startTime: LocalTime,
    /** Untrimmed tracked time, including what earlier submissions already logged. */
    val totalRawSeconds: Long,
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
 */
object WorklogPlanner {
    private const val SECONDS_PER_MINUTE = 60L

    fun plan(
        pending: List<WorkSession>,
        submitted: List<SubmittedWorklog>,
        roundingMinutes: Int,
        today: LocalDate,
        zone: ZoneId,
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
                val pendingSeconds = sessions.sumOf { it.durationMillis } / 1000
                val totalRawSeconds = (existing?.rawSeconds ?: 0) + pendingSeconds
                val rounded = round(totalRawSeconds, incrementSeconds, isPastDay = date.isBefore(today))

                when {
                    rounded > 0 -> worklogs += PlannedWorklog(
                        issueKey = issueKey,
                        date = date,
                        startTime = existing?.startTime ?: startTimeOf(sessions, zone),
                        totalRawSeconds = totalRawSeconds,
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

    private fun startTimeOf(sessions: List<WorkSession>, zone: ZoneId): LocalTime =
        Instant.ofEpochMilli(sessions.minOf { it.startMillis })
            .atZone(zone)
            .toLocalTime()
            .truncatedTo(ChronoUnit.SECONDS)
}
