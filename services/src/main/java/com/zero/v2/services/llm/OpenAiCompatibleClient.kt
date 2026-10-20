package com.zero.v2.services.llm

import com.zero.v2.core.common.CacheObservation
import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.StreamChunk
import com.zero.v2.core.common.TokenUsage
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import com.zero.v2.services.embedding.EmbeddingAuthorizationException
import com.zero.v2.services.security.CredentialStore
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Provider 差异适配器接口 — 开发前规范化 N2.1。
 *
 * 只管"该 provider 特有的请求字段构造/流字段解析"，通用协议（messages/tools/SSE/鉴权）交给 [OpenAiCompatibleClient]。各家实现在 N2.2+
 * 落地： DeepSeek: thinking.type + reasoning_effort（D28） Qwen: extra_body enable_thinking +
 * thinking_budget（D28） MiMo TTS: audio.format/voice + delta.audio.data 解析
 */
interface LlmAdapter {
    /** 匹配 LlmProfile.id（一 adapter 服务一 provider） */
    val providerId: String

    /**
     * 把该 provider 的差异字段合并进请求体（在通用字段之后执行，可覆盖）。 stream=false（sync 快速查询）时按需跳过重型字段（如 DeepSeek
     * 思考控制——旧行为保持）。
     */
    fun buildExtraBody(
        body: JSONObject,
        profile: LlmProfile,
        stream: Boolean,
    ) {}

    /** 该 provider 特有的流增量解析（如 MiMo delta.audio）；通用 content/thinking/tool_calls 已由 client 处理 */
    fun parseStreamExtra(delta: JSONObject): Any? = null
}

/**
 * OpenAI 兼容统一客户端 — 开发前规范化 N2.1（协议层，D59 同族 / 落地计划 N2）。
 *
 * 一个客户端承载全部 LLM-API 调用（chat 流式/非流 + embeddings），profile 驱动：
 * - 统一 SSE 解析（行为等价复刻自 DeepSeekAdapter：content/reasoning_content/tool_calls/usage/finish）
 * - 统一鉴权（SecureKeyStore ref→fallback 链）
 * - 统一可观测（llm_call / llm_call_done 埋点）
 * - 统一用量计量（usageTotal StateFlow —— N5 BudgetGuard 的全 API 计量咽喉）
 *
 * 双轨：既有 DeepSeek/Qwen Adapter 暂不动，N2.2+ 逐 provider 迁移后再删。
 */
