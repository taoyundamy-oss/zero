package com.zero.v2.services.embedding

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.MemoryEntity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Tests only gate mechanics with deterministic vectors; not a provider quality result. */
@RunWith(AndroidJUnit4::class)
class VectorBenchmarkGateTest {
    private class RankModel : com.zero.v2.services.llm.ILanguageModel {
        override val modelId = "rank-fixture"
        override val displayName = modelId
        override val contextWindow = 8192
        override val capabilities = setOf(com.zero.v2.services.llm.ModelCapability.CHAT)
        override val keyConfigured = true
        override val priority = 1
        override val enabled = true
        override var retrievalIdentity = "rank-fixture-v1"
        var valid = true
        var switchModel = false
        var calls = 0
        override suspend fun boundedCompletion(userMessage: String, systemPrompt: String): String? {
            calls++
            if (switchModel) retrievalIdentity = "changed"
            if (!valid) return null
            val input = JSONObject(userMessage)
            val sample = VectorRecallBenchmark.dataset(VectorDomain.MEMORY).samples.first { it.query == input.getString("query") }
            val expectedText = VectorRecallBenchmark.sources.first { it.id in sample.relevant }.text
            val rows = input.getJSONArray("candidates")
            assertTrue((0 until rows.length()).all { !rows.getJSONObject(it).has("id") })
            val order = (0 until rows.length()).sortedByDescending { rows.getJSONObject(it).getString("text") == expectedText }
            return org.json.JSONArray(order).toString()
        }
        override suspend fun chatSync(userMessage: String, systemPrompt: String?): String = error("unbounded")
        override fun chatStream(messages: List<com.zero.v2.core.common.ChatMessage>, systemPrompt: String?,
            functions: List<com.zero.v2.core.common.FunctionDefinition>, prefixVersion: String?) =
            kotlinx.coroutines.flow.emptyFlow<com.zero.v2.core.common.StreamChunk>()
    }
    private class Provider : EmbeddingProvider {
        override val modelId = "benchmark-fixture"
        override var identity = "benchmark-fixture-v1"
        var calls = 0
        var wrong = false
        var interrupt = false
        var changeIdentity = false
        val purposes = mutableListOf<EmbeddingPurpose>()
        var queryCalls = 0
        override suspend fun isAvailable() = true
        override suspend fun embed(text: String) = embedBatch(listOf(text)).first()
        override suspend fun embedQuery(text: String): FloatArray? {
            queryCalls++
            return embed(text)
        }
        override suspend fun embedBatch(texts: List<String>, purpose: EmbeddingPurpose): List<FloatArray?> {
            purposes += purpose
            return embedBatch(texts)
        }
        override suspend fun embedBatch(texts: List<String>): List<FloatArray?> {
            calls++
            if (interrupt && texts.size > 1) throw java.io.IOException("offline")
            if (changeIdentity && texts.size > 1) identity = "changed-during-evaluation"
            val sources = VectorRecallBenchmark.sources
            val samples = VectorRecallBenchmark.dataset(VectorDomain.MEMORY).samples
            return texts.map { text ->
                val sample = samples.firstOrNull { it.query == text }
                val index = if (sample != null) sources.indexOfFirst {
                    it.id == (if (wrong) sample.forbidden.first() else sample.relevant.first())
                } else sources.indexOfFirst { it.text == text }.coerceAtLeast(0)
                val correct = sample?.let { value -> sources.indexOfFirst { it.id in value.relevant } }
                FloatArray(sources.size) { when (it) { index -> 1f; correct -> 0.9f; else -> 0f } }
            }
        }
    }

    private suspend fun fixture(block: suspend (ZeroDatabase, Provider, VectorIndexWorker, VectorEvaluationJournal) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val root = File(context.cacheDir, "benchmark-${UUID.randomUUID()}")
        try {
            db.memoryDao().insert(MemoryEntity("personal", "独立的用户数据"))
            val provider = Provider()
            val journal = VectorEvaluationJournal(root)
            val worker = VectorIndexWorker(db.vectorIndexDao(), provider, journal, VectorBenchmarkGate(provider))
            block(db, provider, worker, journal)
        } finally { db.close(); root.deleteRecursively() }
    }

