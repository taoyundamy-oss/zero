package com.zero.v2.core.bus

import com.zero.v2.core.common.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ChatBus 实现 — 差异化 SharedFlow 缓冲策略。
 *
 * v2.1 策略：
 *   - 高频流式渲染 → DROP_OLDEST（丢旧帧，保证 UI 不卡）
 *   - 关键事件（ToolCall、ChatFinished）→ SUSPEND（不丢帧，阻塞生产者）
 *   - 通知性质消息 → DROP_OLDEST（可丢弃过期消息）
 */
@Singleton
class ChatBusImpl @Inject constructor() : ChatBus {

    /** 通知性质 — 可丢旧消息 */
    override val onChatSent = MutableSharedFlow<ChatSentEvent>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** 高频流式 — 可丢帧（UI 渲染，丢一帧不影响最终结果） */
    override val onChatReceived = MutableSharedFlow<StreamChunk>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** 每次对话一次 — 不可丢帧 */
    override val onChatFinished = MutableSharedFlow<ChatFinishedEvent>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.SUSPEND
    )

    /** 不可丢帧 */
    override val onChatHistoryAdd = MutableSharedFlow<ChatMessage>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.SUSPEND
    )

    /** 系统消息 — 可丢旧消息 */
    override val onPokeSend = MutableSharedFlow<PokeEvent>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** ToolCall 不可丢帧 */
    override val onToolCallStart = MutableSharedFlow<ToolCallStartEvent>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.SUSPEND
    )

    /** ToolCall 结果不可丢帧 */
    override val onToolCallResult = MutableSharedFlow<ToolCallResultEvent>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.SUSPEND
    )

    /** A1a D68：用户优先打断——容量 1 新覆旧，丢旧不打紧 */
    override val onUserPriorityInterrupt = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** S2.3 工具轨迹显示流 — DROP_OLDEST(64)（显示级可丢旧；全量审计在 RunLog） */
    override val onToolCallEvent = MutableSharedFlow<ToolTraceEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
}
