package com.zero.v2.app.music

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.zero.v2.core.common.ActionPermission
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.data.dao.MusicDao
import com.zero.v2.core.data.entity.MusicTrackEntity
import com.zero.v2.core.data.entity.PlaylistEntity
import com.zero.v2.core.data.entity.PlaylistItemEntity
import com.zero.v2.core.data.entity.ScheduledPlaybackEntity
import com.zero.v2.core.moduleapi.ConfigField
import com.zero.v2.core.moduleapi.ConfigFieldType
import com.zero.v2.core.moduleapi.IZeroModule
import com.zero.v2.core.moduleapi.ModuleCategory
import com.zero.v2.core.moduleapi.ModuleConfigBase
import com.zero.v2.core.moduleapi.ModuleLifecycle
import com.zero.v2.core.moduleapi.ModuleMetadata
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import com.zero.v2.app.background.MusicService
import com.zero.v2.app.data.WorkDirRepository
import com.zero.v2.services.agent.AgentJob
import com.zero.v2.services.agent.AgentRuntime
import com.zero.v2.services.agent.ExactAlarmScheduler
import com.zero.v2.services.agent.TriggerHub
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 音乐轨路径常量 — MD15（设计 §九 薄约定）。
 *
 * 音乐库目录 = 工译期常量，**不入配置**：目录名配置化会使库内 relative_path 全量失效
 * （改配置=整库报废）；"音乐放 llmmusic"是约定不是偏好；单一事实源。
 */
object MusicPaths {
    const val SUBDIR = "llmmusic"

    /** 工作目录根相对全路径（物理操作经 WorkDirRepository 通用原语时使用） */
    fun inSubdir(relativePath: String): String = "$SUBDIR/$relativePath"
}

/**
 * 播放状态快照（MU2.2 · 设计 §8.2a）——MusicModule 单例唯一事实源；
 * MusicService collect → 通知，MusicViewModel collect → now-playing UI（MU2.3）。
 * positionMs 为事件点快照（listener 触发时刷新）；精确值经 music_status FC 实时读 player。
 */
data class PlaybackState(
    val isPlaying: Boolean = false,
    val trackId: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val relativePath: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val queueSize: Int = 0,
    val queueIndex: Int = -1,
    val shuffle: Boolean = false,
    /** Player.REPEAT_MODE_OFF / REPEAT_MODE_ONE / REPEAT_MODE_ALL */
    val repeatMode: Int = Player.REPEAT_MODE_OFF
)

class MusicModuleConfig : ModuleConfigBase() {
    @ConfigField(label = "启动自动扫描", fieldType = ConfigFieldType.TOGGLE,
        description = "模块启动时自动增量扫描音乐库（后台，不打扰）")
    var autoScanOnStart: Boolean = true

    @ConfigField(label = "扫描间隔（分钟）", fieldType = ConfigFieldType.NUMBER,
        min = 10.0, max = 1440.0,
        description = "周期扫描的最小间隔（增量扫描，未变化的文件跳过元数据抽取）")
    var scanIntervalMin: Int = 60

    @ConfigField(label = "默认音量", fieldType = ConfigFieldType.SLIDER,
        min = 0.0, max = 100.0,
        description = "播放起始音量（百分比）")
    var defaultVolume: Int = 70

    @ConfigField(label = "语音时暂停音乐", fieldType = ConfigFieldType.TOGGLE,
        description = "语音会话期间自动暂停音乐（音频焦点协调）")
    var audioDuckDuringVoice: Boolean = true

    @ConfigField(label = "AUTO 定时日上限", fieldType = ConfigFieldType.NUMBER,
        min = 1.0, max = 50.0,
        description = "AUTO 定时播放每日最多触发次数（超顶拒绝并记录，另受全局事件上限约束）")
    var scheduledAutoCap: Int = 10

    override fun toJson(): String =
        """{"autoScanOnStart":$autoScanOnStart,"scanIntervalMin":$scanIntervalMin,""" +
            """"defaultVolume":$defaultVolume,"audioDuckDuringVoice":$audioDuckDuringVoice,""" +
            """"scheduledAutoCap":$scheduledAutoCap}"""

    override fun fromJson(json: String) {
        try {
            val obj = JSONObject(json)
            // coerceIn 兜底：防直改存储 JSON 绕过 UI 界限（§7.3）
            autoScanOnStart = obj.optBoolean("autoScanOnStart", true)
            scanIntervalMin = obj.optInt("scanIntervalMin", 60).coerceIn(10, 1440)
            defaultVolume = obj.optInt("defaultVolume", 70).coerceIn(0, 100)
            audioDuckDuringVoice = obj.optBoolean("audioDuckDuringVoice", true)
            scheduledAutoCap = obj.optInt("scheduledAutoCap", 10).coerceIn(1, 50)
        } catch (_: Exception) {}
    }

    override fun defaultConfig(): ModuleConfigBase = MusicModuleConfig()
}

/**
 * 音乐模块 — MU1.2 骨架（库与文件域；播放随 MU2、歌单 MU3、调度 MU4）。
 *
 * 路径模型（MD13）：曲目以 relative_path（相对 llmmusic/ 的含子目录路径）为唯一物理
 * 定位键，display_title 只展示。物理操作复用 S9.1 已落地原语（moveEntry/deleteFile/
 * listTree，M13 勘误——不新造 subdir 方法集），DB 同步是本模块的增值。
 *
 * 扫描=最终一致性修复机制（MD16）：递归子树 + 增量三元组 (relative_path, file_size,
 * modified_at)；缺失 miss_count+1，连续 ≥2 才物理删行（防 SAF 抖动清库）；SAF 成功
 * Room 失败 → trace + 立即 reconcile 重扫（物理优先，不做伪事务）。
 */
