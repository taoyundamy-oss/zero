package com.zero.v2.app.ui.settings

import com.zero.v2.core.data.dao.UsageBucket
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class UsageReportTest {
    @Test fun `weighted denominator retains unknown input and missing attempts`() {
        val rows = listOf(UsageBucket(0, 1, 100, 90, 100, 5, 1, 0, 0, 0, 0),
            UsageBucket(0, 2, 900, 0, 0, 10, 0, 1, 1, 0, 0))
        val total = UsageReport.total(rows)
        assertEquals("90.00%", UsageReport.percent(total.cached, total.observedInput))
        assertEquals("10.00%", UsageReport.percent(total.observedInput, total.input))
        assertEquals(900, total.input - total.observedInput)
        assertEquals(1015, total.input + total.output)
        assertEquals(1, total.unfinished)
    }

    @Test fun `local windows include DST and half hour timezone without assuming 24 hours`() {
        val day = LocalDate.of(2026, 3, 8)
        val dst = UsageWindow(day, day, ZoneId.of("America/New_York"))
        assertEquals(23 * 3600000L, dst.end - dst.start)
        assertEquals(23, UsageReport.chart(emptyList(), dst).size)
        val india = UsageWindow(day, day, ZoneId.of("Asia/Kolkata"))
        assertEquals(30, java.time.Instant.ofEpochMilli(india.start).atZone(ZoneId.of("UTC")).minute)
        assertEquals(24, UsageReport.chart(emptyList(), india).size)
    }

    @Test fun `daily chart totals equal overview and fills inactive dates`() {
        val first = LocalDate.of(2026, 10, 1)
        val window = UsageWindow(first, first.plusDays(2), ZoneId.of("Asia/Shanghai"))
        val rows = listOf(UsageBucket(window.start, 1, 100, 90, 100, 10, 1, 0, 0, 0, 0),
            UsageBucket(window.end - 3600000, 1, 200, 0, 200, 20, 1, 0, 0, 0, 0))
        val chart = UsageReport.chart(rows, window)
        assertEquals(3, chart.size)
        assertEquals(0, chart[1].requests)
        assertEquals(UsageReport.total(rows), UsageReport.total(chart))
        assertEquals("未知", UsageReport.percent(0, 0))
    }
}
