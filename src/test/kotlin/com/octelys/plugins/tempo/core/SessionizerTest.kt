package com.octelys.plugins.tempo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionizerTest {
    private val idleMillis = minutes(5)
    private val sessionizer = Sessionizer { idleMillis }

    @Test
    fun `activity within the idle threshold extends the session`() {
        sessionizer.onActivity(minutes(0), "ABC-1")
        assertNull(sessionizer.onActivity(minutes(4), "ABC-1"))
        assertNull(sessionizer.onActivity(minutes(8), "ABC-1"))

        assertEquals(WorkSession("ABC-1", minutes(0), minutes(8)), sessionizer.close())
    }

    @Test
    fun `an idle gap closes the session at the last activity`() {
        sessionizer.onActivity(minutes(0), "ABC-1")
        sessionizer.onActivity(minutes(3), "ABC-1")

        val closed = sessionizer.onActivity(minutes(20), "ABC-1")

        assertEquals(WorkSession("ABC-1", minutes(0), minutes(3)), closed)
        assertEquals("ABC-1", sessionizer.currentIssueKey)
    }

    @Test
    fun `switching issue closes the session at the switch`() {
        sessionizer.onActivity(minutes(0), "ABC-1")

        val closed = sessionizer.onActivity(minutes(2), "ABC-2")
        sessionizer.onActivity(minutes(5), "ABC-2")

        assertEquals(WorkSession("ABC-1", minutes(0), minutes(2)), closed)
        assertEquals(WorkSession("ABC-2", minutes(2), minutes(5)), sessionizer.close())
    }

    @Test
    fun `closeIfIdle only closes after the threshold`() {
        sessionizer.onActivity(minutes(0), "ABC-1")
        sessionizer.onActivity(minutes(2), "ABC-1")

        assertNull(sessionizer.closeIfIdle(minutes(6)))
        assertEquals(WorkSession("ABC-1", minutes(0), minutes(2)), sessionizer.closeIfIdle(minutes(8)))
        assertNull(sessionizer.currentIssueKey)
    }

    @Test
    fun `checkpoint emits the time so far and keeps tracking`() {
        sessionizer.onActivity(minutes(0), "ABC-1")
        sessionizer.onActivity(minutes(3), "ABC-1")

        val checkpoint = sessionizer.checkpoint()
        sessionizer.onActivity(minutes(5), "ABC-1")

        assertEquals(WorkSession("ABC-1", minutes(0), minutes(3)), checkpoint)
        assertEquals(WorkSession("ABC-1", minutes(3), minutes(5)), sessionizer.close())
    }

    @Test
    fun `a session with no elapsed time is not emitted`() {
        sessionizer.onActivity(minutes(0), "ABC-1")

        assertNull(sessionizer.close())
    }

    @Test
    fun `the open session is reported up to its last activity without closing it`() {
        sessionizer.onActivity(minutes(0), "ABC-1")
        sessionizer.onActivity(minutes(4), "ABC-1")

        assertEquals(WorkSession("ABC-1", minutes(0), minutes(4)), sessionizer.openSession)
        assertEquals(WorkSession("ABC-1", minutes(0), minutes(4)), sessionizer.close())
        assertNull(sessionizer.openSession)
    }

    private fun minutes(value: Int): Long = value * 60_000L
}
