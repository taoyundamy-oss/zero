package com.zero.v2.services.module

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationSchedulePolicyTest {
    @Test
    fun derivesIntervalAndDailySchedules() {
        assertEquals("INTERVAL" to "300", AutomationSchedulePolicy.derive("每 5 分钟执行"))
        assertEquals("DAILY" to "08:30", AutomationSchedulePolicy.derive("每天 8:30 执行"))
        assertEquals("DAILY" to "09:00", AutomationSchedulePolicy.derive("每日 9点执行"))
    }

    @Test
    fun computesFutureRunAndRejectsInvalidValues() {
        val now = 1_700_000_000_000L
        assertEquals(now + 60_000, AutomationSchedulePolicy.nextRun("INTERVAL", "60", now))
        assertEquals(Long.MAX_VALUE, AutomationSchedulePolicy.nextRun("INTERVAL", "0", now))
        assertTrue(AutomationSchedulePolicy.nextRun("DAILY", "08:30", now) > now)
    }
}