@Singleton
class OpenAiCompatibleClient
@Inject
constructor(
    @Named("llm") private val okHttpClient: OkHttpClient,
    private val secureKeyStore: CredentialStore,
    private val traceManager: AgentTraceManager,
    private val budgetGuard: com.zero.v2.services.agent.BudgetGuard,
    internal val requestContext: RequestContextPreparation,
    val requestHealth: ModelRequestHealth,
) {
    private val usageLedger
        get() = requestContext.usageLedger

    companion object {
        /** trace 中 prefixVersion 截断长度（hex 前 12 位——可观测，不记原 prompt）。 */
        private const val BOUNDED_OUTPUT = 128
        private const val PREFIX_TAKE = 12
        private const val NANOS_PER_MILLISECOND = 1_000_000
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    /** 全 API 累计用量（N5 BudgetGuard 计量点；chat/embeddings 每次调用后累加） */
    private val _usageTotal = MutableStateFlow(TokenUsage())
    val usageTotal: StateFlow<TokenUsage> = _usageTotal.asStateFlow()

    /** M3：content 数组 codec（OpenAI 兼容图输入；协议差异收在此，不在 ChatBot if(provider)） */
    private val observedHttpClient = okHttpClient.newBuilder().addInterceptor(requestHealth.interceptor()).build()
    private val planHttpClient = com.zero.v2.services.chatgpt.ChatGptInferenceHttp.client
            .newBuilder().addInterceptor(requestHealth.interceptor()).build()

    /** 解析 profile 的 API Key（ref → fallback 链；null = 未配置） */
    fun resolveApiKey(profile: LlmProfile): String? =
        if (profile.kind == LlmProfile.Kind.CHAT && profile.authMode == ConnectionAuth.NONE) null
        else
            secureKeyStore.getOrFallbackString(profile.apiKeyRef, null)
                ?: profile.apiKeyRefFallback?.let { secureKeyStore.getOrFallbackString(it, null) }

    val credentialRevision: Long
        get() = secureKeyStore.revision

    // ═══════════════ chat · 流式 ═══════════════

    @Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
    // LongParameterList: C1 加 prefixVersion 参数（provider 协议观测，设计§4.1）；7 参数为流式 chat 协议必然
    // LongMethod/CyclomaticComplexMethod: chatStream 含 SSE 流解析+tool call 增量+reasoning+usage/cache
    // 归一（C1/M3）；
    //   预存复杂协议方法，拆分破坏单一职责
    fun chatStream(
        profile: LlmProfile,
        messages: List<ChatMessage>,
        systemPrompt: String?,
        functions: List<FunctionDefinition>,
        apiKey: String? = null,
        prefixVersion: String? = null,
        planLease: com.zero.v2.services.chatgpt.ChatGptLease? = null,
    ): Flow<StreamChunk> {
        // Existing SSE decoder, scoped here so retries share the exact frozen request inputs.
        @Suppress("LongMethod", "CyclomaticComplexMethod")
        fun attempt(profile: LlmProfile): Flow<StreamChunk> = kotlinx.coroutines.flow.flow {
                    val plan = ChatGptPlanProtocol.enabled(profile)
                    check(!plan || planLease != null) { "ChatGPT套餐必须使用独立账号连接" }
                    val key = if (plan) requireNotNull(planLease).token()
                        else apiKey ?: resolveApiKey(profile)
                    check(CloudflareChatTransport.credentialReady(profile, key)) {
                        "${profile.displayName} API Key 未配置"
                    }
                    val requestMessages = requestContext.restore(profile, messages)
                    val body =
                        ChatRequestBody.build( profile, systemPrompt, requestMessages, stream = true, functions)
                            .also { requestContext.prepare(profile, it, key, requestMessages) }
                    val cacheRequestId = java.util.UUID.randomUUID().toString()
                    val continuity = requestContext.describe(profile, body, key, requestMessages)
                    val continuityReason = listOfNotNull(
                                requestContext.takeEvent(profile, key, requestMessages),
                                continuity.substringAfter("reason=").substringBefore(" ")).joinToString("/")
                    traceManager.record(
                        "llm_call",
                        NodeType.SYSTEM_EVENT,
                        profile.id,
                        "$continuity request=$cacheRequestId")
                    val budgetDiagnostic = requestContext.budgetDiagnostic(profile, body, key, requestMessages)
                    traceManager.record(
                        "llm_call",
                        NodeType.SYSTEM_EVENT,
                        profile.id,
                        "request_budget: request=$cacheRequestId $budgetDiagnostic")
                    usageLedger?.begin(
                        cacheRequestId,
                        profile,
                        "stream",
                        continuityReason,
                        budgetDiagnostic.substringAfter("source=").substringBefore(" "))
                    var cacheRecorded = false
                    var latestUsage: TokenUsage? = null
                    suspend fun recordCache(status: String, observed: TokenUsage?) {
                        if (cacheRecorded) return
                        cacheRecorded = true
                        finishCompletion(profile, cacheRequestId, status, observed)
                        if (status == "completed" && observed != null) {
                            requestContext.observe( profile, body, key, requestMessages, observed.inputTokens)
                        }
                        traceManager.record(
                            "llm_call",
                            NodeType.SYSTEM_EVENT,
                            profile.id,
                            "cache_observation: request=$cacheRequestId model=${profile.modelId} " +
                                "contract=${profile.contractId} prefix=${prefixVersion ?: "unknown"} status=$status " +
                                "input=${observed?.inputTokens ?: "unknown"} " +
                                "hit=" +
                                "${observed?.takeIf { it.cacheObservedInputTokens > 0 }?.cachedTokens ?: "unknown"} " +
                                "observedInput=${observed?.cacheObservedInputTokens ?: 0} " +
                                    "continuity=$continuityReason")
                    }
                    val call = (if (plan) planHttpClient else observedHttpClient).newCall(
                            CloudflareChatTransport.request(profile, key, stream = true).tag(
                                    ModelRequestHealth.Identity::class.java,
                                    requestHealth.identity(profile, key, "chat_stream"))
                                .post(body.toString().toRequestBody(JSON_MEDIA)).build())
                    kotlinx.coroutines.coroutineScope {
                        val accountWatcher = if (plan)
                                launch(Dispatchers.Default) {
                                    requireNotNull(planLease).awaitInvalidation()
                                    call.cancel()
                                }
                            else null
                        val cancellation = launch(
                                Dispatchers.Default,
                                start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED,
                            ) {
                                try {
                                    kotlinx.coroutines.awaitCancellation()
                                } finally {
                                    call.cancel()
                                }
                            }
                        try {
                            call.execute().use { response ->
                                if (!response.isSuccessful) {
                                    if (plan)
                                        throw com.zero.v2.services.chatgpt.ChatGptInferenceHttp.failure(response)
                                    val raw = response.body?.string().orEmpty()
                                    val error = ProviderErrorMessage.redacted(raw, key)
                                    traceManager.record(
                                        "llm_call",
                                        NodeType.ERROR,
                                        profile.id,
                                        "http_${response.code}: $error",)
                                    throw java.io.IOException( "${profile.displayName} HTTP ${response.code}: $error")
                                }
                                val reader = response.body?.charStream()?.buffered()
                                        ?: throw java.io.IOException("provider_empty_body")
                                var completed = false
                                var finishReason: String? = null
                                var usage: TokenUsage? = null
                                var nativeContentComplete = false
                                val gemini = if (GeminiNativeProtocol.enabled(profile))
                                        GeminiNativeStream( profile.modelId, ProviderToolState.scope(profile))
                                    else null
                                val responses = if (ResponsesProtocol.enabled(profile)) {
                                        ResponsesStream(
                                            profile.modelId,
                                            ProviderToolState.scope(profile),
                                            if (plan) ChatGptPlanProtocol.NAMESPACE else null)
                                    } else null
                                val anthropic = if (profile.effectiveGatewayMode() != GatewayMode.DIRECT &&
                                        profile.modelId.startsWith("anthropic/")) {
                                        CloudflareAnthropicStream(
                                            profile.modelId,
                                            if (AnthropicNativeProtocol.enabled(profile))
                                                ProviderToolState.scope(profile)
                                            else null)
                                    } else null
                                while (true) {
                                    val line = reader.readLine() ?: break
                                    if (!line.startsWith("data:")) continue
                                    val data = line.removePrefix("data:").trim()
                                    if (data == "[DONE]") {
                                        completed = true
                                        break
                                    }
                                    val event = if (anthropic != null) JSONObject(data) else null
                                    val chunks = if (responses != null)
                                            try {
                                                responses.decode(JSONObject(data))
                                            } catch (failure: ResponsesFailure) {
                                                recordCache( failure.message.orEmpty(), failure.usage)
                                                traceManager.record(
                                                    "llm_call",
                                                    NodeType.ERROR,
                                                    profile.id,
                                                    "responses_terminal: reason=${failure.message} " +
                                                        "limit=${profile.maxTokens} " +
                                                        "input=${failure.usage?.inputTokens} " +
                                                        "output=${failure.usage?.outputTokens} " +
                                                        "cache=${failure.usage?.cacheSummary ?: "unknown"}")
                                                val failedUsage = failure.usage?.copy(model = profile.modelId)
                                                        ?: TokenUsage(
                                                            model = profile.modelId,
                                                            cacheUnknownSamples = 1,
                                                            inputObserved = false,
                                                            outputObserved = false)
                                                emit(StreamChunk(tokenUsage = failedUsage))
                                                throw failure
                                            }
                                        else if (gemini != null) {
                                            val nativeEvent = JSONObject(data)
                                            nativeEvent.optJSONObject("usageMetadata")?.let {
                                                usage = ProviderUsage.read(
                                                        GeminiNativeProtocol.usage(it),
                                                        profile.modelId)
                                                latestUsage = usage
                                            }
                                            gemini.decode(nativeEvent)
                                        } else if (event?.has("type") == true)
                                            anthropic!!.decode(event)
                                        else
                                            OpenAiStreamDecoder.decode(
                                                data,
                                                profile.modelId,
                                                ProviderToolState.scope(profile))
                                    for (chunk in chunks) {
                                        planLease?.checkCurrent()
                                        if (chunk.providerContent != null)
                                            nativeContentComplete = true
                                        chunk.finishReason?.let { finishReason = it }
                                        if (chunk.tokenUsage != null) {
                                            usage = chunk.tokenUsage
                                            latestUsage = chunk.tokenUsage
                                        } else emit(chunk)
                                    }
                                    if (responses?.completed == true) break
                                    if (gemini?.complete == true) break
                                }
                                val anthropicIncomplete = AnthropicNativeProtocol.enabled(profile) &&
                                        !nativeContentComplete
                                planLease?.checkCurrent()
                                val nativeIncomplete = anthropicIncomplete ||
                                        responses?.completed == false ||
                                        gemini?.complete == false
                                if ((!completed && finishReason == null) || nativeIncomplete) {
                                    throw java.io.IOException("provider_stream_interrupted")
                                }
                                usage?.let { value ->
                                    val observation = CacheObservation.fromUsage(
                                            value.cacheObservedInputTokens > 0,
                                            value.inputTokens,
                                            value.cachedTokens,
                                            (value.inputTokens - value.cachedTokens).coerceAtLeast( 0),
                                            value.outputTokens,
                                            value.totalTokens,)
                                    traceManager.record(
                                        "llm_call",
                                        NodeType.LLM_OUTPUT,
                                        profile.id,
                                        "llm_call_done: prefix=${prefixVersion?.take(PREFIX_TAKE) ?: "n/a"} " +
                                            "input=${value.inputTokens} hit=${value.cachedTokens} " +
                                            "observedInput=${value.cacheObservedInputTokens} " +
                                            "cache=${observation.ratioLabel}")
                                }
                                    ?: traceManager.record(
                                        "llm_call",
                                        NodeType.LLM_OUTPUT,
                                        profile.id,
                                        "llm_call_done: prefix=${prefixVersion?.take(PREFIX_TAKE) ?: "n/a"}" +
                                            " cache=unknown")
                                recordCache("completed", usage)
                                val finalUsage = usage
                                        ?: TokenUsage(
                                            model = profile.modelId,
                                            cacheUnknownSamples = 1,
                                            inputObserved = false,
                                            outputObserved = false)

                                emit(
                                    StreamChunk(
                                        tokenUsage = finalUsage,
                                        finishReason = finishReason,
                                        isFinal = true))
                            }
                        } catch (failure: java.io.IOException) {
                            // Closing a cancelled blocking socket throws IOException. Preserve the
                            // coroutine cancellation instead of turning a user stop into a chat
                            // error.
                            currentCoroutineContext().ensureActive()
                            planLease?.checkCurrent()
                            if (!cacheRecorded) {
                                val unknown = latestUsage
                                        ?: TokenUsage(
                                            model = profile.modelId,
                                            cacheUnknownSamples = 1,
                                            inputObserved = false,
                                            outputObserved = false)
                                emit(StreamChunk(tokenUsage = unknown))
                            }
                            throw failure
                        } finally {
                            if (!cacheRecorded) {
                                recordCache(
                                    if (currentCoroutineContext().isActive) "failed"
                                    else "cancelled",
                                    latestUsage)
                            }
                            cancellation.cancel()
                            accountWatcher?.cancel()
                            call.cancel()
                        }
                    }
                }.flowOn(Dispatchers.IO)
        return ResponsesRecovery.stream(
            profile,
            onRetry = { next ->
                val verdict = budgetGuard.preCheck("responses_budget_retry")
                if (verdict != com.zero.v2.services.agent.BudgetGuard.Verdict.OK) {
                    throw java.io.IOException("responses_retry_budget_exceeded")
                }
                traceManager.record(
                    "llm_call",
                    NodeType.SYSTEM_EVENT,
                    profile.id,
                    "responses_budget_retry: attempts=1 outputLimit=$next")
            },
            attempt = ::attempt)
    }

    // ═══════════════ chat · 非流式 ═══════════════

    suspend fun chatSync(
        profile: LlmProfile,
        userMessage: String,
        systemPrompt: String? = null,
        apiKey: String? = null,
    ): String = withContext(Dispatchers.IO) {
            require(!ChatGptPlanProtocol.enabled(profile)) { "ChatGPT同步调用须经套餐模型聚合SSE" }
            val key = apiKey ?: resolveApiKey(profile)
            if (!CloudflareChatTransport.credentialReady(profile, key)) {
                return@withContext "[错误: ${profile.displayName} API Key 未配置]"
            }
            val messages =
                listOf( ChatMessage( role = com.zero.v2.core.common.MessageRole.USER, content = userMessage))
            val body =
                ChatRequestBody.build( profile, systemPrompt, messages, stream = false, functions = emptyList())
                    .also { requestContext.prepare(profile, it, key, messages) }
            traceManager.record(
                "llm_call",
                NodeType.LLM_OUTPUT,
                profile.id,
                "provider=${profile.id} model=${profile.modelId} sync=true",)
            val requestId = java.util.UUID.randomUUID().toString()
            usageLedger?.begin(requestId, profile, "sync", "independent", "conservative")
            var ledgerUsage: TokenUsage? = null
            var ledgerStatus = "failed"
            try {
                val resp = observedHttpClient.newCall(
                            CloudflareChatTransport.request(profile, key).tag(
                                    ModelRequestHealth.Identity::class.java,
                                    requestHealth.identity(profile, key, "chat_sync"))
                                .addHeader("Content-Type", "application/json; charset=utf-8")
                                .post(body.toString().toRequestBody(JSON_MEDIA)).build(),
                        ).execute()
                val json = normalizeCompletion(ProviderErrorMessage.chatResponse(resp, key))
                json.optJSONObject("usage")?.let { u ->
                    ledgerUsage = ProviderUsage.read(u, profile.modelId)
                }
                ledgerStatus = "completed"
                json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                    ?.optString("content", "") ?: ""
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                ledgerStatus = "cancelled"
                throw cancelled
            } catch (e: Exception) {
                ledgerUsage = failureUsage(e) ?: ledgerUsage
                traceManager.record(
                    "llm_call",
                    NodeType.ERROR,
                    profile.id,
                    "sync_failed: ${e.message?.take(ProviderErrorMessage.MAX_TEXT_LENGTH)}")
                "[错误: ${e.message?.take(ProviderErrorMessage.MAX_TEXT_LENGTH)}]"
            } finally {
                finishCompletion(profile, requestId, ledgerStatus, ledgerUsage)
            }
        }

    // ═══════════════ embeddings（DashScope 兼容 /v1/embeddings，N2.5 迁移接入）═══════════════

    /**
     * One tools-free request, hard HTTP deadline and output cap; never changes the saved profile.
     */
    suspend fun boundedCompletion(
        profile: LlmProfile,
        userMessage: String,
        systemPrompt: String,
        apiKey: String? = null,
    ): String? = withContext(Dispatchers.IO) {
            if (ChatGptPlanProtocol.enabled(profile)) return@withContext null
            val key = apiKey ?: resolveApiKey(profile)
            if (!CloudflareChatTransport.credentialReady(profile, key)) return@withContext null
            val limited = ModelContracts.get(profile.contractId).bounded(
                        profile.copy(
                            maxTokens = minOf(BOUNDED_OUTPUT, profile.maxOutputTokens),
                            temperature = 0f,
                            enableThinking = false))
            val body = boundedBody(limited, userMessage, systemPrompt)
            requestContext.prepare(limited, body, key, emptyList())
            val request = CloudflareChatTransport.request(profile, key).tag(
                        ModelRequestHealth.Identity::class.java,
                        requestHealth.identity(profile, key, "bounded_completion"))
                    .post(body.toString().toRequestBody(JSON_MEDIA)).build()
            val requestId = java.util.UUID.randomUUID().toString()
            usageLedger?.begin(requestId, limited, "bounded", "independent", "conservative")
            var ledgerUsage: TokenUsage? = null
            var ledgerStatus = "failed"
            try {
                val started = System.nanoTime()
                val response = executeBounded(request, profile)?.let {
                        runCatching { normalizeCompletion(it) }
                            .onFailure { ledgerUsage = failureUsage(it) ?: ledgerUsage }.getOrNull()
                    }
                val elapsed = (System.nanoTime() - started) / NANOS_PER_MILLISECOND
                traceManager.record(
                    "retrieval_rank",
                    NodeType.SYSTEM_EVENT,
                    profile.id,
                    "bounded_completion elapsedMs=$elapsed response=${response != null}")
                if (response == null) return@withContext null
                ledgerStatus = "completed"
                response.optJSONObject("usage")?.let { usage ->
                    ledgerUsage = ProviderUsage.read(usage, profile.modelId)
                    traceManager.record(
                        "retrieval_rank",
                        NodeType.SYSTEM_EVENT,
                        profile.id,
                        "usage input=${usage.optLong("prompt_tokens")} " +
                            "cache=${ProviderUsage.read(usage, profile.modelId).cacheSummary} " +
                            "output=${usage.optLong("completion_tokens")}")
                }
                val content = response.optJSONArray("choices")?.optJSONObject(0)
                        ?.optJSONObject("message")?.optString("content")
                val slots = runCatching { org.json.JSONArray(content.orEmpty()).length() }.getOrNull()
                traceManager.record(
                    "retrieval_rank",
                    NodeType.SYSTEM_EVENT,
                    profile.id,
                    "rank_response slots=$slots")
                content
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                ledgerStatus = "cancelled"
                throw cancelled
            } finally {
                finishCompletion(profile, requestId, ledgerStatus, ledgerUsage)
            }
        }

    private suspend fun executeBounded(request: okhttp3.Request, profile: LlmProfile): JSONObject? =
        BoundedCompletionTransport.execute(observedHttpClient, request) { outcome ->
            traceManager.record("retrieval_rank", NodeType.SYSTEM_EVENT, profile.id,
                "bounded_transport outcome=$outcome")
        }

    suspend fun embeddings(
        profile: LlmProfile,
        texts: List<String>,
        purpose: com.zero.v2.services.embedding.EmbeddingPurpose =
            com.zero.v2.services.embedding.EmbeddingPurpose.DOCUMENT,
    ): List<FloatArray?> {
        val key = resolveApiKey(profile) ?: return texts.map { null }
        return EmbeddingTransport(observedHttpClient, requestHealth, traceManager)
            .execute(profile, texts, purpose, key) { accumulateUsage(it, profile) }
    }

    // ═══════════════ TTS · 流式音频（MiMo 型 OpenAI 兼容 TTS，N2.4 迁移接入）═══════════════

    /** TTS 流式统计（对齐旧 MimoTtsClient.MimoStats） */
    data class TtsStats(val firstChunkMs: Long, val chunks: Int, val totalMs: Long)

    /**
     * 流式语音合成：SSE 逐块 base64 pcm16 → 解码即回调 [onPcm]（挂起回调 = 天然背压， v6.16/D54：回调内可挂起——播报方在回调中插静默探针窗）。
     * 协议复刻自 MimoTtsClient.streamPcm（行为等价）：assistant 消息载文 + adapter 注 audio 字段。
     *
     * @param shouldStop 轮询停止（打断/挂断）→ 提前中断读取；用户主动打断致连接中断不算错误
     * @throws IllegalStateException Key 未配置 / HTTP 错误
     */
    suspend fun ttsStreamPcm(
        profile: LlmProfile,
        text: String,
        adapter: LlmAdapter,
        shouldStop: () -> Boolean,
        apiKey: String? = null,
        onPcm: suspend (ByteArray) -> Unit,
    ): TtsStats = withContext(Dispatchers.IO) {
            val key = apiKey
                    ?: resolveApiKey(profile)
                    ?: throw IllegalStateException("${profile.displayName} API Key 未配置")
            val body = JSONObject().apply {
                    put("model", profile.modelId)
                    put("stream", true)
                    put( "messages", JSONArray().put( JSONObject().put("role", "assistant").put("content", text), ),)
                    adapter.buildExtraBody(this, profile, stream = true)
                }
            traceManager.record(
                "llm_call",
                NodeType.LLM_OUTPUT,
                profile.id,
                "provider=${profile.id} tts=true chars=${text.length}",)
            val t0 = android.os.SystemClock.elapsedRealtime()
            var firstChunkMs = -1L
            var chunks = 0
            try {
                observedHttpClient.newCall(
                        Request.Builder().tag(
                                ModelRequestHealth.Identity::class.java,
                                requestHealth.identity(profile, key, "tts_stream"))
                            .url("${profile.baseUrl}/chat/completions").addHeader("Authorization", "Bearer $key")
                            .addHeader("Content-Type", "application/json; charset=utf-8")
                            .post(body.toString().toRequestBody(JSON_MEDIA)).build(),
                    ).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            val err = runCatching { resp.body?.string()?.take(120) }.getOrNull() ?: ""
                            throw IllegalStateException("MiMo HTTP ${resp.code}: $err")
                        }
                        val source = resp.body ?: throw IllegalStateException("MiMo 空响应体")
                        // SSE 行协议：`data: {json}` … `data: [DONE]`
                        while (true) {
                            if (shouldStop()) break
                            val line = source.source().readUtf8Line() ?: break
                            if (!line.startsWith("data:")) continue
                            val payload = line.removePrefix("data:").trim()
                            if (payload == "[DONE]") break
                            val b64 = runCatching {
                                        JSONObject(payload).optJSONArray("choices")
                                            ?.optJSONObject(0)?.optJSONObject("delta")
                                            ?.let { adapter.parseStreamExtra(it) } as? String
                                    }.getOrNull() ?: continue
                            if (firstChunkMs < 0)
                                firstChunkMs = android.os.SystemClock.elapsedRealtime() - t0
                            chunks++
                            onPcm(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
                        }
                    }
            } catch (e: java.io.IOException) {
                // 用户主动打断导致的连接中断不是错误；其余照抛
                if (!shouldStop()) {
                    traceManager.record(
                        "llm_call",
                        NodeType.ERROR,
                        profile.id,
                        "network_error: ${e.message?.take(60)}",)
                    throw e
                }
            }
            TtsStats(firstChunkMs, chunks, android.os.SystemClock.elapsedRealtime() - t0)
        }

    // ═══════════════ ASR · 音频转写流（S4.1 / D121 · C2-G4 终稿端点）═══════════════

    /**
     * 音频转写流式：/v1/chat/completions + 音频 content part + stream:true（C2/G4 终稿—— MiMo 实测端点，非
     * multipart、非 /audio/transcriptions）；SSE delta.content 增量回调
     * [onPartial]（ONLINE_PARTIAL），返回全文（ONLINE_FINAL）。transcriptions 型厂商经 adapter 兼容（后续按需）。 G16：wav
     * Base64 超 10MB（MiMo 单段官方限）→ 不发请求直接抛错，调用方走本地兜底。 usage 计量经 [accumulateUsage]（BudgetGuard 咽喉不旁路）。
     *
     * @throws IllegalStateException Key 未配置 / 段超限 / HTTP 错误
     * @throws java.io.IOException 网络错误（调用方本地回退）
     */
    suspend fun chatAudioStream( profile: LlmProfile, wavBytes: ByteArray, onPartial: (String) -> Unit, ): String =
        withContext(Dispatchers.IO) {
            val key = resolveApiKey(profile)
                    ?: throw IllegalStateException("${profile.displayName} API Key 未配置")
            val b64 = android.util.Base64.encodeToString(wavBytes, android.util.Base64.NO_WRAP)
            if (b64.length > 10 * 1024 * 1024) {
                error( "audio_segment_too_large: b64KB=${b64.length / 1024} (MiMo 10MB 限)")
            }
            val body = audioBody(profile, b64)
            traceManager.record(
                "llm_call",
                NodeType.LLM_OUTPUT,
                profile.id,
                "provider=${profile.id} asr=true wavKB=${wavBytes.size / 1024}",)
            val t0 = android.os.SystemClock.elapsedRealtime()
            val sb = StringBuilder()
            observedHttpClient.newCall(
                    Request.Builder().tag(
                            ModelRequestHealth.Identity::class.java,
                            requestHealth.identity(profile, key, "asr_stream"))
                        .url("${profile.baseUrl}/chat/completions").addHeader("Authorization", "Bearer $key")
                        .addHeader("Content-Type", "application/json; charset=utf-8")
                        .post(body.toString().toRequestBody(JSON_MEDIA)).build(),
                ).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val err = runCatching { resp.body?.string()?.take(120) }.getOrNull() ?: ""
                        traceManager.record(
                            "llm_call",
                            NodeType.ERROR,
                            profile.id,
                            "asr_http_${resp.code}: ${err.take(80)}",)
                        throw IllegalStateException("ASR HTTP ${resp.code}: $err")
                    }
                    val source = resp.body ?: throw IllegalStateException("ASR 空响应体")
                    // SSE 行协议：`data: {json}` … `data: [DONE]`（ttsStreamPcm 同款）
                    while (true) {
                        val line = source.source().readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val payload = line.removePrefix("data:").trim()
                        if (payload == "[DONE]") break
                        val json = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                        json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.let { delta ->
                                safeDelta(delta, "content")?.let {
                                    sb.append(it)
                                    onPartial(it)
                                }
                            }
                        json.optJSONObject("usage")?.let { u ->
                            if (u.length() > 0) {
                                accumulateUsage( ProviderUsage.read(u, profile.modelId), profile, )
                            }
                        }
                    }
                }
            traceManager.record(
                "llm_call",
                NodeType.LLM_OUTPUT,
                profile.id,
                "asr_done: chars=${sb.length} ${android.os.SystemClock.elapsedRealtime() - t0}ms",)
            sb.toString()
        }

    // ═══════════════ 内部 ═══════════════

    /** 累计用量（N5 BudgetGuard 计量——全 API 咽喉：chat/TTS/embeddings 每次调用后经守卫持久累计） */
    private fun accumulateUsage(u: TokenUsage, profile: LlmProfile) {
        _usageTotal.update { old -> old + u }
        budgetGuard.record(
            u,
            billable = !profile.localLlamaPreflight,
            costUsd = profile.tokenPrices.cost(u, AnthropicNativeProtocol.enabled(profile)),
            included = profile.tokenPrices.subscription && !profile.localLlamaPreflight)
    }

    private suspend fun finishCompletion(
        profile: LlmProfile, requestId: String, ledgerStatus: String, ledgerUsage: TokenUsage?
    ) {
                accumulateUsage(
                    ledgerUsage
                        ?: TokenUsage(
                            model = profile.modelId,
                            cacheUnknownSamples = 1,
                            inputObserved = false,
                            outputObserved = false),
                    profile)
                usageLedger?.finish(requestId, ledgerStatus, ledgerUsage)
    }


}

