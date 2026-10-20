package com.zero.v2.app.knowledge

import com.zero.v2.app.data.WorkDirRepository
import com.zero.v2.core.bus.SystemBusImpl
import com.zero.v2.core.data.dao.AgentTraceEventDao
import com.zero.v2.core.data.dao.KbDao
import com.zero.v2.core.data.entity.KbChunkEntity
import com.zero.v2.core.data.entity.KbDocEntity
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.services.embedding.EmbeddingProvider
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.junit.Assert.*
import org.junit.Test

class KnowledgeIndexTest {
    @Test fun editingExistingDocumentKeepsSearchableChunksAndRefreshesTitle() = runBlocking {
        val source = Source()
        val dao = IndexDao()
        val module = module(source, dao)
        try {
            module.doIndex("test-insert")
            val originalId = dao.docs.single().id
            source.text = "# New title\n\nReplacement searchable content."
            source.mtime++
            module.doIndex("test-edit")
            assertEquals(originalId, dao.docs.single().id)
            assertEquals(1, dao.searchByText("Replacement", 10).size)
            assertEquals("New title", dao.docs.single().title)
            assertTrue(dao.searchByText("Original", 10).isEmpty())
            assertEquals(dao.chunks.size, dao.docs.single().chunkCount)
        } finally { module.onDestroy() }
    }

    @Test fun failedReadPreservesPreviouslySearchableDocument() = runBlocking {
        val source = Source()
        val dao = IndexDao()
        val module = module(source, dao)
        try {
            module.doIndex("test-insert")
            val before = dao.docs.single()
            source.text = null
            source.mtime++
            assertTrue(runCatching { module.rebuildIndex(null) }.isFailure)
            assertEquals(before, dao.docs.single())
            assertEquals(1, dao.searchByText("Original", 10).size)
        } finally { module.onDestroy() }
    }

    @Test fun repairsLegacyMissingChunksWithoutSourceTimestampChange() = runBlocking {
        val source = Source()
        val dao = IndexDao()
        val module = module(source, dao)
        try {
            module.doIndex("insert")
            dao.chunks.clear()
            module.doIndex("repair")
            assertEquals(1, dao.searchByText("Original", 10).size)
        } finally { module.onDestroy() }
    }

    @Test fun incompleteListingPreservesIndexButConfirmedDeletionRemovesIt() = runBlocking {
        val source = Source()
        val dao = IndexDao()
        val module = module(source, dao)
        try {
            module.doIndex("insert")
            source.present = false
            source.complete = false
            module.doIndex("unreadable-directory")
            assertEquals(1, dao.searchByText("Original", 10).size)
            source.complete = true
            module.doIndex("confirmed-delete")
            assertTrue(dao.docs.isEmpty())
            assertTrue(dao.chunks.isEmpty())
        } finally { module.onDestroy() }
    }

    @Test fun fullRebuildFailureDoesNotPublishEarlierFilesOrDeletions() = runBlocking {
        val source = Source()
        source.extra["knowledge/later.md"] = "Later original"
        source.extra["knowledge/deleted.md"] = "Deleted original"
        val dao = IndexDao()
        val module = module(source, dao)
        try {
            module.doIndex("insert")
            val before = dao.docs.toList()
            val beforeChunks = dao.chunks.toList()
            source.text = "Replacement first document"
            source.extra["knowledge/later.md"] = null
            source.extra.remove("knowledge/deleted.md")
            runCatching { module.rebuildIndex(null) }
            assertEquals(before, dao.docs)
            assertEquals(beforeChunks, dao.chunks)
        } finally { module.onDestroy() }
    }

    @Test fun incompleteFullRebuildDoesNotPublishAnyCandidate() = runBlocking {
        val source = Source()
        val dao = IndexDao()
        val module = module(source, dao)
        try {
            module.doIndex("insert")
            val before = dao.docs.toList()
            source.complete = false
            source.text = "Replacement candidate"
            runCatching { module.rebuildIndex(null) }
            assertEquals(before, dao.docs)
            assertTrue(dao.searchByText("Replacement", 10).isEmpty())
        } finally { module.onDestroy() }
    }

    @Test fun fullRebuildPublishesReplacementsAndConfirmedDeletionsTogether() = runBlocking {
        val source = Source().apply { extra["knowledge/deleted.md"] = "Deleted original" }
        val dao = IndexDao()
        val module = module(source, dao)
        try {
            module.doIndex("insert")
            val id = dao.getByPath("test.md")!!.id
            source.extra.clear()
            source.text = "# New title\nReplacement content"
            module.rebuildIndex(null)
            assertEquals(id, dao.docs.single().id)
            assertEquals("New title", dao.docs.single().title)
            assertEquals(1, dao.searchByText("Replacement", 10).size)
            assertTrue(dao.searchByText("Deleted", 10).isEmpty())
        } finally { module.onDestroy() }
    }

