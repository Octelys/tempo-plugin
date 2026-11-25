package com.octelys.plugins.tempo.core

import org.junit.Assert.assertEquals
import org.junit.Test

class BusyTimeTest {
    private val session = WorkSession("ABC-1", 100, 200)

    @Test
    fun `a session with no busy span is entirely free`() {
        assertEquals(listOf(100L until 200L), session.outside(emptyList()))
    }

    @Test
    fun `a busy span in the middle splits the session`() {
        assertEquals(listOf(100L until 120L, 150L until 200L), session.outside(listOf(BusySpan(120, 150))))
    }

    @Test
    fun `busy spans outside the session are ignored`() {
        assertEquals(listOf(100L until 200L), session.outside(listOf(BusySpan(0, 100), BusySpan(200, 300))))
    }

    @Test
    fun `busy spans overlapping the ends trim the session`() {
        assertEquals(listOf(130L until 170L), session.outside(listOf(BusySpan(50, 130), BusySpan(170, 250))))
    }

    @Test
    fun `a busy span covering the session leaves nothing`() {
        assertEquals(emptyList<LongRange>(), session.outside(listOf(BusySpan(0, 300))))
    }

    @Test
    fun `unsorted and overlapping busy spans are merged`() {
        assertEquals(listOf(100L until 110L, 160L until 200L), session.outside(listOf(BusySpan(140, 160), BusySpan(110, 150))))
    }
}
