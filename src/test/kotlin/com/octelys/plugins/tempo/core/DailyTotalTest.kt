package com.octelys.plugins.tempo.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class DailyTotalTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 24)
    private val yesterday = today.minusDays(1)

    @Test
    fun `sessions on the same day add up`() {
        val total = DailyTotal(today, 0)
            .plus(session(today, "09:00", today, "09:30"), today, zone)
            .plus(session(today, "10:00", today, "10:15"), today, zone)

        assertEquals(today, total.date)
        assertEquals(minutes(45), total.millis)
    }

    @Test
    fun `a total from an earlier day starts over`() {
        val total = DailyTotal(yesterday, minutes(120))
            .plus(session(today, "09:00", today, "09:10"), today, zone)

        assertEquals(today, total.date)
        assertEquals(minutes(10), total.millis)
    }

    @Test
    fun `only the part of a session after midnight counts for today`() {
        val total = DailyTotal(yesterday, minutes(60))
            .plus(session(yesterday, "23:50", today, "00:20"), today, zone)

        assertEquals(today, total.date)
        assertEquals(minutes(20), total.millis)
    }

    @Test
    fun `a session entirely on another day adds nothing`() {
        val total = DailyTotal(today, minutes(5))
            .plus(session(yesterday, "22:00", yesterday, "23:00"), today, zone)

        assertEquals(minutes(5), total.millis)
    }

    @Test
    fun `the open session is added to today's total`() {
        val millis = DailyTotal(today, minutes(30))
            .millisOn(today, session(today, "11:00", today, "11:07"), zone)

        assertEquals(minutes(37), millis)
    }

    @Test
    fun `a stale total counts as zero for today`() {
        assertEquals(0L, DailyTotal(yesterday, minutes(90)).millisOn(today, null, zone))
    }

    @Test
    fun `counting an open session as it grows and again once closed counts each stretch once`() {
        val total = DailyTotal(today, 0)
            .plus(session(today, "09:00", today, "09:10"), today, zone)
            .plus(session(today, "09:00", today, "09:25"), today, zone)
            .plus(session(today, "09:00", today, "09:40"), today, zone)

        assertEquals(DailyTotal(today, minutes(40), millis(today, "09:40")), total)
    }

    @Test
    fun `a checkpointed session reopened at its last activity is not counted twice`() {
        val total = DailyTotal(today, 0)
            .plus(session(today, "09:00", today, "09:20"), today, zone)
            .plus(session(today, "09:20", today, "09:30"), today, zone)

        assertEquals(minutes(30), total.millis)
    }

    @Test
    fun `the open session adds only its uncounted part to the shown total`() {
        val counted = DailyTotal(today, 0).plus(session(today, "09:00", today, "09:15"), today, zone)

        assertEquals(minutes(20), counted.millisOn(today, session(today, "09:00", today, "09:20"), zone))
    }

    @Test
    fun `after a restart the counted total is kept and new sessions add to it`() {
        val beforeRestart = DailyTotal(today, 0).plus(session(today, "09:00", today, "11:00"), today, zone)
        val restored = DailyTotal(beforeRestart.date, beforeRestart.millis, beforeRestart.countedUntilMillis)

        val total = restored.plus(session(today, "11:30", today, "11:45"), today, zone)

        assertEquals(minutes(135), total.millis)
    }

    @Test
    fun `an open session counted across midnight starts today's total at midnight`() {
        val yesterdayTotal = DailyTotal(yesterday, 0).plus(session(yesterday, "23:00", yesterday, "23:50"), yesterday, zone)

        val total = yesterdayTotal.plus(session(yesterday, "23:00", today, "00:30"), today, zone)

        assertEquals(DailyTotal(today, minutes(30), millis(today, "00:30")), total)
    }

    private fun session(startDate: LocalDate, start: String, endDate: LocalDate, end: String) =
        WorkSession("ABC-1", millis(startDate, start), millis(endDate, end))

    private fun millis(date: LocalDate, time: String): Long =
        date.atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()

    private fun minutes(value: Int): Long = value * 60_000L
}
