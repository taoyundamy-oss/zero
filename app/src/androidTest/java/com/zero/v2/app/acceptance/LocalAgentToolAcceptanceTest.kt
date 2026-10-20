package com.zero.v2.app.acceptance

import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.ChatState
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.common.MessageRole
import com.zero.v2.core.common.StreamChunk
import com.zero.v2.framework.chat.ContextBuilderConfig
import com.zero.v2.services.llm.ConnectionAuth
import com.zero.v2.services.llm.ILanguageModel
import com.zero.v2.services.llm.LlmProfile
import com.zero.v2.services.llm.ProfileLanguageModel
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real loopback requests, production tool engine; no saved profile or personal conversation edits. */
class LocalAgentToolAcceptanceTest {
    @Test fun realLocalDiscoveryInvocationAndNextTurnPreservePrefix() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowLocalAgent") == "true")
        assertTrue(instrumentation is ProviderAcceptanceRunner)
        val context = instrumentation.targetContext
        val engineEntry = EntryPointAccessors.fromApplication(context, RegenerationAcceptanceEntryPoint::class.java)
        val provider = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val selector = engineEntry.modelSelector()
        assertTrue("Runner must not register daily cloud models", selector.getAll().isEmpty())
        val profile = LlmProfile.localQwen("http://127.0.0.1:8080/v1", "local-qwen").copy(
            id = "isolated-a10-local", authMode = ConnectionAuth.NONE, maxTokens = 512,
        )
        assertNull(provider.profiles().get(profile.id))
        val ledger = Ledger()
        val model = TrackingModel(ProfileLanguageModel(profile.id, profile, provider.llmClient(), provider.profiles()), ledger)
        val nonce = "ZERO_" + UUID.randomUUID().toString().take(8)
        var executions = 0
        val tool = FunctionDefinition("a10_nonce_probe", "读取本轮测试口令，只读，无外部副作用",
            mapOf("type" to "object", "properties" to emptyMap<String, Any>()))
        val config = engineEntry.contextBuilder().config as ContextBuilderConfig
        val originalEvidence = config.evidenceAutoInject
        val report = JSONObject().put("scope", "real local Qwen; isolated production Agent loop")
            .put("accepted", false).put("cloudCacheAcceptance", false)
        val reportFile = File(context.getExternalFilesDir(null), "a10-local-agent.json")
        selector.register(model)
        engineEntry.toolRegistrar().register(tool, "chatbot") {
            executions++
            FunctionResult.success(nonce)
        }
        try {
            config.evidenceAutoInject = false
            val engine = engineEntry.chatBot()
            engine.initialize("isolated local Agent")
            engine.loadHistory("isolated-a10-${UUID.randomUUID()}", emptyList())
            withTimeout(120_000) {
                engine.sendMessage("请先用 discover_tools 发现 a10_nonce_probe，再用 invoke_tool 执行它读取口令，最后原样回复口令。不要猜测。",
                    listOf(tool)).collect(engine::applyStreamChunk)
                assertEquals(ChatState.IDLE, engine.chatState.value)
                assertEquals(1, executions)
                val first = engine.chatHistory.value.messages
                assertTrue(first.any { row -> row.toolCalls.any { it.name == "discover_tools" } })
                assertTrue(first.any { row -> row.toolCalls.any { it.name == "invoke_tool" } })
                assertTrue(first.any { it.role == MessageRole.TOOL && it.content.orEmpty().contains(nonce) })
                assertTrue(first.last().content.orEmpty().contains(nonce))
                val firstRequests = ledger.requests.size
                assertTrue(firstRequests >= 3)
                assertTrue(ledger.requests.zipWithNext().all { (a, b) -> b.take(a.size) == a })
                engine.sendMessage("只复述刚才工具返回的口令，不要再次调用工具。", listOf(tool)).collect(engine::applyStreamChunk)
                assertEquals(ChatState.IDLE, engine.chatState.value)
                assertEquals(1, executions)
                assertTrue(engine.chatHistory.value.messages.last().content.orEmpty().contains(nonce))
                assertEquals(2, ledger.snapshots)
                assertTrue(ledger.requests.size > firstRequests)
                assertEquals(1, ledger.prefixes.distinct().size)
                assertTrue(ledger.prefixes.all { !it.first.isNullOrBlank() && !it.second.isNullOrBlank() })
                val continuity = provider.traces().getTrace("llm_call")
                    .filter { it.sourceModule == profile.id && it.summary.startsWith("request_prefix:") }
                    .map { it.summary }
                report.put("requestContinuity", JSONArray(continuity))
                assertEquals(ledger.requests.size, continuity.size)
                assertTrue(continuity.first().contains("reason=first_request"))
                assertTrue("Actual wire requests must preserve history across the next user turn",
                    continuity.drop(1).all { it.contains("reason=append_only") })
                report.put("accepted", true).put("executions", executions).put("snapshots", ledger.snapshots)
                    .put("firstTurnRequests", firstRequests).put("stablePrefix", true).put("appendOnlyToolLoop", true)
            }
        } finally {
            report.put("requests", ledger.requests.size).put("usage", ledger.usage)
            reportFile.writeText(report.toString(2))
            config.evidenceAutoInject = originalEvidence
            engineEntry.toolRegistrar().unregister(tool.name)
            selector.unregister(profile.id)
        }
    }

    private class Ledger {
        var snapshots = 0
        val requests = mutableListOf<List<ChatMessage>>()
        val prefixes = mutableListOf<Pair<String?, String?>>()
        val usage = JSONArray()
    }

    private class TrackingModel(
        private val delegate: ILanguageModel,
        private val ledger: Ledger,
        private val frozen: Boolean = false,
    ) : ILanguageModel by delegate {
        override fun snapshotForTurn(): ILanguageModel {
            if (frozen) return this
            ledger.snapshots++
            return TrackingModel(delegate.snapshotForTurn(), ledger, true)
        }
        override fun chatStream(messages: List<ChatMessage>, systemPrompt: String?,
            functions: List<FunctionDefinition>, prefixVersion: String?) = flow {
            check(ledger.requests.size < 8) { "Local acceptance request budget reached" }
            ledger.requests += messages.toList()
            ledger.prefixes += systemPrompt to prefixVersion
            delegate.chatStream(messages, systemPrompt, functions, prefixVersion).collect { chunk: StreamChunk ->
                chunk.tokenUsage?.let { usage -> ledger.usage.put(JSONObject()
                    .put("inputTokens", usage.inputTokens).put("cachedTokens", usage.cachedTokens)
                    .put("outputTokens", usage.outputTokens)) }
                emit(chunk)
            }
        }
    }
}
