package com.zero.v2.app.acceptance

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.common.*
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.di.ChroniclePrivacy
import com.zero.v2.core.data.entity.*
import com.zero.v2.core.data.store.MemoryDerivationStore
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.framework.memory.*
import com.zero.v2.services.embedding.*
import com.zero.v2.services.llm.ModelSelector
import com.zero.v2.services.retrieval.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ClassifiedSourceAcceptanceTest {
    private fun fixture(block: suspend (ZeroDatabase, MemoryDerivationStore, MemoryModule) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).addCallback(ChroniclePrivacy).build()
        val store = MemoryDerivationStore(db)
        val provider = object : EmbeddingProvider {
            override val modelId = "c5-no-inference"
            override suspend fun isAvailable() = false
            override suspend fun embed(text: String): FloatArray? = error("no provider request")
            override suspend fun embedBatch(texts: List<String>): List<FloatArray?> = error("no provider request")
        }
        val module = MemoryModule(db.memoryDao(), db.userGraphDao(), AgentTraceManager(db.agentTraceEventDao()), provider,
            MemoryScoringEngine(), db.memoryExtractionJobDao(), VectorIndexWorker(db.vectorIndexDao(), provider),
            BoundedSemanticReranker(ModelSelector()), MemoryClassificationTools(db.chronicleSourceDao(), db.memoryDerivationDao(), store), store)
        try { block(db, store, module) } finally { module.onDestroy(); db.close() }
    }
    private val body = "储藏室灯的安排。" + "还在清点材料。".repeat(40) + "只有有货才动工，目前没有换好，不能据此说是天气造成延期。"
    private suspend fun seed(db: ZeroDatabase, store: MemoryDerivationStore, scope: String = "personal", run: String? = null): MemoryDerivationEntity {
        val s = ChronicleSourceEntity.message(MessageEntity("s", "c", "user", body,
            automationRunId = run, source = "CHAT_WINDOW", createdAt = 10), 20)!!
        db.chronicleSourceDao().retainSource(s)
        val d = MemoryDerivationEntity.create(s.id, "储藏室灯计划更换", body, MemoryType.EVENT,
            "EXPLICIT", scope, "储藏室/换灯", .8f, now = 30)
        store.submit(d); store.commit(store.prepare(d.id)!!, 31)
        return d
    }
    @Test fun explicitToolKeepsFullSourceAndNewAutoPathRemainsStaged() = fixture { db, store, module ->
        val d = seed(db, store)
        val recall = module.contributions().single { it.definition.name == "memory_recall" }.handler
        val exact = recall(mapOf("query" to d.memoryId))
        assertTrue(exact.success); assertTrue(exact.data.contains("目前没有换好"))
        assertTrue(exact.data.contains("不等于问题支持"))
        val ranked = recall(mapOf("query" to "储藏室", "top_k" to 3))
        assertTrue(ranked.data.contains("目前没有换好"))
        assertTrue("old automatic entry must not bypass the C5 gate", module.evidenceCandidates("储藏室", 3).isEmpty())
        val refs = module.qualifiedEvidenceCandidates("储藏室", 3)
        assertEquals(1, refs.size)
        assertTrue(refs.single().completeSource)
        assertTrue(module.revalidateQualifiedEvidence(refs.single()))
        assertTrue(EvidencePolicy.formatBlock(refs)!!.contains("memory_recall"))
    }
    @Test fun selectedSourceWithdrawsAfterDeletionAndNeverReappearsById() = fixture { db, store, module ->
        val d = seed(db, store)
        val ref = module.qualifiedEvidenceCandidates("储藏室").single()
        db.chronicleSourceDao().redact(ChronicleDeletionEntity("message", "s", 50))
        assertFalse(module.revalidateQualifiedEvidence(ref))
        assertNull(store.readCandidate(d.memoryId, 60))
        val recall = module.contributions().single { it.definition.name == "memory_recall" }.handler
        assertFalse(recall(mapOf("query" to d.memoryId)).success)
        assertNotNull(db.memoryDao().getById(d.memoryId))
    }
    @Test fun staleCachedViewCannotHideAConflictAndReadsDoNotWriteViewsOrCounters() = fixture { db, store, module ->
        val d = seed(db, store)
        val stale = db.memoryDerivationDao().viewPage("", 10).single()
        val next = MemoryDerivationEntity.create(d.sourceId, "储藏室已经换好", body, MemoryType.EVENT,
            "EXPLICIT", "personal", d.claimKey, .8f, now = 40)
        store.submit(next); store.commit(store.prepare(next.id)!!, 41)
        db.memoryDerivationDao().putView(stale)
        val before = db.memoryDao().getById(d.memoryId)!!
        assertEquals("CONFLICT", store.readCandidate(d.memoryId, 50)!!.view.state)
        assertNull(ClassifiedMemoryReader(store).capture(d.memoryId, now = 50).ticket)
        assertEquals(stale, db.memoryDerivationDao().viewPage("", 10).single())
        assertEquals(before, db.memoryDao().getById(d.memoryId))
        assertTrue(module.qualifiedEvidenceCandidates("储藏室").isEmpty())
    }
    @Test fun automationAndOtherScopeAreNotPersonalEvidence() = fixture { db, store, module ->
        seed(db, store, scope = "work", run = "automation")
        assertTrue(module.qualifiedEvidenceCandidates("储藏室").isEmpty())
        assertTrue(module.evidenceCandidates("储藏室").isEmpty())
    }
    @Test fun explicitScopeCanReadItsOwnCandidateButNeverDefaultOrAnotherScope() = fixture { db, store, module ->
        val d = seed(db, store, scope = "work")
        val recall = module.contributions().single { it.definition.name == "memory_recall" }.handler
        assertFalse(recall(mapOf("query" to d.memoryId)).success)
        assertFalse(recall(mapOf("query" to d.memoryId, "scope" to "other")).success)
        val result = recall(mapOf("query" to d.memoryId, "scope" to "work"))
        assertTrue(result.success); assertTrue(result.data.contains("目前没有换好"))
        assertTrue(recall(mapOf("query" to "储藏室", "scope" to "work")).data.contains("目前没有换好"))
        assertFalse(recall(mapOf("query" to "储藏室")).data.contains("目前没有换好"))
    }
    @Test fun canonicalReadHasBoundedSlotAndFailsClosedWhenOverfull() = fixture { db, store, _ ->
        val first = seed(db, store)
        for (i in 1..128) {
            val d = MemoryDerivationEntity.create(first.sourceId, "储藏室候选$i", body, MemoryType.EVENT,
                "EXPLICIT", "personal", first.claimKey, .8f, now = 40)
            db.memoryDerivationDao().insert(d)
        }
        assertNull(store.readCandidate(first.memoryId, 50))
    }

    @Test fun legacyAutomaticEvidenceRequiresWholeVerifiedSourceAndRechecksChanges() = fixture { db, _, module ->
        db.conversationDao().insert(ConversationEntity("legacy-chat", "isolated"))
        val source = MessageEntity("legacy-source", "legacy-chat", "user", body, source = "CHAT_WINDOW", createdAt = 10)
        db.messageDao().insert(source)
        val m = MemoryEntity("legacy-memory", body, origin = "USER_QUOTE", sourceMessageId = source.id,
            sourceConversationId = source.conversationId, createdAt = 10)
        db.memoryDao().insert(m)
        db.memoryDao().insert(MemoryEntity("unknown", "储藏室已经换好", origin = "UNKNOWN"))
        val refs = module.evidenceCandidates("储藏室", 3)
        assertEquals(listOf(m.id), refs.map { it.id })
        assertTrue(refs.single().content.contains("目前没有换好"))
        assertTrue(module.revalidateSourceEvidence(refs.single()))
        db.messageDao().insert(source.copy(content = "储藏室变更后的原文"))
        assertFalse(module.revalidateSourceEvidence(refs.single()))
        assertTrue(module.evidenceCandidates("储藏室", 3).isEmpty())
        assertNotNull(db.memoryDao().getById("unknown"))
    }
    @Test fun explicitUnverifiedNotesRetainLateQualifiersWithoutBecomingAutomaticEvidence() = fixture { db, _, module ->
        db.memoryDao().insert(MemoryEntity("old-note", body, origin = "UNKNOWN"))
        val recall = module.contributions().single { it.definition.name == "memory_recall" }.handler
        val result = recall(mapOf("query" to "储藏室"))
        assertTrue(result.success)
        assertTrue(result.data.contains("目前没有换好"))
        assertTrue(result.data.contains("不是来源证据"))
        assertTrue(module.evidenceCandidates("储藏室").isEmpty())
        assertEquals(body, db.memoryDao().getById("old-note")!!.content)
    }
    @Test fun confirmationAuditMustMatchParentVersionAndScope() = fixture { db, store, _ ->
        val parent = MemoryEntity("parent", "历史待复核笔记")
        db.memoryDao().insert(parent)
        val review = db.memoryReviewDao().propose(parent, "以后请把检查项单独列出，不要混入结论", MemoryType.DIRECTIVE,
            "personal", "显式确认", 10)!!
        assertTrue(db.memoryReviewDao().decide(review, true, 11))
        val source = db.chronicleSourceDao().all().single()
        val d = MemoryDerivationEntity.create(source.id, source.body, source.body, MemoryType.DIRECTIVE,
            "EXPLICIT", "personal", "回复/检查项", .8f, now = 20)
        store.submit(d); store.commit(store.prepare(d.id)!!, 21)
        val reader = ClassifiedMemoryReader(store)
        val ticket = reader.capture(d.memoryId, now = 30).ticket!!
        val wrongScope = MemoryDerivationEntity.create(source.id, source.body, source.body, MemoryType.DIRECTIVE,
            "EXPLICIT", "work", "回复/检查项", .8f, now = 22)
        store.submit(wrongScope); store.commit(store.prepare(wrongScope.id)!!, 23)
        assertNull(reader.capture(wrongScope.memoryId, scope = "work", now = 30).ticket)
        val actual = db.memoryDao().getById(parent.id)!!
        db.memoryDao().insert(actual.copy(content = "用户已经修改原记录"))
        assertFalse(reader.revalidate(ticket, 31))
    }

    @Test fun actualModelToolArgumentsReplayThroughProductionStoreWithoutCertifyingSemantics() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val data = org.json.JSONArray(instrumentation.context.assets.open("c5-main-dialogue-replay.json").bufferedReader().use { it.readText() })
        val report = org.json.JSONArray()
        var accepted = 0
        var rejected = 0
        for (i in 0 until data.length()) {
            val item = data.getJSONObject(i)
            fixture { db, store, module ->
                val source = ChronicleSourceEntity.message(MessageEntity(item.getString("id"), "c5-fixture", "user",
                    item.getString("body"), source = "CHAT_WINDOW", createdAt = 100), 200)!!
                db.chronicleSourceDao().retainSource(source)
                val handler = module.contributions().single { it.definition.name == "memory_remember" }.handler
                val attempts = item.getJSONArray("arguments")
                val results = org.json.JSONArray()
                for (j in 0 until attempts.length()) {
                    val args = attempts.getJSONObject(j)
                    assertEquals(source.id, args.getString("source_id"))
                    val result = handler(args.keys().asSequence().associateWith { args.get(it) })
                    if (result.success) {
                        accepted++
                        val reply = org.json.JSONObject(result.data)
                        assertEquals(args.optString("scope", "personal"), reply.getString("scope"))
                        val id = reply.getString("derivation_id")
                        store.prepare(id)?.let { assertEquals("APPLIED", store.commit(it, System.currentTimeMillis())) }
                        val entry = db.memoryDerivationDao().get(id)!!
                        assertEquals("APPLIED", entry.state)
                        assertEquals(item.getString("body"), db.chronicleSourceDao().chronicle(entry.sourceId)!!.body)
                        assertEquals("MODEL_CLASSIFIED", db.memoryDao().getById(entry.memoryId)!!.origin)
                    } else rejected++
                    results.put(org.json.JSONObject().put("accepted", result.success).put("error", result.errorMessage))
                }
                assertTrue("new automatic path remains gated", module.evidenceCandidates("记录", 3).isEmpty())
                report.put(org.json.JSONObject().put("id", item.getString("id")).put("attempts", results))
            }
        }
        assertTrue(accepted > 0); assertTrue(rejected > 0)
        java.io.File(instrumentation.targetContext.cacheDir, "c5-model-replay.json").writeText(
            org.json.JSONObject().put("accepted", accepted).put("rejected", rejected).put("cases", report)
                .put("semanticCertified", false).put("cloudCalls", 0).toString())
    }
}
