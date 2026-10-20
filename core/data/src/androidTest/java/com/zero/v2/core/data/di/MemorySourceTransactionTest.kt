package com.zero.v2.core.data.di

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.common.MemoryStatus
import com.zero.v2.core.common.MemoryType
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.MemoryEntity
import com.zero.v2.core.data.entity.MemoryExtractionJobEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemorySourceTransactionTest {
    @Test fun contextMemoryHonorsInclusiveStartAndExclusiveExpiry() = runBlocking {
        val db = database()
        try {
            val dao = db.memoryDao()
            val original = MemoryEntity("timed", "仅在有效期间发送", type = MemoryType.DIRECTIVE,
                origin = "USER_QUOTE", sourceMessageId = "synthetic", validFrom = 100, validUntil = 200)
            dao.insert(original)
            for (now in listOf(99L, 200L, 201L)) {
                assertTrue(dao.importantMemories(now).isEmpty())
                assertTrue(dao.currentMemories(listOf(original.id), now).isEmpty())
            }
            for (now in listOf(100L, 199L)) {
                assertEquals(listOf(original.id), dao.importantMemories(now).map { it.id })
                assertEquals(listOf(original.id), dao.currentMemories(listOf(original.id), now).map { it.id })
            }
            assertEquals(original.content, dao.getById(original.id)!!.content)
        } finally { db.close() }
    }

    @Test fun userConflictChoiceIsAtomicAndStaleChoiceCannotOverwriteEdit() = runBlocking {
        val db = database()
        try {
            val dao = db.memoryDao()
            val a = MemoryEntity("a", "上海", type = MemoryType.FACT)
            val b = a.copy(id = "b", content = "北京")
            dao.insertAll(listOf(a,b))
            val c = com.zero.v2.core.data.entity.MemoryConflictEntity.candidate(a,b,"CONTRADICTION",1)
            dao.insertConflict(c)
            assertTrue(dao.resolveConflict(c.id, "RIGHT", 2))
            assertFalse(dao.resolveConflict(c.id, "LEFT", 3))
            assertEquals("a", dao.getById("b")!!.supersedesId)
            assertEquals(MemoryStatus.ARCHIVED, dao.getById("a")!!.status)
            dao.insertAll(listOf(a,b))
            val changed = a.copy(revision = 2)
            val c2 = com.zero.v2.core.data.entity.MemoryConflictEntity.candidate(changed,b,"OUTDATED",4)
            dao.insertConflict(c2)
            assertFalse(dao.resolveConflict(c2.id, "LEFT", 5))
            assertEquals("STALE", dao.conflict(c2.id)!!.state)
            assertEquals(MemoryStatus.ACTIVE, dao.getById("b")!!.status)
        } finally { db.close() }
    }

    @Test fun forgettingOriginalDeletesNestedSummariesAndRejectsDelayedCompression() = runBlocking {
        val db = database()
        try {
            val dao = db.memoryDao()
            val a = MemoryEntity("original", "私有原文")
            val b = MemoryEntity("other", "无关原文")
            val first = MemoryEntity("summary-1", "摘要", type = MemoryType.INSIGHT)
            val second = first.copy(id = "summary-2", level = 2)
            dao.insertAll(listOf(a, b))
            assertTrue(dao.commitCompression(listOf(a), first))
            assertTrue(dao.commitCompression(listOf(first), second))
            assertEquals(listOf(a.id), dao.summarySources(first.id))
            assertEquals(3, db.memoryExtractionJobDao().forgetById(a.id, 10))
            listOf(a.id, first.id, second.id).forEach {
                assertNull(dao.getById(it)); assertTrue(dao.wasDeleted(it))
            }
            assertNotNull(dao.getById(b.id))
            assertFalse(dao.commitCompression(listOf(a), first.copy(id = "late")))
            dao.insert(first)
            assertNull(dao.getById(first.id))
        } finally { db.close() }
    }

    @Test fun compressionRejectsDirectiveRevisionChangeAndDeletedSummaryId() = runBlocking {
        val db = database()
        try {
            val dao = db.memoryDao()
            val source = MemoryEntity("source", "内容")
            val summary = MemoryEntity("summary", "摘要")
            dao.insert(source.copy(revision = 2))
            assertFalse(dao.commitCompression(listOf(source), summary))
            dao.insert(source.copy(type = MemoryType.DIRECTIVE))
            assertFalse(dao.commitCompression(listOf(source.copy(type = MemoryType.DIRECTIVE)), summary))
            dao.insert(source)
            db.memoryExtractionJobDao().forgetById(summary.id, 1)
            assertFalse(dao.commitCompression(listOf(source), summary))
            assertEquals(MemoryStatus.ACTIVE, dao.getById(source.id)!!.status)
        } finally { db.close() }
    }

    @Test fun duplicateCommitRejectsChangedSourcesAndIsIdempotent() = runBlocking {
        val db = database()
        try {
            val dao = db.memoryDao()
            val kept = MemoryEntity("kept", "same", type = MemoryType.FACT)
            val removed = kept.copy(id = "removed")
            dao.insertAll(listOf(kept, removed))
            dao.insert(removed.copy(revision = 2))
            assertFalse(dao.archiveDuplicate(kept, removed))
            assertEquals(MemoryStatus.ACTIVE, dao.getById(removed.id)!!.status)
            val latest = dao.getById(removed.id)!!
            assertTrue(dao.archiveDuplicate(kept, latest))
            assertFalse(dao.archiveDuplicate(kept, latest))
            assertEquals(2, dao.count())
            assertEquals(MemoryStatus.ACTIVE, dao.getById(kept.id)!!.status)
        } finally { db.close() }
    }

    private fun database() = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext, ZeroDatabase::class.java,
    ).build()

    @Test fun retirementPreservesUserQueueAndRejectsStaleAssistantCommit() = runBlocking {
        val db = database()
        try {
            val dao = db.memoryExtractionJobDao()
            val old = MemoryExtractionJobEntity("old", "AI_DEFERRED", sourceDigest = "assistant", uniqueKey = "old")
            val user = MemoryExtractionJobEntity("new", "USER_FACT", "chat", "message", "user", "new")
            dao.insertIfNew(old)
            dao.insertIfNew(user)
            assertEquals(1, dao.retireAssistantSources(1))
            assertEquals(0, dao.retireAssistantSources(2))
            assertEquals(listOf(user), dao.queryPendingUserFacts(Long.MAX_VALUE, 20))
            assertFalse(dao.completeBatch(listOf(old), listOf(MemoryEntity("bad", "pollution")), 3))
            assertNull(db.memoryDao().getById("bad"))
            assertEquals("SUPERSEDED", dao.getByIds(listOf("old")).single().state)
            assertTrue(dao.completeBatch(listOf(user), listOf(MemoryEntity("good", "user")), 4))
            assertEquals("SUCCEEDED", dao.getByIds(listOf("new")).single().state)
        } finally { db.close() }
    }

    @Test fun narrowQuarantineIsReversibleAndPreservesGenuinePreferences() = runBlocking {
        val db = database()
        try {
            val dao = db.memoryDao()
            val old = MemoryEntity("old", "用户偏好提醒类消息通过微信送达", type = MemoryType.FACT)
            dao.insertAll(listOf(old, old.copy(id = "interrupt", content = "用户可能随时发消息打断助手。"),
                old.copy(id = "explicit", confidence = 0.9f), old.copy(id = "sourced", sourceConversationId = "chat"),
                old.copy(id = "other", content = "我喜欢咖啡")))
            assertEquals(2, dao.quarantineReportedTemplateFacts())
            assertEquals(0, dao.quarantineReportedTemplateFacts())
            assertEquals(5, dao.count())
            listOf("explicit", "sourced", "other").forEach { assertEquals(MemoryStatus.ACTIVE, dao.getById(it)!!.status) }
            assertEquals(MemoryStatus.ARCHIVED, dao.getById("old")!!.status)
            dao.updateStatus("old", MemoryStatus.ACTIVE)
            assertEquals(old.content, dao.getById("old")!!.content)
        } finally { db.close() }
    }

    @Test fun graphBatchFiltersNotesBeforeApplyingLimit() = runBlocking {
        val db = database()
        try {
            val dao = db.memoryDao()
            repeat(55) { dao.insert(MemoryEntity("note-$it", "note", createdAt = 1)) }
            val fact = MemoryEntity("user-fact:message:hash", "我喜欢咖啡。", type = MemoryType.FACT,
                sourceConversationId = "chat", createdAt = 2)
            dao.insert(fact)
            assertEquals(listOf(fact), dao.getGroundedFactsSince(0, 1))
        } finally { db.close() }
    }
}
