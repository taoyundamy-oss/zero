package com.zero.v2.app.acceptance

import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.data.DataExporter
import com.zero.v2.app.data.DataImporter
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.di.ChroniclePrivacy
import com.zero.v2.core.data.entity.*
import com.zero.v2.core.trace.AgentTraceManager
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChronicleBackupTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun db() = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).addCallback(ChroniclePrivacy).build()
    private fun exporter(d: ZeroDatabase) = DataExporter(context, d.conversationDao(), d.messageDao(),
        d.characterDao(), d.memoryDao(), d.automationDao(), AgentTraceManager(d.agentTraceEventDao()), d)
    private fun importer(d: ZeroDatabase) = DataImporter(context, d.conversationDao(), d.messageDao(),
        d.characterDao(), d.memoryDao(), d.automationDao(), AgentTraceManager(d.agentTraceEventDao()), d)
    private fun fixture(block: suspend (ZeroDatabase, ZeroDatabase, File) -> Unit) = runBlocking {
        val from = db(); val to = db(); val file = File(context.cacheDir, "c3-isolated-backup.json")
        try { block(from, to, file) } finally { from.close(); to.close(); file.delete() }
    }
    private suspend fun seed(d: ZeroDatabase): MemoryEntity {
        d.conversationDao().insert(ConversationEntity("c", "合成来源"))
        val m = MessageEntity("source", "c", "user", "合成事实原文", createdAt = 10, source = "CHAT_WINDOW")
        d.messageDao().insertWithSource(m)
        return MemoryEntity("memory", m.content!!, origin = "USER_QUOTE", sourceMessageId = m.id,
            sourceConversationId = m.conversationId, createdAt = 11).also { d.memoryDao().insert(it) }
    }

    @Test fun v4SourcesAndConfirmationRoundTripIdempotently() = fixture { from, to, file ->
        val m = seed(from)
        val id = from.memoryReviewDao().propose(m, "确认偏好", com.zero.v2.core.common.MemoryType.PREFERENCE,
            "personal", "用户明确复核", 12)!!
        from.memoryReviewDao().decide(id, true, 13)
        assertEquals(2, exporter(from).exportTo(Uri.fromFile(file)).getOrThrow().chronicleSources)
        assertEquals(6, JSONObject(file.readText()).getInt("version"))
        assertEquals(2, importer(to).importFrom(Uri.fromFile(file)).getOrThrow().chronicleSources)
        assertEquals(from.chronicleSourceDao().all(), to.chronicleSourceDao().all())
        assertEquals(0, importer(to).importFrom(Uri.fromFile(file)).getOrThrow().chronicleSources)
        assertEquals(from.memoryReviewDao().get(id), to.memoryReviewDao().get(id))
    }

    @Test fun localForgetBlocksOldRawSourceAndRenamedMemory() = fixture { from, to, file ->
        seed(from); exporter(from).exportTo(Uri.fromFile(file)).getOrThrow()
        importer(to).importFrom(Uri.fromFile(file)).getOrThrow()
        to.memoryExtractionJobDao().forgetById("memory", 20)
        val root = JSONObject(file.readText())
        root.getJSONArray("memories").getJSONObject(0).put("id", "renamed")
        file.writeText(root.toString())
        importer(to).importFrom(Uri.fromFile(file)).getOrThrow()
        assertTrue(to.chronicleSourceDao().all().isEmpty())
        assertNull(to.memoryDao().getById("renamed"))
        assertEquals(1, to.messageDao().count()) // User did not request deleting the conversation text.
        exporter(to).exportTo(Uri.fromFile(file)).getOrThrow()
        assertEquals(0, JSONObject(file.readText()).getJSONArray("chronicleSources").length())
    }

    @Test fun preMigrationDeletionWithoutLocalPointerUsesBackupBinding() = fixture { from, to, file ->
        seed(from); exporter(from).exportTo(Uri.fromFile(file)).getOrThrow()
        to.memoryExtractionJobDao().forgetById("memory", 20)
        importer(to).importFrom(Uri.fromFile(file)).getOrThrow()
        assertTrue(to.chronicleSourceDao().all().isEmpty())
        assertTrue(to.memoryDao().sourceRedacted("source"))
        assertNull(to.memoryDao().getById("memory"))
        assertEquals("合成事实原文", to.messageDao().getByConversation("c").single().content)
    }

    @Test fun deletedConversationCannotBeRevivedByOldBackupOrAnotherVersion() = fixture { from, to, file ->
        seed(from); exporter(from).exportTo(Uri.fromFile(file)).getOrThrow()
        importer(to).importFrom(Uri.fromFile(file)).getOrThrow()
        to.conversationDao().delete("c")
        importer(to).importFrom(Uri.fromFile(file)).getOrThrow()
        assertNull(to.conversationDao().getById("c"))
        assertTrue(to.chronicleSourceDao().all().isEmpty())
        assertEquals(0, to.messageDao().count())
    }

    @Test fun importedDeletionPurgesLocalCopyAndWinsAgainstLaterOldBackup() = fixture { from, to, file ->
        seed(from); exporter(from).exportTo(Uri.fromFile(file)).getOrThrow()
        val old = file.readText()
        importer(to).importFrom(Uri.fromFile(file)).getOrThrow()
        from.messageDao().delete("source")
        exporter(from).exportTo(Uri.fromFile(file)).getOrThrow()
        importer(to).importFrom(Uri.fromFile(file)).getOrThrow()
        assertTrue(to.chronicleSourceDao().all().isEmpty())
        file.writeText(old)
        importer(to).importFrom(Uri.fromFile(file)).getOrThrow()
        assertTrue(to.chronicleSourceDao().all().isEmpty())
        assertNull(to.memoryDao().readEvidenceSnapshot("memory")!!.source)
    }

    @Test fun v3TranscriptRestoreDoesNotBackfillChronicle() = fixture { from, to, file ->
        seed(from); exporter(from).exportTo(Uri.fromFile(file)).getOrThrow()
        val root = JSONObject(file.readText()).put("version", 3)
        root.remove("chronicleSources"); root.remove("chronicleDeletions")
        file.writeText(root.toString())
        importer(to).importFrom(Uri.fromFile(file)).getOrThrow()
        assertEquals(1, to.messageDao().count())
        assertTrue(to.chronicleSourceDao().all().isEmpty())
    }

    @Test fun damagedSourceRejectsBackupBeforeAnyDomainChanges() = fixture { from, to, file ->
        seed(from)
        from.characterDao().insert(CharacterEntity("rollback", "合成", systemPrompt = "test"))
        exporter(from).exportTo(Uri.fromFile(file)).getOrThrow()
        val root = JSONObject(file.readText())
        root.getJSONArray("chronicleSources").getJSONObject(0).put("body", "被修改的正文")
        file.writeText(root.toString())
        assertTrue(importer(to).importFrom(Uri.fromFile(file)).isFailure)
        assertTrue(to.characterDao().getAll().isEmpty())
        assertTrue(to.chronicleSourceDao().all().isEmpty())
        assertEquals(0, to.memoryDao().count())
    }

    @Test fun localFirstCaptureAndTranscriptWinAgainstOlderBackup() = fixture { from, to, file ->
        seed(from); seed(to)
        val before = to.chronicleSourceDao().all()
        to.messageDao().updateExisting(to.messageDao().getByConversation("c").single().copy(content = "本机更新"))
        exporter(from).exportTo(Uri.fromFile(file)).getOrThrow()
        importer(to).importFrom(Uri.fromFile(file)).getOrThrow()
        assertEquals(before, to.chronicleSourceDao().all())
        assertEquals("本机更新", to.messageDao().getByConversation("c").single().content)
    }
}
