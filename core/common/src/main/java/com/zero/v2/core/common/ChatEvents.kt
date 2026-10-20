package com.zero.v2.core.common

/**
 * 对话总线事件类型定义。
 *
 * 对标 Alife ChatBot 事件系统：
 *   ChatSend → ChatSent → ChatReceived(流式) → ChatFinished
 *   以及 PokeSend、ToolCallStart、ToolCallResult
 */

/** 消息来源枚举 */
enum class MessageSource {
    /** 聊天窗口直接输入 */
    CHAT_WINDOW,
    /** 系统消息（Poke） */
    POKE,
    /** 自动化任务 */
    AUTOMATION,
    /** QQ/通讯平台 */
    REMOTE,
    /** 语音输入 */
    VOICE,
    /** 自主思考（事件触发，A1a 新增） */
    AUTONOMOUS
}

// ═══ TurnContext 契约（自主触发与健康守护设计 A1a，D79）═══

/** 回合来源（区别于 MessageSource：回合级语义，供 TurnContext 校验用） */
enum class TurnSource { USER, AUTONOMOUS, LIVE }

/**
 * 回合级不可变上下文（D79 契约冻结）。
 *
 * 每一轮对话（sendMessage flow 生命周期）绑定一个 TurnContext：
 *   - turnId: 唯一标识（UUID）
 *   - source: 回合来源（USER/AUTONOMOUS/LIVE）
 *   - triggerId: AUTONOMOUS 回合的 TriggerEvent.id（其他来源 null）
 *
 * ChatBotModule 持 AtomicReference<TurnContext?>（activeTurn）——入口设置、finally 清除。
 * autonomous_notify FC 校验 activeTurn.source==AUTONOMOUS && triggerId!=null（D75 护栏）。
 * 不可变 data class → 无嵌套/竞态污染（A 轮读到 B 的 trigger 不可能发生）。
 */
data class TurnContext(
    val turnId: String,
    val source: TurnSource,
    val triggerId: String? = null
) {
    companion object {
        fun user() = TurnContext(turnId = java.util.UUID.randomUUID().toString(), source = TurnSource.USER)
        fun live() = TurnContext(turnId = java.util.UUID.randomUUID().toString(), source = TurnSource.LIVE)
        fun autonomous(triggerId: String? = null) =
            TurnContext(turnId = java.util.UUID.randomUUID().toString(), source = TurnSource.AUTONOMOUS, triggerId = triggerId)

        /** MessageSource → TurnSource 映射（sendMessage 入口建 ctx 用） */
        fun fromMessageSource(ms: MessageSource, triggerId: String? = null): TurnContext = when (ms) {
            MessageSource.VOICE -> live()
            MessageSource.AUTONOMOUS -> autonomous(triggerId)
            else -> user()
        }
    }
}

/** 用户消息发送事件（由 ChatBotModule 构建，经 MessagePipeline 处理后发送） */
data class ChatSendEvent(
    val message: String,
    val source: MessageSource,
    val attachments: List<Any> = emptyList()
)

/** 用户消息已发送事件（通知性质，只读 — ChatBus 广播） */
data class ChatSentEvent(
    val message: ChatMessage,
    val timestamp: Long
)

/** 流式回复块 */
data class StreamChunk(
    val contentDelta: String? = null,
    val thinkingDelta: String? = null,
    val toolCallDelta: ToolCallDelta? = null,
    val tokenUsage: TokenUsage? = null,
    val finishReason: String? = null,
    val isFinal: Boolean = false,
    val errorCode: String? = null,
    val providerContent: ProviderContentState? = null,
)

/** Opaque complete provider content; never render or forward outside its original route. */
data class ProviderContentState(val scope: String, val contentJson: String) {
    override fun toString(): String = "ProviderContentState(redacted)"
}

/** Tool Call 增量 */
data class ToolCallDelta(
    val index: Int = 0,
    val id: String? = null,
    val name: String? = null,
    val argumentsDelta: String? = null,
    val providerState: ToolProviderState? = null,
)

/** 对话完成事件 */
data class ChatFinishedEvent(
    val userMessage: ChatMessage,
    val assistantMessage: ChatMessage,
    val toolCalls: List<ToolCall>,
    val totalTokens: TokenUsage,
    /** P3：回合所属会话 id——reducer 按 conversationId 读写 ConversationMoodState（默认"" 兼容旧 emit） */
    val conversationId: String = "",
    /** Original caller input before MessagePipeline adds runtime guidance; null for legacy emitters. */
    val originalUserText: String? = null,
)

