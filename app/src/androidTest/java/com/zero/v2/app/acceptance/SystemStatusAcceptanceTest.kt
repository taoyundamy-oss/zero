package com.zero.v2.app.acceptance

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.system.RetrievalStatusReader
import com.zero.v2.app.ui.settings.RetrievalStatusPresentation
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.MemoryEntity
import com.zero.v2.core.data.entity.MemoryExtractionJobEntity
import com.zero.v2.core.moduleapi.StatusFact
import com.zero.v2.core.moduleapi.SystemStateAssembler
import com.zero.v2.services.embedding.EmbeddingProvider
import com.zero.v2.services.embedding.VectorIndexWorker
import com.zero.v2.services.embedding.EmbeddingSpace
import com.zero.v2.services.embedding.spaceId
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Isolated real SQLite owner -> projection -> UI data path. Provider methods must never run. */
class SystemStatusAcceptanceTest {
    @Test fun metadataChangesAndFailureRemainTruthfulWithoutCloud() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        var model = "metadata-only"
        val provider = object : EmbeddingProvider {
            override val modelId get() = model
            override suspend fun isAvailable(): Boolean = error("Unexpected credential probe")
            override suspend fun embed(text: String): FloatArray? = error("Unexpected paid request")
            override suspend fun embedBatch(texts: List<String>): List<FloatArray?> = error("Unexpected paid request")
        }
        val reader = RetrievalStatusReader(VectorIndexWorker(db.vectorIndexDao(), provider), db.memoryExtractionJobDao())
        try {
            db.withTransaction {
                db.memoryDao().insertAll(listOf(MemoryEntity("m1", "private-source-do-not-expose")))
                db.memoryExtractionJobDao().insertIfNew(MemoryExtractionJobEntity("job1", "USER_FACT",
                    sourceDigest = "private-source-do-not-expose", uniqueKey = "job1"))
                val sql = db.openHelper.writableDatabase
                sql.execSQL("INSERT INTO vector_versions VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf("v1", "memory", provider.spaceId(EmbeddingSpace.MEMORY), 2, 1, null, "PUBLISHED", "", 1))
                sql.execSQL("INSERT INTO vector_active VALUES (?, ?)", arrayOf("memory", "v1"))
            }
            reader.refresh()
            val initial = reader.observation
            assertEquals("memory:v1", initial.indexVersion)
            assertEquals(1, initial.extractionJobs)
            assertEquals(2, initial.backlog)
            assertEquals(true, initial.domains.first().activeSpaceMatches)
            model = "other-model"
            reader.refresh()
            assertEquals(false, reader.observation.domains.first().activeSpaceMatches)
            val observed = reader.observation
            val now = System.currentTimeMillis()
            val inputs = SystemStateAssembler.SystemStateInputs(emptyMap(),
                SystemStateAssembler.BudgetInputs(0, 0, 0.0, 100, 10f), "IDLE",
                SystemStateAssembler.TermuxInputs(false, 7878, null), emptyList(),
                StatusFact.LIVE, StatusFact.UNKNOWN, observed.indexVersion, observed.backlog, now,
                retrievalObservedAt = observed.observedAt, retrievalDomains = observed.domains,
                extractionJobs = observed.extractionJobs, extractionObservedAt = observed.extractionObservedAt)
            val snapshot = SystemStateAssembler.assemble(inputs, 1)
            val full = SystemStateAssembler.zeroStatus(snapshot, setOf("retrieval"), null, 100)
            assertFalse(full.facts.toString().contains("private-source"))
            val projected = (full.facts["retrievalDomains"] as List<*>).first() as Map<*, *>
            assertEquals("v1", projected["activeVersion"])
            assertEquals(false, projected["activeSpaceMatches"])
            assertTrue(RetrievalStatusPresentation.text(snapshot.retrieval.domains.first()).contains("不兼容"))
            val aged = SystemStateAssembler.assemble(inputs.copy(now = now + 60_001), 2)
            assertEquals(StatusFact.STALE, aged.retrieval.domains.first().freshness)
            assertTrue(SystemStateAssembler.zeroStatus(aged, setOf("retrieval"), null, 100).stale.contains("retrieval.memory"))
            val stamp = observed.domains.first().observedAt
            db.withTransaction { db.openHelper.writableDatabase.execSQL("DROP TABLE memories") }
            reader.refresh()
            assertEquals(stamp, reader.observation.domains.first().observedAt)
            assertEquals("owner_read_failed", reader.observation.domains.first().readError)
            assertNull(reader.observation.backlog)
            File(context.filesDir, "acceptance-a5-status.json").writeText(JSONObject()
                .put("completed", true).put("schemaVersion", snapshot.schemaVersion)
                .put("isolatedSqlite", true).put("providerCalls", 0)
                .put("versionAndModelSwitch", true).put("failureAndTtl", true)
                .put("sourceTextExcluded", true).put("uiMatchesProjection", true).toString(2))
        } finally { db.close() }
    }
}
