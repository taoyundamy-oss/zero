package com.zero.v2.core.data.di

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.common.MemoryType
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GraphForgetTransactionTest {
    @Test fun singleDeleteSupersedesOnlyItsSourceAndRollsBackOnFailure() = runBlocking {
        val db = database()
        try {
            val selected = source("user-fact:message:0123456789abcdef")
            seed(db, selected)
            val jobs = db.memoryExtractionJobDao()
            val job = MemoryExtractionJobEntity("pending", "USER_FACT", "chat", "message", "示例食物A", "pending")
            val unrelated = job.copy(id = "other", conversationId = "other-chat", uniqueKey = "other")
            jobs.insertIfNew(job)
            jobs.insertIfNew(unrelated)
            val deletion = com.zero.v2.core.data.repository.MemoryDeletionRepository(jobs)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_single BEFORE DELETE ON memories " +
                "BEGIN SELECT RAISE(ABORT, 'fixture_failure'); END")
            assertTrue(runCatching { deletion.deleteById(selected.id) }.isFailure)
            assertNotNull(db.memoryDao().getById(selected.id))
            assertFalse(db.memoryDao().wasDeleted(selected.id))
            assertEquals(1, db.userGraphDao().getAttributes("user").size)
            assertEquals(1, db.userGraphDao().getStoredCurrentRelationships("user").size)
            assertEquals(job, jobs.getByIds(listOf(job.id)).single())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_single")
            assertEquals(1, deletion.deleteById(selected.id))
            assertEquals("SUPERSEDED", jobs.getByIds(listOf(job.id)).single().state)
            assertEquals(unrelated, jobs.getByIds(listOf(unrelated.id)).single())
            assertFalse(jobs.completeBatch(listOf(job), listOf(selected), 4))
            assertEquals(0, deletion.deleteById(selected.id))
        } finally { db.close() }
    }

    @Test fun singleDeletePreservesSameTextOtherMemory() = runBlocking {
        val db = database()
        try {
            val selected = source()
            seed(db, selected)
            seed(db, source("user-fact:other"), 3)
            com.zero.v2.core.data.repository.MemoryDeletionRepository(db.memoryExtractionJobDao()).deleteById(selected.id)
            assertEquals(1, db.userGraphDao().getAttributes("user").size)
            assertEquals(1, db.userGraphDao().getStoredCurrentRelationships("user").size)
            assertNotNull(db.memoryDao().getById("user-fact:other"))
        } finally { db.close() }
    }

    @Test fun failedMemoryDeleteRollsBackGraphAndQueue() = runBlocking {
        val db = database()
        try {
            val source = source()
            seed(db, source)
            val job = MemoryExtractionJobEntity("pending", "USER_FACT", sourceDigest = source.content, uniqueKey = "pending")
            db.memoryExtractionJobDao().insertIfNew(job)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_forget BEFORE DELETE ON memories " +
                "BEGIN SELECT RAISE(ABORT, 'fixture_failure'); END")
            assertTrue(runCatching { db.memoryExtractionJobDao().forgetMatching("示例食物A", 3) }.isFailure)
            assertNotNull(db.memoryDao().getById(source.id))
            assertEquals(1, db.userGraphDao().getAttributes("user").size)
            assertEquals(1, db.userGraphDao().getStoredCurrentRelationships("user").size)
            assertEquals(listOf(job), db.memoryExtractionJobDao().getByIds(listOf(job.id)))
        } finally { db.close() }
    }

    @Test fun boundedPagesRemoveAllMatchesAndPreserveUnrelatedAndLegacyGraph() = runBlocking {
        val db = database()
        try {
            repeat(129) { seed(db, source("user-fact:$it"), it.toLong()) }
            seed(db, source("user-fact:128-extra", "喜欢咖啡"), 200)
            db.userGraphDao().insertAttribute(UserGraphAttribute("user", "legacy", "示例食物A",
                AttributeSource.INFERRED, 0.4f, 201))
            val job = MemoryExtractionJobEntity("pending", "USER_FACT", sourceDigest = "喜欢示例食物A", uniqueKey = "pending")
            val jobs = db.memoryExtractionJobDao()
            jobs.insertIfNew(job)
            assertEquals(129, jobs.forgetMatching("示例食物A", 3))
            assertEquals(2, db.userGraphDao().getAttributes("user").size)
            assertEquals(1, db.userGraphDao().getStoredCurrentRelationships("user").size)
            assertEquals("SUPERSEDED", jobs.getByIds(listOf(job.id)).single().state)
            assertFalse(jobs.completeBatch(listOf(job), listOf(source()), 4))
            assertEquals(0, jobs.forgetMatching("示例食物A", 5))
            assertTrue(runCatching { jobs.forgetMatching(" ", 6) }.isFailure)
            assertNotNull(db.memoryDao().getById("user-fact:128-extra"))
        } finally { db.close() }
    }

    private fun database() = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext, ZeroDatabase::class.java).build()

    private fun source(id: String = "user-fact:one", content: String = "我喜欢示例食物A") =
        MemoryEntity(id, content, type = MemoryType.FACT, sourceConversationId = "chat", createdAt = 1)

    private suspend fun seed(db: ZeroDatabase, source: MemoryEntity, time: Long = 2) {
        db.memoryDao().insert(source)
        val evidence = GraphMemoryEvidence.reference(source)
        db.userGraphDao().insertAttribute(UserGraphAttribute("user", "preference", source.content,
            AttributeSource.EXTRACTED, 0.4f, time, evidence = evidence))
        db.userGraphDao().insertRelationship(UserGraphRelationship("user", "food", "likes", time, evidence = evidence))
    }

    @Test fun forgetRemovesStoredGraphIncludingPreviousSourceRevision() = runBlocking {
        val db = database()
        try {
            val original = source()
            seed(db, original)
            db.memoryDao().insert(original.copy(content = "更正：我喜欢示例食物A和茶"))
            assertEquals(1, db.memoryExtractionJobDao().forgetMatching("示例食物A", 3))
            assertNull(db.memoryDao().getById(original.id))
            assertTrue(db.userGraphDao().getAttributes("user").isEmpty())
            assertTrue(db.userGraphDao().getStoredCurrentRelationships("user").isEmpty())
        } finally { db.close() }
    }
}
