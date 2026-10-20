package com.zero.v2.services.embedding

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.KbChunkEntity
import com.zero.v2.core.data.entity.KbDocEntity
import com.zero.v2.core.data.entity.VectorValidationEntity
import com.zero.v2.core.data.knowledge.ScopedDocumentQuery
import com.zero.v2.services.retrieval.RetrievalKit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQLite and production worker; deterministic vectors, no provider-quality claim. */
@RunWith(AndroidJUnit4::class)
class ScopedKnowledgeRetrievalTest {
    private class Provider : EmbeddingProvider {
        var queries = 0
        override val modelId = "scope-fixture"
        override suspend fun isAvailable() = true
        override suspend fun embed(text: String): FloatArray {
            queries++
            return floatArrayOf(1f, 0f)
        }
        override suspend fun embedBatch(texts: List<String>) = texts.map {
            when {
                "范围外" in it -> floatArrayOf(1f, 0f)
                "最佳" in it -> floatArrayOf(0.8f, 0.6f)
                else -> floatArrayOf(0.3f, 0.9f)
            }
        }
    }

    @Test fun scopePrecedesSqlLimitAndVectorTopKAcrossPagesAndBindBatches() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        try {
            val dao = db.kbDao()
            val outside = KbDocEntity("outside", "outside.md", "范围外", 0, 0, 320, 0)
            val target = KbDocEntity("target", "allowed.md", "范围内", 0, 0, 65, 0)
            val second = KbDocEntity("second", "second.md", "范围内第二篇", 0, 0, 65, 0)
            dao.insertDoc(outside)
            dao.insertDoc(target)
            dao.insertDoc(second)
            val outsideChunks = (0 until 320).map {
                KbChunkEntity("a%03d".format(it), outside.id, it, text = "培训范围外", charLen = 5, updatedAt = 0)
            }
            val insideChunks = (0 until 130).map {
                KbChunkEntity("z%03d".format(it), if (it < 65) target.id else second.id, it,
                    text = if (it == 129) "培训最佳" else "培训相关", charLen = 4,
                    embedding = RetrievalKit.encodeEmbedding(floatArrayOf(1f, 0f)), embedModel = "legacy", updatedAt = 0)
            }
            dao.insertChunks(outsideChunks + insideChunks)
            val documents = listOf(second.id) + (0 until 501).map { "absent$it" } + target.id
            val lexical = ScopedDocumentQuery.page(documents, 300, { row: KbChunkEntity -> row.id }) {
                assertTrue(it.size <= 500)
                dao.searchScopedText("培训", it, 300)
            }
            assertEquals(130, lexical.size)
            assertTrue(lexical.all { it.docId in setOf(target.id, second.id) })
            val legacy = dao.scopedEmbeddingPage("legacy", "z127", listOf(second.id), 128)
            assertEquals(listOf("z128", "z129"), legacy.map { it.id })

            val provider = Provider()
            val worker = VectorIndexWorker(db.vectorIndexDao(), provider)
            assertEquals(VectorIndexWorker.Outcome.WAITING_VALIDATION, worker.build(VectorDomain.KNOWLEDGE))
            val version = db.vectorIndexDao().versions("knowledge").single()
            db.vectorIndexDao().approve(VectorValidationEntity(version.id, version.digest,
                "synthetic-scope-fixture", "a".repeat(64), 1, 1, 0.0, 1.0, 0.9, "synthetic mechanics only"))
            worker.publishValidated(VectorDomain.KNOWLEDGE, version.id)
            assertTrue(worker.search(VectorDomain.KNOWLEDGE, "培训", 1)!!.single().sourceId.startsWith("a"))
            assertEquals("z129", worker.search(VectorDomain.KNOWLEDGE, "培训", 1, documents)!!.single().sourceId)
            val queries = provider.queries
            assertTrue(worker.search(VectorDomain.KNOWLEDGE, "培训", 1, emptyList())!!.isEmpty())
            assertEquals(queries, provider.queries)
            dao.replaceDocument(second, insideChunks.filter { it.docId == second.id }
                .map { if (it.id == "z129") it.copy(text = "已更正") else it })
            assertFalse(worker.search(VectorDomain.KNOWLEDGE, "培训", 1, documents)!!.any { it.sourceId == "z129" })
        } finally { db.close() }
    }
}
