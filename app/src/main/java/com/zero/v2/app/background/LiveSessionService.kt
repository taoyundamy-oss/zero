package com.zero.v2.app.background

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.app.Notification
import android.content.ComponentCallbacks2
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.zero.v2.app.MainActivity
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import com.zero.v2.services.realtime.LocalVoiceProvider
import com.zero.v2.services.realtime.RealtimeState
import com.zero.v2.services.realtime.RealtimeVoiceEvent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * Live 会话前台服务 — Step 6.4（D38 会话制 / R1 双类型 FGS / R5 overlay 收归 / R9 被杀不复活）。
 *
 * v4.0 适配：注入 LocalVoiceProvider（D48 sherpa-onnx 全本地引擎）替代 VoiceCascadeModule。
 *
 * D38：服务仅由用户显式开启 Live 启动；结束（stopSelf/通知/被杀）= 全释放。
 * R9：onTaskRemoved/系统杀 → 不自动重启（START_NOT_STICKY），通知用户重开。
 */
@AndroidEntryPoint
class LiveSessionService : android.app.Service() {

    @Inject lateinit var voiceProvider: LocalVoiceProvider
    @Inject lateinit var screenCapture: com.zero.v2.services.realtime.ScreenCaptureModule
    @Inject lateinit var traceManager: AgentTraceManager
    /** MU2.3（C2 fallback）：语音会话状态广播（音乐订阅暂停/恢复） */
    @Inject lateinit var systemBus: com.zero.v2.core.bus.SystemBus

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    // Gemini 式悬浮球组件（v6.6a：状态色动画 + 菜单 + 气泡框；v6.6b：可拖动 + 实时滚动日志）
    private var ballView: View? = null
    private var bubbleText: android.widget.TextView? = null
    private var popupMenu: android.widget.PopupWindow? = null
    private var pulseAnimator: android.animation.ObjectAnimator? = null
    @Volatile private var bubbleVisible = false
    private val captionLines = mutableListOf<String>()  // 仅主线程访问（updateOverlayCaption/toggle 收敛 mainHandler）
    @Volatile private var serviceDestroyed = false
    private var startupRejected = false
    private var memCallbacks: ComponentCallbacks2? = null

    companion object {
        const val CHANNEL_ID = "zero_live_session"
        const val NOTIFICATION_ID = 2001
        const val ACTION_END = "com.zero.v2.action.END_LIVE"
        const val ACTION_MUTE = "com.zero.v2.action.MUTE_LIVE"
        // 6.6: ConsentActivity 授权结果转发（R1 时序在服务内执行）
        const val ACTION_ATTACH_PROJECTION = "com.zero.v2.action.ATTACH_PROJECTION"
        const val ACTION_STOP_PROJECTION = "com.zero.v2.action.STOP_PROJECTION"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        /** 便捷启动（前台服务需前台发起——LiveSessionScreen 调用） */
        fun start(context: Context) {
            if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                android.widget.Toast.makeText(context, "麦克风权限未授予，请在 Live 页面重新授权", android.widget.Toast.LENGTH_LONG).show()
                return
            }
            ContextCompat.startForegroundService(
                context, Intent(context, LiveSessionService::class.java))
        }
        /** 停止 = 直接 stopService（onDestroy 统一清理——避免后台 startService 限制与竞态） */
        fun stop(context: Context) {
            context.stopService(Intent(context, LiveSessionService::class.java))
        }
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = null  // Started service，非绑定

