package com.zero.v2.services.llm

import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.MessageRole
import com.zero.v2.core.common.ToolCall
import com.zero.v2.core.common.ToolCallAssembly
import com.zero.v2.core.common.ToolCallDelta
import com.zero.v2.core.common.ToolProviderState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class ToolStateAndSnapshotTest {
    private val profile = LlmProfile("p", "test", "https://example.test/v1", "key", modelId = "m")

    @Test fun `turn freezes collections route budget and missing or rotated credential`() {
        val parameters = mutableMapOf("thinking" to "false")
        val capabilities = mutableSetOf(ModelCapability.CHAT)
        var key: String? = "old-key"
        var live = profile.copy(parameters = parameters, capabilities = capabilities)
        val first = LlmTurnSnapshot(live) { key }
        parameters["thinking"] = "true"
        capabilities.add(ModelCapability.FUNCTION_CALLING)
        key = "new-key"
        live = live.copy(modelId = "new", baseUrl = "https://other.test", maxTokens = 1024)
        repeat(3) {
            assertEquals("m", first.profile.modelId)
            assertEquals("false", first.profile.parameters["thinking"])
            assertEquals(setOf(ModelCapability.CHAT), first.profile.capabilities)
            assertEquals(4096, first.profile.maxTokens)
            assertEquals("old-key", first.credential)
        }
        assertEquals("new", LlmTurnSnapshot(live) { key }.profile.modelId)
        assertEquals("new-key", LlmTurnSnapshot(live) { key }.credential)
        key = null
        val absent = LlmTurnSnapshot(live) { key }
        key = "later"
        assertEquals("", absent.credential)
    }

    @Test fun `parallel fragmented calls retain late signature and two sequential round replays`() {
        val scope = ProviderToolState.scope(profile)
        fun delta(value: String) = OpenAiStreamDecoder.decode(
            """{"choices":[{"delta":{"tool_calls":[$value]}}]}""", "m", scope).single().toolCallDelta!!
        var calls = emptyList<ToolCall>()
        calls = ToolCallAssembly.append(calls, delta(
            """{"index":1,"function":{"arguments":"{"}}"""))
        calls = ToolCallAssembly.append(calls, delta(
            """{"index":0,"id":"a","function":{"name":"lookup","arguments":"{}"}}"""))
        calls = ToolCallAssembly.append(calls, delta(
            """{"index":1,"id":"b","function":{"name":"lookup","arguments":"}"}}"""))
        calls = ToolCallAssembly.append(calls, delta(
            """{"index":0,"extra_content":{"google":{"thought_signature":"exact+/=signature"}}}"""))
        assertEquals(listOf("a", "b"), calls.map { it.id })
        assertEquals(listOf("{}", "{}"), calls.map { it.arguments })
        assertNull(calls[1].providerState)
        val history = mutableListOf(ChatMessage(role = MessageRole.USER, content = "test"))
        repeat(2) { round ->
            history += ChatMessage(role = MessageRole.ASSISTANT, content = null,
                toolCalls = calls.map { it.copy(id = it.id + round) })
            calls.forEach {
                history += ChatMessage(role = MessageRole.TOOL, content = "ok", toolCallId = it.id + round)
            }
        }
        val body = ChatRequestBody.build(profile, null, history, true, emptyList())
        listOf(1, 4).forEach { index ->
            val wire = body.getJSONArray("messages").getJSONObject(index).getJSONArray("tool_calls")
            assertEquals("exact+/=signature", wire.getJSONObject(0).getJSONObject("extra_content")
                .getJSONObject("google").getString("thought_signature"))
            assertFalse(wire.getJSONObject(1).has("extra_content"))
        }
        val foreign = JSONObject()
        ProviderToolState.replay(foreign, calls[0].providerState, profile.copy(baseUrl = "https://foreign.test"))
        assertFalse(foreign.has("extra_content"))
        assertFalse(calls[0].toString().contains("exact+/=signature"))
    }

    @Test fun `index and id mutation or conflicting signature fail closed`() {
        val state = ToolProviderState("scope", "a")
        val calls = ToolCallAssembly.append(emptyList(), ToolCallDelta(0, "a", "lookup", "{}", state))
        assertTrue(runCatching { ToolCallAssembly.append(calls, ToolCallDelta(0, "b")) }.isFailure)
        assertTrue(runCatching { ToolCallAssembly.append(calls, ToolCallDelta(-1)) }.isFailure)
        assertTrue(runCatching {
            ToolCallAssembly.append(calls, ToolCallDelta(0, providerState = ToolProviderState("scope", "b")))
        }.isFailure)
    }
}
