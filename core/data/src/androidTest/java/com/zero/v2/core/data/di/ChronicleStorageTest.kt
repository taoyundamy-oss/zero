package com.zero.v2.core.data.di

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Isolated synthetic databases only; no app preferences, usage ledger or provider requests. */
class ChronicleStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        ZeroDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())
    private fun database() = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java)
        .addCallback(ChroniclePrivacy).build()

    @Test fun migration35To36PreservesEveryOldTableAndStartsEmpty() {
        val name = "c3-migration-isolated"
        val before: Map<String, List<String>>
        helper.createDatabase(name, 35).apply {
            execSQL("INSERT INTO conversations(id,title,created_at,updated_at,message_count) VALUES('chat','合成',1,2,1)")
            execSQL("INSERT INTO messages(id,conversation_id,role,content,created_at,source) " +
                "VALUES('old-source','chat','user','迁移不复制此原文',3,'CHAT_WINDOW')")
            execSQL("INSERT INTO memories(id,content,level,importance,tier,type,status,confidence,reinforcement_count," +
                "created_at,access_count,last_accessed_at,embed_model) " +
                "VALUES('old','原记录',0,0.8,2,'EVENT','ACTIVE',0.5,0,123,0,123,'old-space')")
            execSQL("UPDATE memories SET embedding = X'0000803F' WHERE id = 'old'")
            execSQL("INSERT INTO module_configs(module_id,config_json,updated_at) VALUES('synthetic-config','{\"keep\":true}',4)")
            execSQL("INSERT INTO usage_requests(id,startedAt,connectionId,profileId,model,kind,status,continuity," +
                "budgetSource,local,inputTokens,cachedTokens) VALUES('usage',1,'c','p','m','stream','completed'," +
                "'append_only','observed',0,100,98)")
            before = rows(this)
            close()
        }
        helper.runMigrationsAndValidate(name, 36, true, *DatabaseModule.ALL_MIGRATIONS).use { db ->
            assertEquals(before, rows(db).filterKeys { it in before })
            for (table in listOf("chronicle_sources", "chronicle_deletions")) {
                db.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            }
            db.query("SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name LIKE 'chronicle_%'").use {
                it.moveToFirst(); assertEquals(5, it.getInt(0))
            }
        }
        context.deleteDatabase(name)
    }

    @Test fun oldestSupportedVersionCanReach36() {
        val name = "c3-oldest-isolated"
        helper.createDatabase(name, 5).close()
        helper.runMigrationsAndValidate(name, 36, true, *DatabaseModule.ALL_MIGRATIONS).close()
        context.deleteDatabase(name)
    }

    @Test fun explicitNewInputOnlyAndCaptureRollsBackWithMessage() = runBlocking {
        val db = database()
        try {
            db.conversationDao().insert(ConversationEntity("c", "合成"))
            db.messageDao().insert(MessageEntity("legacy", "c", "user", "历史载入"))
            assertTrue(db.chronicleSourceDao().all().isEmpty())
            try {
                db.withTransaction {
                    db.messageDao().insertWithSource(MessageEntity("rolled-back", "c", "user", "应回滚"))
                    error("rollback")
                }
            } catch (_: IllegalStateException) { }
            assertEquals(1, db.messageDao().count())
            assertTrue(db.chronicleSourceDao().all().isEmpty())
            val m = MessageEntity("fresh", "c", "user", "合成新输入", createdAt = 10)
            assertEquals(1, db.messageDao().appendTurn("c", listOf(m)))
            val source = db.chronicleSourceDao().all().single()
            assertEquals(0, db.messageDao().appendTurn("c", listOf(m)))
            assertEquals(source, db.chronicleSourceDao().all().single())
        } finally { db.close() }
    }

    @Test fun messageAndConversationDeletionPurgeCopiesAndBlockFutureCapture() = runBlocking {
        val db = database()
        try {
            db.conversationDao().insert(ConversationEntity("c", "合成"))
            val a = MessageEntity("a", "c", "user", "第一条")
            db.messageDao().insertWithSource(a)
            db.messageDao().insertWithSource(a.copy(id = "b"))
            db.messageDao().delete("a")
            assertTrue(db.chronicleSourceDao().transcriptDeleted("a", "c"))
            assertEquals(listOf("b"), db.chronicleSourceDao().all().map { it.anchorId })
            assertFalse(db.chronicleSourceDao().retainSource(ChronicleSourceEntity.message(a, 1)!!))
            db.conversationDao().delete("c")
            assertTrue(db.chronicleSourceDao().all().isEmpty())
            assertFalse(db.chronicleSourceDao().retainSource(ChronicleSourceEntity.message(a.copy(id = "renamed"), 1)!!))
        } finally { db.close() }
    }

    @Test fun forgettingMemoryPurgesSourceAndPreventsAliasedReExtractionWithoutEditingTranscript() = runBlocking {
        val db = database()
        try {
            db.conversationDao().insert(ConversationEntity("c", "合成"))
            val message = MessageEntity("source", "c", "user", "需要遗忘的合成事实")
            db.messageDao().insertWithSource(message)
            val m = MemoryEntity("m", message.content!!, sourceMessageId = message.id, sourceConversationId = "c")
            db.memoryDao().insert(m)
            assertEquals(1, db.memoryExtractionJobDao().forgetById("m", 100))
            assertTrue(db.chronicleSourceDao().all().isEmpty())
            assertEquals(message, db.messageDao().getByConversation("c").single())
            assertFalse(db.chronicleSourceDao().transcriptDeleted("source", "c"))
            db.memoryDao().insert(m.copy(id = "renamed"))
            assertNull(db.memoryDao().getById("renamed"))
        } finally { db.close() }
    }

    @Test fun confirmationIsCapturedOnlyAfterExplicitDecisionAndDeletionPurgesAuditCopy() = runBlocking {
        val db = database()
        try {
            val m = MemoryEntity("m", "合成旧记忆")
            db.memoryDao().insert(m)
            val id = db.memoryReviewDao().propose(m, "明确确认内容", com.zero.v2.core.common.MemoryType.DIRECTIVE,
                "personal", "复核", 1)!!
            assertTrue(db.chronicleSourceDao().all().isEmpty())
            assertTrue(db.memoryReviewDao().decide(id, true, 2))
            assertEquals("confirmation", db.chronicleSourceDao().all().single().kind)
            db.memoryExtractionJobDao().forgetById("review:$id", 3)
            assertTrue(db.chronicleSourceDao().all().isEmpty())
            assertTrue(db.chronicleSourceDao().chronicleBlocked("confirmation", id, null, m.id))
        } finally { db.close() }
    }

    @Test fun sourceRedactionRevokesCurrentAndDelayedRecallButPreservesHistory() = runBlocking {
        val db = database()
        try {
            db.conversationDao().insert(ConversationEntity("c", "合成"))
            db.messageDao().insertWithSource(MessageEntity("source", "c", "user", "合成要求"))
            val m = MemoryEntity("m", "合成要求", origin = "USER_QUOTE", sourceMessageId = "source",
                sourceConversationId = "c", importance = 1f, type = com.zero.v2.core.common.MemoryType.DIRECTIVE)
            db.memoryDao().insert(m)
            assertEquals(1, db.memoryDao().importantMemories(10).size)
            val fact = m.copy(id = "user-fact:source:abcdef0123456789", type = com.zero.v2.core.common.MemoryType.FACT,
                importance = 0.5f)
            db.memoryDao().insert(fact)
            db.userGraphDao().enqueueGraphSources()
            assertEquals(1, db.userGraphDao().pendingGraphJobs(Long.MAX_VALUE).size)
            db.messageDao().delete("source")
            assertTrue(db.memoryDao().importantMemories(10).isEmpty())
            assertTrue(db.memoryDao().currentMemories(listOf("m"), 10).isEmpty())
            assertTrue(db.memoryDao().recordCurrentRecall(listOf(m), 1).isEmpty())
            assertTrue(db.vectorIndexDao().sourcePage("memory", "", 10).isEmpty())
            assertEquals(0, db.vectorIndexDao().sourceCount("memory"))
            db.userGraphDao().enqueueGraphSources()
            assertTrue(db.userGraphDao().pendingGraphJobs(Long.MAX_VALUE).isEmpty())
            assertNull(db.userGraphDao().getAttributeSource(fact.id))
            assertNull(db.memoryDao().readEvidenceSnapshot("m")!!.source)
            assertEquals(m, db.memoryDao().getById("m"))
        } finally { db.close() }
    }

    @Test fun redactedConfirmationCannotBeAcceptedAgain() = runBlocking {
        val db = database()
        try {
            val m = MemoryEntity("m", "合成原文")
            db.memoryDao().insert(m)
            val id = db.memoryReviewDao().propose(m, "确认内容", com.zero.v2.core.common.MemoryType.FACT,
                "personal", "复核", 1)!!
            db.chronicleSourceDao().redact(ChronicleDeletionEntity("confirmation", id, 2))
            assertFalse(db.memoryReviewDao().decide(id, true, 3))
            assertNull(db.memoryDao().getById("review:$id"))
        } finally { db.close() }
    }

    @Test fun keywordForgetAlsoPurgesRawSourceWithoutDerivedMemory() = runBlocking {
        val db = database()
        try {
            db.conversationDao().insert(ConversationEntity("c", "合成"))
            repeat(3) { db.messageDao().insertWithSource(MessageEntity("raw$it", "c", "user", "敏感合成片段$it")) }
            assertEquals(0, db.memoryExtractionJobDao().forgetMatching("敏感", 10))
            assertTrue(db.chronicleSourceDao().all().isEmpty())
            repeat(3) { assertTrue(db.memoryDao().sourceRedacted("raw$it")) }
        } finally { db.close() }
    }

    @Test fun sourceVersionsPersistAcrossDatabaseReopen() = runBlocking {
        val name = "c3-source-reopen-isolated"
        context.deleteDatabase(name)
        fun disk() = Room.databaseBuilder(context, ZeroDatabase::class.java, name).addCallback(ChroniclePrivacy).build()
        val first = disk()
        val expected: List<ChronicleSourceEntity>
        try {
            val m = MessageEntity("m", "c", "user", "原文", createdAt = 1)
            first.conversationDao().insert(ConversationEntity("c", "合成"))
            first.messageDao().insertWithSource(m)
            first.chronicleSourceDao().retainSource(ChronicleSourceEntity.message(m.copy(content = "新版本"), 2)!!)
            expected = first.chronicleSourceDao().all()
        } finally { first.close() }
        val second = disk()
        try { assertEquals(expected, second.chronicleSourceDao().all()); assertEquals(2, expected.size) }
        finally { second.close(); context.deleteDatabase(name) }
    }

    @Test fun inputCanPersistWithoutTranscriptAndLaterTurnDoesNotDuplicateOrRetimeIt() = runBlocking {
        val db = database()
        try {
            val m = MessageEntity("before-model", "c", "user", "模型失败也要保留输入", createdAt = 1)
            assertTrue(com.zero.v2.core.data.store.ChronicleInputStore(db).capture(m))
            val snapshot = db.chronicleSourceDao().all().single()
            assertEquals(0, db.messageDao().count())
            db.conversationDao().insert(ConversationEntity("c", "合成"))
            db.messageDao().appendTurn("c", listOf(m))
            assertEquals(listOf(snapshot), db.chronicleSourceDao().all())
            assertEquals(1L, snapshot.recordedAt)
        } finally { db.close() }
    }

    @Test fun recaptureCannotUpgradeLegacyProvenanceOrReplaceFirstInput() = runBlocking {
        val db = database()
        try {
            fun packed(values: List<String?>) = values.joinToString("") { it?.let { s -> "${s.length}:$s" } ?: "-1:" }
            val m = MessageEntity("legacy-retry", "c", "user", "只保留首次输入", source = "CHAT_WINDOW", createdAt = 10)
            val new = ChronicleSourceEntity.message(m, 20)!!
            val old = new.copy(provenance = packed(listOf(m.role, m.source, m.systemSource, m.triggerId)))
            val bytes = packed(listOf(old.kind, old.anchorId, old.conversationId, old.parentMemoryId, old.body,
                old.provenance, old.recordedAt.toString())).toByteArray(Charsets.UTF_8)
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val legacy = old.copy(id = hash)
            db.chronicleSourceDao().retainSource(legacy)
            val store = com.zero.v2.core.data.store.ChronicleInputStore(db)
            assertFalse(store.capture(m))
            assertFalse(store.capture(m.copy(content = "不得自动替换正文")))
            db.conversationDao().insert(ConversationEntity("c", "isolated"))
            db.messageDao().insertWithSource(m)
            assertEquals(listOf(legacy), db.chronicleSourceDao().all())
            assertFalse(db.chronicleSourceDao().all().single().hasDirectUserProvenance())
        } finally { db.close() }
    }

    private fun rows(db: SupportSQLiteDatabase): Map<String, List<String>> {
        val names = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' " +
            "AND name NOT IN ('room_master_table','android_metadata') ORDER BY name").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        return names.associateWith { name ->
            db.query("SELECT * FROM `$name` ORDER BY rowid").use { c ->
                buildList { while (c.moveToNext()) add((0 until c.columnCount).joinToString("|") {
                    if (c.isNull(it)) "NULL" else if (c.getType(it) == 4) c.getBlob(it).joinToString(",") else c.getString(it)
                }) }
            }
        }
    }
}
