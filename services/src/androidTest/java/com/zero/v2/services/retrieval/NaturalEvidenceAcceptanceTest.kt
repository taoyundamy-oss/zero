package com.zero.v2.services.retrieval

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.StreamChunk
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.ConversationEntity
import com.zero.v2.core.data.entity.MemoryEntity
import com.zero.v2.core.data.entity.MessageEntity
import com.zero.v2.services.llm.ILanguageModel
import com.zero.v2.services.llm.ModelCapability
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real generator via USB loopback, isolated Room; no daily model selection/credentials or writes. */
class NaturalEvidenceAcceptanceTest {
    private class LocalGenerator : ILanguageModel {
        private val http = OkHttpClient()
        var raw: String? = null
        override val modelId = "acceptance-local-qwen35-9b"
        override val displayName = modelId
        override val contextWindow = 8192
        override val capabilities = setOf(ModelCapability.CHAT)
        override val keyConfigured = true
        override val priority = 1
        override val enabled = true
        override suspend fun boundedCompletion(userMessage: String, systemPrompt: String): String? {
            raw = null
            val body = JSONObject().put("messages", JSONArray(listOf(
                JSONObject().put("role", "system").put("content", systemPrompt),
                JSONObject().put("role", "user").put("content", userMessage))))
                .put("temperature", 0).put("seed", 947).put("max_tokens", 256).put("stream", false)
                .put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
            val request = Request.Builder().url("http://127.0.0.1:18086/v1/chat/completions")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            return suspendCancellableCoroutine { continuation ->
                val call = http.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, error: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                    override fun onResponse(call: Call, response: Response) {
                        val result = runCatching {
                            response.use {
                                check(it.isSuccessful)
                                JSONObject(checkNotNull(it.body).string()).getJSONArray("choices")
                                    .getJSONObject(0).getJSONObject("message").getString("content")
                            }
                        }
                        if (continuation.isActive) result.fold(
                            { raw = it; continuation.resume(it) }, { continuation.resumeWithException(it) })
                    }
                })
            }
        }
        override suspend fun chatSync(userMessage: String, systemPrompt: String?): String = error("unbounded disabled")
        override fun chatStream(messages: List<ChatMessage>, systemPrompt: String?,
            functions: List<FunctionDefinition>, prefixVersion: String?): Flow<StreamChunk> = emptyFlow()
        fun close() { http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
    }

    @Test fun naturalSourcesThroughRealGenerator(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("allowLocalGenerator") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "natural-fixture.json")
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(hash == args.getString("fixtureSha256"))
        val fixture = JSONObject(file.readText())
        val rows = JSONArray()
        val report = JSONObject().put("fixtureSha256", hash).put("rows", rows)
            .put("cloudCalls", 0).put("inferenceLocation", "desktop through USB")
        val output = File(context.filesDir, "natural-report.json")
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val model = LocalGenerator()
        try {
            db.conversationDao().insert(ConversationEntity("fixture", "Isolated natural sources"))
            val sources = fixture.getJSONArray("sources")
            val ids = (0 until sources.length()).map { sources.getJSONObject(it).getString("id") }
            for (i in ids.indices) {
                val text = sources.getJSONObject(i).getString("text")
                db.messageDao().insert(MessageEntity("s$i", "fixture", "user", text, source = "CHAT_WINDOW"))
                db.memoryDao().insert(MemoryEntity(ids[i], text, origin = if (i == 5) "UNKNOWN" else "USER_QUOTE",
                    scope = if (i == 6) "work" else "personal", sourceMessageId = "s$i", sourceConversationId = "fixture"))
            }
            val before = ids.map { db.memoryDao().getById(it) }
            val reader = MemoryEvidenceReader(db.memoryDao())
            val production = MemoryEvidencePipeline(reader)
            // Explicit diagnostic control only; never changes the production constructor/budget.
            val diagnostic = MemoryEvidencePipeline(reader, System::currentTimeMillis, 10000)
            val cases = fixture.getJSONArray("cases")
            for (i in 0 until cases.length()) {
                val case = cases.getJSONObject(i)
                val modes = if (i % 2 == 0) listOf("production", "diagnostic") else listOf("diagnostic", "production")
                for (mode in modes) {
                    val start = android.os.SystemClock.elapsedRealtime()
                    val pipeline = if (mode == "production") production else diagnostic
                    val result = pipeline.select(case.getString("query"), ids, model, history = true)
                    val elapsed = android.os.SystemClock.elapsedRealtime() - start
                    val certain = result.entries.filter { it.use == EvidenceSupport.Use.CERTAIN }.map { it.memoryId }.toSet()
                    val uncertain = result.entries.filter { it.use == EvidenceSupport.Use.UNCERTAIN }.map { it.memoryId }.toSet()
                    fun expected(key: String): Set<String> {
                        val labels = case.getJSONArray(key)
                        return (0 until labels.length()).map { ids[labels.getInt(it)] }.toSet()
                    }
                    val textIntact = result.entries.all { entry -> entry.use == EvidenceSupport.Use.NONE ||
                        entry.text == sources.getJSONObject(ids.indexOf(entry.memoryId)).getString("text") }
                    val safe = result.entries.none { it.memoryId in setOf("m5", "m6") && it.use != EvidenceSupport.Use.NONE }
                    rows.put(JSONObject().put("id", case.getString("id")).put("group", case.getString("group"))
                        .put("mode", mode).put("status", result.status).put("elapsedMs", elapsed)
                        .put("certain", JSONArray(certain.toList())).put("uncertain", JSONArray(uncertain.toList()))
                        .put("matched", result.completed && certain == expected("certain") && uncertain == expected("uncertain"))
                        .put("textIntact", textIntact).put("filteredSourcesSafe", safe))
                    output.writeText(report.toString(2))
                }
            }
            val unchanged = before == ids.map { db.memoryDao().getById(it) }
            report.put("memoryUnchanged", unchanged)
            output.writeText(report.toString(2))
            assertTrue("Isolated reads must not mutate memories", unchanged)
            val all = (0 until rows.length()).map { rows.getJSONObject(it) }
            assertTrue(all.all { it.getBoolean("textIntact") && it.getBoolean("filteredSourcesSafe") })
            val grouped = all.filter { it.getString("mode") == "production" }.groupBy { it.getString("group") }
            assertTrue("Failures are not successful abstentions; see retained report",
                grouped.size == 3 && grouped.values.all { group -> group.count { it.getBoolean("matched") }.toDouble() / group.size >= 0.9 })
        } finally { model.close(); db.close() }
    }
}
