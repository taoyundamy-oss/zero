package com.zero.v2.services.llm

import com.zero.v2.core.common.ProviderContentState
import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PersistentProviderContractsTest {
    @Test fun responsesMissingOutputUsageStaysUnknown() {
        val payload = org.json.JSONObject().put("object", "response").put("status", "completed")
            .put("output", org.json.JSONArray()).put("usage", org.json.JSONObject().put("input_tokens", 10))
        val normalized = ResponsesProtocol.normalizeResponse(payload).getJSONObject("usage")
        assertFalse(normalized.has("completion_tokens"))
        assertFalse(normalized.has("total_tokens"))
        assertFalse(ProviderUsage.read(normalized).outputObserved)
    }
    private val base =
        LlmProfile.defaults()
            .first()
            .copy(
                baseUrl = "https://api.openai.com/v1",
                gatewayMode = GatewayMode.DIRECT,
                cloudflareByok = false,
                contractId = "openai-responses-v1",
                modelId = "gpt-example",
                parameters = mapOf("effort" to "high"))

    @Test
    fun directResponsesPreservesStatelessNativeHistoryAndCannotUseChatGptAccountRoute() {
        val request = CloudflareChatTransport.request(base, "synthetic").build()
        assertEquals("https://api.openai.com/v1/responses", request.url.toString())
        assertNull(request.header("cf-aig-gateway-id"))
        val output = """[{"type":"reasoning","id":"r","encrypted_content":"opaque","summary":[]}]"""
        val msg =
            ChatMessage(
                role = MessageRole.ASSISTANT,
                content = "",
                providerContent = ProviderContentState(ProviderToolState.scope(base), output))
        val body = ChatRequestBody.build(base, "stable", listOf(msg), true, emptyList())
        assertFalse(body.getBoolean("store"))
        assertEquals(
            "opaque", body.getJSONArray("input").getJSONObject(1).getString("encrypted_content"))
        listOf(
                "https://chatgpt.com/backend-api/codex",
                "http://api.openai.com/v1",
                "https://api.openai.com/v1?route=other")
            .forEach { assertNotNull(ModelContracts.validationError(base.copy(baseUrl = it))) }
    }

    @Test
    fun zhipuPreservesFullReasoningAndRejectsForcedThinkingDisable() {
        val p =
            base.copy(
                contractId = "zhipu-thinking-v1",
                modelId = "glm-4.7",
                parameters = mapOf("thinking" to "true", "clear_thinking" to "false"))
        val messages =
            listOf(
                ChatMessage(role = MessageRole.ASSISTANT, content = "答复", thinking = "原始推理\n不得改写"))
        for (stream in listOf(true, false)) {
            val body = ChatRequestBody.build(p, "stable", messages, stream, emptyList())
            assertFalse(body.getJSONObject("thinking").getBoolean("clear_thinking"))
            assertEquals(
                messages.single().thinking,
                body.getJSONArray("messages").getJSONObject(1).getString("reasoning_content"))
        }
        assertNotNull(
            ModelContracts.validationError(
                p.copy(modelId = "glm-5.3", parameters = mapOf("thinking" to "false"))))
    }

    @Test
    fun geminiEffortDoesNotInjectQwenParametersOrOfferUnsupportedThinkingOff() {
        val p = base.copy(contractId = "gemini-openai-v1", parameters = mapOf("effort" to "medium"))
        val body = ChatRequestBody.build(p, "stable", emptyList(), true, emptyList())
        assertEquals("medium", body.getString("reasoning_effort"))
        assertFalse(body.has("enable_thinking"))
        assertNotNull(
            ModelContracts.validationError(p.copy(parameters = mapOf("effort" to "none"))))
    }
}
