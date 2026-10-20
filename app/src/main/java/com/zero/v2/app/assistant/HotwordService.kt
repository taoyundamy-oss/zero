package com.zero.v2.app.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.zero.v2.app.background.LiveSessionService
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** KB3.3 文件内共享 trace 常量（服务 + 接收器） */
private const val TRACE_ID = "hotword"
private const val MODULE = "assistant"

/**
 * 唤醒词常驻监听服务 — KB3.3（设计 §18.2 · 裁-B/裁-C · KD12/KD15）。
 *
 * 前台 microphone 服务（仿 LiveSessionService 3-arg startForeground）；亮屏/前台
 * 期间监听唤醒词「你好，大帝」（息屏不可达=平台特权墙，不设计不承诺）。
 *
 * **KD12（与 Live 并存=停采集保服务）**：onVoiceActive true → 停 AudioRecord/KWS
 * 保前台服务（通知切"会话中暂停监听"），false → 恢复——防 TTS 播报自唤醒（R7/AB-A）。
 *
 * **KD15（启动来源规则）**：前台交互启动为主路径；后台事件（POWER 广播）try 启动 +
 * catch `ForegroundServiceStartNotAllowedException` → trace 留痕等待合法窗口——
 * `start()` 返回成败由调用方（AssistantModule/PowerReceiver）留痕（E28）。
 *
 * **本步边界**：KWS 引擎构造随 KB3.4 与模型资产同落（`resolveKws()` 置 null +
 * 五件套就绪检查）——模型未就绪时服务可启停/通知/协同全链可验，仅不采集（省电）。
 */
@AndroidEntryPoint
class HotwordService : Service() {

