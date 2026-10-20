package com.zero.v2.services.retrieval

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.common.MemoryStatus
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.ConversationEntity
import com.zero.v2.core.data.entity.MemoryConflictEntity
import com.zero.v2.core.data.entity.MemoryEntity
import com.zero.v2.core.data.entity.MessageEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryEvidenceRoomTest {
    private val message = MessageEntity("source", "conversation", "user", "Only after confirmation.",
        source = "CHAT_WINDOW", createdAt = 1)
    private val memory = MemoryEntity("memory", message.content.orEmpty(), origin = "USER_QUOTE",
        sourceMessageId = message.id, sourceConversationId = message.conversationId, createdAt = 1)
    private val scope = EvidenceSupport.QueryScope(100)

    private fun fixture(block: suspend (ZeroDatabase, MemoryEvidenceReader) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        try {
            db.conversationDao().insert(ConversationEntity(message.conversationId, "Synthetic fixture"))
            db.messageDao().insert(message)
            db.memoryDao().insert(memory)
            block(db, MemoryEvidenceReader(db.memoryDao()))
        } finally { db.close() }
    }

    @Test fun snapshotReadsDoNotRewardOrRewriteMemory() = fixture { db, reader ->
        val before = db.memoryDao().getById(memory.id)
        val ticket = checkNotNull(reader.capture(memory.id).ticket)
        assertEquals(message.content, ticket.text)
        assertEquals(EvidenceSupport.Use.CERTAIN,
            reader.revalidate(ticket, EvidenceSupport.Use.CERTAIN, scope).use)
        assertEquals(before, db.memoryDao().getById(memory.id))
        assertEquals(message, db.messageDao().getByConversation(message.conversationId).single())
    }

    @Test fun sourceDeletionInvalidatesAnAlreadyCapturedTicket() = fixture { db, reader ->
        val ticket = checkNotNull(reader.capture(memory.id).ticket)
        db.messageDao().delete(message.id)
        assertEquals(EvidenceSupport.Use.NONE,
            reader.revalidate(ticket, EvidenceSupport.Use.CERTAIN, scope).use)
        assertNull(reader.capture(memory.id).ticket)
        assertNotNull(db.memoryDao().getById(memory.id))
    }

    @Test fun editedSourceAndMemoryRevisionInvalidateDelayedOutput() = fixture { db, reader ->
        val ticket = checkNotNull(reader.capture(memory.id).ticket)
        db.messageDao().updateExisting(message.copy(createdAt = 2))
        assertEquals(EvidenceSupport.Use.NONE,
            reader.revalidate(ticket, EvidenceSupport.Use.CERTAIN, scope).use)
        db.messageDao().updateExisting(message)
        db.memoryDao().insert(memory.copy(revision = memory.revision + 1))
        assertEquals(EvidenceSupport.Use.NONE,
            reader.revalidate(ticket, EvidenceSupport.Use.CERTAIN, scope).use)
    }

    @Test fun newConflictDowngradesAndReplacementSuppressesCurrentSupport() = fixture { db, reader ->
        val ticket = checkNotNull(reader.capture(memory.id).ticket)
        val other = memory.copy(id = "other", content = "Different statement")
        db.memoryDao().insert(other)
        val conflict = MemoryConflictEntity.candidate(memory, other, "conflict", 2)
        db.memoryDao().insertConflict(conflict)
        assertEquals(EvidenceSupport.Use.UNCERTAIN,
            reader.revalidate(ticket, EvidenceSupport.Use.CERTAIN, scope).use)
        assertEquals(EvidenceSupport.Use.NONE,
            reader.revalidate(ticket, EvidenceSupport.Use.NONE, scope).use)
        db.memoryDao().insert(other.copy(supersedesId = memory.id))
        assertEquals(EvidenceSupport.Use.NONE,
            reader.revalidate(ticket, EvidenceSupport.Use.CERTAIN, scope).use)
    }

    @Test fun archiveAllowsExplicitHistoryButDeletionNeverDoes() = fixture { db, reader ->
        val ticket = checkNotNull(reader.capture(memory.id).ticket)
        db.memoryDao().updateStatus(memory.id, MemoryStatus.ARCHIVED)
        assertEquals(EvidenceSupport.Use.NONE,
            reader.revalidate(ticket, EvidenceSupport.Use.CERTAIN, scope).use)
        assertEquals(EvidenceSupport.Use.CERTAIN,
            reader.revalidate(ticket, EvidenceSupport.Use.CERTAIN, scope.copy(history = true)).use)
        db.memoryDao().delete(memory.id)
        assertEquals(EvidenceSupport.Use.NONE,
            reader.revalidate(ticket, EvidenceSupport.Use.CERTAIN, scope.copy(history = true)).use)
    }

    @Test fun supportDecoderUsesAndroidJsonRuntime() {
        assertEquals(EvidenceSupport.Selection(listOf(0), listOf(1)),
            EvidenceSupport.decode("{\"certain\":[0],\"uncertain\":[1]}", 8))
        assertNull(EvidenceSupport.decode("{\"certain\":[0],\"uncertain\":[0]}", 8))
        assertNull(EvidenceSupport.decode("{\"certain\":[],\"uncertain\":[],\"extra\":1}", 8))
    }

    @Test fun tombstoneBlocksEvenAStalePhysicalRow() = fixture { db, reader ->
        val ticket = checkNotNull(reader.capture(memory.id).ticket)
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO memory_deletions (id, deletedAt) VALUES (?, ?)", arrayOf(memory.id, 2L))
        assertNotNull(db.memoryDao().getById(memory.id))
        assertNull(db.memoryDao().readEvidenceSnapshot(memory.id))
        assertEquals(EvidenceSupport.Use.NONE,
            reader.revalidate(ticket, EvidenceSupport.Use.CERTAIN, scope.copy(history = true)).use)
    }

    @Test fun persistedUnknownAndPartialQuoteAreNotCertified() = fixture { db, reader ->
        db.memoryDao().insert(memory.copy(origin = "UNKNOWN"))
        assertNull(reader.capture(memory.id).ticket)
        db.memoryDao().insert(memory.copy(content = "confirmation"))
        assertNull(reader.capture(memory.id).ticket)
        db.memoryDao().insert(memory)
        db.messageDao().updateExisting(message.copy(role = "assistant"))
        assertNull(reader.capture(memory.id).ticket)
    }

    @Test fun uniqueParagraphCarriesTheWholeMessageThroughRoom() = fixture { db, reader ->
        val body = "This rule only applies after approval.\n\nUse the blue folder.\n\nNever delete the originals."
        db.messageDao().updateExisting(message.copy(content = body))
        db.memoryDao().insert(memory.copy(content = "Use the blue folder."))
        val ticket = checkNotNull(reader.capture(memory.id).ticket)
        assertEquals(body, ticket.text)
        db.messageDao().updateExisting(message.copy(content = body.replace("after approval", "after written approval")))
        assertEquals(EvidenceSupport.Use.NONE,
            reader.revalidate(ticket, EvidenceSupport.Use.CERTAIN, scope).use)
    }

    @Test fun explicitConfirmationAuditIsReadAndRecheckedWithoutReplayingTheDecision() = fixture { db, reader ->
        val reviews = db.memoryReviewDao()
        val id = checkNotNull(reviews.propose(memory, "Keep original copies.", memory.type, "personal", "fixture", 2))
        check(reviews.decide(id, true, 3))
        val resultId = "review:$id"
        val before = db.memoryDao().getById(resultId)
        val ticket = checkNotNull(reader.capture(resultId).ticket)
        assertEquals("USER_CONFIRMATION", ticket.evidenceKind)
        assertEquals(EvidenceSupport.Use.CERTAIN,
            reader.revalidate(ticket, EvidenceSupport.Use.CERTAIN, scope).use)
        assertEquals(before, db.memoryDao().getById(resultId))
        db.openHelper.writableDatabase.execSQL("UPDATE memory_reviews SET state = 'REJECTED' WHERE id = ?", arrayOf(id))
        assertEquals(EvidenceSupport.Use.NONE,
            reader.revalidate(ticket, EvidenceSupport.Use.CERTAIN, scope).use)
    }

    @Test fun confirmationCannotOutliveItsDeletedParentOrMissingDependency() = fixture { db, reader ->
        val reviews = db.memoryReviewDao()
        val id = checkNotNull(reviews.propose(memory, "A preference.", memory.type, "personal", "fixture", 2))
        check(reviews.decide(id, true, 3))
        val resultId = "review:$id"
        val ticket = checkNotNull(reader.capture(resultId).ticket)
        db.openHelper.writableDatabase.execSQL("DELETE FROM memory_sources WHERE summaryId = ?", arrayOf(resultId))
        assertNull(reader.capture(resultId).ticket)
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO memory_sources (summaryId, sourceId) VALUES (?, ?)", arrayOf(resultId, memory.id))
        db.memoryDao().delete(memory.id)
        assertEquals(EvidenceSupport.Use.NONE,
            reader.revalidate(ticket, EvidenceSupport.Use.CERTAIN, scope.copy(history = true)).use)
    }
}
