package com.zero.v2.services.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import com.zero.v2.core.moduleapi.AlarmWorkQueue
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 精确闹钟调度器 — D40（双层调度：准点任务走系统闹钟，Doze 下也触发）。
 *
 * 进程内 delay/kick 在后台冻结时不唤醒（2026-08-22 三星实测）；
 * AlarmManager.setExactAndAllowWhileIdle 由系统闹钟回调，不受进程冻结影响。
 * 未授予 SCHEDULE_EXACT_ALARM 时降级 setWindow（尽力而为，权限中心可直达授权）。
 */
@Singleton
class ExactAlarmScheduler @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) {
    fun scheduleReminder(triggerAtMs: Long) = schedule(ACTION_REMINDER_DUE, REQUEST_REMINDER, triggerAtMs)
    fun scheduleAutomation(triggerAtMs: Long) = schedule(ACTION_AUTOMATION_DUE, REQUEST_AUTOMATION, triggerAtMs)

    /**
     * MU4.2a（音乐轨 M2/MD14）：单一 `REQUEST_MUSIC` 身份**合法**——闹钟=唤醒 tick 而非
     * per-schedule 身份：恒指最近到期，执行后必重排；多调度并存靠 MusicModule 到期扫描+
     * 状态机认领区分（对齐 REMINDER_DUE 同模式，设计 §7.1 已论证）。
     */
    fun scheduleMusic(triggerAtMs: Long) = schedule(ACTION_MUSIC_DUE, REQUEST_MUSIC, triggerAtMs)

    /** Remove only music's wakeup when there is no remaining active music schedule. */
    fun cancelMusic() {
        val pending = android.app.PendingIntent.getBroadcast(
            context, REQUEST_MUSIC,
            Intent(context, ExactAlarmReceiver::class.java).setAction(ACTION_MUSIC_DUE),
            android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE) ?: return
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        alarms.cancel(pending)
    }

    fun cancelAll() {
        val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        listOf(REQUEST_REMINDER to ACTION_REMINDER_DUE,
            REQUEST_AUTOMATION to ACTION_AUTOMATION_DUE,
            REQUEST_MUSIC to ACTION_MUSIC_DUE)
            .forEach { (code, action) ->
                am.cancel(android.app.PendingIntent.getBroadcast(
                    context, code,
                    Intent(context, ExactAlarmReceiver::class.java).setAction(action),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE))
            }
    }

    private fun schedule(action: String, requestCode: Int, triggerAtMs: Long) {
        runCatching {
            val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val pi = android.app.PendingIntent.getBroadcast(
                context, requestCode,
                Intent(context, ExactAlarmReceiver::class.java).setAction(action),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            val result = AlarmSchedulingPolicy.submit(
                canExact = { if (android.os.Build.VERSION.SDK_INT >= 31) am.canScheduleExactAlarms() else true },
                exact = { am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAtMs, pi) },
                window = { am.setWindow(android.app.AlarmManager.RTC_WAKEUP, triggerAtMs, 60_000L, pi) },
            )
            if (result.mode != AlarmSchedulingPolicy.Mode.EXACT) {
                android.util.Log.w("ZeroAlarm",
                    "alarm_schedule: action=$action mode=${result.mode} reason=${result.reason}")
            }
        }.onFailure { failure ->
            if (failure is CancellationException || failure !is RuntimeException) throw failure
            android.util.Log.w("ZeroAlarm",
                "alarm_schedule_failed: action=$action reason=${failure.javaClass.simpleName}")
        }
    }

    companion object {
        const val ACTION_REMINDER_DUE = "com.zero.v2.action.REMINDER_DUE"
        const val ACTION_AUTOMATION_DUE = "com.zero.v2.action.AUTOMATION_DUE"
        const val ACTION_MUSIC_DUE = "com.zero.v2.action.MUSIC_DUE"
        private const val REQUEST_REMINDER = 4001
        private const val REQUEST_AUTOMATION = 4002
        private const val REQUEST_MUSIC = 4003
    }
}

/**
 * Broadcast lifetime only covers a bounded durable handoff. Workers own business execution.
 */
@AndroidEntryPoint
class ExactAlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var workQueue: AlarmWorkQueue
    @Inject lateinit var traceManager: AgentTraceManager

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in DUE_ACTIONS) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                workQueue.enqueue(action)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                traceManager.record("exact_alarm", NodeType.ERROR, "alarm",
                    "alarm_enqueue_failed: ${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        val DUE_ACTIONS = setOf(ExactAlarmScheduler.ACTION_REMINDER_DUE,
            ExactAlarmScheduler.ACTION_AUTOMATION_DUE, ExactAlarmScheduler.ACTION_MUSIC_DUE)
    }
}
