# Zero V2 Phase 6 语音引擎重设计（v5.0）

> 依据：2026-08-24 三轮真机实测反馈 + 用户框架化设计要求 + sherpa-onnx JAR API 实际验证
> 替代：ROADMAP §三 Step 6.3~6.5a 中的语音引擎部分
> 2026-08-24

---

## 一、当前问题诊断（三轮实测）

| # | 问题 | 根因 | 影响 |
|---|------|------|------|
| 1 | TTS 模型加载必崩 | 114MB int8 Kokoro + ONNX Runtime 超出进程可用堆（即使 largeHeap） | Live 功能不可用 |
| 2 | 麦克风权限不弹出 | 模型加载在权限请求之前发生 → 加载崩溃时权限流程中断 | 用户无法授权 |
| 3 | 模型复制阻塞 | 446MB assets→filesDir 同步复制（首次 ~40s） → ANR | App 被杀 |
| 4 | 违反 Zero 模块化 | LocalVoiceProvider 是单体（ASR+TTS+VAD+AudioRecord 全在一个类） | 单点故障全局崩溃 |
| 5 | 无降级路径 | TTS 崩溃 → 整个 Live 崩溃 | 无法仅用 ASR |
| 6 | sherpa-onnx JAR 无 AssetManager | 只有文件路径构造器 → 必须先复制到可写目录 | 无法直接从 assets 加载 |

## 二、重设计原则（对标 Alife + Zero 框架规范）

```
                    ┌─────────────────────────────────┐
                    │     LiveSessionService (FGS)     │
                    │  只做编排，不做 ASR/TTS/VAD 业务    │
                    └───────────┬─────────────────────┘
                                │ 注入（Hilt）
                    ┌───────────┼────────────────┐
                    │           │                │
                    ▼           ▼                ▼
              ┌─────────┐ ┌─────────┐  ┌─────────────┐
              │ ASR     │ │ TTS     │  │ VAD         │
              │ Module  │ │ Module  │  │ Module      │
              │(IZeroModule)│(IZeroModule)│(IZeroModule)│
              └────┬────┘ └────┬────┘  └──────┬──────┘
                   │           │              │
                   ▼           ▼              ▼
              Zipformer     Kokoro/系统TTS   Silero
              (流式ASR)    (懒加载/降级)    (VAD)
```

### 核心原则

1. **每引擎独立 IZeroModule**：ASR/TTS/VAD 各一个模块，有自己的 lifecycle/config/错误处理
2. **模型懒加载**：onAwake() 只做模型复制（后台协程），模型实例化推迟到首次使用
3. **TTS 可选降级**：TTS 模型加载失败 → 降级系统 TTS → 再降级纯文本（不影响 ASR）
4. **权限先于服务**：LiveSessionScreen 请求麦克风权限 → 授权后才启动 Service
5. **AudioRecord 由 Provider 统一管理**：单一音频源分发给 ASR + VAD

## 三、模块设计

### 3.1 SpeechRecognitionModule（ASR 模块）

```kotlin
// services/realtime/SpeechRecognitionModule.kt
@Singleton
class SpeechRecognitionModule @Inject constructor(
    @ApplicationContext private val context: Context,
    private val traceManager: AgentTraceManager
) : IZeroModule {

    override val metadata = ModuleMetadata(
        id = "speech_asr",
        displayName = "语音识别",
        description = "本地流式语音识别（sherpa-onnx Zipformer 中英双语）",
        category = ModuleCategory.INSTRUMENT
    )

    // 懒加载的 recognizer
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null

    // 模型文件路径（onAwake 后可用）
    private var encoderPath: String? = null
    private var decoderPath: String? = null
    private var joinerPath: String? = null
    private var tokensPath: String? = null

    override suspend fun onAwake() {
        // 后台复制模型文件（58MB，首次 ~5s）
        val paths = ModelAssetManager.extractModels(context, "asr")
        encoderPath = paths["encoder"]
        decoderPath = paths["decoder"]
        joinerPath = paths["joiner"]
        tokensPath = paths["tokens"]
        registerFunctions() // FC: transcribe_audio
        traceManager.record("asr_module", "models_ready", ...)
    }

    /** 懒加载 recognizer（首次调用时实例化） */
    private fun getRecognizer(): OnlineRecognizer? {
        if (recognizer != null) return recognizer
        return try {
            val transducer = OnlineTransducerModelConfig.builder()
                .setEncoder(encoderPath).setDecoder(decoderPath).setJoiner(joinerPath).build()
            val modelConfig = OnlineModelConfig.builder()
                .setTransducer(transducer).setTokens(tokensPath).setNumThreads(2).build()
            val config = OnlineRecognizerConfig.builder()
                .setOnlineModelConfig(modelConfig).build()
            recognizer = OnlineRecognizer(config)
            stream = recognizer!!.createStream()
            recognizer
        } catch (t: Throwable) {
            traceManager.record("asr_module", "init_failed: ${t.message}")
            null
        }
    }

    /** 处理一帧音频 → 流式 partial/final 结果 */
    fun processAudioChunk(samples: FloatArray, sampleRate: Int): Pair<String, Boolean>? {
        val rec = getRecognizer() ?: return null
        val s = stream ?: return null
        try {
            s.acceptWaveform(samples, sampleRate)
            if (rec.isReady(s)) {
                rec.decode(s)
                val result = rec.getResult(s)
                val isEndpoint = rec.isEndpoint(s)
                if (isEndpoint) rec.reset(s)
                return result.text to isEndpoint
            }
        } catch (_: Exception) {}
        return null
    }

    fun isReady(): Boolean = encoderPath != null
    fun release() { stream?.release(); recognizer?.release(); ... }
}
```

