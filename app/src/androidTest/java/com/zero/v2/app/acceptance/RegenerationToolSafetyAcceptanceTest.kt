package com.zero.v2.app.acceptance

import androidx.room.Room
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.zero.v2.app.ui.ChatViewModel
import com.zero.v2.app.ui.GenerationState
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.data.ConversationRepository
import com.zero.v2.core.common.*
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.framework.chat.ChatBotModuleConfig
import com.zero.v2.services.llm.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class RegenerationToolSafetyAcceptanceTest {
    @Test fun ownershipAndMissingModelRejectionsPreserveOriginalReply() = runBlocking {
        fixture(Mode.ERROR) { entry, model, repo, user ->
            val original = entry.chatBot().chatHistory.value
            val rejected = mutableListOf<StreamChunk>()
            entry.chatBot().regenerate(userMessageId = "different-user").collect { rejected += it }
            assertEquals("REGENERATION_TURN_CHANGED", rejected.single().errorCode)
            assertEquals(original, entry.chatBot().chatHistory.value)
            entry.modelSelector().unregister(model.modelId)
            val before = repo.getMessages(original.id)
            val store = ViewModelStore()
            val isolatedDb = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, ZeroDatabase::class.java).build()
            val vm = withContext(Dispatchers.Main) {
                ChatViewModel(entry.chatBot(), entry.toolContextSelector(), entry.traces(), repo, isolatedDb.characterDao(),
                    entry.chatBus(), entry.attachments(), SavedStateHandle(mapOf("chat.conversation" to original.id)))
                    .also { store.put("isolated", it) }
            }
            try {
                withContext(Dispatchers.Main) { vm.regenerate() }
                withTimeout(5_000) { while (vm.isGenerating.value) delay(10) }
                assertEquals(GenerationState.ERROR, vm.sendState.value)
                assertEquals(before, repo.getMessages(original.id))
                assertEquals(original.messages.map { it.content }, entry.chatBot().chatHistory.value.messages.map { it.content })
                assertEquals(user.id, entry.chatBot().chatHistory.value.messages.first().id)
                assertEquals("old", vm.messages.value[1].content)
            } finally { withContext(Dispatchers.Main) { store.clear() }; isolatedDb.close() }
        }
    }

    @Test fun cancellingLaterToolKeepsCompletedResultAndClosesUnknownResult() = runBlocking {
        fixture(Mode.CANCEL) { entry, model, repo, user ->
            val engine = entry.chatBot()
            val job = launch(start = CoroutineStart.UNDISPATCHED) { engine.regenerate().collect(engine::applyStreamChunk) }
            try {
                withTimeout(5_000) { model.blockStarted.await() }
                job.cancelAndJoin()
                val history = engine.chatHistory.value
                val results = history.messages.filter { it.role == MessageRole.TOOL }
                assertEquals(listOf("fast-call", "blocked-call"), results.map { it.toolCallId })
                assertEquals("SAFE_RESULT", results.first().content)
                assertTrue(results.last().content!!.contains("工具未完成"))
                assertNull(engine.activeTurn.get())
                assertEquals(ChatState.IDLE, engine.chatState.value)
                assertTrue(repo.persistRegeneratedReply(history, user.id))
                assertEquals(history.messages.map { it.id }, repo.getMessages(history.id).map { it.id })
                assertEquals(1, model.requests.size)
            } finally { job.cancelAndJoin() }
        }
    }
    @Test fun capUsesStableSystemAndAccurateManifestHashForFinalSynthesis() = runBlocking {
        fixture(Mode.CAP) { entry, model, _, _ ->
            (entry.chatBot().config as ChatBotModuleConfig).maxFcRounds = 1
            withTimeout(5_000) { entry.chatBot().regenerate().collect(entry.chatBot()::applyStreamChunk) }
            assertEquals(2, model.requests.size)
            assertEquals(model.systems[0], model.systems[1])
            assertTrue(model.tools[1].isEmpty())
            assertEquals(PromptPrefix.prefixVersion(PromptPrefix.PROVIDER_PROTOCOL_V1, model.modelId,
                model.systems[1]!!, PromptPrefix.toolManifest(emptyList())), model.versions[1])
            assertEquals("SAFE_FINAL", entry.chatBot().chatHistory.value.messages.last().content)
            assertEquals(1, entry.chatBot().chatHistory.value.messages.count { it.role == MessageRole.USER })
        }
    }
    @Test fun continuationFailureIsVisibleAndRetainsCompletedToolResults() = runBlocking {
        fixture(Mode.ERROR) { entry, _, repo, user ->
            val chunks = mutableListOf<StreamChunk>()
            withTimeout(5_000) { entry.chatBot().regenerate().collect { chunks += it; entry.chatBot().applyStreamChunk(it) } }
            assertTrue(chunks.any { it.errorCode == "CHAT_REQUEST_FAILED" })
            assertEquals(ChatState.ERROR, entry.chatBot().chatState.value)
            val history = entry.chatBot().chatHistory.value
            assertEquals("SAFE_RESULT", history.messages.first { it.role == MessageRole.TOOL }.content)
            assertTrue(repo.persistRegeneratedReply(history, user.id))
            assertTrue(repo.getMessages(history.id).last().content!!.contains("请求失败"))
            assertNull(entry.chatBot().activeTurn.get())
        }
    }
    private suspend fun fixture(mode: Mode, block: suspend CoroutineScope.(RegenerationAcceptanceEntryPoint, LocalModel, ConversationRepository, ChatMessage) -> Unit) = coroutineScope {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(instrumentation is ProviderAcceptanceRunner)
        val entry = EntryPointAccessors.fromApplication(instrumentation.targetContext, RegenerationAcceptanceEntryPoint::class.java)
        assertTrue(entry.modelSelector().getAll().isEmpty())
        val model = LocalModel(mode)
        entry.modelSelector().register(model)
        val db = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, ZeroDatabase::class.java).build()
        val originalRounds = (entry.chatBot().config as ChatBotModuleConfig).maxFcRounds
        val definition = FunctionDefinition("isolated_safe_probe", "safe local fixture", mapOf("type" to "object"))
        entry.toolRegistrar().register(definition, "chatbot") { args ->
            if (args["block"] == true) { model.blockStarted.complete(Unit); awaitCancellation() }
            FunctionResult.success("SAFE_RESULT")
        }
        try {
            val repo = ConversationRepository(db.conversationDao(), db.messageDao())
            val id = repo.createConversation("isolated tool safety")
            val user = ChatMessage("safe-user", MessageRole.USER, "safe fixture")
            val old = ChatMessage("safe-reply", MessageRole.ASSISTANT, "old")
            repo.persistTurn(id, listOf(user, old), user.id)
            entry.chatBot().initialize("isolated safety")
            entry.chatBot().loadHistory(id, listOf(user, old))
            block(entry, model, repo, user)
        } finally {
            (entry.chatBot().config as ChatBotModuleConfig).maxFcRounds = originalRounds
            entry.toolRegistrar().unregister(definition.name)
            entry.modelSelector().unregister(model.modelId)
            db.close()
        }
    }
    private enum class Mode { CANCEL, CAP, ERROR }
    private class LocalModel(private val mode: Mode) : ILanguageModel {
        override val modelId = "isolated-safety-model"
        override val displayName = "isolated"
        override val contextWindow = 960000
        override val capabilities = setOf(ModelCapability.CHAT, ModelCapability.FUNCTION_CALLING)
        override val keyConfigured = true
        override val priority = 0
        override val enabled = true
        val requests = mutableListOf<List<ChatMessage>>()
        val systems = mutableListOf<String?>()
        val versions = mutableListOf<String?>()
        val tools = mutableListOf<List<FunctionDefinition>>()
        val blockStarted = CompletableDeferred<Unit>()
        override suspend fun chatSync(userMessage: String, systemPrompt: String?) = error("No sync request")
        override fun chatStream(messages: List<ChatMessage>, systemPrompt: String?, functions: List<FunctionDefinition>, prefixVersion: String?) = flow {
            requests += messages.toList(); systems += systemPrompt; versions += prefixVersion; tools += functions.toList()
            if (requests.size == 1) {
                emit(StreamChunk(toolCallDelta = ToolCallDelta(id="fast-call", name="invoke_tool", argumentsDelta="{\"name\":\"isolated_safe_probe\",\"arguments\":{}}")))
                if (mode == Mode.CANCEL) emit(StreamChunk(toolCallDelta = ToolCallDelta(index=1,id="blocked-call", name="invoke_tool", argumentsDelta="{\"name\":\"isolated_safe_probe\",\"arguments\":{\"block\":true}}")))
            } else {
                if (mode == Mode.ERROR) error("isolated continuation failure")
                emit(StreamChunk(contentDelta="SAFE_FINAL"))
            }
            emit(StreamChunk(isFinal=true))
        }
    }
}
