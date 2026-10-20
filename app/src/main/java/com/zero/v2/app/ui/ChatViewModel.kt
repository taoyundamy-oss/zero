package com.zero.v2.app.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zero.v2.app.data.ConversationRepository
import com.zero.v2.core.common.Attachment
import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.MessageRole
import com.zero.v2.core.data.dao.CharacterDao
import com.zero.v2.core.common.StreamChunk
import com.zero.v2.core.common.TokenUsage
import com.zero.v2.core.data.entity.CharacterEntity
import com.zero.v2.core.data.entity.ConversationEntity
import com.zero.v2.core.data.entity.MessageEntity
import com.zero.v2.core.moduleapi.ToolContextRequest
import com.zero.v2.core.moduleapi.ToolContextSelector
import com.zero.v2.core.trace.AgentTrace
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.framework.chat.ChatBotModule
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

/** 生成状态 — 用于 UI loading 指示器，不通过空 assistant 消息实现 */
enum class GenerationState { IDLE, GENERATING, CALLING_TOOL, STOPPING, STOPPED, ERROR }
private const val DEFAULT_PROMPT = "\\u4f60\\u662f Zero\\uff0c\\u4e00\\u4e2a\\u667a\\u80fd AI \\u52a9\\u624b\\u3002\\u8bf7\\u7528\\u7b80\\u6d01\\u7684\\u4e2d\\u6587\\u56de\\u590d\\u7528\\u6237\\u3002"

