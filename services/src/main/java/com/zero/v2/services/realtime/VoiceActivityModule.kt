package com.zero.v2.services.realtime

import android.content.Context
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.zero.v2.core.moduleapi.ConfigField
import com.zero.v2.core.moduleapi.ConfigFieldType
import com.zero.v2.core.moduleapi.IZeroModule
import com.zero.v2.core.moduleapi.ModuleCategory
import com.zero.v2.core.moduleapi.ModuleConfigBase
import com.zero.v2.core.moduleapi.ModuleLifecycle
import com.zero.v2.core.moduleapi.ModuleMetadata
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** VAD 模块配置 — v6.20 规范化（D17：检测灵敏度可调，实时生效） */
class SpeechVadConfig : ModuleConfigBase() {
    @ConfigField(label = "语音判定阈值", fieldType = ConfigFieldType.NUMBER, min = 0.1, max = 0.9,
        description = "Silero 语音概率门限（越小越灵敏；0.35 为耳机全双工打断推荐值）")
    var speechThreshold: Double = 0.35

    override fun toJson(): String = """{"speechThreshold":$speechThreshold}"""
    override fun fromJson(json: String) {
        try {
            val obj = JSONObject(json)
            speechThreshold = obj.optDouble("speechThreshold", 0.35)
        } catch (_: Exception) {}
    }
    override fun defaultConfig(): ModuleConfigBase = SpeechVadConfig()
}

/**
 * 语音活动检测模块 — v5.0 创建，v6.20 规范化为正式 IZeroModule（设计稿 §3.3 补账）。
 *
 * Silero VAD（~0.6MB）。懒加载：processFrame 首帧实例化（v6.11 修复的死接线）。
 * 降级：振幅阈值。
 */
@Singleton
class VoiceActivityModule @Inject constructor(
    @ApplicationContext private val context: Context,
    private val traceManager: AgentTraceManager
) : IZeroModule {

    companion object {
        const val SAMPLE_RATE = 16000
        private const val SPEECH_FRAME_COUNT = 3
        private const val AMPLITUDE_THRESHOLD = 0.02f
    }

    override val metadata = ModuleMetadata(
        id = "speech_vad",
        displayName = "语音活动检测",
        description = "Silero VAD——耳机全双工打断判定的语音概率检测",
        category = ModuleCategory.INSTRUMENT
    )

    private val _lifecycle = MutableStateFlow(ModuleLifecycle.UNINITIALIZED)
    override val lifecycle = _lifecycle.asStateFlow()
    override var isEnabled: Boolean = true
    override val requiredPermissions: List<String> = emptyList()
    override val config = SpeechVadConfig()

    private var vad: Vad? = null
    private var paths: ModelAssetManager.VadPaths? = null
    private var useNative = false
    private var loadAttempted = false  // v6.11: 防加载失败后逐帧重试风暴
    private var consecutiveSpeech = 0
    private val initialized = AtomicBoolean(false)

    private val _ready = MutableStateFlow(false)
    val ready = _ready.asStateFlow()

    var isSpeechActive = false; private set

    // ═══ IZeroModule 生命周期 ═══

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

    suspend fun initialize() {
        if (!initialized.compareAndSet(false, true)) return
        paths = ModelAssetManager.getVadPaths(context)
        _ready.value = paths != null
        traceManager.record("vad", NodeType.SYSTEM_EVENT, "speech_vad",
            "models_ready: ${paths != null}")
    }

    private fun threshold(): Float = (config as SpeechVadConfig).speechThreshold.toFloat().coerceIn(0.1f, 0.9f)

    /** 懒加载 */
    private fun getVad(): Vad? {
        vad?.let { return it }
        val p = paths ?: return null
        return try {
            val silero = SileroVadModelConfig.builder()
                .setModel(p.model).setThreshold(threshold()).build()
            val config = VadModelConfig.builder()
                .setSileroVadModelConfig(silero).setSampleRate(SAMPLE_RATE).setNumThreads(1).build()
            vad = Vad(config)
            useNative = true
            traceManager.record("vad", NodeType.SYSTEM_EVENT, "speech_vad", "vad_loaded")
            vad
        } catch (t: Throwable) {
            useNative = false
            traceManager.record("vad", NodeType.SYSTEM_EVENT, "speech_vad",
                "vad_fallback: ${t.javaClass.simpleName} → 振幅阈值")
            null
        }
    }

    /** 处理一帧 → 是否检测到语音活动（阈值实时读 config，模块详情页修改即生效） */
    fun processFrame(samples: FloatArray): Boolean {
        // v6.11 P0 根因修复：懒加载接线（曾为死代码，Silero 从未加载）
        if (!useNative && !loadAttempted) {
            loadAttempted = true
            getVad()
        }
        return if (useNative) {
            try {
                val prob = vad!!.compute(samples)
                if (prob > threshold()) consecutiveSpeech++ else consecutiveSpeech = 0
                isSpeechActive = consecutiveSpeech >= SPEECH_FRAME_COUNT
                isSpeechActive
            } catch (_: Exception) {
                useNative = false
                processAmplitude(samples)
            }
        } else processAmplitude(samples)
    }

    private fun processAmplitude(samples: FloatArray): Boolean {
        // v6.11: FloatArray.map→List 装箱逐帧分配——改原位累加（等价计算，零分配）
        var sum = 0.0
        for (s in samples) sum += (s * s).toDouble()
        val rms = kotlin.math.sqrt(sum / samples.size).toFloat()
        if (rms > AMPLITUDE_THRESHOLD) consecutiveSpeech++ else consecutiveSpeech = 0
        isSpeechActive = consecutiveSpeech >= SPEECH_FRAME_COUNT
        return isSpeechActive
    }

    fun reset() {
        consecutiveSpeech = 0
        isSpeechActive = false
        if (useNative) try { vad?.clear() } catch (_: Exception) {}
    }

    fun release() {
        vad?.release(); vad = null; useNative = false; _ready.value = false
    }
}
