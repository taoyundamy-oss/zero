package com.zero.v2.app.ui.settings

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageFilterTest {
    private val day = LocalDate.of(2026, 10, 13)
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test fun `relative windows advance after midnight and reset stale pagination`() {
        val filter = UsageFilter(UsageWindow(day.minusDays(6), day, zone), offset = 500, rollingDays = 7)
        val next = filter.calendar(day.plusDays(1), zone)
        assertEquals(day.minusDays(5), next.window.first)
        assertEquals(day.plusDays(1), next.window.last)
        assertEquals(0, next.offset)
        assertEquals(filter, filter.calendar(day, zone))
    }

    @Test fun `custom dates stay fixed but adopt device timezone`() {
        val filter = UsageFilter(UsageWindow(day, day, zone), rollingDays = null)
        val changed = filter.calendar(day.plusDays(2), ZoneId.of("America/New_York"))
        assertEquals(day, changed.window.first)
        assertEquals(day, changed.window.last)
        assertEquals(ZoneId.of("America/New_York"), changed.window.zone)
    }
}
