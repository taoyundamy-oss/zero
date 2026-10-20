package com.zero.v2.framework.chat

import android.util.Log
import com.zero.v2.core.bus.ChatBus
import com.zero.v2.core.bus.ChatBusImpl
import com.zero.v2.core.bus.MessagePipeline
import com.zero.v2.core.common.*
import com.zero.v2.core.moduleapi.*
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.AgentTraceEvent
import com.zero.v2.core.trace.NodeType
import com.zero.v2.core.moduleapi.ToolDispatcher
import com.zero.v2.core.moduleapi.ToolContextRequest
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.common.attachments
import com.zero.v2.services.llm.ModelCapability
import com.zero.v2.services.llm.ModelSelector
import com.zero.v2.services.retrieval.EvidencePolicy
import com.zero.v2.services.retrieval.EvidenceProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 对话引擎模块 — Zero v2 核心。
 *
 * 对标 Alife ChatBot + ChatActivity。
 * 职责：
 *   - 管理对话状态（ChatHistory + ChatState）
 *   - 调用 LLM（via QwenClient）
 *   - 处理 Tool Call 循环
 *   - 通过 ChatBus 发布事件（通知各模块）
 *   - 集成 MessagePipeline 进行消息预处理
 *
 * 线程安全：Mutex 确保同时只有一次生成。
 */
