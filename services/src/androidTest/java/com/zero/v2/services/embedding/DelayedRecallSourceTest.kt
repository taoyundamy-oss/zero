package com.zero.v2.services.embedding

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.common.MemoryStatus
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.KbChunkEntity
import com.zero.v2.core.data.entity.KbDocEntity
import com.zero.v2.core.data.entity.MemoryEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DelayedRecallSourceTest {
    @Test fun memoryChangeAndArchiveInvalidateDelayedResultsWithoutAccessReward() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        try {
            val dao = db.memoryDao()
            val changed = MemoryEntity("changed", "原地点")
            val archived = MemoryEntity("archived", "旧资料")
            val retained = MemoryEntity("retained", "现行资料")
            listOf(changed, archived, retained).forEach { dao.insert(it) }
            dao.insert(changed.copy(content = "新地点"))
            dao.updateStatus(archived.id, MemoryStatus.ARCHIVED)
            val result = dao.recordCurrentRecall(listOf(changed, archived, retained), 1)
            assertEquals(listOf(retained.id), result.map { it.id })
            assertEquals(0, dao.getById(changed.id)!!.reinforcementCount)
            assertEquals(0, dao.getById(archived.id)!!.reinforcementCount)
            assertEquals(1, dao.getById(retained.id)!!.reinforcementCount)
        } finally { db.close() }
    }

    @Test fun replacedKnowledgeAndDeletedDocumentInvalidateDelayedResults() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        try {
            val dao = db.kbDao()
            val doc = KbDocEntity("doc", "fixture.md", "资料", 0, 0, 1, 0)
            val chunk = KbChunkEntity("chunk", doc.id, 0, text = "原文", charLen = 2, updatedAt = 0)
            dao.replaceDocument(doc, listOf(chunk))
            assertEquals(listOf(chunk), dao.currentRecall(listOf(chunk), mapOf(doc.id to doc)))
            dao.replaceDocument(doc, listOf(chunk.copy(text = "更正")))
            assertTrue(dao.currentRecall(listOf(chunk), mapOf(doc.id to doc)).isEmpty())
            dao.replaceDocument(doc.copy(title = "更新标题"), listOf(chunk))
            assertTrue(dao.currentRecall(listOf(chunk), mapOf(doc.id to doc)).isEmpty())
            dao.deleteDoc(doc.relativePath)
            assertTrue(dao.currentRecall(listOf(chunk), mapOf(doc.id to doc)).isEmpty())
        } finally { db.close() }
    }
}
