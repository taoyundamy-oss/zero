package com.zero.v2.app.ui.settings

import com.zero.v2.framework.memory.MemoryModuleConfig
import com.zero.v2.services.agent.AgentRuntimeConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsOwnershipTest {
    @Test fun `dedicated editors preserve full module ranges including disabled jitter`() {
        val runtime = SettingsOwnership.numericRanges(AgentRuntimeConfig())
        assertEquals(30f..3600f, runtime["baseIntervalSec"])
        assertEquals(0f..600f, runtime["randomOffsetSec"])
        assertEquals(1f..10f, runtime["intervalMultiplier"])
        assertEquals(0f..8f, runtime["maxDoublings"])
        assertTrue(runtime.keys.containsAll(SettingsOwnership.dedicatedFields("agent_runtime")))
        val memory = SettingsOwnership.numericRanges(MemoryModuleConfig())
        assertEquals(10f..2000f, memory["graphExtractionThreshold"])
        assertTrue(memory.keys.containsAll(SettingsOwnership.dedicatedFields("memory")))
        assertTrue(SettingsOwnership.dedicatedFields("unknown-module").isEmpty())
    }
}
