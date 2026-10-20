package com.zero.v2.app.acceptance

import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.common.ChatState
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.common.MessageRole
import com.zero.v2.core.common.StreamChunk
import com.zero.v2.framework.chat.ChatBotModule
import com.zero.v2.framework.chat.ContextBuilderConfig
import com.zero.v2.services.llm.ConnectionAuth
import com.zero.v2.services.llm.LlmProfile
import com.zero.v2.services.llm.ProfileLanguageModel
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Real Qwen discovery/invocation, then an isolated actual HTTP503 or cancelled socket. */
class LocalAgentFailureAcceptanceTest {
    @Test fun disconnectedContinuationRecoversWithoutRepeatingCompletedTool() = runBlocking<Unit> {
        fixture("disconnect") { engine, nonce, report ->
            val failed = mutableListOf<StreamChunk>()
            withTimeout(60_000) {
                engine.sendMessage(PROMPT).collect { failed += it; engine.applyStreamChunk(it) }
            }
            assertTrue(failed.any { it.errorCode == "CHAT_REQUEST_FAILED" })
            assertEquals(ChatState.ERROR, engine.chatState.value)
            assertNull(engine.activeTurn.get())
            assertTrue(engine.chatHistory.value.messages.any {
                it.role == MessageRole.TOOL && it.content.orEmpty().contains(nonce)
            })
            val disconnectedRequests = status().getInt("chatRequests")
            assertTrue(disconnectedRequests >= 3)
            control(JSONObject().put("mode", "online"))
            val recovered = mutableListOf<StreamChunk>()
            withTimeout(60_000) {
                engine.sendMessage("网络已恢复。仅原样回复上一轮工具已返回的口令，不要再次调用工具。")
                    .collect { recovered += it; engine.applyStreamChunk(it) }
            }
            assertFalse(recovered.any { it.errorCode != null })
            assertEquals(ChatState.IDLE, engine.chatState.value)
            assertNull(engine.activeTurn.get())
            assertTrue(recovered.joinToString("") { it.contentDelta.orEmpty() }.contains(nonce))
            assertTrue(status().getInt("chatRequests") in 1..2)
            report.put("disconnectedRequests", disconnectedRequests)
                .put("recoveryRequests", status().getInt("chatRequests"))
                .put("completedToolRetained", true).put("contextRecovered", true)
        }
    }

    @Test fun failedHttpContinuationIsVisibleAndKeepsCompletedTools() = runBlocking<Unit> {
        fixture("fail") { engine, nonce, report ->
            val chunks = mutableListOf<StreamChunk>()
            withTimeout(60_000) {
                engine.sendMessage(PROMPT).collect { chunks += it; engine.applyStreamChunk(it) }
            }
            assertTrue(chunks.any { it.errorCode == "CHAT_REQUEST_FAILED" })
            assertEquals(ChatState.ERROR, engine.chatState.value)
            assertTrue(engine.chatHistory.value.messages.any { it.role == MessageRole.TOOL && it.content.orEmpty().contains(nonce) })
            assertNull(engine.activeTurn.get())
            assertEquals(3, status().getInt("chatRequests"))
            report.put("visibleFailure", true).put("completedToolRetained", true)
        }
    }

    @Test fun cancellingHttpContinuationKeepsToolsAndRejectsLateReply() = runBlocking<Unit> {
        fixture("block") { engine, nonce, report ->
            val chunks = mutableListOf<StreamChunk>()
            val job = launch { engine.sendMessage(PROMPT).collect { chunks += it; engine.applyStreamChunk(it) } }
            try {
                withTimeout(60_000) { while (!status().getBoolean("thirdEntered")) delay(50) }
                withTimeout(3_000) { job.cancelAndJoin() }
                assertNull(engine.activeTurn.get())
                assertEquals(ChatState.IDLE, engine.chatState.value)
                assertTrue(engine.chatHistory.value.messages.any { it.role == MessageRole.TOOL && it.content.orEmpty().contains(nonce) })
                val history = engine.chatHistory.value
                val count = chunks.size
                control(JSONObject().put("release", true))
                delay(700)
                assertEquals(history, engine.chatHistory.value)
                assertEquals(count, chunks.size)
                assertEquals(3, status().getInt("chatRequests"))
                report.put("cancelledSocket", true).put("noLateReply", true)
                    .put("completedToolRetained", true)
            } finally { job.cancelAndJoin(); control(JSONObject().put("release", true)) }
        }
    }

    private suspend fun fixture(mode: String, block: suspend CoroutineScope.(ChatBotModule, String, JSONObject) -> Unit) = coroutineScope {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowLocalFault") == "true")
        assertTrue(instrumentation is ProviderAcceptanceRunner)
        val context = instrumentation.targetContext
        val entry = EntryPointAccessors.fromApplication(context, RegenerationAcceptanceEntryPoint::class.java)
        val provider = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        assertTrue(entry.modelSelector().getAll().isEmpty())
        val profile = LlmProfile.localQwen("http://127.0.0.1:18082/v1", "local-qwen").copy(
            id = "isolated-a10-fault", authMode = ConnectionAuth.NONE, maxTokens = 512,
        )
        assertNull(provider.profiles().get(profile.id))
        val model = ProfileLanguageModel(profile.id, profile, provider.llmClient(), provider.profiles())
        val nonce = "ZERO_" + UUID.randomUUID().toString().take(8)
        var executions = 0
        val tool = FunctionDefinition("a10_failure_probe", "读取本轮测试口令，只读，无外部副作用",
            mapOf("type" to "object", "properties" to emptyMap<String, Any>()))
        val config = entry.contextBuilder().config as ContextBuilderConfig
        val originalEvidence = config.evidenceAutoInject
        val report = JSONObject().put("scope", "real local Qwen + controlled HTTP fault")
            .put("accepted", false).put("mode", mode)
        entry.modelSelector().register(model)
        entry.toolRegistrar().register(tool, "chatbot") { executions++; FunctionResult.success(nonce) }
        try {
            config.evidenceAutoInject = false
            control(JSONObject().put("mode", mode))
            val engine = entry.chatBot()
            engine.initialize("isolated local failure")
            engine.loadHistory("isolated-a10-${UUID.randomUUID()}", emptyList())
            block(engine, nonce, report)
            assertEquals(1, executions)
            report.put("accepted", true)
        } finally {
            report.put("executions", executions)
            File(context.getExternalFilesDir(null), "a10-local-fault-$mode.json").writeText(report.toString(2))
            config.evidenceAutoInject = originalEvidence
            entry.toolRegistrar().unregister(tool.name)
            entry.modelSelector().unregister(profile.id)
            control(JSONObject().put("release", true))
        }
    }

    private fun status(): JSONObject = URL("http://127.0.0.1:18082/status").openConnection().let { raw ->
        val connection = raw as HttpURLConnection
        connection.connectTimeout = 1000; connection.readTimeout = 1000
        try { JSONObject(connection.inputStream.bufferedReader().use { it.readText() }) }
        finally { connection.disconnect() }
    }

    private fun control(body: JSONObject) {
        val connection = URL("http://127.0.0.1:18082/control").openConnection() as HttpURLConnection
        connection.connectTimeout = 1000; connection.readTimeout = 1000
        try {
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            connection.inputStream.close()
        } finally { connection.disconnect() }
    }

    companion object {
        private const val PROMPT = "请先用 discover_tools 发现 a10_failure_probe，再用 invoke_tool 执行它读取口令，最后原样回复口令。不要猜测。"
    }
}
