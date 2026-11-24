package com.octelys.plugins.tempo.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationsTest {
    @Test
    fun `less than half a minute shows as zero`() {
        assertEquals("0m", Durations.format(29))
    }

    @Test
    fun `half a minute rounds up`() {
        assertEquals("1m", Durations.format(30))
    }

    @Test
    fun `just under five minutes shows as five minutes`() {
        assertEquals("5m", Durations.format(4 * 60 + 50))
    }

    @Test
    fun `rounding can carry into the next hour`() {
        assertEquals("1h 00m", Durations.format(59 * 60 + 45))
    }

    @Test
    fun `hours keep two digit minutes`() {
        assertEquals("1h 05m", Durations.format(65 * 60))
    }

    @Test
    fun `negative durations show as zero`() {
        assertEquals("0m", Durations.format(-90))
    }
}
