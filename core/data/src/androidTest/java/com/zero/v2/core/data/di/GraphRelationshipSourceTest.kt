package com.zero.v2.core.data.di

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.common.MemoryType
import com.zero.v2.core.common.MemoryStatus
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.GraphMemoryEvidence
import com.zero.v2.core.data.entity.MemoryEntity
import com.zero.v2.core.data.entity.UserGraphRelationship
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GraphRelationshipSourceTest {
    private fun database() = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext, ZeroDatabase::class.java).build()

    private fun source() = MemoryEntity("user-fact:relation", "小明是我的同事。", type = MemoryType.FACT,
        sourceConversationId = "chat", createdAt = 1)

    @Test fun staleOrArchivedSourceCannotReplaceCurrentRelationship() = runBlocking {
        val db = database()
        try {
            val source = source()
            val dao = db.userGraphDao()
            val old = UserGraphRelationship("user", "person_ming", "colleague", 0)
            val edge = old.copy(effectiveFrom = 2, evidence = GraphMemoryEvidence.reference(source))
            dao.insertRelationship(old)
            db.memoryDao().insert(source.copy(content = "小明已经不是我的同事。"))
            assertFalse(dao.replaceGroundedRelationship(edge, source))
            db.memoryDao().insert(source.copy(status = MemoryStatus.ARCHIVED))
            assertFalse(dao.replaceGroundedRelationship(edge, source))
            db.memoryDao().delete(source.id)
            assertFalse(dao.replaceGroundedRelationship(edge, source))
            assertEquals(listOf(old), dao.getCurrentRelationships("user"))
        } finally { db.close() }
    }

    @Test fun failedReplacementRollsBackAndSuccessfulReplacementKeepsHistory() = runBlocking {
        val db = database()
        try {
            val source = source()
            val dao = db.userGraphDao()
            db.memoryDao().insert(source)
            val old = UserGraphRelationship("user", "person_ming", "colleague", 0)
            dao.insertRelationship(old)
            val edge = old.copy(effectiveFrom = 2, evidence = GraphMemoryEvidence.reference(source))
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_edge BEFORE INSERT ON " +
                "user_graph_relationships WHEN NEW.effectiveFrom = 2 " +
                "BEGIN SELECT RAISE(ABORT, 'fixture_failure'); END")
            assertTrue(runCatching { dao.replaceGroundedRelationship(edge, source) }.isFailure)
            assertEquals(listOf(old), dao.getCurrentRelationships("user"))
            val accepted = edge.copy(effectiveFrom = 3)
            assertTrue(dao.replaceGroundedRelationship(accepted, source))
            assertEquals(listOf(accepted), dao.getCurrentRelationships("user"))
            db.openHelper.writableDatabase.query("SELECT effectiveTo FROM user_graph_relationships WHERE effectiveFrom=0").use {
                assertTrue(it.moveToFirst()); assertEquals(3L, it.getLong(0))
            }
            db.memoryDao().recordAccess(source.id)
            assertEquals(listOf(accepted), dao.getCurrentRelationships("user"))
            db.memoryDao().updateStatus(source.id, MemoryStatus.ARCHIVED)
            assertTrue(dao.getCurrentRelationships("user").isEmpty())
            db.memoryDao().insert(source.copy(content = "小明已经不是我的同事。"))
            assertTrue(dao.getCurrentRelationships("user").isEmpty())
        } finally { db.close() }
    }

    @Test fun deletedSourceInvalidatesRelationship() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, ZeroDatabase::class.java).build()
        try {
            val source = MemoryEntity("user-fact:relation", "小明是我的同事。", type = MemoryType.FACT,
                sourceConversationId = "chat", createdAt = 1)
            db.memoryDao().insert(source)
            val edge = UserGraphRelationship("user", "person_ming", "colleague", 2,
                evidence = GraphMemoryEvidence.reference(source))
            db.userGraphDao().insertRelationship(edge)
            assertEquals(listOf(edge), db.userGraphDao().getCurrentRelationships("user"))
            db.memoryDao().delete(source.id)
            assertTrue(db.userGraphDao().getCurrentRelationships("user").isEmpty())
        } finally { db.close() }
    }
}
