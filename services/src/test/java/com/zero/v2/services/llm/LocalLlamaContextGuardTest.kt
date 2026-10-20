package com.zero.v2.services.llm

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class LocalLlamaContextGuardTest {
    private fun guard(tokens: Int, calls: MutableList<String>) = LocalLlamaContextGuard(
        OkHttpClient.Builder().addInterceptor { chain ->
            calls += chain.request().url.encodedPath
            val text = when (chain.request().url.encodedPath) {
                "/props" -> """{"default_generation_settings":{"n_ctx":4096}}"""
                "/apply-template" -> """{"prompt":"rendered tools and messages"}"""
                else -> JSONObject().put("tokens", org.json.JSONArray(List(tokens) { 1 })).toString()
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(text.toResponseBody()).build()
        }.build(),
    )

    @Test fun `actual server context wins over oversized configured window`() {
        val calls = mutableListOf<String>()
        val body = JSONObject().put("max_tokens", 2048)
        try {
            guard(3000, calls).prepare(LlmProfile.localQwen("http://127.0.0.1:8080/v1", "test"), body, "test-key")
            fail("Must not send generation outside actual slot context")
        } catch (error: IOException) { assertTrue(error.message.orEmpty().contains("LOCAL_CONTEXT_LIMIT")) }
        assertEquals(listOf("/props", "/apply-template", "/tokenize"), calls)
    }

    @Test fun `local template settings and prompt cache are explicit`() {
        val body = JSONObject().put("max_tokens", 256).put("stream", true)
        guard(300, mutableListOf()).prepare(LlmProfile.localQwen("http://127.0.0.1:8080/v1/", "test"), body, "test-key")
        assertFalse(body.getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"))
        assertTrue(body.getBoolean("cache_prompt"))
        assertTrue(body.getJSONObject("stream_options").getBoolean("include_usage"))
    }

    @Test fun `cloud body is byte unchanged and never calls local endpoints`() {
        val calls = mutableListOf<String>()
        val body = JSONObject("""{"messages":[{"role":"system","content":"stable"}],"max_tokens":4096}"""
        )
        val before = body.toString()
        guard(300, calls).prepare(LlmProfile.defaults().first { it.id == "deepseek" }, body)
        assertEquals(before, body.toString())
        assertTrue(calls.isEmpty())
    }

    @Test fun `copied local model retains preflight and forwards explicit authentication`() {
        val model = LlmProfile.localQwen("http://127.0.0.1:8080/v1", "wire").copy(id = "copy")
        assertTrue(LlmProfileJson.fromJson(LlmProfileJson.toJson(model)).localLlamaPreflight)
        val headers = mutableListOf<String?>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            headers.add(chain.request().header("x-local-key"))
            val text = when (chain.request().url.encodedPath) {
                "/props" -> """{"default_generation_settings":{"n_ctx":4096}}"""
                "/apply-template" -> """{"prompt":"test"}"""
                else -> """{"tokens":[1]}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(text.toResponseBody()).build()
        }.build()
        LocalLlamaContextGuard(client).prepare(model.copy(authMode = ConnectionAuth.HEADER, authHeader = "x-local-key"),
            JSONObject().put("max_tokens", 128), "isolated-key")
        assertEquals(listOf("isolated-key", "isolated-key", "isolated-key"), headers)
    }
}