private fun audioBody(profile: LlmProfile, b64: String): JSONObject {
            val body = JSONObject().apply {
                    put("model", profile.modelId)
                    put("stream", true)
                    put(
                        "messages",
                        JSONArray().put(
                                JSONObject().apply {
                                    put("role", "user")
                                    put(
                                        "content",
                                        JSONArray().put(
                                                JSONObject().apply {
                                                    put("type", "input_audio")
                                                    put(
                                                        "input_audio",
                                                        JSONObject().apply {
                                                            put("data", b64)
                                                            put("format", "wav")
                                                        },)
                                                },),)
                                },),)
                }
return body
}

private fun boundedBody(limited: LlmProfile, userMessage: String, systemPrompt: String): JSONObject {
            val body = ChatRequestBody.build(
                    limited,
                    systemPrompt,
                    listOf( ChatMessage( role = com.zero.v2.core.common.MessageRole.USER, content = userMessage)),
                    stream = false,
                    functions = emptyList())
            // Bounded completion disables thinking where supported, otherwise requests minimum
            // effort.
            if (!GeminiNativeProtocol.enabled(limited))
                CloudflareChatTransport.applyContract(body, limited, true)
return body
}

    private fun normalizeCompletion(json: JSONObject): JSONObject = GeminiNativeProtocol.normalize(
            ResponsesProtocol.normalizeResponse(AnthropicNativeProtocol.normalizeResponse(json)))

    private fun safeDelta(
        json: JSONObject,
        key: String,
    ): String? {
        if (!json.has(key) || json.isNull(key)) return null
        return json.optString(key, "").ifEmpty { null }
    }

