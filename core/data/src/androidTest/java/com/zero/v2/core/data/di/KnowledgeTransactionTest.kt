package com.zero.v2.core.data.di

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.KbChunkEntity
import com.zero.v2.core.data.entity.KbDocEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KnowledgeTransactionTest {
    @Test fun diskCandidatesRollbackOnReadFailureAndReconcileAfterDatabaseReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "a2a-${java.util.UUID.randomUUID()}.db"
        val directory = java.io.File(context.cacheDir, name).apply { mkdirs() }
        val store = com.zero.v2.core.data.knowledge.KnowledgeCandidateStore(directory)
        var db = Room.databaseBuilder(context, ZeroDatabase::class.java, name).build()
        try {
            val a = KbDocEntity("a", "a.md", "Old A", 1, 100, 1, 1)
            val b = KbDocEntity("b", "b.md", "Old B", 1, 100, 1, 1)
            db.kbDao().replaceDocument(a, listOf(chunk("old-a", "a", "original-a")))
            db.kbDao().replaceDocument(b, listOf(chunk("old-b", "b", "original-b")))
            val baseline = com.zero.v2.core.data.knowledge.KnowledgeCandidateStore.fingerprint(db.kbDao().loadAllDocs().toString())
            store.begin(com.zero.v2.core.data.knowledge.KnowledgeCandidateStore.Job("source", baseline, null, "READY"))
            store.put(0, com.zero.v2.core.data.knowledge.KnowledgeCandidateStore.Candidate("hash-a", a.copy(title = "New A"),
                listOf(chunk("new-a", "a", "replacement-a"))))
            store.put(1, com.zero.v2.core.data.knowledge.KnowledgeCandidateStore.Candidate("hash-b", b.copy(title = "New B"),
                listOf(chunk("new-b", "b", "replacement-b"))))
            var reads = 0
            val failed = runCatching {
                db.kbDao().publishCandidates(2, emptyList(), baseline) { index ->
                    // Validation reads both, then a is replaced; the second publication read fails.
                    if (++reads == 4) java.io.File(directory, "1.bin").appendText("corrupt")
                    store.candidate(index)!!
                }
            }
            assertTrue(failed.isFailure)
            assertEquals(listOf(a, b), db.kbDao().loadAllDocs())
            assertEquals(2, db.kbDao().searchByText("original", 10).size)
            store.put(1, com.zero.v2.core.data.knowledge.KnowledgeCandidateStore.Candidate("hash-b", b.copy(title = "New B"),
                listOf(chunk("new-b", "b", "replacement-b"))))
            assertTrue(db.kbDao().publishCandidates(2, emptyList(), baseline) { store.candidate(it)!! })
            db.kbDao().updateEmbedding("new-a", byteArrayOf(1), "current", 2)
            db.close()
            db = Room.databaseBuilder(context, ZeroDatabase::class.java, name).build()
            val reopened = com.zero.v2.core.data.knowledge.KnowledgeCandidateStore(directory)
            assertEquals("READY", reopened.job()!!.state)
            assertFalse(db.kbDao().publishCandidates(2, emptyList(), baseline) { reopened.candidate(it)!! })
            assertEquals("current", db.kbDao().searchByText("replacement-a", 10).single().embedModel)
        } finally {
            db.close()
            context.deleteDatabase(name)
            store.clear()
            directory.delete()
        }
    }

    @Test fun replacementFailureRollsBackMetadataAndAllChunks() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        try {
            val dao = db.kbDao()
            val original = KbDocEntity("doc", "a.md", "Old title", 1, 100, 1, 1)
            val oldChunk = chunk("old", "doc", "original")
            dao.replaceDocument(original, listOf(oldChunk))
            // Real SQLite primary-key failure after old chunks have been removed inside the transaction.
            val failed = runCatching {
                dao.replaceDocument(original.copy(title = "Broken", chunkCount = 2), listOf(
                    chunk("duplicate", "doc", "new-a"), chunk("duplicate", "doc", "new-b")))
            }
            assertTrue(failed.isFailure)
            assertEquals(original, dao.getByPath("a.md"))
            assertEquals(listOf(oldChunk), dao.searchByText("original", 10))
            assertTrue(dao.searchByText("new-", 10).isEmpty())

            dao.replaceDocument(original.copy(title = "New title"), listOf(chunk("new", "doc", "replacement")))
            assertEquals("doc", dao.getByPath("a.md")!!.id)
            assertEquals("New title", dao.getByPath("a.md")!!.title)
            assertEquals(1, dao.searchByText("replacement", 10).size)
            assertTrue(dao.searchByText("original", 10).isEmpty())
        } finally { db.close() }
    }

    @Test fun batchFailureRollsBackEarlierDocumentsAndRemovals() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        try {
            val dao = db.kbDao()
            val a = KbDocEntity("a", "a.md", "A", 1, 100, 1, 1)
            val b = KbDocEntity("b", "b.md", "B", 1, 100, 1, 1)
            val removed = KbDocEntity("removed", "removed.md", "Keep on failure", 1, 100, 1, 1)
            dao.publishDocuments(listOf(a, b, removed), listOf(
                listOf(chunk("old-a", "a", "original-a")),
                listOf(chunk("old-b", "b", "original-b")),
                listOf(chunk("old-r", "removed", "original-removed"))), emptyList())
            val failure = runCatching {
                dao.publishDocuments(listOf(a.copy(title = "Changed"), b.copy(chunkCount = 2)), listOf(
                    listOf(chunk("new-a", "a", "replacement-a")),
                    listOf(chunk("duplicate", "b", "new-b"), chunk("duplicate", "b", "new-b"))),
                    listOf("removed.md"))
            }
            assertTrue(failure.isFailure)
            assertEquals(a, dao.getByPath("a.md"))
            assertEquals(b, dao.getByPath("b.md"))
            assertEquals(removed, dao.getByPath("removed.md"))
            assertEquals(3, dao.searchByText("original", 10).size)
            dao.publishDocuments(listOf(a.copy(title = "Changed")),
                listOf(listOf(chunk("new-a", "a", "replacement-a"))), listOf("removed.md"))
            assertNull(dao.getByPath("removed.md"))
            assertEquals(1, dao.searchByText("replacement", 10).size)
        } finally { db.close() }
    }

    @Test fun backlogIncludesLegacyVectorsAndExcludesCurrentSpace() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        try {
            val dao = db.kbDao()
            val doc = KbDocEntity("a", "a.md", "A", 1, 100, 3, 1)
            dao.replaceDocument(doc, listOf(
                chunk("missing", "a", "missing"),
                chunk("legacy", "a", "legacy").copy(embedding = byteArrayOf(1), embedModel = "old"),
                chunk("current", "a", "current").copy(embedding = byteArrayOf(1), embedModel = "space")))
            assertEquals(2, dao.countEmbeddingBacklog("space"))
            assertEquals(setOf("missing", "legacy"), dao.getForReembedding("space", 10).map { it.id }.toSet())
            val memory = db.memoryDao()
            memory.insert(com.zero.v2.core.data.entity.MemoryEntity("missing", "missing"))
            memory.insert(com.zero.v2.core.data.entity.MemoryEntity("legacy", "legacy", embedding = byteArrayOf(1), embedModel = "old"))
            memory.insert(com.zero.v2.core.data.entity.MemoryEntity("current", "current", embedding = byteArrayOf(1), embedModel = "space"))
            assertEquals(2, memory.countEmbeddingBacklog("space"))
        } finally { db.close() }
    }

    private fun chunk(id: String, docId: String, text: String) = KbChunkEntity(
        id = id, docId = docId, seq = 0, text = text, charLen = text.length, updatedAt = 1)
}
