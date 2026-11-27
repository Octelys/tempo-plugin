package com.octelys.plugins.tempo.ledger

import com.octelys.plugins.tempo.core.PlannedWorklog
import com.octelys.plugins.tempo.core.WorkSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class WorklogLedgerTest {
    private val ledger = WorklogLedger()

    @Test
    fun `an open session is kept apart from pending time`() {
        ledger.initializeComponent()

        ledger.record("project", closed = null, open = WorkSession("ABC-1", 0, 1_000))

        assertTrue(ledger.pending().isEmpty())
        assertEquals(listOf(WorkSession("ABC-1", 0, 1_000)), ledger.open())
    }

    @Test
    fun `a submitted worklog keeps the time logged in Tempo and leaves pending`() {
        val session = WorkSession("ABC-1", 0, 50 * 60_000L)
        ledger.record(null, closed = session, open = null)
        val worklog = PlannedWorklog(
            issueKey = "ABC-1",
            date = LocalDate.of(2026, 11, 26),
            startTime = LocalTime.of(9, 0),
            totalRawSeconds = 50 * 60L,
            pendingSeconds = 50 * 60L,
            roundedSeconds = 60 * 60L,
            existingWorklogId = null,
            sessions = listOf(session),
        )

        ledger.markSubmitted(worklog, tempoWorklogId = 42)

        assertTrue(ledger.pending().isEmpty())
        val submitted = ledger.submitted().single()
        assertEquals(50 * 60L, submitted.rawSeconds)
        assertEquals(60 * 60L, submitted.loggedSeconds)
    }

    @Test
    fun `recording the open session replaces the previous one of that project only`() {
        ledger.initializeComponent()
        ledger.record("one", closed = null, open = WorkSession("ABC-1", 0, 1_000))
        ledger.record("two", closed = null, open = WorkSession("ABC-2", 0, 1_000))

        ledger.record("one", closed = null, open = WorkSession("ABC-1", 0, 2_000))

        assertEquals(setOf(WorkSession("ABC-1", 0, 2_000), WorkSession("ABC-2", 0, 1_000)), ledger.open().toSet())
    }

    @Test
    fun `closing a session moves it to pending and clears the open one`() {
        ledger.initializeComponent()
        ledger.record("project", closed = null, open = WorkSession("ABC-1", 0, 1_000))

        ledger.record("project", closed = WorkSession("ABC-1", 0, 1_500), open = null)

        assertEquals(listOf(WorkSession("ABC-1", 0, 1_500)), ledger.pending())
        assertTrue(ledger.open().isEmpty())
    }

    @Test
    fun `open sessions left in the file become pending on the first load`() {
        val saved = stateWithOpenSession(WorkSession("ABC-1", 0, 1_000))

        ledger.loadState(saved)
        ledger.initializeComponent()

        assertEquals(listOf(WorkSession("ABC-1", 0, 1_000)), ledger.pending())
        assertTrue(ledger.open().isEmpty())
    }

    @Test
    fun `a reload keeps the live open sessions and does not make them pending`() {
        ledger.initializeComponent()
        ledger.record("project", closed = null, open = WorkSession("ABC-1", 0, 2_000))

        ledger.loadState(stateWithOpenSession(WorkSession("ABC-1", 0, 1_000)))

        assertTrue(ledger.pending().isEmpty())
        assertEquals(listOf(WorkSession("ABC-1", 0, 2_000)), ledger.open())
    }

    @Test
    fun `the saved state includes the open sessions`() {
        ledger.initializeComponent()
        ledger.record("project", closed = null, open = WorkSession("ABC-1", 0, 1_000))

        val saved = ledger.state.open.single()

        assertEquals("project", saved.projectId)
        assertEquals(1_000, saved.endMillis)
    }

    private fun stateWithOpenSession(session: WorkSession) = WorklogLedger.LedgerState().apply {
        open += WorklogLedger.OpenEntry().apply {
            projectId = "project"
            issueKey = session.issueKey
            startMillis = session.startMillis
            endMillis = session.endMillis
        }
    }
}
