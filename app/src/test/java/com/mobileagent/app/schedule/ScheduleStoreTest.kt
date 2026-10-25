package com.mobileagent.app.schedule

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ScheduleStoreTest {

    init {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    @Test
    fun oneTimeRepeatReturnsSameInstant() {
        val t = at(2026, 11, 1, 9, 30)
        assertEquals(t, ScheduleStore.nextOccurrence("none", t))
        assertEquals(t, ScheduleStore.nextOccurrence("", t))
    }

    @Test
    fun hourlyAddsOneHour() {
        val t = at(2026, 11, 1, 9, 30)
        val next = ScheduleStore.nextOccurrence("hourly", t)
        assertEquals(t + 3_600_000L, next)
    }

    @Test
    fun dailyAddsOneDayAcrossMonthBoundary() {
        val t = at(2026, 10, 31, 23, 0)
        val next = ScheduleStore.nextOccurrence("daily", t)
        assertEquals(at(2026, 11, 1, 23, 0), next)
    }

    @Test
    fun weeklyAddsSevenDays() {
        val t = at(2026, 10, 26, 8, 0)
        val next = ScheduleStore.nextOccurrence("weekly", t)
        assertEquals(at(2026, 11, 2, 8, 0), next)
    }

    @Test
    fun unknownRepeatFallsBackToOneTime() {
        val t = at(2026, 11, 1, 9, 0)
        assertEquals(t, ScheduleStore.nextOccurrence("sometimes", t))
    }
}