### 3.2 SpeechSynthesisModule（TTS 模块，懒加载 + 降级）

```kotlin
// services/realtime/SpeechSynthesisModule.kt
@Singleton  
class SpeechSynthesisModule @Inject constructor(
    @ApplicationContext private val context: Context,
    private val traceManager: AgentTraceManager
) : IZeroModule {

    // 三级降级：Kokoro native → 系统 TTS → 纯文本
    enum class TtsMode { NATIVE_KOKORO, SYSTEM_TTS, TEXT_ONLY }

    private var mode: TtsMode = TtsMode.TEXT_ONLY
    private var kokoroTts: OfflineTts? = null
    private var systemTts: TextToSpeech? = null

    override suspend fun onAwake() {
        // 后台复制模型（186MB，首次 ~15s）
        // 注意：这里只复制文件，不实例化（懒加载）
        ModelAssetManager.extractModels(context, "tts")
        registerFunctions()
    }

    /** 懒加载：首次 speak() 时才尝试加载 Kokoro */
    private fun ensureKokoro(): Boolean {
        if (kokoroTts != null) return true
        // 尝试加载 → 成功设 mode=NATIVE_KOKORO
        // 失败 → 尝试系统 TTS → 设 mode=SYSTEM_TTS
        // 都失败 → mode=TEXT_ONLY
    }

    suspend fun speak(text: String): Boolean {
        return when (mode) {
            TtsMode.NATIVE_KOKORO -> speakWithKokoro(text)
            TtsMode.SYSTEM_TTS -> speakWithSystem(text)
            TtsMode.TEXT_ONLY -> true // 静默，文本已在 UI 显示
        }
    }

    fun stop() { /* 停止当前播放 */ }
    fun release() { /* 释放所有资源 */ }
}
```

### 3.3 VoiceActivityModule（VAD 模块）

```kotlin
// services/realtime/VoiceActivityModule.kt
@Singleton
class VoiceActivityModule @Inject constructor(
    @ApplicationContext private val context: Context,
    private val traceManager: AgentTraceManager
) : IZeroModule {

    private var vad: Vad? = null
    private var consecutiveSpeechFrames = 0
    var isSpeechActive = false; private set

    override suspend fun onAwake() {
        // 复制 VAD 模型（0.6MB，秒级）
        ModelAssetManager.extractModels(context, "vad")
        registerFunctions()
    }

    /** 懒加载 */
    private fun getVad(): Vad? {
        if (vad != null) return vad
        // 构造 SileroVadModelConfig + VadModelConfig → new Vad(config)
    }

    fun processFrame(samples: FloatArray): Boolean { ... }
    fun reset() { ... }
    fun release() { ... }
}
```

### 3.4 LocalVoiceProvider（编排层，不碰模型）

```kotlin
// services/realtime/LocalVoiceProvider.kt — 纯编排，零模型逻辑
@Singleton
class LocalVoiceProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val asrModule: SpeechRecognitionModule,   // 注入独立模块
    private val ttsModule: SpeechSynthesisModule,
    private val vadModule: VoiceActivityModule,
    private val chatEngine: ChatEngineBridge,
    private val traceManager: AgentTraceManager
) : RealtimeVoiceProvider {

    override suspend fun start() {
        // 1. 检查权限
        if (!hasRecordAudioPermission()) return

        // 2. 检查模块就绪
        if (!asrModule.isReady()) {
            _events.tryEmit(Error("ASR 模型未就绪", false))
            return
        }

        // 3. 启动 AudioRecord 主循环
        startListeningLoop()
    }

    private fun startListeningLoop() {
        scope.launch {
            audioRecord = AudioRecord(...)
            while (active) {
                val chunk = readChunk()

                // VAD 检测（Barge-in）
                val speech = vadModule.processFrame(chunk)
                if (state == SPEAKING && speech) { interrupt() }

                // ASR 处理
                if (state == LISTENING) {
                    val (text, isFinal) = asrModule.processAudioChunk(chunk, SAMPLE_RATE) ?: continue
                    if (isFinal && text.isNotBlank()) processTurn(text)
                }
            }
        }
    }

    private fun processTurn(text: String) {
        scope.launch {
            _state.value = THINKING
            val reply = chatEngine.chatAndCollect(text)
            _events.tryEmit(AssistantText(reply, true))

            _state.value = SPEAKING
            ttsModule.speak(reply)  // TTS 模块自己处理降级

            _state.value = LISTENING
        }
    }
}
```

### 3.5 权限流程修正

