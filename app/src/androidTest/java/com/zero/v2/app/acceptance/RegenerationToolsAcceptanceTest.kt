package com.zero.v2.app.acceptance

import androidx.room.Room
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.data.ConversationRepository
import com.zero.v2.app.ui.ChatViewModel
import com.zero.v2.core.common.*
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.services.llm.*
import com.zero.v2.framework.chat.ContextBuilderConfig
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

/** Actual registry, engine and isolated Room. Deterministic local requests only. */
class RegenerationToolsAcceptanceTest {
    @Test fun regeneratedToolTranscriptContinuesAndSurvivesReload() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(instrumentation is ProviderAcceptanceRunner)
        val entry = EntryPointAccessors.fromApplication(instrumentation.targetContext, RegenerationAcceptanceEntryPoint::class.java)
        val selector = entry.modelSelector()
        assertTrue(selector.getAll().isEmpty())
        val model = LocalModel()
        selector.register(model)
        var executions = 0
        val fixture = FunctionDefinition("isolated_regeneration_probe", "isolated probe", mapOf("type" to "object", "properties" to emptyMap<String, Any>()))
        entry.toolRegistrar().register(fixture, "chatbot") { executions++;
            FunctionResult(true, data = "LOCAL_RESULT", mediaRefs = listOf(MediaRef("content://isolated/media", "image/png", MediaKind.IMAGE))) }
        val db = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, ZeroDatabase::class.java).build()
        val store = ViewModelStore()
        try {
            val repo = ConversationRepository(db.conversationDao(), db.messageDao())
            val id = repo.createConversation("isolated regeneration tools")
            val user = ChatMessage("regen-tools-user", MessageRole.USER, "probe")
            val old = ChatMessage("regen-tools-reply", MessageRole.ASSISTANT, "old")
            repo.persistTurn(id, listOf(user, old), user.id)
            val engine = entry.chatBot()
            engine.initialize("isolated tools")
            engine.loadHistory(id, listOf(user, old))
            val vm = withContext(Dispatchers.Main) {
                ChatViewModel(engine, entry.toolContextSelector(), entry.traces(), repo, db.characterDao(),
                    entry.chatBus(), entry.attachments(), SavedStateHandle(mapOf("chat.conversation" to id)))
                    .also { store.put("isolated", it) }
            }
            withTimeout(5_000) { while (vm.messages.value.lastOrNull()?.content != "old") delay(10) }
            withContext(Dispatchers.Main) { vm.regenerate() }
            withTimeout(5_000) { while (vm.isGenerating.value) delay(10) }
            assertEquals("Discovery, invocation and final synthesis must each request the model", 3, model.requests.size)
            assertEquals(1, executions)
            assertEquals(1, model.snapshots)
            assertEquals("opaque-signature", engine.chatHistory.value.messages
                .first { it.toolCalls.isNotEmpty() }.toolCalls.first().providerState?.googleThoughtSignature)
            val history = engine.chatHistory.value
            assertEquals(1, history.messages.count { it.role == MessageRole.USER })
            assertEquals("LOCAL_FINAL", history.messages.last().content)
            assertEquals(listOf("discover-call", "invoke-call"), history.messages.filter { it.role == MessageRole.TOOL }.map { it.toolCallId })
            assertEquals(1, model.prefixes.distinct().size)
            assertTrue(model.requests.zipWithNext().all { (a,b) -> b.take(a.size) == a })
            assertEquals(listOf("discover-call", "invoke-call"), vm.messages.value.last().toolCalls.map { it.id })
            assertTrue(repo.persistRegeneratedReply(history, user.id))
            val stored = repo.getMessages(id).mapNotNull(repo::toChatMessage)
            assertEquals(history.messages.map { it.id }, stored.map { it.id })
            assertEquals(history.messages.map { it.toolCalls }, stored.map { it.toolCalls })
            assertEquals(history.messages.map { it.attachments }, stored.map { it.attachments })
            assertEquals("LOCAL_FINAL", stored.last().content)
            engine.loadHistory(id, stored)
            assertEquals(history.messages.map { it.id }, engine.chatHistory.value.messages.map { it.id })
            withContext(Dispatchers.Main) { vm.loadConversation(id) }
            withTimeout(5_000) { while (vm.isSwitchingConversation.value) delay(10) }
            assertEquals(listOf("discover-call", "invoke-call"), vm.messages.value.last().toolCalls.map { it.id })
            // Ordinary send exercises the same loop without cloud evidence or normal module startup.
            withContext(Dispatchers.Main) { store.clear() }
            val prefix = model.prefixes.first()
            model.requests.clear(); model.prefixes.clear()
            val cfg = entry.contextBuilder().config as ContextBuilderConfig
            val evidence = cfg.evidenceAutoInject
            try {
                cfg.evidenceAutoInject = false
                engine.loadHistory(repo.createConversation("isolated ordinary send"), emptyList())
                withTimeout(5_000) { engine.sendMessage("local ordinary send", listOf(fixture)).collect(engine::applyStreamChunk) }
                assertEquals(3, model.requests.size)
                assertEquals(2, executions)
                assertEquals(2, model.snapshots)
                assertTrue(model.prefixes.all { it == prefix })
                assertEquals("LOCAL_FINAL", engine.chatHistory.value.messages.last().content)
            } finally { cfg.evidenceAutoInject = evidence }
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            entry.toolRegistrar().unregister(fixture.name)
            selector.unregister(model.modelId)
            db.close()
        }
    }
    private class LocalModel : ILanguageModel {
        override val modelId = "isolated-regeneration-tools"
        override val displayName = "isolated"
        override val contextWindow = 960000
        override val capabilities = setOf(ModelCapability.CHAT, ModelCapability.FUNCTION_CALLING)
        override val keyConfigured = true
        override val priority = 0
        override val enabled = true
        var snapshots = 0
        override fun snapshotForTurn(): ILanguageModel { snapshots++; return this }
        val requests = mutableListOf<List<ChatMessage>>()
        val prefixes = mutableListOf<Pair<String?, String?>>()
        override suspend fun chatSync(userMessage: String, systemPrompt: String?) = error("No sync request")
        override fun chatStream(messages: List<ChatMessage>, systemPrompt: String?, functions: List<FunctionDefinition>, prefixVersion: String?) = flow {
            requests += messages.toList(); prefixes += systemPrompt to prefixVersion
            when (requests.size) {
                1 -> emit(StreamChunk(toolCallDelta = ToolCallDelta(id="discover-call", name="discover_tools", argumentsDelta="{\"query\":\"isolated_regeneration_probe\"}")))
                2 -> emit(StreamChunk(toolCallDelta = ToolCallDelta(id="invoke-call", name="invoke_tool", argumentsDelta="{\"name\":\"isolated_regeneration_probe\",\"arguments\":{}}")))
                else -> emit(StreamChunk(contentDelta="LOCAL_FINAL"))
            }
            if (requests.size <= 2) emit(StreamChunk(toolCallDelta = ToolCallDelta(
                providerState = ToolProviderState("fixture-scope", "opaque-signature"))))
            emit(StreamChunk(isFinal=true))
        }
    }
}
