package com.zero.v2.services.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomousTriggerPolicyTest {
    @Test
    fun classifiesTickTaskAndInteractiveSources() {
        assertEquals(AutonomousTriggerPolicy.TurnType.TICK,
            AutonomousTriggerPolicy.turnType("SELF_DRIVE", ""))
        assertEquals(AutonomousTriggerPolicy.TurnType.TASK,
            AutonomousTriggerPolicy.turnType("AUTOMATION", "run"))
        assertEquals(AutonomousTriggerPolicy.TurnType.INTERACTIVE,
            AutonomousTriggerPolicy.turnType("NOTIFICATION", "alert"))
    }

    @Test
    fun appliesDeferralRules() {
        assertFalse(AutonomousTriggerPolicy.countsAsRealEvent("BATTERY_LOW", ""))
        assertFalse(AutonomousTriggerPolicy.countsAsRealEvent("SCHEDULED_PLAYBACK", "play"))
        assertFalse(AutonomousTriggerPolicy.countsAsRealEvent("SELF_DRIVE", ""))
        assertTrue(AutonomousTriggerPolicy.countsAsRealEvent("CLOUD_TASK", "done"))
    }
}