private class EmbeddingTransport(
    private val observedHttpClient: OkHttpClient,
    private val requestHealth: ModelRequestHealth,
    private val traceManager: AgentTraceManager,
) {
    // Embedding failures degrade to lexical retrieval; authorization and cancellation are rethrown first.
    @Suppress("TooGenericExceptionCaught")
    suspend fun execute(
        profile: LlmProfile,
        texts: List<String>,
        purpose: com.zero.v2.services.embedding.EmbeddingPurpose,
        key: String,
        onUsage: (TokenUsage) -> Unit,
    ): List<FloatArray?> = withContext(Dispatchers.IO) {
            if (texts.isEmpty()) return@withContext emptyList()
            try {
                val spec = EmbeddingRequestSpec.create(profile.baseUrl, profile.modelId, texts, purpose)
                val resp = observedHttpClient.newCall(
                            Request.Builder().tag(
                                    ModelRequestHealth.Identity::class.java,
                                    requestHealth.identity(profile, key, "embeddings")).url(spec.url)
                                .addHeader("Authorization", "Bearer $key")
                                .post(spec.body.toString().toRequestBody(JSON_MEDIA)).build(),
                        ).execute()
                resp.use {
                    if (!resp.isSuccessful) {
                        traceManager.record( "llm_call", NodeType.ERROR, profile.id, "embed_http_${resp.code}",)
                        if (resp.code == UNAUTHORIZED) throw EmbeddingAuthorizationException()
                        return@withContext texts.map { null }
                    }
                    val responseBody = resp.body?.string() ?: ""
                    EmbeddingResponseDecoder.usage(responseBody, profile.modelId)?.let {
                        onUsage(it)
                    }
                    EmbeddingResponseDecoder.decode(responseBody, texts.size, spec.native)
                }
            } catch (rejected: EmbeddingAuthorizationException) {
                throw rejected
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                traceManager.record(
                    "llm_call", NodeType.ERROR, profile.id, "embed_failed: ${e.message?.take(ERROR_LIMIT)}")
                texts.map { null }
            }
        }


    private companion object {
        const val UNAUTHORIZED = 401
        const val ERROR_LIMIT = 60
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

private fun failureUsage(failure: Throwable): TokenUsage? = when (failure) {
    is ResponsesFailure -> failure.usage
    is GeminiCompletionFailure -> failure.usage
    else -> null
}
