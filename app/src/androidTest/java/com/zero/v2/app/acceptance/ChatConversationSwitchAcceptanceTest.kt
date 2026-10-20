package com.zero.v2.app.acceptance

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.data.ConversationRepository
import com.zero.v2.app.ui.ChatViewModel
import com.zero.v2.core.common.*
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.services.llm.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

/** Real ViewModel, engine, and isolated Room; one local model, no personal conversation writes. */
class ChatConversationSwitchAcceptanceTest {
    @Test fun switchingAndDeletingRetainOnlyOwnedPartialReply() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(instrumentation is ProviderAcceptanceRunner)
        val entry = EntryPointAccessors.fromApplication(instrumentation.targetContext, RegenerationAcceptanceEntryPoint::class.java)
        val selector = entry.modelSelector()
        assertTrue("No normal provider startup allowed", selector.getAll().isEmpty())
        val model = LocalModel()
        selector.register(model)
        val db = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, ZeroDatabase::class.java).build()
        val repo = ConversationRepository(db.conversationDao(), db.messageDao())
        val first = repo.createConversation("isolated first")
        val second = repo.createConversation("isolated second")
        val user = ChatMessage("switch-user", MessageRole.USER, "isolated query")
        val reply = ChatMessage("switch-reply", MessageRole.ASSISTANT, "original")
        repo.persistTurn(first, listOf(user, reply), user.id)
        val secondUser = ChatMessage("second-user", MessageRole.USER, "second query")
        val secondReply = ChatMessage("second-reply", MessageRole.ASSISTANT, "second original")
        repo.persistTurn(second, listOf(secondUser, secondReply), secondUser.id)
        val store = ViewModelStore()
        val vm = withContext(Dispatchers.Main) {
            ChatViewModel(entry.chatBot(), entry.toolContextSelector(), entry.traces(), repo, db.characterDao(),
                entry.chatBus(), entry.attachments(), SavedStateHandle(mapOf("chat.conversation" to first)))
                .also { store.put("isolated", it) }
        }
        suspend fun idleAt(id: String) = withTimeout(5_000) {
            while (vm.currentConversationId.value != id || vm.isSwitchingConversation.value || vm.isGenerating.value) delay(10)
        }
        try {
            withTimeout(5_000) { while (entry.chatBot().chatHistory.value.id != first) delay(10) }
            withContext(Dispatchers.Main) { vm.regenerate() }
            withTimeout(5_000) { model.started.await() }
            withContext(Dispatchers.Main) { vm.refreshActiveCharacter() }
            withTimeout(5_000) { while (vm.isSwitchingConversation.value) delay(10) }
            assertTrue("Unchanged character must not stop generation", vm.isGenerating.value)
            withContext(Dispatchers.Main) { vm.loadConversation(second) }
            idleAt(second)
            assertEquals("partial local reply", repo.getMessages(first).last().content)
            assertEquals(listOf(secondUser.id, secondReply.id), vm.messages.value.map { it.id })
            assertEquals("second original", vm.messages.value.last().content)
            model.started = CompletableDeferred()
            withContext(Dispatchers.Main) { vm.regenerate() }
            withTimeout(5_000) { model.started.await() }
            withContext(Dispatchers.Main) { vm.deleteConversation(first) }
            withTimeout(5_000) { while (vm.isSwitchingConversation.value) delay(10) }
            assertTrue("Deleting another conversation must not stop generation", vm.isGenerating.value)
            assertNull(repo.getConversation(first))
            withContext(Dispatchers.Main) { vm.clearConversation() }
            withTimeout(5_000) { while (vm.isSwitchingConversation.value || vm.isGenerating.value) delay(10) }
            assertEquals("partial local reply", repo.getMessages(second).last().content)
            assertNotEquals(second, vm.currentConversationId.value)
            assertTrue(vm.messages.value.isEmpty())
            withContext(Dispatchers.Main) { vm.loadConversation(second) }
            idleAt(second)
            model.started = CompletableDeferred()
            withContext(Dispatchers.Main) { vm.regenerate() }
            withTimeout(5_000) { model.started.await() }
            db.characterDao().insert(com.zero.v2.core.data.entity.CharacterEntity(
                id = "isolated-character", name = "Isolated", systemPrompt = "local fixture only", isActive = true))
            withContext(Dispatchers.Main) { vm.refreshActiveCharacter() }
            withTimeout(5_000) { while (vm.isSwitchingConversation.value || vm.isGenerating.value) delay(10) }
            assertEquals("partial local reply", repo.getMessages(second).last().content)
            assertEquals("Isolated", vm.activeCharacterName.value)
            assertNotEquals(second, vm.currentConversationId.value)
            assertTrue(vm.messages.value.isEmpty())
            withContext(Dispatchers.Main) { vm.loadConversation(second) }
            idleAt(second)
            model.started = CompletableDeferred()
            withContext(Dispatchers.Main) { vm.regenerate() }
            withTimeout(5_000) { model.started.await() }
            withContext(Dispatchers.Main) { vm.deleteConversation(second) }
            withTimeout(5_000) { while (vm.isSwitchingConversation.value || vm.isGenerating.value) delay(10) }
            assertNull(repo.getConversation(second))
            assertNotEquals(second, entry.chatBot().chatHistory.value.id)
            assertTrue(vm.messages.value.isEmpty())
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            withTimeout(5_000) { while (vm.isGenerating.value) delay(10) }
            selector.unregister(model.modelId)
            db.close()
        }
    }
    private class LocalModel : ILanguageModel {
        override val modelId = "isolated-switch-model"
        override val displayName = "isolated"
        override val contextWindow = 960000
        override val capabilities = setOf(ModelCapability.CHAT, ModelCapability.FUNCTION_CALLING)
        override val keyConfigured = true
        override val priority = 0
        override val enabled = true
        var started = CompletableDeferred<Unit>()
        override suspend fun chatSync(userMessage: String, systemPrompt: String?) = error("No sync request")
        override fun chatStream(messages: List<ChatMessage>, systemPrompt: String?, functions: List<FunctionDefinition>, prefixVersion: String?) = flow {
            emit(StreamChunk(contentDelta = "partial local reply"))
            started.complete(Unit)
            awaitCancellation()
        }
    }
}
