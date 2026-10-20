package com.zero.v2.app.acceptance

import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.common.MessageRole
import com.zero.v2.framework.chat.ChatBotModuleConfig
import com.zero.v2.framework.chat.ContextBuilderConfig
import com.zero.v2.services.llm.CloudflareChatTransport
import com.zero.v2.services.llm.LlmProfile
import com.zero.v2.services.llm.ProfileLanguageModel
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

/** Opt-in physical-device probe. Synthetic input only; saved profiles and credentials are never edited. */
class LlmProviderAcceptanceTest {
    @Test fun configuredCloudflareRequestsAndAgentLoop() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is ProviderAcceptanceRunner)
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("runLlmAcceptance") == "true")
        val stage = args.getString("llmStage") ?: "diagnostic"
        require(stage in setOf("diagnostic", "matrix", "discovery", "gemini", "configured", "lmstudio", "dashscope", "mimo", "luna_phone", "haiku_agent", "haiku_thinking", "luna_responses", "review_responses", "luna_reasoning"))
        val context = instrumentation.targetContext
        val provider = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val entry = EntryPointAccessors.fromApplication(context, RegenerationAcceptanceEntryPoint::class.java)
        val profiles = provider.profiles().profiles.value
        val selectedId = if (stage == "configured") requireNotNull(args.getString("configuredProfile")) else if (stage == "lmstudio") "local-qwen" else if (stage == "dashscope") "qwen-embedding" else if (stage == "mimo") "mimo-tts" else "qwen"
        val saved = profiles.single { it.id == selectedId && (stage in setOf("dashscope", "mimo") || it.kind == LlmProfile.Kind.CHAT) }
        check(stage in setOf("configured", "lmstudio", "dashscope", "mimo") || URI(saved.baseUrl).host in setOf("api.cloudflare.com", "gateway.ai.cloudflare.com"))
        val key = provider.llmClient().resolveApiKey(saved).orEmpty()
        val rows = JSONArray()
        val report = JSONObject().put("stage", stage).put("rows", rows)
            .put("host", URI(saved.baseUrl).host).put("savedModel", saved.modelId)
            .put("scope", "synthetic_inputs_real_network_real_chatbot_no_personal_chat_or_profile_edit")
        val output = File(context.filesDir, "llm-u6-${if (stage == "configured") selectedId else stage}.json")
        val journal = File(context.filesDir, if (stage in setOf("lmstudio", "dashscope", "mimo", "luna_phone", "haiku_agent", "haiku_thinking", "luna_responses", "review_responses", "luna_reasoning")) "llm-u6b-$stage-attempts.json" else if (stage == "configured") "llm-u6b-remaining-$selectedId-attempts.json" else if (stage == "gemini") "llm-u6b-attempts.json" else "llm-u6-attempts.json")
        if (stage in setOf("haiku_agent", "haiku_thinking", "luna_responses", "review_responses", "luna_reasoning")) check(!output.exists() && !journal.exists()) { "Do not repeat paid closeout acceptance" }
        fun flush() = output.writeText(report.toString(2))
        suspend fun reserve() {
            check(provider.budget().preCheck("llm-u6") == com.zero.v2.services.agent.BudgetGuard.Verdict.OK)
            val used = if (journal.exists()) JSONObject(journal.readText()).getInt("requests") else 0
            check(used < if (stage in setOf("haiku_thinking", "luna_responses", "review_responses")) 5 else if (stage in setOf("haiku_agent", "luna_reasoning")) 3 else if (stage == "luna_phone") 6 else if (stage in setOf("gemini", "configured", "lmstudio", "dashscope", "mimo")) 8 else REQUEST_CAP) { "acceptance_request_cap" }
            journal.writeText(JSONObject().put("requests", used + 1).toString())
        }
        val qwen = saved.copy(modelId = "@cf/qwen/qwen3.8-27b", contractId = "cf-qwen38-v1",
            parameters = mapOf("effort" to "low"), maxTokens = 4096)
        flush()
        if (stage == "diagnostic") {
            val body = JSONObject().put("model", qwen.modelId).put("stream", false)
                .put("temperature", saved.temperature.toDouble()).put("max_tokens", saved.maxTokens)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply OK only.")))
            val legacy = JSONObject(body.toString()).put("extra_body", JSONObject()
                .put("enable_thinking", saved.enableThinking).put("thinking_budget", saved.thinkingBudget))
            val compiled = JSONObject(body.toString())
            CloudflareChatTransport.applyContract(compiled, qwen, false)
            val http = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false).build()
            for ((name, payload) in listOf("legacy_qwen_slot" to legacy, "without_extra_body" to body,
                "explicit_cf_contract" to compiled)) {
                reserve()
                val row = JSONObject().put("case", name).put("request", payload)
                rows.put(row); flush()
                try {
                    withContext(Dispatchers.IO) {
                        http.newCall(CloudflareChatTransport.request(qwen, key)
                            .post(payload.toString().toRequestBody(JSON)).build()).execute().use { response ->
                            val text = response.body?.string().orEmpty()
                            row.put("httpStatus", response.code).put("cfRay", response.header("cf-ray"))
                            if (!response.isSuccessful) row.put("error", text.let { if (key.isEmpty()) it else it.replace(key, "[redacted]") }
                                .replace(saved.baseUrl, "[route]").take(ERROR_LIMIT))
                            else {
                                val message = JSONObject(text).optJSONArray("choices")?.optJSONObject(0)
                                row.put("finishReason", message?.optString("finish_reason"))
                                row.put("content", message?.optJSONObject("message")?.optString("content")?.take(100))
                            }
                        }
                    }
                } catch (failure: Exception) { row.put("errorClass", failure.javaClass.simpleName) }
                flush()
            }
        } else {
            check(entry.modelSelector().getAll().isEmpty())
            val candidates = if (stage in setOf("luna_responses", "review_responses", "luna_reasoning")) listOf(stage to saved.copy(
                modelId = "openai/gpt-6-luna", contractId = if (stage == "review_responses") "cf-openai-responses-v1" else "cf-luna-responses-v1",
                parameters = mapOf("effort" to if (stage in setOf("luna_reasoning", "review_responses")) "medium" else "low"),
                gatewayMode = com.zero.v2.services.llm.GatewayMode.CF_GATEWAY,
                cloudflareGatewayId = "default", localLlamaPreflight = false,
                capabilities = saved.capabilities + com.zero.v2.services.llm.ModelCapability.IMAGE_INPUT,
            )) else if (stage == "haiku_thinking") listOf(stage to saved.copy(
                modelId = "anthropic/claude-haiku-4-5", contractId = "cf-anthropic-native-v1",
                parameters = mapOf("thinking" to "true", "budget" to "1024"),
                gatewayMode = com.zero.v2.services.llm.GatewayMode.CF_GATEWAY,
                cloudflareGatewayId = "default", localLlamaPreflight = false,
            )) else if (stage == "haiku_agent") listOf(stage to saved.copy(
                modelId = "anthropic/claude-haiku-4.5", contractId = "generic-v1", parameters = emptyMap(),
                gatewayMode = com.zero.v2.services.llm.GatewayMode.CF_GATEWAY,
                cloudflareGatewayId = "default", localLlamaPreflight = false,
            )) else if (stage == "luna_phone") listOf(stage to saved.copy(
                modelId = "openai/gpt-6-luna", contractId = "effort-v1", parameters = mapOf("effort" to "none"),
                gatewayMode = com.zero.v2.services.llm.GatewayMode.CF_GATEWAY,
                cloudflareGatewayId = "default", localLlamaPreflight = false,
            )) else if (stage in setOf("dashscope", "mimo")) {
                val wire = if (stage == "dashscope") "qwen3.8-flash" else "mimo-v2.5"
                val catalog = JSONArray(File(context.filesDir, "llm-u6b-provider-catalogs.json").readText())
                check((0 until catalog.length()).map { catalog.getJSONObject(it) }.single {
                    it.getString("profile") == selectedId
                }.getJSONArray("models").let { models -> (0 until models.length()).any { models.getString(it) == wire } })
                listOf(stage to saved.copy(id = "acceptance-$stage", kind = LlmProfile.Kind.CHAT,
                    capabilities = LlmProfile.defaultCapabilities(LlmProfile.Kind.CHAT), modelId = wire,
                    contractId = if (stage == "dashscope") "dashscope-effort-v1" else "mimo-v1",
                    parameters = if (stage == "dashscope") mapOf("thinking" to "true", "effort" to "low")
                        else mapOf("thinking" to "true"), localLlamaPreflight = false,
                ))
            } else if (stage == "lmstudio") listOf("lmstudio" to saved.copy(
                id = "acceptance-lmstudio", baseUrl = "http://127.0.0.1:1234/v1",
                modelId = "u6b-qwen3-8b", contextWindow = 16384, maxTokens = 4096,
                authMode = com.zero.v2.services.llm.ConnectionAuth.NONE,
                localLlamaPreflight = false, contractId = "generic-v1", parameters = emptyMap(),
            )) else if (stage == "configured") listOf(selectedId to saved) else if (stage == "gemini") {
                val inventory = JSONObject(File(context.filesDir, "llm-u6b-inventory.json").readText())
                check(inventory.getBoolean("passed"))
                val gateways = inventory.getJSONArray("gateways")
                val gateway = (0 until gateways.length()).map { gateways.getJSONObject(it) }.single { item ->
                    val configs = item.getJSONArray("providers")
                    (0 until configs.length()).any { index -> configs.getJSONObject(index).let {
                        it.getString("provider") == "google-ai-studio" && it.getString("alias") == "default"
                    } }
                }.getString("gateway")
                val account = Regex("/client/v4/accounts/([^/]+)/ai/v1/?")
                    .matchEntire(URI(saved.baseUrl).path)!!.groupValues[1]
                listOf("gemini_byok" to saved.copy(
                    baseUrl = "https://gateway.ai.cloudflare.com/v1/$account/$gateway/compat",
                    modelId = "google-ai-studio/gemini-3.8-flash", contractId = "generic-v1",
                    parameters = emptyMap(), gatewayMode = com.zero.v2.services.llm.GatewayMode.CF_BYOK,
                    cloudflareGatewayId = gateway, localLlamaPreflight = false,
                ))
            } else if (stage == "discovery") listOf("cf_qwen" to qwen)
                else listOf("configured_glm" to saved, "cf_qwen" to qwen)
            for ((label, selected) in candidates) {
                val profile = selected.copy(id = if (stage == "configured") selected.id else "acceptance-$label", maxTokens = if (stage in setOf("haiku_thinking", "luna_responses", "review_responses", "luna_reasoning")) 1536 else if (stage in setOf("luna_phone", "haiku_agent")) 256 else if (stage == "configured") selected.maxTokens else 4096, enabled = true)
                val model = ProfileLanguageModel(profile.id, profile, provider.llmClient(), provider.profiles())
                    .snapshotForTurn()
                suspend fun record(name: String, block: suspend (JSONObject) -> Unit): Boolean {
                    val row = JSONObject().put("case", "$label/$name").put("model", profile.modelId)
                    rows.put(row); flush()
                    return try {
                        withTimeout(180_000) { block(row) }
                        row.put("passed", true); flush(); true
                    } catch (failure: Exception) {
                        row.put("passed", false).put("errorClass", failure.javaClass.simpleName)
                            .put("error", failure.message.orEmpty().let { if (key.isEmpty()) it else it.replace(key, "[redacted]") }
                                .replace(saved.baseUrl, "[route]").take(ERROR_LIMIT))
                        flush(); false
                    }
                }
                if (stage in setOf("matrix", "gemini", "configured", "lmstudio", "dashscope", "mimo", "luna_phone", "haiku_thinking", "luna_responses", "review_responses")) {
                val sync = record("sync") { row ->
                    reserve()
                    val answer = model.chatSync(if (stage == "mimo")
                        "Calculate 19 plus 23 and explain briefly." else "Reply ZERO_ACCEPT_OK only.")
                    row.put("answer", answer.take(300))
                    check(answer.contains(if (stage == "mimo") "42" else "ZERO_ACCEPT_OK") &&
                        !answer.startsWith("[错误"))
                }
                if (!sync) continue
                val sse = record("sse") { row ->
                    reserve()
                    val text = StringBuilder()
                    var final = false
                    val images = if (stage == "review_responses") {
                        val bitmap = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(android.graphics.Color.GREEN)
                        val bytes = java.io.ByteArrayOutputStream()
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bytes)
                        bitmap.recycle()
                        listOf(com.zero.v2.core.common.Attachment("synthetic://green", "image/png",
                            base64Data = "data:image/png;base64," + android.util.Base64.encodeToString(
                                bytes.toByteArray(), android.util.Base64.NO_WRAP)))
                    } else emptyList()
                    model.chatStream(listOf(ChatMessage(role = MessageRole.USER, attachments = images,
                        content = if (stage == "review_responses") "Name the solid color in this image. Reply GREEN only."
                        else if (stage == "mimo")
                        "What is 6 multiplied by 7? Give the numerical answer." else "Reply ZERO_STREAM_OK only.")),
                        null, emptyList()).collect { chunk ->
                        chunk.contentDelta?.let(text::append)
                        final = final || chunk.isFinal
                        check(chunk.errorCode == null)
                    }
                    row.put("answer", text.toString().take(300)).put("final", final)
                    check(final && text.contains(if (stage == "review_responses") "GREEN" else if (stage == "mimo") "42" else "ZERO_STREAM_OK"))
                }
                if (!sse) continue
                }
                record("agent_two_tools") { row ->
                    val engine = entry.chatBot()
                    val contextConfig = entry.contextBuilder().config as ContextBuilderConfig
                    val chatConfig = engine.config as ChatBotModuleConfig
                    val oldEvidence = contextConfig.evidenceAutoInject
                    val oldDiscovery = contextConfig.toolDiscoveryEnabled
                    val oldRounds = chatConfig.maxFcRounds
                    val seen = mutableListOf<String>()
                    var persistedReplayCount = 0
                    val tool = FunctionDefinition("zero_acceptance_probe", if (stage == "mimo")
                        "Read a predefined record value for record 1 or 2; step is the record number."
                        else "Return a synthetic verification token for step 1 or 2.",
                        mapOf("type" to "object", "properties" to mapOf("step" to mapOf("type" to "string")),
                            "required" to listOf("step")))
                    entry.toolRegistrar().register(tool, "chatbot") { values ->
                        val step = values["step"].toString()
                        check(step in listOf("1", "2") && step !in seen)
                        seen += step
                        FunctionResult.success(if (step == "1") "TOKEN_ALPHA" else "TOKEN_BETA")
                    }
                    val counted = object : com.zero.v2.services.llm.ILanguageModel by model {
                        // Only the isolated test selector enables the existing disabled profile.
                        override val enabled = true
                        override fun chatStream(messages: List<ChatMessage>, systemPrompt: String?,
                            functions: List<FunctionDefinition>, prefixVersion: String?) = kotlinx.coroutines.flow.flow {
                            reserve()
                            val replay = if (stage in setOf("haiku_thinking", "luna_responses", "review_responses", "luna_reasoning")) messages.map { message ->
                                val encoded = com.zero.v2.app.data.StoredMessageCodec.encode(message)
                                val stored = File(context.filesDir, "llm-$stage-signed-replay.json")
                                stored.writeText(JSONObject().put("metadata", encoded ?: JSONObject.NULL).toString())
                                val disk = JSONObject(stored.readText())
                                val restored = com.zero.v2.app.data.StoredMessageCodec.restore(
                                    message.copy(providerContent = null, toolCalls = emptyList(), toolCallId = null,
                                        toolCallName = null, thinking = null),
                                    if (disk.isNull("metadata")) null else disk.getString("metadata"))
                                check(restored == message) { "native_android_storage_roundtrip_mismatch" }
                                if (restored.providerContent != null) persistedReplayCount++
                                restored
                            } else messages
                            model.chatStream(replay, systemPrompt, functions, prefixVersion).collect { emit(it) }
                        }
                        override fun snapshotForTurn(): com.zero.v2.services.llm.ILanguageModel = this
                    }
                    entry.modelSelector().register(counted)
                    try {
                        contextConfig.evidenceAutoInject = false
                        contextConfig.toolDiscoveryEnabled = stage == "discovery"
                        chatConfig.maxFcRounds = if (stage == "discovery") 4 else 3
                        engine.initialize(if (stage == "mimo") "Use zero_acceptance_probe to look up the requested records."
                            else "Follow the user's verification steps. Only discover and execute zero_acceptance_probe.")
                        engine.loadHistory("acceptance-$label", emptyList())
                        val discoveryPrompt = if (stage == "discovery")
                            "First call discover_tools for zero_acceptance_probe, then use invoke_tool to execute it. " else ""
                        val prompt = if (stage == "mimo")
                            "Please look up records 1 and 2 using zero_acceptance_probe, one at a time. Summarize both returned values."
                        else discoveryPrompt + "Call zero_acceptance_probe with step 1. After reading its result, " +
                            "call it with step 2. Then reply with both tokens from the tool results. Do not invent tokens."
                        val reasoningTask = if (stage in setOf("luna_reasoning", "review_responses"))
                            "Find the smallest positive integer n such that n modulo 17 is 3, modulo 19 is 5, " +
                                "and modulo 23 is 7. Give n in your final answer. Solve this before the first tool call. "
                            else ""
                        engine.sendMessage(reasoningTask + prompt,
                            listOf(tool)).collect(engine::applyStreamChunk)
                        val history = engine.chatHistory.value.messages
                        row.put("executedSteps", JSONArray(seen))
                            .put("toolResultCount", history.count { it.role == MessageRole.TOOL })
                            .put("answer", history.lastOrNull()?.content?.take(500))
                        if (stage == "gemini") {
                            val calls = history.flatMap { it.toolCalls.orEmpty() }
                            row.put("signatureCount", calls.count { it.providerState != null })
                            check(calls.size == 2 && calls.all { it.providerState != null }) {
                                "real_google_tool_signature_missing"
                            }
                        }
                        if (stage in setOf("luna_responses", "review_responses", "luna_reasoning")) {
                            val shapes = JSONArray()
                            val encrypted = history.mapNotNull { it.providerContent }.sumOf { state ->
                                val items = JSONArray(state.contentJson)
                                (0 until items.length()).count { i -> items.getJSONObject(i).let {
                                    shapes.put(JSONObject().put("type", it.optString("type"))
                                        .put("keys", JSONArray(it.keys().asSequence().toList()))
                                        .put("hasEncryptedContent", !it.isNull("encrypted_content") &&
                                            it.optString("encrypted_content").isNotEmpty()))
                                    it.optString("type") == "reasoning" && !it.isNull("encrypted_content") &&
                                        it.optString("encrypted_content").isNotEmpty()
                                } }
                            }
                            row.put("outputShapes", shapes).put("encryptedReasoningCount", encrypted).put("persistedReplayCount", persistedReplayCount)
                            check(encrypted > 0 && persistedReplayCount >= 2) { "responses_reasoning_replay_not_observed" }
                        }
                        if (stage == "haiku_thinking") {
                            val native = history.mapNotNull { it.providerContent }
                            val signed = native.sumOf { state ->
                                val blocks = JSONArray(state.contentJson)
                                (0 until blocks.length()).count { i -> blocks.getJSONObject(i).let {
                                    it.optString("type") == "thinking" && it.optString("signature").isNotEmpty()
                                } }
                            }
                            row.put("signedBlockCount", signed).put("persistedReplayCount", persistedReplayCount)
                            check(signed > 0 && persistedReplayCount >= 2) { "native_signature_replay_not_observed" }
                        }
                        check(seen == listOf("1", "2"))
                        check(history.count { it.role == MessageRole.TOOL } == if (stage == "discovery") 3 else 2)
                        check(history.last().content.orEmpty().contains("TOKEN_ALPHA"))
                        check(history.last().content.orEmpty().contains("TOKEN_BETA"))
                    } finally {
                        contextConfig.evidenceAutoInject = oldEvidence
                        contextConfig.toolDiscoveryEnabled = oldDiscovery
                        chatConfig.maxFcRounds = oldRounds
                        entry.toolRegistrar().unregister(tool.name)
                        entry.modelSelector().unregister(profile.id)
                    }
                }
            }
        }
        report.put("requestsTotal", JSONObject(journal.readText()).getInt("requests"))
        report.put("finished", true); flush()
        if (stage != "diagnostic") assertTrue("See device-private llm-u6-$stage.json",
            rows.length() == (if (stage == "matrix") 6 else if (stage in setOf("gemini", "configured", "lmstudio", "dashscope", "mimo", "luna_phone", "haiku_thinking", "luna_responses", "review_responses")) 3 else 1) &&
                (0 until rows.length()).all { rows.getJSONObject(it).optBoolean("passed") })
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val ERROR_LIMIT = 2000
        private const val REQUEST_CAP = 18
    }
}
