package com.zero.v2.app.background

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import com.zero.v2.services.agent.AgentRuntime
import com.zero.v2.services.realtime.ScreenTextReader
import com.zero.v2.services.shizuku.ShizukuBridge
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Agent Runtime 服务 — 基于 AccessibilityService 的自主运行引擎（DESIGN §2.1-2.2 / D15）。
 *
 * 职责（服务=感官；自主调度由应用完成模块初始化后启动）：
 *   1. 服务连接/断开只挂载/卸载感官能力，不改变用户自主行为模式或心跳。
 *   2. 窗口切换 → agentRuntime.onUserActivity()（用户活跃 → 重置报点间隔）
 *   3. **v6.2**：向 ScreenTextReader 注入 rootInActiveWindow 读取入口（D39：canRetrieveWindowContent=true，
 *      隐私边界=白名单包 + 仅 FC/Live 调用时读取；升级后需重新开关无障碍服务生效 — R8）
 *   4. **v6.2**：TYPE_WINDOW_CONTENT_CHANGED 节流（3s）→ ScreenTextReader 通知（页面变化提示，快照仍为拉取式）
 *   5. **M1.2（模块化轨）**：向 ShizukuBridge 注入手势兜底执行器（canPerformGestures=true——
 *      Shizuku 未激活时 tap/swipe 回退 dispatchGesture；配置升级需重开无障碍生效 — R8）
 */
@AndroidEntryPoint
class AgentRuntimeService : AccessibilityService() {

    @Inject lateinit var agentRuntime: AgentRuntime
    @Inject lateinit var screenTextReader: ScreenTextReader
    @Inject lateinit var shizukuBridge: ShizukuBridge
    @Inject lateinit var traceManager: AgentTraceManager
    // M3.0：工作站文件通道（WorkDirRepository 在 app 层——services 侧 TermuxBridge 经接口消费）
    @Inject lateinit var termuxBridge: com.zero.v2.services.termux.TermuxBridge
    @Inject lateinit var workDirRepository: com.zero.v2.app.data.WorkDirRepository

    /** 内容变化节流：上次转发时间 */
    private var lastContentChangeForward = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        traceManager.record("agent_runtime", NodeType.SYSTEM_EVENT, "accessibility",
            "accessibility_connected: 屏幕与手势能力已连接")
        // v6.2: 注入屏幕根节点读取入口（拉取式——ScreenTextReader 调用时才读树）
        screenTextReader.attachRootSupplier { rootInActiveWindow }
        // M1.2: 注入手势兜底执行器（attachRootSupplier 同款 app→services 能力注入模式）
        shizukuBridge.attachGestureDispatcher { description -> dispatchGestureSuspend(description) }
        // M3.0: 注入工作站文件流面（同款模式——linux_upload/linux_download 的 SAF 推拉底座）
        termuxBridge.attachWorkDirStreams(object : com.zero.v2.services.termux.TermuxBridge.WorkDirFileStreams {
            override suspend fun openRead(fileName: String) = workDirRepository.openReadStream(fileName)
            override suspend fun writeStream(subdir: String, fileName: String, mime: String,
                                              maxBytes: Long, input: java.io.InputStream) =
                workDirRepository.writeBinaryStream(subdir, fileName, mime, maxBytes, input).let {
                    com.zero.v2.services.termux.TermuxBridge.WorkDirWriteResult(
                        it.error, it.finalName, it.bytesWritten)
                }
        })
    }

    /** M1.2：无障碍手势兜底执行器（dispatchGesture 异步回调 → 挂起布尔；调用方 ShizukuBridge 带超时）。 */
    private suspend fun dispatchGestureSuspend(description: GestureDescription): Boolean =
        suspendCoroutine { cont ->
            val dispatched = try {
                dispatchGesture(description, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) { cont.resume(true) }
                    override fun onCancelled(gestureDescription: GestureDescription?) { cont.resume(false) }
                }, null)
            } catch (e: Exception) {
                // E28：手势注入异常（服务未连手势能力等）留痕
                traceManager.record("agent_runtime", NodeType.ERROR, "accessibility",
                    "gesture_dispatch_failed: ${e.message?.take(60)}")
                false
            }
            if (!dispatched) cont.resume(false)  // 立即失败——回调不会再触发
        }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // 感知窗口切换 → 检测用户活跃 → 重置报点间隔（Alife: 打断→重置）
                agentRuntime.onUserActivity()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // v6.2: 内容变化节流转发（不做快照——快照由消费方拉取，见 ScreenTextReader KDoc）
                val now = System.currentTimeMillis()
                if (now - lastContentChangeForward > 3000) {
                    lastContentChangeForward = now
                    screenTextReader.onScreenContentChanged(event.packageName?.toString())
                }
            }
        }
    }

    override fun onInterrupt() { /* 系统打断 — 报点循环不受影响 */ }

    override fun onDestroy() {
        traceManager.record("agent_runtime", NodeType.SYSTEM_EVENT, "accessibility",
            "accessibility_destroyed: 屏幕与手势能力已断开，自主调度保持用户设置")
        screenTextReader.attachRootSupplier { null }
        shizukuBridge.attachGestureDispatcher(null)  // M1.2：手势兜底通道随服务断开
        termuxBridge.attachWorkDirStreams(null)      // M3.0：工作站文件面随服务断开
        super.onDestroy()
    }
}
