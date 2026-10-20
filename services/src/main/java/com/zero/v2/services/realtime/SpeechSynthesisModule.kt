package com.zero.v2.services.realtime

import android.content.Context
import android.speech.tts.TextToSpeech
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.zero.v2.core.common.ActionPermission
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.moduleapi.ConfigField
import com.zero.v2.core.moduleapi.ConfigFieldType
import com.zero.v2.core.moduleapi.ModuleCategory
import com.zero.v2.core.moduleapi.ModuleConfigBase
import com.zero.v2.core.moduleapi.ModuleLifecycle
import com.zero.v2.core.moduleapi.ModuleMetadata
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * S4.2（D125/C4）：TTS 产物落盘缝——services 侧定义、app 侧 WorkDirRepository 实现
 * （ChatEngineBridge 同款 Hilt 缝：services 不能反向依赖 app 拿 SAF 写入能力）。
 * G17 净化（basename 强制、剥路径穿越）在实现侧统一收口。
 */
interface TtsWavSink {
    /** WAV 写入工作目录 downloads/（C4：openReadStream 只检索 root/downloads 两层）。
     *  @return 最终文件名（成功）；失败由实现侧 trace（E28）并返回 null */
    suspend fun writeWavToDownloads(
        fileName: String,
        wav: ByteArray,
    ): String?
}

/**
 * TTS 模块配置 — v5.7。模块管理 → 语音合成（详情页 AutoForm 即设置子页）。
 *
 * 引擎选项（用户决策 2026-08-24：去除 Kokoro——实测 RTF=3.07 本机不可用）：
 *   mimo  = 小米 MiMo v2.5 在线流式（默认；OpenAI 兼容 / 24kHz pcm16 SSE / 限时免费）
 *   melo  = 本地 vits-melo-tts-zh_en（离线零上传；模型放 assets/models/tts_melo/）
 *   system = 系统 TTS（零成本兜底）
 */
class SpeechTtsConfig : ModuleConfigBase() {
    @ConfigField(
        label = "TTS 引擎",
        fieldType = ConfigFieldType.DROPDOWN,
        options = ["mimo", "melo", "system"],
        description =
            "mimo=在线流式（默认，音质最好；端点/模型/Key 在本页「模型管理」编辑页配置，断网自动降级系统 TTS）｜" +
                "melo=本地离线（零上传，需放入模型文件）｜system=系统 TTS（零成本兜底）",
    )
    var engine: String = "mimo"

    /**
     * MiMo Key 的 SecureKeyStore 条目名（Secret/Config 分离）。
     * v5.8：不再是可编辑配置字段——旧版 SECRET ConfigField 语义误导用户把明文 Key
     * 粘进本字段直存 config（模块当条目名查必然 miss）。Key 统一走模块详情页
     * "🔑 API Keys" 卡片（buildApiKeyFields → secureKeyStore.put）。保留 var 仅为
     * 从旧存量 JSON 迁移明文 Key（见 initialize()）。
     */
    var mimoApiKeyRef: String = "tts:mimo"

    /**
     * MiMo 音色（S1.2-b 退役 ConfigField）：音色唯一源 = mimo-tts profile.voice
     * （「模型管理」编辑子页，用户裁定单入口沿用 S1.1-fix2 设计）。保留 var + JSON
     * 往返仅作一次性迁移源（mimoProfile() 搬运 config.mimoVoice → profile.voice）。
     */
    var mimoVoice: String = "冰糖"

    @ConfigField(
        label = "Melo 语速 %",
        fieldType = ConfigFieldType.NUMBER,
        min = 50.0,
        max = 200.0,
        description = "仅本地 Melo 引擎生效（100=原速，数值越大越快）",
    )
    var meloSpeedPercent: Int = 100

    override fun toJson(): String =
        """{"engine":"$engine","mimoApiKeyRef":"$mimoApiKeyRef","mimoVoice":"$mimoVoice","meloSpeedPercent":$meloSpeedPercent}"""

    override fun fromJson(json: String) {
        try {
            val obj = JSONObject(json)
            engine = obj.optString("engine", "mimo")
            mimoApiKeyRef = obj.optString("mimoApiKeyRef", "tts:mimo")
            mimoVoice = obj.optString("mimoVoice", "冰糖")
            meloSpeedPercent = obj.optInt("meloSpeedPercent", 100)
        } catch (_: Exception) {
        }
    }

    override fun defaultConfig(): ModuleConfigBase = SpeechTtsConfig()
}

/**
 * 语音合成模块 — v5.7 正式 IZeroModule（模块管理可配置）；S4.2 首个 FC（text_to_speech）。
 *
 * 三引擎：MiMo 在线流式（默认）/ Melo 本地 VITS / 系统 TTS。
 * 失败降级：mimo（Key 缺失/网络错）→ system；melo（模型缺失）→ system。
 * S4.2（D125）：synthesizeToWav 截获合成流写 WAV（mimo 优先/本地 Melo 同理，系统 TTS
 * 不支持）→ TtsWavSink 落盘 downloads/ → FC 默认自动 upload_file 得 file_id。
 * 沿用 v5.5/v5.6 机制：speakMutex 播报互斥、speakEpoch 会话代际（挂断漏音）、
 * nativeLock（generate/release 互斥）、AudioTrack 写背压流水线、flushTrack 即停。
 */
