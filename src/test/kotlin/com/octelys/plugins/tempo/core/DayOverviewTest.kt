package com.octelys.plugins.tempo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class DayOverviewTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val today: LocalDate = LocalDate.of(2026, 11, 26)
    private val yesterday: LocalDate = today.minusDays(1)
    private val lastWeek: LocalDate = today.minusDays(5)

    @Test
    fun `pending and open time on an issue add up`() {
        val lines = overview(
            pending = listOf(session("ABC-1", today, "09:00", "09:30")),
            open = listOf(session("ABC-1", today, "10:00", "10:15")),
        )

        assertEquals(listOf(IssueDay("ABC-1", today, sentSeconds = 0, pendingSeconds = 45 * 60L)), lines)
    }

    @Test
    fun `time sent today shows next to time still pending on the same issue`() {
        val lines = overview(
            pending = listOf(session("ABC-1", today, "13:00", "13:10")),
            submitted = listOf(submitted("ABC-1", today, rawSeconds = 52 * 60L, loggedSeconds = 60 * 60L)),
        )

        assertEquals(listOf(IssueDay("ABC-1", today, sentSeconds = 60 * 60L, pendingSeconds = 10 * 60L)), lines)
    }

    @Test
    fun `an issue only sent today is listed without pending time`() {
        val lines = overview(submitted = listOf(submitted("ABC-2", today, rawSeconds = 1800, loggedSeconds = 1800)))

        assertEquals(listOf(IssueDay("ABC-2", today, sentSeconds = 1800, pendingSeconds = 0)), lines)
    }

    @Test
    fun `a record without the logged time falls back to the tracked time`() {
        val lines = overview(submitted = listOf(submitted("ABC-1", today, rawSeconds = 1500, loggedSeconds = 0)))

        assertEquals(1500L, lines.single().sentSeconds)
    }

    @Test
    fun `an earlier day is listed only while it has pending time`() {
        val lines = overview(
            pending = listOf(session("ABC-1", yesterday, "16:00", "16:30")),
            submitted = listOf(
                submitted("ABC-2", yesterday, rawSeconds = 3600, loggedSeconds = 3600),
                submitted("ABC-3", lastWeek, rawSeconds = 3600, loggedSeconds = 3600),
            ),
        )

        assertEquals(
            listOf(
                IssueDay("ABC-1", yesterday, sentSeconds = 0, pendingSeconds = 30 * 60L),
                IssueDay("ABC-2", yesterday, sentSeconds = 3600, pendingSeconds = 0),
            ),
            lines,
        )
    }

    @Test
    fun `a session crossing midnight counts on the day it started`() {
        val crossing = WorkSession("ABC-1", millis(yesterday, "23:50"), millis(today, "00:20"))

        val lines = overview(pending = listOf(crossing))

        assertEquals(listOf(IssueDay("ABC-1", yesterday, sentSeconds = 0, pendingSeconds = 30 * 60L)), lines)
    }

    @Test
    fun `nothing tracked gives no lines`() {
        assertTrue(overview().isEmpty())
    }

    @Test
    fun `lines are ordered by day then issue`() {
        val lines = overview(
            pending = listOf(
                session("ABC-2", today, "09:00", "09:10"),
                session("ABC-1", today, "10:00", "10:10"),
                session("ABC-9", yesterday, "10:00", "10:10"),
            ),
        )

        assertEquals(listOf("ABC-9", "ABC-1", "ABC-2"), lines.map { it.issueKey })
    }

    private fun overview(
        pending: List<WorkSession> = emptyList(),
        open: List<WorkSession> = emptyList(),
        submitted: List<SubmittedWorklog> = emptyList(),
    ) = DayOverview.of(today, pending, open, submitted, zone)

    private fun submitted(key: String, date: LocalDate, rawSeconds: Long, loggedSeconds: Long) =
        SubmittedWorklog(key, date, LocalTime.of(9, 0), tempoWorklogId = 1, rawSeconds = rawSeconds, loggedSeconds = loggedSeconds)

    private fun session(key: String, date: LocalDate, start: String, end: String) =
        WorkSession(key, millis(date, start), millis(date, end))

    private fun millis(date: LocalDate, time: String) =
        date.atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()
}
