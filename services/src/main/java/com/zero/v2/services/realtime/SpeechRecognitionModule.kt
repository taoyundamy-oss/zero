package com.zero.v2.services.realtime

import android.content.Context
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.zero.v2.core.moduleapi.ConfigField
import com.zero.v2.core.moduleapi.ConfigFieldType
import com.zero.v2.core.moduleapi.IZeroModule
import com.zero.v2.core.moduleapi.ModuleCategory
import com.zero.v2.core.moduleapi.ModuleConfigBase
import com.zero.v2.core.moduleapi.ModuleLifecycle
import com.zero.v2.core.moduleapi.ModuleMetadata
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import com.zero.v2.services.llm.LlmProfile
import com.zero.v2.services.llm.LlmProfileStore
import com.zero.v2.services.llm.OpenAiCompatibleClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** ASR 模块配置 — v6.20 规范化（D17 用户主权）+ S4.1 双通道引擎（D121） */
class SpeechAsrConfig : ModuleConfigBase() {
    @ConfigField(label = "识别引擎", fieldType = ConfigFieldType.DROPDOWN,
        options = ["local", "online"],
        description = "local=本地离线（sherpa，默认，零上传）｜online=线上 API（MiMo 流式转写，Key 在本页「模型管理」配置）｜" +
                "线上模式：按语句分段流式识别（非逐字实时），断网/超限自动回退本地")
    var engine: String = "local"

    @ConfigField(label = "推理线程数", fieldType = ConfigFieldType.NUMBER, min = 1.0, max = 4.0,
        description = "Zipformer 解码线程（本地引擎与线上模式的断句器共用；修改后需重新进入 Live 生效）")
    var numThreads: Int = 2

    override fun toJson(): String = """{"engine":"$engine","numThreads":$numThreads}"""
    override fun fromJson(json: String) {
        try {
            val obj = JSONObject(json)
            engine = obj.optString("engine", "local").ifBlank { "local" }
            numThreads = obj.optInt("numThreads", 2)
        } catch (_: Exception) {}
    }
    override fun defaultConfig(): ModuleConfigBase = SpeechAsrConfig()
}

/**
 * 语音识别模块 — v5.0 创建，v6.20 规范化为正式 IZeroModule；S4.1 双通道（D121）。
 *
 * local：sherpa-onnx Zipformer 流式 ASR（中英双语 int8）——零变化。
 * online（S4.1）：本地 sherpa 降级为**断句器 + 兜底转写**（端点=VAD 断句），段落 PCM
 * 整段上传 MiMo（/v1/chat/completions + 音频 content part + stream，C2-G4 终稿），
 * SSE 流式出文（ONLINE_PARTIAL 逐段显示，SR4：非逐字实时）；失败/超限（G16）回退
 * 本地已算好的段落文本（LOCAL_FALLBACK，零延迟）。P1-6 状态机：仅终态
 * （ONLINE_FINAL/LOCAL_FALLBACK）→ COMMITTED 进回合；epoch 守卫丢弃打断/挂断后
 * 过期段落（PROCESSING→∅）。
 * 懒加载：initialize() 只提取文件，首次 processAudioChunk() 才实例化 recognizer。
 * 设计偏差：设计稿预留的 FC `transcribe_audio` 未实现——无持久音频可转写，无消费场景。
 */