@Singleton
class SpeechSynthesisModule
    @Inject
    constructor(
        @ApplicationContext private val context: Context,

        private val onlineSpeech: MimoSpeechProvider,
        private val traceManager: AgentTraceManager,

        private val toolDispatcher: com.zero.v2.core.moduleapi.ToolDispatcher,

        private val ttsWavSink: TtsWavSink,
    ) : com.zero.v2.core.moduleapi.IZeroModule, com.zero.v2.core.moduleapi.ToolContributor {
        enum class TtsMode { ONLINE_MIMO, NATIVE_MELO, SYSTEM_TTS, TEXT_ONLY }

        companion object {
            private const val TAG_TRACE = "tts"
            private const val TAG_SRC = "speech_tts"

            // D54 静默探针：播报每 3.5s 等缓冲排空后静默 450ms——扬声器模式在探针窗内检测
            // 真人插话（此刻无任何播放 = 无回声，有声音必是真人；打断延迟上限 ≈ 句长）
            private const val PROBE_INTERVAL_MS = 3500L
            private const val PROBE_MS = 450L
        }

        override val metadata =
            ModuleMetadata(
                id = "speech_tts",
                displayName = "语音合成",
                description = "TTS 引擎：MiMo 在线流式 / Melo 本地 / 系统 TTS（Live 语音对话输出）+ text_to_speech 语音文件生成",
                category = ModuleCategory.INSTRUMENT,
            )

        private val _lifecycle = MutableStateFlow(ModuleLifecycle.UNINITIALIZED)
        override val lifecycle = _lifecycle.asStateFlow()
        override var isEnabled: Boolean = true
        override val requiredPermissions: List<String> = emptyList()
        override val config = SpeechTtsConfig()

        // 引擎状态（跨线程可见）
        @Volatile private var mode: TtsMode = TtsMode.TEXT_ONLY

        @Volatile private var melo: OfflineTts? = null
        private var systemTts: TextToSpeech? = null

        @Volatile private var systemTtsReady = false

        @Volatile private var meloPaths: ModelAssetManager.MeloPaths? = null
        private val meloLoadStarted = AtomicBoolean(false)
        private val initialized = AtomicBoolean(false)

        private val _ready = MutableStateFlow(false)
        val ready = _ready.asStateFlow()

        private var audioTrack: android.media.AudioTrack? = null
        private val stopRequested = AtomicBoolean(false)
        private val isPlaying = AtomicBoolean(false)

        // v5.5 并发防护：播报互斥 + native 调用互斥（generate/release/预热）
        private val speakMutex = Mutex()
        private val nativeLock = Any()
        private val moduleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        // v5.6 会话代际：stop()/挂断 +1，排队等锁的 speak 获锁后发现代际变化即丢弃（挂断后继续发声的根因）
        private val speakEpoch = java.util.concurrent.atomic.AtomicInteger(0)

        /**
         * D53 far-end 参考回调（Step 6.11）：写入扬声器的音频喂软件 AEC 作回声参考。
         * v6.14 关键修正——**按播放进度释放**：write() 时音频还在 AudioTrack 缓冲里未出声，
         * 实际播放滞后 200-300ms；若 write 即喂，回声到达麦克风时其参考已滑出 AEC 滤波窗
         * （实测 erle 仅 0-4.5dB，形同没开）。现按 playbackHeadPosition 对齐——参考样本
         * 恰好在被播出时进入 AEC，滤波窗只需吸收声学+环路残差。
         * 系统 TTS 引擎无 PCM 访问 → 不喂（该引擎下由播报文本回声判别兜底）。
         */
        @Volatile var farEndListener: ((FloatArray) -> Unit)? = null

        /** D54 静默探针窗回调（true=探针开始全静默，false=结束恢复播报）——由 Provider 接管 */
        @Volatile var probeListener: ((Boolean) -> Unit)? = null

        private val aecResampler = SoftwareAec() // 仅用其重采样/转换工具（零滤波状态）

        // far-end 播放对齐队列：(起始播放帧, 16kHz float 块)——head 到达起始帧才释放
        private val farQueue = java.util.ArrayDeque<Pair<Long, FloatArray>>()
        private var farWritten = 0L // 累计已 write 的 track 帧数
        private var farLastHead = 0L // 上次观测的 playbackHead（检测 flush 重置）

        private fun emitFarEnd(
            track: android.media.AudioTrack,
            pcm16Bytes: ByteArray,
            sampleRate: Int,
        ) {
            val l = farEndListener ?: return
            val head = track.playbackHeadPosition.toLong()
            // 头位置回退 = flush/interrupt/换轨（播放中止）→ 清队重置（残余参考不再会出声）
            if (head + 64 < farLastHead || (farLastHead == 0L && head == 0L && farQueue.isNotEmpty())) {
                farQueue.clear()
                farWritten = head
            }
            farLastHead = head
            val start = farWritten
            farWritten += pcm16Bytes.size / 2
            farQueue.add(
                start to
                    aecResampler.resampleTo16k(
                        aecResampler.pcm16ToFloat(pcm16Bytes), sampleRate,
                    ),
            )
            // 释放已开始实际播放的参考块
            while (farQueue.isNotEmpty() && head >= farQueue.peekFirst().first) {
                l(farQueue.removeFirst().second)
            }
        }

        private fun resetFarQueue() {
            farQueue.clear()
            farWritten = 0
            farLastHead = 0
        }

        // ═══ IZeroModule 生命周期 ═══

        override suspend fun onAwake() {
            _lifecycle.value = ModuleLifecycle.AWAKE
            initialize()
            // S4.2-fix（L3 实测触发）：FC 注册须在 onAwake 自调——ModuleLifecycleManager 不统一
            // 代调 registerFunctions（WebFetch/WebSearch 先例）；漏调 = text_to_speech 不进工具列表

        }

        override suspend fun onStart() {
            _lifecycle.value = ModuleLifecycle.STARTED
        }

        override suspend fun onDestroy() {
            fcScope.cancel()
            moduleScope.cancel()
            _lifecycle.value = ModuleLifecycle.DESTROYED
            release()
        }

        override suspend fun onConfigChanged(newConfig: ModuleConfigBase) {
            // 引擎/音色/语速即时生效（speak 每次直读 config，无需处理）；Melo 语速变化需重建引擎
            if (melo != null) releaseLocalEngine("config_changed")
        }

        override fun contributions(): List<com.zero.v2.core.moduleapi.ToolContribution> =
            com.zero.v2.core.moduleapi.toolDeclarations(metadata.id) {
            val functionRegistry = this
            // S4.2（D125）：TTS 首个 FC——结束零 FC 历史。权限对齐 save_to_file（写新文件进
            // 工作目录 = CONFIRM）；上传联动走 registry.execute 程序化通道（无权限闸——外层
            // text_to_speech 已过确认）
            functionRegistry.register(
                FunctionDefinition(
                    name = "text_to_speech",
                    description =
                        "将文本合成为语音 WAV 文件，保存到工作目录 downloads/，默认自动上传云端得 file_id（24 小时有效）。" +
                            "适用：生成语音消息、朗读内容；配合通道工具发送语音。",
                    parameters =
                        mapOf(
                            "type" to "object",
                            "properties" to
                                mapOf(
                                    "text" to mapOf("type" to "string", "description" to "要合成的文本"),
                                    "save_as" to
                                        mapOf(
                                            "type" to "string",
                                            "description" to "保存文件名（可选，仅文件名不含路径——自动净化剥穿越；缺省自动命名 .wav）",
                                        ),
                                    "cloud" to mapOf("type" to "boolean", "description" to "是否自动上传云端得 file_id（默认 true）"),
                                    "play" to mapOf("type" to "boolean", "description" to "合成后立即在本机外放（默认 false）"),
                                ),
                            "required" to listOf("text"),
                        ),
                ),
                metadata.id,
                permission = ActionPermission.CONFIRM,
            ) { args ->
                synthesizeAndSave(
                    text = args["text"] as? String ?: "",
                    saveAs = args["save_as"] as? String,
                    cloud = (args["cloud"] as? Boolean) ?: true,
                    play = (args["play"] as? Boolean) ?: false,
                )
            }
        }

        // ═══ S4.2（D125）：合成落盘 FC 通道 ═══

        /** FC 主流程：合成 → WAV 落盘 downloads/ →（默认）上传云端得 file_id →（可选）外放 */
        private suspend fun synthesizeAndSave(
            text: String,
            saveAs: String?,
            cloud: Boolean,
            play: Boolean,
        ): FunctionResult =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (text.isBlank()) return@withContext FunctionResult.failure("text 不能为空")
                val wav =
                    synthesizeToWav(text)
                        ?: return@withContext FunctionResult.failure(
                            "语音合成失败：MiMo Key 未配置且本地 Melo 引擎不可用（模块管理 → 语音合成）",
                        )
                val finalName =
                    ttsWavSink.writeWavToDownloads(saveAs ?: "", wav)
                        ?: return@withContext FunctionResult.failure("WAV 落盘失败（工作目录 downloads/，见 RunLog）")
                traceManager.record(
                    TAG_TRACE,
                    NodeType.SYSTEM_EVENT,
                    TAG_SRC,
                    "tts_file_done: $finalName bytes=${wav.size}",
                )
                val sb = StringBuilder("✅ 已生成语音：工作目录/downloads/$finalName（${wav.size / 1024}KB）")
                if (cloud) {
                    // 产物自动上云（经 registry 程序化通道调 upload_file——统一通信，模块间零直连）
                    val up = toolDispatcher.execute("upload_file", mapOf("file_name" to finalName))
                    if (up.success) {
                        Regex("file_id=([\\w-]+)").find(up.data)?.groupValues?.get(1)?.let { id ->
                            sb.append("\n☁️ 已上传云端：file_id=").append(id).append("（24 小时有效）")
                        } ?: sb.append("\n☁️ ").append(up.data)
                    } else {
                        sb.append("\n⚠️ 云端上传失败：").append(up.data.take(60)).append("（文件已保留本地）")
                    }
                }
                if (play) {
                    fcScope.launch {
                        val played = speak(text) // 走既有完整播报管线（含降级链）
                        traceManager.record(
                            TAG_TRACE,
                            NodeType.SYSTEM_EVENT,
                            TAG_SRC,
                            "tts_file_play: played=$played",
                        )
                    }
                    sb.append("\n🔊 正在本机外放")
                }
                val refs = if (wav.size <= com.zero.v2.core.common.ContentLimits.MAX_BYTES) {
                    val file = com.zero.v2.core.common.AttachmentVault(java.io.File(context.filesDir, "attachments"))
                        .save(wav)
                    listOf(com.zero.v2.core.common.MediaRef(file.toURI().toString(), "audio/wav",
                        com.zero.v2.core.common.MediaKind.AUDIO))
                } else emptyList()
                FunctionResult.success(sb.toString()).copy(mediaRefs = refs)
            }

        /** FC 播放外放协程域（不阻塞 FC 返回） */
        private val fcScope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

        /**
         * S4.2（D125）：文本 → WAV 字节（截获音频流写文件，不外放）。引擎：MiMo（有 Key
         * 优先，复用 speakMimo 的 ttsStreamPcm 流）→ 本地 Melo（同理逐句 generate）；
         * 系统 TTS 不支持文件合成。@return null = 无可用引擎/失败（已 trace，E28）
         */
        suspend fun synthesizeToWav(text: String): ByteArray? =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val segments = TtsTextSegmenter.split(text)
                if (segments.isEmpty()) return@withContext null
                try {
                    val key = resolveMimoKey()
                    if (!key.isNullOrBlank()) {
                        val pcm = java.io.ByteArrayOutputStream()
                        for (seg in segments) {
                            onlineSpeech.streamPcm(mimoProfile(), seg, { false }, key) { chunk ->
                                pcm.write(chunk)
                            }
                        }
                        traceManager.record(
                            TAG_TRACE,
                            NodeType.SYSTEM_EVENT,
                            TAG_SRC,
                            "tts_file_synth: engine=mimo segs=${segments.size} pcmKB=${pcm.size() / 1024}",
                        )
                        wavFromPcm16(pcm.toByteArray(), MimoTtsClient.SAMPLE_RATE)
                    } else {
                        val engine =
                            ensureMelo() ?: run {
                                traceManager.record(
                                    TAG_TRACE, NodeType.ERROR, TAG_SRC,
                                    "tts_file_no_engine: MiMo Key 未配置且 Melo 不可用",
                                )
                                return@withContext null
                            }
                        val pcm = java.io.ByteArrayOutputStream()
                        var sampleRate = engine.sampleRate
                        for (seg in segments) {
                            val audio: GeneratedAudio = synchronized(nativeLock) { engine.generate(seg, 0, 1.0f) }
                            sampleRate = audio.sampleRate
                            val bytes = ByteArray(audio.samples.size * 2)
                            for (i in audio.samples.indices) {
                                val v = (audio.samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                                bytes[i * 2] = (v.toInt() and 0xFF).toByte()
                                bytes[i * 2 + 1] = ((v.toInt() shr 8) and 0xFF).toByte()
                            }
                            pcm.write(bytes)
                        }
                        traceManager.record(
                            TAG_TRACE,
                            NodeType.SYSTEM_EVENT,
                            TAG_SRC,
                            "tts_file_synth: engine=melo segs=${segments.size} pcmKB=${pcm.size() / 1024}",
                        )
                        wavFromPcm16(pcm.toByteArray(), sampleRate)
                    }
                } catch (t: Throwable) {
                    traceManager.record(
                        TAG_TRACE,
                        NodeType.ERROR,
                        TAG_SRC,
                        "tts_file_failed: ${t.javaClass.simpleName} ${t.message?.take(60)}",
                    )
                    null
                }
            }

        /** PCM16 LE → WAV（mono/16bit，44 字节标准头） */
        private fun wavFromPcm16(
            pcm: ByteArray,
            sampleRate: Int,
        ): ByteArray {
            val out = ByteArray(44 + pcm.size)

            fun le32(
                off: Int,
                v: Int,
            ) {
                out[off] = (v and 0xFF).toByte()
                out[off + 1] = ((v shr 8) and 0xFF).toByte()
                out[off + 2] = ((v shr 16) and 0xFF).toByte()
                out[off + 3] = ((v shr 24) and 0xFF).toByte()
            }

            fun le16(
                off: Int,
                v: Int,
            ) {
                out[off] = (v and 0xFF).toByte()
                out[off + 1] = ((v shr 8) and 0xFF).toByte()
            }
            out[0] = 'R'.code.toByte()
            out[1] = 'I'.code.toByte()
            out[2] = 'F'.code.toByte()
            out[3] = 'F'.code.toByte()
            le32(4, 36 + pcm.size)
            out[8] = 'W'.code.toByte()
            out[9] = 'A'.code.toByte()
            out[10] = 'V'.code.toByte()
            out[11] = 'E'.code.toByte()
            out[12] = 'f'.code.toByte()
            out[13] = 'm'.code.toByte()
            out[14] = 't'.code.toByte()
            out[15] = ' '.code.toByte()
            le32(16, 16)
            le16(20, 1)
            le16(22, 1)
            le32(24, sampleRate)
            le32(28, sampleRate * 2)
            le16(32, 2)
            le16(34, 16)
            out[36] = 'd'.code.toByte()
            out[37] = 'a'.code.toByte()
            out[38] = 't'.code.toByte()
            out[39] = 'a'.code.toByte()
            le32(40, pcm.size)
            System.arraycopy(pcm, 0, out, 44, pcm.size)
            return out
        }

        override suspend fun checkAvailability(): Boolean = true

        override fun markDisabled() {
            _lifecycle.value = ModuleLifecycle.DISABLED
        }

        /** 模块初始化（幂等）：提取 Melo 模型路径 + 系统 TTS 就绪 */
        suspend fun initialize() {
            if (!initialized.compareAndSet(false, true)) return
            meloPaths = ModelAssetManager.getMeloPaths(context)
            initSystemTts()
            mode = TtsMode.SYSTEM_TTS.takeIf { systemTtsReady } ?: TtsMode.TEXT_ONLY
            _ready.value = true
            traceManager.record(
                TAG_TRACE,
                NodeType.SYSTEM_EVENT,
                TAG_SRC,
                "models_ready: meloModel=${meloPaths != null} systemTts=$systemTtsReady engine=${config.engine}",
            )
        }

        /**
         * 解析 MiMo Key（顺序无关）：
         * ① ref 正常（如 tts:mimo）→ SecureKeyStore 查值（miss 再兜底查 tts:mimo）；
         * ② 字段值本身是明文 Key（sk- 开头，v5.8 前误存）→ 直接使用并当场迁入 SecureKeyStore 复位——
         *    不能只在 initialize() 迁移：模块启动不恢复存量 config（挂载点在 ModuleDetailViewModel.load()），
         *    initialize 先于 UI 恢复执行时迁移必然空转（实测根因）。
         */
        private fun resolveMimoKey(): String? = onlineSpeech.resolveKey(config)

        private fun initSystemTts() {
            try {
                systemTts =
                    TextToSpeech(context) { status ->
                        systemTtsReady = status == TextToSpeech.SUCCESS
                        if (systemTtsReady) {
                            systemTts?.language = Locale.getDefault()
                            traceManager.record(TAG_TRACE, NodeType.SYSTEM_EVENT, TAG_SRC, "system_tts_ready")
                        }
                    }
            } catch (_: Exception) {
            }
        }

        /** 当前引擎模式（trace 展示用） */
        fun currentMode(): TtsMode = mode

        /** 后台尝试加载 Melo（由 Provider 会话初始化时调用——一次性守卫） */
        fun tryLoadMeloInBackground() {
            if (melo == null && meloPaths != null && meloLoadStarted.compareAndSet(false, true)) {
                Thread {
                    try {
                        ensureMelo()
                    } catch (_: Throwable) {
                    }
                }.start()
            }
        }

        /** 懒加载 Melo（vits-melo-tts-zh_en：VITS 架构，手机 RTF ≈ 0.1） */
        private fun ensureMelo(): OfflineTts? {
            melo?.let { return it }
            val p =
                meloPaths ?: run {
                    traceManager.record(
                        TAG_TRACE, NodeType.ERROR, TAG_SRC,
                        "melo_skipped: 模型未部署——请将 vits-melo-tts-zh_en 官方包内容复制到 assets/models/tts_melo/",
                    )
                    return null
                }
            return try {
                val vits =
                    OfflineTtsVitsModelConfig.builder()
                        .setModel(p.model).setTokens(p.tokens)
                        .setLexicon(p.lexicon ?: "").setDataDir(p.dataDir).setDictDir(p.dictDir ?: "")
                        .setNoiseScale(0.667f).setNoiseScaleW(0.8f)
                        .setLengthScale(100f / config.meloSpeedPercent.coerceIn(50, 200))
                        .build()
                val modelConfig =
                    OfflineTtsModelConfig.builder()
                        .setVits(vits).setNumThreads(2).setDebug(false).build()
                val cfg = OfflineTtsConfig.builder().setModel(modelConfig).build()
                val instance = OfflineTts(cfg)
                synchronized(nativeLock) { instance.generate("你好", 0, 1.0f) } // 预热验证词典全链路
                melo = instance
                mode = TtsMode.NATIVE_MELO
                traceManager.record(
                    TAG_TRACE,
                    NodeType.SYSTEM_EVENT,
                    TAG_SRC,
                    "melo_loaded: speakers=${instance.numSpeakers} sampleRate=${instance.sampleRate} prewarmed=true",
                )
                instance
            } catch (t: Throwable) {
                traceManager.record(
                    TAG_TRACE,
                    NodeType.ERROR,
                    TAG_SRC,
                    "melo_failed: ${t.javaClass.simpleName} ${t.message?.take(
                        60,
                    )} → 降级 ${if (systemTtsReady) "SYSTEM_TTS" else "TEXT_ONLY"}",
                )
                mode = if (systemTtsReady) TtsMode.SYSTEM_TTS else TtsMode.TEXT_ONLY
                null
            }
        }

        // ═══ 播报入口 ═══

        /** 合成并播放（引擎分发 + 失败降级）。@return true = 完整播放 */
        suspend fun speak(text: String): Boolean =
            withContext(Dispatchers.IO) {
                val epoch = speakEpoch.get() // 入队时的会话代际
                speakMutex.withLock {
                    if (epoch != speakEpoch.get()) {
                        traceManager.record(
                            TAG_TRACE,
                            NodeType.SYSTEM_EVENT,
                            TAG_SRC,
                            "speak_discarded: 排队期间会话已停止/打断",
                        )
                        return@withLock false
                    }
                    speakLocked(text)
                }
            }

        /**
         * v6.19 (Step 6.12) 分句流式播报：句 Channel 逐句消费——MiMo 引擎走句级流水线
         * （合成 N+1 与播放 N 并行，容量 1 有界预取）；非 MiMo 引擎聚合全文走整段播报。
         * @return true = 完整播放
         */
        suspend fun speakStreaming(sentences: kotlinx.coroutines.channels.Channel<String>): Boolean =
            withContext(Dispatchers.IO) {
                val epoch = speakEpoch.get()
                speakMutex.withLock {
                    if (epoch != speakEpoch.get()) {
                        traceManager.record(
                            TAG_TRACE,
                            NodeType.SYSTEM_EVENT,
                            TAG_SRC,
                            "speak_discarded: 排队期间会话已停止/打断",
                        )
                        return@withLock false
                    }
                    // v6.19c 关键复位：清除上次打断遗留的 stopRequested（旧 speakLocked 有此行，
                    // 流式路径漏抄——打断一次后标志恒 true，后续每轮 sentences=0 静音，实测 P0）
                    stopRequested.set(false)
                    isPlaying.set(true)
                    try {
                        speakStreamingLocked(sentences)
                    } finally {
                        isPlaying.set(false)
                    }
                }
            }

        private suspend fun speakStreamingLocked(sentences: kotlinx.coroutines.channels.Channel<String>): Boolean {
            val useMimo = config.engine == "mimo" && !resolveMimoKey().isNullOrBlank()
            return if (useMimo) {
                val ok = speakMimoStreaming(sentences)
                // 降级守卫（同旧 speak 语义）：MiMo 流式中途失败且非打断 → 剩余句走系统 TTS 防哑
                if (!ok && !stopRequested.get() && systemTtsReady) {
                    traceManager.record(
                        TAG_TRACE,
                        NodeType.SYSTEM_EVENT,
                        TAG_SRC,
                        "mimo_stream_fallback_system",
                    )
                    val rest = StringBuilder()
                    for (s in sentences) { // 排空剩余未合成句（等 LLM 流结束，channel 已在 close 时序）
                        if (rest.isNotEmpty()) rest.append('\n')
                        rest.append(s)
                    }
                    mode = TtsMode.SYSTEM_TTS
                    if (rest.isNotBlank()) speakSystem(rest.toString()) else true
                } else {
                    ok
                }
            } else {
                // 非 MiMo（melo/system）：聚合全文整段播（本地引擎合成快，整段足够）
                val full = StringBuilder()
                for (s in sentences) {
                    if (full.isNotEmpty()) full.append('\n')
                    full.append(s)
                }
                speakLocked(full.toString())
            }
        }

        /** 引擎分发主体（speak/speakStreaming 共用；调用方已持 speakMutex 并校验代际） */
        private suspend fun speakLocked(text: String): Boolean {
            traceManager.record(
                TAG_TRACE,
                NodeType.SYSTEM_EVENT,
                TAG_SRC,
                "speak_called: len=${text.length} engine=${config.engine} melo=${melo != null} sys=$systemTtsReady",
            )
            if (text.isBlank()) return true
            stopRequested.set(false)
            isPlaying.set(true)
            return try {
                when (config.engine) {
                    "mimo" -> {
                        val ok = speakMimo(text)
                        // 失败且非打断 → 降级系统 TTS 保证不哑
                        if (!ok && !stopRequested.get() && systemTtsReady) {
                            traceManager.record(
                                TAG_TRACE,
                                NodeType.SYSTEM_EVENT,
                                TAG_SRC,
                                "mimo_fallback_system",
                            )
                            mode = TtsMode.SYSTEM_TTS
                            speakSystem(text)
                        } else {
                            ok
                        }
                    }
                    "melo" -> {
                        val m = melo ?: ensureMelo()
                        if (m != null) {
                            speakMelo(text, m)
                        } else if (systemTtsReady) {
                            speakSystem(text)
                        } else {
                            traceManager.record(TAG_TRACE, NodeType.SYSTEM_EVENT, TAG_SRC, "text_only_fallback")
                            true
                        }
                    }
                    else -> {
                        mode = TtsMode.SYSTEM_TTS
                        speakSystem(text)
                    }
                }
            } finally {
                isPlaying.set(false)
            }
        }

        /**
         * v6.19 MiMo 句级流水线：生产者逐句独立合成（audioChannel 容量 1 = 播放句 N 时
         * 预取合成句 N+1，有界内存）→ 消费者逐块写 AudioTrack（track 连续不重建=句间无缝）。
         */
        private suspend fun speakMimoStreaming(sentences: kotlinx.coroutines.channels.Channel<String>): Boolean =
            kotlinx.coroutines.coroutineScope {
                val key = resolveMimoKeySafe()
                if (key == null) {
                    traceManager.record(TAG_TRACE, NodeType.ERROR, TAG_SRC, "mimo_stream_no_key")
                    return@coroutineScope false
                }
                try {
                    val track = getOrCreateTrack(MimoTtsClient.SAMPLE_RATE)
                    track.play()
                    var totalBytes = 0L
                    var sentenceCount = 0
                    var firstMs = -1L
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    val audioChannel = kotlinx.coroutines.channels.Channel<List<ByteArray>>(capacity = 1)
                    val producer =
                        launch {
                            try {
                                for (s in sentences) {
                                    if (stopRequested.get()) break
                                    val buffers = mutableListOf<ByteArray>()
                                    // N2.4：委托 OpenAiCompatibleClient.ttsStreamPcm（协议/SSE/计量统一，
                                    // 端点/模型经 profile 不再硬编码）；背压/打断语义与旧 MimoTtsClient 逐行一致
                                    onlineSpeech.streamPcm(mimoProfile(), s, { stopRequested.get() }, key) { pcm ->
                                        buffers.add(pcm)
                                    }
                                    if (stopRequested.get()) break
                                    audioChannel.send(buffers) // 容量 1：消费者播放中此处背压等待
                                }
                            } finally {
                                audioChannel.close()
                            }
                        }
                    for (buffers in audioChannel) {
                        sentenceCount++
                        for (pcm in buffers) {
                            if (stopRequested.get()) break
                            if (firstMs < 0) firstMs = android.os.SystemClock.elapsedRealtime() - t0
                            emitFarEnd(track, pcm, MimoTtsClient.SAMPLE_RATE)
                            track.write(pcm, 0, pcm.size)
                            totalBytes += pcm.size
                        }
                    }
                    if (stopRequested.get()) producer.cancel()
                    // 末尾排空（此前各句已由 track 连续性衔接）
                    if (totalBytes > 0) {
                        val frames = totalBytes / 2
                        while (track.playbackHeadPosition < frames && !stopRequested.get()) delay(20)
                    }
                    if (stopRequested.get()) flushTrack()
                    mode = TtsMode.ONLINE_MIMO
                    traceManager.record(
                        TAG_TRACE,
                        NodeType.SYSTEM_EVENT,
                        TAG_SRC,
                        "mimo_stream_tts: sentences=$sentenceCount firstAudio=${firstMs}ms " +
                            "audioMs=${totalBytes / 2 * 1000 / MimoTtsClient.SAMPLE_RATE}",
                    )
                    !stopRequested.get()
                } catch (t: Throwable) {
                    traceManager.record(
                        TAG_TRACE,
                        NodeType.ERROR,
                        TAG_SRC,
                        "mimo_stream_failed: ${t.javaClass.simpleName} ${t.message?.take(60)}",
                    )
                    false
                }
            }

        private fun resolveMimoKeySafe(): String? =
            try {
                resolveMimoKey()
            } catch (_: Throwable) {
                null
            }

        /**
         * MiMo TTS 有效 profile（S1.2-b）：baseUrl/modelId/keyRef/voice 全量经 LlmProfileStore
         * 即时生效（原 N2.4 双轨期取静态 defaults——用户改 profile 不生效，S1.2 收口）。
         * voice 一次性迁移：config.mimoVoice（已退役 ConfigField）的实际值搬入 profile.voice
         * （幂等：迁移后 profile.voice ≠ 出厂默认，条件不再成立；config 留在 mimo_default 不搬）。
         */
        private fun mimoProfile() = onlineSpeech.profile(config)

        /** MiMo 在线流式：SSE pcm16 逐块直写 AudioTrack（写满背压 = 实时速率播放） */
        private suspend fun speakMimo(text: String): Boolean {
            val key = resolveMimoKey()
            if (key.isNullOrBlank()) {
                traceManager.record(
                    TAG_TRACE,
                    NodeType.ERROR,
                    TAG_SRC,
                    "mimo_no_key: 请在 模块管理 → 语音合成 填入 MiMo API Key",
                )
                return false
            }
            try {
                val track = getOrCreateTrack(MimoTtsClient.SAMPLE_RATE)
                var baseHead = -1L
                var totalBytes = 0L
                var lastProbeAt = 0L
                val stats =
                    onlineSpeech.streamPcm(mimoProfile(), text, { stopRequested.get() }, key) { pcm ->
                        if (baseHead < 0) {
                            track.play()
                            baseHead = track.playbackHeadPosition.toLong()
                        }
                        emitFarEnd(track, pcm, MimoTtsClient.SAMPLE_RATE) // D53: far-end 参考（播放对齐）
                        track.write(pcm, 0, pcm.size)
                        totalBytes += pcm.size
                        // D54 静默探针：间隔期满 → 等缓冲全部播完（真静默）→ 探针窗 → 恢复
                        val pl = probeListener
                        if (pl != null && android.os.SystemClock.elapsedRealtime() - lastProbeAt >= PROBE_INTERVAL_MS) {
                            lastProbeAt = android.os.SystemClock.elapsedRealtime()
                            val targetFrames = totalBytes / 2
                            while (track.playbackHeadPosition.toLong() < targetFrames && !stopRequested.get()) delay(25)
                            if (!stopRequested.get()) {
                                pl(true)
                                val until = android.os.SystemClock.elapsedRealtime() + PROBE_MS
                                while (android.os.SystemClock.elapsedRealtime() < until && !stopRequested.get()) delay(50)
                                pl(false)
                            }
                        }
                    }
                mode = TtsMode.ONLINE_MIMO
                traceManager.record(
                    TAG_TRACE,
                    NodeType.SYSTEM_EVENT,
                    TAG_SRC,
                    "mimo_tts: first=${stats.firstChunkMs}ms chunks=${stats.chunks} " +
                        "audioMs=${totalBytes / 2 * 1000 / MimoTtsClient.SAMPLE_RATE} total=${stats.totalMs}ms voice=${config.mimoVoice}",
                )
                // 末尾排空（此前各块已由 write 背压衔接）
                if (baseHead >= 0 && totalBytes > 0) {
                    val frames = totalBytes / 2
                    while (track.playbackHeadPosition - baseHead < frames && !stopRequested.get()) delay(20)
                }
                if (stopRequested.get()) flushTrack()
                return !stopRequested.get()
            } catch (t: Throwable) {
                traceManager.record(
                    TAG_TRACE,
                    NodeType.ERROR,
                    TAG_SRC,
                    "mimo_failed: ${t.javaClass.simpleName} ${t.message?.take(80)}",
                )
                return false
            }
        }

        /** Melo 本地流水线播报：生产者逐句合成 → Channel → 消费者写 AudioTrack（v5.6 管线复用） */
        private suspend fun speakMelo(
            text: String,
            engine: OfflineTts,
        ): Boolean {
            val segments = TtsTextSegmenter.split(text)
            if (segments.isEmpty()) return true
            traceManager.record(
                TAG_TRACE,
                NodeType.SYSTEM_EVENT,
                TAG_SRC,
                "melo_speak: segs=${segments.size} pipeline=true",
            )

            val synthTotalMs = java.util.concurrent.atomic.AtomicLong(0)
            val audioTotalMs = java.util.concurrent.atomic.AtomicLong(0)
            val channel = Channel<Pair<FloatArray, Int>>(capacity = 1)
            var baseHead = -1
            var totalFrames = 0L
            var lastProbeAt = 0L // D54 探针节律（Melo）

            coroutineScope {
                val producer =
                    launch {
                        try {
                            for (seg in segments) {
                                if (stopRequested.get()) break
                                try {
                                    val t0 = android.os.SystemClock.elapsedRealtime()
                                    val audio: GeneratedAudio =
                                        synchronized(nativeLock) {
                                            engine.generate(seg, 0, 1.0f)
                                        }
                                    synthTotalMs.addAndGet(android.os.SystemClock.elapsedRealtime() - t0)
                                    audioTotalMs.addAndGet(audio.samples.size * 1000L / audio.sampleRate)
                                    channel.send(audio.samples to audio.sampleRate)
                                } catch (t: Throwable) {
                                    traceManager.record(
                                        TAG_TRACE,
                                        NodeType.ERROR,
                                        TAG_SRC,
                                        "melo_seg_fail: ${t.message?.take(40)}",
                                    )
                                }
                            }
                        } finally {
                            channel.close()
                        }
                    }

                for (chunk in channel) {
                    if (stopRequested.get()) break
                    val (samples, sampleRate) = chunk
                    val track = getOrCreateTrack(sampleRate)
                    if (baseHead < 0) {
                        track.play()
                        baseHead = track.playbackHeadPosition
                    }
                    val pcm = java.nio.ByteBuffer.allocate(samples.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    for (s in samples) pcm.putShort((s.coerceIn(-1f, 1f) * 32767).toInt().toShort())
                    totalFrames += samples.size
                    emitFarEnd(track, pcm.array(), sampleRate) // D53: far-end 参考（播放对齐）
                    track.write(pcm.array(), 0, pcm.position())
                    // D54 静默探针（Melo 本地引擎同策略）
                    val pl = probeListener
                    if (pl != null && android.os.SystemClock.elapsedRealtime() - lastProbeAt >= PROBE_INTERVAL_MS) {
                        lastProbeAt = android.os.SystemClock.elapsedRealtime()
                        while (track.playbackHeadPosition.toLong() < totalFrames && !stopRequested.get()) delay(25)
                        if (!stopRequested.get()) {
                            pl(true)
                            val until = android.os.SystemClock.elapsedRealtime() + PROBE_MS
                            while (android.os.SystemClock.elapsedRealtime() < until && !stopRequested.get()) delay(50)
                            pl(false)
                        }
                    }
                }
                if (stopRequested.get()) {
                    producer.cancel()
                    flushTrack()
                } else {
                    val track = audioTrack
                    if (track != null && baseHead >= 0) {
                        while (track.playbackHeadPosition - baseHead < totalFrames && !stopRequested.get()) delay(20)
                    }
                    if (stopRequested.get()) flushTrack()
                }
            }

            if (audioTotalMs.get() > 0) {
                val rtf = synthTotalMs.get().toDouble() / audioTotalMs.get()
                traceManager.record(
                    TAG_TRACE,
                    NodeType.SYSTEM_EVENT,
                    TAG_SRC,
                    "melo_rtf: segs=${segments.size} synth=${synthTotalMs.get()}ms " +
                        "audio=${audioTotalMs.get()}ms rtf=${"%.2f".format(rtf)}",
                )
            }
            return !stopRequested.get()
        }

        private suspend fun speakSystem(text: String): Boolean {
            val tts = systemTts ?: return true
            return suspendCancellableCoroutine { cont ->
                tts.setSpeechRate(1.0f)
                tts.setOnUtteranceProgressListener(
                    object : android.speech.tts.UtteranceProgressListener() {
                        override fun onStart(id: String?) {}

                        override fun onDone(id: String?) {
                            if (cont.isActive) cont.resume(true)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) {
                            if (cont.isActive) cont.resume(false)
                        }

                        override fun onStop(
                            id: String?,
                            interrupted: Boolean,
                        ) {
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                )
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "zero_${System.nanoTime()}")
                cont.invokeOnCancellation { tts.stop() }
            }
        }

        // ═══ 播放设施 ═══

        private fun getOrCreateTrack(sampleRate: Int): android.media.AudioTrack {
            if (audioTrack != null && audioTrack!!.sampleRate == sampleRate) return audioTrack!!
            audioTrack?.release()
            val buf =
                android.media.AudioTrack.getMinBufferSize(
                    sampleRate,
                    android.media.AudioFormat.CHANNEL_OUT_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT,
                )
            audioTrack =
                android.media.AudioTrack(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                    android.media.AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).build(),
                    buf * 2, android.media.AudioTrack.MODE_STREAM, 0,
                )
            return audioTrack!!
        }

        /** 立即停止出声：暂停并清空 AudioTrack 缓冲（否则已写入的整段会继续播完） */
        private fun flushTrack() {
            try {
                audioTrack?.pause()
                audioTrack?.flush()
            } catch (_: Exception) {
            }
        }

        fun stop() {
            speakEpoch.incrementAndGet() // 代际 +1：排队中的 speak 获锁后丢弃
            stopRequested.set(true)
            audioTrack?.let {
                try {
                    it.pause()
                    it.flush()
                } catch (_: Exception) {
                }
            }
            systemTts?.stop()
            resetFarQueue() // v6.14: 播放中止，未播参考块作废
        }

        val playing: Boolean get() = isPlaying.get()

        /** 释放本地 Melo 引擎（内存压力降级 / 配置变更；在线与系统引擎不受影响） */
        fun releaseLocalEngine(reason: String) {
            if (melo == null) return
            stopRequested.set(true)
            moduleScope.launch {
                speakMutex.withLock {
                    if (melo == null) return@withLock
                    try {
                        synchronized(nativeLock) { melo?.release() }
                    } catch (_: Throwable) {
                    }
                    melo = null
                    mode = if (systemTtsReady) TtsMode.SYSTEM_TTS else TtsMode.TEXT_ONLY
                    meloLoadStarted.set(false)
                    traceManager.record(
                        TAG_TRACE,
                        NodeType.SYSTEM_EVENT,
                        TAG_SRC,
                        "melo_released: $reason（下次使用时自动重载）",
                    )
                }
            }
        }

        fun release() {
            stop()
            audioTrack?.release()
            audioTrack = null
            try {
                synchronized(nativeLock) { melo?.release() }
            } catch (_: Throwable) {
            }
            melo = null
            systemTts?.shutdown()
            systemTts = null
            systemTtsReady = false
            mode = TtsMode.TEXT_ONLY
            _ready.value = false
        }
    }