    @Inject lateinit var traceManager: AgentTraceManager
    @Inject lateinit var systemBus: com.zero.v2.core.bus.SystemBus
    /** 注入配置宿主（AssistantConfig 由模块持有——服务按期望态自检，KB3.4 编排实装） */
    @Inject lateinit var assistantModule: AssistantModule

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** KD12：Live 会话期间暂停采集（服务保活） */
    @Volatile private var voicePaused = false
    @Volatile private var collecting = false
    /** fix10（Gemini 形态）：亮屏监听、息屏停麦——麦克风不 24h 常开 */
    @Volatile private var screenOn = true
    private var voiceJob: Job? = null
    private var collectJob: Job? = null
    private var screenReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        // 屏幕真值初始化（服务可能在息屏时被启动——查 PowerManager 而非假设亮屏）
        val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        screenOn = pm?.isInteractive ?: true
        createChannel()
        // KD12 协同（R7 自唤醒防治）：与音乐让位同款 onVoiceActive
        voiceJob = serviceScope.launch {
            systemBus.onVoiceActive.collect { active ->
                voicePaused = active
                if (active) stopCollecting(reason = "voice_session")
                else if (isHotwordDesired() && screenOn) startCollecting(reason = "voice_ended")
                updateNotification()
            }
        }
        // fix10：屏幕通断——亮屏监听/息屏彻底停麦（AudioRecord+KWS release，麦克风
        // 指示灯灭；对齐设计"亮屏有效"边界与 Gemini 亮屏监听形态；动态注册 receiver）
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        screenOn = false
                        stopCollecting(reason = "screen_off")
                        updateNotification()
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        screenOn = true
                        if (isHotwordDesired() && !voicePaused) startCollecting(reason = "screen_on")
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(screenReceiver, filter)
        }
        traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, MODULE, "service_created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // KB3.4-fix4（L3 模拟器闪退实证）：Android 14+ microphone 类型 FGS **启动瞬间**必须
        // 已持 RECORD_AUDIO（未授即 SecurityException 崩主进程）——权限检查必须在
        // startForeground 之前；及时 stopSelf 满足 FGS 时限（不触发 did-not-start 超时）
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, MODULE,
                "mic_not_granted_fg: 停止启动（未授录音权限——经 Live 会话授权后再开）")
            stopSelf()
            return START_NOT_STICKY
        }
        val types = if (Build.VERSION.SDK_INT >= 30)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, buildNotification(), types)
            else startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            // GA3/SR18 落位：eligible state 拒绝（后台/权限竞态等）→ trace 留痕 +
            // 停止服务等待合法窗口（前台交互/VIS 触发），不崩主进程
            traceManager.record(TRACE_ID, NodeType.ERROR, MODULE,
                "fgs_start_rejected: ${e.javaClass.simpleName} ${e.message?.take(60)}" +
                        "（KD15 等待合法窗口）")
            stopSelf()
            return START_NOT_STICKY
        }

        // 期望态自检（KB3.4 编排实装前的服务侧防线）
        if (!isHotwordDesired()) {
            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, MODULE,
                "self_stop: 期望态关闭（用户关开关/改档）")
            stopSelf()
            return START_NOT_STICKY
        }
        if ((assistantModule.config as AssistantConfig).residentMode == AssistantConfig.MODE_CHARGING_ONLY
            && !isCharging()) {
            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, MODULE, "self_stop: 仅充电档且未充电")
            stopSelf()
            return START_NOT_STICKY
        }
        if (screenOn) startCollecting(reason = "start")
        else traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, MODULE,
            "collect_wait_screen: 息屏启动——等亮屏自动开始监听（fix10）")
        return START_NOT_STICKY  // 进程死=监听终止（与 Live/Music 同 v1 语义，不做保活）
    }

    override fun onDestroy() {
        running = false
        stopCollecting(reason = "destroy")
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        voiceJob?.cancel()
        serviceScope.cancel()
        traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, MODULE, "service_destroyed")
        super.onDestroy()
    }

    // ═══ 采集（KWS 引擎随 KB3.4 与模型同落）═══

    /** 期望态：开关开 + 档位非关（档位细分由 PowerReceiver/编排方管，服务侧只看总开关） */
    private fun isHotwordDesired(): Boolean {
        val cfg = assistantModule.config as AssistantConfig
        return cfg.hotwordEnabled && cfg.residentMode != AssistantConfig.MODE_OFF
    }

    private fun isCharging(): Boolean {
        val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return false
        return bm.isCharging
    }

    private fun startCollecting(reason: String) {
        // KB3.4-fix5（L3 · E28 补留痕）：KD12 暂静默 return 无 trace——实测分诊盲区
        if (voicePaused) {
            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, MODULE,
                "collect_deferred_voice: $reason（KD12：Live 会话中暂停监听，结束自动恢复）")
            return
        }
        if (collecting) return
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, MODULE,
                "mic_not_granted: $reason——录音权限未授，采集未启动（Live 会话授权后再开）")
            updateNotification()
            return
        }
        val kws = buildKws()
        if (kws == null) {
            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, MODULE,
                "kws_model_missing: $reason——采集未启动（模型放置指引见 KB3.4 进度注记）")
            updateNotification()
            return
        }
        collecting = true
        collectJob = serviceScope.launch(Dispatchers.IO) {
            var rec: AudioRecord? = null
            var stream: OnlineStream? = null
            try {
                // fix8：构造+初始化校验经 createAudioRecord()（VOICE_RECOGNITION→MIC——
                // L3 实证：audio_stats 从未出现=read 恒空转、麦克风指示不亮=未获通道）
                rec = createAudioRecord()
                if (rec == null) {
                    traceManager.record(TRACE_ID, NodeType.ERROR, MODULE,
                        "audio_record_init_failed: 无法初始化 AudioRecord（$reason）——检查系统麦克风后台访问权限")
                    return@launch
                }
                // fix9（根因坐实）：漏调 startRecording()——构造成功但录音未启动时
                // read() 恒返 0（L3 read_stalled n=0 实证；LocalVoiceProvider:256 先例）
                rec.startRecording()
                if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    traceManager.record(TRACE_ID, NodeType.ERROR, MODULE,
                        "start_recording_failed: state=${rec.recordingState}（$reason）")
                    return@launch
                }
                stream = kws.createStream()
                val buf = ShortArray(SAMPLE_RATE / 10)  // 100ms 帧
                traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, MODULE, "collect_started: $reason")
                updateNotification()
                var lastHitAt = 0L
                // KB3.4-fix6：音频健康监测——Android 14 后台启动的 microphone FGS 被
                // while-in-use 静默（read 得静音帧不抛错）；连续 10s 近零能量 → trace
                // 留痕（分诊证据；自愈靠前台热重启，AssistantModule 编排侧）
                var silentFrames = 0
                var silentReported = false
                // fix7：音频健康快照（每 10s）——能量量级/帧数/decode 行为一屏可见，
                // 下轮日志直接分诊"音源增益低 / 阈值 / token 序列"三嫌疑
                var statFrames = 0L
                var statEnergySum = 0L
                var statPeak = 0
                var statDecodes = 0L
                var readFails = 0
                var recreated = false
                while (isActive && collecting && !voicePaused) {
                    val n = rec!!.read(buf, 0, buf.size)
                    if (n <= 0) {
                        // fix8：read 失败健康检查（原无限静默 continue——E28 盲区，
                        // L3 实证 audio_stats 从未出现即此路径）：连续 ~5s 无数据 →
                        // 留痕 + 一次重建自愈；重建仍失败放弃本轮（服务保活等下窗口）
                        if (++readFails >= 50) {
                            traceManager.record(TRACE_ID, NodeType.ERROR, MODULE,
                                "read_stalled: 连续 $readFails 次无数据（n=$n）——未获音频通道")
                            if (!recreated) {
                                recreated = true
                                runCatching { rec!!.release() }
                                rec = createAudioRecord() ?: break
                                rec.startRecording()  // fix9：重建路径同样启动
                                readFails = 0
                            } else break
                        }
                        continue
                    }
                    readFails = 0
                    var energy = 0L
                    var peak = 0
                    for (i in 0 until n) {
                        val a = kotlin.math.abs(buf[i].toInt())
                        energy += a
                        if (a > peak) peak = a
                    }
                    statFrames += n
                    statEnergySum += energy
                    if (peak > statPeak) statPeak = peak
                    if (energy < n * 3L) {  // 平均振幅 <3（数字静音）
                        if (++silentFrames >= SILENT_FRAMES_10S && !silentReported) {
                            silentReported = true
                            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, MODULE,
                                "mic_silent_10s: 连续 ${silentFrames} 帧近零能量——疑似 Android 14 " +
                                        "后台启动 while-in-use 静默（SR18 第三形态）；回前台热重启自愈")
                        }
                    } else silentFrames = 0
                    val floats = FloatArray(n) { buf[it] / 32768f }
                    stream.acceptWaveform(floats, SAMPLE_RATE)
                    while (kws.isReady(stream)) {
                        kws.decode(stream)
                        statDecodes++
                        val keyword = kws.getResult(stream).keyword
                        if (keyword.isNotBlank()) {
                            val now = System.currentTimeMillis()
                            kws.reset(stream)  // 命中即重置，防同窗重复触发
                            if (now - lastHitAt < HIT_COOLDOWN_MS) break  // 冷却窗（R7 残响）
                            lastHitAt = now
                            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, MODULE,
                                "wake_word_hit: \"$keyword\" → 拉起 Live 语音会话")
                            runCatching { LiveSessionService.start(this@HotwordService) }
                        }
                    }
                    if (statFrames >= SAMPLE_RATE * 10L) {  // 每 10s 音频快照
                        traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, MODULE,
                            "audio_stats: frames=${statFrames / SAMPLE_RATE}s avgAmp=" +
                                    "${if (statFrames > 0) statEnergySum / statFrames else 0} " +
                                    "peak=$statPeak decodes=$statDecodes thr=$KEYWORDS_THRESHOLD")
                        statFrames = 0; statEnergySum = 0; statPeak = 0; statDecodes = 0
                    }
                }
            } catch (e: Exception) {
                traceManager.record(TRACE_ID, NodeType.ERROR, MODULE,
                    "collect_failed: ${e.javaClass.simpleName} ${e.message?.take(50)}")
            } finally {
                runCatching { stream?.release() }
                runCatching { rec?.release() }
                runCatching { kws.release() }
                collecting = false
                if (!voicePaused) updateNotification()
            }
        }
    }

    /**
     * fix8：AudioRecord 工厂——**MIC 源 + STATE_INITIALIZED 校验**。
     * 原实现用 VOICE_RECOGNITION 源且不校验初始化态：L3 实证（麦克风指示不亮 +
     * audio_stats 从未出现）该源在本机后台 FGS 场景拿不到音频通道、read 恒空转。
     */
    private fun createAudioRecord(): AudioRecord? {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, MODULE,
                "audio_record_permission_missing: RECORD_AUDIO 未授权")
            return null
        }
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return null
        val record = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, 12800))
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            traceManager.record(TRACE_ID, NodeType.ERROR, MODULE,
                "audio_record_init_failed: source=MIC state=${record.state}")
            runCatching { record.release() }
            return null
        }
        return record
    }

    private fun stopCollecting(reason: String) {
        if (!collecting) return
        collectJob?.cancel()
        collecting = false
        traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, MODULE, "collect_stopped: $reason")
        updateNotification()
    }

    /**
     * KWS 引擎构造（五件套就绪时）。AB-C 定案（KB3.4）：**双通道**——① filesDir/models/kws/
     * 直推（L3 免重打包：adb push 模型包内容即生效）；② assets/models/kws/ 打包内置
     * （对齐 ModelAssetManager 的 ASR/TTS/VAD 资产惯例，首启自拷贝）。keywords.txt
     * 交由模型包提供（含默认词「你好大帝」拼音行——格式随模型包，硬编码序列易错不开）。
     */
    private fun buildKws(): KeywordSpotter? {
        val dir = resolveKws() ?: return null
        return try {
            val transducer = OnlineTransducerModelConfig.builder()
                .setEncoder(File(dir, "encoder.onnx").absolutePath)
                .setDecoder(File(dir, "decoder.onnx").absolutePath)
                .setJoiner(File(dir, "joiner.onnx").absolutePath)
                .build()
            val model = OnlineModelConfig.builder()
                .setTransducer(transducer)
                .setTokens(File(dir, "tokens.txt").absolutePath)
                .setNumThreads(2).build()
            val config = KeywordSpotterConfig.builder()
                .setFeatureConfig(FeatureConfig.builder()
                    .setSampleRate(SAMPLE_RATE).setFeatureDim(80).build())
                .setOnlineModelConfig(model)
                .setKeywordsFile(File(dir, "keywords.txt").absolutePath)
                .setKeywordsThreshold(KEYWORDS_THRESHOLD)   // 误触/漏触调参入口（KB3.4/G4 同思路）
                .setKeywordsScore(1.0f)
                .setNumTrailingBlanks(1)
                .build()
            KeywordSpotter(config)
        } catch (e: Exception) {
            traceManager.record(TRACE_ID, NodeType.ERROR, MODULE,
                "kws_init_failed: ${e.javaClass.simpleName} ${e.message?.take(50)}")
            null
        }
    }

    /** 五件套就绪检查：先查 filesDir（直推通道），无则从 assets 自拷贝（打包通道）再查 */
    private fun resolveKws(): File? {
        val dir = File(filesDir, KWS_DIR)
        if (isKwsComplete(dir)) return dir
        // assets 通道（模型包内置时；无该 assets 目录=空清单，保持未就绪态）
        runCatching {
            val src = assets.list("models/kws").orEmpty()
            if (src.isNotEmpty() && !isKwsComplete(dir)) {
                dir.mkdirs()
                src.filter { !File(dir, it).isFile }.forEach { name ->
                    assets.open("models/kws/$name").use { input ->
                        File(dir, name).outputStream().use { input.copyTo(it) }
                    }
                }
            }
        }
        return if (isKwsComplete(dir)) dir else null
    }

    private fun isKwsComplete(dir: File): Boolean {
        val required = listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt", "keywords.txt")
        return dir.isDirectory && required.all { File(dir, it).isFile }
    }

    // ═══ 前台通知（KD12 状态透明：监听中/会话中暂停/模型未就绪三态文案）═══

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "语音唤醒监听",
                NotificationManager.IMPORTANCE_LOW)
            channel.description = "唤醒词「${(assistantModule.config as AssistantConfig).wakeWord}」常驻监听（亮屏有效）"
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val text = when {
            voicePaused -> "语音会话中——监听已暂停（会话结束自动恢复）"
            !screenOn -> "息屏暂停监听（麦克风已关）——亮屏自动恢复"
            resolveKws() == null -> "监听待命——唤醒词模型未就绪（随下版自动生效）"
            else -> "正在监听唤醒词（亮屏期间；息屏自动停麦）"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle("Zero 语音唤醒")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0,
                packageManager.getLaunchIntentForPackage(packageName),
                PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    companion object {
        const val CHANNEL_ID = "zero_hotword"
        const val NOTIFICATION_ID = 2003
        const val KWS_DIR = "models/kws"
        /** KWS 采样率（sherpa zipformer 中文模型标配） */
        const val SAMPLE_RATE = 16000
        /** 命中阈值（fix7：0.25→0.12 灵敏化——fix6 静默假设被 L3 证伪（无 mic_silent_10s，
         *  音频有能量），转攻检测层；误触由 3s 冷却+KD12 承接） */
        const val KEYWORDS_THRESHOLD = 0.12f
        /** 命中冷却窗（防同窗重复触发/残响，R7 辅助） */
        const val HIT_COOLDOWN_MS = 3_000L
        /** 静默监测窗：100ms/帧 × 100 = 10s */
        const val SILENT_FRAMES_10S = 100
        /** 服务运行态（编排侧热重启判定用） */
        @Volatile var running = false
            private set

        /**
         * try 启动（KD15：调用方留痕成败——后台 POWER 广播触发可能被
         * ForegroundServiceStartNotAllowedException 拒绝，属预期，等待合法窗口）。
         * fix4：**前置权限检查**——RECORD_AUDIO 未授直接 false 不发 start
         * （Android 14+ microphone FGS 启动瞬间须已持权，模拟器未授权即崩的根因）
         */
        fun start(context: Context): Boolean {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) return false
            return try {
                ContextCompat.startForegroundService(
                    context, Intent(context, HotwordService::class.java))
                true
            } catch (e: Exception) {
                false  // E28：由调用方（AssistantModule/PowerReceiver）record 留痕
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HotwordService::class.java))
        }
    }
}