    @Test fun candidateBudgetAndConcurrentSourceChangePreserveActiveIndex() = runBlocking {
        val source = Source()
        val dao = IndexDao()
        val module = module(source, dao)
        try {
            module.doIndex("insert")
            val before = dao.docs.toList()
            source.text = "x".repeat(4_000_001)
            assertTrue(runCatching { module.rebuildIndex(null) }.isFailure)
            assertEquals(before, dao.docs)
            source.text = "Changed source"
            source.changeDuringRead = true
            assertTrue(runCatching { module.rebuildIndex(null) }.isFailure)
            assertEquals(before, dao.docs)
        } finally { module.onDestroy() }
    }

    @Test fun backfillDelegatesToKnowledgeCandidateWorkerWithoutWritingSourceVectors() = runBlocking {
        val source = Source()
        val dao = IndexDao()
        val provider = object : EmbeddingProvider {
            override var modelId = "old-model"
            override suspend fun isAvailable() = true
            override suspend fun embed(text: String): FloatArray? = null
            override suspend fun embedBatch(texts: List<String>): List<FloatArray?> {
                modelId = "new-model"
                return texts.map { floatArrayOf(1f, 0f) }
            }
        }
        val module = module(source, dao, provider)
        try {
            module.doIndex("insert")
            // Stop the asynchronous no-op worker before running the exact production backfill path.
            module.onDestroy()
            module.config.hybridEmbedding = true
            module.backfillLoop()
            assertTrue(dao.chunks.all { it.embedding == null })
            assertEquals(1, dao.buildRequests)
        } finally { module.onDestroy() }
    }

    @Test fun legacyVectorsRemainUnchangedWhileCandidateAwaitsValidation() = runBlocking {
        val source = Source()
        val dao = IndexDao()
        val provider = object : EmbeddingProvider {
            override val modelId = "test"
            override suspend fun isAvailable() = true
            override suspend fun embed(text: String) = floatArrayOf(1f, 0f)
            override suspend fun embedBatch(texts: List<String>) = texts.map { floatArrayOf(1f, 0f) }
        }
        val module = module(source, dao, provider)
        try {
            module.doIndex("insert")
            module.onDestroy()
            val before = dao.chunks.single()
            dao.updateEmbedding(before.id, byteArrayOf(0), "test", 1)
            module.config.hybridEmbedding = true
            module.backfillLoop()
            assertEquals(before.text, dao.chunks.single().text)
            assertEquals("test", dao.chunks.single().embedModel)
            assertEquals(1, dao.buildRequests)
            assertEquals(before.id, dao.chunks.single().id)
        } finally { module.onDestroy() }
    }

