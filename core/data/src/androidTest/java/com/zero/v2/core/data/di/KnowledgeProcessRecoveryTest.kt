package com.zero.v2.core.data.di

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.KbChunkEntity
import com.zero.v2.core.data.entity.KbDocEntity
import com.zero.v2.core.data.knowledge.KnowledgeCandidateStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Run prepare and recover via separate instrumentation invocations, with force-stop between them. */
@RunWith(AndroidJUnit4::class)
@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
class KnowledgeProcessRecoveryTest {
    @Test fun prepare() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "a2a-process-candidates")
        val store = KnowledgeCandidateStore(directory)
        context.deleteDatabase("a2a-process.db")
        val db = Room.databaseBuilder(context, ZeroDatabase::class.java, "a2a-process.db").build()
        try {
            val doc = KbDocEntity("doc", "test.md", "old", 1, 1, 1, 1)
            db.kbDao().replaceDocument(doc, listOf(chunk("old", "original")))
            store.begin(KnowledgeCandidateStore.Job("source", KnowledgeCandidateStore.fingerprint(db.kbDao().loadAllDocs().toString()), null, "BUILDING"))
            store.put(0, KnowledgeCandidateStore.Candidate("hash", doc.copy(title = "new"), listOf(chunk("new", "replacement"))))
            assertEquals("old", db.kbDao().getByPath("test.md")!!.title)
        } finally { db.close() }
    }

    @Test fun recover() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "a2a-process-candidates")
        val store = KnowledgeCandidateStore(directory)
        // No fixture fallback: a missing journal must fail the cross-process recovery assertion.
        assertNotNull(store.job())
        val db = Room.databaseBuilder(context, ZeroDatabase::class.java, "a2a-process.db").build()
        try {
            val job = store.job()!!
            assertEquals("BUILDING", job.state)
            assertEquals("old", db.kbDao().getByPath("test.md")!!.title)
            assertTrue(db.kbDao().publishCandidates(1, emptyList(), job.baseline) { store.candidate(it)!! })
            assertFalse(db.kbDao().publishCandidates(1, emptyList(), job.baseline) { store.candidate(it)!! })
            assertEquals("new", db.kbDao().getByPath("test.md")!!.title)
        } finally {
            db.close()
            context.deleteDatabase("a2a-process.db")
            store.clear()
            directory.delete()
        }
    }

    private fun chunk(id: String, text: String) = KbChunkEntity(id, "doc", 0, text = text, charLen = text.length, updatedAt = 1)
}
