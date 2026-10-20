package com.zero.v2.app.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.content.ContextCompat
import com.zero.v2.app.MainActivity
import com.zero.v2.app.music.MusicModule
import com.zero.v2.app.music.PlaybackState
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 音乐前台宿主服务 — MU2.1 骨架 + MU2.2 playbackState→通知接线（MD2/MD17）。
 *
 * 薄前台宿主：状态与播放引擎在 MusicModule @Singleton；本服务只承担前台生命周期
 * （mediaPlayback FGS + 通知）。启动统一经 MusicModule.ensurePlaybackService()（MD17
 * 单入口——FC/UI/调度三入口共用，禁各自 startService）。
 *
 * 生命周期语义（§8.2b/M12）：START_NOT_STICKY + onTaskRemoved→stopSelf——进程死=播放
 * 终止，v1 不做跨进程死亡自动恢复；库/歌单/调度经 Room 恢复。
 */
@AndroidEntryPoint
class MusicService : android.app.Service() {

    @Inject lateinit var traceManager: AgentTraceManager
    @Inject lateinit var musicModule: MusicModule

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        const val CHANNEL_ID = "zero_music"
        const val NOTIFICATION_ID = 2002  // LiveSession=2001，错开

        /** 便捷启动（前台服务需前台发起——经 MusicModule.ensurePlaybackService() 调用） */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, MusicService::class.java))
        }

        /** 停止 = 直接 stopService（onDestroy 统一清理，仿 LiveSessionService 防后台竞态） */
        fun stop(context: Context) {
            context.stopService(Intent(context, MusicService::class.java))
        }
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = null  // Started service，非绑定

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // SR10：3-arg startForeground 按 SDK_INT 分支（仿 LiveSessionService 写法）
        val types = if (Build.VERSION.SDK_INT >= 30)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, buildNotification(), types)
        else startForeground(NOTIFICATION_ID, buildNotification())

        traceManager.record("music_service", NodeType.SYSTEM_EVENT, "music",
            "service_started: FGS=mediaPlayback")

        // MU2.2：播放状态 → 通知动态更新（仿 LiveSessionService state.collect 模式）
        serviceScope.launch {
            try {
                musicModule.playbackState.collect { updateNotification(it) }
            } catch (e: Exception) {
                traceManager.record("music_service", NodeType.ERROR, "music",
                    "state_collect_crash_prevented: ${e.message?.take(50)}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // §8.2b：被杀不自动重启（v1 语义——进程死=播放终止，不做死亡恢复）
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // §8.2b：用户划掉任务=停止播放（v1 语义，与 LiveSessionService 一致）
        traceManager.record("music_service", NodeType.SYSTEM_EVENT, "music",
            "task_removed → 停止播放（v1 语义）")
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        traceManager.record("music_service", NodeType.SYSTEM_EVENT, "music", "service_destroyed")
        super.onDestroy()
    }

    // ═══ 通知（MU2.2：playbackState 驱动动态更新）═══

    private fun buildNotification(): Notification = buildNotification(PlaybackState())

    private fun buildNotification(state: PlaybackState): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = state.title
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.zero.v2.app.R.drawable.ic_stat_zero)  // S8.2 品牌线形 glyph
            .setContentTitle("🎵 Zero 音乐")
            .setContentText(
                if (title != null) "$title${state.artist?.let { " · $it" } ?: ""} — " +
                        (if (state.isPlaying) "播放中" else "已暂停")
                else "音乐服务运行中")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    /** 状态变化 → 通知更新（collect 回调；状态未含曲目时保持运行中占位） */
    private fun updateNotification(state: PlaybackState) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Zero 音乐播放", NotificationManager.IMPORTANCE_LOW))
    }
}
