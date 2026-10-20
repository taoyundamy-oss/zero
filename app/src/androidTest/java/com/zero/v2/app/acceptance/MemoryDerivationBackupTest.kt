package com.zero.v2.app.acceptance

import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.data.*
import com.zero.v2.core.common.MemoryType
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.di.ChroniclePrivacy
import com.zero.v2.core.data.entity.*
import com.zero.v2.core.data.store.MemoryDerivationStore
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.framework.memory.MemoryClassificationTools
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MemoryDerivationBackupTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun db() = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).addCallback(ChroniclePrivacy).build()
    private fun exporter(d: ZeroDatabase) = DataExporter(context, d.conversationDao(), d.messageDao(), d.characterDao(),
        d.memoryDao(), d.automationDao(), AgentTraceManager(d.agentTraceEventDao()), d)
    private fun importer(d: ZeroDatabase) = DataImporter(context, d.conversationDao(), d.messageDao(), d.characterDao(),
        d.memoryDao(), d.automationDao(), AgentTraceManager(d.agentTraceEventDao()), d)
    private fun fixture(block: suspend (ZeroDatabase, ZeroDatabase, File) -> Unit) = runBlocking {
        val from = db(); val to = db(); val file = File(context.cacheDir, "c4-backup-isolated.json")
        try { block(from, to, file) } finally { from.close(); to.close(); file.delete() }
    }
    private suspend fun seed(db: ZeroDatabase, id: String = "s", key: String = "饮食"): MemoryDerivationEntity {
        val s = ChronicleSourceEntity.message(MessageEntity(id, "c", "user", "我喜欢清淡饭菜", createdAt = 10), 11)!!
        db.chronicleSourceDao().retainSource(s)
        return MemoryDerivationEntity.create(s.id, s.body, s.body, MemoryType.PREFERENCE,
            "EXPLICIT", "personal", key, .8f, now = 20).also { MemoryDerivationStore(db).submit(it) }
    }

    @Test fun v5RoundTripsAllQueueStatesAndRebuildsInsteadOfRestoringCache() = fixture { from, to, file ->
        val applied = seed(from); MemoryDerivationStore(from).drain()
        val pending = seed(from, "pending", "pending"); val stale = seed(from, "stale", "stale")
        from.memoryDerivationDao().finish(stale.id, "STALE", 30)
        exporter(from).exportTo(Uri.fromFile(file)).getOrThrow()
        val root = JSONObject(file.readText()); assertEquals(6, root.getInt("version"))
        assertFalse(root.has("memoryDerivationViews"))
        importer(to).importFrom(Uri.fromFile(file)).getOrThrow()
        assertEquals(from.memoryDerivationDao().all(), to.memoryDerivationDao().all())
        assertTrue(to.memoryDao().recallBlocked(applied.memoryId))
        assertEquals("CANDIDATE", MemoryDerivationStore(to).current("personal", "饮食", 40).state)
        assertFalse(to.memoryDao().recallBlocked(applied.memoryId))
        assertEquals(1, MemoryDerivationStore(to).drain())
        importer(to).importFrom(Uri.fromFile(file)).getOrThrow()
        assertEquals("APPLIED", to.memoryDerivationDao().get(pending.id)!!.state)
        assertEquals("STALE", to.memoryDerivationDao().get(stale.id)!!.state)
    }

    @Test fun oldBackupCannotRestoreDeletedSourceOrLateResult() = fixture { from, to, file ->
        val d = seed(from); exporter(from).exportTo(Uri.fromFile(file)).getOrThrow()
        importer(to).importFrom(Uri.fromFile(file)).getOrThrow()
        val store = MemoryDerivationStore(to); val p = store.prepare(d.id)!!
        to.memoryExtractionJobDao().forgetById(d.memoryId, 30)
        importer(to).importFrom(Uri.fromFile(file)).getOrThrow()
        assertEquals("GONE", store.commit(p, 40)); assertTrue(to.memoryDerivationDao().all().isEmpty())
        assertTrue(to.chronicleSourceDao().all().isEmpty()); assertNull(to.memoryDao().getById(d.memoryId))
    }

    @Test fun pointerlessLocalTombstoneBlocksBackupBindingBeforeSourceRestore() = fixture { from, to, file ->
        val d = seed(from); MemoryDerivationStore(from).drain(); exporter(from).exportTo(Uri.fromFile(file)).getOrThrow()
        to.memoryExtractionJobDao().forgetById(d.memoryId, 30)
        importer(to).importFrom(Uri.fromFile(file)).getOrThrow()
        assertTrue(to.chronicleSourceDao().all().isEmpty()); assertTrue(to.memoryDerivationDao().all().isEmpty())
        assertNull(to.memoryDao().getById(d.memoryId))
    }

    @Test fun corruptBindingAndMissingAppliedResultRollbackWholeImport() = fixture { from, to, file ->
        seed(from); MemoryDerivationStore(from).drain(); exporter(from).exportTo(Uri.fromFile(file)).getOrThrow()
        val original = file.readText(); val corrupt = JSONObject(original)
        corrupt.getJSONArray("memoryDerivations").getJSONObject(0).put("type", "FACT")
        file.writeText(corrupt.toString()); assertTrue(importer(to).importFrom(Uri.fromFile(file)).isFailure)
        assertTrue(to.chronicleSourceDao().all().isEmpty())
        val missing = JSONObject(original).put("memories", org.json.JSONArray())
        file.writeText(missing.toString()); assertTrue(importer(to).importFrom(Uri.fromFile(file)).isFailure)
        assertTrue(to.chronicleSourceDao().all().isEmpty()); assertTrue(to.memoryDerivationDao().all().isEmpty())
    }

    @Test fun v4NeverInventsBindingsForDerivedMemories() = fixture { from, to, file ->
        seed(from); MemoryDerivationStore(from).drain(); exporter(from).exportTo(Uri.fromFile(file)).getOrThrow()
        val root = JSONObject(file.readText()).put("version", 4); root.remove("memoryDerivations")
        file.writeText(root.toString()); importer(to).importFrom(Uri.fromFile(file)).getOrThrow()
        assertTrue(to.memoryDerivationDao().all().isEmpty())
        assertNull(to.memoryDao().getById(from.memoryDerivationDao().all().single().memoryId))
    }

    @Test fun mainToolClassifiesPreferenceAndNormWithoutSecondaryModelAndKeepsSchemaStable() = fixture { from, _, _ ->
        val d = seed(from)
        val store = MemoryDerivationStore(from)
        val tools = MemoryClassificationTools(from.chronicleSourceDao(), from.memoryDerivationDao(), store)
        val before = tools.contributions().map { it.definition }
        val sources = tools.contributions().first { it.definition.name == "memory_sources" }.handler(emptyMap())
        assertTrue(sources.success); assertEquals(d.sourceId, JSONObject(sources.data).getJSONArray("sources").getJSONObject(0).getString("source_id"))
        val args = mapOf("source_id" to d.sourceId, "content" to d.content, "quote" to d.quote, "type" to "PREFERENCE",
            "basis" to "EXPLICIT", "claim_key" to "饮食")
        assertTrue(tools.remember(args).success)
        assertFalse(tools.remember(args - "type").success)
        assertFalse(tools.remember(args + mapOf("type" to "DIRECTIVE", "basis" to "INFERRED")).success)
        assertEquals(1, store.drain()); assertEquals(MemoryType.PREFERENCE, from.memoryDao().getById(d.memoryId)!!.type)
        assertEquals(before, tools.contributions().map { it.definition })
        assertTrue(from.memoryDao().importantMemories(System.currentTimeMillis()).isEmpty())
    }
    @Test fun explicitNormThroughMainToolPreservesTypeAndDoesNotAuthenticateIt() = fixture { from, _, _ ->
        val s = ChronicleSourceEntity.message(MessageEntity("norm", "c", "user", "以后发送通知都分条表达", createdAt = 10), 11)!!
        from.chronicleSourceDao().retainSource(s)
        val store = MemoryDerivationStore(from)
        val tools = MemoryClassificationTools(from.chronicleSourceDao(), from.memoryDerivationDao(), store)
        val result = tools.remember(mapOf("source_id" to s.id, "content" to s.body, "quote" to s.body,
            "type" to "DIRECTIVE", "basis" to "EXPLICIT", "claim_key" to "通知表达"))
        assertTrue(result.success); assertEquals(1, store.drain())
        val m = from.memoryDao().getById(JSONObject(result.data).getString("memory_id"))!!
        assertEquals(MemoryType.DIRECTIVE, m.type); assertEquals("MODEL_CLASSIFIED", m.origin)
        assertTrue(from.memoryDao().importantMemories(System.currentTimeMillis()).isEmpty())
    }

    @Test fun legacyFrozenToolContractUpgradesOnceAndSurvivesStoreRestart() = fixture { from, _, _ ->
        val root = File(context.cacheDir, "c4-manifest-owned")
        root.deleteRecursively()
        try {
            val scoped = object : android.content.ContextWrapper(context) {
                override fun getNoBackupFilesDir() = root
            }
            val conversation = "c4-contract-only"
            val key = java.security.MessageDigest.getInstance("SHA-256").digest(conversation.toByteArray()).joinToString("") { "%02x".format(it) }
            val file = File(File(root, "tool-manifests").apply { mkdirs() }, "$key.properties")
            java.util.Properties().apply {
                setProperty("epoch", "7")
                setProperty("contractVersion", "3") // Immediately previous persisted schema must upgrade too.
                setProperty("tools", "[{\"name\":\"memory_remember\",\"description\":\"legacy\",\"parameters\":{}}]")
            }.also { p -> file.outputStream().use { p.store(it, "legacy fixture") } }
            val tools = MemoryClassificationTools(from.chronicleSourceDao(), from.memoryDerivationDao(), MemoryDerivationStore(from))
            val catalog = tools.contributions().map { it.definition }
            fun select(store: com.zero.v2.framework.chat.ToolManifestStore, input: List<com.zero.v2.core.common.FunctionDefinition>): List<*> {
                val method = store.javaClass.methods.single { it.name.startsWith("select") }
                return method.invoke(store, conversation, 7L, input, emptySet<String>()) as List<*>
            }
            val upgraded = select(com.zero.v2.framework.chat.ToolManifestStore(scoped), catalog)
            // Persisted snapshots contain provider declarations; live registry retains execution permissions.
            fun wire(items: List<*>) = items.map { it as com.zero.v2.core.common.FunctionDefinition }
                .map { Triple(it.name, it.description, it.parameters) }.sortedBy { it.first }
            assertEquals(wire(catalog), wire(upgraded))
            val version = java.util.Properties().apply { file.inputStream().use { load(it) } }.getProperty("contractVersion")
            assertEquals("4", version)
            val restored = select(com.zero.v2.framework.chat.ToolManifestStore(scoped), catalog.map { it.copy(description = "catalog changed") })
            assertEquals(upgraded, restored)
        } finally { root.deleteRecursively() }
    }

    @Test fun restoredViewsPrecedeIndexBuildAndCompletedUnchangedSweepsDoNotWakeItAgain() = fixture { from, _, _ ->
        val store = MemoryDerivationStore(from)
        val first = seed(from); store.drain(); from.memoryDerivationDao().clearView(first.slotId)
        val provider = object : com.zero.v2.services.embedding.EmbeddingProvider {
            override val modelId = "offline-no-inference"
            override suspend fun isAvailable() = false
            override suspend fun embed(text: String): FloatArray? = error("no provider calls")
            override suspend fun embedBatch(texts: List<String>): List<FloatArray?> = error("no provider calls")
        }
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val observations = kotlinx.coroutines.channels.Channel<Int>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val runtime = object : com.zero.v2.services.embedding.VectorIndexRuntime by com.zero.v2.services.embedding.VectorIndexWorker(from.vectorIndexDao(), provider) {
            override suspend fun build(domain: com.zero.v2.services.embedding.VectorDomain): com.zero.v2.services.embedding.VectorIndexWorker.Outcome {
                calls.incrementAndGet(); observations.send(from.vectorIndexDao().sourceCount(domain.key))
                return com.zero.v2.services.embedding.VectorIndexWorker.Outcome.CURRENT
            }
        }
        val module = com.zero.v2.framework.memory.MemoryModule(from.memoryDao(), from.userGraphDao(),
            AgentTraceManager(from.agentTraceEventDao()), provider, com.zero.v2.framework.memory.MemoryScoringEngine(),
            from.memoryExtractionJobDao(), runtime, com.zero.v2.services.retrieval.BoundedSemanticReranker(com.zero.v2.services.llm.ModelSelector()),
            MemoryClassificationTools(from.chronicleSourceDao(), from.memoryDerivationDao(), store), store)
        try {
            module.onAwake(); module.onStart()
            assertEquals(1, kotlinx.coroutines.withTimeout(5000) { observations.receive() })
            val job = com.zero.v2.framework.memory.MemoryDerivationJob(module, store)
            job.execute(); assertEquals(1, kotlinx.coroutines.withTimeout(5000) { observations.receive() })
            job.execute(); assertEquals(2, calls.get())
            val second = seed(from, "new-source", "another-key"); store.drain()
            from.memoryDerivationDao().clearView(second.slotId)
            store.current("personal", "another-key", System.currentTimeMillis()) // a read rebuilds before the worker
            job.execute(); assertEquals(2, kotlinx.coroutines.withTimeout(5000) { observations.receive() })
            job.execute(); assertEquals(3, calls.get())
        } finally { module.onDestroy(); observations.close() }
    }

}
