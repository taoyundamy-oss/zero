package com.zero.v2.core.bus

import com.zero.v2.core.common.*
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * 系统事件总线接口。
 *
 * 对标 Alife 的系统级事件 + ITimeIterative.OnUpdate。
 *
 * v2.1 新增 OnScheduleTriggered 事件 — 解耦 SystemEventModule 与 AutomationModule 的循环依赖。
 * SystemEventModule 只发布事件，AutomationModule 订阅执行。
 */
interface SystemBus {

    /** 心跳帧（对标 Alife OnUpdate） */
    val onTick: MutableSharedFlow<Long>

    /** 自动化调度触发事件（v2.1：解耦 SEM→ATM） */
    val onScheduleTriggered: MutableSharedFlow<ScheduleTriggeredEvent>

    /** 系统通知到达 */
    val onNotificationPosted: MutableSharedFlow<NotificationEvent>

    /** 应用切换到前台 */
    val onAppForeground: MutableSharedFlow<String>

    /** 网络连接变化 */
    val onNetworkChanged: MutableSharedFlow<Boolean>

    /** 电池状态变化 */
    val onBatteryChanged: MutableSharedFlow<BatteryEvent>

    /**
     * 语音会话活跃状态（MU2.3 · C2 fallback 裁决）：true=Live 语音会话开始，false=结束。
     * 由 LiveSessionService（会话宿主）发布；MusicModule 订阅暂停/恢复音乐——
     * AudioFocus 之外的保险层（sherpa TTS 用 AudioTrack 未必申请焦点，实测不暂停）。
     */
    val onVoiceActive: MutableSharedFlow<Boolean>

    /**
     * 音乐定时播放到期 tick（MU4.2a · C1/M2/MD14）：**Unit 载荷——tick 语义**，
     * 不携带"是哪个调度"（身份由 MusicModule 到期扫描+认领决定；闹钟恒指最近到期，
     * 单一 REQUEST_MUSIC 身份合法）。ExactAlarmReceiver（services）emit，
     * MusicModule（app）onStart collect——services→app 解耦（X12）。
     */
    val onMusicDue: MutableSharedFlow<Unit>

    /**
     * 工作目录文件变更通知（KB1.4 · KA10/KD8）：载荷 = 变更的工作目录相对路径列表。
     * FilesModule 四个可影响 knowledge/ 的写 FC（save_to_file[emit finalRelPath 真实落点]/
     * move_file/delete_work_file/delete_folder，AA2/C8）尾部 tryEmit；
     * KnowledgeModule collect → debounce → 触发 doIndex 全量树对账。
     * **事件=提示，树=真相**（GA2/C6）：事件只触发索引，一致性以 listTree 三元组对账
     * 为准；DROP_OLDEST + tryEmit 不阻塞 FC（满丢旧保新——新事件的对账覆盖旧事件；
     * EK5 勘误：原设计写 EXTRA_BUFFERED 系 Channel 概念误植，SharedFlow 无此策略）。
     */
    val onFilesChanged: MutableSharedFlow<List<String>>

    /** 安全确认请求（v2.2） */
    val onSecurityConfirmRequest: MutableSharedFlow<SecurityConfirmRequest>

    /** 安全确认响应（v2.2） */
    val onSecurityConfirmResponse: MutableSharedFlow<SecurityConfirmResponse>
}
