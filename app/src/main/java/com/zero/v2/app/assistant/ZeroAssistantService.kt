package com.zero.v2.app.assistant

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.zero.v2.app.background.LiveSessionService
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** KB3.1a 文件内共享 trace 常量 */
private const val TRACE_ID = "assistant"
private const val MODULE = "assistant"

/**
 * Zero Assistant 壳 — KB3.1a（设计 §十八 · 裁-5/KD11）。
 *
 * 触发链：手势（对角线上滑）/电源键长按 → 系统绑定当前默认助手（用户在
 * 设置→应用→默认应用→数字助手 选择 Zero，KB3.1b 提供引导入口）→
 * onPrepareSession → Session.show()。
 *
 * v1 形态 = **最薄壳**：Session 自带系统窗口（免 Android 10+ BAL 限制，AB-E）
 * 立即退场，直接拉起既有 LiveSessionService 语音会话（音乐轨 MD17"单入口"同精神）——
 * 零新 UI、零新逻辑；语音全链（VAD/ASR/TTS/对话）全部复用既有组件。
 *
 * R6（会话并发）：重复触发直接幂等 start——LiveSessionService.onStartCommand 的
 * 无 action 路径幂等（不重置状态），无需运行态查询。
 *
 * 声明见 Manifest：`android:permission="android.permission.BIND_VOICE_INTERACTION"`
 * 为组件属性（约束绑定方=系统），app 自身零特权（KD15/§18.1）。
 */
@AndroidEntryPoint
class ZeroAssistantService : VoiceInteractionService() {

    @Inject lateinit var traceManager: AgentTraceManager

    override fun onReady() {
        // 服务就绪（被系统绑定/默认助手选中后的生命周期起点）
        traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, MODULE,
            "assistant_service_ready: 零特权通道（用户选择为默认助手后系统绑定）")
    }
}

/**
 * 会话宿主（manifest 声明 + BIND_VOICE_INTERACTION；由 ZeroAssistantService 的
 * meta-data @xml/zero_assistant 关联——系统标准机制）。
 */
@AndroidEntryPoint
class ZeroAssistantSessionService : VoiceInteractionSessionService() {

    @Inject lateinit var traceManager: AgentTraceManager

    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        ZeroAssistantSession(this, traceManager)
}

/**
 * 一次 assistant 触发的会话窗口：onShow = 手势/电源键触发点——起 Live 语音会话后
 * 本窗口即退（v1 无 overlay UI 需求；后续版本可在此挂 Gemini 式半屏 UI）。
 */
class ZeroAssistantSession(
    context: Context,
    private val traceManager: AgentTraceManager
) : VoiceInteractionSession(context) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, MODULE,
            "assistant_invoked: args=${args?.keySet()?.joinToString(",").orEmpty().take(40)} " +
                    "→ 拉起 Live 语音会话（幂等，R6）")
        // MD17 单入口同精神；会话已在跑时 onStartCommand 无 action 路径幂等不重置
        LiveSessionService.start(context)
        hide()  // 系统窗口退场，语音会话（悬浮泡/通知）接管
    }
}

/**
 * KB3.1b-fix2（L3 实测 09-14 · 数字助手列表无 Zero）：**ASSIST activity 路**。
 *
 * 系统"数字助手"候选列表 = VoiceInteractionService（service 路）∥ ACTION_ASSIST
 * activity（轻助手路）两路并集（AOSP DefaultAssistPreferenceController 同构）——
 * ChatGPT 等成功入选的公开配方两路兼备；此前 Zero 只有 service 路，One UI 对第三方
 * VIS 挑剔时即被列表遗漏。本 activity 补第二路：无 UI 中转（Theme.NoDisplay）——
 * 收到 assist intent（含部分 OEM 的手势/长按触发路径）→ 起 Live 语音会话 → 立即
 * finish。manifest 声明含 ASSIST + DEFAULT 双 category。
 */
class AssistHandlerActivity : android.app.Activity() {

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { LiveSessionService.start(this) }
            .onFailure {
                android.widget.Toast.makeText(this,
                    "语音会话启动失败：${it.message?.take(40)}", android.widget.Toast.LENGTH_SHORT).show()
            }
        finish()  // NoDisplay 主题：onCreate 内必须 finish，不留任务痕迹（noHistory）
    }
}
