package com.zero.v2.app.acceptance

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.data.ConversationRepository
import com.zero.v2.core.common.*
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.framework.chat.ContextBuilderConfig
import com.zero.v2.services.llm.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Production dispatch/projection and disk Room; scripted model, zero network requests. */
class LargeToolResultAcceptanceTest {
    @Test fun originalCanBeReadAfterDatabaseReloadWithoutReexecutingTool() = runBlocking {
        val runner = InstrumentationRegistry.getInstrumentation()
        check(runner is ProviderAcceptanceRunner)
        val context = runner.targetContext
        val entry = EntryPointAccessors.fromApplication(context, RegenerationAcceptanceEntryPoint::class.java)
        val selector = entry.modelSelector()
        assertTrue(selector.getAll().isEmpty())
        val model = ScriptedModel()
        val raw = "log entry\n".repeat(10000) + "NEEDLE=42\n" + "remaining log\n".repeat(10000)
        var executions = 0
        val fixture = FunctionDefinition("isolated_large_result", "isolated large result", mapOf("type" to "object", "properties" to emptyMap<String, Any>()))
        val database = "c2-isolated-${System.nanoTime()}"
        fun open() = Room.databaseBuilder(context, ZeroDatabase::class.java, database).build()
        var db = open()
        val config = entry.contextBuilder().config as ContextBuilderConfig
        val evidence = config.evidenceAutoInject
        selector.register(model)
        entry.toolRegistrar().register(fixture, "chatbot") { executions++; FunctionResult.success(raw) }
        try {
            config.evidenceAutoInject = false
            var repo = ConversationRepository(db.conversationDao(), db.messageDao())
            val id = repo.createConversation("isolated C2")
            val engine = entry.chatBot()
            engine.initialize("Isolated deterministic acceptance")
            engine.loadHistory(id, emptyList())
            withTimeout(15000) { engine.sendMessage("read the tool result", listOf(fixture)).collect(engine::applyStreamChunk) }
            assertEquals(ChatState.IDLE, engine.chatState.value)
            assertEquals("NEEDLE=42", engine.chatHistory.value.messages.last().content)
            val original = engine.chatHistory.value.messages
            assertTrue(original.any { it.content == raw })
            repo.persistTurn(id, original, original.first { it.role == MessageRole.USER }.id)
            db.close()
            db = open()
            repo = ConversationRepository(db.conversationDao(), db.messageDao())
            val restored = repo.getMessages(id).mapNotNull(repo::toChatMessage)
            assertTrue(restored.any { it.content == raw })
            engine.loadHistory(id, restored)
            withTimeout(15000) { engine.sendMessage("read it again without rerunning", listOf(fixture)).collect(engine::applyStreamChunk) }
            assertEquals(ChatState.IDLE, engine.chatState.value)
            assertEquals(1, executions)
            assertEquals("NEEDLE=42", engine.chatHistory.value.messages.last().content)
            assertEquals(6, model.requests.size)
            assertTrue(model.requests.zipWithNext().all { (a, b) -> b.take(a.size) == a })
            assertEquals(1, model.prefixes.distinct().size)
            val preview = model.requests[2].first { it.toolCallId == "large-call" }.content!!
            assertTrue(preview.length < 6000)
            File(context.filesDir, "acceptance-c2-results.json").writeText(JSONObject()
                .put("originalChars", raw.length).put("previewChars", preview.length)
                .put("scriptedModelRequests", model.requests.size).put("toolExecutions", executions)
                .put("networkRequests", 0).put("diskRoomReload", true).put("appendOnly", true)
                .put("semanticCloudAcceptance", false).toString(2))
        } finally {
            config.evidenceAutoInject = evidence
            entry.toolRegistrar().unregister(fixture.name)
            selector.unregister(model.modelId)
            db.close()
            context.deleteDatabase(database)
        }
    }

    private class ScriptedModel : ILanguageModel {
        override val modelId = "isolated-c2"
        override val displayName = "isolated"
        override val contextWindow = 960000
        override val capabilities = setOf(ModelCapability.CHAT, ModelCapability.FUNCTION_CALLING)
        override val keyConfigured = true
        override val priority = 0
        override val enabled = true
        val requests = mutableListOf<List<ChatMessage>>()
        val prefixes = mutableListOf<Pair<String?, String?>>()
        override suspend fun chatSync(userMessage: String, systemPrompt: String?) = error("No sync request")
        override fun chatStream(messages: List<ChatMessage>, systemPrompt: String?, functions: List<FunctionDefinition>, prefixVersion: String?) = flow {
            assertTrue(functions.any { it.name == "read_tool_result" })
            // Room createdAt is storage time; it is not part of the provider request.
            requests += messages.map { it.copy(timestamp = 0) }
            prefixes += systemPrompt to prefixVersion
            val number = requests.size
            val call = when (number) {
                1 -> ToolCallDelta(id = "discover-call", name = "discover_tools", argumentsDelta = "{\"query\":\"isolated_large_result\"}")
                2 -> ToolCallDelta(id = "large-call", name = "invoke_tool", argumentsDelta = "{\"name\":\"isolated_large_result\",\"arguments\":{}}")
                3, 5 -> {
                    val preview = messages.first { it.toolCallId == "large-call" }.content!!
                    val ref = Regex("ref=([a-f0-9]{64})").find(preview)!!.groupValues[1]
                    ToolCallDelta(id = "read-$number", name = "read_tool_result",
                        argumentsDelta = JSONObject().put("ref", ref).put("query", "NEEDLE=").put("limit", 100).toString())
                }
                else -> null
            }
            if (call != null) emit(StreamChunk(toolCallDelta = call)) else {
                assertTrue(messages.last().content!!.contains("NEEDLE=42"))
                emit(StreamChunk(contentDelta = "NEEDLE=42"))
            }
            emit(StreamChunk(isFinal = true))
        }
    }
}
