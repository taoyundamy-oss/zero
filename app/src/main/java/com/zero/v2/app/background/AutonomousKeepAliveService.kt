package com.zero.v2.app.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.zero.v2.app.MainActivity
import com.zero.v2.app.ZeroApplication
import com.zero.v2.core.data.store.ModuleConfigDao
import com.zero.v2.core.modulemanager.ModuleLifecycleManager
import com.zero.v2.services.agent.AgentRuntime
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import org.json.JSONObject
import javax.inject.Inject

/** Foreground lifetime only: AgentRuntime remains the sole adaptive heartbeat emitter. */
@AndroidEntryPoint
class AutonomousKeepAliveService : Service() {
    @Inject lateinit var dao: ModuleConfigDao
    @Inject lateinit var lifecycle: ModuleLifecycleManager
    @Inject lateinit var runtime: AgentRuntime
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        const val CHANNEL_ID = "zero_autonomous"
        const val NOTIFICATION_ID = 2003
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, AutonomousKeepAliveService::class.java))
        }
        fun stop(context: Context) { context.stopService(Intent(context, AutonomousKeepAliveService::class.java)) }
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "自主运行", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 2003, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.zero.v2.app.R.mipmap.ic_launcher).setContentTitle("Zero 自主运行")
            .setContentText("遵循在场退避与预算；可在自主行为中关闭")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, notification)
        scope.launch {
            try {
                (application as ZeroApplication).awaitModuleInitialization()
                observeAutonomousMode(dao, lifecycle, runtime) { stopSelf() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                Log.e("ZeroV2", "Autonomous foreground host failed", e)
                lifecycle.safeCall(runtime, "foreground_runtime_failed") { runtime.stopLoop() }
                stopSelf()
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

/** Confirmed Room configuration drives the existing owner; no timer, network or second scheduler. */
internal suspend fun observeAutonomousMode(dao: ModuleConfigDao, lifecycle: ModuleLifecycleManager, runtime: AgentRuntime, onOff: () -> Unit) {
    combine(dao.observeAll(), lifecycle.runtimeStates) { records, states ->
        val json = records.firstOrNull { it.moduleId == "agent_behavior" }?.configJson
        (if (json == null) "SMART" else JSONObject(json).optString("mode", "SMART")) to
            (states[runtime.metadata.id]?.operational ?: runtime.isEnabled)
    }.distinctUntilChanged().collect { (mode, enabled) ->
        checkNotNull(lifecycle.safeCall(runtime, "foreground_runtime_mode") {
            if (mode == "OFF" || !enabled) runtime.stopLoop() else runtime.startLoop()
        })
        if (mode == "OFF" || !enabled) onOff()
    }
}