/** 工具调用开始事件 */
data class ToolCallStartEvent(
    val name: String,
    val arguments: Map<String, Any?>,
    val callId: String
)

/** 工具调用结果事件 */
data class ToolCallResultEvent(
    val callId: String,
    val name: String,
    val success: Boolean,
    val data: String,
    val errorMessage: String? = null,
    val tokensUsed: Long = 0,
    val durationMs: Long = 0
)

/** Poke 消息事件（系统消息注入对话） */
data class PokeEvent(
    val message: String,
    val source: String,
    val priority: PokePriority = PokePriority.NORMAL
)

/** Poke 优先级 */
enum class PokePriority { LOW, NORMAL, HIGH, URGENT }

/** Token 用量 */
data class TokenUsage(
    val inputTokens: Long = 0,
    val cachedTokens: Long = 0,
    val outputTokens: Long = 0,
    val totalTokens: Long = 0,
    val model: String = "",
    /** Input tokens for which the provider explicitly reported valid cache counts. */
    val cacheObservedInputTokens: Long = 0,
    val cacheUnknownSamples: Long = if (inputTokens > 0 && cacheObservedInputTokens == 0L) 1 else 0,
    val inputObserved: Boolean = true,
    val outputObserved: Boolean = true,
    val cacheWriteTokens: Long? = null,
) {
    operator fun plus(other: TokenUsage) = TokenUsage(
        inputTokens + other.inputTokens, cachedTokens + other.cachedTokens,
        outputTokens + other.outputTokens, totalTokens + other.totalTokens,
        other.model.ifBlank { model }, cacheObservedInputTokens + other.cacheObservedInputTokens,
        cacheUnknownSamples + other.cacheUnknownSamples,
        inputObserved && other.inputObserved, outputObserved && other.outputObserved,
        if (cacheWriteTokens != null && other.cacheWriteTokens != null) cacheWriteTokens + other.cacheWriteTokens else null,
    )

    val cacheSummary: String get() {
        if (cacheObservedInputTokens <= 0) return "缓存命中 unknown（无有效缓存观测）"
        val hit = cachedTokens * 100 / cacheObservedInputTokens
        val coverage = if (inputTokens > 0) cacheObservedInputTokens * 100 / inputTokens else 0
        val detail = "已观测 $cachedTokens/$cacheObservedInputTokens；已知输入覆盖率 $coverage%；未知记录 $cacheUnknownSamples"
        return if (cacheObservedInputTokens == inputTokens && cacheUnknownSamples == 0L) "缓存命中 $hit%（$detail）"
        else "缓存命中 $hit%（$detail；全部已知输入命中下界 ${cachedTokens * 100 / inputTokens}%）"
    }
}

/** 工具调用记录 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
    val providerState: ToolProviderState? = null,
    val streamIndex: Int? = null,
)

/** 消息角色 */
enum class MessageRole { SYSTEM, USER, ASSISTANT, TOOL }

/** 附件类型 */
enum class AttachmentType { IMAGE, FILE, AUDIO, VIDEO }

/** Saved with each attachment so later turns keep the same provider representation. */
enum class MediaRoute { AUTO, NATIVE, TOOLS }

/** 附件数据模型 */
data class Attachment(
    val uri: String,
    val mimeType: String,
    val type: AttachmentType = AttachmentType.IMAGE,
    /** v2.6: 运行时解析的图片载荷（data:image/...;base64,...）— 由 ChatViewModel 从 content:// URI 读取，
     *  供 ChatBotModule 视觉预处理（VisionModule Poke 快速路径）。不持久化。 */
    val base64Data: String? = null,
    val route: MediaRoute = MediaRoute.AUTO,
)

/** 聊天消息 */
data class ChatMessage(
    val id: String = "",
    val role: MessageRole,
    val content: String?,
    val thinking: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolCallName: String? = null,
    val attachments: List<Attachment> = emptyList(),
    val source: MessageSource = MessageSource.CHAT_WINDOW,
    /** S2R.1：系统注入来源细名（TriggerEvent.source，如 WECHAT_PRIVATE/SELF_DRIVE）——
     *  自驱/事件注入消息（source=AUTONOMOUS）携带；null=普通用户消息（§2.1 内容协议） */
    val systemSource: String? = null,
    /** S2R.1：触发事件 id（TriggerEvent.id）——留痕↔RunLog 审计链关联
     *  （F7 载体从 UI 徽标改为消息字段；triggerId 列预留清理策略，一期不做） */
    val triggerId: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val providerContent: ProviderContentState? = null,
)

/** 对话状态 */
enum class ChatState { IDLE, GENERATING, ERROR }
