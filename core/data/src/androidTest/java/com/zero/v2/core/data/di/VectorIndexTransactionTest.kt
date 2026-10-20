package com.zero.v2.core.data.di

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.data.dao.VectorIndexDao
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.KbChunkEntity
import com.zero.v2.core.data.entity.KbDocEntity
import com.zero.v2.core.data.entity.MemoryEntity
import com.zero.v2.core.data.entity.VectorEntryEntity
import com.zero.v2.core.data.entity.VectorValidationEntity
import com.zero.v2.core.data.entity.VectorVersionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class VectorIndexTransactionTest {
    @Test fun rollbackRequiresCurrentPointerSpaceAndUnchangedSources() = runBlocking {
        val db = database()
        try {
            seed(db)
            val dao = db.vectorIndexDao()
            build(dao, "old"); approve(dao, "old"); dao.publish("old")
            build(dao, "new"); approve(dao, "new"); dao.publish("new")
            assertTrue(runCatching { dao.rollback("memory", "stale", "old", "space:old") }.isFailure)
            assertTrue(runCatching { dao.rollback("memory", "new", "old", "space:new") }.isFailure)
            assertEquals("new", dao.active("memory")!!.id)
            assertTrue(dao.rollback("memory", "new", "old", "space:old"))
            assertEquals("old", dao.active("memory")!!.id)
            assertEquals("RETIRED", dao.version("new")!!.state)
            build(dao, "next"); approve(dao, "next"); dao.publish("next")
            db.memoryDao().insert(db.memoryDao().getById("same-id")!!.copy(content = "更正后的新事实"))
            assertTrue(runCatching { dao.rollback("memory", "next", "old", "space:old") }.isFailure)
            assertEquals("next", dao.active("memory")!!.id)
            assertEquals("更正后的新事实", db.memoryDao().getById("same-id")!!.content)
        } finally { db.close() }
    }

    @Test fun forgetPhysicallyRemovesOnlyMemoryDomainCopies() = runBlocking {
        val db = database()
        try {
            seed(db)
            val dao = db.vectorIndexDao()
            build(dao, "old"); approve(dao, "old"); assertTrue(dao.publish("old"))
            build(dao, "current"); approve(dao, "current"); assertTrue(dao.publish("current"))
            build(dao, "candidate")
            build(dao, "knowledge", "knowledge")
            db.memoryExtractionJobDao().forgetById("same-id", 2)
            listOf("old", "current", "candidate").forEach { assertEquals(0, dao.countEntries(it)) }
            assertEquals(1, dao.countEntries("knowledge"))
            // Historical benchmark receipt is retained for the next candidate's comparison.
            assertNotNull(dao.validation("current"))
            assertEquals("current", dao.active("memory")!!.id)
            assertTrue(runCatching {
                dao.stage("candidate", listOf(VectorEntryEntity("candidate", "same-id", "用户喜欢清淡饮食", vector = vector())))
            }.isFailure)
            assertEquals(0, dao.countEntries("candidate"))
        } finally { db.close() }
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun database() = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
    private fun vector(a: Float = 1f, b: Float = 0f) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(a).putFloat(b).array()

    private suspend fun seed(db: ZeroDatabase) {
        db.memoryDao().insert(MemoryEntity("same-id", "用户喜欢清淡饮食", embedding = vector(), embedModel = "legacy"))
        db.kbDao().replaceDocument(KbDocEntity("doc", "food.md", "饮食指南", 1, 20, 1, 1),
            listOf(KbChunkEntity("same-id", "doc", 0, "早餐", "少盐", 2, vector(), "legacy", 1)))
    }

    private suspend fun build(dao: VectorIndexDao, id: String, domain: String = "memory") {
        dao.begin(VectorVersionEntity(id, domain, "space:$id", 2, dao.sourceCount(domain), dao.active(domain)?.id, createdAt = 1))
        dao.stage(id, dao.sourcePage(domain, "", 100).map {
            VectorEntryEntity(id, it.id, it.text, it.title, it.heading, vector())
        })
    }

    /** Synthetic storage receipt only; this is not a provider recall benchmark. */
    private suspend fun approve(dao: VectorIndexDao, id: String) {
        val digest = dao.seal(id)
        dao.approve(report(id, digest))
    }

    private fun report(id: String, digest: String) = VectorValidationEntity(
        id, digest, "storage-fixture-v1", "a".repeat(64), 2, 2, 1.0, 1.0, 0.9, "synthetic transaction fixture")

    @Test fun domainsAndLegacyVectorsRemainIndependent() = runBlocking {
        val db = database()
        try {
            seed(db)
            val dao = db.vectorIndexDao()
            build(dao, "m"); build(dao, "k", "knowledge")
            assertNull(dao.active("memory"))
            assertTrue(dao.publishedPage("m", "", 100).isEmpty())
            approve(dao, "m"); approve(dao, "k")
            assertTrue(dao.publish("m")); assertTrue(dao.publish("k"))
            assertEquals("m", dao.active("memory")!!.id)
            assertEquals("k", dao.active("knowledge")!!.id)
            assertEquals("用户喜欢清淡饮食", dao.publishedPage("m", "", 100).single().sourceText)
            assertEquals("少盐", dao.publishedPage("k", "", 100).single().sourceText)
            assertEquals("legacy", db.memoryDao().getById("same-id")!!.embedModel)
            assertEquals("legacy", db.kbDao().searchByText("少盐", 10).single().embedModel)
        } finally { db.close() }
    }

    @Test fun invalidVectorsCannotEnterCandidate() = runBlocking {
        val db = database()
        try {
            seed(db)
            val dao = db.vectorIndexDao()
            dao.begin(VectorVersionEntity("m", "memory", "space", 2, 1, null, createdAt = 1))
            for (bad in listOf(byteArrayOf(1), vector(0f, 0f), vector(Float.NaN), vector(Float.POSITIVE_INFINITY))) {
                assertTrue(runCatching { dao.stage("m", listOf(VectorEntryEntity("m", "same-id", "用户喜欢清淡饮食", vector = bad))) }.isFailure)
                assertEquals(0, dao.countEntries("m"))
            }
            assertTrue(runCatching { dao.seal("m") }.isFailure)
            assertNull(dao.active("memory"))
        } finally { db.close() }
    }

    @Test fun reportsMustCoverAllSamplesAndExactFrozenCandidateWithoutRegression() = runBlocking {
        val db = database()
        try {
            seed(db)
            val dao = db.vectorIndexDao()
            build(dao, "m")
            val digest = dao.seal("m")
            assertTrue(runCatching { dao.stage("m", dao.candidatePage("m", "", 10)) }.isFailure)
            for (bad in listOf(report("m", "wrong"), report("m", digest).copy(evaluatedSamples = 1),
                report("m", digest).copy(candidateRecall = 0.99), report("m", digest).copy(candidateRecall = Double.NaN))) {
                assertTrue(runCatching { dao.approve(bad) }.isFailure)
                assertEquals("VALIDATING", dao.version("m")!!.state)
            }
            assertTrue(runCatching { dao.publish("m") }.isFailure)
            dao.approve(report("m", digest))
            assertTrue(dao.publish("m"))
            assertFalse(dao.publish("m"))
        } finally { db.close() }
    }

    @Test fun sourceChangesAfterEvaluationRefusePromotion() = runBlocking {
        val db = database()
        try {
            seed(db)
            val dao = db.vectorIndexDao()
            build(dao, "old"); approve(dao, "old"); dao.publish("old")
            build(dao, "new"); approve(dao, "new")
            db.memoryDao().insert(db.memoryDao().getById("same-id")!!.copy(content = "用户现在不喜欢清淡饮食"))
            assertTrue(runCatching { dao.publish("new") }.isFailure)
            assertEquals("old", dao.active("memory")!!.id)
            assertTrue(dao.publishedPage("old", "", 10).isEmpty())
        } finally { db.close() }
    }

    @Test fun callerCannotLowerRecordedBaselineOrChangeDatasetToBypassRegressionGate() = runBlocking {
        val db = database()
        try {
            seed(db)
            val dao = db.vectorIndexDao()
            build(dao, "old"); approve(dao, "old"); dao.publish("old")
            build(dao, "new")
            val digest = dao.seal("new")
            assertTrue(runCatching {
                dao.approve(report("new", digest).copy(baselineRecall = 0.5, candidateRecall = 0.9))
            }.isFailure)
            assertTrue(runCatching {
                dao.approve(report("new", digest).copy(datasetDigest = "b".repeat(64)))
            }.isFailure)
            assertEquals("old", dao.active("memory")!!.id)
        } finally { db.close() }
    }

    @Test fun failedPointerTransactionPreservesOldVersionAndCanRetry() = runBlocking {
        val db = database()
        try {
            seed(db)
            val dao = db.vectorIndexDao()
            build(dao, "old"); approve(dao, "old"); dao.publish("old")
            build(dao, "new"); approve(dao, "new")
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_publish BEFORE UPDATE ON vector_versions " +
                "WHEN NEW.id = 'new' AND NEW.state = 'PUBLISHED' BEGIN SELECT RAISE(ABORT, 'injected failure'); END")
            assertTrue(runCatching { dao.publish("new") }.isFailure)
            assertEquals("old", dao.active("memory")!!.id)
            assertEquals("PUBLISHED", dao.version("old")!!.state)
            assertEquals("READY", dao.version("new")!!.state)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_publish")
            assertTrue(dao.publish("new"))
            assertEquals("RETIRED", dao.version("old")!!.state)
            // A query pinned before the swap can finish on its old version.
            assertEquals(1, dao.publishedPage("old", "", 10).size)
            db.memoryDao().delete("same-id")
            assertTrue(dao.publishedPage("old", "", 10).isEmpty())
            assertTrue(dao.publishedPage("new", "", 10).isEmpty())
        } finally { db.close() }
    }

    @Test fun competingCandidateCannotReplaceNewerPublishedVersion() = runBlocking {
        val db = database()
        try {
            seed(db)
            val dao = db.vectorIndexDao()
            build(dao, "a"); build(dao, "b")
            approve(dao, "a"); approve(dao, "b")
            dao.publish("a")
            assertTrue(runCatching { dao.publish("b") }.isFailure)
            assertEquals("a", dao.active("memory")!!.id)
            dao.abandon("b")
            assertEquals("FAILED", dao.version("b")!!.state)
        } finally { db.close() }
    }

    @Test fun candidateAndReceiptSurviveDatabaseReopen() = runBlocking {
        val name = "vector-recovery-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, ZeroDatabase::class.java, name).build()
        try {
            seed(db)
            build(db.vectorIndexDao(), "m")
            db.close()
            db = Room.databaseBuilder(context, ZeroDatabase::class.java, name).build()
            assertEquals("BUILDING", db.vectorIndexDao().version("m")!!.state)
            approve(db.vectorIndexDao(), "m")
            db.close()
            db = Room.databaseBuilder(context, ZeroDatabase::class.java, name).build()
            assertEquals("READY", db.vectorIndexDao().version("m")!!.state)
            assertTrue(db.vectorIndexDao().publish("m"))
            assertEquals("storage-fixture-v1", db.vectorIndexDao().validation("m")!!.datasetVersion)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
