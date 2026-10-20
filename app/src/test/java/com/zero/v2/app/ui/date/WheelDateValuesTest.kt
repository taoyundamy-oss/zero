package com.zero.v2.app.ui.date

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class WheelDateValuesTest {
    @Test fun monthAndLeapYearChangesClampDayInsteadOfCreatingInvalidDate() {
        assertEquals(LocalDate.of(2024, 2, 29), WheelDateValues.date(2024, 2, 31))
        assertEquals(LocalDate.of(2025, 2, 28), WheelDateValues.date(2025, 2, 29))
        assertEquals(LocalDate.of(2026, 4, 30), WheelDateValues.date(2026, 4, 31))
    }
    @Test fun existingMillisecondsSurviveAnUnchangedSelection() {
        val original = 1792325709123L
        for (zone in listOf("Asia/Shanghai", "UTC", "America/New_York")) {
            val id = ZoneId.of(zone)
            assertEquals(original, WheelDateValues.epoch(WheelDateValues.local(original, id), id, original))
        }
    }
    @Test fun daylightSavingGapCannotSilentlyMoveTheChosenTime() {
        assertTrue(runCatching { WheelDateValues.epoch(LocalDateTime.of(2026, 3, 8, 2, 30), ZoneId.of("America/New_York")) }.isFailure)
    }
    @Test fun overlapRetainsExistingOffsetAndInstantWhenUnchanged() {
        val date = LocalDateTime.of(2026, 11, 1, 1, 30)
        val id = ZoneId.of("America/New_York")
        for (offset in listOf(ZoneOffset.ofHours(-4), ZoneOffset.ofHours(-5))) {
            val original = date.toInstant(offset).toEpochMilli()
            assertEquals(original, WheelDateValues.epoch(date, id, original))
        }
    }
    @Test fun epochOriginIsPreservedAndEarlierInstantsAreRejected() {
        val zone = ZoneId.of("America/New_York")
        assertEquals(0L, WheelDateValues.epoch(WheelDateValues.local(0, zone), zone, 0))
        assertTrue(runCatching { WheelDateValues.epoch(LocalDateTime.of(1960, 1, 1, 0, 0), zone) }.isFailure)
    }
}
