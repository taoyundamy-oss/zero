package com.zero.v2.app.acceptance

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.data.DataExporter
import com.zero.v2.app.data.DataImporter
import com.zero.v2.core.common.MemoryType
import com.zero.v2.core.common.MemoryStatus
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.MemoryEntity
import com.zero.v2.core.trace.AgentTraceManager
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.json.JSONArray

@RunWith(AndroidJUnit4::class)
class BackupRestoreAcceptanceTest {
    @Test fun mergedRestoreCannotLeaveTwoActiveCharacters() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val file = File(context.cacheDir, "acceptance-backup-role-owner.json")
        try {
            db.characterDao().insert(com.zero.v2.core.data.entity.CharacterEntity("local-active", "本机当前角色", systemPrompt = "local", isActive = true))
            val importer = DataImporter(context, db.conversationDao(), db.messageDao(), db.characterDao(),
                db.memoryDao(), db.automationDao(), AgentTraceManager(db.agentTraceEventDao()), db)
            file.writeText(JSONObject().put("__format", "zero_backup").put("version", 2)
                .put("characters", JSONArray().put(JSONObject().put("id", "backup-active").put("isActive", true))).toString())
            assertTrue(importer.importFrom(Uri.fromFile(file)).isSuccess)
            assertEquals(2, db.characterDao().getAll().size)
            assertEquals(1, db.characterDao().getAll().count { it.isActive })
            assertEquals("local-active", db.characterDao().getActive()!!.id)
        } finally { file.delete(); db.close() }
    }
    @Test fun roundTripPreservesAllFiveDomainsIncludingSummaryAndAutomationExecutionMode() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val restored = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val file = File(context.cacheDir, "acceptance-backup-five-domains.json")
        try {
            val character = com.zero.v2.core.data.entity.CharacterEntity("owned-character", "角色", "描述", "prompt",
                isActive = true, createdAt = 10, updatedAt = 20)
            val conversation = com.zero.v2.core.data.entity.ConversationEntity("owned-conversation", "会话",
                character.id, 10, 20, 1, "已经压缩的历史摘要", "owned-message")
            val message = com.zero.v2.core.data.entity.MessageEntity("owned-message", conversation.id, "assistant",
                "正文", "[{\"id\":\"owned-tool\"}]", "owned-run", 31, "[{\"id\":\"owned-reference\"}]", 10,
                "AUTONOMOUS", "tick", "owned-trigger")
            val memory = MemoryEntity("owned-memory", "事实", sourceConversationId = conversation.id,
                status = MemoryStatus.ARCHIVED, createdAt = 10, lastAccessedAt = 20)
            val automation = com.zero.v2.core.data.entity.AutomationEntity("owned-automation", "定时任务", "描述", "{}",
                status = "PAUSED", execMode = "REACT", nextRunAt = 100, createdAt = 10)
            original.characterDao().insert(character)
            original.conversationDao().insert(conversation)
            original.messageDao().insertAll(listOf(message))
            original.memoryDao().insert(memory)
            original.automationDao().insert(automation)
            val exporter = DataExporter(context, original.conversationDao(), original.messageDao(), original.characterDao(),
                original.memoryDao(), original.automationDao(), AgentTraceManager(original.agentTraceEventDao()), original)
            val importer = DataImporter(context, restored.conversationDao(), restored.messageDao(), restored.characterDao(),
                restored.memoryDao(), restored.automationDao(), AgentTraceManager(restored.agentTraceEventDao()), restored)
            assertEquals(com.zero.v2.app.data.ExportStats(1,1,1,1,1), exporter.exportTo(Uri.fromFile(file)).getOrThrow())
            assertEquals(com.zero.v2.app.data.ImportStats(1,1,1,1,1,0), importer.importFrom(Uri.fromFile(file)).getOrThrow())
            assertEquals(character, restored.characterDao().getAll().single())
            assertEquals(conversation, restored.conversationDao().getAll().single())
            assertEquals(message, restored.messageDao().getByConversation(conversation.id).single())
            assertEquals(memory, restored.memoryDao().getById(memory.id))
            assertEquals(automation, restored.automationDao().getById(automation.id))
        } finally { file.delete(); original.close(); restored.close() }
    }
    @Test fun cancellationAtReadOrBeforeCommitCannotModifyTheDatabase() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val file = File(context.cacheDir, "acceptance-backup-cancel.json")
        try {
            val importer = DataImporter(context, db.conversationDao(), db.messageDao(), db.characterDao(),
                db.memoryDao(), db.automationDao(), AgentTraceManager(db.agentTraceEventDao()), db)
            file.writeText(JSONObject().put("__format", "zero_backup").put("version", 2)
                .put("characters", JSONArray().put(JSONObject().put("id", "must-not-commit"))).toString())
            for (target in listOf("正在读取备份", "正在事务恢复，请等待完成")) {
                val child = launch {
                    importer.importFrom(Uri.fromFile(file)) { phase ->
                        if (phase.label == target) coroutineContext[kotlinx.coroutines.Job]!!.cancel()
                    }
                }
                child.join()
                assertTrue(child.isCancelled)
                assertTrue(db.characterDao().getAll().isEmpty())
            }
        } finally { file.delete(); db.close() }
    }

    @Test fun cancellationBeforeWritingKeepsExistingTargetAndFutureVersionCannotMutateRows() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val file = File(context.cacheDir, "acceptance-backup-safe-target.json")
        try {
            val exporter = DataExporter(context, db.conversationDao(), db.messageDao(), db.characterDao(),
                db.memoryDao(), db.automationDao(), AgentTraceManager(db.agentTraceEventDao()), db)
            file.writeText("original owned bytes")
            val child = launch {
                exporter.exportTo(Uri.fromFile(file)) { phase ->
                    if (!phase.cancellable) coroutineContext[kotlinx.coroutines.Job]!!.cancel()
                }
            }
            child.join()
            assertTrue(child.isCancelled)
            assertEquals("original owned bytes", file.readText())
            val importer = DataImporter(context, db.conversationDao(), db.messageDao(), db.characterDao(),
                db.memoryDao(), db.automationDao(), AgentTraceManager(db.agentTraceEventDao()), db)
            file.writeText(JSONObject().put("__format", "zero_backup").put("version", 999)
                .put("characters", JSONArray().put(JSONObject().put("id", "future"))).toString())
            assertTrue(importer.importFrom(Uri.fromFile(file)).isFailure)
            assertTrue(db.characterDao().getAll().isEmpty())
        } finally { file.delete(); db.close() }
    }
    @Test fun invalidRowsAreCountedAndCannotInsertAnEmptyIdentity() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val file = File(context.cacheDir, "acceptance-backup-invalid.json")
        try {
            val importer = DataImporter(context, db.conversationDao(), db.messageDao(), db.characterDao(),
                db.memoryDao(), db.automationDao(), AgentTraceManager(db.agentTraceEventDao()), db)
            file.writeText(JSONObject().put("__format", "zero_backup").put("version", 2)
                .put("characters", JSONArray().put(JSONObject.NULL).put("not a record").put(JSONObject())
                    .put(JSONObject().put("id", "valid-owned-character"))).toString())
            val result = importer.importFrom(Uri.fromFile(file)).getOrThrow()
            assertEquals(3, result.skipped)
            assertEquals(1, result.characters)
            assertEquals(listOf("valid-owned-character"), db.characterDao().getAll().map { it.id })
        } finally { file.delete(); db.close() }
    }
    @Test fun importFailureRollsBackEarlierTables() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val file = File(context.cacheDir, "acceptance-backup-rollback.json")
        try {
            val local = com.zero.v2.core.data.entity.CharacterEntity("rollback-local", "本机当前角色", systemPrompt = "local", isActive = true)
            db.characterDao().insert(local)
            val importer = DataImporter(context, db.conversationDao(), db.messageDao(), db.characterDao(),
                db.memoryDao(), db.automationDao(), AgentTraceManager(db.agentTraceEventDao()), db)
            file.writeText(JSONObject().put("__format", "zero_backup").put("version", 1)
                .put("characters", JSONArray().put(JSONObject().put("id", "fixture-character").put("isActive", true)))
                .put("memories", JSONArray().put(JSONObject().put("id", "fixture-memory").put("content", "fixture"))).toString())
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_backup BEFORE INSERT ON memories " +
                "BEGIN SELECT RAISE(ABORT, 'fixture_failure'); END")
            assertTrue(importer.importFrom(Uri.fromFile(file)).isFailure)
            assertEquals(listOf(local), db.characterDao().getAll())
            assertEquals(local.id, db.characterDao().getActive()!!.id)
            assertNull(db.memoryDao().getById("fixture-memory"))
        } finally { file.delete(); db.close() }
    }

    @Test fun roundTripPreservesProvenanceStatusAndDeletionAcrossDatabases() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val restored = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val file = File(context.cacheDir, "acceptance-backup-roundtrip.json")
        try {
            val memory = MemoryEntity("kept", "带条件的原句", type = MemoryType.FACT, status = MemoryStatus.ARCHIVED,
                sourceConversationId = "source-chat", confidence = 0.7f, createdAt = 10, lastAccessedAt = 20)
            original.memoryDao().insert(memory)
            original.memoryExtractionJobDao().forgetById("deleted", 30)
            val exporter = DataExporter(context, original.conversationDao(), original.messageDao(), original.characterDao(),
                original.memoryDao(), original.automationDao(), AgentTraceManager(original.agentTraceEventDao()), original)
            assertTrue(exporter.exportTo(Uri.fromFile(file)).isSuccess)
            val importer = DataImporter(context, restored.conversationDao(), restored.messageDao(), restored.characterDao(),
                restored.memoryDao(), restored.automationDao(), AgentTraceManager(restored.agentTraceEventDao()), restored)
            assertTrue(importer.importFrom(Uri.fromFile(file)).isSuccess)
            assertEquals(memory, restored.memoryDao().getById(memory.id))
            assertTrue(restored.memoryDao().wasDeleted("deleted"))
            restored.memoryDao().insert(MemoryEntity("deleted", "stale"))
            assertNull(restored.memoryDao().getById("deleted"))
        } finally { file.delete(); original.close(); restored.close() }
    }

    @Test fun oldBackupCannotResurrectDeletedMemory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val file = File(context.cacheDir, "acceptance-backup.json")
        val traces = AgentTraceManager(db.agentTraceEventDao())
        try {
            val exporter = DataExporter(context, db.conversationDao(), db.messageDao(), db.characterDao(),
                db.memoryDao(), db.automationDao(), traces, db)
            val importer = DataImporter(context, db.conversationDao(), db.messageDao(), db.characterDao(),
                db.memoryDao(), db.automationDao(), traces, db)
            val memory = MemoryEntity("fixture", "只在隔离测试库中保存", type = MemoryType.FACT, sourceConversationId = "fixture-chat")
            db.conversationDao().insert(com.zero.v2.core.data.entity.ConversationEntity("conversation", "fixture"))
            db.memoryDao().insert(memory)
            assertTrue(exporter.exportTo(Uri.fromFile(file)).isSuccess)
            db.messageDao().insertAll(listOf(com.zero.v2.core.data.entity.MessageEntity("local-only", "conversation", "USER", "keep")))
            // Legacy format must also honor local deletion records.
            val legacy = JSONObject(file.readText()).put("version", 1)
            legacy.remove("deletedMemories")
            file.writeText(legacy.toString())
            db.memoryExtractionJobDao().forgetById(memory.id, 1)
            assertTrue(importer.importFrom(Uri.fromFile(file)).isSuccess)
            assertNull(db.memoryDao().getById(memory.id))
            assertEquals("local-only", db.messageDao().getByConversation("conversation").single().id)
            assertEquals(1, db.conversationDao().getById("conversation")!!.messageCount)
        } finally { file.delete(); db.close() }
    }
}
