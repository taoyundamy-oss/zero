package com.zero.v2.app.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zero.v2.core.moduleapi.SystemSnapshot
import com.zero.v2.core.moduleapi.SystemStateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * 系统状态胶囊 ViewModel（S1 / L-5）——UI 订阅 [SystemStateRepository] 投影。
 *
 * `liveActive` 取自语音会话投影（LocalVoiceProvider.isActive 经 Repository 聚合），
 * 与 LLM 经 `zero_status` FC 读到的 voice section 同源——落实总纲"UI 与 LLM 共享真实状态投影"。
 * 不持有独立状态，删除 ZeroNavHost `liveActive = false` 硬编码。
 *
 * U3：暴露完整快照 [snapshot] 供系统状态卡消费（运行态/模型路由/预算/termux/语音——绑定真实状态，
 * 非硬编码）。
 */
@HiltViewModel
class SystemStatusViewModel @Inject constructor(
    systemState: SystemStateRepository,
) : ViewModel() {

    /** Live 语音会话进行中——驱动 ChatScreen mic 按钮态/输入禁用。 */
    val liveActive: StateFlow<Boolean> = systemState.snapshots
        .map { it.voice.active }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), false)

    /** U3：完整快照——系统状态卡数据源（绑定真实 S1 投影，与 LLM zero_status 同源）。 */
    val snapshot: StateFlow<SystemSnapshot?> = systemState.snapshots
        .map { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)
}
