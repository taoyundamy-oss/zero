package com.zero.v2.app.acceptance

import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.data.*
import com.zero.v2.core.common.*
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.di.ChroniclePrivacy
import com.zero.v2.core.data.entity.*
import com.zero.v2.core.data.store.MemoryDerivationStore
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.services.llm.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Isolated Room/production Agent, no network, model runtime or personal data. */
class MemoryLiveAcceptanceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun database() = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).addCallback(ChroniclePrivacy).build()
    private fun isolated(block: suspend (ZeroDatabase, MemoryDerivationStore) -> Unit): Unit = runBlocking {
        val db = database()
        try { block(db, MemoryDerivationStore(db)) } finally { db.close() }
    }
    private suspend fun draft(db: ZeroDatabase, id: String, type: MemoryType = MemoryType.TASK,
        key: String = "院子任务", status: String = "ACTIVE", scope: String = "personal", importance: Float = .8f): MemoryDerivationEntity {
        val body = "院子资料$id：请检查备份，不要删除旧配置。"
        val source = ChronicleSourceEntity.message(MessageEntity(id, "isolated", "user", body,
            source = "CHAT_WINDOW", createdAt = 10), 11)!!
        db.chronicleSourceDao().retainSource(source)
        val content = if (type == MemoryType.TASK) TaskMemory(status, "核对备份", "配置保持", "knowledge/task.md", body).encode() else body
        return MemoryDerivationEntity.create(source.id, content, body, type, "EXPLICIT", scope, key, importance, now = 20)
    }
    private suspend fun apply(store: MemoryDerivationStore, d: MemoryDerivationEntity) { store.submit(d); store.drain() }

    @Test fun replacementIsAtomicIdempotentAndKeepsHistory() = isolated { db, store ->
        val first = draft(db, "first"); apply(store, first)
        val version = store.receipt(first.memoryId)!!.version
        val next = draft(db, "next", status = "WAITING")
        val saved = store.replace(next, first.memoryId, version)
        assertEquals("APPLIED", saved.state)
        assertEquals(saved, store.replace(next, first.memoryId, version))
        assertEquals(MemoryStatus.ARCHIVED, db.memoryDao().getById(first.memoryId)!!.status)
        val current = db.memoryDao().getById(next.memoryId)!!
        assertEquals(first.memoryId, current.supersedesId); assertEquals(2, current.revision)
        assertEquals("WAITING", TaskMemory.decode(current.content)!!.status)
        assertEquals(next.id, store.current("personal", first.claimKey, 30).selectedId)
        assertNotNull(db.chronicleSourceDao().chronicle(first.sourceId))
        assertEquals(2, db.memoryDerivationDao().all().size)
    }
    @Test fun staleVersionScopeAndDirectiveDemotionAreRejectedWithoutPartialWrites() = isolated { db, store ->
        val first = draft(db, "norm", MemoryType.DIRECTIVE); apply(store, first)
        val version = store.receipt(first.memoryId)!!.version
        val other = draft(db, "other", MemoryType.DIRECTIVE)
        for ((d, expected) in listOf(other to "wrong", draft(db, "scope", MemoryType.DIRECTIVE, scope = "work") to version,
            draft(db, "demote", MemoryType.FACT) to version)) {
            try { store.replace(d, first.memoryId, expected); fail() } catch (_: IllegalArgumentException) { }
        }
        assertEquals(1, db.memoryDerivationDao().all().size)
        assertEquals(MemoryStatus.ACTIVE, db.memoryDao().getById(first.memoryId)!!.status)
    }
    @Test fun conflictingNormCannotBeChosenByReplacement() = isolated { db, store ->
        val a = draft(db, "a", MemoryType.DIRECTIVE); apply(store, a)
        val b = draft(db, "b", MemoryType.DIRECTIVE); apply(store, b)
        assertEquals("CONFLICT", store.current("personal", a.claimKey, 30).state)
        try { store.replace(draft(db, "c", MemoryType.DIRECTIVE), a.memoryId, store.receipt(a.memoryId)!!.version); fail() }
        catch (_: IllegalArgumentException) { }
        assertEquals(2, db.memoryDerivationDao().all().size)
    }
    @Test fun logicalWithdrawalCancelsLateResultButPreservesSourceAndHistory() = isolated { db, store ->
        val a = draft(db, "active"); apply(store, a)
        val p = draft(db, "pending", key = "另一个任务"); store.submit(p)
        val prepared = store.prepare(p.id)!!
        assertEquals(2, store.withdrawMatching("院子资料", 30))
        assertEquals("STALE", store.commit(prepared, 40)); assertEquals(0, store.drain())
        assertEquals(MemoryStatus.ARCHIVED, db.memoryDao().getById(a.memoryId)!!.status)
        assertFalse(db.memoryDao().wasDeleted(a.memoryId))
        assertNotNull(db.chronicleSourceDao().chronicle(a.sourceId))
        assertEquals(0, store.withdrawMatching("院子资料", 50))
    }
    @Test fun backupRestoresTaskFieldsReplacementAndLocalWithdrawalWinsOldBackup() = isolated { from, store ->
        val to = database(); val file = File(context.cacheDir, "live-${UUID.randomUUID()}.json")
        val exporter = DataExporter(context, from.conversationDao(), from.messageDao(), from.characterDao(), from.memoryDao(),
            from.automationDao(), AgentTraceManager(from.agentTraceEventDao()), from)
        val importer = DataImporter(context, to.conversationDao(), to.messageDao(), to.characterDao(), to.memoryDao(),
            to.automationDao(), AgentTraceManager(to.agentTraceEventDao()), to)
        try {
            val a = draft(from, "a"); apply(store, a)
            val b = draft(from, "b", status = "DONE")
            store.replace(b, a.memoryId, store.receipt(a.memoryId)!!.version)
            exporter.exportTo(Uri.fromFile(file)).getOrThrow(); importer.importFrom(Uri.fromFile(file)).getOrThrow()
            assertEquals(MemoryStatus.ARCHIVED, to.memoryDao().getById(a.memoryId)!!.status)
            assertEquals(a.memoryId, to.memoryDao().getById(b.memoryId)!!.supersedesId)
            assertEquals("DONE", TaskMemory.decode(to.memoryDao().getById(b.memoryId)!!.content)!!.status)
            MemoryDerivationStore(to).withdrawMatching("院子资料", 40)
            importer.importFrom(Uri.fromFile(file)).getOrThrow()
            assertEquals(MemoryStatus.ARCHIVED, to.memoryDao().getById(b.memoryId)!!.status)
        } finally { to.close(); file.delete() }
    }
    @Test fun contextUsesSixKindsStatusFrequencyScopeAndSourceWithoutSelfReinforcement() = runBlocking {
        val provider = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val f = ChronicleAgentFixture(context, provider.mediaAnalysis())
        try {
            val entries = listOf(draft(f.db, "norm", MemoryType.DIRECTIVE, "norm"),
                draft(f.db, "relation", MemoryType.RELATIONSHIP, "relation"),
                draft(f.db, "active", key = "active"), draft(f.db, "waiting", key = "waiting", status = "WAITING"),
                draft(f.db, "done", key = "done", status = "DONE"), draft(f.db, "cancel", key = "cancel", status = "CANCELLED"),
                draft(f.db, "fact", MemoryType.FACT, "fact"), draft(f.db, "pref", MemoryType.PREFERENCE, "pref", importance = .1f),
                draft(f.db, "event", MemoryType.EVENT, "event"), draft(f.db, "work", MemoryType.RELATIONSHIP, "work", scope = "work"))
            entries.forEach { apply(f.store, it) }
            val pref = entries.single { it.claimKey == "pref" }
            repeat(2) { f.db.memoryDao().recordAccess(pref.memoryId) }
            f.db.memoryDao().insert(MemoryEntity("unknown", "不应自动认证", type = MemoryType.DIRECTIVE))
            val current = f.memory.contextMemories(emptyList())
            val expected = entries.filter { it.claimKey in setOf("norm", "relation", "active", "waiting", "fact", "pref") }.map { it.memoryId }.toSet()
            assertEquals(expected, current.map { it.id }.toSet())
            assertEquals(2, f.db.memoryDao().getById(pref.memoryId)!!.accessCount)
            assertTrue(current.all { it.content.contains("非语义认证") })
            f.db.memoryExtractionJobDao().forgetById(entries.first().memoryId, 50)
            assertFalse(f.memory.contextMemories(current.map { it.id }).any { it.id == entries.first().memoryId })
        } finally { f.close() }
    }
    @Test fun actualAgentWithdrawalKeepsOldRequestPrefixAndPurgeStillDeletesSource() = runBlocking {
        val provider = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val f = ChronicleAgentFixture(context, provider.mediaAnalysis(), liveMemoryContext = true)
        var round = 0; var first = emptyList<ChatMessage>(); var prefix: String? = null
        val model = object : ILanguageModel {
            override val modelId = "local-script-no-network"
            override val displayName = modelId
            override val contextWindow = 32768
            override val capabilities = setOf(ModelCapability.CHAT, ModelCapability.FUNCTION_CALLING)
            override val keyConfigured = true
            override val priority = 1
            override val enabled = true
            override suspend fun chatSync(userMessage: String, systemPrompt: String?) = error("no model requests")
            override fun chatStream(messages: List<ChatMessage>, systemPrompt: String?, functions: List<FunctionDefinition>, prefixVersion: String?) = flow {
                if (round++ == 0) {
                    first = messages.toList(); prefix = systemPrompt
                    assertTrue(messages.any { it.content.orEmpty().contains("主Agent记录") })
                    assertTrue(messages.none { it.role == MessageRole.SYSTEM })
                    emit(StreamChunk(toolCallDelta = ToolCallDelta(id = "forget", name = "memory_forget", argumentsDelta = """{"keyword":"院子资料","purge":false}"""), finishReason = "tool_calls", isFinal = true))
                } else {
                    assertEquals(prefix, systemPrompt); assertEquals(first, messages.take(first.size))
                    assertTrue(messages.last().content.orEmpty().contains("撤回 id="))
                    emit(StreamChunk(contentDelta = "已停止使用，历史保留。", isFinal = true, finishReason = "stop"))
                }
            }
        }
        try {
            val d = draft(f.db, "agent", MemoryType.DIRECTIVE); apply(f.store, d)
            f.start(model, "固定系统前缀。")
            withTimeout(30000) { f.bot.sendMessage("停止使用院子资料。", f.registry.getAllDefinitions()).collect(f.bot::applyStreamChunk) }
            assertEquals(2, round); assertEquals(MemoryStatus.ARCHIVED, f.db.memoryDao().getById(d.memoryId)!!.status)
            assertNotNull(f.db.chronicleSourceDao().chronicle(d.sourceId))
            assertTrue(f.registry.execute(ToolCall("purge", "memory_forget", """{"keyword":"院子资料","purge":true}""")).success)
            assertNull(f.db.chronicleSourceDao().chronicle(d.sourceId))
            assertTrue(f.db.memoryDao().wasDeleted(d.memoryId))
        } finally { f.close() }
    }
}
