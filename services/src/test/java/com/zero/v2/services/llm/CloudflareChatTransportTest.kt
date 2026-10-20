package com.zero.v2.services.llm

import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareChatTransportTest {
    private val profile = LlmProfile.defaults().first { it.id == "qwen" }.copy(
        baseUrl = "https://gateway.ai.cloudflare.com/v1/account/zero/compat/",
        modelId = "google-ai-studio/gemini-3.8-flash", cloudflareByok = true,
        contractId = "generic-v1", parameters = emptyMap(),
    )

    @Test fun `BYOK does not forward gateway token as upstream authorization`() {
        val request = CloudflareChatTransport.request(profile, "gateway-token").build()
        assertNull(request.header("Authorization"))
        assertEquals("Bearer gateway-token", request.header("cf-aig-authorization"))
        assertEquals("/v1/account/zero/compat/chat/completions", request.url.encodedPath)
        val rest = CloudflareChatTransport.request(profile.copy(
            baseUrl = "https://api.cloudflare.com/client/v4/accounts/account/ai/v1",
            cloudflareGatewayId = "zero",
        ), "token").build()
        assertEquals("Bearer token", rest.header("Authorization"))
        assertEquals("zero", rest.header("cf-aig-gateway-id"))
        assertNull(rest.header("cf-aig-authorization"))
    }

    @Test fun `reused qwen slot preserves tools and transcript without qwen fields`() {
        val body = JSONObject("""{"model":"google-ai-studio/gemini-3.8-flash","stream":true,"max_tokens":4096,
            "tools":[{"type":"function","function":{"name":"lookup"}}],
            "messages":[{"role":"assistant",
                "tool_calls":[{"id":"call1","type":"function","function":{"name":"lookup","arguments":"{}"}}]},
                {"role":"tool","tool_call_id":"call1","content":"result"}]}""")
        val before = body.toString()
        CloudflareChatTransport.applyContract(body, profile, true)
        assertEquals(before, body.toString())
        CloudflareChatTransport.applyContract(body, profile.copy(cloudflareByok = false,
            contractId = "dashscope-v1", parameters = mapOf("thinking" to "false")), true)
        assertTrue(body.has("enable_thinking"))
        val direct = CloudflareChatTransport.request(profile.copy(cloudflareByok = false), "old-key").build()
        assertEquals("Bearer old-key", direct.header("Authorization"))
        assertNull(direct.header("cf-aig-authorization"))
    }

    @Test fun `invalid gateway or bare model is rejected without changing model version`() {
        assertNotNull(CloudflareChatTransport.validationError(profile.copy(modelId = "gemini-3.8-flash")))
        assertNotNull(CloudflareChatTransport.validationError(profile.copy(baseUrl = "https://example.com/compat")))
        assertNotNull(CloudflareChatTransport.validationError(profile.copy(
            baseUrl = "https://api.cloudflare.com/client/v4/accounts/a/ai/v1")))
        assertNull(CloudflareChatTransport.validationError(profile))
    }

    @Test fun `array error preserves HTTP status and redacts credential before success decoding`() {
        val raw = """[{"error":{"code":503,"message":"High demand secret-token","status":"UNAVAILABLE"}}]"""
        val response = okhttp3.Response.Builder().request(okhttp3.Request.Builder().url("https://example.test").build())
            .protocol(okhttp3.Protocol.HTTP_1_1).code(503).message("Unavailable").body(raw.toResponseBody()).build()
        val error = runCatching { ProviderErrorMessage.chatResponse(response, "secret-token") }.exceptionOrNull()
        assertTrue(error is java.io.IOException)
        assertEquals("HTTP 503: High demand [redacted]", error?.message)
        assertEquals("High demand secret-token", ProviderErrorMessage.parse(raw))
    }

    @Test fun `cloudflare and indexed google errors retain actionable message`() {
        assertEquals("No such model", ProviderErrorMessage.parse("""{"errors":[{"message":"No such model"}]}"""))
        assertEquals("Use models/gemini-3.8-flash", ProviderErrorMessage.parse(
            """{"0":{"error":{"code":404,"message":"Use models/gemini-3.8-flash","status":"NOT_FOUND"}}}"""))
        assertEquals("Bad request", ProviderErrorMessage.parse("""{"error":{"message":"Bad request"}}"""))
    }
}
