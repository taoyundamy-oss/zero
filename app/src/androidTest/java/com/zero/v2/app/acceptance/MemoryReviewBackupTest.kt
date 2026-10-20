package com.zero.v2.app.acceptance

import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.data.DataExporter
import com.zero.v2.app.data.DataImporter
import com.zero.v2.core.common.MemoryStatus
import com.zero.v2.core.common.MemoryType
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.*
import com.zero.v2.core.trace.AgentTraceManager
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic data only. Exercise the actual URI exporter/importer and transactional Room store. */
class MemoryReviewBackupTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun db() = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java)
        .addCallback(com.zero.v2.core.data.di.ChroniclePrivacy).build()
    private fun exporter(db: ZeroDatabase) = DataExporter(context, db.conversationDao(), db.messageDao(),
        db.characterDao(), db.memoryDao(), db.automationDao(), AgentTraceManager(db.agentTraceEventDao()), db)
    private fun importer(db: ZeroDatabase) = DataImporter(context, db.conversationDao(), db.messageDao(),
        db.characterDao(), db.memoryDao(), db.automationDao(), AgentTraceManager(db.agentTraceEventDao()), db)
    private suspend fun proposal(db: ZeroDatabase, id: String, accept: Boolean? = null): String {
        val m = MemoryEntity(id, "合成原文$id", type = MemoryType.EVENT, origin = "UNKNOWN",
            occurredAt = 5, validFrom = 1, validUntil = 1000, revision = 3, scope = "阅读", createdAt = 8)
        db.memoryDao().insert(m)
        val p = db.memoryReviewDao().propose(m, "合成确认$id", MemoryType.PREFERENCE, "阅读", "用户复核", 10)!!
        if (accept != null) assertTrue(db.memoryReviewDao().decide(p, accept, 20))
        return p
    }
    private fun fixture(block: suspend (ZeroDatabase, ZeroDatabase, File) -> Unit) = runBlocking {
        val from = db(); val to = db(); val file = File(context.cacheDir, "p5-review-backup-test.json")
        try { block(from, to, file) } finally { from.close(); to.close(); file.delete() }
    }

    @Test fun fourStatesAndCompleteMetadataRoundTripWithoutReconfirming() = fixture { from, to, file ->
        proposal(from, "pending"); val accepted = proposal(from, "accepted", true)
        proposal(from, "rejected", false); val invalid = proposal(from, "invalid")
        from.memoryDao().updateStatus("invalid", MemoryStatus.ARCHIVED)
        assertFalse(from.memoryReviewDao().decide(invalid, true, 30))
        assertEquals(4, exporter(from).exportTo(Uri.fromFile(file)).getOrThrow().memoryReviews)
        assertEquals(6, JSONObject(file.readText()).getInt("version"))
        assertEquals(4, importer(to).importFrom(Uri.fromFile(file)).getOrThrow().memoryReviews)
        assertEquals(from.memoryReviewDao().observe().first(), to.memoryReviewDao().observe().first())
        assertEquals(from.memoryDao().observeAll().first().sortedBy { it.id }, to.memoryDao().observeAll().first().sortedBy { it.id })
        assertEquals(listOf("accepted"), to.memoryDao().summarySources("review:$accepted"))
        assertEquals(listOf("review:$accepted"), to.memoryDao().importantMemories(30).map { it.id })
        assertFalse(to.memoryReviewDao().decide(accepted, true, 40))
        assertEquals(0, importer(to).importFrom(Uri.fromFile(file)).getOrThrow().memoryReviews)
    }

    @Test fun oldPendingBackupCannotUndoLocalAcceptedOrRejectedDecision() = fixture { from, to, file ->
        val a = proposal(from, "a"); val b = proposal(from, "b")
        exporter(from).exportTo(Uri.fromFile(file)).getOrThrow()
        importer(to).importFrom(Uri.fromFile(file)).getOrThrow()
        to.memoryReviewDao().decide(a, true, 20); to.memoryReviewDao().decide(b, false, 20)
        val before = to.memoryReviewDao().observe().first()
        importer(to).importFrom(Uri.fromFile(file)).getOrThrow()
        assertEquals(before, to.memoryReviewDao().observe().first())
        assertEquals(MemoryStatus.ARCHIVED, to.memoryDao().getById("a")!!.status)
        assertNotNull(to.memoryDao().getById("review:$a"))
    }

    @Test fun localDeletionBlocksOldBackupIncludingRenamedResult() = fixture { from, to, file ->
        val id = proposal(from, "deleted-base", true)
        exporter(from).exportTo(Uri.fromFile(file)).getOrThrow()
        to.memoryExtractionJobDao().forgetById("deleted-base", 50)
        importer(to).importFrom(Uri.fromFile(file)).getOrThrow()
        assertNull(to.memoryDao().getById("deleted-base"))
        assertNull(to.memoryDao().getById("review:$id")); assertNull(to.memoryReviewDao().get(id))
        assertTrue(to.memoryDao().wasDeleted("review:$id"))
    }

    @Test fun restoredLineageProtectsDeletionAfterRestoreAndSecondImport() = fixture { from, to, file ->
        val id = proposal(from, "base", true)
        exporter(from).exportTo(Uri.fromFile(file)).getOrThrow()
        importer(to).importFrom(Uri.fromFile(file)).getOrThrow()
        assertEquals(2, to.memoryExtractionJobDao().forgetById("base", 50))
        importer(to).importFrom(Uri.fromFile(file)).getOrThrow()
        assertNull(to.memoryDao().getById("review:$id")); assertNull(to.memoryReviewDao().get(id))
    }

    @Test fun malformedAuditRollsBackMemoryAndOtherTables() = fixture { from, to, file ->
        proposal(from, "bad-audit", true)
        from.characterDao().insert(CharacterEntity("must-rollback", "合成角色", systemPrompt = "test"))
        exporter(from).exportTo(Uri.fromFile(file)).getOrThrow()
        val root = JSONObject(file.readText())
        root.getJSONArray("memoryReviews").getJSONObject(0).put("decidedAt", 21)
        file.writeText(root.toString())
        assertTrue(importer(to).importFrom(Uri.fromFile(file)).isFailure)
        assertEquals(0, to.memoryDao().count()); assertTrue(to.characterDao().getAll().isEmpty())
        assertTrue(to.memoryDao().allSources().isEmpty())
    }

    @Test fun missingAuditCannotAuthenticateImportedConfirmedMemory() = fixture { from, to, file ->
        proposal(from, "orphan-audit", true)
        exporter(from).exportTo(Uri.fromFile(file)).getOrThrow()
        val root = JSONObject(file.readText()).put("memoryReviews", org.json.JSONArray())
        file.writeText(root.toString())
        assertTrue(importer(to).importFrom(Uri.fromFile(file)).isFailure)
        assertEquals(0, to.memoryDao().count())
    }

    @Test fun restoredPendingSourceCannotOverwriteNewerLocalMessage() = fixture { from, to, file ->
        val c = ConversationEntity("conversation", "合成")
        val message = MessageEntity("source", c.id, "user", "只在阅读时偏好柔和灯光")
        from.conversationDao().insert(c); from.messageDao().insert(message)
        val memory = MemoryEntity("pending-source", message.content!!, sourceConversationId = c.id, sourceMessageId = message.id)
        from.memoryDao().insert(memory)
        val id = from.memoryReviewDao().propose(memory, memory.content, MemoryType.PREFERENCE, "阅读", "复核", 10)!!
        exporter(from).exportTo(Uri.fromFile(file)).getOrThrow()
        to.conversationDao().insert(c); to.messageDao().insert(message.copy(content = "已经更正"))
        importer(to).importFrom(Uri.fromFile(file)).getOrThrow()
        assertEquals("已经更正", to.memoryReviewDao().message(message.id)!!.content)
        assertEquals("INVALIDATED", to.memoryReviewDao().get(id)!!.state)
        assertFalse(to.memoryReviewDao().decide(id, true, 50))
    }

    @Test fun incomingAcceptanceCannotOverrideLocalRejection() = fixture { from, to, file ->
        val id = proposal(from, "local-wins")
        exporter(from).exportTo(Uri.fromFile(file)).getOrThrow()
        importer(to).importFrom(Uri.fromFile(file)).getOrThrow()
        to.memoryReviewDao().decide(id, false, 20)
        from.memoryReviewDao().decide(id, true, 30)
        exporter(from).exportTo(Uri.fromFile(file)).getOrThrow()
        importer(to).importFrom(Uri.fromFile(file)).getOrThrow()
        assertEquals("REJECTED", to.memoryReviewDao().get(id)!!.state)
        assertEquals(MemoryStatus.ACTIVE, to.memoryDao().getById("local-wins")!!.status)
        assertNull(to.memoryDao().getById("review:$id"))
    }

    @Test fun restoredAuditSurvivesDiskDatabaseReopen() = fixture { from, _, file ->
        val id = proposal(from, "disk-audit", true)
        exporter(from).exportTo(Uri.fromFile(file)).getOrThrow()
        val name = "p5-json-restored-audit-isolated.db"
        context.deleteDatabase(name)
        try {
            val target = Room.databaseBuilder(context, ZeroDatabase::class.java, name)
                .addCallback(com.zero.v2.core.data.di.ChroniclePrivacy).build()
            try { importer(target).importFrom(Uri.fromFile(file)).getOrThrow() } finally { target.close() }
            val reopened = Room.databaseBuilder(context, ZeroDatabase::class.java, name)
                .addCallback(com.zero.v2.core.data.di.ChroniclePrivacy).build()
            try {
                assertEquals(from.memoryReviewDao().get(id), reopened.memoryReviewDao().get(id))
                assertEquals(listOf("disk-audit"), reopened.memoryDao().summarySources("review:$id"))
                assertFalse(reopened.memoryReviewDao().decide(id, true, 50))
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun explicitDateChangesRoundTripWithAudit() = fixture { from, to, file ->
        val m = MemoryEntity("date-change", "旧事件", type = MemoryType.EVENT, occurredAt = 100, validFrom = 10)
        from.memoryDao().insert(m)
        val id = from.memoryReviewDao().propose(m, "人物关系", MemoryType.RELATIONSHIP, "personal", "用户修订", 1,
            MemoryReviewTimes(null, null, 5000))!!
        assertTrue(from.memoryReviewDao().decide(id, true, 2))
        exporter(from).exportTo(Uri.fromFile(file)).getOrThrow()
        importer(to).importFrom(Uri.fromFile(file)).getOrThrow()
        assertEquals(from.memoryReviewDao().get(id), to.memoryReviewDao().get(id))
        val result = checkNotNull(to.memoryDao().getById("review:$id"))
        assertNull(result.occurredAt); assertNull(result.validFrom); assertEquals(5000L, result.validUntil)
        assertEquals(100L, to.memoryDao().getById(m.id)?.occurredAt)
    }

    @Test fun legacyV5AuditRetainsItsIdentityAndInheritedDates() = fixture { from, to, file ->
        val id = proposal(from, "v5-compatible", true)
        exporter(from).exportTo(Uri.fromFile(file)).getOrThrow()
        val root = JSONObject(file.readText()).put("version", 5)
        val rows = root.getJSONArray("memoryReviews")
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            listOf("editTimes", "proposedOccurredAt", "proposedValidFrom", "proposedValidUntil").forEach { row.remove(it) }
        }
        file.writeText(root.toString())
        importer(to).importFrom(Uri.fromFile(file)).getOrThrow()
        assertEquals(from.memoryReviewDao().get(id), to.memoryReviewDao().get(id))
        assertEquals(from.memoryDao().getById("review:$id"), to.memoryDao().getById("review:$id"))
    }

}
