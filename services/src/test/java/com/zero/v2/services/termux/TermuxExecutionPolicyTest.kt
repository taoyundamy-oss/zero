package com.zero.v2.services.termux

import org.junit.Assert.assertEquals
import org.junit.Test

class TermuxExecutionPolicyTest {
    @Test
    fun clampsRequestedTimeoutToConfiguredCeiling() {
        assertEquals(120, TermuxExecutionPolicy.resolveTimeoutSeconds(null, 600))
        assertEquals(1, TermuxExecutionPolicy.resolveTimeoutSeconds(0, 600))
        assertEquals(600, TermuxExecutionPolicy.resolveTimeoutSeconds(900, 600))
    }

    @Test
    fun addsSupervisorGraceWindow() {
        assertEquals(135_000L, TermuxExecutionPolicy.requestTimeoutMs(120))
    }
}
