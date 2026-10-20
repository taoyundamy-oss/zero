package com.zero.v2.app.acceptance

import android.os.Process
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.common.MemoryStatus
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.KbChunkEntity
import com.zero.v2.core.data.entity.KbDocEntity
import com.zero.v2.core.data.entity.MemoryEntity
import com.zero.v2.services.retrieval.RetrievalKit
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** DAO differential probe only; no personal database, cloud provider or index publishing. */
@RunWith(AndroidJUnit4::class)
class LexicalGroupReadAcceptanceTest {
    @Test fun groupedReadsPreserveLegacyRowsAndMeasureActualCost() = runBlocking {
        listOf(100, 1000, 10000).forEach { checkCorpus(it) }
    }

    private suspend fun checkCorpus(size: Int) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        try {
            val corpus = ScaleRecallFixture.corpus(size)
            val memories = corpus.mapIndexed { i, row -> MemoryEntity("row-$i", row.text, createdAt = i.toLong()) } +
                listOf(MemoryEntity("literal", "字面%_设备巡检", keywords = "关键词测试", embedding = byteArrayOf(1, 2)),
                    MemoryEntity("archived", "设备巡检", status = MemoryStatus.ARCHIVED))
            val chunks = corpus.mapIndexed { i, row ->
                val doc = if (i < 22) "row-$i" else "noise-${(i - 22) / 25}"
                KbChunkEntity("row-$i", doc, i, text = row.text, charLen = row.text.length, updatedAt = 0)
            } + KbChunkEntity("literal", "literal", 0, text = "字面%_设备巡检", charLen = 9,
                embedding = byteArrayOf(1, 2), updatedAt = 0)
            db.withTransaction {
                memories.chunked(100).forEach { db.memoryDao().insertAll(it) }
                chunks.map { it.docId }.distinct().forEach { db.kbDao().insertDoc(KbDocEntity(it, "$it.md", mtime = 0, size = 0, indexedAt = 0)) }
                chunks.chunked(100).forEach { db.kbDao().insertChunks(it) }
            }
            val cases = ScaleRecallFixture.samples.map { RetrievalKit.queryBigrams(it.query) } +
                listOf(listOf("设备", "巡检", "%", "_", "关键词测试", "设备", "", "不存在"))
            val rows = JSONArray()
            cases.forEachIndexed { index, terms ->
                val oldCpuMem = Process.getElapsedCpuTime(); val oldStartMem = System.nanoTime()
                val oldMem = terms.map { db.memoryDao().searchActiveByKeyword(it, 100) }
                val oldMemMs = (System.nanoTime() - oldStartMem) / 1_000_000.0
                val oldMemCpu = Process.getElapsedCpuTime() - oldCpuMem
                val cpuMem = Process.getElapsedCpuTime(); val startMem = System.nanoTime()
                val newMem = db.memoryDao().searchActiveKeywordGroups(terms, 100)
                val memMs = (System.nanoTime() - startMem) / 1_000_000.0
                val memCpu = Process.getElapsedCpuTime() - cpuMem
                assertEquals(oldMem.map { g -> g.map { it.copy(embedding = null) } }, newMem.map { g -> g.map { it.copy(embedding = null) } })
                oldMem.zip(newMem).forEach { (a, b) -> a.zip(b).forEach { (x, y) -> assertArrayEquals(x.embedding, y.embedding) } }
                val oldCpuKb = Process.getElapsedCpuTime(); val oldStartKb = System.nanoTime()
                val oldKb = terms.map { db.kbDao().searchByText(it, 300) }
                val oldKbMs = (System.nanoTime() - oldStartKb) / 1_000_000.0
                val oldKbCpu = Process.getElapsedCpuTime() - oldCpuKb
                val cpuKb = Process.getElapsedCpuTime(); val startKb = System.nanoTime()
                val newKb = db.kbDao().searchTextGroups(terms, 300)
                val kbMs = (System.nanoTime() - startKb) / 1_000_000.0
                val kbCpu = Process.getElapsedCpuTime() - cpuKb
                assertEquals(oldKb.map { g -> g.map { it.copy(embedding = null) } }, newKb.map { g -> g.map { it.copy(embedding = null) } })
                oldKb.zip(newKb).forEach { (a, b) -> a.zip(b).forEach { (x, y) -> assertArrayEquals(x.embedding, y.embedding) } }
                rows.put(JSONObject().put("case", index).put("terms", terms.size)
                    .put("memoryLegacyMs", oldMemMs).put("memoryLegacyCpuMs", oldMemCpu)
                    .put("memoryGroupMs", memMs).put("memoryGroupCpuMs", memCpu)
                    .put("knowledgeLegacyMs", oldKbMs).put("knowledgeLegacyCpuMs", oldKbCpu)
                    .put("knowledgeGroupMs", kbMs).put("knowledgeGroupCpuMs", kbCpu)
                    .put("equivalent", true))
            }
            assertTrue(db.memoryDao().searchActiveKeywordGroups(emptyList()).isEmpty())
            assertTrue(db.kbDao().searchTextGroups(emptyList(), 300).isEmpty())
            if (size > 2000) {
                val shared = db.memoryDao().searchActiveKeywordGroups(listOf("设备", "巡检"))
                val common = shared[0].first { a -> shared[1].any { it.id == a.id } }
                assertSame(common, shared[1].first { it.id == common.id })
            } else if (size == 100) {
                db.withTransaction {
                    db.memoryDao().insertAll((memories.size until 500).map { MemoryEntity("boundary-$it", "边界资料") })
                    db.kbDao().insertChunks((chunks.size until 500).map {
                        KbChunkEntity("boundary-$it", "literal", it, text = "边界资料", charLen = 4, updatedAt = 0)
                    })
                }
                db.withTransaction {
                    db.memoryDao().insert(MemoryEntity("boundary-500", "边界资料"))
                    db.kbDao().insertChunks(listOf(KbChunkEntity("boundary-500", "literal", 500,
                        text = "边界资料", charLen = 4, updatedAt = 0)))
                }
                assertEquals(db.memoryDao().searchActiveByKeyword("边界", 100),
                    db.memoryDao().searchActiveKeywordGroups(listOf("边界"), 100).single())
                assertEquals(db.kbDao().searchByText("边界", 300),
                    db.kbDao().searchTextGroups(listOf("边界"), 300).single())
                db.withTransaction {
                    db.memoryDao().insertAll((501 until 2000).map { MemoryEntity("boundary-$it", "边界资料") })
                    db.kbDao().insertChunks((501 until 2000).map {
                        KbChunkEntity("boundary-$it", "literal", it, text = "边界资料", charLen = 4, updatedAt = 0)
                    })
                }
                assertFalse(db.memoryDao().hasManyLexicalRows())
                assertFalse(db.kbDao().hasManyLexicalRows())
                db.withTransaction {
                    db.memoryDao().insert(MemoryEntity("late-2000", "新到达", embedding = byteArrayOf(7, 9)))
                    db.kbDao().insertChunks(listOf(KbChunkEntity("late-2000", "literal", 2000,
                        text = "新到达", charLen = 3, embedding = byteArrayOf(7, 9), updatedAt = 0)))
                }
                assertTrue(db.memoryDao().hasManyLexicalRows())
                assertTrue(db.kbDao().hasManyLexicalRows())
                // Crossing the strategy boundary cannot hide newly inserted facts.
                val freshMemory = db.memoryDao().searchActiveKeywordGroups(listOf("新到达")).single().single()
                val freshChunk = db.kbDao().searchTextGroups(listOf("新到达"), 300).single().single()
                assertEquals("late-2000", freshMemory.id)
                assertEquals("late-2000", freshChunk.id)
                assertArrayEquals(byteArrayOf(7, 9), freshMemory.embedding)
                assertArrayEquals(byteArrayOf(7, 9), freshChunk.embedding)
            }
            streamAcceptanceReport(JSONObject().put("scope", "DAO in-memory differential probe; not formal resource gate")
                .put("corpusSize", size)
                .put("cases", rows).put("completed", true))
        } finally { db.close() }
    }
}
