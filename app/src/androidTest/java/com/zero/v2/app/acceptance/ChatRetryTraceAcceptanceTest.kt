package com.zero.v2.app.acceptance

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.data.ConversationRepository
import com.zero.v2.app.ui.ChatViewModel
import com.zero.v2.app.ui.GenerationState
import com.zero.v2.core.common.*
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.trace.NodeType
import com.zero.v2.services.llm.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class ChatRetryTraceAcceptanceTest {
    @Test fun failedRegenerationRefreshesTraceAndExplicitRetryKeepsUserAndDraft() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(instrumentation is ProviderAcceptanceRunner)
        val entry = EntryPointAccessors.fromApplication(instrumentation.targetContext, RegenerationAcceptanceEntryPoint::class.java)
        assertTrue(entry.modelSelector().getAll().isEmpty())
        val model = RetryModel()
        entry.modelSelector().register(model)
        val db = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, ZeroDatabase::class.java).build()
        val store = ViewModelStore()
        try {
            val repo = ConversationRepository(db.conversationDao(), db.messageDao())
            val id = repo.createConversation("isolated retry trace")
            val user = ChatMessage("retry-user", MessageRole.USER, "local request")
            repo.persistTurn(id, listOf(user, ChatMessage("retry-old", MessageRole.ASSISTANT, "old")), user.id)
            entry.chatBot().initialize("isolated retry")
            val vm = withContext(Dispatchers.Main) {
                ChatViewModel(entry.chatBot(), entry.toolContextSelector(), entry.traces(), repo, db.characterDao(),
                    entry.chatBus(), entry.attachments(), SavedStateHandle(mapOf("chat.conversation" to id)))
                    .also { store.put("retry", it) }
            }
            withTimeout(5_000) { while (vm.messages.value.lastOrNull()?.content != "old") delay(10) }
            withContext(Dispatchers.Main) { vm.setInput("new draft stays"); vm.regenerate() }
            withTimeout(5_000) { while (vm.isGenerating.value) delay(10) }
            assertEquals(GenerationState.ERROR, vm.sendState.value)
            assertTrue("current error trace must be visible", vm.traceTraces.value.any { it.nodeType == NodeType.ERROR })
            assertTrue(vm.traceTraces.value.any { it.summary == "generation_finished: ERROR" })
            val failedTrace = vm.traceTraces.value.first().traceId
            assertEquals(user.id, vm.retryUserId.value)
            assertEquals(1, model.requests)
            assertEquals("new draft stays", vm.inputText.value)
            model.fail = false
            withContext(Dispatchers.Main) { vm.retryFailedTurn(); vm.retryFailedTurn() }
            withTimeout(5_000) { while (vm.isGenerating.value) delay(10) }
            assertEquals(GenerationState.IDLE, vm.sendState.value)
            assertEquals(2, model.requests)
            assertTrue(vm.traceTraces.value.all { it.traceId != failedTrace })
            assertTrue(vm.traceTraces.value.any { it.summary == "generation_finished: IDLE" })
            assertFalse(vm.traceTraces.value.any { it.nodeType == NodeType.ERROR })
            assertNull(vm.retryUserId.value)
            assertEquals("new draft stays", vm.inputText.value)
            assertEquals(listOf(user.id), repo.getMessages(id).filter { it.role == "user" }.map { it.id })
            assertEquals("RECOVERED", vm.messages.value.last().content)
            assertEquals("RECOVERED", repo.getMessages(id).last().content)
            withContext(Dispatchers.Main) { vm.setInput("unreadable attachment draft"); vm.setAttachment("content://isolated-missing/image", "image/png"); vm.sendMessage() }
            withTimeout(5_000) { while (vm.isGenerating.value) delay(10) }
            assertEquals(GenerationState.ERROR, vm.sendState.value)
            assertNull("preflight failure must not retry the previous user", vm.retryUserId.value)
            assertEquals("unreadable attachment draft", vm.inputText.value)
            withContext(Dispatchers.Main) { vm.retryFailedTurn() }
            assertEquals(2, model.requests)
            entry.modelSelector().unregister(model.modelId)
            withContext(Dispatchers.Main) { vm.regenerate() }
            withTimeout(5_000) { while (vm.isGenerating.value) delay(10) }
            assertEquals(GenerationState.ERROR, vm.sendState.value)
            assertTrue(vm.traceTraces.value.any { it.summary.contains("REGENERATION_MODEL_UNAVAILABLE") })
            assertEquals("RECOVERED", repo.getMessages(id).last().content)
            withContext(Dispatchers.Main) { vm.clearConversation() }
            withTimeout(5_000) { while (vm.isSwitchingConversation.value) delay(10) }
            assertEquals(GenerationState.IDLE, vm.sendState.value)
            assertNull(vm.retryUserId.value)
            assertTrue(vm.traceTraces.value.isEmpty())
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            entry.modelSelector().unregister(model.modelId)
            db.close()
        }
    }

    private class RetryModel : ILanguageModel {
        override val modelId = "isolated-retry-trace"
        override val displayName = "isolated"
        override val contextWindow = 960000
        override val capabilities = setOf(ModelCapability.CHAT, ModelCapability.FUNCTION_CALLING)
        override val keyConfigured = true
        override val priority = 0
        override val enabled = true
        var fail = true
        var requests = 0
        override suspend fun chatSync(userMessage: String, systemPrompt: String?) = error("No sync request")
        override fun chatStream(messages: List<ChatMessage>, systemPrompt: String?, functions: List<FunctionDefinition>, prefixVersion: String?) = flow {
            requests++
            if (fail) error("isolated transport failure")
            emit(StreamChunk(contentDelta = "RECOVERED")); emit(StreamChunk(isFinal = true))
        }
    }
}