```kotlin
// LiveSessionScreen.kt — 权限请求必须在 Service 启动之前
LaunchedEffect(Unit) {
    if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        // 等待用户授权，不自动启动 Service
    }
}

// 只在权限授予后才调用 startSession
fun startSession() {
    if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
        LiveSessionService.start(context)
    }
}
```

## 四、sherpa-onnx 正确 Android 集成

### 4.1 已验证的 API（JAR v1.12.28）

```kotlin
// 全部使用 Builder pattern（setXxx 方法，非 Kotlin DSL）
// 无 AssetManager 构造器 → 必须先复制到可写路径

// ASR
OnlineTransducerModelConfig.builder().setEncoder(path).setDecoder(path).setJoiner(path).build()
OnlineModelConfig.builder().setTransducer(transducer).setTokens(path).setNumThreads(2).build()
OnlineRecognizerConfig.builder().setOnlineModelConfig(modelConfig).build()
OnlineRecognizer(config)  →  .createStream()  →  stream.acceptWaveform(FloatArray, sampleRate)
                             .decode(stream)   →  .getResult(stream).text
                             .isEndpoint(stream)  →  .reset(stream)

// TTS
OfflineTtsKokoroModelConfig.builder().setModel(path).setVoices(path).setTokens(path).setDataDir(path).build()
OfflineTtsModelConfig.builder().setKokoro(kokoro).setNumThreads(2).setDebug(false).build()
OfflineTtsConfig.builder().setModel(modelConfig).build()
OfflineTts(config)  →  .generate(text, speakerId, speed)  →  GeneratedAudio.getSamples() / getSampleRate()

// VAD
SileroVadModelConfig.builder().setModel(path).setThreshold(0.5f).build()
VadModelConfig.builder().setSileroVadModelConfig(silero).setSampleRate(16000).setNumThreads(1).build()
Vad(config)  →  .compute(FloatArray)  →  Float (speech probability)
```

### 4.2 模型资产管理

```kotlin
// ModelAssetManager — 每模块独立提取，带进度
object ModelAssetManager {
    /** 仅提取指定模块的模型（不阻塞其他模块） */
    suspend fun extractModels(context: Context, module: String): Map<String, String> {
        val base = File(context.filesDir, "sherpa/$module")
        if (isExtracted(base)) return getPaths(base)

        return withContext(Dispatchers.IO) {
            copyAssetDir(context, "models/$module", base)
            getPaths(base)
        }
    }
}
```

### 4.3 模型配置（用户主权 D17）

```kotlin
// SpeechModuleConfig — 所有参数 ConfigField
class SpeechModuleConfig : ModuleConfigBase() {
    @ConfigField(label = "TTS 引擎", DROPDOWN, options = ["auto", "kokoro", "system"])
    var ttsEngine: String = "auto"

    @ConfigField(label = "Kokoro 声音 ID", NUMBER)
    var speakerId: Int = 50  // zf_xiaoxiao 中文女声

    @ConfigField(label = "ASR 线程数", NUMBER)
    var asrThreads: Int = 2

    @ConfigField(label = "语音识别语言", DROPDOWN, options = ["auto", "zh", "en"])
    var asrLanguage: String = "auto"
}
```

## 五、实施 Step 重定义

| Step | 内容 | 依赖 |
|------|------|------|
| 6.3-R1 | SpeechRecognitionModule（ASR 独立模块 + FC + 懒加载） | sherpa-onnx JAR |
| 6.3-R2 | SpeechSynthesisModule（TTS 三级降级 + 懒加载） | 6.3-R1 |
| 6.3-R3 | VoiceActivityModule（VAD 独立模块） | 无 |
| 6.3-R4 | LocalVoiceProvider 重写（纯编排，注入三模块） | 6.3-R1~R3 |
| 6.4-R | LiveSessionService + LiveSessionScreen 适配 | 6.3-R4 |
| 6.5-R | 权限流程修正（麦克风权限先于 Service 启动） | 6.4-R |

## 六、与旧代码的清理

| 删除 | 原因 |
|------|------|
| `VoiceCascadeModule.kt` | D48 已废弃，被新模块替代 |
| `LocalVoiceProvider.kt`（旧版） | 单体设计，被编排层+独立模块替代 |
| `VadDetector.kt`（旧版） | 被 VoiceActivityModule 替代 |
| `LocalTtsPlayer.kt`（旧版） | 被 SpeechSynthesisModule 替代 |
| `ModelAssetManager.kt`（旧版） | 重写为按模块提取 + 进度 |

## 七、验证矩阵

| 场景 | 预期行为 |
|------|---------|
| 模型全部就绪 | 流式 ASR + Kokoro TTS + VAD Barge-in |
| TTS 模型加载失败 | ASR 正常 → 系统 TTS 降级 → UI 仍显示文本 |
| ASR 模型加载失败 | Live 显示错误提示，不崩溃 |
| 麦克风权限拒绝 | 不启动 Service，UI 提示授权 |
| 飞行模式 | ASR + TTS 全本地工作（LLM 需网络） |
| 模型未复制完成 | 显示"模型准备中..."进度 |
