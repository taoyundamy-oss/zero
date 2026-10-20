package com.zero.v2.services.llm

import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.MessageRole
import com.zero.v2.core.common.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRequestBodyTest {
    private val profile = LlmProfile("arbitrary", "Gateway", "https://example.test/v1", "ref",
        modelId = "provider/wire", cloudflareByok = true, contractId = "deepseek-v1",
        parameters = mapOf("thinking" to "true", "effort" to "high"))

    @Test fun `tool transcript reasoning and stable schema ordering survive contract compilation`() {
        val messages = listOf(
            ChatMessage(role = MessageRole.USER, content = "query"),
            ChatMessage(role = MessageRole.ASSISTANT, content = null, thinking = "provider reasoning",
                toolCalls = listOf(ToolCall("call1", "lookup", "{\"value\":1}"))),
            ChatMessage(role = MessageRole.TOOL, content = "result", toolCallId = "call1"),
        )
        val functions = listOf(FunctionDefinition("z", "last"), FunctionDefinition("a", "first"))
        val body = ChatRequestBody.build(profile, "system", messages, true, functions)
        assertEquals("provider/wire", body.getString("model"))
        val firstTool = body.getJSONArray("tools").getJSONObject(0).getJSONObject("function")
        assertEquals("a", firstTool.getString("name"))
        val transcript = body.getJSONArray("messages")
        assertEquals("system", transcript.getJSONObject(0).getString("role"))
        val assistant = transcript.getJSONObject(2)
        assertEquals("provider reasoning", assistant.getString("reasoning_content"))
        val call = assistant.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("call1", call.getString("id"))
        assertEquals("{\"value\":1}", call.getJSONObject("function").getString("arguments"))
        assertEquals("call1", transcript.getJSONObject(3).getString("tool_call_id"))
        assertEquals("result", transcript.getJSONObject(3).getString("content"))
    }

    @Test fun `sync remains tools free and generic does not inject provider parameters`() {
        val generic = profile.copy(contractId = "generic-v1", parameters = emptyMap())
        val body = ChatRequestBody.build(generic, null,
            listOf(ChatMessage(role = MessageRole.USER, content = "query")), false,
            listOf(FunctionDefinition("lookup", "description")))
        assertFalse(body.has("tools"))
        assertFalse(body.has("thinking"))
        assertFalse(body.has("enable_thinking"))
        assertTrue(body.has("max_tokens"))
    }

    @Test fun `cf contract preserves agent tool identifiers arguments and results`() {
        val cf = profile.copy(contractId = "cf-qwen38-v1", parameters = mapOf("effort" to "low"),
            gatewayMode = GatewayMode.CF_WORKERS, modelId = "@cf/qwen/qwen3.8-27b")
        val messages = listOf(
            ChatMessage(role = MessageRole.ASSISTANT, content = null,
                toolCalls = listOf(ToolCall("call-exact", "lookup", "{\"value\":1}"))),
            ChatMessage(role = MessageRole.TOOL, content = "result", toolCallId = "call-exact"),
        )
        val functions = listOf(FunctionDefinition("lookup", "description"))
        val original = ChatRequestBody.build(cf.copy(contractId = "generic-v1", parameters = emptyMap()),
            "system", messages, true, functions)
        val body = ChatRequestBody.build(cf, "system", messages, true, functions)
        assertEquals(original.getJSONArray("messages").toString(), body.getJSONArray("messages").toString())
        assertEquals(original.getJSONArray("tools").toString(), body.getJSONArray("tools").toString())
        assertEquals(cf.modelId, body.getString("model"))
    }
}
