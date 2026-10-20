package com.zero.v2.app.ui.live

import com.zero.v2.services.realtime.RealtimeState
import org.junit.Assert.*
import org.junit.Test

class LiveMotionPolicyTest {
    @Test fun modalCoverageStopsLivePulse() {
        assertFalse(LiveMotionPolicy.shouldPulse(RealtimeState.LISTENING, true, true, false, true))
    }
    @Test fun thinkingHasTheSameBoundedActivityGuard() {
        assertTrue(LiveMotionPolicy.shouldPulse(RealtimeState.THINKING, true, true, false, false))
    }
    @Test fun inactivePausedAndReducedStatesDoNotLoop() {
        for (state in RealtimeState.entries) {
            assertFalse(LiveMotionPolicy.shouldPulse(state, false, true, false, false))
            assertFalse(LiveMotionPolicy.shouldPulse(state, true, false, false, false))
            assertFalse(LiveMotionPolicy.shouldPulse(state, true, true, true, false))
        }
    }
}