    @Test fun automaticGatePublishesWithoutAddingBenchmarkToPersonalData() = runBlocking {
        fixture { db, _, worker, journal ->
            assertEquals(VectorIndexWorker.Outcome.CURRENT, worker.build(VectorDomain.MEMORY))
            assertNotNull(db.vectorIndexDao().active("memory"))
            assertEquals(1, db.vectorIndexDao().sourceCount("memory"))
            val record = JSONObject(journal.latest(VectorDomain.MEMORY)!!)
            assertEquals("independent_benchmark", record.getJSONObject("metrics").getString("scope"))
            assertEquals(12, record.getInt("evaluatedSamples"))
            assertTrue(record.getBoolean("published"))
        }
    }

    @Test fun rejectedCandidateDoesNotRepeatPaidEvaluationAfterWorkerRecreation() = runBlocking {
        fixture { db, provider, worker, journal ->
            provider.wrong = true
            assertEquals(VectorIndexWorker.Outcome.WAITING_VALIDATION, worker.build(VectorDomain.MEMORY))
            val calls = provider.calls
            val restarted = VectorIndexWorker(db.vectorIndexDao(), provider, journal, VectorBenchmarkGate(provider))
            assertEquals(VectorIndexWorker.Outcome.WAITING_VALIDATION, restarted.build(VectorDomain.MEMORY))
            assertEquals(calls, provider.calls)
            assertNull(db.vectorIndexDao().active("memory"))
            assertEquals(Long.MAX_VALUE, restarted.retryAt(VectorDomain.MEMORY))
        }
    }

    @Test fun interruptedEvaluationRetainsCandidateAndRetriesWithoutRebuilding() = runBlocking {
        fixture { db, provider, worker, journal ->
            provider.interrupt = true
            assertTrue(runCatching { worker.build(VectorDomain.MEMORY) }.exceptionOrNull() is java.io.IOException)
            val candidate = db.vectorIndexDao().versions("memory").first()
            assertEquals("VALIDATING", candidate.state)
            assertNull(db.vectorIndexDao().active("memory"))
            assertNull(journal.latest(VectorDomain.MEMORY))
            assertTrue(worker.retryAt(VectorDomain.MEMORY) != Long.MAX_VALUE)
            provider.interrupt = false
            assertEquals(VectorIndexWorker.Outcome.CURRENT, worker.build(VectorDomain.MEMORY))
            assertEquals(candidate.id, db.vectorIndexDao().active("memory")!!.id)
        }
    }

    @Test fun providerChangeDuringBenchmarkCannotApproveCandidate() = runBlocking {
        fixture { db, provider, worker, journal ->
            provider.changeIdentity = true
            val error = runCatching { worker.build(VectorDomain.MEMORY) }.exceptionOrNull()
            assertEquals("vector_provider_changed", error?.message)
            assertNull(db.vectorIndexDao().active("memory"))
            assertNull(journal.latest(VectorDomain.MEMORY))
        }
    }

    @Test fun benchmarkAndPublishedSearchUseQueryContract() = runBlocking {
        fixture { _, provider, worker, _ ->
            assertEquals(VectorIndexWorker.Outcome.CURRENT, worker.build(VectorDomain.MEMORY))
            assertEquals(listOf(EmbeddingPurpose.DOCUMENT, EmbeddingPurpose.QUERY), provider.purposes)
            assertNotNull(worker.search(VectorDomain.MEMORY, "query", 1))
            assertEquals(1, provider.queryCalls)
        }
    }

    @Test fun semanticGateReportsRawFailureSeparatelyAndPublishesFinalPass() = runBlocking {
        fixture { db, provider, _, journal ->
            provider.wrong = true
            val model = RankModel()
            val ranker = com.zero.v2.services.retrieval.BoundedSemanticReranker(
                com.zero.v2.services.llm.ModelSelector().also { it.register(model) })
            val worker = VectorIndexWorker(db.vectorIndexDao(), provider, journal, VectorBenchmarkGate(provider, ranker))
            assertEquals(VectorIndexWorker.Outcome.CURRENT, worker.build(VectorDomain.MEMORY))
            val receipt = JSONObject(worker.evaluationReport(VectorDomain.MEMORY)!!)
            assertEquals(1.0, receipt.getDouble("candidateRecall"), 0.0)
            assertEquals(0.0, receipt.getJSONObject("metrics").getDouble("vectorRecall"), 0.0)
            assertEquals(12, model.calls)
            assertEquals(1, db.vectorIndexDao().sourceCount("memory"))
            model.retrievalIdentity = "changed-after-publication"
            assertFalse(JSONObject(worker.evaluationReport(VectorDomain.MEMORY)!!).getBoolean("currentRankingPolicyMatches"))
        }
    }

