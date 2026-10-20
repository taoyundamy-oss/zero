package com.zero.v2.services.module

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.zero.v2.core.bus.SystemBus
import com.zero.v2.core.common.ActionPermission
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.NotificationEvent
import com.zero.v2.core.moduleapi.*
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通知管理模块 — Zero v2.2 → v2.5 → v7.0（Step 9.1 通知读取打通）。
 *
 * Android 独有能力：读取系统通知（环形缓冲）+ 推送本地通知。
 *
 * v2.5: push_notification 真实 NotificationManager 调用。
 * v7.0 (Step 9.1): read_notifications 从 stub 改为真实——
 *   NotificationListener → SystemBus.onNotificationPosted → 本模块环形缓冲（最近 50 条，2s 去重）
 *   → FC read_notifications 读取（支持 count / filter_app）。
 */
@Singleton
class NotificationModule @Inject constructor(
    @ApplicationContext private val context: Context,
    private val systemBus: SystemBus,

    private val traceManager: AgentTraceManager
) : IZeroModule, com.zero.v2.core.moduleapi.ToolContributor {

    companion object {
        private const val CHANNEL_ID = "zero_agent_notify"
        private const val CHANNEL_NAME = "Zero Agent 通知"
        private const val MAX_BUFFER = 50
        private const val DEDUP_WINDOW_MS = 2000L
    }

    override val metadata = ModuleMetadata(
        id = "notification",
        displayName = "通知管理",
        description = "读取最近收到的系统通知，或推送本地通知提醒用户。",
        category = ModuleCategory.SYSTEM,
        launchOrder = 110
    )

    private val _lifecycle = MutableStateFlow(ModuleLifecycle.UNINITIALIZED)
    override val lifecycle = _lifecycle.asStateFlow()
    override var isEnabled: Boolean = true
    override val requiredPermissions: List<String> = listOf(
        android.Manifest.permission.POST_NOTIFICATIONS
    )
    override val config = object : ModuleConfigBase() {
        override fun toJson() = "{}"
        override fun fromJson(json: String) {}
        override fun defaultConfig() = this
    }

    // Step 9.1: 通知环形缓冲（最新在前）
    private data class BufferedNotification(
        val event: NotificationEvent,
        val receivedAt: Long
    )
    private val notificationBuffer = ConcurrentLinkedDeque<BufferedNotification>()
    private val bufferScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override suspend fun onAwake() {

        startNotificationCollection()
        _lifecycle.value = ModuleLifecycle.AWAKE
    }
    override suspend fun onStart() { _lifecycle.value = ModuleLifecycle.STARTED }
    override suspend fun onDestroy() {
        bufferScope.cancel()
        _lifecycle.value = ModuleLifecycle.DESTROYED
    }
    override suspend fun onConfigChanged(newConfig: ModuleConfigBase) {}
    override suspend fun checkAvailability(): Boolean = true
    override fun markDisabled() { _lifecycle.value = ModuleLifecycle.DISABLED }

    /** Step 9.1: 收集 NotificationListener 发射的系统通知到环形缓冲 */
    private fun startNotificationCollection() {
        bufferScope.launch {
            try {
                systemBus.onNotificationPosted.collect { event ->
                    val now = System.currentTimeMillis()
                    // 去重：同 package+title+content 在 2s 内视为重复
                    val head = notificationBuffer.peekFirst()
                    if (head != null &&
                        head.event.packageName == event.packageName &&
                        head.event.title == event.title &&
                        head.event.content == event.content &&
                        now - head.receivedAt < DEDUP_WINDOW_MS) {
                        return@collect
                    }
                    notificationBuffer.addFirst(BufferedNotification(event, now))
                    while (notificationBuffer.size > MAX_BUFFER) notificationBuffer.removeLast()
                }
            } catch (e: Exception) {
                // 防御：SystemBus collect 异常不击穿进程（通知读取是辅助能力，不阻塞主链路）
                traceManager.record("notification", NodeType.ERROR, metadata.id,
                    "buffer_collect_crash_prevented: ${e.javaClass.simpleName} ${e.message?.take(40)}")
            }
        }
        traceManager.record("notification", NodeType.SYSTEM_EVENT, metadata.id,
            "buffer_started: SystemBus.onNotificationPosted → 环形缓冲($MAX_BUFFER)")
    }

    override fun contributions(): List<com.zero.v2.core.moduleapi.ToolContribution> =
        com.zero.v2.core.moduleapi.toolDeclarations(metadata.id) {
        val functionRegistry = this
        // Step 9.1: 真实通知读取（替换 Phase 9 stub）
        functionRegistry.register(
            FunctionDefinition("read_notifications",
                "读取最近收到的系统通知。了解用户手机上的消息动态（微信/QQ/邮件/日历提醒等）。",
                mapOf("type" to "object", "properties" to mapOf(
                    "count" to mapOf("type" to "number", "description" to "读取条数 1-20（默认 5）"),
                    "filter_app" to mapOf("type" to "string", "description" to "只读取指定应用的通知（包名或应用名模糊匹配）")
                ))
            ), metadata.id, permission = ActionPermission.AUTO
        ) { args ->
            val count = ((args["count"] as? Number)?.toInt() ?: 5).coerceIn(1, 20)
            val filterApp = args["filter_app"] as? String
            val all = notificationBuffer.toList()
            val filtered = if (filterApp.isNullOrBlank()) all
            else all.filter {
                it.event.packageName.contains(filterApp, ignoreCase = true) ||
                (it.event.appName?.contains(filterApp, ignoreCase = true) == true)
            }
            val result = filtered.take(count)
            if (result.isEmpty()) {
                val hint = if (all.isEmpty())
                    "📭 通知缓冲区为空——需在 设置→权限中心 授权「通知读取」后才能接收系统通知"
                else "📭 无匹配「$filterApp」的通知（缓冲区共 ${all.size} 条）"
                FunctionResult.success(hint)
            } else {
                val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                val sb = StringBuilder("📬 最近通知（${result.size}/${filtered.size} 条）：\n")
                result.forEachIndexed { i, bn ->
                    val time = fmt.format(Date(bn.receivedAt))
                    val app = bn.event.packageName.substringAfterLast('.').take(12)
                    val title = bn.event.title ?: "(无标题)"
                    val content = (bn.event.content ?: "").take(80)
                    sb.appendLine("[${i + 1}] $time $app: $title — $content")
                }
                traceManager.record("fc_read_notifications", NodeType.SYSTEM_EVENT, metadata.id,
                    "notifications_read: count=${result.size} bufferTotal=${all.size}")
                FunctionResult.success(sb.toString())
            }
        }

        functionRegistry.register(
            FunctionDefinition("push_notification", "推送一条系统通知到用户手机。用于提醒、告知重要结果。",
                mapOf("type" to "object", "properties" to mapOf(
                    "title" to mapOf("type" to "string", "description" to "通知标题"),
                    "content" to mapOf("type" to "string", "description" to "通知内容")
                ), "required" to listOf("title", "content"))
            ), metadata.id, permission = ActionPermission.CONFIRM
        ) { args ->
            val content = args["content"] as? String ?: ""
            // v3.4: title 空时回退内容首行（修复通知标题恒为 "Zero"）
            val title = (args["title"] as? String)?.takeIf { it.isNotBlank() }
                ?: content.lineSequence().firstOrNull()?.take(16)?.ifBlank { null }
                ?: "Zero"
            push(title, content)
        }
    }

    // ═══ v2.5: 真实通知推送 ═══

    /** API 33+ 检查 POST_NOTIFICATIONS 运行时权限；26-32 安装即有渠道级授权 */
    private fun hasNotifyPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun push(title: String, content: String): FunctionResult {
        if (!hasNotifyPermission()) {
            traceManager.record("fc_push_notification", NodeType.ERROR, metadata.id,
                "notification_blocked: POST_NOTIFICATIONS 未授予")
            return FunctionResult.failure(
                "通知权限未授予。请引导用户到 系统设置 → 应用 → ZeroV2 → 通知 开启后重试。")
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
        )

        // S8.2：品牌线形 glyph（applicationInfo.icon 现为 adaptive XML，作小图标渲染不良）
        val smallIcon = com.zero.v2.services.R.drawable.ic_stat_zero
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(Notification.BigTextStyle().bigText(content))
            .setAutoCancel(true)
            .build()

        val notifyId = System.currentTimeMillis().toInt()
        nm.notify(notifyId, notification)

        traceManager.record("fc_push_notification", NodeType.TOOL_CALL_RESULT, metadata.id,
            "notification_pushed: id=$notifyId title=${title.take(30)} len=${content.length}")
        return FunctionResult.success("通知已推送: $title")
    }
}
