package com.zero.v2.app.acceptance

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.store.ModuleConfigStore
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.services.agent.BudgetGuard
import com.zero.v2.services.llm.LlmProfile
import com.zero.v2.services.llm.ModelRequestHealth
import com.zero.v2.services.llm.OpenAiCompatibleClient
import com.zero.v2.services.llm.RequestContextBudget
import com.zero.v2.services.llm.RequestContextPreparation
import com.zero.v2.services.llm.StoredAttachmentReader
import com.zero.v2.services.security.CredentialStore
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Real client + local HTTP only; isolated test-package preferences and in-memory SQLite. */
class ModelHttpStatusAcceptanceTest {
    @Test fun realClientRecordsResponseAndRejectsChangedCredentialIdentity() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.context
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val credentials = object : CredentialStore {
            override fun put(key: String, value: String) = error("No credential writes")
            override fun getString(key: String) = "test-only"
            override fun getOrFallbackString(primaryKey: String, fallbackKey: String?) = "test-only"
            override fun delete(key: String) = error("No credential writes")
            override fun isConfigured(key: String) = true
        }
        val trace = AgentTraceManager(db.agentTraceEventDao())
        val health = ModelRequestHealth()
        val client = OpenAiCompatibleClient(OkHttpClient(), credentials, trace,
            BudgetGuard(context, ModuleConfigStore(db.moduleConfigDao()), trace), RequestContextPreparation(StoredAttachmentReader(context), RequestContextBudget(), OkHttpClient()), health)
        try {
            for (code in listOf(200, 401)) {
                ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                    val worker = thread {
                        server.accept().use { socket ->
                            val input = socket.getInputStream().bufferedReader()
                            var length = 0
                            while (true) {
                                val line = input.readLine() ?: break
                                if (line.isEmpty()) break
                                if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                            }
                            repeat(length) { input.read() }
                            val body = if (code == 200) "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                                else "{\"error\":{\"message\":\"test refusal\"}}"
                            socket.getOutputStream().write(("HTTP/1.1 $code Test\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body").toByteArray())
                        }
                    }
                    val profile = LlmProfile("acceptance-local", "Local test", "http://127.0.0.1:${server.localPort}",
                        "unused", modelId = "test-model", enableThinking = false)
                    try {
                        val chunks = client.chatStream(profile, emptyList(), null, emptyList()).toList()
                        assertEquals(200, code)
                        assertTrue(chunks.last().isFinal)
                        assertEquals(1L, chunks.last().tokenUsage?.cacheUnknownSamples)
                        assertEquals(1L, client.usageTotal.value.cacheUnknownSamples)
                        assertEquals(0L, client.usageTotal.value.cacheObservedInputTokens)
                    } catch (error: java.io.IOException) {
                        assertEquals(401, code)
                    } finally { worker.join(3000) }
                    val observation = health.observation(health.identity(profile, "test-only", "status"))!!
                    assertEquals(code, observation.httpStatus)
                    assertEquals("chat_stream", observation.operation)
                    assertNull(health.observation(health.identity(profile, "replaced", "status")))
                    assertFalse(observation.toString().contains("test-only"))
                }
            }
            assertEquals(2L, client.usageTotal.value.cacheUnknownSamples)
            File(instrumentation.targetContext.filesDir, "acceptance-a5-http-health.json").writeText(JSONObject()
                .put("completed", true).put("realClientLoopbackRequests", 2).put("paidRequests", 0)
                .put("http200And401", true).put("credentialIsolation", true)
                .put("healthScope", "http_response_only").toString(2))
        } finally { db.close() }
    }
}
