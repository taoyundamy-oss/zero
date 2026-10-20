package com.zero.v2.app.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zero.v2.core.moduleapi.AlarmWorkQueue
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import com.zero.v2.services.agent.AlarmDueDispatcher
import kotlinx.coroutines.*
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 开机广播接收器 — 系统重启后恢复调度。
 *
 * BOOT_COMPLETED / MY_PACKAGE_REPLACED → guarded host + durable reminder recovery.
 * —— Room 重载全部 active 提醒 + G6 判定（一次性过期→missed 不补发；周期→下期重排）
 * + 精确闹钟重排。此前为空存根（重启后闹钟全丢=提醒"不稳定"成因之一）。
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var workQueue: AlarmWorkQueue
    @Inject lateinit var traces: AgentTraceManager
    @Inject lateinit var autonomousHost: AutonomousBootHost

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                try {
                    val started = autonomousHost.restore()
                    traces.record("boot_restore", NodeType.SYSTEM_EVENT, "alarm",
                        "host_restore_requested: source=${intent.action} started=$started")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    traces.record("boot_restore", NodeType.ERROR, "alarm", "boot_host_start_failed: ${error.javaClass.simpleName}")
                }
                workQueue.enqueue(AlarmDueDispatcher.ACTION_BOOT_RESTORE)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                traces.record("boot_restore", NodeType.ERROR, "alarm", "boot_enqueue_failed: ${error.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }
}
