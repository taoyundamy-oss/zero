package com.zero.v2.services.llm

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RequestContextBudgetTest {
    private fun body(): JSONObject = JSONObject().put("model", "test").put("input", JSONArray()
        .put(JSONObject().put("type", "reasoning").put("encrypted_content", "x".repeat(500_000))))

    @Test fun `observed unchanged input does not trigger a false 256k generation switch`() {
        val budget = RequestContextBudget()
        val request = body()
        budget.observe("route-account", request, 90_000)
        request.getJSONArray("input").put(JSONObject().put("type", "function_call_output")
            .put("call_id", "one").put("output", "small new result"))
        assertTrue("observed 90k prefix was counted as 250k wire characters",
            budget.estimate("route-account", request) < 256_000 * 0.9)
    }

    @Test(expected = java.io.IOException::class)
    fun `oversized atomic tool result is rejected before transport`() {
        val request = JSONObject().put("messages", JSONArray()
            .put(JSONObject().put("role", "tool").put("content", "中".repeat(30_000))))
        RequestContextBudget().requireFits("route", request, 24_576, 2048)
    }

    @Test fun `changed route header tools or historical input cannot reuse a measurement`() {
        val budget = RequestContextBudget()
        val request = body()
        budget.observe("first-account", request, 90_000)
        assertTrue(budget.estimate("second-account", request) > 230_400)
        val tools = JSONObject(request.toString()).put("tools", JSONArray().put(JSONObject().put("name", "new")))
        assertTrue(budget.estimate("first-account", tools) > 230_400)
        request.getJSONArray("input").getJSONObject(0).put("encrypted_content", "y".repeat(500_000))
        assertTrue(budget.estimate("first-account", request) > 230_400)
    }

    @Test fun `missing input usage cannot replace a valid observation with zero`() {
        val budget = RequestContextBudget()
        val request = body()
        budget.observe("scope", request, 90_000)
        budget.observe("scope", request, 0)
        assertEquals(90_000, budget.estimate("scope", request))
    }

    @Test fun `hash metadata restores the exact prefix without saving any message text`() {
        val budget = RequestContextBudget()
        val request = body()
        request.getJSONArray("input").put(JSONObject().put("content", "private-marker"))
        budget.observe("hashed-scope", request, 90_000)
        val state = budget.snapshot()
        assertFalse(state.contains("private-marker") || state.contains("encrypted_content"))
        val restored = RequestContextBudget().apply { restore(state) }
        assertEquals(90_000, restored.estimate("hashed-scope", request))
        request.getJSONArray("input").remove(0)
        assertTrue(restored.estimate("hashed-scope", request) < 90_000)
    }

    @Test(expected = java.io.IOException::class)
    fun `larger retry output reuses input measurement but rechecks available space`() {
        val budget = RequestContextBudget()
        val request = body().put("max_output_tokens", 1000)
        budget.observe("scope", request, 90_000)
        budget.requireFits("scope", request, 100_000, 1000)
        request.put("max_output_tokens", 10_000)
        assertEquals(90_000, budget.estimate("scope", request))
        budget.requireFits("scope", request, 100_000, 10_000)
    }

    @Test(expected = java.io.IOException::class)
    fun `large new result cannot hide behind a small observed prefix`() {
        val budget = RequestContextBudget()
        val request = body()
        budget.observe("scope", request, 90_000)
        request.getJSONArray("input").put(JSONObject().put("output", "中".repeat(30_000)))
        budget.requireFits("scope", request, 100_000, 1000)
    }

    @Test fun `production responses encoder keeps native state while observation avoids false switch`() {
        val profile = LlmProfile("test", "Test",
            "https://api.cloudflare.com/client/v4/accounts/fixture/ai/v1", "ref",
            modelId = "openai/gpt-6-luna", gatewayMode = GatewayMode.CF_GATEWAY,
            cloudflareGatewayId = "default", contractId = "cf-luna-responses-v1")
        val native = JSONArray().put(JSONObject().put("type", "reasoning")
            .put("encrypted_content", "x".repeat(500_000)).put("summary", JSONArray()))
            .put(JSONObject().put("type", "function_call").put("call_id", "call")
                .put("name", "probe").put("arguments", "{}"))
        val assistant = com.zero.v2.core.common.ChatMessage(
            role = com.zero.v2.core.common.MessageRole.ASSISTANT, content = "",
            toolCalls = listOf(com.zero.v2.core.common.ToolCall("call", "probe", "{}")),
            providerContent = com.zero.v2.core.common.ProviderContentState(
                ProviderToolState.scope(profile), native.toString()))
        val old = ChatRequestBody.build(profile, "stable", listOf(assistant), true, emptyList())
        val budget = RequestContextBudget()
        budget.observe("scope", old, 90_000)
        val next = ChatRequestBody.build(profile, "stable", listOf(assistant,
            com.zero.v2.core.common.ChatMessage(role = com.zero.v2.core.common.MessageRole.TOOL,
                toolCallId = "call", content = "result")), true, emptyList())
        assertEquals(500_000, next.getJSONArray("input").getJSONObject(1).getString("encrypted_content").length)
        assertTrue(budget.estimate("scope", next) in 90_000..91_000)
    }

    @Test fun `scopes are bounded and integer ceiling cannot wrap around`() {
        val budget = RequestContextBudget()
        val request = body()
        repeat(9) { budget.observe("s$it", request, 100) }
        assertTrue(budget.estimate("s0", request) > 100)
        budget.observe("max", request, Int.MAX_VALUE.toLong())
        request.getJSONArray("input").put(JSONObject().put("content", "more"))
        assertEquals(Int.MAX_VALUE, budget.estimate("max", request))
    }
    @Test fun `fallback reason distinguishes missing shortened and changed observations without content`() {
        val budget = RequestContextBudget()
        val request = body()
        assertEquals("no_observation", budget.inspect("scope", request).source)
        budget.observe("scope", request, 90000)
        assertEquals("observed_prefix", budget.inspect("scope", request).source)
        val shortened = JSONObject(request.toString()).put("input", JSONArray())
        assertEquals("history_shortened", budget.inspect("scope", shortened).source)
        request.getJSONArray("input").getJSONObject(0).put("encrypted_content", "secret-changed")
        assertEquals("history_changed", budget.inspect("scope", request).source)
        assertFalse(budget.inspect("scope", request).diagnostic().contains("secret"))
    }
}
