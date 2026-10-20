package com.zero.v2.core.data.entity

import org.junit.Assert.*
import org.junit.Test

class MemoryReviewTimesTest {
    @Test fun legacyIdentityIsUnchangedButExplicitUnknownIsDifferent() {
        val old = java.security.MessageDigest.getInstance("SHA-256")
            .digest("1:b1:c4:FACT8:personal".toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(old, MemoryReviewTimes.identity("b", "c", "FACT", "personal", null))
        assertNotEquals(old, MemoryReviewTimes.identity("b", "c", "FACT", "personal", MemoryReviewTimes(null, null, null)))
    }
    @Test fun invalidPeriodsAndNegativeDatesAreRejected() {
        assertTrue(runCatching { MemoryReviewTimes(-1, null, null) }.isFailure)
        assertTrue(runCatching { MemoryReviewTimes(null, 20, 10) }.isFailure)
        assertEquals(20L, MemoryReviewTimes(20, null, null).occurredAt)
    }
}
