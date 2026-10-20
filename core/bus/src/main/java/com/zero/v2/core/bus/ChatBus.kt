package com.zero.v2.core.bus

import com.zero.v2.core.common.*
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * 对话事件总线接口。
 *
 * 对标 Alife ChatBot 事件系统。
 * 所有模块通过 ChatBus 解耦通信，而非直接方法调用。
 *
 * v2.1 缓冲策略：
 *   - onChatReceived: DROP_OLDEST(64) — 高频流式渲染，丢一帧不影响
 *   - onToolCallStart/Result: SUSPEND(16) — 不可丢帧
 *   - onChatFinished: SUSPEND(8) — 不可丢帧
 *   - onPokeSend: DROP_OLDEST(32) — 可丢旧消息
 */
interface ChatBus {

    /** 用户消息已发送（通知性质，只读） — DROP_OLDEST(16) */
    val onChatSent: MutableSharedFlow<ChatSentEvent>

    /** LLM 流式回复到达 — DROP_OLDEST(64) */
    val onChatReceived: MutableSharedFlow<StreamChunk>

    /** 一轮对话结束 — SUSPEND(8) */
    val onChatFinished: MutableSharedFlow<ChatFinishedEvent>

    /** 消息加入历史 — SUSPEND(8) */
    val onChatHistoryAdd: MutableSharedFlow<ChatMessage>

    /** 系统消息（Poke）发送 — DROP_OLDEST(32) */
    val onPokeSend: MutableSharedFlow<PokeEvent>

    /** 工具调用开始 — SUSPEND(16) */
    val onToolCallStart: MutableSharedFlow<ToolCallStartEvent>

    /** 工具调用结果 — SUSPEND(16) */
    val onToolCallResult: MutableSharedFlow<ToolCallResultEvent>

    /** 用户优先打断（A1a D68）：用户消息命中自主回合进行中时 emit；ChatEngineBridgeImpl 收→cancel 自有 job — DROP_OLDEST(1) */
    val onUserPriorityInterrupt: MutableSharedFlow<Unit>

    /** S2.3/D117 工具调用事件（显示层观察流；G5：toolCallId+traceId 关联 RunLog 审计全量）— DROP_OLDEST(64) */
    val onToolCallEvent: MutableSharedFlow<ToolTraceEvent>
}

/** S2.3：工具调用轨迹事件（摘要级——全量参数/结果以 id 经 RunLog 检索，摘要不可还原全量） */
data class ToolTraceEvent(
    val toolCallId: String,
    val traceId: String,
    val name: String,
    /** 参数摘要 ≤80 字 */
    val argsDigest: String? = null,
    /** 结果摘要 ≤80 字（开始事件为 null） */
    val resultDigest: String? = null,
    val ok: Boolean = true,
    val durationMs: Long = 0,
    val round: Int = 0,
    val ts: Long = System.currentTimeMillis()
)
