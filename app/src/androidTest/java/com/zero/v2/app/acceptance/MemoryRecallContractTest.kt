package com.zero.v2.app.acceptance

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.bus.ChatBusImpl
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.MemoryEntity
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.framework.memory.MemoryModule
import com.zero.v2.framework.memory.MemoryScoringEngine
import com.zero.v2.framework.memory.UserFactExtractor
import com.zero.v2.services.embedding.EmbeddingProvider
import com.zero.v2.services.embedding.VectorIndexWorker
import com.zero.v2.services.llm.ModelSelector
import com.zero.v2.services.retrieval.BoundedSemanticReranker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryRecallContractTest {
    @Test fun currentCorrectionExcludesExplicitlyRetiredRecordsButHistoryRetainsThem() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val provider = object : EmbeddingProvider {
            override val modelId = "offline-fixture"
            override suspend fun isAvailable() = false
            override suspend fun embed(text: String): FloatArray? = null
            override suspend fun embedBatch(texts: List<String>) = texts.map { null }
        }
        val selector = ModelSelector()
        val module = MemoryModule(db.memoryDao(), db.userGraphDao(), AgentTraceManager(db.agentTraceEventDao()),
            provider, MemoryScoringEngine(), db.memoryExtractionJobDao(),
            VectorIndexWorker(db.vectorIndexDao(), provider), BoundedSemanticReranker(selector), com.zero.v2.framework.memory.MemoryClassificationTools(db.chronicleSourceDao(), db.memoryDerivationDao(), com.zero.v2.core.data.store.MemoryDerivationStore(db)), com.zero.v2.core.data.store.MemoryDerivationStore(db))
        val current = "培训地点更正为五楼501室，原三楼302室安排失效。"
        val retired = "旧培训安排：培训地点是三楼302室，后续已被更正。"
        try {
            db.memoryDao().insertAll(listOf(MemoryEntity("current", current), MemoryEntity("retired", retired)))
            val handler = module.contributions().single { it.definition.name == "memory_recall" }.handler
            val result = handler(mapOf("query" to "更正后培训地点", "top_k" to 3))
            assertTrue(result.data.contains(current))
            assertTrue("memory tool citation must expose the exact source id", result.data.contains("[memory/current]"))
            assertFalse("current request must not inject explicitly retired arrangement", result.data.contains(retired))
            assertFalse(module.evidenceCandidates("更正后培训地点", 3).any { it.id == "retired" })
            assertTrue(handler(mapOf("query" to "旧培训安排", "top_k" to 3)).data.contains(retired))
            assertEquals(retired, db.memoryDao().getById("retired")!!.content)
        } finally { module.onDestroy(); db.close() }
    }
    @Test fun recallDoesNotRepeatDuplicateNotesOrTreatOldQuestionsAsAnswers() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val provider = object : EmbeddingProvider {
            override val modelId = "offline-fixture"
            override suspend fun isAvailable() = false
            override suspend fun embed(text: String): FloatArray? = null
            override suspend fun embedBatch(texts: List<String>) = texts.map { null }
        }
        val selector = ModelSelector()
        val module = MemoryModule(db.memoryDao(), db.userGraphDao(),
            AgentTraceManager(db.agentTraceEventDao()), provider, MemoryScoringEngine(),
            db.memoryExtractionJobDao(), VectorIndexWorker(db.vectorIndexDao(), provider), BoundedSemanticReranker(selector), com.zero.v2.framework.memory.MemoryClassificationTools(db.chronicleSourceDao(), db.memoryDerivationDao(), com.zero.v2.core.data.store.MemoryDerivationStore(db)), com.zero.v2.core.data.store.MemoryDerivationStore(db))
        try {
            val notes = listOf(
                MemoryEntity("duplicate-a", "用户提醒类消息偏好送微信", importance = 0.5f),
                MemoryEntity("duplicate-b", "用户提醒类消息偏好送微信。", importance = 0.5f),
                MemoryEntity("echo", "嗯，你可知道我最近在做什么，不妨整理一下", importance = 0.9f),
                MemoryEntity("wrapped", "我最近有什么日程\n(注意消息来源和意图；调用工具后等待结果；保持回复简洁；禁止编造结果)", importance = 0.9f),
                MemoryEntity("fact", "我最近在学习如何制作软件。", importance = 0.5f),
            )
            notes.forEach { db.memoryDao().insert(it) }
            val handler = module.contributions().single { it.definition.name == "memory_recall" }.handler
            val duplicates = handler(mapOf("query" to "提醒", "top_k" to 3))
            assertEquals(1, Regex("用户提醒类消息偏好送微信").findAll(duplicates.data).count())
            val recent = handler(mapOf("query" to "我最近在做什么", "top_k" to 3))
            assertFalse(recent.data.contains(notes[2].content))
            assertFalse(recent.data.contains("注意消息来源和意图"))
            assertTrue(recent.data.contains(notes[4].content))
            for (note in notes) assertEquals(note.content, db.memoryDao().getById(note.id)!!.content)
        } finally { module.onDestroy(); db.close() }
    }
}
