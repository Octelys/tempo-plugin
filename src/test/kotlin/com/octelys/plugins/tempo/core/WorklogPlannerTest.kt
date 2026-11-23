package com.octelys.plugins.tempo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class WorklogPlannerTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val today: LocalDate = LocalDate.of(2026, 11, 23)
    private val yesterday: LocalDate = today.minusDays(1)

    @Test
    fun `sessions on the same issue and day become one worklog`() {
        val plan = plan(
            pending = listOf(session("ABC-1", today, "09:00", "09:40"), session("ABC-1", today, "13:00", "13:20")),
            roundingMinutes = 1,
        )

        val worklog = plan.worklogs.single()
        assertEquals("ABC-1", worklog.issueKey)
        assertEquals(LocalTime.of(9, 0), worklog.startTime)
        assertEquals(60 * 60L, worklog.roundedSeconds)
        assertNull(worklog.existingWorklogId)
        assertEquals(2, worklog.sessions.size)
    }

    @Test
    fun `different issues and days get separate worklogs`() {
        val plan = plan(
            pending = listOf(
                session("ABC-1", today, "09:00", "09:30"),
                session("ABC-2", today, "10:00", "10:30"),
                session("ABC-1", yesterday, "09:00", "09:30"),
            ),
            roundingMinutes = 1,
        )

        assertEquals(
            listOf("ABC-1" to yesterday, "ABC-1" to today, "ABC-2" to today),
            plan.worklogs.map { it.issueKey to it.date },
        )
    }

    @Test
    fun `time already submitted that day updates the existing worklog`() {
        val submitted = SubmittedWorklog("ABC-1", today, LocalTime.of(8, 30), tempoWorklogId = 42, rawSeconds = 50 * 60L)

        val plan = plan(
            pending = listOf(session("ABC-1", today, "11:00", "11:25")),
            submitted = listOf(submitted),
            roundingMinutes = 15,
        )

        val worklog = plan.worklogs.single()
        assertEquals(42L, worklog.existingWorklogId)
        assertEquals(LocalTime.of(8, 30), worklog.startTime)
        assertEquals(75 * 60L, worklog.totalRawSeconds)
        assertEquals(75 * 60L, worklog.roundedSeconds)
    }

    @Test
    fun `rounding goes to the nearest increment`() {
        val plan = plan(pending = listOf(session("ABC-1", today, "09:00", "09:23")), roundingMinutes = 15)

        assertEquals(30 * 60L, plan.worklogs.single().roundedSeconds)
    }

    @Test
    fun `today's time that rounds to zero stays pending`() {
        val plan = plan(pending = listOf(session("ABC-1", today, "09:00", "09:05")), roundingMinutes = 15)

        assertTrue(plan.worklogs.isEmpty())
        assertTrue(plan.discarded.isEmpty())
    }

    @Test
    fun `a past day's time that rounds to zero is rounded up to one increment`() {
        val plan = plan(pending = listOf(session("ABC-1", yesterday, "09:00", "09:05")), roundingMinutes = 15)

        assertEquals(15 * 60L, plan.worklogs.single().roundedSeconds)
    }

    @Test
    fun `a past day's time under a minute is discarded`() {
        val tiny = WorkSession("ABC-1", millis(yesterday, "09:00"), millis(yesterday, "09:00") + 30_000)

        val plan = plan(pending = listOf(tiny), roundingMinutes = 15)

        assertTrue(plan.worklogs.isEmpty())
        assertEquals(listOf(tiny), plan.discarded)
    }

    private fun plan(
        pending: List<WorkSession>,
        submitted: List<SubmittedWorklog> = emptyList(),
        roundingMinutes: Int,
    ) = WorklogPlanner.plan(pending, submitted, roundingMinutes, today, zone)

    private fun session(issueKey: String, date: LocalDate, start: String, end: String) =
        WorkSession(issueKey, millis(date, start), millis(date, end))

    private fun millis(date: LocalDate, time: String): Long =
        date.atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()
}
