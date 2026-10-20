package com.zero.v2.app.acceptance

import android.os.Process
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.data.ConversationRepository
import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.MessageRole
import com.zero.v2.core.common.ToolCall
import com.zero.v2.core.common.ToolProviderState
import com.zero.v2.core.data.db.ZeroDatabase
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** Two separate instrumentation processes; only this dedicated acceptance database is used. */
class LlmToolStateRestartAcceptanceTest {
    @Test fun savedToolStateSurvivesNewProcessWithoutExecutingHistory() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is ProviderAcceptanceRunner)
        val context = instrumentation.targetContext
        val phase = InstrumentationRegistry.getArguments().getString("toolStatePhase")
        require(phase in setOf("seed", "restore"))
        val journal = File(context.filesDir, "llm-u5-restart.json")
        val db = Room.databaseBuilder(context, ZeroDatabase::class.java, "llm-u5-acceptance.db").build()
        try {
            val repo = ConversationRepository(db.conversationDao(), db.messageDao())
            if (phase == "seed") {
                val id = repo.createConversation("U5 synthetic restart")
                val messages = listOf(ChatMessage("u5-user", MessageRole.USER, "synthetic"),
                    ChatMessage("u5-call", MessageRole.ASSISTANT, null, toolCalls = listOf(
                        ToolCall("done", "synthetic_probe", "{}", ToolProviderState("scope", "sig+/=exact"), 0))),
                    ChatMessage("u5-result", MessageRole.TOOL, "synthetic result", toolCallId = "done"),
                    ChatMessage("u5-pending", MessageRole.ASSISTANT, null, toolCalls = listOf(
                        ToolCall("pending", "synthetic_probe", "{}", ToolProviderState("scope", "pending-signature"), 0))))
                repo.persistTurn(id, messages, "u5-user")
                journal.writeText(JSONObject().put("conversation", id).put("seedPid", Process.myPid()).toString(2))
            } else {
                val report = JSONObject(journal.readText())
                assertNotEquals(report.getInt("seedPid"), Process.myPid())
                val id = report.getString("conversation")
                val messages = repo.getMessages(id).mapNotNull(repo::toChatMessage)
                assertEquals(4, messages.size)
                assertEquals("sig+/=exact", messages[1].toolCalls.single().providerState?.googleThoughtSignature)
                assertEquals("pending-signature", messages.last().toolCalls.single().providerState?.googleThoughtSignature)
                assertEquals("done", messages[2].toolCallId)
                val entry = EntryPointAccessors.fromApplication(context, RegenerationAcceptanceEntryPoint::class.java)
                entry.chatBot().initialize("Synthetic restore")
                entry.chatBot().loadHistory(id, messages)
                assertEquals(messages, entry.chatBot().chatHistory.value.messages)
                assertNull(entry.chatBot().activeTurn.get())
                report.put("restorePid", Process.myPid()).put("passed", true)
                    .put("messages", messages.size).put("pendingHistoryNotAutoResumed", true)
                journal.writeText(report.toString(2))
            }
        } finally { db.close() }
    }
}
