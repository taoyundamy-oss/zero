package com.zero.v2.services.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomousTickCadenceTest {
    @Test fun `many scheduler observations cannot advance the autonomous deadline`() {
        val cadence = AutonomousTickCadence()
        assertTrue(cadence.isDue(0))
        cadence.schedule(0, 90_000)
        repeat(90) { second -> assertFalse(cadence.isDue(second * 1_000L)) }
        assertEquals(1_000L, cadence.remaining(89_000))
        assertTrue(cadence.isDue(90_000))
        cadence.schedule(90_000, 270_000)
        assertFalse(cadence.isDue(359_999))
        assertTrue(cadence.isDue(360_000))
    }
}