private val DEFAULT_PROMPT_TEXT = listOf(
    0x4F60, 0x662F, 0x20, 0x5A, 0x65, 0x72, 0x6F, 0xFF0C,
    0x4E00, 0x4E2A, 0x667A, 0x80FD, 0x20, 0x41, 0x49, 0x20, 0x52A9, 0x624B, 0x3002,
    0x8BF7, 0x7528, 0x7B80, 0x6D01, 0x7684, 0x4E2D, 0x6587, 0x56DE, 0x590D, 0x7528, 0x6237, 0x3002
).map { code -> code.toChar() }.toCharArray().concatToString()
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatBot: ChatBotModule,
    private val toolContextSelector: ToolContextSelector,
    private val traceManager: AgentTraceManager,
    private val conversationRepo: ConversationRepository,
    private val characterDao: CharacterDao,
    private val chatBus: com.zero.v2.core.bus.ChatBus,
    private val attachmentStore: com.zero.v2.app.data.AttachmentStore,
    savedStateHandle: SavedStateHandle,
    private val characterWrites: com.zero.v2.core.data.store.CharacterRepository = com.zero.v2.core.data.store.CharacterRepository(characterDao),
) : ViewModel() {

    init {
        // S2.3-fix：工具轨迹事件镜像到当前流式 assistant（D117/G5 正确载体）——
        // 根因：FC 多轮下 toolCalls 落在中间轮 assistant（P1），投影过滤（toolCalls.isEmpty()）
        // 将其整条滤除——最终回复消息字段捷径不可达；总线事件才是可靠数据源（name/args/id 实时全）
        // （SharedFlow 无回放、首值挂起等待——collect 体不在构造期同步执行，属性后置安全）
        viewModelScope.launch {
            chatBus.onToolCallEvent.collect { ev ->
                if (!_isGenerating.value) return@collect
                val msgs = _messages.value.toMutableList()
                val toolCall = com.zero.v2.core.common.ToolCall(
                    id = ev.toolCallId, name = ev.name, arguments = ev.argsDigest ?: "")
                val idx = msgs.indexOfLast { it.role == MessageRole.ASSISTANT }
                if (idx > msgs.indexOfLast { it.role == MessageRole.USER }) {
                    msgs[idx] = msgs[idx].copy(
                        toolCalls = msgs[idx].toolCalls.filterNot { it.id == ev.toolCallId } + toolCall)
                } else {
                    msgs.add(ChatMessage(role = MessageRole.ASSISTANT, content = "",
                        toolCalls = listOf(toolCall)))
                }
                _messages.value = msgs
            }
        }
    }

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val draftState = com.zero.v2.app.ui.chat.ChatDraftState(savedStateHandle)
    private val _inputText = draftState.text
    val inputText: StateFlow<String> = _inputText

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _sendState = MutableStateFlow(GenerationState.IDLE)
    val sendState: StateFlow<GenerationState> = _sendState.asStateFlow()
    private val _retryUserId = MutableStateFlow<String?>(null)
    val retryUserId: StateFlow<String?> = _retryUserId.asStateFlow()

    private val _pendingAttachment = draftState.attachment
    val pendingAttachment: StateFlow<Attachment?> = _pendingAttachment

    private val _activeCharacterName = MutableStateFlow("")
    val activeCharacterName: StateFlow<String> = _activeCharacterName.asStateFlow()

    private val _conversationList = MutableStateFlow<List<ConversationEntity>>(emptyList())
    val conversationList: StateFlow<List<ConversationEntity>> = _conversationList.asStateFlow()

    private val _currentConversationId = MutableStateFlow("")
    val currentConversationId: StateFlow<String> = _currentConversationId.asStateFlow()

    private val _traceTraces = MutableStateFlow<List<AgentTrace>>(emptyList())
    val traceTraces: StateFlow<List<AgentTrace>> = _traceTraces.asStateFlow()

    private val _traceExpanded = MutableStateFlow(false)
    val traceExpanded: StateFlow<Boolean> = _traceExpanded.asStateFlow()

    private val generationTask by lazy { com.zero.v2.app.ui.chat.ChatGenerationTask(viewModelScope) }
    val isSwitchingConversation get() = generationTask.isTransitioning

    fun stopGeneration() {
        if (!_isGenerating.value) return
        val previous = _sendState.value
        _sendState.value = GenerationState.STOPPING
        if (!generationTask.stop()) _sendState.value = previous
    }

    private var currentTraceId: String? = null
    private var initializationJob: Job? = null
    private var globalActiveCharacterId: String? = null
    private var globalActiveCharacterName = "Zero"
    private var globalActiveSystemPrompt = DEFAULT_PROMPT_TEXT

    fun toggleTrace() { _traceExpanded.value = !_traceExpanded.value }

    init {
        // S2R.4 实时投影（D-P4）：观察 chatHistory——自主/事件回合随流式实时重投影
        // （bridge applyStreamChunk 已回写 history，观察即得流式，无需镜像管道）。
        // 用户轮由 sendMessage 既有路径刷新（_isGenerating 守卫防双写）。
        // ⚠️ 须在全部 StateFlow 属性声明之后（本 init 块）：viewModelScope=Main.immediate，
        // launch 体构造期同步执行，StateFlow.collect 即时回放首值——置前则读到未初始化属性
        // （2026-09-19 闪退实证：NPE _isGenerating.value @ 构造期 collect）。
        viewModelScope.launch {
            chatBot.chatHistory.collect { history ->
                if (!_isGenerating.value) {
                    _messages.value = projectToUiMessages(history.messages)
                }
            }
        }
        initializationJob = viewModelScope.launch {
            // D26: 从设置中读取活跃角色的 systemPrompt（非对话内切换）
            val activeCharacter = ensureActiveCharacter()
            applyGlobalCharacter(activeCharacter)
            traceManager.record(
                "char_init",
                com.zero.v2.core.trace.NodeType.SYSTEM_EVENT,
                "chat",
                "active_character: id=${activeCharacter?.id}, name=${activeCharacter?.name ?: globalActiveCharacterName}"
            )
            val conversations = conversationRepo.getConversations()
            _conversationList.value = conversations
            if (conversations.isNotEmpty()) {
                loadConversationInternal(conversations.firstOrNull { it.id == draftState.conversationId }?.id ?: conversations.first().id)
            } else {
                startNewConversation()
            }
        }
    }

    fun setInput(text: String) { draftState.setText(text) }

    /** D26: sync the settings-selected character; switching creates a new conversation */
    fun refreshActiveCharacter() {
        var activeCharacter: CharacterEntity? = null
        generationTask.transition(shouldTransition = {
            initializationJob?.join()
            activeCharacter = ensureActiveCharacter()
            // R14：对比须用与 applyGlobalCharacter 相同的兜底后有效值——原实现拿原始字段
            // 对比兜底全局（null vs "Zero"/DEFAULT_PROMPT_TEXT），无角色安装上恒判"已切换"，
            // 每次 ChatScreen 进入都误新建会话（上下文丢失）。角色真正变化（id/名/提示词变）
            // 仍正常新开——D26 语义不变。
            val unchanged = activeCharacter?.id == globalActiveCharacterId &&
                (activeCharacter?.name ?: "Zero") == globalActiveCharacterName &&
                (activeCharacter?.systemPrompt ?: DEFAULT_PROMPT_TEXT) == globalActiveSystemPrompt
            !unchanged
        }) {
            val previousCharacterId = globalActiveCharacterId
            applyGlobalCharacter(activeCharacter)
            startNewConversation()
            traceManager.record(
                "char_switch",
                com.zero.v2.core.trace.NodeType.SYSTEM_EVENT,
                "chat",
                "character_switched: from=$previousCharacterId, to=${activeCharacter?.id}"
            )
        }
    }
    /** 设置待发送附件（用户从系统图片选择器选择后调用） */
    fun setAttachment(uri: String, mimeType: String = "image/*") {
        draftState.setAttachment(Attachment(uri = uri, mimeType = mimeType,
            type = com.zero.v2.core.common.MediaAttachmentPolicy.type(mimeType)))
    }

    /** 清除待发送附件 */
    fun clearAttachment() { draftState.setAttachment(null) }

    fun setAttachmentRoute(route: com.zero.v2.core.common.MediaRoute) {
        draftState.setAttachment(_pendingAttachment.value?.copy(route = route))
    }

    fun sendMessage() {
        val text = _inputText.value.trim()
        val attachment = _pendingAttachment.value
        val ownedUserId = UUID.randomUUID().toString()
        if ((text.isEmpty() && attachment == null) || _isGenerating.value || isSwitchingConversation.value) return
        setInput("")
        clearAttachment()
        _isGenerating.value = true
        _sendState.value = GenerationState.GENERATING
        _retryUserId.value = null

        // 开始新 trace
        currentTraceId = traceManager.startTrace()
        _traceTraces.value = emptyList()
        _traceExpanded.value = false
        traceManager.record(currentTraceId!!, com.zero.v2.core.trace.NodeType.USER_INPUT, "chat", text.take(80))

        generationTask.start {
            try {
                initializationJob?.join()
                val toolDefs = toolContextSelector.select(chatBot.toolSelectionRequest(text))
                // v2.6: 附件运行时解析 — content:// URI → Base64 data URL（供视觉预处理）
                val resolvedAttachment = attachment?.let { resolveAttachmentData(it) }
                val flow = chatBot.sendMessage(text, functions = toolDefs,
                    attachments = resolvedAttachment?.let { listOf(it) } ?: emptyList(), userMessageId = ownedUserId)

                // 乐观更新：用户消息立即投影到 UI（chatBot 已将其加入 chatHistory）
                _messages.value = projectToUiMessages(chatBot.chatHistory.value.messages)

                var content = ""
                var tokenUsage = TokenUsage()
                var turnSnapshot: com.zero.v2.framework.chat.ChatHistory? = null
                flow.collect { chunk: StreamChunk ->
                    if (turnSnapshot == null) _messages.value = projectToUiMessages(chatBot.chatHistory.value.messages)
                    chunk.errorCode?.let { code ->
                        _sendState.value = GenerationState.ERROR
                        traceManager.record(currentTraceId!!, com.zero.v2.core.trace.NodeType.ERROR, "chat", code)
                    }
                    chunk.tokenUsage?.let {
                        tokenUsage = tokenUsage + it
                    }
                    if (chunk.contentDelta != null) {
                        content += chunk.contentDelta!!
                        updateAssistantMessage(content)
                    }
                    chatBot.applyStreamChunk(chunk)
                    turnSnapshot = chatBot.chatHistory.value

                    // 检测 tool call 状态变化
                    val lastMsg = chatBot.chatHistory.value.messages.lastOrNull()
                    if (lastMsg?.toolCalls?.isNotEmpty() == true && _sendState.value !in setOf(GenerationState.CALLING_TOOL, GenerationState.ERROR)) {
                        _sendState.value = GenerationState.CALLING_TOOL
                    }
                }

                // 投影最终结果：排除 TOOL 和 tool_calls-only assistant
                // S2.3-fix：投影前捕获本轮工具轨迹（事件镜像在流式 assistant 上，投影会冲掉），
                // 投影后回挂到最终回复——轨迹卡跨 turn 持续可见
                val turnTools = turnSnapshot?.messages?.let { rows ->
                    rows.drop(rows.indexOfLast { it.role == MessageRole.USER } + 1)
                        .flatMap { it.toolCalls }.distinctBy { it.id }
                }.orEmpty()
                _messages.value = projectToUiMessages(chatBot.chatHistory.value.messages)
                if (turnTools.isNotEmpty()) {
                    val msgs = _messages.value.toMutableList()
                    val idx = msgs.indexOfLast { it.role == MessageRole.ASSISTANT }
                    if (idx >= 0) msgs[idx] = msgs[idx].copy(toolCalls = turnTools)
                    _messages.value = msgs
                }

                // Known cache input is the hit-rate denominator; unknown usage remains in coverage.
                traceManager.record(currentTraceId!!, com.zero.v2.core.trace.NodeType.LLM_OUTPUT, "chat",
                    "${content.take(80)} | tokens=${tokenUsage.totalTokens}, " +
                            tokenUsage.cacheSummary,
                    tokensUsed = tokenUsage.totalTokens)

                // ── 持久化对话到 Room ──
                turnSnapshot?.let { if (!persistConversationTurn(text, it, ownedUserId)) _sendState.value = GenerationState.ERROR }

                // 更新 trace
                currentTraceId?.let { _traceTraces.value = traceManager.getTrace(it) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                finishCancelledTurn(text, attachment, ownedUserId, regenerated = false)
                throw e
            } catch (e: Exception) {
                _sendState.value = GenerationState.ERROR
                if (_inputText.value.isBlank()) setInput(text)
                if (attachment != null && _pendingAttachment.value == null) draftState.setAttachment(attachment)
                traceManager.record(currentTraceId!!, com.zero.v2.core.trace.NodeType.ERROR, "chat",
                    "错误: ${e.message?.take(80)}")
                currentTraceId?.let { _traceTraces.value = traceManager.getTrace(it) }

                _messages.value = _messages.value + ChatMessage(
                    role = MessageRole.ASSISTANT,
                    content = "[错误: ${e.message}]"
                )
            } finally {
                _isGenerating.value = false
                if (_sendState.value !in setOf(GenerationState.ERROR, GenerationState.STOPPED)) _sendState.value = GenerationState.IDLE
                finishGenerationTrace()
                updateRetryTarget(ownedUserId)
            }
        }
    }

    fun regenerate() = startRegeneration(null)

    fun retryFailedTurn() {
        val userId = _retryUserId.value ?: return
        if (_sendState.value != GenerationState.ERROR) return
        startRegeneration(userId)
    }

    private fun startRegeneration(expectedUserId: String?) {
        if (_isGenerating.value || isSwitchingConversation.value) return
        _isGenerating.value = true
        _sendState.value = GenerationState.GENERATING
        _retryUserId.value = null
        currentTraceId = traceManager.startTrace()
        _traceTraces.value = emptyList()
        _traceExpanded.value = false
        traceManager.record(currentTraceId!!, com.zero.v2.core.trace.NodeType.SYSTEM_EVENT, "chat", "regeneration_started")
        var ownedUserId: String? = null
        generationTask.start {
            try {
                initializationJob?.join()
                ownedUserId = expectedUserId ?: chatBot.chatHistory.value.messages.lastOrNull { it.role == MessageRole.USER }?.id
                val intent = chatBot.chatHistory.value.messages
                    .lastOrNull { it.role == MessageRole.USER }
                    ?.content
                    .orEmpty()
                var rejected = false
                chatBot.regenerate(
                    functions = toolContextSelector.select(chatBot.toolSelectionRequest(intent)),
                    userMessageId = ownedUserId,
                ).collect { chunk ->
                    chunk.errorCode?.let { code ->
                        _sendState.value = GenerationState.ERROR
                        traceManager.record(currentTraceId!!, com.zero.v2.core.trace.NodeType.ERROR, "chat", code)
                    }
                    if (chunk.errorCode in setOf("REGENERATION_NO_USER", "REGENERATION_MODEL_UNAVAILABLE", "REGENERATION_TURN_CHANGED")) {
                        rejected = true
                        _messages.value = projectToUiMessages(chatBot.chatHistory.value.messages) + ChatMessage(
                            id = UUID.randomUUID().toString(), role = MessageRole.ASSISTANT, content = chunk.contentDelta)
                        return@collect
                    }
                    chatBot.applyStreamChunk(chunk)
                    _messages.value = projectToUiMessages(chatBot.chatHistory.value.messages)
                    if (chatBot.chatHistory.value.messages.lastOrNull()?.toolCalls?.isNotEmpty() == true &&
                        _sendState.value != GenerationState.ERROR) {
                        _sendState.value = GenerationState.CALLING_TOOL
                    }
                }
                if (rejected) return@start
                val history = chatBot.chatHistory.value
                _messages.value = projectToUiMessages(history.messages)
                ownedUserId?.let { if (!conversationRepo.persistRegeneratedReply(history, it)) _sendState.value = GenerationState.ERROR }
            } catch (e: kotlinx.coroutines.CancellationException) {
                finishCancelledTurn("", null, ownedUserId, regenerated = true)
                throw e
            } catch (e: Exception) {
                _sendState.value = GenerationState.ERROR
                traceManager.record(currentTraceId!!, com.zero.v2.core.trace.NodeType.ERROR, "chat", "regeneration_failed")
                _messages.value = _messages.value + ChatMessage(
                    role = MessageRole.ASSISTANT,
                    content = "[错误: ${e.message}]"
                )
            } finally {
                _isGenerating.value = false
                if (_sendState.value !in setOf(GenerationState.ERROR, GenerationState.STOPPED)) _sendState.value = GenerationState.IDLE
                finishGenerationTrace()
                updateRetryTarget(ownedUserId)
            }
        }
    }

    private fun updateRetryTarget(userId: String?) {
        _retryUserId.value = userId?.takeIf {
            _sendState.value == GenerationState.ERROR &&
                chatBot.chatHistory.value.messages.lastOrNull { it.role == MessageRole.USER }?.id == it
        }
    }

    private fun resetGenerationPresentation() {
        if (_isGenerating.value) return
        _sendState.value = GenerationState.IDLE
        _retryUserId.value = null
        _traceTraces.value = emptyList()
        _traceExpanded.value = false
    }

    private fun finishGenerationTrace() {
        currentTraceId?.let { id ->
            traceManager.record(id, com.zero.v2.core.trace.NodeType.SYSTEM_EVENT, "chat", "generation_finished: ${_sendState.value}")
            _traceTraces.value = traceManager.getTrace(id)
            traceManager.endTrace(id)
        }
    }

    private suspend fun finishCancelledTurn(text: String, attachment: Attachment?, userId: String?, regenerated: Boolean) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            var saved = true
            val history = chatBot.chatHistory.value
            if (userId != null && history.messages.any { it.id == userId && it.role == MessageRole.USER }) {
                _messages.value = projectToUiMessages(history.messages)
                saved = try {
                    kotlinx.coroutines.withTimeout(3_000) {
                        if (regenerated) conversationRepo.persistRegeneratedReply(history, userId)
                        else persistConversationTurn(text, history, userId)
                    }
                } catch (_: Exception) { false }
            } else if (!regenerated) {
                if (_inputText.value.isBlank()) setInput(text)
                if (_pendingAttachment.value == null) draftState.setAttachment(attachment)
            }
            _sendState.value = when {
                !saved -> GenerationState.ERROR
                generationTask.userStopped -> GenerationState.STOPPED
                else -> GenerationState.IDLE
            }
            currentTraceId?.let { traceId ->
                traceManager.record(traceId, com.zero.v2.core.trace.NodeType.SYSTEM_EVENT, "chat",
                    if (saved) "generation_cancelled: partial_turn_retained" else "generation_cancelled: persist_failed")
                _traceTraces.value = traceManager.getTrace(traceId)
                traceManager.endTrace(traceId)
            }
        }
    }

    fun clearConversation() {
        generationTask.transition {
            initializationJob?.join()
            startNewConversation()
        }
    }

    /** 删除指定对话。如果删除的是当前对话，则切换到最近的另一个对话或新建。 */
    fun deleteConversation(conversationId: String) {
        generationTask.transition(cancelGeneration = { conversationId == _currentConversationId.value }) {
            initializationJob?.join()
            conversationRepo.deleteConversation(conversationId)
            val remaining = conversationRepo.getConversations()
            _conversationList.value = remaining
            if (conversationId == _currentConversationId.value) {
                if (remaining.isNotEmpty()) {
                    loadConversationInternal(remaining.first().id)
                } else {
                    startNewConversation()
                }
            }
        }
    }

    /** 加载指定对话的历史消息 */
    fun loadConversation(conversationId: String) {
        generationTask.transition {
            initializationJob?.join()
            loadConversationInternal(conversationId)
        }
    }

    /** 刷新对话列表 */
    fun loadHistory() {
        viewModelScope.launch {
            _conversationList.value = conversationRepo.getConversations()
        }
    }

   // ═══ 内部 ═══
    /**
     * v2.6 / M1: 按引用读取附件图片字节 → Base64 data URL（运行时 only，不入库/日志正文）。
     * 读取限制经 [ContentLimits] 裁定（5MB/空流/mime）；失败原样返回（优雅降级）。
     */
    private suspend fun resolveAttachmentData(att: Attachment): Attachment {
        return attachmentStore.prepare(att)
    }

    private suspend fun ensureActiveCharacter(): CharacterEntity? {
        return characterWrites.ensureActive()
    }

    private fun applyGlobalCharacter(character: CharacterEntity?) {
        globalActiveCharacterId = character?.id
        globalActiveCharacterName = character?.name ?: "Zero"
        globalActiveSystemPrompt = character?.systemPrompt ?: DEFAULT_PROMPT_TEXT
        chatBot.initialize(globalActiveSystemPrompt)
        _activeCharacterName.value = globalActiveCharacterName
    }

    private suspend fun startNewConversation() {
        resetGenerationPresentation()
        chatBot.initialize(globalActiveSystemPrompt)
        _messages.value = emptyList()
        _activeCharacterName.value = globalActiveCharacterName
        val newConversationId = conversationRepo.createConversation(
            title = "\\u65b0\\u5bf9\\u8bdd",
            characterId = globalActiveCharacterId
        )
        _currentConversationId.value = newConversationId
        draftState.conversationId = newConversationId
        // S2R.4：chatHistory.id 与 Room 会话 id 对齐（persistTurn 桥缝按 history.id 落库
        // ——不对齐则自主回合留痕 FK 失配静默失败）
        chatBot.loadHistory(newConversationId, emptyList())
        _conversationList.value = conversationRepo.getConversations()
    }

    /**
     * UI 消息投影：从完整 chatHistory 中筛选可显示的消息。
     *
     * 规则:
     *   USER → ✅
     *   ASSISTANT + content 非空 + toolCalls 为空 → ✅
     *   ASSISTANT + tool_calls only → ❌ (Agent 思维链展示)
     *   TOOL / SYSTEM → ❌
     */
    private suspend fun loadConversationInternal(conversationId: String) {
        val conversation = conversationRepo.getConversation(conversationId) ?: return
        resetGenerationPresentation()
        val conversationCharacter = conversation.characterId
            ?.let { characterId -> characterDao.getById(characterId) }
        chatBot.initialize(conversationCharacter?.systemPrompt ?: globalActiveSystemPrompt)
        _activeCharacterName.value = conversationCharacter?.name ?: globalActiveCharacterName
        _currentConversationId.value = conversationId
        draftState.conversationId = conversationId

        val messages = conversationRepo.getMessages(conversationId)
        // S2R.4：回读映射器切换——source/systemSource/triggerId 三列恢复（repo.toChatMessage，
        // S2R.2 落地），重启后系统消息按完整模型渲染（S2R.5 三色数据源）
        val chatMessages = messages.mapNotNull { conversationRepo.toChatMessage(it) }
        chatBot.loadHistory(conversationId, chatMessages)
        _messages.value = projectToUiMessages(chatMessages)
    }
    private fun projectToUiMessages(history: List<ChatMessage>): List<ChatMessage> {
        val replyTools = mutableMapOf<String, List<com.zero.v2.core.common.ToolCall>>()
        var tools = emptyList<com.zero.v2.core.common.ToolCall>()
        history.forEach { message ->
            if (message.role == MessageRole.USER) tools = emptyList()
            if (message.role == MessageRole.ASSISTANT) {
                tools = (tools + message.toolCalls).distinctBy { it.id }
                if (!message.content.isNullOrBlank()) replyTools[message.id] = tools
            }
        }
        return history.filter { msg ->
            when (msg.role) {
                MessageRole.USER -> true
                MessageRole.ASSISTANT -> msg.content?.isNotBlank() == true
                MessageRole.TOOL -> msg.attachments.isNotEmpty()
                else -> false
            }
        }.map { msg ->
            // S2R.4：系统注入消息可见正文 = "[事件·X] 净载荷"——≪sys≫ 指令段剥离
            // （持久化保留，后续轮 LLM 仍知当时指令）+ D107 定界标签行剥离（视觉预分析同法先例）。
            // S2R.5 系统卡样式（图标/三色/折叠）按 systemSource 渲染
            if (msg.role == MessageRole.USER &&
                msg.source == com.zero.v2.core.common.MessageSource.AUTONOMOUS) {
                msg.copy(content = stripSysSection(msg.content ?: ""))
            }
            // v6.8: 剥离 UI 展示用不到的视觉预分析块（LLM 侧仍随历史可见）
            else if (msg.role == MessageRole.USER && msg.content != null)
                msg.copy(content = msg.content!!.substringBefore("\n[图片内容预分析]"))
            else if (msg.role == MessageRole.TOOL) msg.copy(role = MessageRole.ASSISTANT, content = "媒体结果")
            else if (msg.role == MessageRole.ASSISTANT) msg.copy(toolCalls = replyTools[msg.id] ?: msg.toolCalls)
            else msg
        }
    }

    /** S2R.4：≪sys≫ 指令段剥离 + D107 定界标签行剥离（仅显示层——持久化保留） */
    private fun stripSysSection(content: String): String {
        val visible = content.substringBefore("\n≪sys≫")
        return visible.lineSequence()
            .filterNot { it.trimStart().startsWith("<untrusted_external_content") }
            .joinToString("\n")
    }

    /**
     * 将本轮对话（user + assistant）持久化到 Room。
     * 在 LLM 流式回复完成后调用。
     */
    private suspend fun persistConversationTurn(
        userText: String, history: com.zero.v2.framework.chat.ChatHistory, userId: String? = null,
    ): Boolean {
        val convId = history.id
        traceManager.record(
            currentTraceId!!, com.zero.v2.core.trace.NodeType.SYSTEM_EVENT, "persist",
            "persist_conversation: conversationId=$convId"
        )

        try {
            // 获取 ChatBotModule 中的消息以取得 UUID
            val messages = history.messages
            val userMsg = messages.findLast { it.role == MessageRole.USER && (userId == null || it.id == userId) }


            // Preserve the exact ordered transcript, including context and assistant/tool pairs.
            val wasEmpty = conversationRepo.getConversation(convId)?.messageCount == 0
            if (userMsg != null) {
                conversationRepo.persistTurn(convId, messages, userMsg.id)
            }
            // 首条消息 → 更新对话标题
            val conv = conversationRepo.getConversation(convId)
            if (conv != null && wasEmpty) {
                conversationRepo.updateTitle(
                    conversationId = convId,
                    title = userText.take(30)
                )
            }

            traceManager.record(
                currentTraceId!!, com.zero.v2.core.trace.NodeType.SYSTEM_EVENT, "persist",
                "persist_done: conversationId=$convId"
            )
            return true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            traceManager.record(
                currentTraceId!!, com.zero.v2.core.trace.NodeType.ERROR, "persist",
                "persist_error: ${e.message?.take(80)}"
            )
            return false
        }
    }

    private fun updateAssistantMessage(content: String) {
        val msgs = _messages.value.toMutableList()
        if (msgs.lastOrNull()?.role == MessageRole.ASSISTANT) {
            msgs[msgs.lastIndex] = msgs.last().copy(content = content)
        } else {
            msgs.add(ChatMessage(role = MessageRole.ASSISTANT, content = content))
        }
        _messages.value = msgs
    }
}