    @Test fun unavailableRerankerCannotCertifyButRecoversWithoutReembeddingCandidate() = runBlocking {
        fixture { db, provider, _, journal ->
            val model = RankModel().also { it.valid = false }
            val ranker = com.zero.v2.services.retrieval.BoundedSemanticReranker(
                com.zero.v2.services.llm.ModelSelector().also { it.register(model) })
            val worker = VectorIndexWorker(db.vectorIndexDao(), provider, journal, VectorBenchmarkGate(provider, ranker))
            assertEquals(VectorIndexWorker.Outcome.RETRY, worker.build(VectorDomain.MEMORY))
            assertNull(db.vectorIndexDao().active("memory"))
            val candidate = db.vectorIndexDao().versions("memory").first().id
            assertTrue(worker.retryAt(VectorDomain.MEMORY) != Long.MAX_VALUE)
            model.valid = true
            assertEquals(VectorIndexWorker.Outcome.CURRENT, worker.build(VectorDomain.MEMORY))
            assertEquals(candidate, db.vectorIndexDao().active("memory")!!.id)
        }
    }

    @Test fun readyCandidateWithChangedPolicyRecoversAndRetainsActiveUntilRevalidated() = runBlocking {
        fixture { db, provider, _, journal ->
            val model = RankModel()
            val ranker = com.zero.v2.services.retrieval.BoundedSemanticReranker(
                com.zero.v2.services.llm.ModelSelector().also { it.register(model) })
            val gate = VectorBenchmarkGate(provider, ranker)
            val dao = db.vectorIndexDao()
            val worker = VectorIndexWorker(dao, provider, journal, gate)
            assertEquals(VectorIndexWorker.Outcome.CURRENT, worker.build(VectorDomain.MEMORY))
            val active = dao.active("memory")!!.id
            db.memoryDao().insert(MemoryEntity("additional", "新增的用户数据"))
            assertEquals(VectorIndexWorker.Outcome.WAITING_VALIDATION,
                VectorIndexWorker(dao, provider, journal).build(VectorDomain.MEMORY))
            val ready = dao.versions("memory").first { it.state == "VALIDATING" }
            dao.approve(gate.evaluate(ready, dao.validation(active), VectorDomain.MEMORY).evidence)
            model.retrievalIdentity = "replacement-model"
            model.valid = false
            val restarted = VectorIndexWorker(dao, provider, journal, gate)
            assertEquals(VectorIndexWorker.Outcome.RETRY, restarted.build(VectorDomain.MEMORY))
            assertEquals(active, dao.active("memory")!!.id)
            assertFalse(dao.versions("memory").any { it.id == ready.id && it.state == "READY" })
            model.valid = true
            assertEquals(VectorIndexWorker.Outcome.CURRENT, restarted.build(VectorDomain.MEMORY))
            assertNotEquals(active, dao.active("memory")!!.id)
            assertNotEquals(ready.id, dao.active("memory")!!.id)
            assertTrue(JSONObject(restarted.evaluationReport(VectorDomain.MEMORY)!!)
                .getBoolean("currentRankingPolicyMatches"))
        }
    }

    @Test fun modelChangeDuringSemanticEvaluationCannotApprove() = runBlocking {
        fixture { db, provider, _, journal ->
            val model = RankModel().also { it.switchModel = true }
            val ranker = com.zero.v2.services.retrieval.BoundedSemanticReranker(
                com.zero.v2.services.llm.ModelSelector().also { it.register(model) })
            val worker = VectorIndexWorker(db.vectorIndexDao(), provider, journal, VectorBenchmarkGate(provider, ranker))
            val error = runCatching { worker.build(VectorDomain.MEMORY) }.exceptionOrNull()
            assertEquals("vector_ranking_policy_changed", error?.message)
            assertNull(db.vectorIndexDao().active("memory"))
            assertNull(journal.latest(VectorDomain.MEMORY))
        }
    }
}
