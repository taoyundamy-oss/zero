package com.zero.v2.services.embedding

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.MemoryEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Deterministic vectors verify the gate, not real provider semantic quality. */
@RunWith(AndroidJUnit4::class)
class VectorRecallEvaluatorTest {
    private class Provider : EmbeddingProvider {
        override val modelId = "fixture"
        override var identity = "fixture-v1"
        var wrong = false
        var calls = 0
        var beforeQuery: () -> Unit = {}
        override suspend fun isAvailable() = true
        override suspend fun embed(text: String): FloatArray {
            calls++
            beforeQuery()
            return if (wrong) floatArrayOf(0f, 1f) else floatArrayOf(1f, 0f)
        }
        override suspend fun embedBatch(texts: List<String>) = texts.map {
            if (it == "当前有效事实") floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f)
        }
    }

    private fun dataset() = VectorRecallDataset("labelled-fixture-v1", VectorDomain.MEMORY,
        VectorRecallDataset.Kind.entries.map {
            VectorRecallDataset.Sample(it.name, "独立问题 ${it.name}", it, setOf("current"), setOf("old"))
        }, limit = 1)

    private suspend fun fixture(block: suspend (ZeroDatabase, Provider, VectorIndexWorker, String) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        try {
            db.memoryDao().insert(MemoryEntity("current", "当前有效事实"))
            db.memoryDao().insert(MemoryEntity("old", "已否定的旧事实"))
            val provider = Provider()
            val worker = VectorIndexWorker(db.vectorIndexDao(), provider)
            assertEquals(VectorIndexWorker.Outcome.WAITING_VALIDATION, worker.build(VectorDomain.MEMORY))
            block(db, provider, worker, db.vectorIndexDao().versions("memory").first().id)
        } finally { db.close() }
    }

    @Test fun fullEvaluationPublishesAndRegressionRetainsActive() = runBlocking {
        fixture { db, provider, worker, id ->
            assertTrue(worker.evaluateAndPublish(id, dataset()).published)
            assertEquals(3, provider.calls)
            assertEquals(1.0, db.vectorIndexDao().validation(id)!!.candidateRecall, 0.0)
            provider.identity = "fixture-v2"
            worker.build(VectorDomain.MEMORY)
            val next = db.vectorIndexDao().versions("memory").first { it.state == "VALIDATING" }.id
            provider.wrong = true
            val rejected = worker.evaluateAndPublish(next, dataset()); assertFalse(rejected.published); assertEquals(0.0, rejected.evidence.candidateRecall, 0.0)
            assertEquals(6, provider.calls)
            assertEquals(id, db.vectorIndexDao().active("memory")!!.id)
            assertNull(db.vectorIndexDao().validation(next))
        }
    }

    @Test fun interruptionNeverApprovesPartialSamples() = runBlocking {
        fixture { db, provider, worker, id ->
            provider.beforeQuery = { if (provider.calls == 2) throw CancellationException("interrupted") }
            assertTrue(runCatching { worker.evaluateAndPublish(id, dataset()) }.exceptionOrNull() is CancellationException)
            assertNull(db.vectorIndexDao().validation(id))
            assertNull(db.vectorIndexDao().active("memory"))
            provider.beforeQuery = {}
            assertTrue(worker.evaluateAndPublish(id, dataset()).published)
        }
    }

    @Test fun changedProviderAndChangedSourceCannotPublish() = runBlocking {
        fixture { db, provider, worker, id ->
            provider.beforeQuery = { provider.identity = "changed" }
            assertTrue(runCatching { worker.evaluateAndPublish(id, dataset()) }.isFailure)
            assertNull(db.vectorIndexDao().active("memory"))
            provider.identity = "fixture-v1"
            provider.beforeQuery = {}
            db.memoryDao().insert(MemoryEntity("current", "来源已改变"))
            assertTrue(runCatching { worker.evaluateAndPublish(id, dataset()) }.isFailure)
            assertNull(db.vectorIndexDao().validation(id))
        }
    }

    @Test fun missingLabelsAndSelfQueriesAreRejected() = runBlocking {
        fixture { db, _, worker, id ->
            val data = dataset()
            val missing = data.copy(samples = data.samples.map { it.copy(relevant = setOf("missing")) })
            assertTrue(runCatching { worker.evaluateAndPublish(id, missing) }.isFailure)
            val self = data.copy(samples = data.samples.map { it.copy(query = "当前有效事实") })
            assertTrue(runCatching { worker.evaluateAndPublish(id, self) }.isFailure)
            assertNull(db.vectorIndexDao().validation(id))
        }
    }

    @Test fun digestBindsQueriesLabelsThresholdAndTopK() {
        val data = dataset().snapshot()
        assertEquals(data.digest(), data.copy(samples = data.samples.reversed()).digest())
        assertNotEquals(data.digest(), data.copy(limit = 2).digest())
        assertNotEquals(data.digest(), data.copy(minimumRecall = 1.0).digest())
        assertNotEquals(data.digest(), data.copy(samples = data.samples.map { it.copy(query = "另一个问题") }).digest())
        assertTrue(runCatching { data.copy(samples = data.samples.take(1)).snapshot() }.isFailure)
    }

    private fun boundDataset() = dataset().copy(sourceDigests = mapOf(
        "current" to VectorRecallSources.digest("当前有效事实"),
        "old" to VectorRecallSources.digest("已否定的旧事实"),
    ))

    @Test fun contentLabelsSurviveRegeneratedIdsWithoutChangingBaseline() = runBlocking {
        fixture { db, _, worker, id ->
            val data = boundDataset()
            assertTrue(worker.evaluateAndPublish(id, data).published)
            db.memoryDao().delete("current")
            db.memoryDao().delete("old")
            db.memoryDao().insert(MemoryEntity("new-current", "当前有效事实"))
            db.memoryDao().insert(MemoryEntity("new-old", "已否定的旧事实"))
            worker.build(VectorDomain.MEMORY)
            val next = db.vectorIndexDao().versions("memory").first { it.state == "VALIDATING" }.id
            assertTrue(worker.evaluateAndPublish(next, data).published)
            assertEquals(db.vectorIndexDao().validation(id)!!.datasetDigest,
                db.vectorIndexDao().validation(next)!!.datasetDigest)
        }
    }

    @Test fun ambiguousContentCannotSilentlyRebindLabels() = runBlocking {
        fixture { db, provider, worker, _ ->
            db.memoryDao().insert(MemoryEntity("duplicate", "当前有效事实"))
            worker.build(VectorDomain.MEMORY)
            val next = db.vectorIndexDao().versions("memory").first { it.state == "VALIDATING" }.id
            val error = runCatching { worker.evaluateAndPublish(next, boundDataset()) }.exceptionOrNull()
            assertEquals("vector_evaluation_source_ambiguous", error?.message)
            assertEquals(0, provider.calls)
            assertNull(db.vectorIndexDao().active("memory"))
        }
    }

    @Test fun journalRetainsRejectionAcrossRecreationWithoutApproval() = runBlocking {
        fixture { db, provider, _, id ->
            val root = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                "recall-journal-${java.util.UUID.randomUUID()}")
            try {
                provider.wrong = true
                val worker = VectorIndexWorker(db.vectorIndexDao(), provider, VectorEvaluationJournal(root))
                assertFalse(worker.evaluateAndPublish(id, boundDataset()).published)
                val record = org.json.JSONObject(VectorEvaluationJournal(root).latest(VectorDomain.MEMORY)!!)
                assertFalse(record.getBoolean("published"))
                assertEquals(0.0, record.getDouble("candidateRecall"), 0.0)
                assertEquals(3, record.getInt("evaluatedSamples"))
                assertFalse(record.toString().contains("独立问题"))
                assertNull(db.vectorIndexDao().validation(id))
                provider.wrong = false
                assertTrue(worker.evaluateAndPublish(id, boundDataset()).published)
                assertTrue(org.json.JSONObject(VectorEvaluationJournal(root).latest(VectorDomain.MEMORY)!!)
                    .getBoolean("published"))
                assertEquals(1, root.listFiles()!!.size)
            } finally { root.deleteRecursively() }
        }
    }
}

