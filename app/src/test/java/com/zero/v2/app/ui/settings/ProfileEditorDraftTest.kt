package com.zero.v2.app.ui.settings

import com.zero.v2.services.llm.LlmProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileEditorDraftTest {
    private val profile = LlmProfile.localQwen("http://127.0.0.1:8080/v1", "isolated")
    private val draft = ProfileEditorDraft(profile.displayName, profile.modelId, profile.baseUrl, profile.voice,
        "0.7", "2048", false, "high", "1024")

    @Test fun responsesContractValidatesNewRouteAndModelInsteadOfSavedLocalProfile() {
        val changed = draft.copy(modelId = "openai/gpt-6-luna",
            baseUrl = "https://api.cloudflare.com/client/v4/accounts/account/ai/v1",
            gatewayMode = com.zero.v2.services.llm.GatewayMode.CF_GATEWAY,
            cloudflareGatewayId = "default", localLlamaPreflight = false,
            contractId = "cf-luna-responses-v1", parameters = mapOf("effort" to "medium"))
        assertNull(changed.validationError(profile))
        assertEquals("openai/gpt-6-luna", changed.toProfile(profile).modelId)
        assertNotNull(changed.copy(modelId = "openai/other").validationError(profile))
    }

    @Test fun rejectsMalformedAndOutOfRangeValuesInsteadOfFallback() {
        for (value in listOf("invalid", "0", "32769", "2147483648", "24576")) {
            assertNotNull(draft.copy(maxTokens = value).validationError(profile))
        }
        for (value in listOf("NaN", "Infinity", "-1", "2.1")) {
            assertNotNull(draft.copy(temperature = value).validationError(profile))
        }
        assertNotNull(draft.copy(baseUrl = "not-an-url").validationError(profile))
        assertNotNull(draft.copy(modelId = " ").validationError(profile))
    }

    @Test fun validatesEnabledThinkingAndPreservesInactiveParameters() {
        assertNotNull(draft.copy(contractId = "dashscope-v1", parameters = mapOf("thinking" to "true", "budget" to "invalid")).validationError(profile))
        assertNotNull(draft.copy(contractId = "deepseek-v1", parameters = mapOf("effort" to "unexpected")).validationError(profile))
        val inactive = draft.copy(thinkingBudget = "invalid", reasoningEffort = "unexpected")
        assertNull(inactive.validationError(profile))
        assertEquals(profile.thinkingBudget, inactive.toProfile(profile).thinkingBudget)
        assertEquals(profile.capabilities, inactive.toProfile(profile).capabilities)
        assertEquals(profile.contextWindow, inactive.toProfile(profile).contextWindow)
    }
    @Test fun cloudflareDraftRequiresRouteAndKeepsAgentCapabilities() {
        val gateway = draft.copy(cloudflareByok = true, localLlamaPreflight = false,
            baseUrl = "https://api.cloudflare.com/client/v4/accounts/account/ai/v1",
            modelId = "google-ai-studio/gemini-3.8-flash", cloudflareGatewayId = "zero")
        assertNull(gateway.validationError(profile))
        assertNotNull(gateway.copy(cloudflareGatewayId = "").validationError(profile))
        val saved = gateway.toProfile(profile)
        assertEquals(true, saved.cloudflareByok)
        assertEquals("zero", saved.cloudflareGatewayId)
        assertEquals(profile.capabilities, saved.capabilities)
        assertEquals(profile.id, saved.id)
        assertEquals(profile.apiKeyRef, saved.apiKeyRef)
        assertNotNull(gateway.copy(localLlamaPreflight = true).validationError(profile))
        assertEquals(false, saved.localLlamaPreflight)
    }
    @Test fun contractAndLimitsRemainExplicitAndSwitchingDoesNotDiscardParameters() {
        val configured = draft.copy(contractId = "deepseek-v1",
            parameters = mapOf("thinking" to "true", "effort" to "max"),
            contextWindow = "65536", maxOutputTokens = "8192")
        assertNull(configured.validationError(profile))
        val saved = configured.toProfile(profile)
        assertEquals("max", saved.parameters["effort"])
        assertEquals(65536, saved.contextWindow)
        assertNotNull(configured.copy(contractId = "generic-v1").validationError(profile))
        assertNotNull(configured.copy(maxOutputTokens = "100").validationError(profile))
        assertNotNull(configured.copy(contextWindow = "invalid").validationError(profile))
    }
}
