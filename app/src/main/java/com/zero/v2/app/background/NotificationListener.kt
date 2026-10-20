package com.zero.v2.app.background

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.zero.v2.core.bus.SystemBus
import com.zero.v2.core.common.NotificationEvent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 系统通知监听服务 — Zero v2.2。
 *
 * 监控系统通知栏，将重要通知通过 SystemBus 广播。
 * 需要用户在 设置>通知访问权限 中手动授权。
 */
@AndroidEntryPoint
class NotificationListener : NotificationListenerService() {

    @Inject lateinit var systemBus: SystemBus

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        val extras = sbn.notification.extras
        systemBus.onNotificationPosted.tryEmit(NotificationEvent(
            packageName = sbn.packageName,
            appName = "",
            title = extras.getString("android.title"),
            content = extras.getString("android.text")?.toString(),
            timestamp = sbn.postTime
        ))
    }

    override fun onListenerConnected() { /* 服务已连接 */ }
}
