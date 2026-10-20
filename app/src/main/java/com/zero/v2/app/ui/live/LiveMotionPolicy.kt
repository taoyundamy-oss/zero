package com.zero.v2.app.ui.live

import com.zero.v2.services.realtime.RealtimeState

/** One pulse decision for the renderer; covered modal state must stop background work. */
object LiveMotionPolicy {
    fun shouldPulse(state: RealtimeState, active: Boolean, resumed: Boolean, reduced: Boolean, covered: Boolean): Boolean =
        active && resumed && !reduced && !covered &&
            state in setOf(RealtimeState.LISTENING, RealtimeState.THINKING, RealtimeState.SPEAKING)
}