    override fun onCreate() {
        super.onCreate()
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            rejectStartup("麦克风权限未授予，请在 Live 页面重新授权")
            return
        }
        createChannel()
        // R1: 先以前台服务身份立足（初始仅麦克风类型；6.6 屏幕共享时升级双类型）
        val types = if (Build.VERSION.SDK_INT >= 30)
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, buildNotification(), types)
            else startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: SecurityException) {
            rejectStartup("麦克风授权或前台状态已变化，请重新打开 Live")
            return
        }
        systemBus.onVoiceActive.tryEmit(true)

        traceManager.record("live_session", NodeType.SYSTEM_EVENT, "live",
            "service_started: FGS=mic provider=${voiceProvider.id}")
        serviceScope.launch {
            try {
                voiceProvider.events().collect { event -> onProviderEvent(event) }
            } catch (e: Exception) {
                traceManager.record("live_session", NodeType.ERROR, "live",
                    "events_collect_crash_prevented: ${e.message?.take(50)}")
            }
        }
        serviceScope.launch {
            try {
                voiceProvider.state.collect { state ->
                    updateNotification(state)
                    applyOverlayState(state)  // v6.6a: 悬浮球状态色 + 脉动动画
                }
            } catch (e: Exception) {
                traceManager.record("live_session", NodeType.ERROR, "live",
                    "state_collect_crash_prevented: ${e.message?.take(50)}")
            }
        }
        serviceScope.launch {
            try {
                // v5: 先初始化三模块（后台提取模型），再启动语音会话
                voiceProvider.initializeModules()
                currentCoroutineContext().ensureActive()
                if (serviceDestroyed) return@launch
                voiceProvider.start()
                if (!voiceProvider.isActive()) mainHandler.post { stopSelf() }
            } catch (e: Exception) {
                traceManager.record("live_session", NodeType.ERROR, "live",
                    "provider_start_crash_prevented: ${e.javaClass.simpleName} ${e.message?.take(50)}")
                mainHandler.post { stopSelf() }
            }
        }
        showOverlay()
        registerMemoryCallbacks()
    }

    private fun rejectStartup(message: String) {
        startupRejected = true
        traceManager.record("live_session", NodeType.ERROR, "live", "service_start_rejected: $message")
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
        stopSelf()
    }

    /**
     * v5.5 内存机制：系统内存压力回调 → 主动释放 Kokoro ~114MB native（降级系统 TTS），
     * 会话保持存活而非被杀（对标常驻型应用的主动降级策略）。
     */
    private fun registerMemoryCallbacks() {
        memCallbacks = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                    traceManager.record("live_session", NodeType.SYSTEM_EVENT, "live",
                        "trim_memory: level=$level → 释放 Kokoro 降级系统 TTS（会话保持）")
                    voiceProvider.onMemoryPressure()
                }
            }
            override fun onLowMemory() {
                traceManager.record("live_session", NodeType.SYSTEM_EVENT, "live",
                    "low_memory → 释放 Kokoro 降级系统 TTS")
                voiceProvider.onMemoryPressure()
            }
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
        }
        applicationContext.registerComponentCallbacks(memCallbacks!!)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (startupRejected) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_END -> {
                traceManager.record("live_session", NodeType.SYSTEM_EVENT, "live",
                    "end_requested: 通知")
                stopSelf()
            }
            ACTION_MUTE -> {
                // 半双工下的"静音"= 打断当前播报（LISTENING 继续）
                voiceProvider.interrupt()
            }
            ACTION_ATTACH_PROJECTION -> attachProjection(intent)
            ACTION_STOP_PROJECTION -> {
                // v6.6d: 屏幕共享开关语义——再次点击 = 主动结束共享（释放 + 降级 FGS）
                traceManager.record("live_session", NodeType.SYSTEM_EVENT, "live",
                    "projection_stop_requested: 用户关闭屏幕共享")
                screenCapture.release()
                downgradeFromMediaProjection()
            }
        }
        // R9: 被杀不自动重启
        return START_NOT_STICKY
    }

    /**
     * 6.6 屏幕共享授权结果落地（R1 严格时序）：
     * 先升级双类型 FGS → 再 getMediaProjection()（API 34+ 顺序颠倒即 SecurityException）
     * → ScreenCaptureModule 持 token 建镜像表面。
     */
    private fun attachProjection(intent: Intent) {
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
        if (code != android.app.Activity.RESULT_OK || data == null) {
            traceManager.record("live_session", NodeType.ERROR, "live", "projection_result_invalid")
            return
        }
        try {
            upgradeToMediaProjection()  // R1: FGS 双类型先行；权限竞态同属失败清理范围
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            val projection = mpm.getMediaProjection(code, data)
            val real = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as android.view.WindowManager)
                .defaultDisplay.getRealMetrics(real)
            screenCapture.attachProjection(projection, real.widthPixels, real.heightPixels, real.densityDpi,
                onSystemStop = { downgradeFromMediaProjection() })
            // v6.6c: 投影建立即重建麦克风——机型（尤其三星）在投屏时重配置 voice 通路，
            // 原有 VOICE_COMMUNICATION 采集会被切断变数字静音（实测 ASR 全哑根因）
            voiceProvider.reopenMicrophone("projection_attached")
        } catch (t: Throwable) {
            traceManager.record("live_session", NodeType.ERROR, "live",
                "projection_attach_failed: ${t.javaClass.simpleName} ${t.message?.take(60)}")
            screenCapture.release()
            downgradeFromMediaProjection()
        }
    }

    /**
     * 6.6 补丁：投影结束（系统侧停止/新授权顶替）后必须立即降级回纯麦克风 FGS——
     * API 34+ 服务持有 mediaProjection 类型而无活跃投影，系统强制杀死服务
     * （实测：二次授权 → 旧投影停止 → 服务被杀 → 整个 Live 会话含 ASR 死亡）。
     */
    private fun downgradeFromMediaProjection() {
        if (serviceDestroyed) return  // v6.6c: 已销毁服务上 startForeground = 幽灵调用
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIFICATION_ID, buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            traceManager.record("live_session", NodeType.SYSTEM_EVENT, "live",
                "fgs_downgraded: 回纯麦克风类型（防 API 34+ 服务被杀）")
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户从最近任务划掉：遵循 R9——不复活，干净结束
        traceManager.record("live_session", NodeType.SYSTEM_EVENT, "live",
            "task_removed → 优雅结束（R9 不复活）")
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        serviceDestroyed = true
        serviceScope.cancel()
        // MU2.3（C2 fallback）：会话结束广播（音乐恢复）
        systemBus.onVoiceActive.tryEmit(false)
        // 先取消初始化和收集，再同步停 provider/投影/overlay。
        // 必须同步执行 stop()：launch 后紧随的 scope.cancel() 会把还没运行的协程直接取消
        // （实测根因：挂断后 voiceProvider.stop 从未执行 → AudioRecord 永不释放，麦克风指示灯常亮）。
        // stop() serializes native access and joins the audio loop; response jobs are cancelled
        // and session-guarded so an uncooperative client cannot block destruction on Main.
        // Clear screen capture before the provider publishes its final IDLE state.
        screenCapture.release()  // Every session requires fresh projection consent.
        runBlocking {
            try { voiceProvider.stop() } catch (_: Exception) {}
        }
        removeOverlay()
        memCallbacks?.let { applicationContext.unregisterComponentCallbacks(it) }
        memCallbacks = null
        serviceScope.cancel()
        traceManager.record("live_session", NodeType.SYSTEM_EVENT, "live", "service_destroyed")
        super.onDestroy()
    }

    // ═══ Provider 事件 ═══

    private fun onProviderEvent(event: RealtimeVoiceEvent) {
        when (event) {
            is RealtimeVoiceEvent.Error -> traceManager.record(
                "live_session", NodeType.ERROR, "live", "provider_error: ${event.message.take(60)}")
            // v6.6b: 转写逐字上屏（partial→final 同行覆盖）+ AI 播报追加，气泡自动滚底
            is RealtimeVoiceEvent.UserTranscript ->
                updateOverlayCaption(event.text, isUser = true)
            is RealtimeVoiceEvent.AssistantText -> if (event.isFinal)
                updateOverlayCaption(event.text, isUser = false)
            else -> {}
        }
    }

    /** 6.6 R1 时序接线点：升级 FGS 双类型后才能创建 MediaProjection */
    fun upgradeToMediaProjection() {
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIFICATION_ID, buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            traceManager.record("live_session", NodeType.SYSTEM_EVENT, "live",
                "fgs_upgraded: +mediaProjection（R1 时序就绪）")
        }
    }

    // ═══ 通知 ═══

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val endIntent = PendingIntent.getService(
            this, 1, Intent(this, LiveSessionService::class.java).setAction(ACTION_END),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val muteIntent = PendingIntent.getService(
            this, 2, Intent(this, LiveSessionService::class.java).setAction(ACTION_MUTE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(safeSmallIcon())
            .setContentTitle("🎙 Zero Live 进行中")
            .setContentText("语音会话活跃——点击回到 Live 界面")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "静音", muteIntent).build())
            .addAction(Notification.Action.Builder(null, "结束", endIntent).build())
            .build()
    }

    /** 通知 small icon：S8.2 品牌线形 glyph（纯白单色通道——adaptive 应用图标不可作小图标） */
    private fun safeSmallIcon(): Int = com.zero.v2.app.R.drawable.ic_stat_zero

    private fun updateNotification(state: RealtimeState) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val text = when (state) {
            RealtimeState.LISTENING -> "正在听你说…"
            RealtimeState.THINKING -> "思考中…"
            RealtimeState.SPEAKING -> "正在回答…"
            RealtimeState.INTERRUPTED -> "已打断"
            RealtimeState.OBSERVING_SCREEN -> "屏幕阅读中…"
            RealtimeState.IDLE -> "语音会话活跃"
        }
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(safeSmallIcon())
            .setContentTitle("🎙 Zero Live")
            .setContentText(text)
            .setOngoing(true)
            .build()
        nm.notify(NOTIFICATION_ID, n)
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Zero Live 会话", NotificationManager.IMPORTANCE_LOW))
    }

    // ═══ Overlay 悬浮球（Gemini 式：状态色动画 + 弹出功能菜单 + 文字气泡框）═══

    /** 状态色（服务进程取不到 Compose 动态色，用固定色板对齐 App 内三态大圆） */
    private fun stateColor(state: RealtimeState): Int = when (state) {
        RealtimeState.LISTENING -> 0xFF4F8CFF.toInt()      // 蓝 — 正在听
        RealtimeState.THINKING -> 0xFFB085F5.toInt()       // 紫 — 思考中
        RealtimeState.SPEAKING -> 0xFF22C08B.toInt()       // 绿 — 播报中
        RealtimeState.INTERRUPTED -> 0xFFFFB020.toInt()    // 橙 — 已打断
        RealtimeState.OBSERVING_SCREEN -> 0xFF20B2AA.toInt()
        RealtimeState.IDLE -> 0xFF9E9E9E.toInt()
    }

    private fun applyOverlayState(state: RealtimeState) {
        val ball = ballView ?: return
        mainHandler.post {
            (ball.background.mutate() as? android.graphics.drawable.GradientDrawable)
                ?.setColor(stateColor(state))
            if (state == RealtimeState.IDLE) {
                pulseAnimator?.cancel(); ball.scaleX = 1f; ball.scaleY = 1f
            } else if (pulseAnimator?.isStarted != true) {
                pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(ball,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.16f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.16f)).apply {
                    duration = 700; repeatMode = ObjectAnimator.REVERSE; repeatCount = ObjectAnimator.INFINITE
                    start()
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            traceManager.record("live_session", NodeType.SYSTEM_EVENT, "live",
                "overlay_skipped: SYSTEM_ALERT_WINDOW 未授权（通知兜底可用）")
            return
        }
        val density = resources.displayMetrics.density
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        // 悬浮球：状态色圆 + 脉动动画（语音识别/输出时动态变色）
        ballView = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(stateColor(voiceProvider.state.value))
            }
        }
        // 文字气泡框：多行对话日志（实时滚动——转写 partial 逐字刷新 + 自动滚底）。
        // v6.6d: maxLines 是硬截断（被截行不布局、永远看不见——"可滚动但显示不全"根因），
        // 改固定可视高度 180dp + 不限行数，所有日志行均布局、滚动可达真实底部
        bubbleText = android.widget.TextView(this).apply {
            visibility = View.GONE
            textSize = 14f
            setTextColor(android.graphics.Color.WHITE)
            maxWidth = (240 * density).toInt()
            minHeight = (48 * density).toInt()
            movementMethod = android.text.method.ScrollingMovementMethod()
            isVerticalScrollBarEnabled = true
            // v6.6e: 布局完成后自动滚底（新文本布局出的真实行数此时才可读——
            // 单靠 setText 后 post 计算会拿到旧行数，长回复滚不到底）
            viewTreeObserver.addOnGlobalLayoutListener {
                if (bubbleVisible && lineCount > 0) {
                    val pad = totalPaddingTop + totalPaddingBottom
                    scrollTo(0, maxOf(0, lineCount * lineHeight - (height - pad)))
                }
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(0xCC202020.toInt())
            }
            val hp = (12 * density).toInt(); val vp = (8 * density).toInt()
            setPadding(hp, vp, hp, vp)
        }
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(bubbleText, android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                (180 * density).toInt()).apply {  // 固定可视高度：内容多行内部滚动
                    marginEnd = (8 * density).toInt()
                })
            addView(ballView, android.widget.LinearLayout.LayoutParams(
                (56 * density).toInt(), (56 * density).toInt()).apply {
                marginStart = (10 * density).toInt()
            })
        }
        // D56 手势集：拖动=移位 / 单击=打断（320ms 延迟派发消歧双击）/ 双击=功能菜单 / 长按=挂断
        var downRawX = 0f; var downRawY = 0f
        var startParamX = 0; var startParamY = 0; var moved = false
        val dragSlop = (10 * density).toInt()
        var pendingSingleTap = false
        var lastTapAt = 0L
        root.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX; downRawY = e.rawY; moved = false
                    overlayParams?.let { startParamX = it.x; startParamY = it.y }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX; val dy = e.rawY - downRawY
                    if (!moved && (kotlin.math.abs(dx) > dragSlop || kotlin.math.abs(dy) > dragSlop))
                        moved = true
                    if (moved) {
                        overlayParams?.let { p ->
                            // END|CENTER_VERTICAL 重力：x 为距右缘偏移（右移→减小），y 为距中心偏移
                            p.x = (startParamX - dx).toInt().coerceAtLeast(0)
                            p.y = startParamY + dy.toInt()
                            runCatching { wm.updateViewLayout(root, p) }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved && e.eventTime - e.downTime > 600) {   // 长按 = 挂断
                        pendingSingleTap = false
                        traceManager.record("live_session", NodeType.SYSTEM_EVENT, "live",
                            "overlay_action: 长按挂断")
                        stopSelf()
                    } else if (!moved) {
                        // D56 单击/双击消歧：320ms 内二次点按 = 菜单（并取消已排队单击打断）
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (pendingSingleTap && now - lastTapAt <= 320) {
                            pendingSingleTap = false
                            traceManager.record("live_session", NodeType.SYSTEM_EVENT, "live",
                                "overlay_action: 双击菜单")
                            showOverlayMenu()
                        } else {
                            pendingSingleTap = true
                            lastTapAt = now
                            mainHandler.postDelayed({
                                if (pendingSingleTap) {
                                    pendingSingleTap = false
                                    traceManager.record("live_session", NodeType.SYSTEM_EVENT, "live",
                                        "overlay_action: 点按打断")
                                    voiceProvider.interrupt()
                                }
                            }, 320)
                        }
                    }
                    true
                }
                else -> false
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        runCatching {
            wm.addView(root, params)
            overlayView = root
            overlayParams = params
            applyOverlayState(voiceProvider.state.value)
            traceManager.record("live_session", NodeType.SYSTEM_EVENT, "live",
                "overlay_shown: 悬浮球（状态色动画 + 可拖动 + 点按打断/长按挂断）")
        }
    }

    /** D56 双击弹出功能菜单：挂断 / 屏幕共享 / 文字气泡框 */
    private fun showOverlayMenu() {
        val root = overlayView ?: return
        if (popupMenu != null) { popupMenu?.dismiss(); return }
        val density = resources.displayMetrics.density
        val ripple = android.util.TypedValue().also {
            theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }.resourceId

        fun menuRow(icon: String, label: String, action: () -> Unit): android.widget.TextView =
            android.widget.TextView(this).apply {
                text = "$icon  $label"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 15f
                setPadding((16 * density).toInt(), (12 * density).toInt(),
                    (16 * density).toInt(), (12 * density).toInt())
                setBackgroundResource(ripple)
                setOnClickListener {
                    traceManager.record("live_session", NodeType.SYSTEM_EVENT, "live",
                        "overlay_action: $label")
                    popupMenu?.dismiss()
                    action()
                }
            }

        val menu = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(0xEE252526.toInt())
            }
            addView(menuRow("🔴", "挂断") { stopSelf() })
            addView(menuRow("📡", if (screenCapture.isSharingActive()) "关闭屏幕共享" else "屏幕共享") {
                if (screenCapture.isSharingActive()) {
                    startService(Intent(this@LiveSessionService, LiveSessionService::class.java)
                        .setAction(ACTION_STOP_PROJECTION))
                } else {
                    startActivity(Intent(this@LiveSessionService,
                        ProjectionConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            })
            addView(menuRow("💬", if (bubbleVisible) "关闭气泡框" else "文字气泡框") {
                toggleCaptionBubble()
            })
        }
        popupMenu = android.widget.PopupWindow(menu, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT, false).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setOnDismissListener { popupMenu = null }
            showAtLocation(root, Gravity.END or Gravity.CENTER_VERTICAL,
                (76 * density).toInt(), 0)
        }
    }

    /** 文字气泡框开关：开启时显示对话日志 */
    private fun toggleCaptionBubble() {
        bubbleVisible = !bubbleVisible
        bubbleText?.let { tv ->
            mainHandler.post {
                tv.visibility = if (bubbleVisible) View.VISIBLE else View.GONE
                if (bubbleVisible) {
                    tv.text = if (captionLines.isEmpty()) "💬 等待语音…" else captionLines.joinToString("\n")
                    scrollBubbleToBottom(tv)
                }
            }
        }
    }

    /**
     * 气泡日志更新（实时滚动）：用户行逐字刷新（ASR partial 定稿覆盖同一行），
     * AI 行整行追加；保留最近 8 行，更新后自动滚到底部。全量收敛主线程（事件收集在 Default）。
     * v6.6f 上限（用户裁定：尽可能大，按安全极限设置）：
     *   - 单条 20,000 字：LLM 单回复物理上限 ≈ maxTokens(4096) ≈ 1.2 万字，留足余量；
     *     StaticLayout 布局成本线性（2 万字约几十 ms，每轮一次可接受）；内存 2B/字符可忽略
     *   - 日志总字数 60,000 封顶（超出裁最旧条目）：最坏情况内存/布局有界
     */
    private fun updateOverlayCaption(text: String, isUser: Boolean) {
        mainHandler.post {
            val line = (if (isUser) "🗣 " else "🤖 ") +
                    text.take(if (isUser) 2000 else 20_000)
            if (isUser && captionLines.isNotEmpty() && captionLines.last().startsWith("🗣")) {
                captionLines[captionLines.lastIndex] = line
            } else {
                captionLines.add(line)
            }
            if (captionLines.size > 8) captionLines.removeAt(0)
            // 总字数封顶：从最旧条目开始整条/掐尾裁剪
            var total = captionLines.sumOf { it.length }
            while (total > 60_000 && captionLines.size > 1) {
                val overflow = total - 60_000
                val oldest = captionLines.first()
                if (oldest.length <= overflow) {
                    total -= oldest.length + 1
                    captionLines.removeAt(0)
                } else {
                    captionLines[0] = oldest.dropLast(overflow)
                    total = 60_000
                }
            }
            if (!bubbleVisible) return@post
            bubbleText?.let { tv ->
                tv.text = captionLines.joinToString("\n")
                scrollBubbleToBottom(tv)
            }
        }
    }

    private fun scrollBubbleToBottom(tv: android.widget.TextView) {
        tv.post {
            val contentH = tv.lineCount * tv.lineHeight
            val pad = tv.totalPaddingTop + tv.totalPaddingBottom
            tv.scrollTo(0, maxOf(0, contentH - (tv.height - pad)))
        }
    }

    private fun removeOverlay() {
        mainHandler.post {
            popupMenu?.dismiss(); popupMenu = null
            pulseAnimator?.cancel(); pulseAnimator = null
        }
        overlayView?.let { v ->
            runCatching {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
            }
        }
        overlayView = null
        ballView = null
        bubbleText = null
    }

}
