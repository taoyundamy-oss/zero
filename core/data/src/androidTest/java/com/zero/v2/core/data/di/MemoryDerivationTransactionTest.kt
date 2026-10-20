package com.zero.v2.core.data.di

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.common.MemoryType
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.*
import com.zero.v2.core.data.store.MemoryDerivationStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MemoryDerivationTransactionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        ZeroDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())
    private fun fixture(block: suspend (ZeroDatabase, MemoryDerivationStore) -> Unit) = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).addCallback(ChroniclePrivacy).build()
        try { block(db, MemoryDerivationStore(db)) } finally { db.close() }
    }
    private suspend fun source(db: ZeroDatabase, id: String = "s", body: String = "我喜欢清淡饭菜") =
        ChronicleSourceEntity.message(MessageEntity(id, "c", "user", body, createdAt = 10), 11)!!.also {
            db.chronicleSourceDao().retainSource(it)
        }
    private fun proposal(s: ChronicleSourceEntity, key: String = "饮食", scope: String = "personal") =
        MemoryDerivationEntity.create(s.id, s.body, s.body, MemoryType.PREFERENCE, "EXPLICIT", scope, key, .8f, now = 20)

    @Test fun duplicateRetriesAndConcurrentCommitMaterializeOnce() = fixture { db, store ->
        val d = proposal(source(db)); store.submit(d)
        assertEquals(d, store.submit(d.copy(createdAt = 100)))
        val p = store.prepare(d.id)!!
        val results = coroutineScope { listOf(async { store.commit(p, 30) }, async { store.commit(p, 31) }).awaitAll() }
        assertEquals(1, results.count { it == "APPLIED" })
        assertEquals(1, db.memoryDao().observeAll().first().size)
        assertEquals("CANDIDATE", store.current("personal", "饮食", 40).state)
        assertFalse(db.memoryDao().recallBlocked(d.memoryId))
        assertEquals("MODEL_CLASSIFIED", db.memoryDao().getById(d.memoryId)!!.origin)
        assertTrue(db.memoryDao().importantMemories(40).isEmpty())
    }

    @Test fun preparedThenSourceDeletedCannotResurrect() = fixture { db, store ->
        val s = source(db); val d = proposal(s); store.submit(d); val p = store.prepare(d.id)!!
        db.chronicleSourceDao().redact(ChronicleDeletionEntity("message", s.anchorId, 25))
        assertEquals("GONE", store.commit(p, 30))
        assertNull(db.memoryDao().getById(d.memoryId)); assertTrue(db.memoryDerivationDao().all().isEmpty())
        assertEquals("EMPTY", store.current("personal", "饮食", 31).state)
        assertFalse(db.chronicleSourceDao().retainSource(s))
    }

    @Test fun pendingResultForgetPurgesPayloadAndLateCommit() = fixture { db, store ->
        val d = proposal(source(db)); store.submit(d); val p = store.prepare(d.id)!!
        db.memoryExtractionJobDao().forgetById(d.memoryId, 25)
        assertEquals("GONE", store.commit(p, 30))
        assertTrue(db.memoryDerivationDao().all().isEmpty()); assertTrue(db.chronicleSourceDao().all().isEmpty())
    }

    @Test fun keywordForgetIncludesUnmaterializedPayload() = fixture { db, store ->
        val d = proposal(source(db)); store.submit(d)
        db.memoryExtractionJobDao().forgetMatching("饮食", 25)
        assertNull(store.prepare(d.id)); assertTrue(db.chronicleSourceDao().all().isEmpty())
    }

    @Test fun pendingJobSurvivesNewWorkerWithoutClassifier() = fixture { db, store ->
        val d = proposal(source(db)); store.submit(d); store.prepare(d.id)
        assertEquals(1, MemoryDerivationStore(db).drain())
        assertEquals("APPLIED", db.memoryDerivationDao().get(d.id)!!.state)
        assertEquals(0, store.drain())
    }

    @Test fun forgedPreparedAndEditedExistingRecordAreRejected() = fixture { db, store ->
        val d = proposal(source(db)); store.submit(d); val p = store.prepare(d.id)!!
        try { store.commit(p.copy(memory = p.memory.copy(content = "伪造")), 30); fail() } catch (_: IllegalArgumentException) { }
        assertNull(db.memoryDao().getById(d.memoryId))
        db.memoryDao().insert(p.memory.copy(content = "人工修订"))
        assertEquals("STALE", store.commit(p, 31))
        assertEquals("人工修订", db.memoryDao().getById(d.memoryId)!!.content)
        assertTrue(db.memoryDao().recallBlocked(d.memoryId))
    }

    @Test fun conflictsAreScopeLocalAndRequireExplicitDecision() = fixture { db, store ->
        val a = proposal(source(db, "a", "我喜欢清淡饭菜")); val b = proposal(source(db, "b", "我喜欢重口饭菜"))
        val other = proposal(source(db, "other", "我喜欢甜食"), scope = "work")
        listOf(a, b, other).forEach { store.submit(it) }; assertEquals(3, store.drain())
        assertEquals("CONFLICT", store.current("personal", "饮食", 40).state)
        assertNull(store.current("personal", "饮食", 41).selectedId)
        assertTrue(db.memoryDao().recallBlocked(a.memoryId)); assertTrue(db.memoryDao().recallBlocked(b.memoryId))
        assertEquals("CANDIDATE", store.current("work", "饮食", 40).state)
        val conflict = db.memoryDao().observeConflicts().first().single()
        assertTrue(db.memoryDao().resolveConflict(conflict.id, "LEFT", 42))
        assertEquals("CANDIDATE", store.current("personal", "饮食", 43).state)
        assertFalse(db.memoryDao().recallBlocked(conflict.leftId))
    }

    @Test fun deletionInvalidatesPreviouslyCachedCandidate() = fixture { db, store ->
        val s = source(db); val d = proposal(s); store.submit(d); store.drain()
        assertFalse(db.memoryDao().recallBlocked(d.memoryId))
        db.chronicleSourceDao().redact(ChronicleDeletionEntity("message", s.anchorId, 50))
        assertNotNull(db.memoryDao().getById(d.memoryId)) // history remains, consumption stops
        assertTrue(db.memoryDao().recallBlocked(d.memoryId))
        assertTrue(db.memoryDao().recordCurrentRecall(listOf(d.memory(s)), 5).isEmpty())
        assertEquals("EMPTY", store.current("personal", "饮食", 51).state)
    }

    @Test fun actualClockExpiryAndSourcePointerTamperingBlockCachedView() = fixture { db, store ->
        val s = source(db); val now = System.currentTimeMillis()
        val d = proposal(s).let { MemoryDerivationEntity.create(it.sourceId, it.content, it.quote, it.type,
            it.basis, it.scope, it.claimKey, it.importance, validUntil = now - 10000, now = 20) }
        store.submit(d); store.drain(); store.current("personal", "饮食", now - 20000)
        assertTrue(db.memoryDao().recallBlocked(d.memoryId))
        val fresh = proposal(source(db, "fresh"), "another"); store.submit(fresh); store.drain()
        val m = db.memoryDao().getById(fresh.memoryId)!!
        db.memoryDao().insert(m.copy(sourceMessageId = "edited"))
        assertTrue(db.memoryDao().recallBlocked(fresh.memoryId))
    }

    @Test fun confirmationSourceForgetBlocksLateResultAndAllAliases() = fixture { db, store ->
        val parent = MemoryEntity("legacy", "用户旧笔记")
        db.memoryDao().insert(parent)
        val review = db.memoryReviewDao().propose(parent, "长期要求分段表达", MemoryType.DIRECTIVE, "personal", "显式确认", 10)!!
        assertTrue(db.memoryReviewDao().decide(review, true, 11))
        val source = db.chronicleSourceDao().all().single()
        val d = MemoryDerivationEntity.create(source.id, source.body, source.body, MemoryType.DIRECTIVE,
            "EXPLICIT", "personal", "表达规范", .8f, now = 20)
        store.submit(d); val p = store.prepare(d.id)!!
        db.memoryExtractionJobDao().forgetById(d.memoryId, 30)
        assertEquals("GONE", store.commit(p, 40))
        assertTrue(db.chronicleSourceDao().chronicleBlocked("confirmation", review, null, parent.id))
        assertFalse(db.chronicleSourceDao().retainSource(source))
        assertTrue(db.memoryDerivationDao().all().isEmpty())
    }

    @Test fun cancellationDoesNotConsumeQueueAndExactOlderSourceVersionIsPreserved() = fixture { db, store ->
        val old = source(db); val d = proposal(old); store.submit(d); val prepared = store.prepare(d.id)!!
        source(db, "s", "我现在喜欢酸味") // a distinct immutable version, never substituted into prepared input
        try {
            withContext(Job().apply { cancel() }) { store.commit(prepared, 30) }; fail()
        } catch (_: CancellationException) { }
        assertEquals("PENDING", db.memoryDerivationDao().get(d.id)!!.state)
        assertNull(db.memoryDao().getById(d.memoryId))
        assertEquals("APPLIED", store.commit(prepared, 31))
        assertEquals(old.body, db.memoryDao().getById(d.memoryId)!!.content)
    }

    @Test fun diskReopenRetainsPendingWorkAndRebuildsCurrentView() = runBlocking {
        val name = "c4-reopen-isolated"; context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, ZeroDatabase::class.java, name).addCallback(ChroniclePrivacy).build()
        val first = open()
        val d = try { proposal(source(first)).also { MemoryDerivationStore(first).submit(it) } } finally { first.close() }
        val second = open()
        try {
            val store = MemoryDerivationStore(second)
            assertEquals(1, store.drain()); assertEquals("CANDIDATE", store.current("personal", "饮食", 40).state)
            assertEquals(d.content, second.memoryDao().getById(d.memoryId)!!.content)
        } finally { second.close(); context.deleteDatabase(name) }
    }

    @Test fun migration36To37LeavesHistoryUsageAndConfigurationUntouched() {
        val name = "c4-migration-isolated"
        lateinit var before: Map<String, List<String>>
        helper.createDatabase(name, 36).use { d ->
            d.execSQL("INSERT INTO module_configs(module_id,config_json,updated_at) VALUES('keep','{}',42)")
            d.execSQL("INSERT INTO memories(id,content,level,importance,tier,type,status,confidence,reinforcement_count,created_at,access_count,last_accessed_at) VALUES('old','历史',0,0.8,1,'EVENT','ACTIVE',0.5,0,1,0,1)")
            d.execSQL("INSERT INTO usage_requests(id,startedAt,connectionId,profileId,model,kind,status,continuity,budgetSource,local,inputTokens,cachedTokens) VALUES('keep-usage',1,'c','p','m','stream','completed','append_only','observed',0,100,98)")
            before = rows(d)
        }
        helper.runMigrationsAndValidate(name, 37, true, *DatabaseModule.ALL_MIGRATIONS).use { d ->
            assertEquals(before, rows(d).filterKeys { it in before })
            d.query("SELECT content,origin FROM memories WHERE id='old'").use { assertTrue(it.moveToFirst()); assertEquals("历史", it.getString(0)); assertEquals("UNKNOWN", it.getString(1)) }
            d.query("SELECT updated_at FROM module_configs WHERE module_id='keep'").use { assertTrue(it.moveToFirst()); assertEquals(42L, it.getLong(0)) }
            for (t in listOf("memory_derivations", "memory_derivation_views")) d.query("SELECT COUNT(*) FROM $t").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        }
        context.deleteDatabase(name)
    }

    @Test fun oldestSupportedVersionReaches37() {
        val name = "c4-oldest-isolated"; helper.createDatabase(name, 5).close()
        helper.runMigrationsAndValidate(name, 37, true, *DatabaseModule.ALL_MIGRATIONS).close()
        context.deleteDatabase(name)
    }
    @Test fun retiringLegacyClassifierKeepsItsAuditAndHistoricalMemoryUnchanged() = fixture { db, _ ->
        val legacy = MemoryEntity("legacy-only", "旧事实不重写", origin = "UNKNOWN")
        db.memoryDao().insert(legacy)
        val job = MemoryExtractionJobEntity("old-job", "USER_FACT", sourceDigest = "待分类旧原文", uniqueKey = "old-key", createdAt = 10, updatedAt = 10)
        db.memoryExtractionJobDao().insertIfNew(job)
        assertEquals(1, db.memoryExtractionJobDao().retireUserFactSources(20))
        assertEquals(0, db.memoryExtractionJobDao().retireUserFactSources(21))
        val retired = db.memoryExtractionJobDao().getByIds(listOf(job.id)).single()
        assertEquals("SUPERSEDED", retired.state); assertEquals(job.sourceDigest, retired.sourceDigest)
        assertEquals(legacy, db.memoryDao().getById(legacy.id))
        assertTrue(db.memoryDerivationDao().all().isEmpty())
    }

    @Test fun rebuildingMissingConflictAuditIsIdempotent() = fixture { db, store ->
        val a = proposal(source(db, "one", "合成偏好甲")); val b = proposal(source(db, "two", "合成偏好乙"))
        store.submit(a); store.submit(b); store.drain()
        db.openHelper.writableDatabase.execSQL("DELETE FROM memory_conflicts")
        val first = store.current("personal", "饮食", 40)
        val second = store.current("personal", "饮食", 41)
        assertEquals("CONFLICT", first.state); assertEquals(first.version, second.version)
        assertTrue(db.memoryDao().recallBlocked(a.memoryId)); assertTrue(db.memoryDao().recallBlocked(b.memoryId))
        assertEquals(1, db.memoryDao().observeConflicts().first().size)
    }

    @Test fun delayedRecallRechecksConflictAndArchiveWithoutWaitingForCachedView() = fixture { db, store ->
        val source = source(db); val d = proposal(source)
        store.submit(d); store.drain()
        val memory = db.memoryDao().getById(d.memoryId)!!
        assertFalse(db.memoryDao().recallBlocked(memory.id))
        val other = MemoryEntity("other", "另一个待判断的记录")
        db.memoryDao().insert(other)
        val conflict = MemoryConflictEntity.candidate(memory, other, "EXPLICIT_REVIEW", 30)
        db.memoryDao().insertConflict(conflict)
        assertTrue(db.memoryDao().recallBlocked(memory.id))
        assertTrue(db.memoryDao().recordCurrentRecall(listOf(memory), 1).isEmpty())
        db.memoryDao().markConflict(conflict.id, "BOTH", 31)
        assertFalse(db.memoryDao().recallBlocked(memory.id))
        db.memoryDao().updateStatus(memory.id, com.zero.v2.core.common.MemoryStatus.ARCHIVED)
        assertTrue(db.memoryDao().recallBlocked(memory.id))
        assertTrue(db.memoryDao().recordCurrentRecall(listOf(memory), 1).isEmpty())
    }

    @Test fun viewFingerprintReadsBeyondFirstPageAndIgnoresRebuildClock() = fixture { db, store ->
        repeat(65) { n -> db.memoryDerivationDao().putView(MemoryDerivationViewEntity(
            "slot-${n.toString().padStart(3, '0')}", "personal", "CANDIDATE", null, 1, "version-a", 10)) }
        val before = store.viewFingerprint()
        val last = db.memoryDerivationDao().viewPage("slot-063", 64).single()
        db.memoryDerivationDao().putView(last.copy(rebuiltAt = 20))
        assertEquals(before, store.viewFingerprint())
        db.memoryDerivationDao().putView(last.copy(version = "version-b", rebuiltAt = 21))
        assertNotEquals(before, store.viewFingerprint())
    }

    private fun rows(db: androidx.sqlite.db.SupportSQLiteDatabase): Map<String, List<String>> {
        val names = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table','android_metadata') ORDER BY name").use { c ->
            buildList { while(c.moveToNext()) add(c.getString(0)) }
        }
        return names.associateWith { name ->
            db.query("SELECT * FROM `$name` ORDER BY rowid").use { c ->
                buildList { while(c.moveToNext()) add((0 until c.columnCount).joinToString("|") {
                    if (c.isNull(it)) "NULL" else if(c.getType(it) == 4) c.getBlob(it).joinToString(",") else c.getString(it)
                }) }
            }
        }
    }

    @Test fun durableWriteReceiptSeparatesPendingAppliedAndDeleted() = fixture { db, store ->
        val s = source(db); val d = proposal(s); store.submit(d)
        assertEquals("PENDING", store.writeReceipt(d.memoryId)?.queueState)
        assertNull(store.writeReceipt(d.memoryId)?.memory)
        store.drain()
        assertEquals("APPLIED", store.writeReceipt(d.memoryId)?.queueState)
        assertEquals("ACTIVE", store.writeReceipt(d.memoryId)?.memory?.status)
        db.chronicleSourceDao().redact(ChronicleDeletionEntity("message", s.anchorId, 40))
        assertNull(store.writeReceipt(d.memoryId))
    }
    @Test fun migration37To38PreservesLegacyMemoryAndProposalDefaults() {
        val name = "maintenance-38-isolated"
        try {
            helper.createDatabase(name, 37).use { d ->
                d.execSQL("INSERT INTO memories(id,content,level,importance,tier,type,status,confidence,reinforcement_count,created_at,access_count,last_accessed_at) VALUES('old','历史',0,0.8,1,'EVENT','ACTIVE',0.5,0,1,0,1)")
                d.execSQL("INSERT INTO memory_reviews(id,memoryId,baseVersion,proposedContent,proposedType,proposedScope,reason,state,evidenceKind,createdAt) VALUES('draft','old','base','提案','FACT','personal','人工','PENDING','USER_REVIEW_DRAFT',1)")
            }
            helper.runMigrationsAndValidate(name, 38, true, *DatabaseModule.ALL_MIGRATIONS).use { d ->
                d.query("SELECT editTimes,proposedOccurredAt,proposedValidFrom,proposedValidUntil,state FROM memory_reviews WHERE id='draft'").use {
                    assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
                    assertTrue(it.isNull(1) && it.isNull(2) && it.isNull(3)); assertEquals("PENDING", it.getString(4))
                }
                d.query("SELECT content,origin FROM memories WHERE id='old'").use {
                    assertTrue(it.moveToFirst()); assertEquals("历史", it.getString(0)); assertEquals("UNKNOWN", it.getString(1))
                }
            }
        } finally { context.deleteDatabase(name) }
    }

}