@Singleton
class ChatBotModule @Inject constructor(
    private val modelSelector: ModelSelector,
    private val functionRegistry: ToolDispatcher,
    private val toolContextSelector: com.zero.v2.core.moduleapi.ToolContextSelector,
    private val chatBus: ChatBus,
    private val messagePipeline: MessagePipeline,
    private val contextBuilder: ContextBuilderModule,
    private val traceManager: AgentTraceManager,
    private val attachmentReader: com.zero.v2.services.llm.StoredAttachmentReader,
    private val mediaAnalysis: com.zero.v2.services.llm.MediaAnalysisService,
    private val budgetGuard: com.zero.v2.services.agent.BudgetGuard,
    private val tokenBudgetAllocator: TokenBudgetAllocator,
    private val generationStore: GenerationProjectionStore,
    private val toolManifestStore: ToolManifestStore,
    private val memoryContextStore: MemoryContextStore,
    private val conversationSummarizer: ConversationSummarizer,
    /** R3：自动证据注入——回合入口跨域融合 Memory+Knowledge 短证据入 turnContext */
    private val evidenceProvider: EvidenceProvider,
    /** P3：动态角色状态——读风格指令注入 turnContext；reducer 接线在 onChatFinished（CharacterStateModule 内） */
    private val characterStateModule: CharacterStateModule,
    private val chronicleInputStore: com.zero.v2.core.data.store.ChronicleInputStore,
) : IZeroModule {

    companion object {
        private const val TAG = "ChatBotModule"
        /** N6.6 世代切片目标（预算占比）：90% 触发线 − 65% 切片目标 = 25% 增长空间（切换天然稀疏） */
        private const val SLICE_TARGET_RATIO = 0.65
    }

    override val metadata = ModuleMetadata(
        id = "chatbot",
        displayName = "对话引擎",
        description = "管理对话状态、调用 LLM、处理 Tool Call",
        category = ModuleCategory.CORE,
        launchOrder = -200  // 第一个启动
    )

    private val _lifecycle = MutableStateFlow(ModuleLifecycle.UNINITIALIZED)
    override val lifecycle = _lifecycle.asStateFlow()

    override var isEnabled: Boolean = true
    override val requiredPermissions: List<String> = emptyList()

    override val config = ChatBotModuleConfig()

    // ── 对话状态 ──
    private val mutex = Mutex()
    private val turnGate = TurnGate()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var systemPrompt: String = ""
    private val _chatHistory = MutableStateFlow(ChatHistory())
    val chatHistory: StateFlow<ChatHistory> = _chatHistory.asStateFlow()
    private val _chatState = MutableStateFlow(ChatState.IDLE)
    val chatState: StateFlow<ChatState> = _chatState.asStateFlow()
    private val _totalUsage = MutableStateFlow(TokenUsage())
    val totalUsage: StateFlow<TokenUsage> = _totalUsage.asStateFlow()

    // ═══ A1a D79：回合级上下文（不可变 TurnContext，AtomicReference 线程安全）═══
    // 入口设（sendMessage flow builder 内）→ finally 清；autonomous_notify FC 经 bridge.activeTurn() 校验
    val activeTurn = java.util.concurrent.atomic.AtomicReference<com.zero.v2.core.common.TurnContext?>(null)

    // ═══ N6.6 世代压缩：世代锚点（发送视图首条消息 id；null=当前世代=全量历史）═══
    // 世代内 append-only（前缀缓存命中铁律，X16 修订）；仅视图 est>90% 预算时切换一次
    // （锚点前移 + 被置换头部异步压缩为摘要）。会话切换时重置（loadHistory）。
    private var generationProjection = GenerationProjection()
    private var generationConversationId: String? = null
    private val turnContextProjection = TurnContextProjection()

    // ═══════════════════════════════════════
    // 生命周期
    // ═══════════════════════════════════════

    override suspend fun onAwake() { _lifecycle.value = ModuleLifecycle.AWAKE }
    override suspend fun onStart() { _lifecycle.value = ModuleLifecycle.STARTED }
    override suspend fun onDestroy() {
        // P1-7：模块拥有的异步压缩任务随生命周期销毁，避免进程内悬挂引用。
        scope.cancel()
        _lifecycle.value = ModuleLifecycle.DESTROYED
    }
    override suspend fun onConfigChanged(newConfig: ModuleConfigBase) {}

    override suspend fun checkAvailability(): Boolean = true
    override fun markDisabled() { _lifecycle.value = ModuleLifecycle.DISABLED }

    fun initialize(prompt: String) {
        this.systemPrompt = prompt
        if (_chatHistory.value.messages.isEmpty()) {
            _chatHistory.value = ChatHistory()
        }
    }

    fun setSystemPrompt(prompt: String) { this.systemPrompt = prompt }
    fun getSystemPrompt(): String = systemPrompt

    /** C2：按 ContextBuilderConfig 灰度开关构造工具选择请求（有界 top-K / 旧全量目录）。 */
    fun toolSelectionRequest(intent: String?): ToolContextRequest {
        val cfg = contextBuilder.config as ContextBuilderConfig
        return ToolContextRequest(
            intent = if (cfg.toolDiscoveryEnabled) intent else null,
            includeAllOperational = !cfg.toolDiscoveryEnabled,  // 有界=true→includeAll=false；false→全量
            maxTools = cfg.maxTools,
        )
    }

    private fun discoverySession(functions: List<FunctionDefinition>): ToolDiscoverySession? {
        val cfg = contextBuilder.config as ContextBuilderConfig
        if (!cfg.toolDiscoveryEnabled) return null
        val conversation = _chatHistory.value.id
        val tools = toolManifestStore.select(conversation, generationStore.load(conversation).epoch,
            toolContextSelector.select(ToolContextRequest()), cfg.pinnedToolNames.split(Regex("[\\s,，]+"))
                .filter { it.isNotBlank() }.toSet())
        if (toolManifestStore.persistenceFailed) traceManager.record("tool_manifest", NodeType.ERROR, metadata.id,
            "tool_manifest_persistence_failed: process-local snapshot only")
        return ToolDiscoverySession(toolContextSelector, functions, cfg.maxTools, tools)
    }

    // ═══════════════════════════════════════
    // 对话操作
    // ═══════════════════════════════════════

    suspend fun sendMessage(
        userContent: String,
        functions: List<FunctionDefinition> = emptyList(),
        attachments: List<Attachment> = emptyList(),
        source: MessageSource = MessageSource.CHAT_WINDOW,
        triggerId: String? = null,
        systemSource: String? = null,
        userMessageId: String? = null,
    ): Flow<StreamChunk> = turnGate.stream {
        prepareMessage(userContent, functions, attachments, source, triggerId, systemSource, userMessageId)
    }

    private suspend fun prepareMessage(
        userContent: String,
        functions: List<FunctionDefinition> = emptyList(),
        attachments: List<Attachment> = emptyList(),
        source: MessageSource = MessageSource.CHAT_WINDOW,
        triggerId: String? = null,
        systemSource: String? = null,
        userMessageId: String? = null
    ): Flow<StreamChunk> {
        val traceId = traceManager.startTrace()
        traceManager.record(traceId, NodeType.USER_INPUT, metadata.id, userContent.take(80))

        // 消息预处理
        val rawEvent = ChatSendEvent(message = userContent, source = source)
        val processedEvent = messagePipeline.process(rawEvent)

        var userMsg: ChatMessage
        var inputConversationId: String
        mutex.withLock {
            inputConversationId = _chatHistory.value.id
            userMsg = ChatMessage(
                id = userMessageId ?: UUID.randomUUID().toString(),
                role = MessageRole.USER,
                content = processedEvent.message,
                attachments = attachments,
                source = processedEvent.source,
                // S2R.3（§2.1）：系统注入消息落来源细名+触发 id——留痕/审计链/UI 三色数据源
                systemSource = systemSource,
                triggerId = triggerId
            )
            _chatHistory.value = _chatHistory.value.copy(
                messages = _chatHistory.value.messages + userMsg
            )
            // 追加空 assistant 占位
            _chatHistory.value = _chatHistory.value.copy(
                messages = _chatHistory.value.messages +
                    ChatMessage(id = UUID.randomUUID().toString(), role = MessageRole.ASSISTANT, content = "")
            )
        }

        // Persist the original accepted input before any model selection, media analysis or generation.
        if (source in setOf(MessageSource.CHAT_WINDOW, MessageSource.VOICE, MessageSource.REMOTE)) {
            try {
                chronicleInputStore.capture(com.zero.v2.core.data.entity.MessageEntity(
                    userMsg.id, inputConversationId, "user", userContent, createdAt = userMsg.timestamp,
                    source = source.name, systemSource = userMsg.systemSource, triggerId = userMsg.triggerId,
                ))
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                traceManager.record(traceId, NodeType.ERROR, metadata.id, "chronicle_input_failed:${error.javaClass.simpleName}")
            }
        }

        // 广播 onChatSent（通知各模块）
        chatBus.onChatSent.emit(ChatSentEvent(userMsg, System.currentTimeMillis()))

        // M3：含图 → 先选声明 IMAGE_INPUT 的 profile 走原生 content 数组；无则回退 vision-poke + CHAT（M4 回退）
        val discovery = discoverySession(functions)
        val initialTools = ToolResultAccess.tools(discovery?.tools ?: functions)
        val hasImage = _chatHistory.value.messages.any { message ->
            message.role == MessageRole.USER && message.attachments.any {
                it.type == AttachmentType.IMAGE && it.route != com.zero.v2.core.common.MediaRoute.TOOLS
            }
        }
        val imageModel = if (hasImage) {
            modelSelector.select(
                ModelRequirements(input = setOf(InputModality.TEXT, InputModality.IMAGE), needsTools = initialTools.isNotEmpty())
            ).model
        } else null
        if (imageModel == null && attachments.any { it.type == AttachmentType.IMAGE &&
            it.route == com.zero.v2.core.common.MediaRoute.NATIVE }) {
            return kotlinx.coroutines.flow.flow {
                _chatState.value = ChatState.ERROR
                traceManager.record(traceId, NodeType.ERROR, metadata.id, "MEDIA_MODEL_UNAVAILABLE")
                emit(StreamChunk(contentDelta = "[无法使用原生多模态：请启用已配置密钥且支持图片的模型，或将附件切换为工具识图]",
                    errorCode = "MEDIA_MODEL_UNAVAILABLE", isFinal = true))
            }
        }
        if (hasImage) traceManager.record(traceId, NodeType.SYSTEM_EVENT, metadata.id,
            "image_route: native=${imageModel != null} model=${imageModel?.modelId ?: "fallback_vision_poke"}")

        // M4：原生失败回退 Vision Adapter（设计§3.4）——无 IMAGE_INPUT profile 时 vision-poke 回退。
        // 回退描述经 attachmentId 关联注入 turnContext（**不悄然改写用户正文**——替代旧 v6.8 改写行为）。
        var visionFallbackBlock: String? = null
        if (hasImage && imageModel == null) {
            val blocks = mutableListOf<String>()
            val fallbackImages = kotlinx.coroutines.withContext(Dispatchers.IO) {
                attachmentReader.restore(listOf(userMsg.copy(attachments = attachments)))
            }
                .single().attachments.filter { it.type == AttachmentType.IMAGE &&
                    it.route == com.zero.v2.core.common.MediaRoute.AUTO }
            for (img in fallbackImages) {
                val visionResult = try {
                    if (img.base64Data.isNullOrBlank()) FunctionResult.failure("image_read_failed")
                    else functionRegistry.execute("vision_analyze", mapOf(
                        "image_data" to img.base64Data,
                        "question" to "简洁详细地描述这张图片：包含的文字(OCR)、物体、人物、场景、图表数据。",
                    ))
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    traceManager.record(traceId, NodeType.ERROR, metadata.id,
                        "vision_poke_failed: uri=${img.uri.take(40)} ${e.message?.take(50)}")
                    com.zero.v2.core.common.FunctionResult.failure(e.message ?: "vision_request_failed")
                }
                val description = visionResult.data
                if (visionResult.success && description.isNotBlank()) {
                    blocks += FallbackMedia.visionBlock(img.uri, description)
                    traceManager.record(traceId, NodeType.SYSTEM_EVENT, metadata.id,
                        "vision_poke_done: uri=${img.uri.take(40)} len=${description.length}")
                } else {
                    // 图片失败不静默丢图（设计§3.4 #5）——降级块标注不可用，LLM 可见
                    blocks += FallbackMedia.unavailableBlock(img.uri, visionResult.errorMessage ?: "vision_response_empty") +
                        "\n可调用 attachment_export(attachment_id=完整引用) 导出图片，再 discover_tools 查找动态视觉/OCR工具，按其schema传入返回路径。不要假定图片内容。"
                    traceManager.record(traceId, NodeType.SYSTEM_EVENT, metadata.id,
                        "vision_fallback_failed: ${visionResult.errorMessage?.take(160)}")
                }
            }
            visionFallbackBlock = blocks.joinToString("\n\n").ifBlank { null }
            traceManager.record(traceId, NodeType.SYSTEM_EVENT, metadata.id,
                "image_fallback_route: blocks=${blocks.size} reason=no_image_input_profile")
        }

        // C1：稳定前缀 + 可变上下文。摘要经 turnContext 注入（不入 stablePrefix/哈希），保持前缀缓存稳定。
        // N6.3：早期对话摘要作 turnContext（存在才注入；更新低频 + 注入历史之后 = 缓存影响有界）。
        val summaryBlock = conversationSummarizer.getSummary(_chatHistory.value.id)
            ?.let { "[早期对话摘要]\n$it" }

        // R3：自动证据注入（D3——evidenceAutoInject 开关，关闭不删除存量；入 turnContext 不入 stablePrefix，缓存稳定）。
        // 无相关 → evidenceFor 返回空 → formatBlock=null → 不注入（不伪造）。异常隔离不阻塞对话。
        val evidenceBlock = if ((contextBuilder.config as ContextBuilderConfig).evidenceAutoInject) {
            try { EvidencePolicy.formatBlock(evidenceProvider.revalidate(evidenceProvider.evidenceFor(processedEvent.message, 3))) }
            catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
        } else null

        // P3：动态角色风格指令（设计§6——≤120 token 短 directive 入 turnContext，不入 stablePrefix）。
        // 读活跃角色 + 会话情绪 → CharacterStyleDirective.compose；无状态记录/关闭 → null（固定 systemPrompt 继续）。
        // 异常隔离返回 null（失败以固定人设继续回复，设计§6）。
        val styleBlock = try { characterStateModule.currentStyleDirective(_chatHistory.value.id) }
        catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
        // M4：vision 回退块入 turnContext（attachmentId 关联，不入 stablePrefix，不悄然改写用户正文）
        val mediaBlock = mediaAnalysis.prepareBlock(attachments, userContent)
        val turnCtx = listOfNotNull(summaryBlock, evidenceBlock, styleBlock, visionFallbackBlock, mediaBlock)
            .joinToString("\n\n").ifBlank { null }
        // Capture once in durable history. FC continuations and later turns reuse the same position and bytes.
        _chatHistory.value = _chatHistory.value.copy(
            messages = turnContextProjection.inject(_chatHistory.value.messages, turnCtx)
        )

        val model = (imageModel ?: modelSelector.select(
            ModelRequirements(input = setOf(InputModality.TEXT), needsTools = initialTools.isNotEmpty())
        ).model)?.snapshotForTurn()
        if (model == null) {
            return kotlinx.coroutines.flow.flow {
                _chatState.value = ChatState.ERROR
                traceManager.record(traceId, NodeType.ERROR, metadata.id, "CHAT_MODEL_UNAVAILABLE")
                emit(StreamChunk(contentDelta = "[错误: 无可用模型，请检查模块管理]",
                    errorCode = "CHAT_MODEL_UNAVAILABLE", isFinal = true))
            }
        }
        // C1：PromptPlan——stablePrefix（persona+协议+工具 manifest，字节稳定）+ prefixVersion（哈希观测，不记原 prompt）
        val plan = contextBuilder.buildPromptPlan(
            persona = systemPrompt,
            history = emptyList(),
            turnContext = turnCtx,
            tools = initialTools,
            modelId = model.modelId,
        )
        traceManager.record(traceId, NodeType.SYSTEM_EVENT, metadata.id,
            "prefix_version: ${plan.prefixVersion.take(12)} stableBytes=${plan.stablePrefix.toByteArray(Charsets.UTF_8).size} " +
                "turnCtx_summary=${summaryBlock != null} turnCtx_evidence=${evidenceBlock != null} " +
                "turnCtx_style=${styleBlock != null} turnCtx_vision=${visionFallbackBlock != null}")
        android.util.Log.i(TAG, "sendMessage: using ${model.modelId}, ${_chatHistory.value.messages.size} msgs, ${functions.size} tools prefix=${plan.prefixVersion.take(8)}")

        // A1a D68：用户优先打断——若自主回合进行中，emit 中断信号（Impl 收→cancel job）
        val currentTurn = activeTurn.get()
        if (currentTurn != null && currentTurn.source == com.zero.v2.core.common.TurnSource.AUTONOMOUS
            && _chatState.value == ChatState.GENERATING) {
            chatBus.onUserPriorityInterrupt.emit(Unit)
            traceManager.record(traceId, NodeType.SYSTEM_EVENT, metadata.id,
                "autonomous_interrupted_by_user: turnId=${currentTurn.turnId.take(8)} triggerId=${currentTurn.triggerId?.take(8)}")
            // 短暂等待 cancel 传播（Impl cancel job → flow collector 取消 → 本 flow 不可达，此处仅安全过渡）
            kotlinx.coroutines.delay(200)
        }

        return runFcLoop(FcTurn(userMsg, userContent, functions, model, discovery, turnCtx, traceId))
    }

    private data class FcTurn(
        val userMsg: ChatMessage,
        val userContent: String,
        val functions: List<FunctionDefinition>,
        val model: com.zero.v2.services.llm.ILanguageModel,
        val discovery: ToolDiscoverySession?,
        val turnCtx: String?,
        val traceId: String,
        val notifyFinished: Boolean = true,
    )

    /** Both entry points use the same budget, guarded dispatcher, discovery and final synthesis. */
    private fun runFcLoop(turn: FcTurn): Flow<StreamChunk> = with(turn) {
        var plan = contextBuilder.buildPromptPlan(systemPrompt, emptyList(), turnCtx, ToolResultAccess.tools(discovery?.tools ?: functions), model.modelId)
        // FC 循环：最多 maxFcRounds 轮（tool call → result → LLM → ...）
        // N0.1：上限由硬编码 5 改用户可调（D17；旧 5 轮截断多页读取致"中断回复"，见开发前规范化设计 §五）
        flow {
            _chatState.value = ChatState.GENERATING
            // A1a D79：回合入口建 TurnContext（source→TurnSource 映射）
            // A1b 接线（D1-fix 2026-09-10 L3 实测触发）：triggerId 经参数贯通——原缺省 null
            // 致自主回合 triggerId 恒空，autonomous_notify 的 D79 校验（triggerId!=null）必拒
            activeTurn.set(if (notifyFinished) com.zero.v2.core.common.TurnContext.fromMessageSource(userMsg.source, userMsg.triggerId)
                else com.zero.v2.core.common.TurnContext.user())
            var loopCount = 0
            val maxRounds = (config as ChatBotModuleConfig).maxFcRounds
            val failedCalls = mutableMapOf<String, Int>() // toolName+args → 连续失败次数

            try {
                traceManager.record(traceId, NodeType.SYSTEM_EVENT, metadata.id,
                    "fc_loop_start: maxRounds=$maxRounds tools=${functions.size}")
                while (loopCount < maxRounds) {
                    // N5：预算守卫每轮前检查（fail-open——守护故障放行，X8）；超支优雅终止非砖死
                    val verdict = budgetGuard.preCheck("chat_fc")
                    if (verdict != com.zero.v2.services.agent.BudgetGuard.Verdict.OK) {
                        val msg = if (verdict == com.zero.v2.services.agent.BudgetGuard.Verdict.DAILY_EXCEEDED)
                            "⏸ 今日 Token 预算已用尽（可在 设置 → Agent 行为 调整或明日恢复）。"
                        else "⏸ 本月费用上限已到（可在 设置 → Agent 行为 调整）。"
                        traceManager.record(traceId, NodeType.SYSTEM_EVENT, metadata.id,
                            "budget_blocked: $verdict round=$loopCount")
                        updateLastAssistant(msg)
                        break
                    }
                    loopCount++

                    // 第 2+ 轮：追加空 assistant 占位，供 applyStreamChunk 填充
                    if (loopCount > 1) {
                        _chatHistory.value = _chatHistory.value.copy(
                            messages = _chatHistory.value.messages +
                                ChatMessage(id = UUID.randomUUID().toString(), role = MessageRole.ASSISTANT, content = "")
                        )
                    }

                    // 构建 LLM 消息列表 + C1 turnContext 注入（摘要入历史之后、当前内容前/尾，不入 stablePrefix）
                    val roundTools = ToolResultAccess.tools(discovery?.tools ?: functions)
                    plan = contextBuilder.buildPromptPlan(systemPrompt, emptyList(), turnCtx, roundTools, model.modelId)
                    val msgsForLLM = buildMsgsForLLM(traceId, model, plan.stablePrefix, roundTools)

                    model.chatStream(
                        messages = msgsForLLM,
                        systemPrompt = plan.stablePrefix,
                        functions = roundTools,
                        prefixVersion = com.zero.v2.core.common.PromptPrefix.prefixVersion(
                            com.zero.v2.core.common.PromptPrefix.PROVIDER_PROTOCOL_V1, model.modelId,
                            plan.stablePrefix, com.zero.v2.core.common.PromptPrefix.toolManifest(roundTools)
                        ),
                    ).collect { chunk -> emit(chunk) }

                    // 检查 LLM 是否返回 tool_calls
                    val lastMsg = _chatHistory.value.messages.lastOrNull()
                    val pendingCalls = lastMsg?.toolCalls ?: emptyList()

                    if (pendingCalls.isEmpty()) break
                    check(pendingCalls.all { it.id.isNotBlank() && it.name.isNotBlank() }) {
                        "provider_tool_call_incomplete"
                    }
                    check(pendingCalls.map { it.id }.distinct().size == pendingCalls.size) {
                        "provider_tool_call_duplicate_id"
                    }

                    traceManager.record(traceId, NodeType.SYSTEM_EVENT, metadata.id,
                        "llm_tool_calls_received: ${pendingCalls.size} calls: ${pendingCalls.map { it.name }}")

                    // 逐个执行 tool call（保证 tool_call_id 1:1 对应）
                    val toolMsgs = pendingCalls.map { tc ->
                        // 诊断 Trace: arguments raw → parsed
                          traceManager.record(AgentTraceEvent(
                              traceId = traceId,
                              nodeType = NodeType.SYSTEM_EVENT,
                              sourceModule = metadata.id,
                              summary = "tool_call_arguments: ${tc.name} id=${tc.id} bytes=${tc.arguments.length}",
                              callId = tc.id,
                              functionName = tc.name
                          ))
                        val parsedArgs = parseToolArguments(tc.arguments)
                          traceManager.record(AgentTraceEvent(
                              traceId = traceId,
                              nodeType = NodeType.SYSTEM_EVENT,
                              sourceModule = metadata.id,
                              summary = "tool_call_argument_fields: ${tc.name} fields=${parsedArgs.keys}",
                              callId = tc.id,
                              functionName = tc.name
                          ))

                        // 连续相同失败调用保护（防止 arguments 为空时无限重试）
                        val failKey = "${tc.name}:${tc.arguments}"
                        val failCount = failedCalls.getOrDefault(failKey, 0)
                        if (failCount >= 2) {
                              traceManager.record(AgentTraceEvent(
                                  traceId = traceId,
                                  nodeType = NodeType.SYSTEM_EVENT,
                                  sourceModule = metadata.id,
                                  summary = "tool_call_skipped: ${tc.name} repeated fail x$failCount",
                                  callId = tc.id,
                                  functionName = tc.name
                              ))
                            return@map ChatMessage(
                                id = UUID.randomUUID().toString(),
                                role = MessageRole.TOOL,
                                content = "[错误: 相同参数连续失败，已跳过]",
                                toolCallId = tc.id, toolCallName = tc.name
                            ).also { appendToolMessage(it) }
                        }

                          traceManager.record(AgentTraceEvent(
                              traceId = traceId,
                              nodeType = NodeType.TOOL_CALL_START,
                              sourceModule = metadata.id,
                              summary = "tool_execute_start: ${tc.name} id=${tc.id}",
                              callId = tc.id,
                              functionName = tc.name
                          ))
                        val toolT0 = System.currentTimeMillis()
                        var executedName = tc.name
                        val result = try {
                            val resolvedCall = discovery?.resolve(tc) ?: tc
                            executedName = resolvedCall.name
                            if (resolvedCall.name == ToolResultAccess.NAME) {
                                val args = org.json.JSONObject(resolvedCall.arguments)
                                FunctionResult.success(ToolResultAccess.read(_chatHistory.value.messages,
                                    args.getString("ref"), args.optInt("offset", 0), args.optInt("limit", 4000), args.optString("query", "")))
                            } else if (resolvedCall.name == ToolDiscoverySession.NAME && discovery != null) {
                                val args = org.json.JSONObject(resolvedCall.arguments)
                                FunctionResult.success(discovery.discover(args.optString("query", ""), args.optInt("offset", 0), args.optString("category", "")))
                            } else {
                                functionRegistry.execute(resolvedCall)
                            }
                        } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                            FunctionResult.failure("执行异常: ${e.message}")
                        }
                        // 跟踪失败
                        if (!result.success) {
                            failedCalls[failKey] = failCount + 1
                        }
                        if (result.success && executedName !in setOf(ToolDiscoverySession.NAME, ToolResultAccess.NAME)) {
                            toolManifestStore.successful(executedName)
                        }
                          traceManager.record(AgentTraceEvent(
                              traceId = traceId,
                              nodeType = NodeType.TOOL_CALL_RESULT,
                              sourceModule = metadata.id,
                              summary = "tool_execute_done: $executedName id=${tc.id} ${if (result.success) "OK" else "FAIL"}",
                              callId = tc.id,
                              functionName = executedName
                          ))
                        // S2.3/D117：工具轨迹显示事件（G5——id 关联 RunLog 全量；摘要 ≤80 字）
                        chatBus.onToolCallEvent.tryEmit(
                            com.zero.v2.core.bus.ToolTraceEvent(
                                toolCallId = tc.id, traceId = traceId, name = executedName,
                                argsDigest = tc.arguments?.take(80),
                                resultDigest = (if (result.success) result.data
                                    else "[错误] ${result.errorMessage ?: "未知"}").take(80),
                                ok = result.success,
                                durationMs = System.currentTimeMillis() - toolT0,
                                round = loopCount))
                        ChatMessage(
                            id = UUID.randomUUID().toString(),
                            role = MessageRole.TOOL,
                            content = if (result.success) result.data else "[错误: ${result.errorMessage ?: "未知错误"}]",
                            toolCallId = tc.id,
                            toolCallName = tc.name,
                            attachments = result.attachments(),
                        ).also { appendToolMessage(it) }
                    }

                    // Completed results are appended individually, even if a later call is cancelled.
                    toolMsgs.forEach { tm ->
                          traceManager.record(AgentTraceEvent(
                              traceId = traceId,
                              nodeType = NodeType.SYSTEM_EVENT,
                              sourceModule = metadata.id,
                              summary = "tool_message_appended: call_id=${tm.toolCallId} name=${tm.toolCallName}",
                              callId = tm.toolCallId,
                              functionName = tm.toolCallName
                          ))
                    }

                    traceManager.record(traceId, NodeType.SYSTEM_EVENT, metadata.id,
                        "llm_continue_after_tools: round=$loopCount")
                }

                // N0.2 最终综合轮（F2，缺陷修复见开发前规范化设计 §五）：
                // 因轮次上限退出且末尾为 TOOL（有待综合的工具结果）时，追加一轮【无工具】调用，
                // 强制 LLM 基于已获取结果作答——旧实现直接退出致末尾 TOOL 不触发 ChatFinishedEvent，
                // 对话界面表现为"中断回复"。functions 为空则模型无工具可调，不会产生新循环。
                val endMsg = _chatHistory.value.messages.lastOrNull()
                if (endMsg != null && endMsg.role == MessageRole.TOOL) {
                    traceManager.record(traceId, NodeType.SYSTEM_EVENT, metadata.id,
                        "fc_loop_capped: round=$loopCount max=$maxRounds final_synthesis=true")
                    _chatHistory.value = _chatHistory.value.copy(
                        messages = _chatHistory.value.messages +
                            ChatMessage(id = UUID.randomUUID().toString(), role = MessageRole.ASSISTANT, content = "")
                    )
                    if (budgetGuard.preCheck("chat_fc_synthesis") != com.zero.v2.services.agent.BudgetGuard.Verdict.OK) {
                        emit(StreamChunk(contentDelta = "[工具结果已保留；预算不足，未执行最终综合]",
                            errorCode = "CHAT_BUDGET_EXCEEDED", isFinal = true))
                        return@flow
                    }
                    model.chatStream(
                        // A transient continuation stays outside the stable system prefix. Qwen templates
                        // reject a system role after the first message, including final synthesis.
                        messages = buildMsgsForLLM(traceId, model, plan.stablePrefix, emptyList()) + ChatMessage(role = MessageRole.USER,
                            content = "已达工具调用轮次上限。请基于以上已获取的工具结果直接回答用户，不要再请求工具。"),
                        systemPrompt = plan.stablePrefix,
                        functions = emptyList(),
                        prefixVersion = PromptPrefix.prefixVersion(PromptPrefix.PROVIDER_PROTOCOL_V1,
                            model.modelId, plan.stablePrefix, PromptPrefix.toolManifest(emptyList())),
                    ).collect { chunk -> emit(chunk) }
                }
            } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e(TAG, "LLM stream error", e)
                _chatState.value = ChatState.ERROR
                traceManager.record(traceId, NodeType.ERROR, metadata.id, "LLM error: ${e.message}")
                val detail = when (e.message) {
                    "responses_max_output_tokens" -> "模型总生成预算耗尽（包含思考）。已完成工具结果已保留；请提高模型输出预算后继续。"
                    "responses_content_filter" -> "模型内容过滤终止了生成；已完成工具结果已保留。"
                    "responses_server_error" -> "模型服务端生成失败；已完成工具结果已保留。"
                    else -> if (e.message?.startsWith("REQUEST_CONTEXT_LIMIT") == true)
                        "本轮内容预计超过模型上下文容量，已保留历史和工具结果。请分页读取大结果或开启新会话后继续。"
                    else e.message
                }
                emit(StreamChunk(contentDelta = "[请求失败：$detail]", errorCode = "CHAT_REQUEST_FAILED", isFinal = true))
            } finally {
                if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
                    val history = _chatHistory.value
                    val userIndex = history.messages.indexOfFirst { it.id == userMsg.id && it.role == MessageRole.USER }
                    if (userIndex >= 0) _chatHistory.value = history.copy(messages = history.messages.take(userIndex) +
                        ToolTranscript.repair(history.messages.drop(userIndex)))
                }
                if (_chatState.value != ChatState.ERROR) _chatState.value = ChatState.IDLE
                // A1a D79：回合结束清上下文（不可变 TurnContext 生命周期=flow 生命周期）
                activeTurn.set(null)
                // Do not rewrite tool results already sent to the provider. Trim only at a generation switch.
                val lastMsg = _chatHistory.value.messages.lastOrNull()
                if (notifyFinished && kotlinx.coroutines.currentCoroutineContext().isActive && lastMsg != null && lastMsg.role == MessageRole.ASSISTANT) {
                    chatBus.onChatFinished.emit(ChatFinishedEvent(
                        userMessage = userMsg, assistantMessage = lastMsg,
                        toolCalls = lastMsg.toolCalls, totalTokens = _totalUsage.value,
                        conversationId = _chatHistory.value.id,
                        originalUserText = userContent,
                    ))
                }
                traceManager.endTrace(traceId)
                android.util.Log.i(TAG, "FC loop done after $loopCount turns, messages: ${_chatHistory.value.messages.size}")
            }
        }
    }

    /**
     * 构建 LLM 消息列表（N0.2 提取；N6.6 世代压缩——取代 N6.1/N6.5 常驻滑窗）：
     * ① 过滤无 toolCallId 的 TOOL 消息 + 剥离末尾空 assistant 占位
     * ② 世代内 append-only：发送视图=世代锚点起至最新，零改写——前缀缓存逐轮满命中
     *    （97% 稳态回归；压缩唯一使命=防 1M 上限，不省 token——用户裁定 2026-09-03）
     * ③ 仅当视图 est > 90% × contextWindow：一次性世代切换（切片到 65% 预算 + 头部
     *    异步置换为摘要），此后新基线继续 append-only
     * 发送点全覆盖：FC 循环 / 最终综合轮（本方法）+ regenerate（复用，X15）
     */
    private suspend fun buildMsgsForLLM(
        traceId: String?,
        model: com.zero.v2.services.llm.ILanguageModel,
        stablePrefix: String,
        tools: List<FunctionDefinition>,
    ): List<ChatMessage> {
        var view = _chatHistory.value.messages
            .filter { it.role != MessageRole.TOOL || it.toolCallId != null }
            .let { msgs ->
                val last = msgs.lastOrNull()
                if (last?.role == MessageRole.ASSISTANT && last.content.isNullOrBlank() && last.toolCalls.isEmpty())
                    msgs.dropLast(1) else msgs
            }
        // S1.1-fix（用户实测 400）：孤儿 TOOL 权威守卫——视图内任何 TOOL 若无前置
        // assistant(toolCalls 含其 toolCallId) 承载，一律剔除（OpenAI 兼容 API 对
        // "tool 无 tool_calls 呼应"直接 400；产生路径：regenerate 移除末尾 assistant /
        // 历史重载丢 toolCalls / 任何未来视图变换——单点收口）。
        // ⚠ 不做"尾部 TOOL 补位"：FC 第 2+ 轮请求以 TOOL 结尾是提交工具结果的标准形态，
        // 补空 assistant 反而破坏 tool→响应续写模式（本轮审查发现并移除）
        val liveCallIds = mutableSetOf<String>()
        val base = com.zero.v2.core.common.ToolTranscript.repair(view).filter { m ->
            when (m.role) {
                MessageRole.ASSISTANT -> {
                    m.toolCalls.forEach { liveCallIds += it.id }
                    true
                }
                MessageRole.TOOL -> m.toolCallId in liveCallIds
                else -> true
            }
        }
        val cfg = contextBuilder.config as ContextBuilderConfig
        val requestBudget = GenerationInputBudget(tokenBudgetAllocator, model, stablePrefix, tools)
        val budget = requestBudget.historyLimit(cfg.contextWindow)

        // 世代内视图：锚点起全部（append-only 稳态）
        val conversationId = _chatHistory.value.id
        if (generationConversationId != conversationId) {
            generationProjection = generationStore.load(conversationId)
            generationConversationId = conversationId
        }
        val generationScope = "${model.profileId}:${model.modelId}:$stablePrefix"
        val projectedBase = base.map(ToolResultAccess::project)
        val rawGenView = generationProjection.view(projectedBase, generationScope)
        val memories = memoryContextStore.load(conversationId)
        val currentMemories = evidenceProvider.contextMemories(memories.knownIds())
        val memoryChars = minOf(4096, budget / 20).coerceAtLeast(0)
        val genView = memories.project(rawGenView, generationProjection.epoch, currentMemories, memoryChars)
        memories.resetReason?.let { model.noteContextEvent(genView, it) }
        try { memoryContextStore.save(conversationId, memories) }
        catch (_: java.io.IOException) { traceManager.record(traceId ?: "memory_ctx", NodeType.ERROR, metadata.id, "memory_context_save_failed") }
        generationProjection.resetReason?.let { reason ->
            model.noteContextEvent(genView, reason)
            traceManager.record(traceId ?: "ctx_gen", NodeType.COMPRESSION, metadata.id,
                "ctx_gen_reset: reason=$reason messages=${projectedBase.size}")
        }
        val viewEst = requestBudget.historyEstimate(genView)

        if (viewEst <= budget * 0.9) {
            requestBudget.check(genView)
            return genView   // Retain the prior request prefix; actual cache reuse is provider-observed.
        }

        traceManager.record(traceId ?: "ctx_gen", NodeType.COMPRESSION, metadata.id,
            "ctx_gen_budget: configured=${cfg.contextWindow} effective=$budget " +
                "trigger=${(budget * 0.9).toInt()} target=${(budget * SLICE_TARGET_RATIO).toInt()} " +
                "modelLimit=${model.contextWindow} outputReserve=${model.outputReserve} " +
                model.inputBudgetDiagnostic(genView, stablePrefix, tools))

        // ═══ 世代切换（稀疏事件：每涨 ~25% 预算一次）═══
        val slice = ContextWindow.sliceByRequestBudget(
            messages = rawGenView,
            targetTokens = (budget * SLICE_TARGET_RATIO).toInt(),
            estimate = { requestBudget.historyEstimate(it) + (requestBudget.historyEstimate(genView) - requestBudget.historyEstimate(rawGenView)).coerceAtLeast(0) },
        )
        // A tail tool batch is atomic and can exceed the target. Never freeze an unsendable projection.
        val nextMemoryView = memories.project(slice.messages, generationProjection.epoch + 1, currentMemories, memoryChars)
        requestBudget.check(nextMemoryView)
        model.noteContextEvent(slice.messages, "generation_switch")
        val newAnchorId = slice.messages.firstOrNull()?.id
        generationProjection.freeze(projectedBase, slice.messages, generationScope)
        try { generationStore.save(conversationId, generationProjection) }
        catch (_: java.io.IOException) {
            traceManager.record(traceId ?: "ctx_gen", NodeType.ERROR, metadata.id, "ctx_gen_save_failed")
        }
        try { memoryContextStore.save(conversationId, memories) }
        catch (_: java.io.IOException) { traceManager.record(traceId ?: "memory_ctx", NodeType.ERROR, metadata.id, "memory_context_save_failed") }
        traceManager.record(traceId ?: "ctx_gen", NodeType.COMPRESSION, metadata.id,
            "ctx_gen_switch: viewEst=$viewEst budget=$budget kept=${slice.keptCount} " +
                "dropped=${slice.droppedCount} anchor=${newAnchorId?.take(8)} " +
                model.inputBudgetDiagnostic(slice.messages, stablePrefix, tools))
        // 被置换头部 → 异步滚动摘要（下一轮注入 system；§7.6 降级——失败仅丢失摘要，不影响发送）
        val convId = _chatHistory.value.id
        if (convId.isNotBlank() && newAnchorId != null) {
            scope.launch {
                conversationSummarizer.compressIfNeeded(
                    conversationId = convId,
                    fullMessages = _chatHistory.value.messages,
                    newAnchorMessageId = newAnchorId,
                    summaryMaxChars = cfg.summaryMaxChars,
                )
            }
        }
        return nextMemoryView
    }

    /**
     * 增量更新 AI 回复（流式渲染时调用）。
     */
    fun applyStreamChunk(chunk: StreamChunk) {
        chunk.providerContent?.let { state ->
            val history = _chatHistory.value
            val last = history.messages.lastOrNull()
            check(last?.role == MessageRole.ASSISTANT) { "provider_content_without_assistant" }
            _chatHistory.value = history.copy(messages = history.messages.dropLast(1) +
                requireNotNull(last).copy(providerContent = state))
        }
        if (chunk.contentDelta != null) {
            val delta = chunk.contentDelta!!
            val newContent = _chatHistory.value.messages.lastOrNull()?.let {
                (it.content ?: "") + delta
            } ?: delta
            updateLastAssistant(newContent)
        }
        if (chunk.thinkingDelta != null) {
            val delta = chunk.thinkingDelta!!
            val newThinking = _chatHistory.value.messages.lastOrNull()?.let {
                (it.thinking ?: "") + delta
            } ?: delta
            updateLastThinking(newThinking)
        }
        if (chunk.toolCallDelta != null) {
            applyToolCallDelta(chunk.toolCallDelta!!)
        }
        if (chunk.tokenUsage != null) {
            // v2.6: 补齐 cachedTokens 累计（D27 缓存命中率可观测）
            _totalUsage.value = _totalUsage.value + requireNotNull(chunk.tokenUsage)
        }
    }

    suspend fun clearConversation() = turnGate.exclusive {
        turnContextProjection.clear()
        generationConversationId = null
        _chatHistory.value = ChatHistory()
    }

    /** 从数据库加载历史对话，替换当前对话状态 */
    suspend fun loadHistory(conversationId: String, messages: List<ChatMessage>) = turnGate.exclusive {
        turnContextProjection.clear()
        generationConversationId = null // Reload durable request projection on first request.
        _chatHistory.value = ChatHistory(id = conversationId, messages = messages)
    }

    suspend fun regenerate(functions: List<FunctionDefinition> = emptyList(), userMessageId: String? = null): Flow<StreamChunk> {
        return turnGate.stream { prepareRegeneration(functions, userMessageId) }
    }

    private suspend fun prepareRegeneration(functions: List<FunctionDefinition>, userMessageId: String?): Flow<StreamChunk> {
        val previous = _chatHistory.value
        val userIndex = previous.messages.indexOfLast { it.role == MessageRole.USER }
        if (userIndex < 0) return flow {
            emit(StreamChunk(contentDelta = "[无法重新生成：没有用户回合]", errorCode = "REGENERATION_NO_USER", isFinal = true))
        }
        val user = previous.messages[userIndex]
        if (userMessageId != null && user.id != userMessageId) return flow {
            emit(StreamChunk(contentDelta = "[当前回合已变化，请确认最新回复后重新生成]",
                errorCode = "REGENERATION_TURN_CHANGED", isFinal = true))
        }
        val discovery = discoverySession(functions)
        val tools = ToolResultAccess.tools(discovery?.tools ?: functions)
        val hasNativeImage = previous.messages.take(userIndex + 1).any { message ->
            message.role == MessageRole.USER && message.attachments.any {
                it.type == AttachmentType.IMAGE && it.route != MediaRoute.TOOLS
            }
        }
        val imageModel = if (hasNativeImage) modelSelector.select(ModelRequirements(
            input = setOf(InputModality.TEXT, InputModality.IMAGE), needsTools = tools.isNotEmpty(),
        )).model else null
        val model = (imageModel ?: modelSelector.select(ModelRequirements(input = setOf(InputModality.TEXT), needsTools = tools.isNotEmpty())).model)?.snapshotForTurn()
        if (model == null || (imageModel == null && user.attachments.any { it.type == AttachmentType.IMAGE && it.route == MediaRoute.NATIVE })) {
            return flow {
                emit(StreamChunk(contentDelta = "[无法重新生成：没有满足当前输入及工具能力的模型]", errorCode = "REGENERATION_MODEL_UNAVAILABLE", isFinal = true))
            }
        }
        // Reuse the first reply ID, but discard the entire superseded tool chain.
        val oldReply = previous.messages.drop(userIndex + 1).firstOrNull { it.role == MessageRole.ASSISTANT }
        val placeholder = oldReply?.copy(content = "", thinking = null, toolCalls = emptyList(), attachments = emptyList())
            ?: ChatMessage(id = UUID.randomUUID().toString(), role = MessageRole.ASSISTANT, content = "")
        val replyId = placeholder.id
        _chatHistory.value = previous.copy(messages = previous.messages.take(userIndex + 1) + placeholder)
        val traceId = traceManager.startTrace()
        // Reuse the already captured context. No ChatSent/ChatFinished replay or fresh evidence/vision requests.
        return runFcLoop(FcTurn(user, user.content.orEmpty(), functions, model, discovery, null, traceId, notifyFinished = false))
            .onCompletion { cause ->
                if (cause is kotlinx.coroutines.CancellationException) {
                    val history = _chatHistory.value
                    val current = history.messages.lastOrNull()
                    if (history.messages.size == userIndex + 2 && current?.id == replyId &&
                        current.content.isNullOrBlank() && current.thinking.isNullOrBlank() && current.toolCalls.isEmpty()) {
                        _chatHistory.value = previous
                    }
                }
            }
    }

    // ═══════════════════════════════════════
    // 内部方法
    // ═══════════════════════════════════════

    private fun appendToolMessage(message: ChatMessage) {
        _chatHistory.value = _chatHistory.value.copy(messages = _chatHistory.value.messages + message)
    }

    private fun updateLastAssistant(content: String) {
        val msgs = _chatHistory.value.messages.toMutableList()
        if (msgs.lastOrNull()?.role == MessageRole.ASSISTANT) {
            msgs[msgs.lastIndex] = msgs.last().copy(content = content)
        }
        _chatHistory.value = _chatHistory.value.copy(messages = msgs)
    }

    private fun updateLastThinking(thinking: String) {
        val msgs = _chatHistory.value.messages.toMutableList()
        if (msgs.lastOrNull()?.role == MessageRole.ASSISTANT) {
            msgs[msgs.lastIndex] = msgs.last().copy(thinking = thinking)
        }
        _chatHistory.value = _chatHistory.value.copy(messages = msgs)
    }

    /** 解析 tool call 的 JSON arguments 字符串为 Map */
    private fun parseToolArguments(argumentsJson: String): Map<String, Any?> {
        return try {
            val json = org.json.JSONObject(argumentsJson)
            json.keys().asSequence().associateWith { json.get(it) }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun applyToolCallDelta(delta: ToolCallDelta) {
        val msgs = _chatHistory.value.messages.toMutableList()
        val lastIdx = msgs.indexOfLast { it.role == MessageRole.ASSISTANT }
        if (lastIdx < 0) return

        msgs[lastIdx] = msgs[lastIdx].copy(toolCalls =
            com.zero.v2.core.common.ToolCallAssembly.append(msgs[lastIdx].toolCalls, delta))
        _chatHistory.value = _chatHistory.value.copy(messages = msgs)
    }
}

/** 对话历史 */
data class ChatHistory(
    val id: String = "conv_${System.currentTimeMillis()}",
    val messages: List<ChatMessage> = emptyList()
)
