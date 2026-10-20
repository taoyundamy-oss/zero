package com.zero.v2.app.knowledge

import com.zero.v2.core.data.entity.KbChunkEntity
import com.zero.v2.core.data.entity.KbDocEntity
import com.zero.v2.core.data.knowledge.KnowledgeCandidateStore
import com.zero.v2.core.data.knowledge.KnowledgeCandidateStore.Candidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class KnowledgeRebuildTest {
    @get:Rule val temporary = TemporaryFolder()
    private val source = KnowledgeIndexTest.Source()
    private val dao = KnowledgeIndexTest.IndexDao()
    private var prepared = 0
    private var published = 0
    private class ProcessExit : Error()

    private suspend fun rebuild(directory: File, resume: Boolean = false, afterCommit: () -> Unit = {}) {
        KnowledgeRebuild(source, dao, KnowledgeCandidateStore(directory)).run(null, resume, 1000, 500,
            prepare = { rel, text, mtime, size, old ->
                prepared++
                val id = old?.id ?: UUID.randomUUID().toString()
                Candidate("", KbDocEntity(id, rel, text.take(20), mtime, size, 1, 123),
                    listOf(KbChunkEntity(UUID.randomUUID().toString(), id, 0, text = text, charLen = text.length, updatedAt = 123)))
            },
            publish = { count, removed, baseline, read ->
                if (dao.publishCandidates(count, removed, baseline, read)) published++
                afterCommit()
            })
    }

    @Test fun processExitResumesCompletedDocumentsFromNewStoreInstance() = runBlocking {
        val directory = temporary.newFolder()
        source.extra["knowledge/z.md"] = "Second"
        source.onRead = { if (it.endsWith("z.md")) throw ProcessExit() }
        assertTrue(runCatching { rebuild(directory) }.exceptionOrNull() is ProcessExit)
        assertEquals("BUILDING", KnowledgeCandidateStore(directory).job()!!.state)
        assertTrue(dao.docs.isEmpty())
        assertEquals(1, prepared)
        source.onRead = {}
        rebuild(directory, resume = true)
        assertEquals(2, prepared)
        assertEquals(2, dao.docs.size)
        assertEquals("COMMITTED", KnowledgeCandidateStore(directory).job()!!.state)
    }

    @Test fun exitAfterCommitDoesNotRepublishOrEraseBackfilledEmbedding() = runBlocking {
        val directory = temporary.newFolder()
        assertTrue(runCatching { rebuild(directory) { throw ProcessExit() } }.exceptionOrNull() is ProcessExit)
        val chunk = dao.chunks.single()
        dao.updateEmbedding(chunk.id, byteArrayOf(1, 2), "current", 456)
        rebuild(directory, resume = true)
        assertEquals(1, published)
        assertEquals(chunk.id, dao.chunks.single().id)
        assertEquals("current", dao.chunks.single().embedModel)
    }

    @Test fun corruptedCandidateAndCancellationKeepOldIndex() = runBlocking {
        val directory = temporary.newFolder()
        rebuild(directory)
        val old = dao.docs.toList()
        source.extra["knowledge/z.md"] = "Second"
        source.onRead = { if (it.endsWith("z.md")) throw ProcessExit() }
        runCatching { rebuild(directory) }
        File(directory, "0.bin").appendText("broken")
        source.onRead = {}
        assertTrue(runCatching { rebuild(directory, resume = true) }.isFailure)
        assertEquals(old, dao.docs)
        assertEquals("FAILED", KnowledgeCandidateStore(directory).job()!!.state)
        source.onRead = { throw CancellationException("cancel") }
        assertTrue(runCatching { rebuild(directory) }.exceptionOrNull() is CancellationException)
        assertEquals("CANCELLED", KnowledgeCandidateStore(directory).job()!!.state)
        assertFalse(KnowledgeRebuild(source, dao, KnowledgeCandidateStore(directory)).pending())
        assertEquals(old, dao.docs)
    }

    @Test fun changedContentWithUnchangedMetadataCannotResumeStaleCandidate() = runBlocking {
        val directory = temporary.newFolder()
        source.extra["knowledge/z.md"] = "Second"
        source.onRead = { if (it.endsWith("z.md")) throw ProcessExit() }
        runCatching { rebuild(directory) }
        source.onRead = {}
        source.text = "Changed without mtime change"
        assertTrue(runCatching { rebuild(directory, resume = true) }.isFailure)
        assertTrue(dao.docs.isEmpty())
    }

    @Test fun changedActiveIndexCannotBeOverwrittenByRecoveredJob() = runBlocking {
        val directory = temporary.newFolder()
        source.extra["knowledge/z.md"] = "Second"
        source.onRead = { if (it.endsWith("z.md")) throw ProcessExit() }
        runCatching { rebuild(directory) }
        source.onRead = {}
        dao.insertDoc(KbDocEntity("external", "external.md", mtime = 1, size = 0, indexedAt = 1))
        assertTrue(runCatching { rebuild(directory, resume = true) }.isFailure)
        assertEquals("external", dao.docs.single().id)
    }

    @Test fun aggregateLargerThanOldMemoryBudgetBuildsOneDocumentAtATime() = runBlocking {
        val directory = temporary.newFolder()
        source.text = "a".repeat(2_100_000)
        source.extra["knowledge/z.md"] = "b".repeat(2_100_000)
        rebuild(directory)
        assertEquals(2, prepared)
        assertEquals(2, dao.docs.size)
    }

    @Test fun sourceDirectorySwitchInvalidatesPendingJob() = runBlocking {
        val directory = temporary.newFolder()
        source.extra["knowledge/z.md"] = "Second"
        source.onRead = { if (it.endsWith("z.md")) throw ProcessExit() }
        runCatching { rebuild(directory) }
        source.onRead = {}
        source.root = "another-root"
        assertTrue(runCatching { rebuild(directory, resume = true) }.isFailure)
        assertEquals("FAILED", KnowledgeCandidateStore(directory).job()!!.state)
        assertTrue(dao.docs.isEmpty())
    }

    @Test fun corruptManifestFailsOnceAndDoesNotBlockFutureIncrementalWork() {
        val directory = temporary.newFolder()
        File(directory, "job").writeText("broken")
        val rebuild = KnowledgeRebuild(source, dao, KnowledgeCandidateStore(directory))
        assertTrue(runCatching { rebuild.pending() }.isFailure)
        assertFalse(rebuild.pending())
        assertEquals("FAILED", KnowledgeCandidateStore(directory).job()!!.state)
    }
}
