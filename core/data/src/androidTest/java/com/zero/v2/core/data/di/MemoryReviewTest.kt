package com.zero.v2.core.data.di

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.zero.v2.core.common.MemoryStatus
import com.zero.v2.core.common.MemoryType
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.junit.Assert.*
import org.junit.Test

/** All data synthetic, database names isolated from the application. */
class MemoryReviewTest {
    private suspend fun ZeroDatabase.use(block: suspend (ZeroDatabase) -> Unit) {
        try { block(this) } finally { close() }
    }
    private fun database() = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(), ZeroDatabase::class.java).build()
    private val original = MemoryEntity("old", "阅读时偏好柔和灯光", type = MemoryType.EVENT)

    @Test fun draftAndRejectNeverChangeMemoryAndCannotBeRegenerated() = runBlocking {
        database().use { db ->
            db.memoryDao().insert(original)
            val d = db.memoryReviewDao()
            val id = d.propose(original, original.content, MemoryType.PREFERENCE, "reading", "分类复核", 1)!!
            assertEquals(original, d.memory(original.id))
            assertTrue(d.decide(id, false, 2))
            assertEquals(id, d.propose(original, original.content, MemoryType.PREFERENCE, "reading", "再次", 3))
            assertEquals("REJECTED", d.get(id)!!.state)
            assertFalse(d.decide(id, true, 4))
            assertEquals(original, d.memory(original.id))
        }
    }

    @Test fun confirmationCreatesAuditableVersionAndDeletionRemovesDerivatives() = runBlocking {
        database().use { db ->
            db.memoryDao().insert(original)
            val d = db.memoryReviewDao()
            val id = d.propose(original, "以后阅读时请用柔和灯光", MemoryType.DIRECTIVE, "reading", "用户确认", 1)!!
            assertTrue(d.decide(id, true, 2))
            assertFalse(d.decide(id, true, 3))
            val result = d.memory("review:$id")!!
            assertEquals("USER_CONFIRMED", result.origin)
            assertNull(result.sourceMessageId)
            assertEquals(original.id, result.supersedesId)
            assertEquals(MemoryStatus.ARCHIVED, d.memory(original.id)!!.status)
            assertEquals(original.content, d.memory(original.id)!!.content)
            assertEquals(listOf(result.id), db.memoryDao().importantMemories(3).map { it.id })
            db.memoryExtractionJobDao().forgetById(original.id, 4)
            assertNull(d.memory(result.id)); assertNull(d.get(id))
            assertFalse(d.decide(id, true, 5))
        }
    }

    @Test fun changedOrDeletedMemoryInvalidatesPendingProposal() = runBlocking {
        for (delete in listOf(false, true)) database().use { db ->
            db.memoryDao().insert(original)
            val d = db.memoryReviewDao()
            val id = d.propose(original, original.content, MemoryType.PREFERENCE, "personal", "复核", 1)!!
            if (delete) db.memoryExtractionJobDao().forgetById(original.id, 2)
            else db.memoryDao().insert(original.copy(content = "更正后的内容"))
            assertFalse(d.decide(id, true, 3))
            assertNull(d.memory("review:$id"))
        }
    }

    @Test fun sourceChangeInvalidatesProposal() = runBlocking {
        database().use { db ->
            db.openHelper.writableDatabase.execSQL("INSERT INTO conversations(id,title,created_at,updated_at,message_count) VALUES('c','合成',1,1,0)")
            val message = MessageEntity("msg", "c", "user", "阅读时偏好柔和灯光")
            db.messageDao().insert(message)
            val m = original.copy(sourceMessageId = "msg", sourceConversationId = "c")
            db.memoryDao().insert(m)
            val d = db.memoryReviewDao()
            val id = d.propose(m, m.content, MemoryType.PREFERENCE, "personal", "复核", 1)!!
            db.messageDao().insert(message.copy(content = "已更正"))
            assertFalse(d.decide(id, true, 2))
            assertEquals("INVALIDATED", d.get(id)!!.state)
        }
    }

    @Test fun keywordDeletionAlsoRemovesRewordedConfirmedVersion() = runBlocking {
        database().use { db ->
            db.memoryDao().insert(original)
            val d = db.memoryReviewDao()
            val id = d.propose(original, "晚上请打开台灯", MemoryType.DIRECTIVE, "阅读", "明确更正", 1)!!
            assertTrue(d.decide(id, true, 2))
            assertEquals(2, db.memoryExtractionJobDao().forgetMatching("柔和灯光", 3))
            assertNull(d.memory("review:$id")); assertNull(d.get(id))
            assertTrue(d.deleted("review:$id"))
        }
    }

    @Test fun competingConfirmationCommitsOnlyOnce() = runBlocking {
        database().use { db ->
            db.memoryDao().insert(original)
            val d = db.memoryReviewDao()
            val id = d.propose(original, original.content, MemoryType.PREFERENCE, "personal", "复核", 1)!!
            val results = kotlinx.coroutines.coroutineScope {
                listOf(async { d.decide(id, true, 2) },
                    async { d.decide(id, true, 2) }).map { it.await() }
            }
            assertEquals(1, results.count { it })
            assertEquals(2, db.memoryDao().count())
        }
    }

    @Test fun deletingConfirmedVersionAlsoRemovesItsProposalCopy() = runBlocking {
        database().use { db ->
            db.memoryDao().insert(original)
            val d = db.memoryReviewDao()
            val id = d.propose(original, original.content, MemoryType.PREFERENCE, "personal", "复核", 1)!!
            d.decide(id, true, 2)
            db.memoryExtractionJobDao().forgetById("review:$id", 3)
            assertNull(d.get(id)); assertFalse(d.decide(id, true, 4))
        }
    }

    @Test fun reopenKeepsDraftWithoutChangingHistory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "p5-review-isolated-test.db"
        context.deleteDatabase(name)
        try {
            var id = ""
            Room.databaseBuilder(context, ZeroDatabase::class.java, name).build().use { db ->
                db.memoryDao().insert(original)
                id = db.memoryReviewDao().propose(original, original.content, MemoryType.PREFERENCE, "personal", "复核", 1)!!
            }
            Room.databaseBuilder(context, ZeroDatabase::class.java, name).build().use { db ->
                assertEquals("PENDING", db.memoryReviewDao().get(id)!!.state)
                assertEquals(original, db.memoryDao().getById(original.id))
            }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun explicitDatesAndTaskFieldsBecomeNewAuditedVersion() = runBlocking {
        database().use { db ->
            val old = original.copy(occurredAt = 100, validFrom = 10, validUntil = 1000)
            db.memoryDao().insert(old)
            val task = com.zero.v2.core.common.TaskMemory("WAITING", "等确认", "编号一致", null, "核对清单").encode()
            val d = db.memoryReviewDao()
            val id = d.propose(old, task, MemoryType.TASK, "personal", "人工整理", 1,
                MemoryReviewTimes(null, 20, 2000))!!
            assertEquals(old, d.memory(old.id))
            assertTrue(d.decide(id, true, 2))
            val result = checkNotNull(d.memory("review:$id"))
            assertNull(result.occurredAt); assertEquals(20L, result.validFrom); assertEquals(2000L, result.validUntil)
            assertEquals("WAITING", com.zero.v2.core.common.TaskMemory.decode(result.content)?.status)
            assertEquals(100L, d.memory(old.id)?.occurredAt)
            assertFalse(d.decide(id, true, 3))
        }
    }

}
