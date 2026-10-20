package com.zero.v2.services.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class MimoAsrUpgradeTest {
    @Test fun `official ASR correction preserves user settings and key references`() {
        val legacy = LlmProfile.defaults().first { it.id == "mimo-asr" }.copy(
            modelId = "MiMo-V2.5-ASR", apiKeyRef = "custom-key-ref", enabled = false,
            temperature = 0.3f, maxTokens = 8192, priority = 37)
        val updated = MimoAsrUpgrade.apply(legacy)
        assertEquals(legacy.copy(modelId = "mimo-v2.5-asr"), updated)
        assertEquals(updated, MimoAsrUpgrade.apply(updated))
    }

    @Test fun `proxy other kinds and custom model identifiers are preserved`() {
        val legacy = LlmProfile.defaults().first { it.id == "mimo-asr" }.copy(modelId = "MiMo-V2.5-ASR")
        for (custom in listOf(legacy.copy(baseUrl = "https://example.com/v1"),
            legacy.copy(kind = LlmProfile.Kind.CHAT), legacy.copy(modelId = "custom-asr"))) {
            assertEquals(custom, MimoAsrUpgrade.apply(custom))
        }
    }

    @Test fun `new installs use the documented API identifier`() {
        assertEquals("mimo-v2.5-asr", LlmProfile.defaults().first { it.id == "mimo-asr" }.modelId)
    }
}
