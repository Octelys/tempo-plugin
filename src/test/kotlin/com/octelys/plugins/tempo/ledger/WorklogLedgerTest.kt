package com.octelys.plugins.tempo.ledger

import com.octelys.plugins.tempo.core.WorkSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