@Singleton
class SpeechRecognitionModule @Inject constructor(
    @ApplicationContext private val context: Context,
    private val traceManager: AgentTraceManager,
    private val llmClient: OpenAiCompatibleClient,
    private val profileStore: LlmProfileStore
) : IZeroModule {

    companion object { const val SAMPLE_RATE = 16000 }

    /** S4.1 线上段落结果回调（P1-6 外显）：partial=true 即 ONLINE_PARTIAL（仅显示）；
     *  partial=false 为终态——fromOnline=true 即 ONLINE_FINAL，false 即 LOCAL_FALLBACK；
     *  仅终态进最终文本/回合（防线上 partial+本地 fallback 双显）。 */
    fun interface OnlineSegmentListener {
        fun onSegment(text: String, partial: Boolean, fromOnline: Boolean)
    }

    @Volatile var onlineListener: OnlineSegmentListener? = null

    // ═══ S4.1 线上通道状态 ═══
    private val onlineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** 段缓冲（帧追加/端点取出/resetStream 清空——跨线程，synchronized 防护） */
    private val segBuf = ArrayList<FloatArray>()
    /** 会话代际：resetStream（打断/挂断/回合结束）递增 → 在途线上段落过期丢弃 */
    private val epoch = AtomicLong(0)
    private val segSeq = AtomicLong(0)

    fun onlineMode(): Boolean = (config as SpeechAsrConfig).engine == "online"

    override val metadata = ModuleMetadata(
        id = "speech_asr",
        displayName = "语音识别",
        description = "ASR 双通道：本地离线（sherpa Zipformer）/ 线上 MiMo 流式转写（断网自动回退本地）",
        category = ModuleCategory.INSTRUMENT
    )

    private val _lifecycle = MutableStateFlow(ModuleLifecycle.UNINITIALIZED)
    override val lifecycle = _lifecycle.asStateFlow()
    override var isEnabled: Boolean = true
    override val requiredPermissions: List<String> = emptyList()
    override val config = SpeechAsrConfig()

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var paths: ModelAssetManager.AsrPaths? = null
    private val initialized = AtomicBoolean(false)

    private val _ready = MutableStateFlow(false)
    val ready = _ready.asStateFlow()

    // ═══ IZeroModule 生命周期（ModuleLifecycleManager 统一调度，safeCall 隔离）═══

    override suspend fun onAwake() {
        _lifecycle.value = ModuleLifecycle.AWAKE
        initialize()
    }
    override suspend fun onStart() { _lifecycle.value = ModuleLifecycle.STARTED }
    override suspend fun onDestroy() {
        _lifecycle.value = ModuleLifecycle.DESTROYED
        release()
    }
    override suspend fun onConfigChanged(newConfig: ModuleConfigBase) {}

    override suspend fun checkAvailability(): Boolean = true
    override fun markDisabled() { _lifecycle.value = ModuleLifecycle.DISABLED }

    /** 模块初始化（幂等：onAwake 与 provider.initializeModules 双入口安全） */
    suspend fun initialize() {
        if (!initialized.compareAndSet(false, true)) return
        paths = ModelAssetManager.getAsrPaths(context)
        if (paths == null) {
            traceManager.record("asr", NodeType.ERROR, "speech_asr",
                "model_extract_failed: asr 模型文件不存在")
            return
        }
        _ready.value = true
        traceManager.record("asr", NodeType.SYSTEM_EVENT, "speech_asr",
            "models_ready: encoder=${paths!!.encoder.takeLast(30)}")
    }

    /** 懒加载 recognizer（线程数取自 config，构建时生效） */
    private fun getRecognizer(): OnlineRecognizer? {
        recognizer?.let { return it }
        val p = paths ?: return null
        return try {
            val transducer = OnlineTransducerModelConfig.builder()
                .setEncoder(p.encoder).setDecoder(p.decoder).setJoiner(p.joiner).build()
            val modelConfig = OnlineModelConfig.builder()
                .setTransducer(transducer).setTokens(p.tokens)
                .setNumThreads((config as SpeechAsrConfig).numThreads.coerceIn(1, 4)).build()
            val endpointConfig = com.k2fsa.sherpa.onnx.EndpointConfig.builder()
                .setRule1(com.k2fsa.sherpa.onnx.EndpointRule.builder()
                    .setMustContainNonSilence(false).setMinTrailingSilence(2.4f)
                    .setMinUtteranceLength(0f).build())
                .setRule2(com.k2fsa.sherpa.onnx.EndpointRule.builder()
                    .setMustContainNonSilence(true).setMinTrailingSilence(1.2f)
                    .setMinUtteranceLength(0.25f).build())
                .setRule3(com.k2fsa.sherpa.onnx.EndpointRule.builder()
                    .setMustContainNonSilence(false).setMinTrailingSilence(0.5f)
                    .setMinUtteranceLength(20f).build())
                .build()
            val config = OnlineRecognizerConfig.builder()
                .setOnlineModelConfig(modelConfig)
                .setEnableEndpoint(true)
                .setEndpointConfig(endpointConfig)
                .build()
            recognizer = OnlineRecognizer(config)
            stream = recognizer!!.createStream()
            traceManager.record("asr", NodeType.SYSTEM_EVENT, "speech_asr",
                "recognizer_loaded: threads=${modelConfig.numThreads} endpoint=1.2s/2.4s（长语音防截断）")
            recognizer
        } catch (t: Throwable) {
            traceManager.record("asr", NodeType.ERROR, "speech_asr",
                "recognizer_init_failed: ${t.javaClass.simpleName} ${t.message?.take(50)}")
            null
        }
    }

    /**
     * 处理一帧音频 → 流式识别结果。
     * @return (text, isEndpoint) 或 null
     */
    fun processAudioChunk(samples: FloatArray, sampleRate: Int = SAMPLE_RATE): Pair<String, Boolean>? {
        val rec = getRecognizer() ?: return null
        val s = stream ?: return null
        return try {
            s.acceptWaveform(samples, sampleRate)
            if (rec.isReady(s)) {
                rec.decode(s)
                val result = rec.getResult(s)
                val endpoint = rec.isEndpoint(s)
                if (endpoint && result.text.isNotBlank()) rec.reset(s)
                result.text to endpoint
            } else null
        } catch (t: Throwable) {
            traceManager.record("asr", NodeType.ERROR, "speech_asr",
                "process_error: ${t.message?.take(40)}")
            null
        }
    }

    // ═══ S4.1 线上通道（D121：VAD 断句整段上传 + SSE 流式出文 + 失败回退本地）═══

    /**
     * 线上模式逐帧：本地 sherpa 仅作断句器与兜底转写——**partial 不外显**（P1-6 防线上
     * partial+本地文本双显；SR4 行为说明=按语句分段流式识别）。端点即段落成立：
     * 取出段缓冲整段上传，上传期间继续收下一句（并发段允许，epoch+LISTENING 守卫防串扰）。
     */
    fun processAudioChunkOnline(samples: FloatArray, sampleRate: Int) {
        val rec = getRecognizer() ?: return  // 模型不可用=两引擎皆不可用（与本地路径同语义）
        val s = stream ?: return
        try {
            s.acceptWaveform(samples, sampleRate)
            var endpoint = false
            var fallbackText = ""
            if (rec.isReady(s)) {
                rec.decode(s)
                fallbackText = rec.getResult(s).text
                endpoint = rec.isEndpoint(s)
            }
            if (endpoint) {
                if (fallbackText.isNotBlank()) rec.reset(s)  // 与本地路径同款：端点且非空才重置
                val segment = synchronized(segBuf) {
                    segBuf.add(samples.copyOf())
                    val total = segBuf.sumOf { it.size }
                    val flat = FloatArray(total)
                    var off = 0
                    segBuf.forEach { System.arraycopy(it, 0, flat, off, it.size); off += it.size }
                    segBuf.clear()
                    flat
                }
                if (fallbackText.isNotBlank()) launchOnline(segment, fallbackText)
            } else {
                synchronized(segBuf) { segBuf.add(samples.copyOf()) }
            }
        } catch (t: Throwable) {
            traceManager.record("asr", NodeType.ERROR, "speech_asr",
                "online_frame_error: ${t.message?.take(40)}")
        }
    }

    /** 段落上传（P1-6：PROCESSING → ONLINE_PARTIAL* → ONLINE_FINAL / LOCAL_FALLBACK） */
    private fun launchOnline(segment: FloatArray, localFallback: String) {
        val myEpoch = epoch.get()
        val segId = segSeq.incrementAndGet()
        onlineScope.launch {
            try {
                val acc = StringBuilder()
                val wav = wavFromPcm16kMono(segment)
                val text = llmClient.chatAudioStream(asrProfile(), wav) { delta ->
                    acc.append(delta)
                    if (epoch.get() == myEpoch)
                        onlineListener?.onSegment(acc.toString(), partial = true, fromOnline = true)
                }
                if (epoch.get() != myEpoch) {
                    traceManager.record("asr", NodeType.SYSTEM_EVENT, "speech_asr",
                        "online_seg_canceled: seg=$segId (epoch 过期)")
                    return@launch
                }
                val final = text.trim()
                if (final.isNotBlank()) {
                    traceManager.record("asr", NodeType.SYSTEM_EVENT, "speech_asr",
                        "online_final: seg=$segId len=${final.length}")
                    onlineListener?.onSegment(final, partial = false, fromOnline = true)
                } else if (localFallback.isNotBlank()) {
                    traceManager.record("asr", NodeType.SYSTEM_EVENT, "speech_asr",
                        "online_empty_fallback: seg=$segId (线上空结果→本地)")
                    onlineListener?.onSegment(localFallback, partial = false, fromOnline = false)
                } else {
                    traceManager.record("asr", NodeType.SYSTEM_EVENT, "speech_asr",
                        "online_seg_empty: seg=$segId (双方皆空，丢弃)")
                }
            } catch (t: Throwable) {
                if (epoch.get() != myEpoch) return@launch
                traceManager.record("asr", NodeType.ERROR, "speech_asr",
                    "online_fallback_local: seg=$segId ${t.javaClass.simpleName} ${t.message?.take(60)}")
                if (localFallback.isNotBlank())
                    onlineListener?.onSegment(localFallback, partial = false, fromOnline = false)
            }
        }
    }

    /** 线上 ASR 有效 profile（S1.2 同款三级解析：启用 → 本 kind 任一 → 预置兜底） */
    private fun asrProfile(): LlmProfile {
        val preset = LlmProfile.defaults().first { it.id == "mimo-asr" }
        return profileStore.getEnabled(LlmProfile.Kind.ASR).firstOrNull()
            ?: profileStore.byKind(LlmProfile.Kind.ASR).firstOrNull()
            ?: preset
    }

    /** 段 PCM(16k mono float) → WAV 字节（16-bit LE，44 字节标准头） */
    private fun wavFromPcm16kMono(samples: FloatArray): ByteArray {
        val dataLen = samples.size * 2
        val out = ByteArray(44 + dataLen)
        fun le32(off: Int, v: Int) { out[off] = (v and 0xFF).toByte(); out[off+1] = ((v shr 8) and 0xFF).toByte(); out[off+2] = ((v shr 16) and 0xFF).toByte(); out[off+3] = ((v shr 24) and 0xFF).toByte() }
        fun le16(off: Int, v: Int) { out[off] = (v and 0xFF).toByte(); out[off+1] = ((v shr 8) and 0xFF).toByte() }
        out[0] = 'R'.code.toByte(); out[1] = 'I'.code.toByte(); out[2] = 'F'.code.toByte(); out[3] = 'F'.code.toByte()
        le32(4, 36 + dataLen)
        out[8] = 'W'.code.toByte(); out[9] = 'A'.code.toByte(); out[10] = 'V'.code.toByte(); out[11] = 'E'.code.toByte()
        out[12] = 'f'.code.toByte(); out[13] = 'm'.code.toByte(); out[14] = 't'.code.toByte(); out[15] = ' '.code.toByte()
        le32(16, 16); le16(20, 1); le16(22, 1)                    // PCM / mono
        le32(24, SAMPLE_RATE); le32(28, SAMPLE_RATE * 2)          // 采样率 / 字节率
        le16(32, 2); le16(34, 16)                                 // 块对齐 / 位深
        out[36] = 'd'.code.toByte(); out[37] = 'a'.code.toByte(); out[38] = 't'.code.toByte(); out[39] = 'a'.code.toByte()
        le32(40, dataLen)
        for (i in samples.indices) {
            val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            out[44 + i * 2] = (v.toInt() and 0xFF).toByte()
            out[45 + i * 2] = ((v.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    fun resetStream() {
        // S4.1：递增 epoch → 在途线上段落过期（打断/挂断/回合结束的尾段不回流）+ 清段缓冲
        epoch.incrementAndGet()
        synchronized(segBuf) { segBuf.clear() }
        try { stream?.let { recognizer?.reset(it) } } catch (_: Exception) {}
    }

    fun release() {
        onlineScope.cancel()
        epoch.incrementAndGet()
        synchronized(segBuf) { segBuf.clear() }
        stream?.release(); stream = null
        recognizer?.release(); recognizer = null
        _ready.value = false
    }
}
