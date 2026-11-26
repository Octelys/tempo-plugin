package com.octelys.plugins.tempo.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Tracked time on one issue and day: what was sent to Tempo and what still waits to be sent. */
data class IssueDay(val issueKey: String, val date: LocalDate, val sentSeconds: Long, val pendingSeconds: Long)

/**
 * What the plugin tracked today, and on earlier days that still have time waiting, per issue.
 *
 * Sessions count on the day they start, as in [WorklogPlanner], so a row matches the worklog its
 * time goes to.
 */
object DayOverview {

    /** [open] are the sessions still being tracked; their time so far counts as pending. */
    fun of(
        today: LocalDate,
        pending: List<WorkSession>,
        open: List<WorkSession>,
        submitted: List<SubmittedWorklog>,
        zone: ZoneId,
    ): List<IssueDay> {
        val waiting = (pending + open)
            .groupBy { it.issueKey to Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate() }
            .mapValues { (_, sessions) -> sessions.sumOf { it.durationMillis.coerceAtLeast(0) } / 1000 }
            .filterValues { it > 0 }
        val sent = submitted.associate { (it.issueKey to it.date) to (it.loggedSeconds.takeIf { s -> s > 0 } ?: it.rawSeconds) }

        val days = waiting.keys.mapTo(hashSetOf(today)) { it.second }
        return (waiting.keys + sent.keys)
            .filter { it.second in days }
            .map { key -> IssueDay(key.first, key.second, sent[key] ?: 0, waiting[key] ?: 0) }
            .sortedWith(compareBy({ it.date }, { it.issueKey }))
    }
}
