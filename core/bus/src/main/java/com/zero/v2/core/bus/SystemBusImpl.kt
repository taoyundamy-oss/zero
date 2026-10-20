package com.zero.v2.core.bus

import com.zero.v2.core.common.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SystemBus 实现。
 */
@Singleton
class SystemBusImpl @Inject constructor() : SystemBus {

    override val onTick = MutableSharedFlow<Long>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** v2.1: 调度触发事件 — 不可丢帧 */
    override val onScheduleTriggered = MutableSharedFlow<ScheduleTriggeredEvent>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.SUSPEND
    )

    override val onNotificationPosted = MutableSharedFlow<NotificationEvent>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    override val onAppForeground = MutableSharedFlow<String>(
        replay = 1,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    override val onNetworkChanged = MutableSharedFlow<Boolean>(
        replay = 1,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    override val onBatteryChanged = MutableSharedFlow<BatteryEvent>(
        replay = 1,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** MU2.3（C2 fallback）：语音会话活跃状态——DROP_OLDEST 只关心最新态 */
    override val onVoiceActive = MutableSharedFlow<Boolean>(
        replay = 1,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** v2.2: 安全确认 — 不可丢帧 */
    override val onSecurityConfirmRequest = MutableSharedFlow<SecurityConfirmRequest>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.SUSPEND
    )

    override val onSecurityConfirmResponse = MutableSharedFlow<SecurityConfirmResponse>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.SUSPEND
    )

    /** MU4.2a（音乐轨 C1/M2）：音乐到期 tick——仿 onScheduleTriggered（不可丢帧，
     *  订阅方到期扫描幂等，重复消费无害） */
    override val onMusicDue = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.SUSPEND
    )

    /** KB1.4（知识库轨 KD8/C5 + EK5 勘误）：文件变更通知——DROP_OLDEST 满丢旧保新
     *  （新事件对账覆盖旧事件；emit 方 tryEmit 永不阻塞 FC），buffer 32 个人场景远不满；
     *  漏掉的事件由 KnowledgeModule 周期路兜底（最终一致性） */
    override val onFilesChanged = MutableSharedFlow<List<String>>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
}
