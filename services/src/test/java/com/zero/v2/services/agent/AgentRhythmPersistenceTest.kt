package com.zero.v2.services.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRhythmPersistenceTest {
    @Test fun snapshotRoundTripKeepsDeadlineAndUserDeferralFields() {
        var disk: String? = null
        val store = AgentRhythmPersistence({ disk }, { disk = it; true })
        val value = AgentRhythmSnapshot(1000, 90000, 4, true, 990, 990, 900, 980, true)
        assertTrue(store.save(value))
        val next = AgentRhythmPersistence({ disk }, { disk = it; true })
        assertEquals(value, next.load())
        assertEquals("restored", next.restoreStatus)
    }

    @Test fun invalidAndFutureVersionAreVisibleInsteadOfInventingADeadline() {
        for (raw in listOf("bad-json", "{\"version\":2}", "{\"version\":1}")) {
            val store = AgentRhythmPersistence({ raw }, { error("Read must not overwrite stored state") })
            assertNull(store.load())
            assertEquals("invalid", store.restoreStatus)
        }
    }

    @Test fun failedCommitIsNotReportedAsDurable() {
        val store = AgentRhythmPersistence({ null }, { false })
        assertFalse(store.save(AgentRhythmSnapshot(1000, 10000, 0, false, 0, 0, 0, 0, false)))
    }
}
