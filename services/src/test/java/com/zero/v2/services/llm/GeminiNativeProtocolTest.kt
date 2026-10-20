package com.zero.v2.services.llm

import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.MessageRole
import com.zero.v2.core.common.ToolCall
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiNativeProtocolTest {
    @Test fun failedSyncRetainsObservedUsage() {
        val payload = JSONObject().put(
            "candidates", org.json.JSONArray().put(JSONObject().put("finishReason", "MAX_TOKENS")))
            .put("usageMetadata", JSONObject().put("promptTokenCount", 100).put("cachedContentTokenCount", 90))
        val failure = runCatching { GeminiNativeProtocol.normalize(payload) }.exceptionOrNull()
        assertTrue(failure is GeminiCompletionFailure)
        assertEquals(90L, (failure as GeminiCompletionFailure).usage!!.cachedTokens)
    }
    private val p =
        LlmProfile.defaults()
            .first()
            .copy(
                baseUrl = "https://generativelanguage.googleapis.com/v1beta",
                contractId = "gemini-native-v1",
                modelId = "gemini-test",
                gatewayMode = GatewayMode.DIRECT,
                cloudflareByok = false,
                authMode = ConnectionAuth.HEADER,
                authHeader = "x-goog-api-key",
                parameters = mapOf("effort" to "low"))

    // Wire fixtures intentionally preserve complete JSON on one line.
    @Suppress("MaxLineLength")
    @Test
    fun nativeToolSignatureSurvivesStreamReplayAndRoutesDifferByStreaming() {
        val stream = GeminiNativeStream(p.modelId, ProviderToolState.scope(p))
        val chunks =
            stream.decode(
                JSONObject(
                    """{"candidates":[{"content":{"parts":[{"thought":true,"text":"想法"},{"functionCall":{"id":"native-1","name":"lookup","args":{"key":"a"}},"thoughtSignature":"opaque+/="}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":100,"cachedContentTokenCount":90,"candidatesTokenCount":2,"thoughtsTokenCount":3,"totalTokenCount":105}}"""))
        val delta = chunks.firstNotNullOf { it.toolCallDelta }
        val message =
            ChatMessage(
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(ToolCall(delta.id!!, delta.name!!, delta.argumentsDelta!!)),
                providerContent = chunks.firstNotNullOf { it.providerContent })
        val body =
            ChatRequestBody.build(
                p,
                "stable",
                listOf(
                    message,
                    ChatMessage(
                        role = MessageRole.TOOL, content = "result", toolCallId = delta.id)),
                true,
                emptyList())
        assertEquals(
            "opaque+/=",
            body
                .getJSONArray("contents")
                .getJSONObject(0)
                .getJSONArray("parts")
                .getJSONObject(1)
                .getString("thoughtSignature"))
        assertEquals(
            "lookup",
            body
                .getJSONArray("contents")
                .getJSONObject(1)
                .getJSONArray("parts")
                .getJSONObject(0)
                .getJSONObject("functionResponse")
                .getString("name"))
        assertEquals(
            "native-1",
            body
                .getJSONArray("contents")
                .getJSONObject(1)
                .getJSONArray("parts")
                .getJSONObject(0)
                .getJSONObject("functionResponse")
                .getString("id"))
        assertEquals(5L, chunks.firstNotNullOf { it.tokenUsage }.outputTokens)
        assertEquals(90L, chunks.firstNotNullOf { it.tokenUsage }.cachedTokens)
        assertTrue(CacheRequestDiagnostics().describe("scope", body).contains("first_request"))
        assertTrue(RequestContextBudget().estimate("scope", body) > 0)
    }

    @Test fun nativeRoutesUseDistinctMethods() {
        assertTrue(
            CloudflareChatTransport.request(p, "synthetic", true)
                .build()
                .url
                .toString()
                .endsWith(":streamGenerateContent?alt=sse"))
        assertTrue(
            CloudflareChatTransport.request(p, "synthetic")
                .build()
                .url
                .toString()
                .endsWith(":generateContent"))
        assertEquals(
            "synthetic",
            CloudflareChatTransport.request(p, "synthetic").build().header("x-goog-api-key"))
    }
    @Suppress("MaxLineLength")
    @Test
    fun unknownCacheStaysUnknownAndInvalidContractAndUnsupportedOutputAreRejected() {
        val usage =
            ProviderUsage.read(
                GeminiNativeProtocol.usage(
                    JSONObject("""{"promptTokenCount":10,"candidatesTokenCount":1}""")))
        assertEquals(1L, usage.cacheUnknownSamples)
        assertNotNull(
            ModelContracts.validationError(
                p.copy(parameters = mapOf("effort" to "low", "budget" to "1024"))))
        assertNotNull(
            ModelContracts.validationError(p.copy(baseUrl = "https://example.com/v1beta")))
        assertTrue(
            runCatching {
                    GeminiNativeStream(p.modelId, "s")
                        .decode(
                            JSONObject(
                                """{"candidates":[{"content":{"parts":[{"executableCode":{"code":"x"}}]},"finishReason":"STOP"}]}"""))
                }
                .isFailure)
    }
}
