package com.zero.v2.services.llm

import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.MessageRole
import com.zero.v2.core.common.ProviderContentState
import com.zero.v2.core.common.ToolCall
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponsesProtocolTest {
    private val profile = LlmProfile("luna", "Luna",
        "https://api.cloudflare.com/client/v4/accounts/account/ai/v1", "ref",
        modelId = "openai/gpt-6-luna", gatewayMode = GatewayMode.CF_GATEWAY,
        cloudflareGatewayId = "default", contractId = "cf-luna-responses-v1",
        parameters = mapOf("effort" to "low"), maxTokens = 1536)
    private val output = """[{"type":"reasoning","id":"rs1","encrypted_content":"opaque+/=","summary":[]},
        {"type":"function_call","id":"fc1","call_id":"call1","name":"probe","arguments":"{}","status":"completed"}]"""

    @Test fun `explicit route uses responses while old contract keeps chat`() {
        val request = CloudflareChatTransport.request(profile, "secret").build()
        assertTrue(request.url.toString().endsWith("/responses"))
        assertEquals("Bearer secret", request.header("Authorization"))
        assertEquals("default", request.header("cf-aig-gateway-id"))
        assertTrue(CloudflareChatTransport.request(profile.copy(contractId = "effort-v1"), "secret")
            .build().url.toString().endsWith("/chat/completions"))
    }

    @Test fun `body has stateless reasoning and flat non strict tools`() {
        val body = ChatRequestBody.build(profile, "system", emptyList(), true,
            listOf(FunctionDefinition("probe", "Probe", mapOf("type" to "object"))))
        assertFalse(body.getBoolean("store"))
        assertEquals("low", body.getJSONObject("reasoning").getString("effort"))
        assertEquals(1536, body.getInt("max_output_tokens"))
        assertFalse(body.has("temperature") || body.has("max_tokens") || body.has("messages"))
        assertEquals("probe", body.getJSONArray("tools").getJSONObject(0).getString("name"))
        assertFalse(body.getJSONArray("tools").getJSONObject(0).getBoolean("strict"))
        assertTrue(CacheRequestDiagnostics().describe("x", body).contains("first_request"))
    }

    @Test fun `contract validates exact provider and supported effort`() {
        for (effort in listOf("none", "low", "medium", "high", "xhigh", "max")) {
            assertEquals(null, ModelContracts.validationError(profile.copy(parameters = mapOf("effort" to effort))))
        }
        for (invalid in listOf(profile.copy(parameters = mapOf("effort" to "minimal")),
            profile.copy(gatewayMode = GatewayMode.DIRECT), profile.copy(modelId = "openai/other"))) {
            assertTrue(ModelContracts.validationError(invalid) != null)
        }
        assertEquals("none", ModelContracts.get(profile.contractId).bounded(profile).parameters["effort"])
    }

    @Test fun `complete output replays in order with call id and rejects edited messages`() {
        val assistant = ChatMessage(role = MessageRole.ASSISTANT, content = "",
            toolCalls = listOf(ToolCall("call1", "probe", "{}")),
            providerContent = ProviderContentState(ProviderToolState.scope(profile), output))
        val result = ChatMessage(role = MessageRole.TOOL, content = "TOKEN", toolCallId = "call1")
        val input = ChatRequestBody.build(profile, null, listOf(assistant, result), true, emptyList())
            .getJSONArray("input")
        assertEquals("opaque+/=", input.getJSONObject(0).getString("encrypted_content"))
        assertEquals("fc1", input.getJSONObject(1).getString("id"))
        assertEquals("call1", input.getJSONObject(2).getString("call_id"))
        assertEquals("function_call_output", input.getJSONObject(2).getString("type"))
        assertFalse(ChatRequestBody.build(profile.copy(id = "other"), null, listOf(assistant), true, emptyList())
            .toString().contains("opaque+/="))
        assertTrue(runCatching { ChatRequestBody.build(profile, null,
            listOf(assistant.copy(content = "edited")), true, emptyList()) }.isFailure)
    }

    @Test fun `stream releases complete tools and opaque state only at terminal event`() {
        val stream = ResponsesStream(profile.modelId, "scope")
        assertTrue(stream.decode(JSONObject("""{"type":"response.function_call_arguments.delta","delta":"{"}"""))
            .isEmpty())
        assertFalse(stream.completed)
        val chunks = stream.decode(completed(JSONArray(output)))
        assertTrue(stream.completed)
        assertEquals("call1", chunks[1].toolCallDelta!!.id)
        assertEquals("{}", chunks[1].toolCallDelta!!.argumentsDelta)
        assertEquals("opaque+/=", JSONArray(chunks[0].providerContent!!.contentJson)
            .getJSONObject(0).getString("encrypted_content"))
        assertEquals(12L, chunks.last().tokenUsage!!.outputTokens)
        assertTrue(runCatching { stream.decode(completed(JSONArray(output))) }.isFailure)
    }

    @Test fun `failed incomplete unknown and mismatched text streams fail closed`() {
        for (type in listOf("response.failed", "response.incomplete", "error", "unknown")) {
            assertTrue(runCatching {
                ResponsesStream("model", "scope").decode(JSONObject().put("type", type))
            }.isFailure)
        }
        val stream = ResponsesStream("model", "scope")
        stream.decode(JSONObject().put("type", "response.output_text.delta").put("delta", "partial"))
        assertTrue(runCatching { stream.decode(completed(JSONArray(output))) }.isFailure)
        assertFalse(stream.completed)
    }

    @Test fun `sync and SSE preserve output text and usage`() {
        val items = JSONArray("""[{"type":"message","role":"assistant","phase":"final_answer",
            "content":[{"type":"output_text","text":"OK"}]}]""")
        val stream = ResponsesStream("model", "scope")
        assertEquals("OK", stream.decode(JSONObject().put("type", "response.output_text.delta")
            .put("delta", "OK")).single().contentDelta)
        stream.decode(completed(items))
        val response = completed(items).getJSONObject("response").put("object", "response")
        assertEquals("OK", ResponsesProtocol.normalizeResponse(response).getJSONArray("choices")
            .getJSONObject(0).getJSONObject("message").getString("content"))
        response.put("status", "incomplete")
        assertTrue(runCatching { ResponsesProtocol.normalizeResponse(response) }.isFailure)
    }

    @Test fun `generic CF responses accepts other OpenAI models without inventing effort`() {
        val generic = profile.copy(contractId = "cf-openai-responses-v1", modelId = "openai/gpt-4.1-mini",
            parameters = emptyMap())
        val body = ChatRequestBody.build(generic, null, emptyList(), true, emptyList())
        assertFalse(body.has("reasoning"))
        assertTrue(CloudflareChatTransport.request(generic, "secret").build().url.encodedPath.endsWith("/responses"))
        assertTrue(ModelContracts.get(generic.contractId).bounded(generic).parameters.isEmpty())
    }

    @Test fun `images use input image and tool routed media stays a reference`() {
        val image = com.zero.v2.core.common.Attachment("file:///synthetic.png", "image/png",
            base64Data = "data:image/png;base64,AAAA")
        val vision = profile.copy(capabilities = setOf(ModelCapability.CHAT, ModelCapability.IMAGE_INPUT))
        val msg = ChatMessage(role = MessageRole.USER, content = "Describe", attachments = listOf(image))
        fun payload(message: ChatMessage) = ChatRequestBody.build(vision, null, listOf(message), true, emptyList())
        val items = payload(msg).getJSONArray("input").getJSONObject(0).getJSONArray("content")
        assertEquals("input_image", items.getJSONObject(items.length() - 1).getString("type"))
        assertTrue(payload(msg).toString().contains("AAAA"))
        val toolImage = image.copy(route = com.zero.v2.core.common.MediaRoute.TOOLS)
        assertFalse(payload(msg.copy(attachments = listOf(toolImage))).toString().contains("AAAA"))
    }

    @Test fun `incomplete terminal preserves billable usage and never emits tools`() {
        val event = completed(JSONArray(output)).put("type", "response.incomplete")
        event.getJSONObject("response").put("object", "response").put("status", "incomplete")
        val stream = ResponsesStream("model", "scope")
        val failure = runCatching { stream.decode(event) }.exceptionOrNull() as ResponsesFailure
        assertEquals(12L, failure.usage!!.outputTokens)
        assertFalse(stream.completed)
        val sync = runCatching { ResponsesProtocol.normalizeResponse(event.getJSONObject("response")) }
            .exceptionOrNull() as ResponsesFailure
        assertEquals(32L, sync.usage!!.totalTokens)
        assertFalse(sync.toString().contains("opaque"))
    }

    @Test fun `terminal reason distinguishes exhausted budget without leaking payload`() {
        val event = JSONObject().put("type", "response.incomplete").put("response",
            JSONObject().put("status", "incomplete").put("incomplete_details",
                JSONObject().put("reason", "max_output_tokens")).put("output", "private text"))
        val failure = runCatching { ResponsesStream("m", "s").decode(event) }.exceptionOrNull()!!
        assertTrue(failure.message.orEmpty().contains("max_output_tokens"))
        assertFalse(failure.toString().contains("private text"))
        val error = JSONObject().put("type", "error").put("code", "server_error").put("message", "secret")
        val failed = runCatching { ResponsesStream("m", "s").decode(error) }.exceptionOrNull()!!
        assertTrue(failed.message.orEmpty().contains("server_error"))
        assertFalse(failed.toString().contains("secret"))
    }

    @Test fun `budget recovery is bounded and requires no published output and known input`() {
        fun failure(input: Long) = ResponsesFailure(JSONObject().put("status", "incomplete")
            .put("incomplete_details", JSONObject().put("reason", "max_output_tokens"))
            .put("usage", JSONObject().put("input_tokens", input)))
        val configured = profile.copy(maxTokens = 4096, maxOutputTokens = 32768, contextWindow = 100000)
        assertEquals(8192, ResponsesRecovery.retryBudget(configured, failure(10000), false))
        assertEquals(null, ResponsesRecovery.retryBudget(configured, failure(10000), true))
        assertEquals(null, ResponsesRecovery.retryBudget(configured, failure(0), false))
        assertEquals(null, ResponsesRecovery.retryBudget(configured, failure(95000), false))
        assertEquals(null, ResponsesRecovery.retryBudget(configured.copy(maxTokens = 16384), failure(1000), false))
    }

    @Test fun `stream recovery retries once and preserves both billable usages`() = kotlinx.coroutines.runBlocking {
        var attempts = 0
        val limits = mutableListOf<Int>()
        val chunks = mutableListOf<com.zero.v2.core.common.StreamChunk>()
        val failure = ResponsesFailure(JSONObject().put("status", "incomplete")
            .put("incomplete_details", JSONObject().put("reason", "max_output_tokens"))
            .put("usage", JSONObject().put("input_tokens", 100)))
        ResponsesRecovery.stream(profile, { limits += it }) { active -> kotlinx.coroutines.flow.flow {
            attempts++
            emit(com.zero.v2.core.common.StreamChunk(tokenUsage = failure.usage))
            if (attempts == 1) throw failure
            assertEquals(profile.maxTokens * 2, active.maxTokens)
            emit(com.zero.v2.core.common.StreamChunk(contentDelta = "done"))
        } }.collect { chunks += it }
        assertEquals(2, attempts)
        assertEquals(1, limits.size)
        assertEquals(200L, chunks.sumOf { it.tokenUsage?.inputTokens ?: 0L })
        attempts = 0
        val secondFailure = runCatching {
            ResponsesRecovery.stream(profile, {}) { kotlinx.coroutines.flow.flow {
                attempts++
                throw failure
                @Suppress("UNREACHABLE_CODE") emit(com.zero.v2.core.common.StreamChunk())
            } }.collect { }
        }.exceptionOrNull()
        assertTrue(secondFailure is ResponsesFailure)
        assertEquals(2, attempts)
    }

    @Test fun `partial output cancellation and budget veto do not retry`() = kotlinx.coroutines.runBlocking {
        val exhausted = ResponsesFailure(JSONObject().put("status", "incomplete")
            .put("incomplete_details", JSONObject().put("reason", "max_output_tokens"))
            .put("usage", JSONObject().put("input_tokens", 100)))
        for (mode in listOf("partial", "cancel", "budget")) {
            var attempts = 0
            val outcome = runCatching {
                ResponsesRecovery.stream(profile, { throw java.io.IOException("budget veto") }) {
                    kotlinx.coroutines.flow.flow<com.zero.v2.core.common.StreamChunk> {
                        attempts++
                        if (mode == "partial") emit(com.zero.v2.core.common.StreamChunk(contentDelta = "partial"))
                        if (mode == "cancel") throw kotlinx.coroutines.CancellationException("cancel")
                        throw exhausted
                    }
                }.collect { }
            }.exceptionOrNull()
            assertEquals(1, attempts)
            assertTrue(outcome != null)
            if (mode == "cancel") assertTrue(outcome is kotlinx.coroutines.CancellationException)
            if (mode == "budget") assertEquals("budget veto", outcome?.message)
        }
    }

    @Test fun `sync normalization does not fabricate cache hit when provider omitted it`() {
        val response = JSONObject().put("object", "response").put("status", "completed")
            .put("output", JSONArray()).put("usage", JSONObject().put("input_tokens", 100)
                .put("output_tokens", 5).put("total_tokens", 105))
        assertFalse(ResponsesProtocol.normalizeResponse(response).getJSONObject("usage")
            .has("prompt_cache_hit_tokens"))
    }

    @Test fun `missing input count cannot become fully observed zero through normalization`() {
        val response = JSONObject().put("object", "response").put("status", "completed")
            .put("output", JSONArray()).put("usage", JSONObject()
                .put("input_tokens_details", JSONObject().put("cached_tokens", 0)))
        val normalized = ResponsesProtocol.normalizeResponse(response).getJSONObject("usage")
        assertFalse(normalized.has("prompt_tokens"))
        assertEquals(1L, ProviderUsage.read(normalized).cacheUnknownSamples)
    }

    private fun completed(items: JSONArray) = JSONObject().put("type", "response.completed")
        .put("response", JSONObject().put("status", "completed").put("output", items)
            .put("usage", JSONObject().put("input_tokens", 20).put("output_tokens", 12).put("total_tokens", 32)))
}
