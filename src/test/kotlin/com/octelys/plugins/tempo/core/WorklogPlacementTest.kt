package com.octelys.plugins.tempo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class WorklogPlacementTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val day: LocalDate = LocalDate.of(2026, 11, 26)

    @Test
    fun `a free preferred start is kept`() {
        val start = place("09:00", minutes = 60, taken = listOf(block("10:00", "10:30")))

        assertEquals(LocalTime.of(9, 0), start)
    }

    @Test
    fun `a preferred start off the five minutes is kept when free`() {
        val start = place("08:33:12", minutes = 30)

        assertEquals(LocalTime.of(8, 33, 12), start)
    }

    @Test
    fun `a block that would run into a meeting moves after it`() {
        val start = place("09:00", minutes = 90, taken = listOf(block("10:00", "10:30")))

        assertEquals(LocalTime.of(10, 30), start)
    }

    @Test
    fun `a block moves to the first gap long enough`() {
        val start = place(
            "09:00",
            minutes = 60,
            taken = listOf(block("09:30", "10:00"), block("10:30", "11:00"), block("11:45", "12:00")),
        )

        assertEquals(LocalTime.of(12, 0), start)
    }

    @Test
    fun `touching blocks do not overlap`() {
        val start = place("09:00", minutes = 60, taken = listOf(block("08:00", "09:00"), block("10:00", "11:00")))

        assertEquals(LocalTime.of(9, 0), start)
    }

    @Test
    fun `a moved block starts on five minutes past the hour`() {
        val start = place("09:00", minutes = 30, taken = listOf(block("09:10", "09:42")))

        assertEquals(LocalTime.of(9, 45), start)
    }

    @Test
    fun `with no room later in the day the block goes to the last gap before`() {
        val start = place(
            "20:00",
            minutes = 60,
            taken = listOf(block("00:00", "06:00"), block("07:00", "08:00"), block("16:00", "23:59")),
        )

        assertEquals(LocalTime.of(15, 0), start)
    }

    @Test
    fun `a full day leaves no place`() {
        val start = place("09:00", minutes = 30, taken = listOf(block("00:00", "23:59")))

        assertNull(start)
    }

    @Test
    fun `a block reaching past midnight avoids a worklog on the next day`() {
        val nextMorning = BusySpan(millis(day.plusDays(1), "00:00"), millis(day.plusDays(1), "01:00"))

        val start = place("23:30", minutes = 60, taken = listOf(nextMorning))

        assertEquals(LocalTime.of(23, 0), start)
    }

    @Test
    fun `a block blocked by a meeting goes before it rather than into the future`() {
        val start = place(
            "09:00",
            minutes = 60,
            taken = listOf(block("09:30", "11:00")),
            now = "10:15",
        )

        assertEquals(LocalTime.of(8, 30), start)
    }

    @Test
    fun `a free preferred start running past now moves earlier`() {
        val start = place("09:00", minutes = 60, now = "09:50")

        assertEquals(LocalTime.of(8, 50), start)
    }

    @Test
    fun `a block ending exactly now fits`() {
        val start = place("09:00", minutes = 60, now = "10:00")

        assertEquals(LocalTime.of(9, 0), start)
    }

    @Test
    fun `with no gap before now there is no place`() {
        val start = place("09:00", minutes = 60, taken = listOf(block("00:00", "09:30")), now = "10:00")

        assertNull(start)
    }

    private fun place(preferred: String, minutes: Long, taken: List<BusySpan> = emptyList(), now: String? = null) =
        WorklogPlacement.place(
            day, LocalTime.parse(preferred), minutes * 60, taken, zone,
            latestEndMillis = now?.let { millis(day, it) } ?: Long.MAX_VALUE,
        )

    private fun block(start: String, end: String) = BusySpan(millis(day, start), millis(day, end))

    private fun millis(date: LocalDate, time: String): Long =
        date.atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()
}
