package com.zero.v2.services.llm

import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.MessageRole
import com.zero.v2.core.common.ProviderContentState
import com.zero.v2.core.common.ToolCall
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptPlanProtocolTest {
    @Test fun `plan terminal empty output uses completed items without releasing tools early`() {
        val stream = ResponsesStream("m", "scope", "zero")
        stream.decode(JSONObject("""{"type":"response.output_text.delta","delta":"SIWC_OK"}"""))
        val items = listOf(
            """{"type":"message","content":[{"type":"output_text","text":"SIWC_OK"}]}""",
            """{"type":"reasoning","encrypted_content":"opaque","summary":[]}""",
            """{"type":"function_call","namespace":"zero","call_id":"c","name":"probe","arguments":"{}"}""",
        )
        items.forEachIndexed { index, item ->
            assertTrue(stream.decode(JSONObject().put("type", "response.output_item.done")
                .put("output_index", index).put("item", JSONObject(item))).isEmpty())
        }
        assertFalse(stream.completed)
        val chunks = stream.decode(JSONObject("""{"type":"response.completed","response":{
            "status":"completed","output":[],"usage":{"input_tokens":24,"output_tokens":7}}}"""))
        assertTrue(stream.completed)
        assertTrue(chunks.first().providerContent!!.contentJson.contains("opaque"))
        assertEquals("probe", chunks[1].toolCallDelta!!.name)
        assertEquals(24L, chunks.last().tokenUsage!!.inputTokens)
    }

    @Test fun `malformed completed output preserves available usage and releases no tools`() {
        val stream = ResponsesStream("m", "s", "zero")
        stream.decode(JSONObject("""{"type":"response.output_text.delta","delta":"wrong"}"""))
        val error = runCatching { stream.decode(JSONObject("""{"type":"response.completed","response":{
            "status":"completed","output":[],"usage":{"input_tokens":24,"output_tokens":7}}}""")) }
            .exceptionOrNull()
        assertTrue(error is ResponsesFailure)
        assertEquals(24L, (error as ResponsesFailure).usage!!.inputTokens)
        assertFalse(stream.completed)
        val unsupported = runCatching { ResponsesStream("m", "s", "zero").decode(JSONObject("""
            {"type":"response.completed","response":{"status":"completed",
            "output":[{"type":"unsupported"}],"usage":{"input_tokens":24,"output_tokens":7}}}
        """)) }.exceptionOrNull()
        assertTrue(unsupported is ResponsesFailure)
        assertEquals(24L, (unsupported as ResponsesFailure).usage!!.inputTokens)
    }
    private val profile = LlmProfile("plan", "Plan", ChatGptPlanProtocol.BASE, "", modelId = "catalog-model",
        connectionId = "oaiapp_test", contractId = ChatGptPlanProtocol.CONTRACT,
        tokenPrices = ModelTokenPrices(subscription = true))

    @Test fun `token rotation retains observed prefix but account and API credential changes remain isolated`() {
        val first = ChatGptPlanProtocol.cacheCredential(profile, "token1")
        assertEquals(first, ChatGptPlanProtocol.cacheCredential(profile, "token2"))
        assertFalse(first == ChatGptPlanProtocol.cacheCredential(profile.copy(connectionId = "oaiapp_other"), "token1"))
        val api = profile.copy(contractId = "generic-v1")
        assertFalse(ChatGptPlanProtocol.cacheCredential(api, "key1") ==
            ChatGptPlanProtocol.cacheCredential(api, "key2"))
        val budget = RequestContextBudget()
        val body = ChatRequestBody.build(profile, "stable", listOf(ChatMessage(role = MessageRole.USER,
            content = "hello")), true, emptyList())
        budget.observe(first, body, 300)
        assertEquals("observed_prefix",
            budget.inspect(ChatGptPlanProtocol.cacheCredential(profile, "token2"), body).source)
    }

    @Test fun `subscription is stateless SSE with developer and namespaced complete tools`() {
        val body = ChatRequestBody.build(profile, "stable", listOf(ChatMessage(role = MessageRole.USER,
            content = "hello")), false, listOf(FunctionDefinition("probe", "Probe", mapOf("type" to "object"))))
        assertTrue(body.getBoolean("stream"))
        assertFalse(body.getBoolean("store"))
        assertEquals("developer", body.getJSONArray("input").getJSONObject(0).getString("role"))
        val tools = body.getJSONArray("tools").getJSONObject(0)
        assertEquals("namespace", tools.getString("type"))
        assertEquals("probe", tools.getJSONArray("tools").getJSONObject(0).getString("name"))
        for (key in listOf("temperature", "max_tokens", "max_output_tokens", "previous_response_id", "tool_search")) {
            assertFalse(body.has(key))
        }
    }

    @Test fun `native reasoning and namespaced calls survive while another account cannot replay`() {
        val output = """[{"type":"reasoning","encrypted_content":"opaque","summary":[]},
            {"type":"function_call","namespace":"zero","call_id":"c1","name":"probe","arguments":"{}"}]"""
        val assistant = ChatMessage(role = MessageRole.ASSISTANT, content = "",
            toolCalls = listOf(ToolCall("c1", "probe", "{}")),
            providerContent = ProviderContentState(ProviderToolState.scope(profile), output))
        val messages = listOf(assistant, ChatMessage(role = MessageRole.TOOL, toolCallId = "c1", content = "ok"))
        val body = ChatRequestBody.build(profile, null, messages, true, emptyList())
        assertTrue(body.toString().contains("opaque"))
        assertEquals("c1", body.getJSONArray("input").getJSONObject(2).getString("call_id"))
        assertFalse(ChatRequestBody.build(profile.copy(connectionId = "oaiapp_other"), null,
            messages, true, emptyList()).toString().contains("opaque"))
        assertEquals(ProviderToolState.scope(profile), ProviderToolState.scope(profile.copy(
            tokenPrices = ModelTokenPrices(subscription = true, input = "1"))))
    }

    @Test fun `invalid endpoint billing or credential settings rejected and no output retry`() {
        for (bad in listOf(profile.copy(baseUrl = "https://example.com/v1"),
            profile.copy(apiKeyRef = "key"), profile.copy(tokenPrices = ModelTokenPrices()),
            profile.copy(gatewayMode = GatewayMode.CF_GATEWAY))) {
            assertTrue(ModelContracts.validationError(bad) != null)
        }
        val failure = ResponsesFailure(JSONObject("""{"status":"incomplete",
            "incomplete_details":{"reason":"max_output_tokens"},"usage":{"input_tokens":100}}"""))
        assertEquals(null, ResponsesRecovery.retryBudget(profile, failure, false))
    }

    @Test fun `subscription quota failure after partial text remains failure and foreign namespace is rejected`() {
        val stream = ResponsesStream("m", "s", "zero")
        stream.decode(JSONObject("""{"type":"response.output_text.delta","delta":"partial"}"""))
        val failed = runCatching { stream.decode(JSONObject("""{"type":"response.failed","response":{
            "status":"failed","error":{"code":"subscription_sharing_usage_limit_exceeded"},
            "usage":{"input_tokens":20,"output_tokens":2,"input_tokens_details":{"cached_tokens":10}}}}""")) }
            .exceptionOrNull() as ResponsesFailure
        assertTrue(failed.message!!.contains("subscription_sharing_usage_limit_exceeded"))
        assertEquals(10L, failed.usage!!.cachedTokens)
        assertFalse(stream.completed)
        assertTrue(runCatching { ResponsesStream("m", "s", "zero").decode(JSONObject("""
            {"type":"response.completed","response":{"status":"completed","output":[
            {"type":"function_call","namespace":"other","call_id":"c","name":"probe","arguments":"{}"}]}}
        """)) }.isFailure)
        assertEquals(profile, ProfileSnapshot.decode(ProfileSnapshot.encode(listOf(profile))).single())
    }
}
