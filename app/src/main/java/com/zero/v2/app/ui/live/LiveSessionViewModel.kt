package com.zero.v2.app.ui.live

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zero.v2.app.background.LiveSessionService
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import com.zero.v2.services.realtime.LocalVoiceProvider
import com.zero.v2.services.realtime.RealtimeState
import com.zero.v2.services.realtime.RealtimeVoiceEvent
import com.zero.v2.services.realtime.ScreenTextReader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Live 界面状态 */
data class LiveUiState(
    val sessionActive: Boolean = false,
    val state: RealtimeState = RealtimeState.IDLE,
    /** 转写滚动（最近 50 条） */
    val transcript: List<TranscriptEntry> = emptyList(),
    val screenReadEnabled: Boolean = false,
    /** 屏幕共享开关态（v6.6d：按钮开/关语义与图标联动） */
    val screenSharing: Boolean = false
)

data class TranscriptEntry(
    val isUser: Boolean,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    /** Stable within this transient session, including same-millisecond / repeated-text events. */
    val id: String = java.util.UUID.randomUUID().toString()
)

/** 白名单候选 App 条目 */
data class AppEntry(
    val packageName: String,
    val label: String,
    val isReadingApp: Boolean = false  // 引导标记（浏览器/阅读/文档类）
)

/**
 * Live 会话 ViewModel — Step 6.5a。
 * 状态源 = VoiceCascadeModule 的 state/events 流（服务同源，UI 与服务一致）。
 */
@HiltViewModel
class LiveSessionViewModel @Inject constructor(
    application: Application,
    private val voiceProvider: LocalVoiceProvider,
    private val screenTextReader: ScreenTextReader,
    private val screenCapture: com.zero.v2.services.realtime.ScreenCaptureModule,
    private val traceManager: AgentTraceManager
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(LiveUiState())
    val state: StateFlow<LiveUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            voiceProvider.state.collect { rs ->
                _state.update { it.copy(state = rs, sessionActive = voiceProvider.isActive()) }
            }
        }
        viewModelScope.launch {
            screenCapture.sharing.collect { sharing ->
                _state.update { it.copy(screenSharing = sharing) }
            }
        }
        viewModelScope.launch {
            voiceProvider.events().collect { event ->
                when (event) {
                    is RealtimeVoiceEvent.UserTranscript -> if (event.isFinal) {
                        _state.update { s ->
                            s.copy(transcript = (s.transcript + TranscriptEntry(true, event.text)).takeLast(50))
                        }
                    }
                    is RealtimeVoiceEvent.AssistantText -> if (event.isFinal) {
                        _state.update { s ->
                            s.copy(transcript = (s.transcript + TranscriptEntry(false, event.text)).takeLast(50))
                        }
                    }
                    is RealtimeVoiceEvent.Error ->
                        _state.update { s ->
                            s.copy(transcript = (s.transcript + TranscriptEntry(false, "⚠️ ${event.message}")).takeLast(50))
                        }
                    else -> {}
                }
            }
        }
    }

    /** 启动 Live（前置：RECORD_AUDIO 已授权——Screen 层请求） */
    fun startSession() {
        if (voiceProvider.isActive()) return
        traceManager.record("ui_live", NodeType.SYSTEM_EVENT, "live", "session_start_requested")
        LiveSessionService.start(getApplication())
    }

    fun endSession() {
        traceManager.record("ui_live", NodeType.SYSTEM_EVENT, "live", "session_end_requested")
        LiveSessionService.stop(getApplication())
    }

    /** 6.6 屏幕共享开关（v6.6d：开↔关 toggle）。
     *  开：透明 ConsentActivity → 系统弹窗 → Service R1 时序落地；
     *  关：Service 统一释放投影 + 降级 FGS */
    fun toggleScreenShare() {
        if (screenCapture.isSharingActive()) {
            traceManager.record("ui_live", NodeType.SYSTEM_EVENT, "live",
                "screen_share_stop_requested")
            getApplication<Application>().startService(
                android.content.Intent(getApplication(), com.zero.v2.app.background.LiveSessionService::class.java)
                    .setAction(com.zero.v2.app.background.LiveSessionService.ACTION_STOP_PROJECTION))
            return
        }
        traceManager.record("ui_live", NodeType.SYSTEM_EVENT, "live", "screen_share_requested")
        getApplication<Application>().startActivity(
            android.content.Intent(getApplication(), com.zero.v2.app.background.ProjectionConsentActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun stopSpeaking() = voiceProvider.interrupt()

    fun toggleScreenRead() {
        val next = !_state.value.screenReadEnabled
        _state.update { it.copy(screenReadEnabled = next) }
        traceManager.record("ui_live", NodeType.SYSTEM_EVENT, "live",
            "screen_read_toggled: $next（白名单 ${screenTextReader.whitelist().size} 个 App）")
    }

    // ═══ 白名单管理（R4/D39）═══

    fun whitelist(): Set<String> = screenTextReader.whitelist()

    fun setWhitelist(packages: Set<String>) {
        screenTextReader.setWhitelist(packages)
    }

    /** 可选 App 列表（用户可安装的第三方+系统，排除自身与系统核心） */
    fun loadAppEntries(): List<AppEntry> {
        val pm = getApplication<Application>().packageManager
        val self = getApplication<Application>().packageName
        val readingHints = listOf("read", "reader", "book", "doc", "pdf", "browser",
            "阅读", "文档", "浏览器", "epub", "novel")
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { it.packageName != self }
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 || it.enabled }  // 非纯系统
            .mapNotNull { info ->
                val label = runCatching { pm.getApplicationLabel(info).toString() }
                    .getOrNull() ?: info.packageName
                AppEntry(
                    packageName = info.packageName,
                    label = label,
                    isReadingApp = readingHints.any { it in info.packageName.lowercase() || it in label }
                )
            }
            .sortedByDescending { it.isReadingApp }  // 阅读类置顶引导
            .sortedBy { it.label }
            .toList()
    }
}
