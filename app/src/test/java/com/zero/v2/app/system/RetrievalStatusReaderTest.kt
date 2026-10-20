package com.zero.v2.app.system

import com.zero.v2.core.data.dao.MemoryExtractionJobDao
import com.zero.v2.core.data.entity.VectorEntryEntity
import com.zero.v2.services.embedding.VectorDomain
import com.zero.v2.services.embedding.VectorIndexRuntime
import com.zero.v2.services.embedding.VectorIndexWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class RetrievalStatusReaderTest {
    private class Indexes : VectorIndexRuntime {
        var failing: VectorDomain? = null
        var cancelled = false
        val reads = mutableListOf<VectorDomain>()
        override suspend fun status(domain: VectorDomain): VectorIndexWorker.Status {
            reads += domain
            if (cancelled) throw CancellationException("cancel")
            check(domain != failing) { "secret source text must not escape" }
            return VectorIndexWorker.Status("${domain.key}-v2", "BUILDING", 2, 10, 8, false)
        }
        override suspend fun build(domain: VectorDomain): VectorIndexWorker.Outcome = error("Must not build")
        override suspend fun search(domain: VectorDomain, query: String, limit: Int,
            knowledgeDocuments: List<String>?): List<VectorEntryEntity>? = error("Must not query provider")
        override suspend fun similar(domain: VectorDomain, sourceId: String, threshold: Float,
            limit: Int): List<VectorEntryEntity>? = error("Must not query provider")
    }

    private fun jobs(count: () -> Int) = Proxy.newProxyInstance(
        MemoryExtractionJobDao::class.java.classLoader, arrayOf(MemoryExtractionJobDao::class.java),
    ) { _, method, _ ->
        check(method.name == "countBacklog") { "Unexpected DAO access ${method.name}" }
        count()
    } as MemoryExtractionJobDao

    @Test fun `reads both owners and extraction jobs without triggering work`() = runBlocking {
        val indexes = Indexes()
        val reader = RetrievalStatusReader(indexes, jobs { 3 })
        assertNull(reader.observation.backlog)
        reader.refresh()
        assertEquals(VectorDomain.entries, indexes.reads)
        assertEquals(19, reader.observation.backlog)
        assertEquals("memory:memory-v2;knowledge:knowledge-v2", reader.observation.indexVersion)
        assertTrue(reader.observation.domains.all { it.activeSpaceMatches == false && it.observedAt > 0 })
    }

    @Test fun `one failed owner keeps its old timestamp without forging zero or poisoning other owner`() = runBlocking {
        val indexes = Indexes()
        val reader = RetrievalStatusReader(indexes, jobs { 3 })
        reader.refresh()
        val old = reader.observation.domains.first()
        indexes.failing = VectorDomain.MEMORY
        reader.refresh()
        assertEquals(old.observedAt, reader.observation.domains.first().observedAt)
        assertEquals("owner_read_failed", reader.observation.domains.first().readError)
        assertNull(reader.observation.domains.last().readError)
        assertNull(reader.observation.backlog)
        assertTrue(!reader.observation.toString().contains("secret"))
    }

    @Test fun `initial failure has no invented counts and cancellation propagates`() = runBlocking {
        val indexes = Indexes().apply { failing = VectorDomain.MEMORY }
        val reader = RetrievalStatusReader(indexes, jobs { error("database failure") })
        reader.refresh()
        assertNull(reader.observation.domains.first().total)
        assertNull(reader.observation.extractionJobs)
        assertNull(reader.observation.backlog)
        val before = reader.observation
        indexes.cancelled = true
        try { reader.refresh(); error("Cancellation swallowed") } catch (_: CancellationException) { }
        assertEquals(before, reader.observation)
    }
}
