package com.zero.v2.services.realtime

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地语音提供器 — v5.0 纯编排层。
 *
 * 不持有任何模型/引擎实例。ASR/TTS/VAD 各自由独立模块管理，
 * 本类只负责 AudioRecord 生命周期 + 音频分发 + 状态流转。
 *
 * 权限检查：start() 前置检查 RECORD_AUDIO。
 */
@Singleton
class LocalVoiceProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val asrModule: SpeechRecognitionModule,
    private val ttsModule: SpeechSynthesisModule,
    private val vadModule: VoiceActivityModule,
    private val chatEngine: ChatEngineBridge,
    private val traceManager: AgentTraceManager
) : RealtimeVoiceProvider {

    override val id: String = "local"
    override val displayName: String = "本地语音引擎（v5 模块化）"

    companion object {
        const val SAMPLE_RATE = 16000
        private const val BUFFER_MS = 100
        private val CHUNK_SIZE = SAMPLE_RATE * BUFFER_MS / 1000
        // v6.6c 麦克风静音看门狗：连续 N 帧纯数字零 = 采集被系统切断（机型在投屏/
        // 通话态重配置 voice 通路时发生），自动重建 AudioRecord 重协商
        private const val SILENT_REOPEN_FRAMES = 30  // 3s
        // v6.6d 音源回退：VOICE_COMMUNICATION 连续 M 次重建仍数字静音（本机投屏期
        // voice 通路被永久切断——实测重建循环无恢复）→ 本会话降级 MIC 音源（失 AEC，
        // 由 Barge-in 阈值 + 尾音守卫两道 D52 防线兜底）
        private const val SOURCE_FALLBACK_AFTER_SILENT_REOPENS = 2
    }

    // v5.5 崩溃防护：任何未被业务 catch 的协程异常在此降级（默认行为是杀进程——长会话闪退最后防线）
    private val crashShield = CoroutineExceptionHandler { _, t ->
        traceManager.record("voice_v5", NodeType.ERROR, "voice",
            "coroutine_crash_shield: ${t.javaClass.simpleName} ${t.message?.take(60)}")
        if (active && _state.value != RealtimeState.LISTENING) {
            _state.value = RealtimeState.LISTENING
            _events.tryEmit(RealtimeVoiceEvent.StateChanged(RealtimeState.LISTENING))
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + crashShield)
    private val lifecycleMutex = Mutex()
    private val microphoneLock = Any()
    @Volatile private var sessionJob: Job? = null
    private var listeningJob: Job? = null
    private var sessionScope = scope
    private val _events = MutableSharedFlow<RealtimeVoiceEvent>(extraBufferCapacity = 64)
    override fun events(): SharedFlow<RealtimeVoiceEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow(RealtimeState.IDLE)
    val state: StateFlow<RealtimeState> = _state

    @Volatile private var active = false
    @Volatile private var audioRecord: AudioRecord? = null
    // v6.13: 正在播报的回复文本（归一化）——ASR partial 与其子集/高重叠 = TTS 回声非真人
    @Volatile private var speakingTextNorm: String = ""
    // D53 (Step 6.11): MIC 原始流 + 软件 far-end 参考 AEC（OEM VOICE_COMMUNICATION 黑盒链废弃，
    // v6.6d 音源回退机制随之失效/不再需要）
    @Volatile private var useMicSource = true
    /** D53: 软件 AEC——TTS far-end 参考 + 麦克风近端 → 干净信号供 VAD/ASR（D54 后旁路备用） */
    private val aec = SoftwareAec()
    private var silentReopenCount = 0
    // D54/D55 双模式：耳机 = 物理无回声（全双工自动打断）；扬声器 = 手动打断（悬浮球/按钮）
    private var hpCache = false
    private var hpCheckedAt = 0L

    /** 输出设备为耳机/蓝牙（2s 缓存检测）——此时扬声器不出声，物理无回声 */
    private fun headphoneMode(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - hpCheckedAt > 2000) {
            hpCheckedAt = now
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            hpCache = am?.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)?.any {
                it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
            } ?: false
        }
        return hpCache
    }

    /** 初始化三模块（Service.onCreate 时调用，模型提取在后台） */
    suspend fun initializeModules() {
        traceManager.record("voice_v5", NodeType.SYSTEM_EVENT, "voice",
            "init_start: 三模块异步初始化")
        try { asrModule.initialize() } catch (t: Throwable) {
            traceManager.record("voice_v5", NodeType.ERROR, "voice", "asr_init: ${t.message?.take(40)}") }
        try { ttsModule.initialize() } catch (t: Throwable) {
            traceManager.record("voice_v5", NodeType.ERROR, "voice", "tts_init: ${t.message?.take(40)}") }
        // v5.7：Melo 后台加载（成功后 melo 引擎可用，失败降级系统/在线）
        ttsModule.tryLoadMeloInBackground()
        // D53 (Step 6.11): TTS 每块 PCM 同步喂 AEC 作 far-end 参考
        ttsModule.farEndListener = { aec.feedFar(it) }
        // D55: 探针废止（扬声器模式手动打断——悬浮球点按即断；TTS 播报恢复连续无人工停顿）
        try { vadModule.initialize() } catch (t: Throwable) {
            traceManager.record("voice_v5", NodeType.ERROR, "voice", "vad_init: ${t.message?.take(40)}") }

        // S4.1（D121/P1-6）：线上 ASR 段落回流——partial 仅显示（ONLINE_PARTIAL），
        // 终态（ONLINE_FINAL/LOCAL_FALLBACK）才进回合（COMMITTED）；非 LISTENING 到达的
        // 过期终段丢弃留痕（上传期间用户已开启新回合的极窄窗口）
        asrModule.onlineListener = SpeechRecognitionModule.OnlineSegmentListener { text, partial, fromOnline ->
            if (!active) return@OnlineSegmentListener
            if (partial) {
                if (_state.value == RealtimeState.LISTENING)
                    _events.tryEmit(RealtimeVoiceEvent.UserTranscript(text, false))
            } else if (_state.value == RealtimeState.LISTENING) {
                traceManager.record("voice_v5", NodeType.SYSTEM_EVENT, "voice",
                    "user_final: ${if (fromOnline) "online" else "local_fallback"} ${text.take(40)}")
                _events.tryEmit(RealtimeVoiceEvent.UserTranscript(text, true))
                processTurn(text)
            } else {
                traceManager.record("voice_v5", NodeType.SYSTEM_EVENT, "voice",
                    "online_final_dropped: state=${_state.value} ${text.take(20)}")
            }
        }

        traceManager.record("voice_v5", NodeType.SYSTEM_EVENT, "voice",
            "engines_ready: asr=${asrModule.ready.value} tts=${ttsModule.ready.value} vad=${vadModule.ready.value}")
    }

    // ═══ RealtimeVoiceProvider 实现 ═══

    override suspend fun start() = lifecycleMutex.withLock {
        if (active) return@withLock

        // 权限前置检查
        if (!hasMicPermission()) {
            _events.tryEmit(RealtimeVoiceEvent.Error("麦克风权限未授予", recoverable = false))
            return@withLock
        }

        // ASR 模块就绪检查
        if (!asrModule.ready.value) {
            _events.tryEmit(RealtimeVoiceEvent.Error("ASR 模型未就绪（正在准备中）", recoverable = true))
            return@withLock
        }

        sessionJob = SupervisorJob(scope.coroutineContext[Job])
        sessionScope = CoroutineScope(scope.coroutineContext + requireNotNull(sessionJob))
        active = true
        _state.value = RealtimeState.LISTENING
        _events.tryEmit(RealtimeVoiceEvent.StateChanged(RealtimeState.LISTENING))
        traceManager.record("voice_v5", NodeType.SYSTEM_EVENT, "voice", "session_started: v5")
        startListeningLoop()
    }

    override suspend fun stop() = lifecycleMutex.withLock {
        if (!active && sessionJob == null) return@withLock
        active = false
        sessionJob?.cancel()
        synchronized(microphoneLock) { releaseMicrophone() }
        ttsModule.stop()
        // A native/client response may ignore cancellation. Only the audio owner must finish
        // before reset/restart; cancelled response jobs are isolated by their captured session.
        listeningJob?.join()
        listeningJob = null
        sessionJob = null
        _state.value = RealtimeState.IDLE
        _events.tryEmit(RealtimeVoiceEvent.StateChanged(RealtimeState.IDLE))
        vadModule.reset()
        asrModule.resetStream()
        aec.reset()  // D53: 会话结束复位滤波器（下次重新收敛）
        traceManager.record("voice_v5", NodeType.SYSTEM_EVENT, "voice", "session_stopped")
    }

    override fun isActive(): Boolean = active

    /** 内存压力（onTrimMemory 由 Service 转发）→ 释放本地 Melo 引擎，会话保持存活 */
    fun onMemoryPressure() {
        traceManager.record("voice_v5", NodeType.SYSTEM_EVENT, "voice", "memory_pressure: 释放本地 TTS 引擎")
        ttsModule.releaseLocalEngine("memory_pressure")
    }

    override fun interrupt() {
        ttsModule.stop()
        vadModule.reset()
        asrModule.resetStream()
        // 打断的是"播报"不是"会话"——立即回 LISTENING（否则主循环 ASR 只认 LISTENING，会话永久卡死）
        if (active) {
            _state.value = RealtimeState.INTERRUPTED
            _events.tryEmit(RealtimeVoiceEvent.StateChanged(RealtimeState.INTERRUPTED))
            sessionScope.launch {
                delay(150)  // 短暂展示"已打断"，随后恢复收音
                if (active && _state.value == RealtimeState.INTERRUPTED) {
                    asrModule.resetStream()  // v6.15: 清打断瞬间的回声尾 partial
                    _state.value = RealtimeState.LISTENING
                    _events.tryEmit(RealtimeVoiceEvent.StateChanged(RealtimeState.LISTENING))
                }
            }
        }
    }

    // ═══ 主循环（纯编排，零模型逻辑）═══

    /** D52 防自听核心：VOICE_COMMUNICATION 启用平台 AEC——麦输入自动扣除扬声器输出。
     *  v6.6d: 投屏期 voice 通路被机型切断时（重建仍静音）本会话降级 MIC 音源 */
    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord? {
        val source = if (useMicSource) MediaRecorder.AudioSource.MIC
                     else MediaRecorder.AudioSource.VOICE_COMMUNICATION
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        return try {
            AudioRecord(source,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT,
                maxOf(minBuf, CHUNK_SIZE * 4))
        } catch (t: Throwable) {
            traceManager.record("voice_v5", NodeType.ERROR, "voice",
                "mic_create_failed: ${t.message?.take(40)}")
            null
        }
    }

    /**
     * 主动重建麦克风（外部触发：投影建立/音频路由变化）。置空字段，主循环下一轮自动重建。
     * 场景：机型在投屏/通话态重配置 voice 通路 → 原有 VOICE_COMMUNICATION 采集变数字静音。
     */
    fun reopenMicrophone(reason: String) {
        traceManager.record("voice_v5", NodeType.SYSTEM_EVENT, "voice", "mic_reopen: $reason")
        synchronized(microphoneLock) { releaseMicrophone() }
    }

    /** Called under microphoneLock; detach before release so failures cannot leave a stale owner. */
    private fun releaseMicrophone() {
        val record = audioRecord
        audioRecord = null
        try { record?.stop() } catch (_: IllegalStateException) { /* Already stopped. */ }
        finally { record?.release() }
    }

    private fun startListeningLoop() {
        listeningJob = sessionScope.launch {
            val buf = FloatArray(CHUNK_SIZE)
            var filled = 0
            var silentFrames = 0   // 纯数字零连续帧计数（采集被切断的标志）

            while (active && isActive) {
                // v5.5: 逐帧容错——native 层偶发异常（内存压力 JNI 抛错 / 挂断时主线程
                // release 与阻塞 read 并发抛 ISE）跳过该帧继续，不让单帧异常击穿协程
                try {
                    // v6.6c: 麦克风自适应重建——字段被置空（reopenMicrophone/异常释放）即重建
                    // Nonblocking read permits a short lock without delaying stop on the main thread.
                    val n = synchronized(microphoneLock) {
                        if (!active || !isActive) return@synchronized 0
                        if (audioRecord == null) {
                            audioRecord = createAudioRecord()
                            audioRecord?.startRecording()
                            silentFrames = 0
                            filled = 0
                            traceManager.record("voice_v5", NodeType.SYSTEM_EVENT, "voice", "mic_recreated")
                        }
                        audioRecord?.read(buf, filled, CHUNK_SIZE - filled, AudioRecord.READ_NON_BLOCKING) ?: 0
                    }
                    if (n <= 0) { delay(10); continue }
                    filled += n
                    if (filled < CHUNK_SIZE) continue
                    val chunk = buf.copyOf()
                    filled = 0 // Preserve the existing 100ms VAD/ASR framing with nonblocking native reads.

                    // v6.6c 静音看门狗：连续 N 帧全零 = 系统切断采集（非声学静默——环境底噪
                    // 恒非零）→ 重建 AudioRecord 重协商音频通路；
                    // v6.6d 连续 M 次重建仍静音（实测本机投屏期 voice 通路永久切断）→ 降级 MIC 音源
                    val isDigitallySilent = chunk.all { it == 0f }
                    if (isDigitallySilent) silentFrames++ else { silentFrames = 0; silentReopenCount = 0 }
                    if (isDigitallySilent && silentFrames >= SILENT_REOPEN_FRAMES) {
                        if (!useMicSource && ++silentReopenCount >= SOURCE_FALLBACK_AFTER_SILENT_REOPENS) {
                            useMicSource = true
                            traceManager.record("voice_v5", NodeType.ERROR, "voice",
                                "mic_source_fallback: VOICE_COMMUNICATION 重建 ${silentReopenCount} 次仍静音 → 本会话降级 MIC 音源（AEC 失效，由 Barge-in 阈值+尾音守卫兜底）")
                        }
                        traceManager.record("voice_v5", NodeType.ERROR, "voice",
                            "mic_digitally_silent: ${silentFrames} 帧 → 重建（疑似投屏/路由切断采集）")
                        reopenMicrophone("silence_watchdog")
                        continue
                    }

                    // D54: AEC 旁路——打断不再依赖软件回声消除（业界结论：无硬件参考的
                    // 纯软件全双工不可靠，v6.11-v6.15 五轮实证），改回声回避策略
                    val hp = headphoneMode()
                    val clean = chunk

                    // ② Barge-in（D55）：仅耳机模式自动打断（物理无回声，VAD 连续 3 帧秒断）；
                    //   扬声器模式手动打断（悬浮球点按/界面按钮/通知静音）——回声问题无从触发
                    val speech = vadModule.processFrame(clean)
                    if (_state.value == RealtimeState.SPEAKING && speech && hp) {
                        traceManager.record("voice_v5", NodeType.SYSTEM_EVENT, "voice",
                            "barge_in_triggered: headphone")
                        interrupt()
                        continue
                    }

                    // ③ ASR —— S4.1 双引擎分支：
                    //    online：仅 LISTENING 段落采集（VAD 断句整段上传，SPEAKING 期回声
                    //    不外发、打断靠 VAD；partial 按语句分段流式显示 SR4）
                    //    local：LISTENING 恒跑；SPEAKING 仅耳机模式连续跑（扬声器模式的
                    //    播报期不检测 = 回声结构性无法误触；探针窗内 VAD 已够）
                    if (asrModule.onlineMode()) {
                        if (_state.value == RealtimeState.LISTENING) {
                            asrModule.processAudioChunkOnline(clean, SAMPLE_RATE)
                        }
                    } else if (_state.value == RealtimeState.LISTENING ||
                        (_state.value == RealtimeState.SPEAKING && hp)) {
                        val result = asrModule.processAudioChunk(clean, SAMPLE_RATE)
                        if (result != null) {
                            val (text, isEndpoint) = result
                            if (_state.value == RealtimeState.SPEAKING) {
                                val compact = normalizeForEcho(text)
                                // v6.15: ≥4 字（回声误转写实测 3-4 字即可成词，AEC 门漏时加余量）
                                if (compact.length >= 4 && !isEchoOfSpeaking(compact)) {
                                    traceManager.record("voice_v5", NodeType.SYSTEM_EVENT, "voice",
                                        "barge_in_triggered: asr_partial=${compact.take(10)}")
                                    interrupt()
                                    continue
                                }
                                // 回声碎片/不足 3 字：端点即静默重置，不外发不入回合
                            } else if (text.isNotBlank()) {
                                _events.tryEmit(RealtimeVoiceEvent.UserTranscript(text, isEndpoint))
                                if (isEndpoint) {
                                    traceManager.record("voice_v5", NodeType.SYSTEM_EVENT, "voice",
                                        "user_final: ${text.take(40)}")
                                    processTurn(text)
                                }
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    traceManager.record("voice_v5", NodeType.ERROR, "voice",
                        "loop_frame_recovered: ${t.javaClass.simpleName} ${t.message?.take(40)}")
                }
            }

            // stop() owns release and joins this session before another start can acquire the mutex.
        }
    }

    private fun processTurn(userText: String) {
        if (!active) return
        val turnScope = sessionScope
        val turnSession = turnScope.coroutineContext[Job]
        fun current() = isCurrentSession(turnSession)
        _state.value = RealtimeState.THINKING
        _events.tryEmit(RealtimeVoiceEvent.StateChanged(RealtimeState.THINKING))
        turnScope.launch {
            try {
                traceManager.record("voice_v5", NodeType.SYSTEM_EVENT, "voice",
                    "turn_start: sending to LLM")
                val t0 = android.os.SystemClock.elapsedRealtime()
                // v6.19 (Step 6.12) 分句流水线：LLM token 流断句逐句下发，首句即启播——
                // 首音延迟 = 首句生成（原为全文生成 3-10s → 现 ~1-2s）
                val sentenceChannel = kotlinx.coroutines.channels.Channel<String>(
                    kotlinx.coroutines.channels.Channel.UNLIMITED)
                var speakJob: kotlinx.coroutines.Job? = null
                try {
                    val reply = chatEngine.chatAndCollect(
                        userText,
                        onStatus = { status ->
                            if (current()) {
                                _events.tryEmit(RealtimeVoiceEvent.AssistantText("💬 $status", isFinal = true))
                            }
                        },
                        onSentence = sentence@ { sentence ->
                            if (!current()) return@sentence
                            if (speakJob == null) {
                                if (active && _state.value == RealtimeState.THINKING) {
                                    traceManager.record("voice_v5", NodeType.SYSTEM_EVENT, "voice",
                                        "first_sentence: ${android.os.SystemClock.elapsedRealtime() - t0}ms len=${sentence.length}")
                                    _state.value = RealtimeState.SPEAKING
                                    _events.tryEmit(RealtimeVoiceEvent.StateChanged(RealtimeState.SPEAKING))
                                    speakJob = turnScope.launch {
                                        val played = ttsModule.speakStreaming(sentenceChannel)
                                        traceManager.record("voice_v5", NodeType.SYSTEM_EVENT, "voice",
                                            "tts_done: played=$played streaming=true mode=${ttsModule.currentMode()}")
                                    }
                                }
                                // THINKING 期被打断（interrupt 已回 LISTENING）→ 不启播（原 tts_skipped 语义）
                            }
                            // v6.13: 回声判别基准随句累积（耳机模式 ASR 判停用）
                            speakingTextNorm = speakingTextNorm + normalizeForEcho(sentence)
                            sentenceChannel.trySend(sentence)
                        })
                    if (!current()) return@launch
                    traceManager.record("voice_v5", NodeType.SYSTEM_EVENT, "voice",
                        "turn_reply: len=${reply.length} mode=${ttsModule.currentMode()}")
                    _events.tryEmit(RealtimeVoiceEvent.AssistantText(reply, isFinal = true))
                    // 关键时序：先关句流再 join——speakStreaming 生产者等 channel 关闭才收尾，
                    // 若 close 拖到 finally（join 之后）将死锁（审计发现）
                    sentenceChannel.close()
                    if (speakJob == null) {
                        traceManager.record("voice_v5", NodeType.SYSTEM_EVENT, "voice",
                            "tts_skipped: interrupted while thinking or empty reply")
                    } else {
                        speakJob!!.join()  // 等播报流水线排空（打断时即时退出）
                    }
                    // 守卫：仍处 SPEAKING 才回 LISTENING（被打断/新回合已接管时不越权改状态）。
                    // D52 防自听：播报结束先清 ASR 流 + 250ms 尾音静默窗
                    finishSpeaking(turnSession)
                } finally {
                    sentenceChannel.close()  // 幂等双保险（异常路径解除 speakStreaming 等待）
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (!current()) return@launch
                recoverTurnFailure(t)
            } finally {
                if (sessionJob === turnSession) speakingTextNorm = ""
            }
        }
    }

    private fun isCurrentSession(job: Job?): Boolean =
        active && job?.isActive == true && sessionJob === job

    private suspend fun finishSpeaking(job: Job?) {
        if (!isCurrentSession(job) || _state.value != RealtimeState.SPEAKING) return
        asrModule.resetStream()
        delay(250)
        if (!isCurrentSession(job) || _state.value != RealtimeState.SPEAKING) return
        _state.value = RealtimeState.LISTENING
        _events.tryEmit(RealtimeVoiceEvent.StateChanged(RealtimeState.LISTENING))
    }

    private fun recoverTurnFailure(error: Throwable) {
        traceManager.record("voice_v5", NodeType.ERROR, "voice",
            "turn_error: ${error.javaClass.simpleName} ${error.message?.take(50)}")
        _events.tryEmit(RealtimeVoiceEvent.Error("对话失败: ${error.message?.take(40)}", true))
        if (active && (_state.value == RealtimeState.THINKING || _state.value == RealtimeState.SPEAKING)) {
            _state.value = RealtimeState.LISTENING
            _events.tryEmit(RealtimeVoiceEvent.StateChanged(RealtimeState.LISTENING))
        }
    }

    private fun hasMicPermission(): Boolean = try {
        context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) { false }

    /** v6.13: 转写/播报文本归一化（去空白与标点）——回声比对基准 */
    private fun normalizeForEcho(s: String): String =
        s.replace(Regex("[\\s，。！？、,.!?…：:;；'\"“”‘’（）()\\[\\]【】\\n\\r]"), "")

    /**
     * v6.13: 回声判别——partial 是正在播报文本的子串，或短碎片与播报文本字符级
     * 高重叠（≥90%，容"AEC 残余失真转写"如 是你↔是您）→ 判为 TTS 回声非真人插话
     */
    private fun isEchoOfSpeaking(compact: String): Boolean {
        val reply = speakingTextNorm
        if (reply.isEmpty()) return false
        if (reply.contains(compact)) return true
        if (compact.length <= 8) {
            val hits = compact.count { reply.contains(it) }
            if (hits.toFloat() / compact.length >= 0.9f) return true
        }
        return false
    }
}