/**
 * 充电事件接收器（manifest 注册——拔插电均可收，"仅充电"档的拉起通道）。
 * **KD15/GA3**：POWER_CONNECTED 不在 FGS 后台启动豁免清单——try 启动失败即 trace
 * 留痕等待合法窗口（用户打开 app / assistant 触发），不做保活重试。
 */
@AndroidEntryPoint
class HotwordPowerReceiver : BroadcastReceiver() {

    @Inject lateinit var traceManager: AgentTraceManager
    @Inject lateinit var assistantModule: AssistantModule

    override fun onReceive(context: Context, intent: Intent) {
        val cfg = assistantModule.config as AssistantConfig
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> {
                if (cfg.hotwordEnabled &&
                    cfg.residentMode == AssistantConfig.MODE_CHARGING_ONLY
                ) {
                    val ok = HotwordService.start(context)
                    traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, MODULE,
                        "power_connected_try_start: ok=$ok${if (!ok) "（后台限制，等待合法窗口——KD15）" else ""}")
                }
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                if (cfg.residentMode == AssistantConfig.MODE_CHARGING_ONLY) {
                    // 服务侧 onStartCommand/自检也会兜底；这里直接停减少空窗
                    HotwordService.stop(context)
                    traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, MODULE,
                        "power_disconnected_stop: 仅充电档拔电停止监听")
                }
            }
        }
    }
}
