package com.zero.v2.app.acceptance

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.bus.ChatBusImpl
import com.zero.v2.core.common.*
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.MemoryEntity
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.framework.memory.*
import com.zero.v2.services.embedding.EmbeddingProvider
import com.zero.v2.services.embedding.VectorIndexWorker
import com.zero.v2.services.llm.*
import com.zero.v2.services.retrieval.BoundedSemanticReranker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GraphLifecycleAcceptanceTest {
    @Test fun realProviderPreservesNegativeSource() = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("allowRealProvider") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = dagger.hilt.android.EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        entry.chatProfiles().refreshProfiles()
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val provider = object : EmbeddingProvider {
            override val modelId = "offline"
            override suspend fun isAvailable() = false
            override suspend fun embed(text: String): FloatArray? = null
            override suspend fun embedBatch(texts: List<String>) = texts.map { null }
        }
        val selector = entry.modelSelector()
        val trace = AgentTraceManager(db.agentTraceEventDao())
        val memory = MemoryModule(db.memoryDao(), db.userGraphDao(), trace, provider,
            MemoryScoringEngine(), db.memoryExtractionJobDao(),
            VectorIndexWorker(db.vectorIndexDao(), provider), BoundedSemanticReranker(selector), com.zero.v2.framework.memory.MemoryClassificationTools(db.chronicleSourceDao(), db.memoryDerivationDao(), com.zero.v2.core.data.store.MemoryDerivationStore(db)), com.zero.v2.core.data.store.MemoryDerivationStore(db))
        try {
            val source = MemoryEntity("user-fact:synthetic-negative", "我不喜欢冷饮，喜欢示例食物A", type = MemoryType.FACT,
                sourceConversationId = "synthetic", createdAt = 1)
            db.memoryDao().insert(source)
            memory.onAwake(); memory.onStart()
            GraphExtractionJob(memory, db.memoryDao(), db.userGraphDao(), selector, trace, db.memoryExtractionJobDao()).execute()
            assertTrue(db.userGraphDao().pendingGraphJobs(Long.MAX_VALUE).isEmpty())
            val attributes = db.userGraphDao().getAllEntities().flatMap { db.userGraphDao().getCurrentAttributes(it.id) }
            assertTrue("real provider must extract the preference", attributes.isNotEmpty())
            assertTrue(attributes.all { it.value == source.content && it.evidence?.startsWith("memory:v1:") == true })
        } finally { memory.onDestroy(); db.close() }
    }
    @Test fun settingsDeleteFailureIsVisibleAndPreservesData() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val owner = androidx.lifecycle.ViewModelStore()
        try {
            db.memoryDao().insert(MemoryEntity("delete-fixture", "独立测试资料"))
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_fixture_delete BEFORE DELETE ON memories BEGIN SELECT RAISE(ABORT, 'acceptance'); END")
            val viewModel = withContext(Dispatchers.Main) {
                com.zero.v2.app.ui.settings.MemoryManageViewModel(db.memoryDao(),
                    com.zero.v2.core.data.repository.MemoryDeletionRepository(db.memoryExtractionJobDao()),
                    AgentTraceManager(db.agentTraceEventDao())).also { owner.put("fixture", it); it.delete("delete-fixture") }
            }
            val state = withTimeout(5000) { viewModel.state.first { it.deleteError != null } }
            assertEquals("删除未完成，数据已保留，请重试", state.deleteError)
            assertEquals("独立测试资料", db.memoryDao().getById("delete-fixture")!!.content)
        } finally { withContext(Dispatchers.Main) { owner.clear() }; db.close() }
    }
    @Test fun knowledgeDisablePreservesDocumentsAndChunks() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val root = java.io.File(context.cacheDir, "knowledge-lifecycle-${java.util.UUID.randomUUID()}")
        val provider = object : EmbeddingProvider {
            override val modelId = "offline"
            override suspend fun isAvailable() = false
            override suspend fun embed(text: String): FloatArray? = null
            override suspend fun embedBatch(texts: List<String>) = texts.map { null }
        }
        val source = object : com.zero.v2.app.knowledge.KnowledgeSource {
            override fun identity() = "isolated-lifecycle"
            override fun hasDirectory() = false
            override suspend fun createFolder(path: String): String? = error("read only")
            override suspend fun listTree() = com.zero.v2.app.data.WorkDirRepository.TreeListing(emptyList(), false)
            override suspend fun readText(path: String): String? = null
        }
        val knowledge = com.zero.v2.app.knowledge.KnowledgeModule(source, db.kbDao(),
            com.zero.v2.core.bus.SystemBusImpl(), provider, AgentTraceManager(db.agentTraceEventDao()),
            com.zero.v2.core.data.knowledge.KnowledgeCandidateStore(root), VectorIndexWorker(db.vectorIndexDao(), provider),
            BoundedSemanticReranker(ModelSelector()))
        knowledge.config.autoIndexOnStart = false
        knowledge.config.hybridEmbedding = false
        try {
            db.kbDao().insertDoc(com.zero.v2.core.data.entity.KbDocEntity("doc", "doc.md", "资料", 0, 0, 1, 0))
            db.kbDao().insertChunks(listOf(com.zero.v2.core.data.entity.KbChunkEntity("chunk", "doc", 0,
                headingPath = "", text = "资料原文", charLen = 4, updatedAt = 0)))
            knowledge.onAwake(); knowledge.onStart(); knowledge.markDisabled()
            assertEquals(1, db.kbDao().loadAllDocs().size)
            assertEquals("资料原文", db.kbDao().getChunk("chunk")!!.text)
            knowledge.onStart()
            assertEquals("资料原文", db.kbDao().getChunk("chunk")!!.text)
        } finally { knowledge.onDestroy(); db.close(); root.deleteRecursively() }
    }
    private class Model : ILanguageModel {
        override val modelId = "graph-fixture"
        override val displayName = modelId
        override val contextWindow = 8192
        override val capabilities = setOf(ModelCapability.CHAT)
        override val keyConfigured = true
        override val priority = 1
        override val enabled = true
        var calls = 0
        var answer: suspend () -> String = { """{"entities":[],"attributes":[{"entityId":"user","key":"preference","memoryIndex":1}],"relationships":[]}""" }
        override suspend fun chatSync(userMessage: String, systemPrompt: String?): String { calls++; return answer() }
        override fun chatStream(messages: List<ChatMessage>, systemPrompt: String?, functions: List<FunctionDefinition>,
            prefixVersion: String?) = emptyFlow<StreamChunk>()
    }

    @Test fun actualJobHonorsDisableCancellationRetryAndCompletion() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val provider = object : EmbeddingProvider {
            override val modelId = "offline"
            override suspend fun isAvailable() = false
            override suspend fun embed(text: String): FloatArray? = null
            override suspend fun embedBatch(texts: List<String>) = texts.map { null }
        }
        val model = Model()
        val selector = ModelSelector().also { it.register(model) }
        val trace = AgentTraceManager(db.agentTraceEventDao())
        val memory = MemoryModule(db.memoryDao(), db.userGraphDao(), trace, provider,
            MemoryScoringEngine(), db.memoryExtractionJobDao(),
            VectorIndexWorker(db.vectorIndexDao(), provider), BoundedSemanticReranker(selector), com.zero.v2.framework.memory.MemoryClassificationTools(db.chronicleSourceDao(), db.memoryDerivationDao(), com.zero.v2.core.data.store.MemoryDerivationStore(db)), com.zero.v2.core.data.store.MemoryDerivationStore(db))
        val job = GraphExtractionJob(memory, db.memoryDao(), db.userGraphDao(), selector, trace, db.memoryExtractionJobDao())
        try {
            val source = MemoryEntity("user-fact:one", "我不喜欢冷饮", type = MemoryType.FACT, sourceConversationId = "chat", createdAt = 1)
            db.memoryDao().insert(source)
            memory.onAwake(); memory.onStart()
            memory.isEnabled = false
            job.execute()
            assertEquals(0, model.calls)
            assertEquals(Long.MAX_VALUE, job.nextRunTime)
            assertEquals(source.content, db.memoryDao().getById(source.id)!!.content)
            memory.isEnabled = true
            val entered = CompletableDeferred<Unit>()
            model.answer = { entered.complete(Unit); awaitCancellation() }
            val running = launch { job.execute() }
            withTimeout(5000) { entered.await() }
            running.cancelAndJoin()
            assertTrue(db.userGraphDao().getAttributes("user").isEmpty())
            assertEquals(1, db.userGraphDao().pendingGraphJobs(Long.MAX_VALUE).size)
            model.answer = { """{"entities":[],"attributes":[{"entityId":"user","key":"preference","memoryIndex":1}],"relationships":[]}""" }
            job.execute()
            assertEquals(source.content, db.userGraphDao().getCurrentAttributes("user").single().value)
            val calls = model.calls
            job.execute()
            assertEquals(calls, model.calls)
            assertTrue(db.userGraphDao().pendingGraphJobs(Long.MAX_VALUE).isEmpty())
            db.memoryDao().insert(MemoryEntity("legacy", "我不喜欢冷饮", type = MemoryType.FACT,
                sourceConversationId = "old-chat", confidence = 0.99f))
            val recall = memory.contributions().single { it.definition.name == "memory_recall" }.handler(
                mapOf("query" to "冷饮", "top_k" to 10))
            assertTrue(recall.success)
            assertTrue(recall.data.contains("历史记录，来源未核验"))
            assertTrue(memory.evidenceCandidates("冷饮", 10).any { it.id == "legacy" && it.content.contains("来源未核验") })
        } finally { memory.onDestroy(); db.close() }
    }
}

