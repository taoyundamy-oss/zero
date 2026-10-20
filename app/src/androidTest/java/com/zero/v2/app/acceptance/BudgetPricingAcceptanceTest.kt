package com.zero.v2.app.acceptance

import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.store.ModuleConfigStore
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.services.agent.BudgetGuard
import com.zero.v2.services.llm.*
import com.zero.v2.services.security.CredentialStore
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

/** Four localhost requests through the real client; no production preferences or cloud calls. */
class BudgetPricingAcceptanceTest {
    @Test fun syncAndBoundedKnownAndMissingUsageAreAccountedExactlyOnce() = runBlocking<Unit> {
        val base = InstrumentationRegistry.getInstrumentation().context
        val prefix = "b3-pricing-${System.nanoTime()}-"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefix + name, mode)
        }
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val trace = AgentTraceManager(db.agentTraceEventDao())
        val budget = BudgetGuard(context, ModuleConfigStore(db.moduleConfigDao()), trace)
        val credentials = object : CredentialStore {
            override fun put(key: String, value: String) = error("No writes")
            override fun getString(key: String) = "fixture"
            override fun getOrFallbackString(primaryKey: String, fallbackKey: String?) = "fixture"
            override fun delete(key: String) = error("No writes")
            override fun isConfigured(key: String) = true
        }
        val http = OkHttpClient()
        val client = OpenAiCompatibleClient(http, credentials, trace, budget,
            RequestContextPreparation(StoredAttachmentReader(context), RequestContextBudget(), http), ModelRequestHealth())
        try {
            ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 10_000
                val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
                val worker = thread {
                    try {
                        repeat(4) { index -> server.accept().use { socket ->
                            val input = socket.getInputStream().bufferedReader()
                            var length = 0
                            while (true) {
                                val line = input.readLine() ?: break
                                if (line.isEmpty()) break
                                if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                            }
                            repeat(length) { check(input.read() >= 0) }
                            val usage = if (index % 2 == 0) ",\"usage\":{\"prompt_tokens\":100,\"prompt_cache_hit_tokens\":80,\"completion_tokens\":10}" else ""
                            val body = "{\"choices\":[{\"message\":{\"content\":\"[]\"}}]$usage}"
                            socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body").toByteArray())
                        } }
                    } catch (error: Throwable) { failure.set(error) }
                }
                val profile = LlmProfile("pricing-fixture", "Fixture", "http://127.0.0.1:${server.localPort}", "fixture",
                    modelId = "fixture", enableThinking = false, tokenPrices = ModelTokenPrices("2", "0.2", "8"))
                repeat(2) { assertEquals("[]", client.chatSync(profile, "test")) }
                repeat(2) { assertEquals("[]", client.boundedCompletion(profile, "test", "test")) }
                worker.join(10_000)
                assertFalse(worker.isAlive)
                failure.get()?.let { throw AssertionError(it) }
                val prefs = context.getSharedPreferences("zero_budget_guard", 0)
                assertEquals(2L, prefs.getLong("month_unpriced_requests", 0))
                assertEquals(220L, budget.snapshot().first)
                assertEquals(0.000272, budget.snapshot().third, 1e-12)
                assertEquals(2L, client.usageTotal.value.cacheUnknownSamples)
                assertEquals(0, "0.000272".toBigDecimal().compareTo(prefs.getString("month_priced_usd", "0")!!.toBigDecimal()))
            }
        } finally {
            db.close()
            base.deleteSharedPreferences(prefix + "zero_budget_guard")
        }
    }
}
