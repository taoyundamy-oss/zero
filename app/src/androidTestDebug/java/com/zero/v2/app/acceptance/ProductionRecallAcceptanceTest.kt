package com.zero.v2.app.acceptance

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.data.WorkDirRepository
import com.zero.v2.app.knowledge.KnowledgeModule
import com.zero.v2.app.knowledge.KnowledgeSource
import com.zero.v2.core.bus.ChatBusImpl
import com.zero.v2.core.bus.SystemBusImpl
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.KbChunkEntity
import com.zero.v2.core.data.entity.KbDocEntity
import com.zero.v2.core.data.entity.MemoryEntity
import com.zero.v2.core.data.knowledge.KnowledgeCandidateStore
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.framework.memory.MemoryModule
import com.zero.v2.framework.memory.MemoryScoringEngine
import com.zero.v2.services.embedding.RecallAcceptanceFixture
import com.zero.v2.services.embedding.VectorBenchmarkGate
import com.zero.v2.services.embedding.VectorDomain
import com.zero.v2.services.embedding.VectorEvaluationJournal
import com.zero.v2.services.embedding.VectorIndexWorker
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses production tool handlers and real configured providers, but never the personal database. */
@RunWith(AndroidJUnit4::class)
class ProductionRecallAcceptanceTest {
    @Test fun fullToolPipelineOnFixedBenchmark() = runBlocking {
        check(InstrumentationRegistry.getArguments().getString("allowRealProvider") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        entry.chatProfiles().refreshProfiles()
        val provider = entry.embeddingProvider()
        check(provider.isAvailable())
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val root = File(context.cacheDir, "full-recall-${UUID.randomUUID()}")
        val rows = JSONArray()
        val report = JSONObject().put("scope", "fixed_benchmark_production_tool_handlers")
            .put("personalData", false).put("samples", rows).put("completed", false)
        val output = File(context.filesDir, "acceptance-production-recall.json")
        val trace = AgentTraceManager(db.agentTraceEventDao())
        val ranker = entry.semanticReranker()
        val isolatedContext = object : android.content.ContextWrapper(context) {
            override fun getNoBackupFilesDir(): File = root
        }
        val worker = VectorIndexWorker(db.vectorIndexDao(), provider,
            VectorEvaluationJournal(isolatedContext), VectorBenchmarkGate(provider, ranker))
        val memory = MemoryModule(db.memoryDao(), db.userGraphDao(), trace, provider,
            MemoryScoringEngine(), db.memoryExtractionJobDao(), worker, ranker,
            com.zero.v2.framework.memory.MemoryClassificationTools(db.chronicleSourceDao(), db.memoryDerivationDao(), com.zero.v2.core.data.store.MemoryDerivationStore(db)), com.zero.v2.core.data.store.MemoryDerivationStore(db))
        val source = object : KnowledgeSource {
            override fun identity() = "isolated-fixed-benchmark"
            override fun hasDirectory() = true
            override suspend fun createFolder(path: String): String? = error("read-only acceptance")
            override suspend fun listTree() = WorkDirRepository.TreeListing(emptyList(), false)
            override suspend fun readText(path: String): String? = error("no personal files")
        }
        val knowledge = KnowledgeModule(source, db.kbDao(), SystemBusImpl(), provider, trace,
            KnowledgeCandidateStore(File(root, "knowledge")), worker, ranker)
        knowledge.config.hybridEmbedding = true
        try {
            output.writeText(report.toString())
            // Opaque database IDs keep labels out of the production ranking inputs.
            for (item in RecallAcceptanceFixture.sources) {
                val id = UUID.randomUUID().toString()
                db.memoryDao().insert(MemoryEntity(id, item.text, importance = 0.5f))
                db.kbDao().insertDoc(KbDocEntity(id, "$id.md", item.title, 0, 0, 1, 0))
                db.kbDao().insertChunks(listOf(KbChunkEntity(id, id, 0, headingPath = item.heading,
                    text = item.text, charLen = item.text.length, updatedAt = 0)))
            }
            val builds = JSONObject()
            report.put("builds", builds)
            for (domain in VectorDomain.entries) {
                builds.put(domain.key, worker.build(domain).name)
                output.writeText(report.toString())
                assertEquals(VectorIndexWorker.Outcome.CURRENT.name, builds.getString(domain.key))
                val handler = if (domain == VectorDomain.MEMORY) memory.contributions()
                    .single { it.definition.name == "memory_recall" }.handler else knowledge.contributions()
                    .single { it.definition.name == "kb_search" }.handler
                val dataset = RecallAcceptanceFixture.dataset(domain)
                for (sample in dataset.samples) {
                    val started = System.nanoTime()
                    val result = handler(mapOf("query" to sample.query, "top_k" to 1))
                    val relevant = RecallAcceptanceFixture.sources.filter { it.id in sample.relevant }
                    val forbidden = RecallAcceptanceFixture.sources.filter { it.id in sample.forbidden }
                    rows.put(JSONObject().put("domain", domain.key).put("sample", sample.id)
                        .put("kind", sample.kind.name).put("success", result.success)
                        .put("recall", relevant.count { result.data.contains(it.text) }.toDouble() / relevant.size)
                        .put("forbiddenHits", forbidden.count { result.data.contains(it.text) })
                        .put("latencyMs", (System.nanoTime() - started) / 1_000_000))
                    output.writeText(report.toString())
                }
            }
            report.put("completed", true).put("rankingDiagnostics",
                JSONArray(entry.traces().getTrace("retrieval_rank").map { it.summary }))
            output.writeText(report.toString())
            assertEquals(12, rows.length())
            assertTrue("See acceptance-production-recall.json; keep all samples and thresholds",
                (0 until rows.length()).all {
                    val row = rows.getJSONObject(it)
                    row.getBoolean("success") && row.getDouble("recall") >= 0.9 && row.getInt("forbiddenHits") == 0
                })
        } finally {
            memory.onDestroy()
            knowledge.onDestroy()
            db.close()
            root.deleteRecursively()
        }
    }
}
