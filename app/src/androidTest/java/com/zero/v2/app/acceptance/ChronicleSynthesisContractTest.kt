package com.zero.v2.app.acceptance

import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.common.*
import com.zero.v2.framework.chat.ChatBotModuleConfig
import com.zero.v2.services.llm.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class ChronicleSynthesisContractTest {
    @Test fun cappedToolLoopHasPortableSynthesisTail() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val f = ChronicleAgentFixture(context, provider.mediaAnalysis())
        var calls = 0
        val prefixes = mutableListOf<String?>()
        val model = object : ILanguageModel {
            override val modelId = "synthesis-contract"
            override val displayName = modelId
            override val contextWindow = 32768
            override val capabilities = setOf(ModelCapability.CHAT, ModelCapability.FUNCTION_CALLING)
            override val keyConfigured = true
            override val priority = 1
            override val enabled = true
            override suspend fun chatSync(userMessage: String, systemPrompt: String?) = error("unexpected")
            override fun chatStream(messages: List<ChatMessage>, systemPrompt: String?,
                functions: List<FunctionDefinition>, prefixVersion: String?) = flow {
                prefixes += systemPrompt
                if (calls++ == 0) emit(StreamChunk(toolCallDelta = ToolCallDelta(id = "read", name = "memory_sources",
                    argumentsDelta = """{"query":"missing"}"""), isFinal = true, finishReason = "tool_calls"))
                else {
                    assertTrue("synthesis must not introduce a late system role rejected by Qwen templates",
                        messages.none { it.role == MessageRole.SYSTEM })
                    assertEquals(MessageRole.USER, messages.last().role)
                    assertTrue(messages.last().content.orEmpty().contains("工具调用轮次上限"))
                    assertTrue(messages.any { it.role == MessageRole.TOOL && it.content.orEmpty().contains("sources") })
                    assertTrue(functions.isEmpty())
                    emit(StreamChunk(contentDelta = "当前没有匹配来源，不能编造。", isFinal = true, finishReason = "stop"))
                }
            }
        }
        try {
            f.start(model, "原有系统前缀不变。")
            (f.bot.config as ChatBotModuleConfig).maxFcRounds = 1
            withTimeout(30000) { f.bot.sendMessage("查找没有的来源。", f.registry.getAllDefinitions()).collect(f.bot::applyStreamChunk) }
            assertEquals(2, calls)
            assertEquals(1, prefixes.distinct().size)
            assertTrue(f.bot.chatHistory.value.messages.last().content.orEmpty().contains("不能编造"))
        } finally { f.close() }
    }
}
