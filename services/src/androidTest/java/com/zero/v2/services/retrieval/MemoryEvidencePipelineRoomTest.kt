package com.zero.v2.services.retrieval

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.StreamChunk
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.ConversationEntity
import com.zero.v2.core.data.entity.MemoryEntity
import com.zero.v2.core.data.entity.MessageEntity
import com.zero.v2.services.llm.ILanguageModel
import com.zero.v2.services.llm.ModelCapability
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryEvidencePipelineRoomTest {
    private class Model(private val action: suspend () -> Unit) : ILanguageModel {
        override val modelId = "synthetic-offline"
        override val displayName = modelId
        override val contextWindow = 8192
        override val capabilities = setOf(ModelCapability.CHAT)
        override val keyConfigured = true
        override val priority = 1
        override val enabled = true
        override suspend fun boundedCompletion(userMessage: String, systemPrompt: String): String {
            action()
            return "{\"certain\":[0],\"uncertain\":[]}"
        }
        override suspend fun chatSync(userMessage: String, systemPrompt: String?): String = error("unbounded")
        override fun chatStream(messages: List<ChatMessage>, systemPrompt: String?,
            functions: List<FunctionDefinition>, prefixVersion: String?): Flow<StreamChunk> = emptyFlow()
    }
    private val source = MessageEntity("s", "c", "user", "Only after approval.\n\nKeep copies.",
        source = "CHAT_WINDOW")
    private val memory = MemoryEntity("m", "Keep copies.", origin = "USER_QUOTE",
        sourceMessageId = "s", sourceConversationId = "c")

    private fun fixture(block: suspend (ZeroDatabase, MemoryEvidencePipeline) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        try {
            db.conversationDao().insert(ConversationEntity("c", "Synthetic"))
            db.messageDao().insert(source)
            db.memoryDao().insert(memory)
            block(db, MemoryEvidencePipeline(MemoryEvidenceReader(db.memoryDao())))
        } finally { db.close() }
    }

    @Test fun realPipelinePreservesWholeEvidenceAndDoesNotRewardReads() = fixture { db, pipeline ->
        val before = db.memoryDao().getById("m")
        val result = pipeline.select("Which rule?", listOf("missing", "m"), Model {})
        assertTrue(result.completed)
        assertEquals(EvidenceSupport.Use.NONE, result.entries.first().use)
        assertEquals(source.content, result.entries.last().text)
        assertEquals("personal", result.entries.last().scope)
        assertEquals(before, db.memoryDao().getById("m"))
    }

    @Test fun sourceDeletionInsideCompletionCannotEscapeRevalidation() = fixture { db, pipeline ->
        val result = pipeline.select("Which rule?", listOf("m"), Model { db.messageDao().delete("s") })
        assertEquals(EvidenceSupport.Use.NONE, result.entries.single().use)
        assertEquals(null, result.entries.single().text)
    }

    @Test fun confirmationIsConsumedAsNewEvidenceAndAuditChangesInvalidateIt() = fixture { db, pipeline ->
        val dao = db.memoryReviewDao()
        val id = checkNotNull(dao.propose(memory, "Keep signed copies.", memory.type, "work", "fixture", 1))
        check(dao.decide(id, true, 2))
        val ids = listOf("review:$id")
        assertEquals("no_verified_sources", pipeline.select("Which rule?", ids, Model {}).status)
        val valid = pipeline.select("Which rule?", ids, Model {}, scope = "work")
        assertEquals("USER_CONFIRMATION", valid.entries.single().evidenceKind)
        val result = pipeline.select("Which rule?", ids, Model {
            db.openHelper.writableDatabase.execSQL(
                "UPDATE memory_reviews SET state = 'INVALIDATED' WHERE id = ?", arrayOf(id))
        }, scope = "work")
        assertEquals(EvidenceSupport.Use.NONE, result.entries.single().use)
    }
}
