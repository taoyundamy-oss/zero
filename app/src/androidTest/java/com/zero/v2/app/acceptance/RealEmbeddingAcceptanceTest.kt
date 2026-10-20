package com.zero.v2.app.acceptance

import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.KbChunkEntity
import com.zero.v2.core.data.entity.KbDocEntity
import com.zero.v2.core.data.entity.MemoryEntity
import com.zero.v2.services.embedding.VectorBenchmarkGate
import com.zero.v2.services.embedding.VectorDomain
import com.zero.v2.services.embedding.VectorEvaluationJournal
import com.zero.v2.services.embedding.VectorIndexWorker
import com.zero.v2.services.embedding.EmbeddingProvider
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Paid network requests require explicit instrumentation opt-in, never part of qualityCheck. */
@RunWith(AndroidJUnit4::class)
class RealEmbeddingAcceptanceTest {
    @Test fun realProviderBothDomains() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(InstrumentationRegistry.getArguments().getString("allowRealProvider") == "true")
        val context = instrumentation.targetContext
        val output = File(context.filesDir, "acceptance-embedding.json")
        val results = JSONArray()
        val report = JSONObject().put("scope", "independent_benchmark_real_provider")
            .put("domains", results).put("completed", false)
        output.writeText(report.toString())
        val entry = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val useRerank = InstrumentationRegistry.getArguments().getString("boundedRerank") == "true"
        if (useRerank) entry.chatProfiles().refreshProfiles()
        val reranker = if (useRerank) entry.semanticReranker() else null
        report.put("boundedRerank", useRerank)
        val candidateModel = InstrumentationRegistry.getArguments().getString("candidateModel")
        val configuredProvider = if (candidateModel == null) entry.embeddingProvider()
            else entry.embeddingCandidates().create(candidateModel)
        report.put("candidateModel", candidateModel ?: JSONObject.NULL)
        // This test only submits the fixed public benchmark and isolated seed, never personal sources.
        // Capture its vectors for offline diagnosis; do not change the scoring path or acceptance threshold.
        val captures = JSONArray()
        val captureOutput = File(context.filesDir, "acceptance-embedding-vectors.json")
        val provider = object : EmbeddingProvider by configuredProvider {
            override suspend fun embedBatch(texts: List<String>): List<FloatArray?> =
                embedBatch(texts, com.zero.v2.services.embedding.EmbeddingPurpose.DOCUMENT)

            override suspend fun embedBatch(texts: List<String>, purpose: com.zero.v2.services.embedding.EmbeddingPurpose): List<FloatArray?> {
                val values = configuredProvider.embedBatch(texts, purpose)
                captures.put(JSONObject().put("purpose", purpose.name).put("inputs", JSONArray(texts))
                    .put("vectors", JSONArray(values.map { vector -> vector?.let { JSONArray(it.toList()) } ?: JSONObject.NULL })))
                captureOutput.writeText(JSONObject().put("scope", "fixed_synthetic_benchmark_only")
                    .put("model", modelId).put("batches", captures).toString())
                return values
            }
        }
        report.put("configured", provider.isAvailable())
        output.writeText(report.toString())
        check(provider.isAvailable()) { "embedding_provider_not_configured" }
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val journalRoot = File(context.cacheDir, "embedding-acceptance-${System.nanoTime()}")
        val isolatedContext = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir(): File = journalRoot
        }
        try {
            db.memoryDao().insert(MemoryEntity("acceptance", "这是一条独立验收数据，不属于用户资料。"))
            db.kbDao().insertDoc(KbDocEntity("acceptance", "acceptance.md", "独立验收", 0, 0, 1, 0))
            db.kbDao().insertChunks(listOf(KbChunkEntity("acceptance", "acceptance", 0,
                text = "这是一条独立验收数据，不属于用户资料。", charLen = 22, updatedAt = 0)))
            val worker = VectorIndexWorker(db.vectorIndexDao(), provider,
                VectorEvaluationJournal(isolatedContext), VectorBenchmarkGate(provider, reranker))
            for (domain in VectorDomain.entries) {
                val row = JSONObject().put("domain", domain.key)
                results.put(row)
                try {
                    row.put("outcome", worker.build(domain).name)
                    row.put("report", worker.evaluationReport(domain)?.let { JSONObject(it) } ?: JSONObject.NULL)
                } catch (error: Exception) {
                    // No exception message, endpoint, key, request or response bodies in the exported receipt.
                    row.put("errorType", error.javaClass.simpleName)
                }
                row.put("diagnostics", JSONArray(entry.traces().getTrace("llm_call").mapNotNull {
                    when {
                        it.summary.matches(Regex("embed_http_[0-9]{3}")) -> it.summary
                        it.summary.startsWith("embed_failed:") -> "embedding_transport_or_decode_failure"
                        else -> null
                    }
                }))
                output.writeText(report.toString())
            }
            report.put("completed", true)
            report.put("rankingDiagnostics", JSONArray(entry.traces().getTrace("retrieval_rank").map { it.summary }))
            output.writeText(report.toString())
            assertTrue("See sanitized acceptance-embedding.json; do not lower thresholds",
                (0 until results.length()).all { results.getJSONObject(it).optString("outcome") == "CURRENT" })
        } finally {
            db.close()
            journalRoot.deleteRecursively()
        }
    }
}
