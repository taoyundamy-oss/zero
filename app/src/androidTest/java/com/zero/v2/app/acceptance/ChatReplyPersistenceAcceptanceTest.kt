package com.zero.v2.app.acceptance

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.data.ConversationRepository
import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.MessageRole
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.framework.chat.ChatHistory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Isolated real Room database; never opens the personal database. */
class ChatReplyPersistenceAcceptanceTest {
    @Test fun fullToolReplacementPreservesLaterContextAndRollsBackEveryWrite() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, ZeroDatabase::class.java).build()
        try {
            val repo = ConversationRepository(db.conversationDao(), db.messageDao())
            val id = repo.createConversation("isolated replacement transaction")
            val user = ChatMessage("owned-user", MessageRole.USER, "first")
            val old = ChatMessage("owned-reply", MessageRole.ASSISTANT, "old")
            val later = ChatMessage("later-user", MessageRole.USER, "later")
            val context = ChatMessage(com.zero.v2.core.common.TurnHistory.contextId(later.id), MessageRole.SYSTEM, "later context")
            repo.persistTurn(id, listOf(user, old), user.id)
            repo.persistTurn(id, listOf(user, old, context, later), later.id)
            val initial = repo.getMessages(id)
            val call = com.zero.v2.core.common.ToolCall("owned-call", "fixture", "{}")
            val request = old.copy(content = "", toolCalls = listOf(call))
            val result = ChatMessage("owned-result", MessageRole.TOOL, "result", toolCallId = call.id, toolCallName = call.name)
            val final = ChatMessage("owned-final", MessageRole.ASSISTANT, "new final")
            val snapshot = ChatHistory(id, listOf(user, request, result, final, context, later))
            assertTrue(repo.persistRegeneratedReply(snapshot, user.id))
            val replaced = repo.getMessages(id)
            assertEquals(snapshot.messages.map { it.id }, replaced.map { it.id })
            assertEquals(initial.first().createdAt, replaced.first().createdAt)
            assertEquals(initial[1].createdAt, replaced[1].createdAt)
            assertTrue(replaced.zipWithNext().all { (a,b) -> a.createdAt < b.createdAt })
            assertEquals("later context", replaced[4].content)
            assertEquals("later", replaced[5].content)
            assertEquals(initial.last().createdAt - initial[2].createdAt, replaced.last().createdAt - replaced[4].createdAt)
            assertTrue(repo.persistRegeneratedReply(snapshot, user.id))
            assertEquals(replaced, repo.getMessages(id))
            db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER fixture_rollback BEFORE INSERT ON messages WHEN NEW.id='injected-rollback' BEGIN SELECT RAISE(ABORT, 'fixture rollback'); END")
            val injected = final.copy(id = "injected-rollback")
            try {
                repo.persistRegeneratedReply(snapshot.copy(messages = listOf(user, request, result, final, injected, context, later)), user.id)
                fail("Injected write failure must abort the transaction")
            } catch (e: android.database.sqlite.SQLiteException) {
                assertTrue(e.message.orEmpty().contains("fixture rollback"))
            }
            assertEquals(replaced, repo.getMessages(id))
            assertEquals(6, repo.getConversation(id)!!.messageCount)
            assertTrue(repo.persistRegeneratedReply(ChatHistory(id, listOf(user, old.copy(content = "simple"))), user.id))
            assertEquals(listOf(user.id, old.id, context.id, later.id), repo.getMessages(id).map { it.id })
        } finally { db.close() }
    }

    @Test fun stoppedAndRegeneratedRepliesPreserveOrderAndLaterTurns() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, ZeroDatabase::class.java).build()
        try {
            val repo = ConversationRepository(db.conversationDao(), db.messageDao())
            val id = repo.createConversation("isolated reply acceptance")
            val user = ChatMessage(id = "u1", role = MessageRole.USER, content = "test")
            val reply = ChatMessage(id = "a1", role = MessageRole.ASSISTANT, content = "partial")
            repo.persistTurn(id, listOf(user, reply), user.id)
            val later = ChatMessage(id = "u2", role = MessageRole.USER, content = "later")
            repo.persistTurn(id, listOf(user, reply, later), later.id)
            val before = repo.getMessages(id)
            assertTrue(repo.persistRegeneratedReply(ChatHistory(id, listOf(user, reply.copy(content = "replacement"))), user.id))
            val after = repo.getMessages(id)
            assertEquals(before.map { it.id }, after.map { it.id })
            assertEquals(before.map { it.createdAt }, after.map { it.createdAt })
            assertEquals("replacement", after.first { it.id == reply.id }.content)
            assertEquals("later", after.last().content)
            assertFalse(repo.persistRegeneratedReply(ChatHistory(id, listOf(later, reply.copy(content = "wrong owner"))), later.id))
            assertEquals("replacement", repo.getMessages(id).first { it.id == reply.id }.content)
            assertEquals(3, repo.getConversation(id)!!.messageCount)
        } finally { db.close() }
    }
}