    internal fun module(source: Source, dao: IndexDao, overrideProvider: EmbeddingProvider? = null,
        reranker: com.zero.v2.services.retrieval.BoundedSemanticReranker? = null): KnowledgeModule {
        val traces = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(AgentTraceEventDao::class.java)) {
            _, method, _ -> when (method.name) {
                "count" -> 0L
                "deleteOlderThan" -> 0
                "insert" -> Unit
                else -> emptyList<Any>()
            }
        } as AgentTraceEventDao
        val provider = object : EmbeddingProvider {
            override val modelId = "test"
            override suspend fun isAvailable() = false
            override suspend fun embed(text: String): FloatArray? = null
            override suspend fun embedBatch(texts: List<String>) = texts.map { null }
        }
        return KnowledgeModule(source, dao, SystemBusImpl(), overrideProvider ?: provider, AgentTraceManager(traces),
            com.zero.v2.core.data.knowledge.KnowledgeCandidateStore(java.nio.file.Files.createTempDirectory("kb-test").toFile()),
            object : com.zero.v2.services.embedding.VectorIndexRuntime {
                override suspend fun build(domain: com.zero.v2.services.embedding.VectorDomain): com.zero.v2.services.embedding.VectorIndexWorker.Outcome {
                    // Match the real suspend worker: a cancelled background request has no effects.
                    currentCoroutineContext().ensureActive()
                    assertEquals(com.zero.v2.services.embedding.VectorDomain.KNOWLEDGE, domain)
                    dao.buildRequests++
                    return com.zero.v2.services.embedding.VectorIndexWorker.Outcome.WAITING_VALIDATION
                }
                override suspend fun status(domain: com.zero.v2.services.embedding.VectorDomain) =
                    com.zero.v2.services.embedding.VectorIndexWorker.Status(null, null, 0, 0)
                override suspend fun search(domain: com.zero.v2.services.embedding.VectorDomain, query: String,
                    limit: Int, knowledgeDocuments: List<String>?): List<com.zero.v2.core.data.entity.VectorEntryEntity>? {
                    dao.searchDocuments = knowledgeDocuments
                    return null
                }
                override suspend fun similar(domain: com.zero.v2.services.embedding.VectorDomain, sourceId: String, threshold: Float, limit: Int) = null
            }, reranker).apply {
            config.hybridEmbedding = false
        }
    }

    internal class Source : KnowledgeSource {
        var root = "test-root"
        override fun identity() = root
        var onRead: (String) -> Unit = {}
        var text: String? = "# Original title\n\nOriginal searchable content."
        var mtime = 1L
        var complete = true
        var changeDuringRead = false
        var present = true
        val extra = linkedMapOf<String, String?>()
        override fun hasDirectory() = true
        override suspend fun createFolder(path: String): String? = null
        override suspend fun listTree() = WorkDirRepository.TreeListing(
            (if (present) listOf(WorkDirRepository.TreeNode("knowledge/test.md", "test.md", false, 100, mtime))
            else emptyList()) + extra.keys.map { WorkDirRepository.TreeNode(it, it.substringAfterLast('/'), false, 100, mtime) }, complete)
        override suspend fun readText(path: String): String? {
            onRead(path)
            if (changeDuringRead) mtime++
            return if (path == "knowledge/test.md") text else extra[path]
        }
    }

    internal class IndexDao : KbDao {
        var searchDocuments: List<String>? = null
        var buildRequests = 0
        override suspend fun getChunk(id: String) = chunks.find { it.id == id }
        override suspend fun chunkIds(docId: String) = chunks.filter { it.docId == docId }.sortedBy { it.seq }.map { it.id }
        val docs = mutableListOf<KbDocEntity>()
        val chunks = mutableListOf<KbChunkEntity>()
        override suspend fun loadAllDocs() = docs.toList()
        override suspend fun getByPath(path: String) = docs.find { it.relativePath == path }
        override suspend fun insertDoc(doc: KbDocEntity) { docs.add(doc) }
        override suspend fun updateDoc(doc: KbDocEntity) { docs[docs.indexOfFirst { it.id == doc.id }] = doc }
        override suspend fun countChunksForDoc(docId: String) = chunks.count { it.docId == docId }
        override suspend fun updateDocIndexed(path: String, mtime: Long, size: Long, chunkCount: Int, indexedAt: Long) {
            val index = docs.indexOfFirst { it.relativePath == path }
            docs[index] = docs[index].copy(mtime = mtime, size = size, chunkCount = chunkCount, indexedAt = indexedAt)
        }
        override suspend fun deleteDoc(path: String) {
            getByPath(path)?.let { deleteChunksByDoc(it.id) }
            docs.removeAll { it.relativePath == path }
        }
        override suspend fun deleteChunksByDoc(docId: String) { chunks.removeAll { it.docId == docId } }
        override suspend fun insertChunks(chunks: List<KbChunkEntity>) { this.chunks.addAll(chunks) }
        override suspend fun searchByText(term: String, limit: Int) = chunks.filter { term in it.text }.take(limit)
        override suspend fun hasManyLexicalRows() = chunks.size > 2000
        override suspend fun textGroupRows(query: androidx.sqlite.db.SupportSQLiteQuery): List<com.zero.v2.core.data.entity.KnowledgeLexicalGroupRow> =
            error("Raw SQLite requires the real Room acceptance fixture")
        override suspend fun searchTextGroups(terms: List<String>, limit: Int) = terms.map { searchByText(it, limit) }
        override suspend fun searchScopedText(term: String, documents: List<String>, limit: Int) =
            chunks.filter { it.docId in documents && term in it.text }.sortedBy { it.id }.take(limit)
        override suspend fun scopedEmbeddingPage(model: String, after: String, documents: List<String>, limit: Int) =
            chunks.filter { it.docId in documents && it.embedModel == model && it.embedding != null && it.id > after }
                .sortedBy { it.id }.take(limit)
        override suspend fun getWithEmbeddingBounded(model: String, limit: Int) = emptyList<KbChunkEntity>()
        override suspend fun embeddingPage(model: String, after: String, limit: Int) = emptyList<KbChunkEntity>()
        override suspend fun getForReembedding(model: String, limit: Int) = chunks.filter { it.embedding == null || it.embedModel != model }.take(limit)
        override suspend fun updateEmbedding(id: String, blob: ByteArray, model: String, updatedAt: Long) {
            val index = chunks.indexOfFirst { it.id == id }
            if (index >= 0) chunks[index] = chunks[index].copy(embedding = blob, embedModel = model, updatedAt = updatedAt)
        }
        override suspend fun countAllChunks() = chunks.size
        override suspend fun countEmbeddingBacklog(model: String) = chunks.count { it.embedding == null || it.embedModel != model }
        override suspend fun countModelOf(model: String) = chunks.count { it.embedModel == model }
    }
}

