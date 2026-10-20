package com.zero.v2.services.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekVisionUpgradeTest {
    @Test fun `edited official preset gains vision while user tuning is retained`() {
        val profile = LlmProfile.defaults().first { it.id == "deepseek" }.copy(
            temperature = 0.3f, maxTokens = 8192, modelId = "deepseek-v4-flash",
            capabilities = LlmProfile.defaultCapabilities(LlmProfile.Kind.CHAT))
        val updated = DeepSeekVisionUpgrade.apply(profile)
        assertEquals(profile.copy(capabilities = updated.capabilities), updated)
        assertTrue(ModelCapability.IMAGE_INPUT in updated.capabilities)
        assertEquals(updated, DeepSeekVisionUpgrade.apply(updated))
    }

    @Test fun `proxy and unknown models are never assigned unverified capabilities`() {
        val profile = LlmProfile.defaults().first { it.id == "deepseek" }.copy(
            capabilities = LlmProfile.defaultCapabilities(LlmProfile.Kind.CHAT))
        for (custom in listOf(profile.copy(baseUrl = "https://example.com/v1"), profile.copy(modelId = "custom"))) {
            assertEquals(custom, DeepSeekVisionUpgrade.apply(custom))
        }
    }
}
