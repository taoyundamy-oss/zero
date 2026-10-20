package com.zero.v2.app.acceptance

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import com.zero.v2.core.bus.*
import com.zero.v2.core.common.*
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.di.ChroniclePrivacy
import com.zero.v2.core.data.store.*
import com.zero.v2.core.moduleapi.ToolExecutionGuard
import com.zero.v2.core.modulemanager.*
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.framework.chat.*
import com.zero.v2.framework.memory.*
import com.zero.v2.services.agent.BudgetGuard
import com.zero.v2.services.embedding.*
import com.zero.v2.services.llm.*
import com.zero.v2.services.retrieval.*
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import javax.inject.Provider

/** Typed production engine, registry, lifecycle guard and memory tools; no host-side imitation. */
internal class ChronicleAgentFixture(context: Context, media: MediaAnalysisService, liveMemoryContext: Boolean = false) {
    val root = File(context.cacheDir, "c5-agent-${UUID.randomUUID()}").apply { mkdirs() }
    val scoped = object : ContextWrapper(context) {
        override fun getNoBackupFilesDir() = File(root, "projection").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(root.name + name, mode)
    }
    val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).addCallback(ChroniclePrivacy).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val configs = ModuleConfigStore(db.moduleConfigDao(), scope)
    val trace = AgentTraceManager(db.agentTraceEventDao())
    val bus = ChatBusImpl()
    val selector = ModelSelector()
    val store = MemoryDerivationStore(db)
    private val embedding = object : EmbeddingProvider {
        override val modelId = "c5-offline-no-vector"
        override suspend fun isAvailable() = false
        override suspend fun embed(text: String): FloatArray? = error("unexpected vector request")
        override suspend fun embedBatch(texts: List<String>): List<FloatArray?> = error("unexpected vector request")
    }
    val memory = MemoryModule(db.memoryDao(), db.userGraphDao(), trace, embedding, MemoryScoringEngine(),
        db.memoryExtractionJobDao(), VectorIndexWorker(db.vectorIndexDao(), embedding), BoundedSemanticReranker(selector),
        MemoryClassificationTools(db.chronicleSourceDao(), db.memoryDerivationDao(), store), store)
    lateinit var manager: ModuleLifecycleManager
    val registry = FunctionRegistryModule(bus, trace, Provider<ToolExecutionGuard> { manager })
    private val allocator = TokenBudgetAllocator()
    val builder = ContextBuilderModule(allocator)
    val character = CharacterStateModule(db.characterStateDao(), db.characterDao(), bus, configs, trace)
    val bot = ChatBotModule(selector, registry,
        DefaultToolContextSelector(registry, Provider { manager }), bus, MessagePipeline(MessagePipelineTraceRecorder()),
        builder, trace, StoredAttachmentReader(scoped), media, BudgetGuard(scoped, configs, trace), allocator,
        GenerationProjectionStore(scoped), ToolManifestStore(scoped), MemoryContextStore(scoped),
        ConversationSummarizer(db.conversationDao(), selector, trace),
        object : EvidenceProvider {
            override suspend fun evidenceFor(query: String, limit: Int) = emptyList<EvidenceRef>()
            override suspend fun contextMemories(knownIds: List<String>): List<ContextMemory> =
                if (liveMemoryContext) manager.safeCall(memory, "context_memories_test") { memory.contextMemories(knownIds) }.orEmpty()
                else emptyList()
        }, character, ChronicleInputStore(db))
    init {
        manager = ModuleLifecycleManager(setOf(memory, registry, builder, character, bot), bus, configs, trace, registry, registry)
        (builder.config as ContextBuilderConfig).evidenceAutoInject = false // new path remains gated in production too
        (bot.config as ChatBotModuleConfig).maxFcRounds = 8
    }
    suspend fun start(model: ILanguageModel, prompt: String) {
        selector.register(model)
        manager.initializeAll()
        bot.initialize(prompt)
        bot.loadHistory("c5-isolated-${UUID.randomUUID()}", emptyList())
    }
    suspend fun close() {
        manager.destroyAll()
        scope.cancel()
        db.close()
        root.deleteRecursively() // owned UUID directory below the test app cache only
    }
}
