package com.zero.v2.app.acceptance

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.MessageRole
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.store.ModuleConfigStore
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.services.agent.BudgetGuard
import com.zero.v2.services.llm.*
import com.zero.v2.services.security.CredentialStore
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Production client and AtomicFile, isolated credentials/database, loopback SSE only. */
class ContextBudgetAcceptanceTest {
    @Test fun prefixObservationSurvivesClientRecreationAndOversizeNeverReachesHttp() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.context
        val directory = File(instrumentation.targetContext.cacheDir, "c1-budget-${System.nanoTime()}")
            .apply { check(mkdirs()) { "Isolated directory unavailable" } }
        val context = object : ContextWrapper(base) {
            override fun getNoBackupFilesDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int) =
                base.getSharedPreferences("c1-isolated-$name", mode)
        }
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val trace = AgentTraceManager(db.agentTraceEventDao())
        val guard = BudgetGuard(context, ModuleConfigStore(db.moduleConfigDao()), trace)
        val credentials = object : CredentialStore {
            override fun put(key: String, value: String): Unit = error("No writes")
            override fun getString(key: String) = "isolated"
            override fun getOrFallbackString(primaryKey: String, fallbackKey: String?) = "isolated"
            override fun delete(key: String): Unit = error("No writes")
            override fun isConfigured(key: String) = true
        }
        val accepted = AtomicInteger()
        val workerError = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        fun client(): OpenAiCompatibleClient {
            val http = OkHttpClient()
            return OpenAiCompatibleClient(http, credentials, trace, guard,
                RequestContextPreparation(StoredAttachmentReader(context),
                    RequestContextBudget(RequestBudgetStore(context)), http, CacheRequestDiagnostics(),
                    UsageLedger(db.usageRequestDao())), ModelRequestHealth())
        }
        try {
            ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 6000
                val worker = thread {
                    try {
                        repeat(2) {
                            server.accept().use { socket ->
                                accepted.incrementAndGet()
                                val input = socket.getInputStream().bufferedReader()
                                var size = 0
                                while (true) {
                                    val line = input.readLine() ?: break
                                    if (line.isEmpty()) break
                                    if (line.startsWith("Content-Length:", true)) size = line.substringAfter(':').trim().toInt()
                                }
                                val chars = CharArray(8192)
                                while (size > 0) {
                                    val count = input.read(chars, 0, minOf(size, chars.size))
                                    check(count > 0)
                                    size -= count
                                }
                                val body = "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]," +
                                    "\"usage\":{\"prompt_tokens\":90000,\"completion_tokens\":1}}\n\ndata: [DONE]\n\n"
                                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n" +
                                    "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body").toByteArray())
                            }
                        }
                        server.soTimeout = 1500
                        try { server.accept().use { accepted.incrementAndGet() } } catch (_: SocketTimeoutException) { }
                    } catch (failure: Throwable) { workerError.set(failure) }
                }
                val profile = LlmProfile("c1-isolated", "Isolated", "http://127.0.0.1:${server.localPort}", "isolated",
                    modelId = "test-model", maxTokens = 2048, enableThinking = false)
                val messages = listOf(ChatMessage(id = "stable", role = MessageRole.USER, content = "x".repeat(500_000)))
                assertTrue(client().chatStream(profile, messages, "stable", emptyList()).toList().last().isFinal)
                val saved = File(directory, "request-input-budget-v1.json").readText()
                assertFalse(saved.contains("isolated") || saved.contains("xxxxxxxxxx"))
                val recreated = client()
                val smaller = profile.copy(contextWindow = 256_000)
                assertTrue(recreated.chatStream(smaller, messages, "stable", emptyList()).toList().last().isFinal)
                try {
                    recreated.chatStream(smaller, messages + ChatMessage(role = MessageRole.USER,
                        content = "x".repeat(700_000)), "stable", emptyList()).toList()
                    fail("Oversized request reached transport")
                } catch (expected: java.io.IOException) {
                    assertTrue(expected.message.orEmpty().startsWith("REQUEST_CONTEXT_LIMIT"))
                }
                worker.join(8000)
                assertFalse(worker.isAlive)
                workerError.get()?.let { throw AssertionError("Loopback worker failed", it) }
                assertEquals(2, accepted.get())
                val ledgerRows = db.usageRequestDao().observe(0, Long.MAX_VALUE).first()
                assertEquals(2, ledgerRows.size)
                assertTrue(ledgerRows.all { it.status == "completed" && it.inputTokens == 90000L &&
                    it.outputTokens == 1L && it.cachedTokens == null })
                File(instrumentation.targetContext.filesDir, "acceptance-c1-context.json").writeText(JSONObject()
                    .put("completed", true).put("loopbackRequests", accepted.get()).put("cloudRequests", 0)
                    .put("atomicFileClientRecreation", true).put("oversizeBlockedBeforeHttp", true)
                    .put("scope", "fixture usage, not real provider tokenizer or process death").toString(2))
            }
        } finally {
            db.close()
            directory.deleteRecursively()
            base.deleteSharedPreferences("c1-isolated-zero_budget_guard")
        }
    }
}
