package com.zero.v2.app.acceptance

import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.MessageRole
import com.zero.v2.core.common.ModelRequirements
import com.zero.v2.core.common.InputModality
import com.zero.v2.services.llm.LlmProfile
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Explicit user-authorized persistent dev routing; never runs as part of generic connected tests. */
class LocalModelAcceptanceTest {
    @Test fun configureAndVerifyLocalDevelopmentModel() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("configureLocal") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val credentials = EntryPointAccessors.fromApplication(context, LocalModelAcceptanceEntryPoint::class.java).credentials()
        val prefs = context.getSharedPreferences("zero_llm_profiles", 0)
        val backup = File(context.filesDir, "local-development-profiles-before.json")
        if (!backup.exists()) backup.writeText(prefs.getString("profiles_json", "[]")!!)
        val local = LlmProfile.localQwen(args.getString("localBaseUrl") ?: "http://127.0.0.1:8080/v1",
            requireNotNull(args.getString("localModelId")))
        credentials.put(local.apiKeyRef, "local")
        entry.profiles().profiles.value.filter { it.kind == LlmProfile.Kind.CHAT && it.id != local.id }
            .forEach { entry.profiles().upsert(it.copy(enabled = false)) }
        entry.profiles().upsert(local)
        entry.chatProfiles().refreshProfiles()
        val model = requireNotNull(entry.modelSelector().select(ModelRequirements(input = setOf(InputModality.TEXT), needsTools = true)).model)
        assertEquals(local.modelId, model.modelId)
        assertEquals(24_576, model.contextWindow)
        assertEquals(2048, model.outputReserve)
        val messages = listOf(
            ChatMessage(role = MessageRole.SYSTEM, content = "第一回合上下文"),
            ChatMessage(role = MessageRole.USER, content = "你好"),
            ChatMessage(role = MessageRole.ASSISTANT, content = "你好"),
            ChatMessage(role = MessageRole.SYSTEM, content = "第二回合上下文"),
            ChatMessage(role = MessageRole.USER, content = "只回复 LOCAL_OK"),
        )
        val chunks = model.chatStream(messages,
            "这是本地接入验收。", emptyList()).toList()
        assertTrue(chunks.joinToString("") { it.contentDelta.orEmpty() }.contains("LOCAL_OK"))
        assertTrue(chunks.any { it.isFinal })
        assertTrue(chunks.any { it.tokenUsage != null })
        val tools = listOf(com.zero.v2.core.common.FunctionDefinition("echo", "返回文字",
            mapOf("type" to "object", "properties" to mapOf("text" to mapOf("type" to "string")),
                "required" to listOf("text"))))
        val toolMessages = messages.dropLast(1) + ChatMessage(role = MessageRole.USER,
            content = "必须调用 echo 工具，传 text=LOCAL_OK")
        val toolChunks = model.chatStream(toolMessages, "工具协议验收", tools).toList()
        assertTrue("Native local tool call must survive the production SSE decoder",
            toolChunks.any { it.toolCallDelta?.name == "echo" })
        val deltas = toolChunks.mapNotNull { it.toolCallDelta }
        val call = com.zero.v2.core.common.ToolCall(
            id = deltas.firstNotNullOf { it.id }, name = "echo",
            arguments = deltas.joinToString("") { it.argumentsDelta.orEmpty() },
        )
        val continuation = toolMessages + listOf(
            ChatMessage(role = MessageRole.ASSISTANT, content = "", toolCalls = listOf(call)),
            ChatMessage(role = MessageRole.TOOL, content = "LOCAL_OK", toolCallId = call.id),
        )
        val reply = model.chatStream(continuation, "工具协议验收", tools).toList()
        assertTrue("Tool result continuation must produce a final response", reply.any { it.isFinal })
        assertTrue(reply.joinToString("") { it.contentDelta.orEmpty() }.contains("LOCAL_OK"))
        try {
            model.chatStream(listOf(ChatMessage(role = MessageRole.USER, content = "超长输入".repeat(15_000))),
                "本地边界验收", emptyList()).toList()
            fail("Oversized input must never reach generation")
        } catch (error: java.io.IOException) {
            assertTrue(error.message.orEmpty().contains("LOCAL_CONTEXT_LIMIT"))
        }
        // Disabled cloud CHAT profiles cannot silently take over when local is stopped.
        entry.modelSelector().unregister(local.modelId)
        assertNull(entry.modelSelector().select(ModelRequirements(input = setOf(InputModality.TEXT))).model)
        entry.chatProfiles().refreshProfiles()
    }
}
