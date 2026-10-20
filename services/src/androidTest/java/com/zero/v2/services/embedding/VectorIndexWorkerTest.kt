package com.zero.v2.services.embedding

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.KbChunkEntity
import com.zero.v2.core.data.entity.KbDocEntity
import com.zero.v2.core.data.entity.MemoryEntity
import com.zero.v2.core.data.entity.VectorEntryEntity
import com.zero.v2.core.data.entity.VectorValidationEntity
import com.zero.v2.core.data.entity.VectorVersionEntity
import com.zero.v2.services.retrieval.RetrievalKit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class VectorIndexWorkerTest {
    @Test fun explicitRollbackRequiresSelectedOldBackendAndSurvivesReopen() = runBlocking {
        val name = "vector-rollback-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, ZeroDatabase::class.java, name).build()
        try {
            seed(db, 1)
            val provider = Provider()
            val worker = VectorIndexWorker(db.vectorIndexDao(), provider)
            worker.build(VectorDomain.MEMORY); approveAndPublish(db, worker, provider)
            val old = db.vectorIndexDao().active("memory")!!.id
            provider.identity = "second-endpoint/model"
            worker.build(VectorDomain.MEMORY); approveAndPublish(db, worker, provider)
            val current = db.vectorIndexDao().active("memory")!!.id
            val calls = provider.calls
            assertTrue(runCatching { worker.maintenance.rollbackValidated(VectorDomain.MEMORY, current) }.isFailure)
            assertEquals(current, db.vectorIndexDao().active("memory")!!.id)
            provider.identity = "endpoint/model"
            assertTrue(worker.maintenance.rollbackValidated(VectorDomain.MEMORY, current))
            assertEquals(calls, provider.calls)
            db.close()
            db = Room.databaseBuilder(context, ZeroDatabase::class.java, name).build()
            assertEquals(old, db.vectorIndexDao().active("memory")!!.id)
            val resumed = VectorIndexWorker(db.vectorIndexDao(), provider)
            assertEquals("000", resumed.search(VectorDomain.MEMORY, "记忆", 1)!!.single().sourceId)
            assertEquals("legacy", db.memoryDao().getById("000")!!.embedModel)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun database() = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()

    private class Provider : EmbeddingProvider {
        override var modelId = "model"
        override var identity = "endpoint/model"
        var available = true
        var calls = 0
        val inputs = mutableListOf<String>()
        var onBatch: suspend (Int) -> Unit = {}
        var onQuery: suspend () -> Unit = {}
        var missingAt = 0
        var dimensions = 2
        override suspend fun isAvailable() = available
        override suspend fun embed(text: String): FloatArray {
            onQuery()
            return floatArrayOf(1f, 0f)
        }
        override suspend fun embedBatch(texts: List<String>): List<FloatArray?> {
            calls++
            inputs += texts
            onBatch(calls)
            return texts.map { if (calls == missingAt) null else FloatArray(dimensions) { index -> if (index == 0) 1f else 0f } }
        }
    }

    private suspend fun seed(db: ZeroDatabase, count: Int = 11) {
        repeat(count) { index ->
            val id = "%03d".format(index)
            db.memoryDao().insert(MemoryEntity(id, "记忆 $id", embedding = RetrievalKit.encodeEmbedding(floatArrayOf(0f, 1f)), embedModel = "legacy"))
        }
    }

    // Synthetic reports exercise publication wiring only; no claim of semantic recall quality.
    private suspend fun approveAndPublish(db: ZeroDatabase, worker: VectorIndexWorker, provider: Provider) {
        val dao = db.vectorIndexDao()
        val candidate = dao.versions("memory").first { it.space == provider.spaceId(EmbeddingSpace.MEMORY) && it.state == "VALIDATING" }
        dao.approve(VectorValidationEntity(candidate.id, candidate.digest, "worker-fixture-v1", "a".repeat(64),
            2, 2, 1.0, 1.0, 0.9, "synthetic worker test"))
        worker.publishValidated(VectorDomain.MEMORY, candidate.id)
    }

    @Test fun failedBatchResumesFromDiskWithoutReembeddingCompletedRows() = runBlocking {
        val name = "vector-worker-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, ZeroDatabase::class.java, name).build()
        try {
            seed(db)
            val provider = Provider().apply { missingAt = 2 }
            val worker = VectorIndexWorker(db.vectorIndexDao(), provider)
            assertEquals(VectorIndexWorker.Outcome.RETRY, worker.build(VectorDomain.MEMORY))
            assertEquals(10, worker.status(VectorDomain.MEMORY).staged)
            assertTrue(worker.retryAt(VectorDomain.MEMORY) > System.currentTimeMillis())
            assertTrue(worker.retryAt(VectorDomain.MEMORY) != Long.MAX_VALUE)
            db.close()
            db = Room.databaseBuilder(context, ZeroDatabase::class.java, name).build()
            val resumedProvider = Provider()
            val resumed = VectorIndexWorker(db.vectorIndexDao(), resumedProvider)
            assertEquals(VectorIndexWorker.Outcome.WAITING_VALIDATION, resumed.build(VectorDomain.MEMORY))
            assertEquals(Long.MAX_VALUE, resumed.retryAt(VectorDomain.MEMORY))
            assertEquals(listOf("记忆 010"), resumedProvider.inputs)
            assertEquals(11, resumed.status(VectorDomain.MEMORY).staged)
            assertEquals("legacy", db.memoryDao().getById("000")!!.embedModel)
            assertNull(resumed.search(VectorDomain.MEMORY, "query", 10))
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun cancellationPropagatesAndKeepsCompletedCheckpoint() = runBlocking {
        val db = database()
        try {
            seed(db)
            val provider = Provider().apply { onBatch = { if (it == 2) throw CancellationException("cancel") } }
            val worker = VectorIndexWorker(db.vectorIndexDao(), provider)
            assertTrue(runCatching { worker.build(VectorDomain.MEMORY) }.exceptionOrNull() is CancellationException)
            assertEquals(10, worker.status(VectorDomain.MEMORY).staged)
            provider.onBatch = {}
            assertEquals(VectorIndexWorker.Outcome.WAITING_VALIDATION, worker.build(VectorDomain.MEMORY))
            assertEquals(3, provider.calls)
        } finally { db.close() }
    }

    @Test fun endpointSwitchKeepsPublishedVersionAndDoesNotMixQuerySpaces() = runBlocking {
        val db = database()
        try {
            seed(db, 2)
            val provider = Provider()
            val worker = VectorIndexWorker(db.vectorIndexDao(), provider)
            worker.build(VectorDomain.MEMORY)
            approveAndPublish(db, worker, provider)
            val old = db.vectorIndexDao().active("memory")!!.id
            provider.identity = "another-endpoint/model"
            assertEquals(VectorIndexWorker.Outcome.WAITING_VALIDATION, worker.build(VectorDomain.MEMORY))
            assertEquals(old, db.vectorIndexDao().active("memory")!!.id)
            assertTrue(worker.search(VectorDomain.MEMORY, "query", 10)!!.isEmpty())
            assertEquals(2, db.vectorIndexDao().publishedPage(old, "", 10).size)
            assertEquals("legacy", db.memoryDao().getById("000")!!.embedModel)
        } finally { db.close() }
    }

    @Test fun providerChangesDuringRequestCannotLabelVectorsWithOldSpace() = runBlocking {
        val db = database()
        try {
            seed(db, 1)
            val provider = Provider().apply { onBatch = { if (it == 1) identity = "changed" } }
            val worker = VectorIndexWorker(db.vectorIndexDao(), provider)
            assertEquals(VectorIndexWorker.Outcome.WAITING_VALIDATION, worker.build(VectorDomain.MEMORY))
            assertEquals(2, provider.calls)
            assertEquals(provider.spaceId(EmbeddingSpace.MEMORY), db.vectorIndexDao().versions("memory").single().space)
        } finally { db.close() }
    }

    @Test fun dimensionDriftRebuildsCandidateWithoutPublishingMixedVectors() = runBlocking {
        val db = database()
        try {
            seed(db)
            val provider = Provider().apply { onBatch = { if (it == 2) dimensions = 3 } }
            val worker = VectorIndexWorker(db.vectorIndexDao(), provider)
            assertEquals(VectorIndexWorker.Outcome.WAITING_VALIDATION, worker.build(VectorDomain.MEMORY))
            val version = db.vectorIndexDao().versions("memory").single()
            assertEquals(3, version.dimensions)
            assertTrue(db.vectorIndexDao().candidatePage(version.id, "", 100).all { it.vector.size == 12 })
            assertNull(db.vectorIndexDao().active("memory"))
        } finally { db.close() }
    }

    @Test fun sourceMutationDuringBuildRestartsOnFreshRows() = runBlocking {
        val db = database()
        try {
            seed(db)
            val provider = Provider().apply {
                onBatch = { if (it == 2) db.memoryDao().insert(db.memoryDao().getById("000")!!.copy(content = "纠正后的记忆")) }
            }
            val worker = VectorIndexWorker(db.vectorIndexDao(), provider)
            assertEquals(VectorIndexWorker.Outcome.WAITING_VALIDATION, worker.build(VectorDomain.MEMORY))
            val version = db.vectorIndexDao().versions("memory").single()
            assertEquals("纠正后的记忆", db.vectorIndexDao().entriesFor(version.id, listOf("000")).single().sourceText)
        } finally { db.close() }
    }

    @Test fun knowledgeUsesTitleHeadingAndTextWithoutChangingLegacyVector() = runBlocking {
        val db = database()
        try {
            db.kbDao().replaceDocument(KbDocEntity("doc", "test.md", "标题", 1, 2, 1, 1),
                listOf(KbChunkEntity("chunk", "doc", 0, "章节", "正文", 2, byteArrayOf(1), "legacy", 1)))
            val provider = Provider()
            val worker = VectorIndexWorker(db.vectorIndexDao(), provider)
            assertEquals(VectorIndexWorker.Outcome.WAITING_VALIDATION, worker.build(VectorDomain.KNOWLEDGE))
            assertEquals(listOf("《标题》 章节 ▸ 正文"), provider.inputs)
            assertEquals("legacy", db.kbDao().getChunk("chunk")!!.embedModel)
            assertTrue(db.vectorIndexDao().versions("memory").isEmpty())
        } finally { db.close() }
    }

    @Test fun publicationDuringQueryDoesNotChangePinnedVersion() = runBlocking {
        val db = database()
        try {
            seed(db, 2)
            val provider = Provider()
            val dao = db.vectorIndexDao()
            val worker = VectorIndexWorker(dao, provider)
            worker.build(VectorDomain.MEMORY)
            approveAndPublish(db, worker, provider)
            val old = dao.active("memory")!!
            val source = dao.sourcePage("memory", "", 10)
            dao.begin(VectorVersionEntity("next", "memory", old.space, 2, 2, old.id, createdAt = 2))
            dao.stage("next", source.map { row -> VectorEntryEntity("next", row.id, row.text,
                vector = RetrievalKit.encodeEmbedding(if (row.id == "001") floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f))) })
            val digest = dao.seal("next")
            dao.approve(VectorValidationEntity("next", digest, "worker-fixture-v1", "a".repeat(64), 2, 2, 1.0, 1.0, 0.9, "fixture"))
            provider.onQuery = { dao.publish("next") }
            val before = worker.search(VectorDomain.MEMORY, "query", 2)!!
            assertTrue(before.all { it.versionId == old.id })
            provider.onQuery = {}
            assertEquals("001", worker.search(VectorDomain.MEMORY, "query", 1)!!.single().sourceId)
            db.memoryDao().delete("001")
            assertTrue(worker.search(VectorDomain.MEMORY, "query", 10)!!.none { it.sourceId == "001" })
            assertTrue(worker.similar(VectorDomain.MEMORY, "001", 0f, 5)!!.isEmpty())
        } finally { db.close() }
    }

    @Test fun unavailableProviderDoesNotCreateJobOrSpendRequests() = runBlocking {
        val db = database()
        try {
            seed(db, 1)
            val provider = Provider().apply { available = false }
            assertEquals(VectorIndexWorker.Outcome.NO_PROVIDER, VectorIndexWorker(db.vectorIndexDao(), provider).build(VectorDomain.MEMORY))
            assertEquals(0, provider.calls)
            assertTrue(db.vectorIndexDao().versions("memory").isEmpty())
        } finally { db.close() }
    }

    @Test fun authorizationRejectionPausesScheduledRetryAndPreservesSources() = runBlocking {
        val db = database()
        try {
            seed(db, 1)
            val provider = Provider().apply {
                missingAt = 1
                onBatch = { available = false }
            }
            val worker = VectorIndexWorker(db.vectorIndexDao(), provider)
            assertEquals(VectorIndexWorker.Outcome.NO_PROVIDER, worker.build(VectorDomain.MEMORY))
            assertEquals(Long.MAX_VALUE, worker.retryAt(VectorDomain.MEMORY))
            assertEquals(VectorIndexWorker.Outcome.NO_PROVIDER, worker.build(VectorDomain.MEMORY))
            assertEquals(1, provider.calls)
            assertNotNull(db.memoryDao().getById("000"))
        } finally { db.close() }
    }

    @Test fun sameSpaceReusesPublishedRowsAndObsoleteCandidatesAreCollected() = runBlocking {
        val db = database()
        try {
            seed(db, 2)
            val provider = Provider()
            val worker = VectorIndexWorker(db.vectorIndexDao(), provider)
            worker.build(VectorDomain.MEMORY)
            approveAndPublish(db, worker, provider)
            val active = db.vectorIndexDao().active("memory")!!.id
            assertEquals(0, worker.status(VectorDomain.MEMORY).backlog)
            db.memoryDao().insert(MemoryEntity("new", "新增记忆"))
            provider.inputs.clear()
            assertEquals(VectorIndexWorker.Outcome.WAITING_VALIDATION, worker.build(VectorDomain.MEMORY))
            assertEquals(listOf("新增记忆"), provider.inputs)
            assertEquals(1, worker.status(VectorDomain.MEMORY).backlog)
            repeat(4) { index ->
                provider.identity = "endpoint-$index/model"
                assertEquals(VectorIndexWorker.Outcome.WAITING_VALIDATION, worker.build(VectorDomain.MEMORY))
                assertEquals(active, db.vectorIndexDao().active("memory")!!.id)
                assertEquals(2, db.vectorIndexDao().versions("memory").size)
                assertEquals(3, worker.status(VectorDomain.MEMORY).backlog)
            }
        } finally { db.close() }
    }
}
