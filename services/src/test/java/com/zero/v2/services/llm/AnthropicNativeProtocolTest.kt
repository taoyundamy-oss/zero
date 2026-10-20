package com.zero.v2.services.llm

import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.MessageRole
import com.zero.v2.core.common.ProviderContentState
import com.zero.v2.core.common.ToolCall
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicNativeProtocolTest {
    private val profile = LlmProfile("native", "Native",
        "https://api.cloudflare.com/client/v4/accounts/account/ai/v1", "ref",
        modelId = "anthropic/claude-haiku-4-5", gatewayMode = GatewayMode.CF_GATEWAY,
        cloudflareGatewayId = "default", contractId = "cf-anthropic-native-v1",
        parameters = mapOf("thinking" to "true", "budget" to "1024"), maxTokens = 1536)

    @Test fun `native route preserves CF auth and explicit thinking budget`() {
        val request = CloudflareChatTransport.request(profile, "secret").build()
        assertEquals("https://gateway.ai.cloudflare.com/v1/account/default/anthropic/v1/messages",
            request.url.toString())
        assertEquals("Bearer secret", request.header("cf-aig-authorization"))
        assertNull(request.header("x-api-key"))
        val body = ChatRequestBody.build(profile, "system",
            listOf(ChatMessage(role = MessageRole.USER, content = "Hi")), true, emptyList())
        assertEquals("claude-haiku-4-5", body.getString("model"))
        assertEquals(1024, body.getJSONObject("thinking").getInt("budget_tokens"))
        assertFalse(body.has("temperature"))
        assertEquals("true", CloudflareChatTransport.request(profile.copy(gatewayMode = GatewayMode.CF_BYOK), "secret")
            .build().header("cf-aig-no-wholesale"))
    }

    @Test fun `native budget and provider validation fail before network`() {
        for (invalid in listOf(profile.copy(maxTokens = 1024), profile.copy(parameters = mapOf("thinking" to "true")),
            profile.copy(gatewayMode = GatewayMode.DIRECT), profile.copy(modelId = "openai/model"))) {
            assertNotNull(ModelContracts.validationError(invalid))
        }
        val disabled = ModelContracts.get(profile.contractId).bounded(profile.copy(maxTokens = 256))
        assertNull(ModelContracts.validationError(disabled))
    }

    @Test fun `native complete content preserves signature order and tool results with route isolation`() {
        val blocks = """[{"type":"thinking","thinking":"summary","signature":"abc+/="},
            {"type":"redacted_thinking","data":"opaque+/="},
            {"type":"tool_use","id":"call1","name":"probe","input":{"step":"1"}}]"""
        val assistant = ChatMessage(role = MessageRole.ASSISTANT, content = "",
            toolCalls = listOf(ToolCall("call1", "probe", "{\"step\":\"1\"}")),
            providerContent = ProviderContentState(ProviderToolState.scope(profile), blocks))
        val messages = listOf(assistant, ChatMessage(role = MessageRole.TOOL, content = "TOKEN", toolCallId = "call1"))
        val body = ChatRequestBody.build(profile, null, messages, true, emptyList())
        val restored = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("abc+/=", restored.getJSONObject(0).getString("signature"))
        assertEquals("opaque+/=", restored.getJSONObject(1).getString("data"))
        val toolResult = body.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(0)
        assertEquals("call1", toolResult.getString("tool_use_id"))
        val other = ChatRequestBody.build(profile.copy(id = "other"), null, messages, true, emptyList()).toString()
        assertFalse(other.contains("abc+/="))
        assertFalse(other.contains("opaque+/="))
        assertTrue(runCatching {
            ChatRequestBody.build(profile, null, listOf(assistant.copy(toolCalls = emptyList())), true, emptyList())
        }.isFailure)
    }

    @Test fun `signature fragments assemble only after all blocks close`() {
        val decoder = CloudflareAnthropicStream(profile.modelId, "scope")
        fun send(s: String) = decoder.decode(JSONObject(s))
        send("""{"type":"message_start","message":{"usage":{}}}""")
        send("""{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""")
        send("""{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"summary"}}""")
        send("""{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"abc+"}}""")
        send("""{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"/="}}""")
        send("""{"type":"content_block_stop","index":0}""")
        send("""{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""")
        val chunks = send("""{"type":"message_stop"}""")
        val state = chunks.first().providerContent!!
        assertEquals("abc+/=",
            org.json.JSONArray(state.contentJson).getJSONObject(0).getString("signature"))
        assertTrue(chunks.last().isFinal)
    }

    @Test fun `missing signatures and truncated blocks fail closed`() {
        val assembly = AnthropicContentAssembly("scope")
        assembly.accept(JSONObject("""{"type":"content_block_start","index":0,
            "content_block":{"type":"thinking","thinking":""}}"""))
        assertTrue(runCatching { assembly.finish() }.isFailure)
        assertTrue(runCatching { assembly.accept(JSONObject("""{"type":"content_block_stop","index":0}""")) }.isFailure)
    }

    @Test fun `native empty tool input and opaque diagnostics are safe`() {
        val assembly = AnthropicContentAssembly("scope")
        assembly.accept(JSONObject("""{"type":"content_block_start","index":0,
            "content_block":{"type":"tool_use","id":"c","name":"noop","input":{}}}"""))
        assembly.accept(JSONObject("""{"type":"content_block_stop","index":0}"""))
        assertEquals("{}", assembly.closedTool(0).single().toolCallDelta!!.argumentsDelta)
        assertEquals("ProviderContentState(redacted)", assembly.finish().toString())
    }
}
