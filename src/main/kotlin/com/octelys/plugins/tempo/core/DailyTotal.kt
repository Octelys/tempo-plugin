package com.octelys.plugins.tempo.core

import java.time.LocalDate
import java.time.ZoneId

/**
 * Active time tracked on [date], in milliseconds.
 *
 * [countedUntilMillis] is the end of the latest time already counted. Sessions of one project never
 * overlap and arrive in order, so counting only what lies after it lets the same session be added
 * while still open and again once closed without counting any stretch twice.
 */
data class DailyTotal(val date: LocalDate, val millis: Long, val countedUntilMillis: Long = 0) {

    /** Adds the part of [session] on [today] not counted yet; a total from an earlier day starts over. */
    fun plus(session: WorkSession, today: LocalDate, zone: ZoneId): DailyTotal {
        val base = if (date == today) millis else 0L
        val uncounted = WorkSession(session.issueKey, maxOf(session.startMillis, countedUntilMillis), session.endMillis)
        return DailyTotal(today, base + overlapMillis(today, uncounted, zone), maxOf(countedUntilMillis, session.endMillis))
    }

    /** This total if it is for [today], plus the uncounted part of the still open [openSession]. */
    fun millisOn(today: LocalDate, openSession: WorkSession?, zone: ZoneId): Long = when (openSession) {
        null -> if (date == today) millis else 0L
        else -> plus(openSession, today, zone).millis
    }

    companion object {
        /** The duration of [session] that overlaps [day], so a session crossing midnight is split. */
        fun overlapMillis(day: LocalDate, session: WorkSession, zone: ZoneId): Long {
            val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            return (minOf(session.endMillis, dayEnd) - maxOf(session.startMillis, dayStart)).coerceAtLeast(0)
        }
    }
}
