package com.zero.v2.services.llm

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRequestHealthTest {
    private val profile = LlmProfile("sample", "Sample", "https://private.example", "secret-ref", modelId = "m1")
    private fun response(request: Request, code: Int) = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(code).message("test").body("unchanged-body".toResponseBody()).build()
    private fun request(health: ModelRequestHealth, key: String = "private-key", p: LlmProfile = profile) =
        Request.Builder().url(p.baseUrl).header("Authorization", "Bearer $key")
            .tag(ModelRequestHealth.Identity::class.java, health.identity(p, key, "chat_stream")).build()

    @Test fun `observation is passive bounded and excludes credentials urls and body`() {
        val health = ModelRequestHealth()
        val identity = health.identity(profile, "private-key", "status")
        assertNull(health.observation(identity))
        val client = OkHttpClient.Builder().addInterceptor(health.interceptor()).addInterceptor {
            assertEquals("Bearer private-key", it.request().header("Authorization"))
            response(it.request(), 401)
        }.build()
        client.newCall(request(health)).execute().use { assertEquals("unchanged-body", it.body!!.string()) }
        val value = health.observation(identity)!!
        assertEquals(401, value.httpStatus)
        assertEquals("http_401", value.outcome)
        assertTrue(value.observedAt > 0L)
        assertFalse(value.toString().contains("private") || value.toString().contains("unchanged-body"))
        assertNull(health.observation(health.identity(profile, "changed-key", "status")))
        assertNull(health.observation(health.identity(profile.copy(modelId = "m2"), "private-key", "status")))
        assertNull(health.observation(health.identity(
            profile.copy(baseUrl = "https://other.example"), "private-key", "status")))
    }

    @Test fun `transport error has safe code and leaves request failure semantics intact`() {
        val health = ModelRequestHealth()
        val client = OkHttpClient.Builder().addInterceptor(health.interceptor()).addInterceptor {
            throw IOException("private-key and private-body")
        }.build()
        try { client.newCall(request(health)).execute(); error("Expected failure") } catch (_: IOException) { }
        val observation = health.observation(health.identity(profile, "private-key", "status"))!!
        assertEquals("transport_error", observation.outcome)
        assertNull(observation.httpStatus)
        assertFalse(observation.toString().contains("private"))
    }

    @Test fun `late old request cannot overwrite newer response`() {
        val health = ModelRequestHealth()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow = OkHttpClient.Builder().addInterceptor(health.interceptor()).addInterceptor {
            entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); response(it.request(), 200)
        }.build()
        val worker = thread { slow.newCall(request(health)).execute().close() }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val fast = OkHttpClient.Builder().addInterceptor(health.interceptor())
                .addInterceptor { response(it.request(), 503) }.build()
            fast.newCall(request(health)).execute().close()
        } finally { release.countDown(); worker.join(3000) }
        assertEquals(503, health.observation(health.identity(profile, "private-key", "status"))!!.httpStatus)
    }

    @Test fun `cancelled request does not invent provider failure and untagged calls are ignored`() {
        val health = ModelRequestHealth()
        val client = OkHttpClient.Builder().addInterceptor(health.interceptor()).addInterceptor {
            it.call().cancel(); throw IOException("cancelled")
        }.build()
        try { client.newCall(request(health)).execute() } catch (_: IOException) { }
        assertNull(health.observation(health.identity(profile, "private-key", "status")))
        val ok = OkHttpClient.Builder().addInterceptor(health.interceptor())
            .addInterceptor { response(it.request(), 200) }.build()
        ok.newCall(Request.Builder().url(profile.baseUrl).build()).execute().close()
        assertNull(health.observation(health.identity(profile, "private-key", "status")))
        repeat(129) { ok.newCall(request(health, p = profile.copy(id = "profile-$it"))).execute().close() }
        assertNull(health.observation(health.identity(profile.copy(id = "profile-0"), "private-key", "status")))
        assertEquals(200, health.observation(health.identity(
            profile.copy(id = "profile-128"), "private-key", "status"))!!.httpStatus)
    }
}
