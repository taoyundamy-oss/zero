package com.zero.v2.core.data.di

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.common.MemoryType
import com.zero.v2.core.common.MemoryStatus
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.AttributeSource
import com.zero.v2.core.data.entity.GraphMemoryEvidence
import com.zero.v2.core.data.entity.MemoryEntity
import com.zero.v2.core.data.entity.UserGraphAttribute
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GraphSourceTransactionTest {
    @Test fun staleModelResultCannotClosePreviousAttribute() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, ZeroDatabase::class.java).build()
        try {
            val source = MemoryEntity("user-fact:source", "我不喜欢冷饮", type = MemoryType.FACT,
                sourceConversationId = "chat", createdAt = 1)
            val dao = db.userGraphDao()
            val old = UserGraphAttribute("user", "preference", "legacy", AttributeSource.INFERRED, 0.4f, 1)
            dao.replaceCurrentAttribute(old)
            db.memoryDao().insert(source)
            val candidate = old.copy(value = source.content, effectiveFrom = 2,
                evidence = GraphMemoryEvidence.reference(source))
            db.memoryDao().insert(source.copy(content = "更正：我喜欢冷饮"))
            assertFalse(dao.replaceGroundedAttribute(candidate, source))
            assertEquals(listOf(old), dao.getCurrentAttributes("user"))
            db.memoryDao().delete(source.id)
            assertFalse(dao.replaceGroundedAttribute(candidate, source))
            assertEquals(listOf(old), dao.getCurrentAttributes("user"))
        } finally { db.close() }
    }

    @Test fun sourceChangesInvalidateReadButAccessCountersDoNot() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, ZeroDatabase::class.java).build()
        try {
            val source = MemoryEntity("user-fact:source", "我不喜欢冷饮", type = MemoryType.FACT,
                sourceConversationId = "chat", createdAt = 1)
            val dao = db.userGraphDao()
            db.memoryDao().insert(source)
            val candidate = UserGraphAttribute("user", "preference", source.content, AttributeSource.EXTRACTED,
                0.4f, 2, evidence = GraphMemoryEvidence.reference(source))
            assertTrue(dao.replaceGroundedAttribute(candidate, source))
            db.memoryDao().recordAccess(source.id)
            assertEquals(listOf(candidate), dao.getCurrentAttributes("user"))
            db.memoryDao().updateStatus(source.id, MemoryStatus.ARCHIVED)
            assertTrue(dao.getCurrentAttributes("user").isEmpty())
            db.memoryDao().insert(source.copy(content = "更正：我喜欢冷饮"))
            assertTrue(dao.getCurrentAttributes("user").isEmpty())
            assertEquals(listOf(candidate), dao.getAttributes("user"))
        } finally { db.close() }
    }

    @Test fun deletedSourceStopsBeingCurrentGraphFact() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, ZeroDatabase::class.java).build()
        try {
            val source = MemoryEntity("user-fact:source", "我喜欢示例食物A", type = MemoryType.FACT,
                sourceConversationId = "chat", createdAt = 1)
            db.memoryDao().insert(source)
            val attribute = UserGraphAttribute("user", "preference", "示例食物A", AttributeSource.EXTRACTED,
                0.4f, 2, evidence = GraphMemoryEvidence.reference(source))
            db.userGraphDao().replaceCurrentAttribute(attribute)
            assertEquals(listOf(attribute), db.userGraphDao().getCurrentAttributes("user"))
            db.memoryDao().delete(source.id)
            assertTrue(db.userGraphDao().getCurrentAttributes("user").isEmpty())
        } finally { db.close() }
    }
}