@Singleton
class MusicModule @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val workDir: WorkDirRepository,
    private val musicDao: MusicDao,

    private val systemBus: com.zero.v2.core.bus.SystemBus,
    private val exactAlarmScheduler: ExactAlarmScheduler,
    private val agentRuntime: AgentRuntime,
    /** MU5.1：AUTO 触发（app→services 一向依赖，SR4 无 cycle） */
    private val triggerHub: TriggerHub,
    private val traceManager: AgentTraceManager
) : IZeroModule, com.zero.v2.core.moduleapi.ToolContributor, AgentJob {

    companion object {
        private const val TRACE_ID = "music"
        /** 连续缺失 ≥2 次才物理删行（M8） */
        private const val MISS_PURGE_THRESHOLD = 2
        private val AUDIO_EXTS = setOf("mp3", "m4a", "flac", "wav", "ogg", "aac", "opus", "wma")
        /** G6 恢复期陈旧判定宽容窗：迟到 ≤5min 仍照播（进程刚重启场景） */
        private const val STALE_GRACE_MS = 5 * 60_000L
        /** MU5.1 第一道门计数存储（音乐域 AUTO 日额度，M5） */
        private const val QUOTA_PREFS = "zero_music_auto_quota"
        private const val KEY_QUOTA_DAY = "day"
        private const val KEY_QUOTA_COUNT = "count"
    }

    private val autoQuotaPrefs by lazy {
        appContext.getSharedPreferences(QUOTA_PREFS, Context.MODE_PRIVATE)
    }

    // ═══ AgentJob（MU4.2b · 设计 §四/§7.1）：Scheduler 轮询驱动 + ExactAlarm/kick 双路径═══
    override val id: String = "music_scheduled"
    override var nextRunTime: Long = Long.MAX_VALUE
    /** 进程内 kick 快路径 job（scheduleNextMusicAlarm 重排时取消旧的） */
    private var wakeJob: Job? = null

    override val metadata = ModuleMetadata(
        id = "music",
        displayName = "音乐",
        description = "本地音乐库（llmmusic/ 子树）：扫描入库、检索、文件管理；播放与定时随后续版本",
        category = ModuleCategory.SYSTEM,
        launchOrder = 120
    )

    private val _lifecycle = MutableStateFlow(ModuleLifecycle.UNINITIALIZED)
    override val lifecycle = _lifecycle.asStateFlow()
    override var isEnabled: Boolean = true
    override val requiredPermissions: List<String> = emptyList()
    override val config = MusicModuleConfig()

    private val moduleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** DB 写串行（安全守卫 #8：Mutex + DAO 单写者） */
    private val dbMutex = Mutex()

    // ═══ MU2.2 播放核心（设计 §8.2a/§8.3/§8.4）═══

    /** 播放引擎主线程域（ExoPlayer 要求 main looper——全部访问经 withPlayer 收敛） */
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** lazy ExoPlayer（首次经 withPlayer 在主线程内构造；isInitialized 供 onDestroy 探测） */
    private val playerDelegate = lazy {
        ExoPlayer.Builder(appContext).build().also { p -> p.addListener(playerListener) }
    }
    private val player: ExoPlayer by playerDelegate

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = refreshPlaybackState()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            refreshPlaybackState()
            // MU3.2（MU2.2 补账接线）：曲目切换即计一次播放（含 setMediaItems 初始与
            // 单曲循环 MEDIA_ITEM_REPEAT；暂停恢复不触发 transition 不重复计）
            mediaItem?.mediaId?.takeIf { it.isNotBlank() }?.let { tid ->
                moduleScope.launch {
                    runCatching { musicDao.incrementPlayCount(tid, System.currentTimeMillis()) }
                        .onFailure {
                            traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id,
                                "play_count_failed: $tid ${it.message?.take(30)}")
                        }
                }
            }
        }
        override fun onPlaybackStateChanged(state: Int) {
            refreshPlaybackState()
            // v1 语义：队列播完（ENDED 且非循环）→ 停前台服务（§8.2a 停止同理）
            if (state == Player.STATE_ENDED &&
                player.repeatMode == Player.REPEAT_MODE_OFF && !player.shuffleModeEnabled
            ) {
                traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                    "queue_ended → 停止前台服务")
                runCatching { MusicService.stop(appContext) }
            }
        }
        override fun onShuffleModeEnabledChanged(enabled: Boolean) = refreshPlaybackState()
        override fun onRepeatModeChanged(mode: Int) = refreshPlaybackState()
    }

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val audioManager by lazy {
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    /** C2 裁决：仅 AudioFocus 协调（不注入 LocalVoiceProvider；gap 留 MU2.3 真机验） */
    @Volatile private var resumeOnFocusGain = false

    /** 焦点永久丢失（LOSS）标记——语音结束恢复前检查，不抢已失焦的播放权 */
    @Volatile private var focusPermanentlyLost = false

    /** onVoiceActive 保险层：因语音会话暂停的音乐，会话结束自动恢复 */
    @Volatile private var voicePausedPlayback = false

    private val focusRequest: AudioFocusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS -> {
                        resumeOnFocusGain = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                        if (change == AudioManager.AUDIOFOCUS_LOSS) focusPermanentlyLost = true
                        if (player.isPlaying) {
                            mainScope.launch { runCatching { player.pause() } }
                            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                                "audio_focus_lost: ${if (change == AudioManager.AUDIOFOCUS_LOSS) "永久" else "瞬时"} → 暂停")
                        }
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        focusPermanentlyLost = false
                        if (resumeOnFocusGain && !player.isPlaying) {
                            resumeOnFocusGain = false
                            mainScope.launch { runCatching { player.play() } }
                            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                                "audio_focus_regained → 续播")
                        }
                    }
                }
            }
            .build()
    }

    /** 主线程收敛的播放引擎访问口（lazy 首次构造也在此发生——主线程内安全；
     *  异常向上抛，调用方 try-catch 留 trace（E28）） */
    private suspend fun <T> withPlayer(block: ExoPlayer.() -> T): T =
        withContext(Dispatchers.Main) { player.block() }

    /** 事件点状态快照刷新（主线程 listener 回调内调用） */
    private fun refreshPlaybackState() {
        val p = player
        _playbackState.value = PlaybackState(
            isPlaying = p.isPlaying,
            trackId = p.currentMediaItem?.mediaId,
            title = p.currentMediaItem?.mediaMetadata?.title?.toString(),
            artist = p.currentMediaItem?.mediaMetadata?.artist?.toString(),
            relativePath = (p.currentMediaItem?.localConfiguration?.tag as? String),
            positionMs = p.currentPosition,
            durationMs = p.duration.coerceAtLeast(0),
            queueSize = p.mediaItemCount,
            queueIndex = p.currentMediaItemIndex,
            shuffle = p.shuffleModeEnabled,
            repeatMode = p.repeatMode
        )
    }

    /** MD17 统一前台入口：三入口（FC/UI/调度）全部经 play 起播路径先过这里，幂等 */
    private fun ensurePlaybackService() {
        runCatching { MusicService.start(appContext) }
            .onFailure {
                traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id,
                    "ensure_service_failed: ${it.message?.take(40)}")
            }
    }

    /**
     * MU2.3（now-playing UI 进度）：播放引擎实时位置出口 (positionMs, durationMs)。
     * 无队列/引擎未构造返回 null——UI ticker 轮询用（PlaybackState 为事件点快照，
     * 连续进度经本出口实时读，与 music_status FC 同源）。
     */
    suspend fun playbackPosition(): Pair<Long, Long>? {
        if (!playerDelegate.isInitialized()) return null
        return try {
            withPlayer {
                if (mediaItemCount == 0) null
                else currentPosition to duration.coerceAtLeast(0)
            }
        } catch (_: Exception) { null }
    }

    private fun requestFocus() {
        runCatching { audioManager.requestAudioFocus(focusRequest) }
    }

    private fun releaseFocus() {
        runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
    }

    override suspend fun onAwake() {
        _lifecycle.value = ModuleLifecycle.AWAKE
        // 静态工具由 ModuleLifecycleManager 在 onAwake 后集中读取 contributions。

        val cfg = config as MusicModuleConfig
        if (cfg.autoScanOnStart && workDir.hasDirectory()) {
            moduleScope.launch {
                runCatching { doScan(trigger = "auto_start") }
                    .onFailure { traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id,
                        "auto_scan_failed: ${it.javaClass.simpleName} ${it.message?.take(50)}") }
            }
        }
    }

    override suspend fun onStart() {
        _lifecycle.value = ModuleLifecycle.STARTED
        // MU4.2b（设计 §7.3 · G6 恢复）：进程重启后 Room 恢复调度 + 陈旧判定 + 重排闹钟
        moduleScope.launch {
            runCatching { restoreSchedules() }
                .onFailure { traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id,
                    "schedule_restore_failed: ${it.javaClass.simpleName} ${it.message?.take(40)}") }
            runCatching { execute() }
                .onFailure { traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id,
                    "execute_failed_on_start: ${it.message?.take(40)}") }
        }
        // MU4.2b（C1/M2）：闹钟 tick 订阅——到期扫描认领（与 Scheduler 路径幂等，MD14）
        moduleScope.launch {
            systemBus.onMusicDue.collect {
                runCatching { execute() }
                    .onFailure { traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id,
                        "execute_failed_on_tick: ${it.message?.take(40)}") }
            }
        }
        // MU2.3-fix（C2 fallback · L3 实测 TTS 期间音乐不暂停）：语音会话状态订阅——
        // AudioFocus 之外的保险层（与焦点恢复标志独立：语音结束且未被焦点永久丢失时恢复）
        moduleScope.launch {
            systemBus.onVoiceActive.collect { active ->
                if (active) {
                    // isPlaying 判定收敛主线程（player 访问纪律）
                    if (playerDelegate.isInitialized()) {
                        val wasPlaying = runCatching {
                            withPlayer { isPlaying }
                        }.getOrDefault(false)
                        if (wasPlaying) {
                            voicePausedPlayback = true
                            runCatching { withPlayer { pause() } }
                            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                                "voice_session_started → 音乐暂停（onVoiceActive 保险层）")
                        }
                    }
                } else if (voicePausedPlayback) {
                    voicePausedPlayback = false
                    // 焦点永久丢失（如他方音乐占台）则不抢恢复
                    if (!focusPermanentlyLost) {
                        runCatching { withPlayer { play() } }
                        traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                            "voice_session_ended → 音乐恢复")
                    } else {
                        traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                            "voice_session_ended: 焦点已永久丢失，不自动恢复")
                    }
                }
            }
        }
    }
    override suspend fun onDestroy() {
        moduleScope.cancel()
        // 设计 §四：释放 ExoPlayer + 音频焦点（未初始化则免——lazy 探测）
        if (playerDelegate.isInitialized()) {
            withContext(Dispatchers.Main) {
                runCatching { player.release() }
                releaseFocus()
            }
        }
        mainScope.cancel()
        _lifecycle.value = ModuleLifecycle.DESTROYED
    }
    override suspend fun onConfigChanged(newConfig: ModuleConfigBase) {}
    override suspend fun checkAvailability(): Boolean = true
    override fun markDisabled() { _lifecycle.value = ModuleLifecycle.DISABLED }

    override fun contributions(): List<com.zero.v2.core.moduleapi.ToolContribution> =
        com.zero.v2.core.moduleapi.toolDeclarations(metadata.id) {
        val functionRegistry = this
        // ═══ 库（MU1.2 · 读类 AUTO）═══
        functionRegistry.register(
            FunctionDefinition(
                name = "music_scan",
                description = "递归扫描工作目录 llmmusic/ 子树的音乐文件入库（含任意深度子目录）。" +
                        "增量：按 路径+大小+修改时间 判断变化，未变化的跳过元数据抽取；" +
                        "连续两次扫描缺失的曲目才从库中移除。首次使用或新增/替换音乐文件后调用。",
                parameters = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
            ),
            metadata.id, permission = ActionPermission.AUTO
        ) { scanLibrary() }

        functionRegistry.register(
            FunctionDefinition(
                name = "music_list",
                description = "列出音乐库曲目（可按艺术家/专辑过滤，可限条数）。" +
                        "返回每首的 id、相对路径、标题、艺术家、时长——播放/歌单操作以 id 引用。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "artist" to mapOf("type" to "string", "description" to "艺术家过滤（包含匹配，可选）"),
                        "album" to mapOf("type" to "string", "description" to "专辑过滤（包含匹配，可选）"),
                        "limit" to mapOf("type" to "number", "description" to "最多返回条数（默认 50）")
                    )
                )
            ),
            metadata.id, permission = ActionPermission.AUTO
        ) { args ->
            val artist = args["artist"] as? String
            val album = args["album"] as? String
            val limit = ((args["limit"] as? Number)?.toInt() ?: 50).coerceIn(1, 200)
            listTracks(artist, album, limit)
        }

        functionRegistry.register(
            FunctionDefinition(
                name = "music_search",
                description = "按标题或艺术家模糊搜索音乐库。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "query" to mapOf("type" to "string", "description" to "搜索词"),
                        "limit" to mapOf("type" to "number", "description" to "最多返回条数（默认 20）")
                    ),
                    "required" to listOf("query")
                )
            ),
            metadata.id, permission = ActionPermission.AUTO
        ) { args ->
            val query = args["query"] as? String ?: ""
            val limit = ((args["limit"] as? Number)?.toInt() ?: 20).coerceIn(1, 100)
            searchTracks(query, limit)
        }

        // ═══ 文件（MU1.2 · 写类 CONFIRM）═══
        functionRegistry.register(
            FunctionDefinition(
                name = "music_rename",
                description = "重命名或移动 llmmusic/ 内的音乐文件，并同步音乐库。参数为相对 llmmusic 的路径" +
                        "（如 Anime/A.mp3）；new_relative_path 含 '/' 即移动到子目录（目标仍在 llmmusic 子树内）。" +
                        "目标已存在时拒绝（不覆盖）。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "old_relative_path" to mapOf("type" to "string", "description" to "现有相对路径"),
                        "new_relative_path" to mapOf("type" to "string",
                            "description" to "新相对路径（重命名：Anime/B.mp3；移动：Game/A.mp3）")
                    ),
                    "required" to listOf("old_relative_path", "new_relative_path")
                )
            ),
            metadata.id, permission = ActionPermission.CONFIRM
        ) { args ->
            val old = args["old_relative_path"] as? String ?: ""
            val new = args["new_relative_path"] as? String ?: ""
            renameTrack(old.trim(), new.trim())
        }

        functionRegistry.register(
            FunctionDefinition(
                name = "music_delete",
                description = "删除 llmmusic/ 内的音乐文件并从音乐库移除（不可恢复）。" +
                        "参数为相对 llmmusic 的路径；回执附受影响歌单数。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "relative_path" to mapOf("type" to "string", "description" to "要删除的相对路径")
                    ),
                    "required" to listOf("relative_path")
                )
            ),
            metadata.id, permission = ActionPermission.CONFIRM
        ) { args -> deleteTrack((args["relative_path"] as? String ?: "").trim()) }

        // ═══ 播放（MU2.2 · 播放控制 AUTO / set_queue CONFIRM · §六表）═══
        functionRegistry.register(
            FunctionDefinition(
                name = "music_play",
                description = "播放曲目：track_id 单曲 / track_ids 队列 / playlist_id 歌单按序播放。" +
                        "曲目 id 用 music_list/music_search 返回的**完整 id**（形如 36 位 UUID）。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "track_id" to mapOf("type" to "string", "description" to "单曲播放的曲目 id"),
                        "track_ids" to mapOf("type" to "array",
                            "items" to mapOf("type" to "string"),
                            "description" to "队列播放的曲目 id 列表（按序）"),
                        "playlist_id" to mapOf("type" to "string", "description" to "歌单 id（后续版本）")
                    )
                )
            ),
            metadata.id, permission = ActionPermission.AUTO
        ) { args -> playTracks(args) }

        functionRegistry.register(
            FunctionDefinition(
                name = "music_pause",
                description = "暂停播放（保留队列与进度，music_resume 续播）。",
                parameters = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
            ),
            metadata.id, permission = ActionPermission.AUTO
        ) { playerControl("pause") { pause() } }

        functionRegistry.register(
            FunctionDefinition(
                name = "music_resume",
                description = "继续播放（从暂停处恢复）。",
                parameters = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
            ),
            metadata.id, permission = ActionPermission.AUTO
        ) { playerControl("resume") { play() } }

        functionRegistry.register(
            FunctionDefinition(
                name = "music_next",
                description = "切到队列下一首（随机模式下沿随机顺序）。队列末尾时如实报错（可 music_repeat(all) 或重选曲）。",
                parameters = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
            ),
            metadata.id, permission = ActionPermission.AUTO
        ) { skipTo("next") }

        functionRegistry.register(
            FunctionDefinition(
                name = "music_prev",
                description = "切到队列上一首（随机模式下沿随机顺序）。队列开头时如实报错。",
                parameters = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
            ),
            metadata.id, permission = ActionPermission.AUTO
        ) { skipTo("prev") }

        functionRegistry.register(
            FunctionDefinition(
                name = "music_seek",
                description = "拖动到指定位置。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "position_ms" to mapOf("type" to "number", "description" to "目标位置（毫秒）")
                    ),
                    "required" to listOf("position_ms")
                )
            ),
            metadata.id, permission = ActionPermission.AUTO
        ) { args ->
            val pos = (args["position_ms"] as? Number)?.toLong() ?: -1L
            if (pos < 0) FunctionResult.failure("position_ms 非法")
            else playerControl("seek:$pos") { seekTo(pos) }
        }

        functionRegistry.register(
            FunctionDefinition(
                name = "music_status",
                description = "查询播放状态：当前曲（标题/艺术家/路径/id）、位置、时长、是否播放、队列长度与序号、随机/循环模式。",
                parameters = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
            ),
            metadata.id, permission = ActionPermission.AUTO
        ) { playbackStatus() }

        functionRegistry.register(
            FunctionDefinition(
                name = "music_set_queue",
                description = "设置播放队列顺序（替换当前队列）。有副作用需确认。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "track_ids" to mapOf("type" to "array",
                            "items" to mapOf("type" to "string"),
                            "description" to "按序的曲目 id 列表")
                    ),
                    "required" to listOf("track_ids")
                )
            ),
            metadata.id, permission = ActionPermission.CONFIRM
        ) { args ->
            // fix（L3 实测 09-12）：JSONArray 兼容（parseToolArguments 产物非 List）
            val ids = stringListArg(args["track_ids"]) ?: emptyList()
            if (ids.isEmpty()) FunctionResult.failure("track_ids 不能为空")
            else startQueue(ids, play = false)
        }

        functionRegistry.register(
            FunctionDefinition(
                name = "music_shuffle",
                description = "随机播放开关。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "enabled" to mapOf("type" to "boolean", "description" to "开/关随机")
                    ),
                    "required" to listOf("enabled")
                )
            ),
            metadata.id, permission = ActionPermission.AUTO
        ) { args ->
            val enabled = args["enabled"] as? Boolean ?: return@register FunctionResult.failure("enabled 需为布尔值")
            playerControl("shuffle:$enabled") { shuffleModeEnabled = enabled }
        }

        functionRegistry.register(
            FunctionDefinition(
                name = "music_repeat",
                description = "循环模式：off 不循环 / all 全队列循环 / one 单曲循环。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "mode" to mapOf("type" to "string", "description" to "off / all / one")
                    ),
                    "required" to listOf("mode")
                )
            ),
            metadata.id, permission = ActionPermission.AUTO
        ) { args ->
            val mode = (args["mode"] as? String ?: "").lowercase()
            val m = MusicPlaybackPolicy.repeatMode(mode)
                ?: return@register FunctionResult.failure("mode 仅支持 off/all/one")
            playerControl("repeat:$mode") { repeatMode = m }
        }

        // ═══ 歌单（MU3.2 · M16 勘误：playlist_create=AUTO——MD4 自主建歌单裁定所必需，
        //     创建无破坏性可逆；delete/add/remove_track 改既有歌单保持 CONFIRM）═══
        functionRegistry.register(
            FunctionDefinition(
                name = "playlist_create",
                description = "创建歌单（可带初始曲目）。创建无破坏性；删除/改单另有确认。" +
                        "Zero 自主建单时建议传 source_prompt 记录意图（如定时播放的 hint）。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "name" to mapOf("type" to "string", "description" to "歌单名"),
                        "from_track_ids" to mapOf("type" to "array",
                            "items" to mapOf("type" to "string"),
                            "description" to "初始曲目 id 列表（按序，可选）"),
                        "source_prompt" to mapOf("type" to "string",
                            "description" to "建单意图（LLM 自主建单时传，可选）")
                    ),
                    "required" to listOf("name")
                )
            ),
            metadata.id, permission = ActionPermission.AUTO
        ) { args -> createPlaylist(args) }

        functionRegistry.register(
            FunctionDefinition(
                name = "playlist_list",
                description = "列出全部歌单（id、名称、曲数、来源意图、创建时间）。",
                parameters = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
            ),
            metadata.id, permission = ActionPermission.AUTO
        ) { listPlaylists() }

        functionRegistry.register(
            FunctionDefinition(
                name = "playlist_delete",
                description = "删除歌单（仅删歌单与其曲目引用，不删音乐文件；不可恢复）。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "playlist_id" to mapOf("type" to "string", "description" to "歌单 id")
                    ),
                    "required" to listOf("playlist_id")
                )
            ),
            metadata.id, permission = ActionPermission.CONFIRM
        ) { args -> deletePlaylist((args["playlist_id"] as? String ?: "").trim()) }

        functionRegistry.register(
            FunctionDefinition(
                name = "playlist_add_track",
                description = "向歌单添加曲目（缺省追加到末尾，可指定插入位置）。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "playlist_id" to mapOf("type" to "string", "description" to "歌单 id"),
                        "track_id" to mapOf("type" to "string", "description" to "曲目 id"),
                        "position" to mapOf("type" to "number", "description" to "插入位置（0 起，缺省末尾）")
                    ),
                    "required" to listOf("playlist_id", "track_id")
                )
            ),
            metadata.id, permission = ActionPermission.CONFIRM
        ) { args ->
            addTrackToPlaylist(
                (args["playlist_id"] as? String ?: "").trim(),
                (args["track_id"] as? String ?: "").trim(),
                (args["position"] as? Number)?.toInt())
        }

        functionRegistry.register(
            FunctionDefinition(
                name = "playlist_remove_track",
                description = "从歌单移除曲目（不移除音乐文件）。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "playlist_id" to mapOf("type" to "string", "description" to "歌单 id"),
                        "track_id" to mapOf("type" to "string", "description" to "曲目 id")
                    ),
                    "required" to listOf("playlist_id", "track_id")
                )
            ),
            metadata.id, permission = ActionPermission.CONFIRM
        ) { args ->
            removeTrackFromPlaylist(
                (args["playlist_id"] as? String ?: "").trim(),
                (args["track_id"] as? String ?: "").trim())
        }

        // ═══ 定时（MU4.2b · §六 定时表）═══
        functionRegistry.register(
            FunctionDefinition(
                name = "music_schedule",
                description = "设置定时播放。mode=FIXED 直播指定歌单（零 token，到点即播）；" +
                        "mode=AUTO 到点由 Zero 按 hint 自主选曲建歌单播放（受音乐日额度与全局触发上限约束，" +
                        "触顶跳过并留痕）。time 支持 +90s/+5m/+2h 相对、21:30 时刻（今日已过自动明天）、" +
                        "2026-09-13 08:00 完整时间。拒绝过去时刻。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "time" to mapOf("type" to "string", "description" to "触发时间（相对/时刻/完整时间）"),
                        "playlist_id" to mapOf("type" to "string", "description" to "[FIXED] 直播的歌单 id"),
                        "mode" to mapOf("type" to "string", "description" to "FIXED / AUTO（默认 FIXED）"),
                        "hint" to mapOf("type" to "string", "description" to "[AUTO] 建单意图（如 学习轻音乐）"),
                        "recurring" to mapOf("type" to "boolean", "description" to "是否周期（默认 false）"),
                        "interval_seconds" to mapOf("type" to "number",
                            "description" to "[周期] 间隔秒（recurring=true 时必填，如 86400=每日）")
                    ),
                    "required" to listOf("time")
                )
            ),
            metadata.id, permission = ActionPermission.CONFIRM
        ) { args -> setSchedule(args) }

        functionRegistry.register(
            FunctionDefinition(
                name = "music_list_schedules",
                description = "列出全部定时播放（时间/模式/状态/歌单或意图/周期）。" +
                        "状态：active 待触发 / fired 已完成 / missed 错过 / skipped_cap 触顶跳过 / cancelled 已取消。",
                parameters = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
            ),
            metadata.id, permission = ActionPermission.AUTO
        ) { listSchedules() }

        functionRegistry.register(
            FunctionDefinition(
                name = "music_cancel_schedule",
                description = "取消定时播放。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "schedule_id" to mapOf("type" to "string", "description" to "定时 id")
                    ),
                    "required" to listOf("schedule_id")
                )
            ),
            metadata.id, permission = ActionPermission.CONFIRM
        ) { args -> cancelSchedule((args["schedule_id"] as? String ?: "").trim()) }
    }

    // ═══ 扫描引擎（MD16 最终一致性）═══

    private data class ScanStats(val scanned: Int, val unchanged: Int, val updated: Int,
                                 val inserted: Int, val missed: Int, val purged: Int, val ms: Long)

    /** 递归子树增量扫描。工作目录未配置返回引导（B11）；失败留 trace 不抛（E28）。 */
    private suspend fun doScan(trigger: String): ScanStats = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        // llmmusic 根引导：不存在则建（幂等——已存在直接成功）；失败留痕不中断（如被同名文件占用）
        workDir.createFolder(MusicPaths.SUBDIR)?.let {
            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                "scan_bootstrap_failed: trigger=$trigger $it")
        }
        // listTree 复用（M13）：全工作目录树 → 过滤 llmmusic 子树音频文件
        val nodes = workDir.listTree()
        val prefix = "${MusicPaths.SUBDIR}/"
        val audio = nodes.asSequence()
            .filter { !it.isDir && it.path.startsWith(prefix) }
            .filter { it.name.substringAfterLast('.', "").lowercase() in AUDIO_EXTS }
            .associateBy { it.path.removePrefix(prefix) }  // relPath -> TreeNode（树内路径唯一）

        val existing = musicDao.loadAllTracks().associateBy { it.relativePath }
        var unchanged = 0; var updated = 0; var inserted = 0; var missed = 0; var purged = 0
        // 枚举可疑守卫（M8）：零音频但库存非空 → 视为 SAF 枚举异常，本轮跳过缺失处理防误删
        val skipMiss = audio.isEmpty() && existing.isNotEmpty()
        if (skipMiss) {
            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                "scan_suspect_empty: 枚举为零但库存 ${existing.size}——跳过缺失处理（trigger=$trigger）")
        }

        if (!skipMiss) {
            existing.values.filter { it.relativePath !in audio }.forEach {
                musicDao.incrementMissCount(it.relativePath); missed++
            }
        }

        audio.forEach { (rel, node) ->
            val cur = existing[rel]
            if (cur != null && cur.fileSize == node.sizeBytes && cur.modifiedAt == node.lastModified) {
                // 三元组一致：跳过 MMR；若此前 miss 过则清零（文件回来了）
                if (cur.missCount > 0) {
                    musicDao.updateMetadataByPath(rel, cur.displayTitle, cur.artist, cur.album,
                        cur.durationMs, cur.fileSize, cur.modifiedAt)
                }
                unchanged++
            } else {
                // 新增/变更：抽元数据（失败回落基名，不中断扫描）
                val meta = workDir.resolveInSubtree(MusicPaths.SUBDIR, rel)?.let { extractMetadata(it) }
                val title = meta?.title?.takeIf { it.isNotBlank() } ?: rel.substringAfterLast('/')
                if (cur != null) {
                    musicDao.updateMetadataByPath(rel, title, meta?.artist, meta?.album,
                        meta?.durationMs ?: 0, node.sizeBytes, node.lastModified)
                    updated++
                } else {
                    musicDao.insertTrack(MusicTrackEntity(
                        id = UUID.randomUUID().toString(),
                        relativePath = rel,
                        displayTitle = title,
                        artist = meta?.artist, album = meta?.album,
                        durationMs = meta?.durationMs ?: 0,
                        fileSize = node.sizeBytes,
                        addedAt = System.currentTimeMillis(),
                        modifiedAt = node.lastModified))
                    inserted++
                }
            }
        }

        if (!skipMiss) {
            musicDao.loadTracksWithMissCountAtLeast(MISS_PURGE_THRESHOLD).forEach {
                musicDao.deleteTrackByPath(it.relativePath); purged++
            }
        }

        val stats = ScanStats(audio.size, unchanged, updated, inserted, missed, purged,
            System.currentTimeMillis() - t0)
        traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
            "scan_done: trigger=$trigger scanned=${stats.scanned} unchanged=${stats.unchanged} " +
                    "updated=${stats.updated} inserted=${stats.inserted} missed=${stats.missed} " +
                    "purged=${stats.purged} ms=${stats.ms}")
        stats
    }

    private suspend fun scanLibrary(): FunctionResult {
        if (!workDir.hasDirectory()) return FunctionResult.failure(NOT_CONFIGURED_HINT)
        return try {
            val s = doScan(trigger = "fc")
            FunctionResult.success(
                "✅ 扫描完成：llmmusic/ 共 ${s.scanned} 首（新增 ${s.inserted} · 更新 ${s.updated} · " +
                        "不变 ${s.unchanged} · 缺失 ${s.missed} · 清除 ${s.purged}）· 用时 ${s.ms / 1000.0}s")
        } catch (e: Exception) {
            traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id,
                "scan_failed: ${e.javaClass.simpleName} ${e.message?.take(50)}")
            FunctionResult.failure("扫描失败: ${e.message?.take(60)}")
        }
    }

    /** MediaMetadataRetriever 抽 ID3/时长（SR8：SAF content Uri 真机验）。失败返回 null 走回落 */
    private fun extractMetadata(uri: Uri): Meta? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(appContext, uri)
            Meta(
                title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?.takeIf { it.isNotBlank() },
                album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    ?.takeIf { it.isNotBlank() },
                durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0
            )
        } catch (t: Throwable) {
            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                "meta_extract_failed: ${t.javaClass.simpleName} ${t.message?.take(40)}")
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    private data class Meta(val title: String?, val artist: String?, val album: String?, val durationMs: Long)

    // ═══ 查询 FC ═══

    private suspend fun listTracks(artist: String?, album: String?, limit: Int): FunctionResult =
        withContext(Dispatchers.IO) {
            if (!workDir.hasDirectory()) return@withContext FunctionResult.failure(NOT_CONFIGURED_HINT)
            val all = musicDao.loadAllTracks()
            val filtered = all
                .filter { artist == null || it.artist?.contains(artist, ignoreCase = true) == true }
                .filter { album == null || it.album?.contains(album, ignoreCase = true) == true }
                .take(limit)
            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                "list: total=${all.size} filtered=${filtered.size}")
            if (filtered.isEmpty()) {
                return@withContext FunctionResult.success(
                    if (all.isEmpty()) "🎼 音乐库为空——放音频文件进工作目录 llmmusic/ 后调 music_scan"
                    else "🎼 无匹配曲目（库共 ${all.size} 首）")
            }
            FunctionResult.success(buildString {
                appendLine("🎼 音乐库（${filtered.size}/${all.size} 首）：")
                filtered.forEach {
                    // fix（L3 实测 09-12）：显示完整 id——原 take(8) 截断致 LLM 只能拿短 id，
                    // getTrackById 查不到恒"曲目不存在"
                    appendLine("[${it.id}] ${it.relativePath} — ${it.displayTitle}" +
                            (it.artist?.let { a -> " · $a" } ?: "") + " · ${formatDuration(it.durationMs)}")
                }
            })
        }

    private suspend fun searchTracks(query: String, limit: Int): FunctionResult =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext FunctionResult.failure("query 不能为空")
            val hits = musicDao.searchTracks(query, limit)
            if (hits.isEmpty()) return@withContext FunctionResult.success("🔍 无匹配「$query」的曲目")
            FunctionResult.success(buildString {
                appendLine("🔍 「$query」匹配 ${hits.size} 首：")
                hits.forEach {
                    // fix（L3 实测 09-12）：完整 id（同 music_list）
                    appendLine("[${it.id}] ${it.relativePath} — ${it.displayTitle}" +
                            (it.artist?.let { a -> " · $a" } ?: "") + " · ${formatDuration(it.durationMs)}")
                }
            })
        }

    // ═══ 文件 FC（物理优先 M9 / 审计 M10）═══

    private suspend fun renameTrack(oldRel: String, newRel: String): FunctionResult =
        withContext(Dispatchers.IO) {
            validateRelative(oldRel)?.let { return@withContext FunctionResult.failure(it) }
            validateRelative(newRel)?.let { return@withContext FunctionResult.failure(it) }
            if (!workDir.hasDirectory()) return@withContext FunctionResult.failure(NOT_CONFIGURED_HINT)
            if (oldRel == newRel) return@withContext FunctionResult.success("路径未变：$oldRel")
            // 物理优先（M9）：复用 S9.1 moveEntry（同目录 rename 快路径/跨目录 copy+delete/
            // 拒穿越/目标已存在拒覆盖——子树钳制由 resolvePath 拒 ".." 保证）
            val physErr = workDir.moveEntry(MusicPaths.inSubdir(oldRel), MusicPaths.inSubdir(newRel))
            if (physErr != null) return@withContext FunctionResult.failure(physErr)
            // DB 同步
            try {
                dbMutex.withLock {
                    val cur = musicDao.getByRelativePath(oldRel)
                    musicDao.updateTrackPath(oldRel, newRel)
                    // display_title 缺省跟随时更新（设计 §六：标题来自旧文件基名 → 跟随新基名）
                    if (cur != null && cur.displayTitle == oldRel.substringAfterLast('/')) {
                        musicDao.updateMetadataByPath(newRel, newRel.substringAfterLast('/'),
                            cur.artist, cur.album, cur.durationMs, cur.fileSize, cur.modifiedAt)
                    }
                }
                traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                    "renamed: $oldRel -> $newRel")
                FunctionResult.success("✅ 已重命名：$oldRel → $newRel")
            } catch (e: Exception) {
                // 物理已成、DB 失败 → trace + 立即 reconcile（扫描=最终一致性修复，MD16）
                traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id,
                    "music_db_sync_failed: rename $oldRel -> $newRel ${e.message?.take(50)} → 触发重扫")
                moduleScope.launch { runCatching { doScan(trigger = "reconcile_rename") } }
                FunctionResult.success("✅ 文件已重命名（$oldRel → $newRel）；库同步失败，已触发重扫修复")
            }
        }

    private suspend fun deleteTrack(relPath: String): FunctionResult =
        withContext(Dispatchers.IO) {
            validateRelative(relPath)?.let { return@withContext FunctionResult.failure(it) }
            if (!workDir.hasDirectory()) return@withContext FunctionResult.failure(NOT_CONFIGURED_HINT)
            val tracked = dbMutex.withLock { musicDao.getByRelativePath(relPath) }
            // M10 审计（MU3.2 接线）：删前查受影响面——CASCADE 静默清项，LLM 须知道歌单已不完整
            val audit = dbMutex.withLock {
                if (tracked != null)
                    musicDao.countPlaylistsContainingTrack(tracked.id) to
                            musicDao.countPlaylistItemsOfTrack(tracked.id)
                else 0 to 0
            }
            // 物理优先（M9）：复用 S9.1 deleteFile（统一引用解析；文件夹守卫自动引导）
            val physErr = workDir.deleteFile(MusicPaths.inSubdir(relPath))
            if (physErr != null) return@withContext FunctionResult.failure(physErr)
            try {
                dbMutex.withLock { musicDao.deleteTrackByPath(relPath) }
                traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                    "deleted: $relPath tracked=${tracked != null} " +
                            "affected_playlists=${audit.first} removed_items=${audit.second}")
                FunctionResult.success("🗑 已删除：$relPath" +
                        (if (audit.first > 0) "（影响歌单 ${audit.first} 个，移除歌单项 ${audit.second} 条）"
                        else "（影响歌单 0 个）") +
                        (if (tracked == null) "；该文件未入库" else ""))
            } catch (e: Exception) {
                traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id,
                    "music_db_sync_failed: delete $relPath ${e.message?.take(50)} → 触发重扫")
                moduleScope.launch { runCatching { doScan(trigger = "reconcile_delete") } }
                FunctionResult.success("🗑 文件已删除（$relPath）；库同步失败，已触发重扫修复")
            }
        }

    /** 相对路径校验（M 路径安全）：拒空/绝对路径/".."/"." 段（D57 穿越防护第一道） */
    private fun validateRelative(p: String): String? = when {
        p.isEmpty() -> "路径不能为空"
        p.startsWith("/") -> "仅支持相对 llmmusic 的路径（不含开头 /）：$p"
        p.split('/').any { it == ".." || it == "." || it.isBlank() } ->
            "非法路径段（含 .. / . / 空段）：$p"
        else -> null
    }

    // ═══ 播放核心（MU2.2）═══

    /**
     * FC 数组参数兼容解析（fix L3 实测 09-12）：parseToolArguments 用 org.json 解析，
     * JSON 数组成 JSONArray 而非 List——as? List 恒 null（music_play track_ids 报
     * "需提供之一"根因）。两种类型统一收口。
     */
    private fun stringListArg(v: Any?): List<String>? = when (v) {
        is org.json.JSONArray ->
            (0 until v.length()).mapNotNull { i ->
                v.optString(i).takeIf { it.isNotBlank() }
            }
        is List<*> -> v.mapNotNull { it?.toString()?.takeIf { s -> s.isNotBlank() } }
        else -> null
    }

    private suspend fun playTracks(args: Map<String, Any?>): FunctionResult {
        val trackId = (args["track_id"] as? String)?.trim()
        val trackIds = stringListArg(args["track_ids"])
        val playlistId = (args["playlist_id"] as? String)?.trim()
        return when {
            !trackId.isNullOrBlank() -> startQueue(listOf(trackId), play = true)
            !trackIds.isNullOrEmpty() -> startQueue(trackIds, play = true)
            !playlistId.isNullOrBlank() -> playPlaylist(playlistId)
            else -> FunctionResult.failure("需提供 track_id / track_ids / playlist_id 之一")
        }
    }

    /** MU3.2：歌单按序起播（tracksOfPlaylist JOIN 按 position） */
    private suspend fun playPlaylist(playlistId: String): FunctionResult {
        if (!workDir.hasDirectory()) return FunctionResult.failure(NOT_CONFIGURED_HINT)
        val pl = withContext(Dispatchers.IO) { musicDao.getPlaylistById(playlistId) }
            ?: return FunctionResult.failure("歌单不存在: $playlistId（playlist_list 查有效 id）")
        val tracks = withContext(Dispatchers.IO) { musicDao.tracksOfPlaylist(playlistId) }
        if (tracks.isEmpty()) return FunctionResult.failure("歌单《${pl.name}》为空——music_playlist_add_track 加曲")
        return startQueue(tracks.map { it.id }, play = true)
    }

    /**
     * 建队列并（可选）起播。MD17：起播路径统一经 ensurePlaybackService() 单入口；
     * MD13：逐曲 resolveInSubtree 定位（任意深度子目录）；C2：AudioFocus 先请求。
     */
    private suspend fun startQueue(ids: List<String>, play: Boolean): FunctionResult {
        if (!workDir.hasDirectory()) return FunctionResult.failure(NOT_CONFIGURED_HINT)
        val tracks = withContext(Dispatchers.IO) { ids.map { resolveTrackId(it) } }
        // 短 id（≤8 位，旧上下文截断显示遗留）多命中歧义防误播：剔除歧义项并如实报告
        val ambiguous = tracks.filterIsInstance<IdResolution.Ambiguous>()
        val resolved = tracks.mapNotNull { (it as? IdResolution.Hit)?.track }
        if (ambiguous.isNotEmpty()) {
            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                "short_id_ambiguous: ${ambiguous.joinToString { it.id }}")
            return FunctionResult.failure(
                "短 id 命中多首（${ambiguous.joinToString { it.id }}）——请用 music_list 返回的完整 id")
        }
        if (resolved.isEmpty()) return FunctionResult.failure(
            "曲目不存在（id 无效或未入库——先 music_scan，并用 music_list 返回的完整 id）")
        val items = resolved.mapNotNull { t ->
            val uri = workDir.resolveInSubtree(MusicPaths.SUBDIR, t.relativePath)
            if (uri == null) {
                traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                    "resolve_failed_on_play: ${t.relativePath}")
                null
            } else t to buildMediaItem(t, uri)
        }
        if (items.isEmpty()) return FunctionResult.failure(
            "文件定位失败（可能已被外部移动/删除——music_scan 将收敛）")
        val skipped = ids.size - items.size
        ensurePlaybackService()
        requestFocus()
        val volumePct = (config as MusicModuleConfig).defaultVolume / 100f
        try {
            withPlayer {
                volume = volumePct
                setMediaItems(items.map { it.second })
                prepare()
                if (play) play()
                refreshPlaybackState()
            }
        } catch (e: Exception) {
            traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id,
                "play_failed: ${e.javaClass.simpleName} ${e.message?.take(50)}")
            return FunctionResult.failure("播放失败: ${e.message?.take(60)}")
        }
        val first = items.first().first
        return FunctionResult.success(
            "🎵 ${if (play) "开始播放" else "队列已设置"}：${items.size} 首" +
                    (if (skipped > 0) "（跳过无效 $skipped）" else "") +
                    " — 当前《${first.displayTitle.ifBlank { first.relativePath.substringAfterLast('/') }}》")
    }

    /** id 解析结果：命中 / 未命中 / 短 id 多命中（歧义防误播） */
    private sealed interface IdResolution {
        data class Hit(val track: MusicTrackEntity) : IdResolution
        data object Miss : IdResolution
        data class Ambiguous(val id: String) : IdResolution
    }

    /**
     * 曲目 id 解析：完整 id 直查；短 id（<36 位，旧上下文 take(8) 截断显示遗留）唯一
     * 前缀命中回退——resolveFileRef 全树唯一匹配同域先例；多命中=歧义报错不猜。
     */
    private suspend fun resolveTrackId(id: String): IdResolution {
        musicDao.getTrackById(id)?.let { return IdResolution.Hit(it) }
        if (id.length >= 36) return IdResolution.Miss
        val hits = musicDao.loadAllTracks().filter { it.id.startsWith(id) }
        return when (hits.size) {
            0 -> IdResolution.Miss
            1 -> IdResolution.Hit(hits.first())
            else -> IdResolution.Ambiguous(id)
        }
    }

    private fun buildMediaItem(t: MusicTrackEntity, uri: Uri): MediaItem =
        MediaItem.Builder()
            .setMediaId(t.id)
            .setUri(uri)
            .setTag(t.relativePath)  // localConfiguration.tag = relative_path 快照（PlaybackState 溯源）
            .setMediaMetadata(MediaMetadata.Builder()
                .setTitle(t.displayTitle.ifBlank { t.relativePath.substringAfterLast('/') })
                .setArtist(t.artist)
                .build())
            .build()

    /**
     * 上下首切换（fix L3 实测 09-12：随机模式下当前曲为随机序末尾时 hasNext=false，
     * 原实现无操作但 block 不抛异常 → 恒返回 ✅ 假成功，曲目不变）。现如实返回：
     * 无处可切 = failure 提示出路，LLM 可感知决策。
     */
    private suspend fun skipTo(direction: String): FunctionResult = try {
        val moved = withPlayer {
            val has = if (direction == "next") hasNextMediaItem() else hasPreviousMediaItem()
            if (has) {
                if (direction == "next") seekToNextMediaItem() else seekToPreviousMediaItem()
                refreshPlaybackState()
                true
            } else false
        }
        if (moved) {
            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id, "player_control: skip_$direction")
            val s = _playbackState.value
            FunctionResult.success("⏭ 已切到《${s.title ?: "?"}》· 队列 ${s.queueIndex + 1}/${s.queueSize}")
        } else {
            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                "skip_noop: $direction（队列端点）")
            FunctionResult.failure(
                "已是队列${if (direction == "next") "末尾" else "开头"}（随机模式下为随机顺序端点）——" +
                        "可 music_repeat(mode=\"all\") 开循环、music_play 重选曲、或 music_shuffle 切换模式")
        }
    } catch (e: Exception) {
        traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id,
            "skip_failed: $direction ${e.message?.take(40)}")
        FunctionResult.failure("切歌失败: ${e.message?.take(60)}")
    }

    // ═══ 歌单实现（MU3.2）═══

    private suspend fun createPlaylist(args: Map<String, Any?>): FunctionResult {
        val name = (args["name"] as? String)?.trim().orEmpty()
        if (name.isEmpty()) return FunctionResult.failure("name 不能为空")
        val fromIds = stringListArg(args["from_track_ids"]) ?: emptyList()
        val sourcePrompt = (args["source_prompt"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        return try {
            dbMutex.withLock {
                val plId = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                musicDao.insertPlaylist(PlaylistEntity(
                    id = plId, name = name,
                    createdBy = if (sourcePrompt != null) PlaylistEntity.CREATED_BY_LLM
                    else PlaylistEntity.CREATED_BY_USER,
                    createdAt = now, sourcePrompt = sourcePrompt))
                var added = 0
                fromIds.forEachIndexed { idx, tid ->
                    (resolveTrackId(tid) as? IdResolution.Hit)?.track?.let {
                        musicDao.insertPlaylistItem(PlaylistItemEntity(
                            id = UUID.randomUUID().toString(),
                            playlistId = plId, trackId = it.id, position = idx))
                        added++
                    }
                }
                traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                    "playlist_created: $name items=$added source=${sourcePrompt != null}")
                FunctionResult.success("✅ 歌单《$name》已创建（$added 首）\nplaylist_id=$plId" +
                        if (fromIds.size > added) "\n（${fromIds.size - added} 个无效 id 跳过）" else "")
            }
        } catch (e: Exception) {
            traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id,
                "playlist_create_failed: $name ${e.message?.take(40)}")
            FunctionResult.failure("创建歌单失败: ${e.message?.take(60)}")
        }
    }

    private suspend fun listPlaylists(): FunctionResult = withContext(Dispatchers.IO) {
        val playlists = musicDao.loadPlaylists()
        if (playlists.isEmpty()) return@withContext FunctionResult.success(
            "📋 暂无歌单——playlist_create 创建，或对话让 Zero 建")
        FunctionResult.success(buildString {
            appendLine("📋 歌单（${playlists.size} 个）：")
            playlists.forEach { pl ->
                val count = musicDao.loadPlaylistItems(pl.id).size
                appendLine("[${pl.id}] 《${pl.name}》$count 首" +
                        (pl.sourcePrompt?.let { " · 源于「${it.take(20)}」" } ?: "") +
                        " · ${java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault())}"
                            .format(java.util.Date(pl.createdAt)))
            }
        })
    }

    private suspend fun deletePlaylist(playlistId: String): FunctionResult {
        if (playlistId.isEmpty()) return FunctionResult.failure("playlist_id 不能为空")
        return try {
            dbMutex.withLock {
                val pl = musicDao.getPlaylistById(playlistId)
                    ?: return FunctionResult.failure("歌单不存在: $playlistId")
                val count = musicDao.loadPlaylistItems(playlistId).size
                musicDao.deletePlaylist(playlistId)  // items 经 FK CASCADE 随清
                traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                    "playlist_deleted: ${pl.name} items=$count")
                FunctionResult.success("🗑 已删除歌单《${pl.name}》（$count 首引用；音乐文件未动）")
            }
        } catch (e: Exception) {
            traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id,
                "playlist_delete_failed: $playlistId ${e.message?.take(40)}")
            FunctionResult.failure("删除歌单失败: ${e.message?.take(60)}")
        }
    }

    private suspend fun addTrackToPlaylist(playlistId: String, trackId: String, position: Int?): FunctionResult {
        if (playlistId.isEmpty() || trackId.isEmpty())
            return FunctionResult.failure("playlist_id 和 track_id 不能为空")
        return try {
            dbMutex.withLock {
                val pl = musicDao.getPlaylistById(playlistId)
                    ?: return FunctionResult.failure("歌单不存在: $playlistId")
                val track = (resolveTrackId(trackId) as? IdResolution.Hit)?.track
                    ?: return FunctionResult.failure("曲目不存在: $trackId（用完整 id）")
                val pos = position ?: musicDao.nextPlaylistPosition(playlistId)
                musicDao.insertPlaylistItem(PlaylistItemEntity(
                    id = UUID.randomUUID().toString(),
                    playlistId = playlistId, trackId = track.id, position = pos))
                traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                    "playlist_track_added: ${pl.name} +《${track.displayTitle.take(20)}》@$pos")
                FunctionResult.success("✅ 已加入《${pl.name}》（位置 ${pos + 1}）")
            }
        } catch (e: Exception) {
            traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id,
                "playlist_add_failed: $playlistId ${e.message?.take(40)}")
            FunctionResult.failure("加曲失败: ${e.message?.take(60)}")
        }
    }

    private suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String): FunctionResult {
        if (playlistId.isEmpty() || trackId.isEmpty())
            return FunctionResult.failure("playlist_id 和 track_id 不能为空")
        return try {
            dbMutex.withLock {
                val pl = musicDao.getPlaylistById(playlistId)
                    ?: return FunctionResult.failure("歌单不存在: $playlistId")
                val track = (resolveTrackId(trackId) as? IdResolution.Hit)?.track
                    ?: return FunctionResult.failure("曲目不存在: $trackId")
                val before = musicDao.loadPlaylistItems(playlistId).size
                musicDao.deletePlaylistItem(playlistId, track.id)
                val after = musicDao.loadPlaylistItems(playlistId).size
                if (before == after) return FunctionResult.failure("《${pl.name}》中没有该曲目")
                traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                    "playlist_track_removed: ${pl.name} -《${track.displayTitle.take(20)}》")
                FunctionResult.success("✅ 已从《${pl.name}》移除（余 ${after} 首；音乐文件未动）")
            }
        } catch (e: Exception) {
            traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id,
                "playlist_remove_failed: $playlistId ${e.message?.take(40)}")
            FunctionResult.failure("移除失败: ${e.message?.take(60)}")
        }
    }

    // ═══ 定时播放（MU4.2b · 设计 §7.1/§7.3 · MD14 认领语义）═══
    // 闹钟=唤醒 tick（单一 REQUEST_MUSIC 恒指最近到期）：ExactAlarm（进程冻结可靠）
    // 与 kick（前台快路径）双路径均调 execute()——幂等由 claimSchedule 条件更新保证
    //（受影响行=0 即被并行路径认领→放弃该行）。

    /** AgentJob.execute：到期扫描 → 逐条原子认领执行 → 推进状态 → 重排下一最近 */
    override suspend fun execute() {
        val now = System.currentTimeMillis()
        val due = withContext(Dispatchers.IO) { musicDao.loadDueSchedules(now) }
        if (due.isEmpty()) {
            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                "music_wake_noop: 无到期调度")
        } else {
            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                "music_wake: due=${due.size}")
            due.forEach { runDueSchedule(it, now) }
        }
        scheduleNextMusicAlarm()
    }

    /**
     * 单条到期处理：认领（幂等落点）→ FIXED 直播 / AUTO 双门控触发（§7.2 M5）→ 推进。
     * 一次性被拒（音乐域/全局顶/开关）→ skipped_cap；周期被拒→跳过本期（公共段推进）。
     */
    private suspend fun runDueSchedule(s: ScheduledPlaybackEntity, now: Long) {
        val claimed = dbMutex.withLock { musicDao.claimSchedule(s.id, now) }
        if (claimed == 0) {
            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                "claim_lost: ${s.id}（并行路径已认领，放弃）")
            return
        }
        var executed = true
        var playbackFailed = false
        when (s.mode) {
            ScheduledPlaybackEntity.MODE_FIXED -> {
                val pl = s.playlistId
                val result = if (pl == null) FunctionResult.failure("定时歌单不存在")
                    else playPlaylist(pl) // 不唤醒 LLM（设计 §7.1）
                if (!result.success) {
                    playbackFailed = true
                    traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id,
                        "schedule_playback_failed: ${s.id}", detail = result.errorMessage)
                }
            }
            ScheduledPlaybackEntity.MODE_AUTO -> {
                // MU5.1 双门控（M5/MD18）：第一道=音乐域日额度（本模块计数）；
                // 第二道=TriggerHub 开关+全局 dailyTriggerCap（fireMusicSchedule 内检查）
                if (!acquireAutoQuota()) {
                    executed = false
                    traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                        "music_auto_cap_reached: ${s.id} 音乐域日顶已满，拒绝触发")
                } else if (!triggerHub.fireMusicSchedule(
                        "schedule=${s.id} hint=${s.hint ?: ""}", s.hint)) {
                    executed = false  // 全局顶/开关关（trace 在 TriggerHub 已留）
                }
                if (!executed && !s.isRecurring) {
                    dbMutex.withLock { musicDao.markSkippedCap(s.id) }
                }
                // 周期被拒：公共段推进下一期（永不终态阻塞）
            }
        }
        // 推进（F17 原相位保持：+=ms while 越过 now——禁 now+ms）
        if (s.isRecurring && s.recurringMs > 0) {
            var next = s.triggerAt + s.recurringMs
            val nowAfter = System.currentTimeMillis()
            while (next <= nowAfter) next += s.recurringMs
            dbMutex.withLock { musicDao.advanceRecurring(s.id, next) }
        } else if (playbackFailed) {
            dbMutex.withLock { musicDao.markFailed(s.id) }
        } else if (executed) {
            // 一次性：正常执行→fired；被拒→skipped_cap（上方已标记，此处不覆盖）
            dbMutex.withLock { musicDao.markFired(s.id) }
        }
    }

    /** 第一道门（M5）：音乐域 AUTO 日额度——模块内当日计数（仿 TriggerHub 日顶模式） */
    private fun acquireAutoQuota(): Boolean {
        val cfg = config as MusicModuleConfig
        val today = System.currentTimeMillis() / 86_400_000L
        val day = autoQuotaPrefs.getLong(KEY_QUOTA_DAY, -1L)
        val count = if (day == today) autoQuotaPrefs.getInt(KEY_QUOTA_COUNT, 0) else 0
        if (count >= cfg.scheduledAutoCap) return false
        autoQuotaPrefs.edit().putLong(KEY_QUOTA_DAY, today).putInt(KEY_QUOTA_COUNT, count + 1).apply()
        return true
    }

    /** 重排：闹钟指向最近 active + 进程内 kick 快路径 + AgentJob.nextRunTime 推进 */
    private fun scheduleNextMusicAlarm() {
        moduleScope.launch {
            val next = withContext(Dispatchers.IO) { musicDao.nextActiveTriggerAt() }
            nextRunTime = next ?: Long.MAX_VALUE
            wakeJob?.cancel()
            if (next != null) {
                exactAlarmScheduler.scheduleMusic(next)
                val wait = next - System.currentTimeMillis()
                if (wait > 0) {
                    wakeJob = moduleScope.launch {
                        delay(wait)
                        agentRuntime.kick()  // 前台快路径（D19：kick 非第二心跳源）
                    }
                }
                traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                    "alarm_rescheduled: nextRun=$next")
            } else {
                exactAlarmScheduler.cancelMusic()
            }
        }
    }

    /** G6 恢复：陈旧一次性（< now-5min）→missed；过期周期→原相位推进；刚过期→照播（execute） */
    private suspend fun restoreSchedules() {
        val now = System.currentTimeMillis()
        dbMutex.withLock {
            val stale = musicDao.loadStaleOneShots(now - STALE_GRACE_MS)
            stale.forEach {
                musicDao.markMissed(it.id)
                traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                    "restored_missed: ${it.id} trigger_at=${it.triggerAt}")
            }
            musicDao.loadSchedules()
                .filter { it.status == ScheduledPlaybackEntity.STATUS_ACTIVE &&
                        it.isRecurring && it.recurringMs > 0 && it.triggerAt <= now }
                .forEach { s ->
                    var next = s.triggerAt + s.recurringMs
                    while (next <= now) next += s.recurringMs
                    if (musicDao.restoreRecurring(s.id, s.triggerAt, next) > 0) {
                        traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                            "restored_recurring: ${s.id} → $next")
                    }
                }
        }
    }

    /** 时间解析（仿 SystemEventModule.parseTime 精简三段：相对 / 完整时间 / 时刻） */
    private fun parseMusicTime(raw: String): Long? {
        val t = raw.trim()
        Regex("""\+(\d+)([smhd])""").find(t)?.let { m ->
            val v = m.groupValues[1].toLong()
            return System.currentTimeMillis() + when (m.groupValues[2]) {
                "s" -> v * 1000; "m" -> v * 60_000; "h" -> v * 3600_000
                "d" -> v * 86_400_000; else -> return null
            }
        }
        val zone = java.time.ZoneId.systemDefault()
        Regex("""(\d{4})-(\d{1,2})-(\d{1,2})[T ](\d{1,2}):(\d{2})(?::(\d{2}))?""").find(t)?.let { m ->
            return try {
                java.time.LocalDateTime.of(
                    m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(),
                    m.groupValues[4].toInt(), m.groupValues[5].toInt(),
                    m.groupValues[6].ifBlank { "0" }.toInt())
                    .atZone(zone).toInstant().toEpochMilli()
            } catch (_: Exception) { null }
        }
        Regex("""(明天)?\s*(\d{1,2}):(\d{2})(?::(\d{2}))?$""").find(t)?.let { m ->
            return try {
                var date = java.time.LocalDate.now(zone)
                if (!m.groupValues[1].isBlank()) date = date.plusDays(1)
                val dt = date.atTime(m.groupValues[2].toInt(), m.groupValues[3].toInt(),
                    m.groupValues[4].ifBlank { "0" }.toInt())
                var ms = dt.atZone(zone).toInstant().toEpochMilli()
                if (ms <= System.currentTimeMillis() && m.groupValues[1].isBlank()) ms += 86_400_000
                ms
            } catch (_: Exception) { null }
        }
        return null
    }

    private suspend fun setSchedule(args: Map<String, Any?>): FunctionResult {
        val timeRaw = (args["time"] as? String ?: "").trim()
        if (timeRaw.isEmpty()) return FunctionResult.failure("time 不能为空")
        val triggerAt = parseMusicTime(timeRaw)
            ?: return FunctionResult.failure("无法解析时间「$timeRaw」——支持 +90s / 21:30 / 2026-09-13 08:00")
        // F17 同则：拒绝过去时刻（防重排立即触发）
        if (triggerAt <= System.currentTimeMillis() + 1000) {
            return FunctionResult.failure("时间已过去——请给未来时刻（今日时刻已过会自动顺延明天，如 21:30）")
        }
        val mode = ((args["mode"] as? String) ?: ScheduledPlaybackEntity.MODE_FIXED).uppercase()
        if (mode !in listOf(ScheduledPlaybackEntity.MODE_FIXED, ScheduledPlaybackEntity.MODE_AUTO)) {
            return FunctionResult.failure("mode 仅支持 FIXED / AUTO")
        }
        if (mode == ScheduledPlaybackEntity.MODE_FIXED && (args["playlist_id"] as? String).isNullOrBlank()) {
            return FunctionResult.failure("FIXED 模式需提供 playlist_id（playlist_list 查询）")
        }
        val recurring = (args["recurring"] as? Boolean) ?: false
        val intervalSec = (args["interval_seconds"] as? Number)?.toLong()
        if (recurring && (intervalSec == null || intervalSec < 60)) {
            return FunctionResult.failure("周期模式需 interval_seconds ≥ 60（如 86400=每日）")
        }
        return try {
            val id = UUID.randomUUID().toString()
            dbMutex.withLock {
                musicDao.insertSchedule(ScheduledPlaybackEntity(
                    id = id, triggerAt = triggerAt,
                    playlistId = (args["playlist_id"] as? String)?.trim(),
                    mode = mode,
                    hint = (args["hint"] as? String)?.trim(),
                    isRecurring = recurring,
                    recurringMs = if (recurring) intervalSec!! * 1000 else 0,
                    createdBy = ScheduledPlaybackEntity.CREATED_BY_LLM))
            }
            scheduleNextMusicAlarm()  // 新调度可能更早
            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                "schedule_set: id=$id mode=$mode trigger=$triggerAt recurring=$recurring")
            val timeText = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(triggerAt))
            FunctionResult.success(
                "⏰ 定时播放已设置（$timeText${if (recurring) " · 每 ${intervalSec}s" else ""}）\n" +
                        "schedule_id=$id" +
                        if (mode == ScheduledPlaybackEntity.MODE_AUTO)
                            "\n（AUTO：到点 Zero 将按「${(args["hint"] as? String)?.take(20) ?: "意图"}」自主建单播放）"
                        else "")
        } catch (e: Exception) {
            traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id,
                "schedule_set_failed: ${e.message?.take(40)}")
            FunctionResult.failure("设置失败: ${e.message?.take(60)}")
        }
    }

    private suspend fun listSchedules(): FunctionResult = withContext(Dispatchers.IO) {
        val all = musicDao.loadSchedules()
        if (all.isEmpty()) return@withContext FunctionResult.success("⏰ 暂无定时播放")
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        FunctionResult.success(buildString {
            appendLine("⏰ 定时播放（${all.size} 条）：")
            all.forEach { s ->
                appendLine("[${s.id}] ${fmt.format(java.util.Date(s.triggerAt))} · ${s.mode}" +
                        (if (s.isRecurring) "·周期${s.recurringMs / 1000}s" else "") +
                        " · ${s.status}" +
                        (s.playlistId?.let { " · 歌单 $it" } ?: s.hint?.let { " · 「${it.take(16)}」" } ?: ""))
            }
        })
    }

    private suspend fun cancelSchedule(id: String): FunctionResult {
        if (id.isEmpty()) return FunctionResult.failure("schedule_id 不能为空")
        return try {
            val n = dbMutex.withLock { musicDao.cancelSchedule(id) }
            if (n == 0) return FunctionResult.failure("定时不存在或已结束: $id")
            scheduleNextMusicAlarm()  // 取消的可能是最早项——重排
            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id, "schedule_cancelled: $id")
            FunctionResult.success("✅ 已取消定时播放")
        } catch (e: Exception) {
            traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id,
                "schedule_cancel_failed: ${e.message?.take(40)}")
            FunctionResult.failure("取消失败: ${e.message?.take(60)}")
        }
    }

    /** 播放控制统一出口：主线程执行 block + 状态刷新 + trace */
    private suspend fun playerControl(action: String, block: ExoPlayer.() -> Unit): FunctionResult =
        try {
            withPlayer { block(); refreshPlaybackState() }
            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id, "player_control: $action")
            FunctionResult.success("✅ $action")
        } catch (e: Exception) {
            traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id,
                "player_control_failed: $action ${e.message?.take(40)}")
            FunctionResult.failure("操作失败: ${e.message?.take(60)}")
        }

    private suspend fun playbackStatus(): FunctionResult = try {
        val s = withPlayer { refreshPlaybackState(); _playbackState.value }
        if (s.trackId == null && s.queueSize == 0) {
            FunctionResult.success("⏸ 无播放（队列空）——用 music_list/music_search 找曲后 music_play")
        } else FunctionResult.success(buildString {
            appendLine(if (s.isPlaying) "▶ 播放中" else "⏸ 已暂停/未播")
            appendLine("当前：《${s.title ?: "-"}》${s.artist?.let { " · $it" } ?: ""}")
            append("位置 ${s.positionMs / 1000}s / ${s.durationMs / 1000}s · 队列 ${s.queueIndex + 1}/${s.queueSize}" +
                    " · 随机${if (s.shuffle) "开" else "关"} · 循环" +
                    when (s.repeatMode) {
                        Player.REPEAT_MODE_ONE -> "单曲"
                        Player.REPEAT_MODE_ALL -> "全部"
                        else -> "关"
                    })
            appendLine()
            append("路径：${s.relativePath ?: "-"} · id：${s.trackId ?: "-"}")
        })
    } catch (e: Exception) {
        traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id,
            "status_failed: ${e.message?.take(40)}")
        FunctionResult.failure("状态查询失败: ${e.message?.take(60)}")
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "-:--"
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}

private const val NOT_CONFIGURED_HINT =
    "尚未设置 Zero 工作目录。请引导用户到 资料 → 知识与工作文件 选择目录后重试。"
