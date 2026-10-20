package com.zero.v2.app.acceptance

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.common.*
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.di.ChroniclePrivacy
import com.zero.v2.core.data.entity.*
import com.zero.v2.core.data.store.MemoryDerivationStore
import com.zero.v2.framework.memory.MemoryClassificationTools
import com.zero.v2.services.llm.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic isolated Room, never a real model or the phone's personal DB. */
class M5SearchAcceptanceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var serial = 0
    private fun isolated(block: suspend (ZeroDatabase, MemoryDerivationStore) -> Unit) = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).addCallback(ChroniclePrivacy).build()
        try { block(db, MemoryDerivationStore(db)) } finally { db.close() }
    }
    private suspend fun source(db: ZeroDatabase, body: String): ChronicleSourceEntity {
        val id = "independent-${++serial}"
        val s = ChronicleSourceEntity.message(MessageEntity(id, "independent-synthetic", "user", body,
            source = "CHAT_WINDOW", createdAt = 100), 200L + serial)!!
        check(db.chronicleSourceDao().retainSource(s)); return s
    }
    private suspend fun seed(db: ZeroDatabase, store: MemoryDerivationStore, body: String,
        key: String = "灯架/材质", scope: String = "personal", until: Long? = null): MemoryDerivationEntity {
        val s = source(db, body)
        val d = MemoryDerivationEntity.create(s.id, body.take(80), body.take(40), MemoryType.DIRECTIVE,
            "EXPLICIT", scope, key, .8f, validUntil = until, now = 300)
        store.submit(d); store.drain(); return d
    }
    @Test fun multiTermSearchIsOrderIndependentAndSearchesBeyondRecentRows() = isolated { db, _ ->
        val target = source(db, "展柜的备用标牌改用海蓝底色。")
        repeat(80) { source(db, "第${it}份无关电缆清单。") }
        assertEquals(listOf(target.id), db.chronicleSourceDao().searchTerms("海蓝 展柜", 8).map { it.id })
        assertTrue(db.chronicleSourceDao().searchTerms("展柜 紫色", 8).isEmpty())
        val english = source(db, "Archive note is on shelf seven.")
        assertEquals(english.id, db.chronicleSourceDao().searchTerms("NOTE archive", 1).single().id)
        assertTrue(db.chronicleSourceDao().searchTerms("%'_ OR 1=1 --", 8).isEmpty())
    }
    @Test fun deletionIsFilteredBeforeLimitEvenIfPhysicalSourceReturns() = isolated { db, _ ->
        val old = source(db, "雨棚检查已经登记。")
        val deleted = source(db, "雨棚检查尚未登记。")
        db.withTransaction { db.chronicleSourceDao().insertDeletion(ChronicleDeletionEntity("message", deleted.anchorId, 900)) }
        assertNull(db.chronicleSourceDao().chronicle(deleted.id)) // SQL privacy trigger removes the normal copy.
        // Simulate a legacy importer bypassing retainSource: the read guard must still reject the old copy.
        db.withTransaction { db.chronicleSourceDao().insertChronicle(deleted) }
        assertNotNull(db.chronicleSourceDao().chronicle(deleted.id))
        assertEquals(old.id, db.chronicleSourceDao().searchTerms("雨棚 检查", 1).single().id)
    }
    @Test fun diagnosticsReadBothSourcesWithoutMutatingHistoryOrCurrentView() = isolated { db, store ->
        val a = seed(db, store, "灯架必须用竹制支架。")
        val b = seed(db, store, "灯架必须用金属支架。")
        val memories = db.memoryDao().observeAll().first(); val sources = db.chronicleSourceDao().all()
        val view = store.viewFingerprint()
        val d = store.conflictDiagnostics("灯架 材质 旧记录", "personal", 1000).single()
        assertEquals(setOf(a.sourceId, b.sourceId), d.candidates.map { it.source!!.id }.toSet())
        assertEquals(memories, db.memoryDao().observeAll().first()); assertEquals(sources, db.chronicleSourceDao().all())
        assertEquals(view, store.viewFingerprint())
    }
    @Test fun conflictSubjectAndScopeDoNotLeakIntoUnrelatedQueries() = isolated { db, store ->
        seed(db, store, "灯架必须用竹制支架。")
        seed(db, store, "灯架必须用金属支架。")
        assertTrue(store.conflictDiagnostics("花盆 材质", "personal", 1000).isEmpty())
        assertTrue(store.conflictDiagnostics("材质", "personal", 1000).isEmpty())
        assertTrue(store.conflictDiagnostics("灯架", "work", 1000).isEmpty())
    }
    @Test fun withdrawnDeletedAndExpiredSidesAreNeverReturnedAsDiagnosticText() = isolated { db, store ->
        val a = seed(db, store, "灯架必须用竹制支架。")
        seed(db, store, "灯架必须用金属支架。")
        seed(db, store, "灯架必须用陶制支架。", until = 500)
        db.withTransaction { db.chronicleSourceDao().insertDeletion(ChronicleDeletionEntity("message", "independent-1", 900)) }
        val rows = store.conflictDiagnostics("灯架", "personal", 1000).flatMap { it.candidates }
        assertTrue(rows.none { it.entry.sourceId == a.sourceId || it.entry.content.contains("陶制") })
        store.withdrawMatching("金属", 1100)
        assertTrue(store.conflictDiagnostics("灯架", "personal", 1200).isEmpty())
    }
    @Test fun diagnosticToolBudgetsKeepCompleteSourcesAndDiscloseOmissions() = isolated { db, store ->
        repeat(4) { seed(db, store, "灯架第${it}种方案要求保留原文。" + "资料".repeat(1700)) }
        val tools = MemoryClassificationTools(db.chronicleSourceDao(), db.memoryDerivationDao(), store)
        val text = checkNotNull(tools.conflictDiagnostics("灯架", "personal"))
        val d = JSONObject(text).getJSONArray("conflicts").getJSONObject(0)
        assertTrue(d.getBoolean("truncated")); assertFalse(d.getBoolean("usable_as_current_fact"))
        assertTrue(text.length < 16000)
        val candidates = d.getJSONArray("candidates")
        for (i in 0 until candidates.length()) assertTrue(candidates.getJSONObject(i).getString("body").endsWith("资料"))
    }
    @Test fun registryRecallExposesConflictSourcesAndKeepsThemOutOfContext() = runBlocking<Unit> {
        val entry = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val f = ChronicleAgentFixture(context, entry.mediaAnalysis(), liveMemoryContext = true)
        val model = object : ILanguageModel {
            override val modelId = "offline-no-calls"
            override val displayName = modelId
            override val contextWindow = 32768
            override val capabilities = setOf(ModelCapability.CHAT, ModelCapability.FUNCTION_CALLING)
            override val keyConfigured = true
            override val enabled = true
            override val priority = 1
            override suspend fun chatSync(userMessage: String, systemPrompt: String?) = error("no model calls")
            override fun chatStream(messages: List<ChatMessage>, systemPrompt: String?, functions: List<FunctionDefinition>, prefixVersion: String?) = flow<StreamChunk> { error("no model calls") }
        }
        try {
            val a = seed(f.db, f.store, "灯架必须用竹制支架。")
            val b = seed(f.db, f.store, "灯架必须用金属支架。")
            f.start(model, "固定前缀。")
            val definitions = f.registry.getAllDefinitions()
            val result = f.registry.execute("memory_recall", mapOf("query" to "灯架 材质 旧记录"))
            assertTrue(result.success); assertTrue(result.data.contains(a.sourceId) && result.data.contains(b.sourceId))
            val exact = f.registry.execute("memory_recall", mapOf("query" to a.memoryId))
            assertTrue(exact.success); assertTrue(exact.data.contains(b.sourceId))
            assertTrue(f.memory.contextMemories(emptyList()).none { it.id in setOf(a.memoryId, b.memoryId) })
            assertEquals(definitions, f.registry.getAllDefinitions())
        } finally { f.close() }
    }
    @Test fun taskToolRejectsNestedProtocolAndPersistsPlainStructuredTask() = isolated { db, store ->
        val s = source(db, "登记手册任务已经完成，编号核对一致，无需后续动作。")
        val tools = MemoryClassificationTools(db.chronicleSourceDao(), db.memoryDerivationDao(), store)
        val args = mapOf<String, Any?>("source_id" to s.id, "quote" to s.body, "type" to "TASK", "basis" to "EXPLICIT",
            "claim_key" to "登记手册/任务", "task_status" to "DONE", "next_step" to "无需后续动作", "acceptance" to "编号核对一致")
        assertFalse(tools.remember(args + ("content" to "[zero-task:v1 status=DONE]\\n重复包装")).success)
        assertTrue(db.memoryDerivationDao().all().isEmpty())
        assertTrue(tools.remember(args + ("content" to "登记手册任务已经完成")).success)
        store.drain()
        val task = TaskMemory.decode(db.memoryDao().observeAll().first().single().content)!!
        assertEquals("DONE", task.status); assertEquals("登记手册任务已经完成", task.text)
    }
}
