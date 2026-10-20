package com.zero.v2.services.realtime

import kotlinx.coroutines.flow.Flow

/**
 * Realtime 语音提供器统一接口 — D46（R0 级联 / R1 云端同接口可换，LiveSession 不感知实现）。
 *
 * 实现契约：
 *   - [start] 开启会话（资源申请）；[stop] 结束并全释放（D38 会话制）
 *   - [events] 输出统一事件流（转写增量/状态变化/回复音频就绪/错误）
 *   - [interrupt] 打断当前播报（R0 = 手动触发；R1 = VAD 自动 + 手动）
 *   - 事件在主线程之外回调时实现方自行保证线程安全
 */
interface RealtimeVoiceProvider {

    /** 提供器标识（"cascade" / "cloud"） */
    val id: String

    /** 显示名（"级联语音" / "云端实时"） */
    val displayName: String

    /** 开启会话。返回 false = 麦克风不可用/初始化失败（events 中亦有 error 事件） */
    suspend fun start()

    /** 结束会话，释放全部资源 */
    suspend fun stop()

    /** 是否运行中 */
    fun isActive(): Boolean

    /** 打断当前播报（半双工 R0：手动；R1：VAD + 手动） */
    fun interrupt()

    /** 事件流（会话期间活跃） */
    fun events(): Flow<RealtimeVoiceEvent>
}

/** Realtime 统一事件 */
sealed interface RealtimeVoiceEvent {
    /** 用户语音转写（增量 delta 或完整 text） */
    data class UserTranscript(val text: String, val isFinal: Boolean) : RealtimeVoiceEvent

    /** AI 回复文本就绪（R0 在完整生成后；R1 流式分段） */
    data class AssistantText(val text: String, val isFinal: Boolean) : RealtimeVoiceEvent

    /** 状态变化 */
    data class StateChanged(val state: RealtimeState) : RealtimeVoiceEvent

    /** 错误（可恢复 = 会话继续；不可恢复 = 会话终止） */
    data class Error(val message: String, val recoverable: Boolean) : RealtimeVoiceEvent
}

/**
 * Realtime 会话状态（与 AgentState 分离管理，v3.5 GPT 意见采纳）。
 * R0 级联半双工：LISTENING ↔ SPEAKING 互斥切换（R2）。
 */
enum class RealtimeState {
    IDLE,               // 未开始
    LISTENING,          // 听用户说话（STT 活跃）
    THINKING,           // LLM 生成中
    SPEAKING,           // TTS 播报中（R0：STT 暂停防自听）
    INTERRUPTED,        // 被打断（手动/R1 VAD）
    OBSERVING_SCREEN    // 屏幕阅读激活（与 LISTENING 可并存，UI 用）
}

/**
 * 对话引擎桥 — D46 依赖反转。
 *
 * VoiceCascadeModule（services）不直接依赖 ChatBotModule（framework.chat——
 * 它依赖 services 的 ModelSelector，直接注入会成环）。
 * 由 app 层提供实现：桥接到 ChatBotModule.sendMessage()（完整 LLM + FC 链路）。
 */
interface ChatEngineBridge {
    /**
     * 发送一轮对话，流式聚合完整回复文本。
     * @param onStatus 过程状态回调（"🔧 调用工具: xxx" / "⚡ 缓存命中 N%"）——供 UI 实时反馈，null 忽略
     * @param onSentence 分句流回调（Step 6.12 流水线：LLM token 流按标点断句逐句下发，
     *   首句即到即播——首音延迟从"全文生成完"降到"首句生成完"）；流末尾冲刷余量
     * @return 完整回复（含 FC 循环结果）；失败抛异常
     */
    suspend fun chatAndCollect(
        userText: String,
        source: String = "VOICE",
        onStatus: ((String) -> Unit)? = null,
        onSentence: ((String) -> Unit)? = null,
        triggerId: String? = null,  // D1-fix（A1b 接线）：AUTONOMOUS 回合的 TriggerEvent.id 贯通
        systemSource: String? = null  // S2R.3：事件来源细名（TriggerEvent.source）——落 userMsg.systemSource（§2.1 留痕/审计链）
    ): String

    // ═══ A1a D79：查询方法（仅查询无副作用——依赖反转，实现收敛 app 层 ChatEngineBridgeImpl）═══

    /** 对话是否空闲（chatState==IDLE）——自主触发并发门控用（D67） */
    fun isConversationIdle(): Boolean

    /** 当前活跃回合上下文（null=无活跃回合）——autonomous_notify 源校验用（D75/D79） */
    fun activeTurn(): com.zero.v2.core.common.TurnContext?

    // ═══ S2R.4：回合留痕桥缝（TtsWavSink 先例——app 侧实装落 Room）═══

    /**
     * 回合留痕：本回合切片（系统消息 userMsg + assistant 已流式部分 + TOOL）全量落 Room。
     * AutonomousThinkingJob 回合 finally 调用；切片按 triggerId 精确圈定（Impl 侧）。
     * 取消路径调用方须 NonCancellable 包裹（R5：已取消协程内普通 suspend 即抛
     * CancellationException——留痕会丢）。
     * @return 落库条数；0=无本回合消息可落（等闲跳过等）；-1=失败（Impl 内部留痕，E28）
     */
    suspend fun persistTurn(triggerId: String?, systemSource: String?): Int
}
