package com.octelys.plugins.tempo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FocusTrackerTest {
    private val focus = FocusTracker<String>()

    @Test
    fun `the first activity has no previous target`() {
        assertNull(focus.moveTo("solution-1"))
        assertEquals("solution-1", focus.current)
    }

    @Test
    fun `activity on the same target reports no change`() {
        focus.moveTo("solution-1")

        assertNull(focus.moveTo("solution-1"))
    }

    @Test
    fun `moving to another target returns the one left behind`() {
        focus.moveTo("solution-1")

        assertEquals("solution-1", focus.moveTo("solution-2"))
        assertEquals("solution-2", focus.current)
    }

    @Test
    fun `time spent in another target is not credited to the one left behind`() {
        val idleMillis = minutes(5)
        val sessionizers = mapOf("solution-1" to Sessionizer { idleMillis }, "solution-2" to Sessionizer { idleMillis })
        val sessions = mutableListOf<WorkSession>()

        fun activity(target: String, atMillis: Long, issueKey: String) {
            focus.moveTo(target)?.let { left -> sessionizers.getValue(left).close()?.let(sessions::add) }
            sessionizers.getValue(target).onActivity(atMillis, issueKey)?.let(sessions::add)
        }

        // 1 minute in solution 1, 2 in solution 2, then 2 more back in solution 1.
        activity("solution-1", minutes(0), "ABC-1")
        activity("solution-1", minutes(1), "ABC-1")
        activity("solution-2", minutes(1), "ABC-2")
        activity("solution-2", minutes(3), "ABC-2")
        activity("solution-1", minutes(3), "ABC-1")
        activity("solution-1", minutes(5), "ABC-1")
        sessionizers.values.forEach { it.close()?.let(sessions::add) }

        assertEquals(
            listOf(
                WorkSession("ABC-1", minutes(0), minutes(1)),
                WorkSession("ABC-2", minutes(1), minutes(3)),
                WorkSession("ABC-1", minutes(3), minutes(5)),
            ),
            sessions,
        )
    }

    private fun minutes(value: Int): Long = value * 60_000L
}
