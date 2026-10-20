package com.zero.v2.app.ui.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zero.v2.app.data.WorkDirRepository
import com.zero.v2.app.music.MusicModule
import com.zero.v2.app.music.PlaybackState
import com.zero.v2.core.data.dao.MusicDao
import com.zero.v2.core.data.entity.MusicTrackEntity
import com.zero.v2.core.data.entity.PlaylistEntity
import com.zero.v2.core.data.entity.ScheduledPlaybackEntity
import com.zero.v2.core.moduleapi.ToolDispatcher
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import com.zero.v2.app.ui.theme.ZeroCard
import com.zero.v2.app.ui.theme.ZeroPageHeader
import androidx.compose.runtime.saveable.rememberSaveable

/**
 * 音乐库页 — MU1.3 + MU2.3 now-playing（设计 §十；仿 WorkFilesScreen 同文件 VM 惯例）。
 *
 * 库列表（搜索过滤）+ 扫描按钮 + now-playing（曲名/位置/进度/控制/队列序号）；
 * 歌单 MU3.2、定时 MU4.3 增量接入。扫描与控制均经 FunctionRegistryModule.execute
 * 程序化通道调 music_* FC（S4.2 先例——与 LLM 同一实现路径，UI 不另造逻辑防漂移）。
 */
data class MusicLibraryUiState(
    val dirConfigured: Boolean = false,
    val tracks: List<MusicTrackEntity> = emptyList(),
    val busy: Boolean = false,
    val lastScanResult: String? = null,
    val loading: Boolean = true,
    val readError: String? = null,
    val operationError: String? = null,
    /** MU2.3：now-playing（PlaybackState 事件点快照 + ticker 实时位置） */
    val playback: PlaybackState = PlaybackState(),
    val posMs: Long = 0,
    val durMs: Long = 0,
    /** MU3.2：歌单（entity + 曲数，refresh 时 N+1 小规模查） */
    val playlists: List<Pair<PlaylistEntity, Int>> = emptyList(),
    /** MU4.3：定时播放（playlistId→name 映射供显示） */
    val schedules: List<ScheduledPlaybackEntity> = emptyList(),
    val playlistNames: Map<String, String> = emptyMap()
)

@HiltViewModel
class MusicViewModel @Inject constructor(
    private val musicDao: MusicDao,
    private val workDir: WorkDirRepository,
    private val functionRegistry: ToolDispatcher,
    private val musicModule: MusicModule,
    private val traceManager: AgentTraceManager
) : ViewModel() {

    private val commandDispatcher = MusicCommandDispatcher(functionRegistry)

    private val _state = MutableStateFlow(MusicLibraryUiState())
    val state: StateFlow<MusicLibraryUiState> = _state.asStateFlow()
    private val readMutex = Mutex()
    private val commandMutex = Mutex()
    private var readJob: Job? = null

    init {
        refresh()
        // 播放状态事件点快照（曲名/队列/模式变化即刷）
        viewModelScope.launch {
            musicModule.playbackState.collect { pb ->
                _state.update {
                    it.copy(playback = pb, posMs = pb.positionMs, durMs = pb.durationMs)
                }
            }
        }
        // 播放中每秒实时位置（ProgressTicker——经模块实时出口，与 music_status 同源）
        viewModelScope.launch {
            while (isActive) {
                if (_state.value.playback.isPlaying) {
                    musicModule.playbackPosition()?.let { (pos, dur) ->
                        _state.update { it.copy(posMs = pos, durMs = dur) }
                    }
                }
                delay(1000)
            }
        }
    }

    fun refresh() {
        if (readJob?.isActive == true) return
        readJob = viewModelScope.launch { readLibrary() }
    }

    private suspend fun readLibrary() = readMutex.withLock {
        _state.update { it.copy(loading = true, readError = null) }
        try {
            _state.update { it.copy(dirConfigured = workDir.hasDirectory()) }
            val tracks = withContext(Dispatchers.IO) { musicDao.loadAllTracks() }
            // MU3.2：歌单 + 曲数（个人级规模 N+1 可接受）
            val playlists = withContext(Dispatchers.IO) {
                musicDao.loadPlaylists().map { it to musicDao.loadPlaylistItems(it.id).size }
            }
            // MU4.3：定时列表 + 歌单名映射
            val schedules = withContext(Dispatchers.IO) { musicDao.loadSchedules() }
            _state.update {
                it.copy(tracks = tracks, playlists = playlists, schedules = schedules,
                    playlistNames = playlists.associate { (pl, _) -> pl.id to pl.name }, loading = false)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            _state.update { it.copy(loading = false, readError = "无法读取音乐库，请重试；已加载的内容仍保留。") }
            traceManager.record("ui_music", NodeType.ERROR, "music", "ui_read_failed: ${failure.javaClass.simpleName}")
        }
    }

    /** 扫描走 FC 通道（与 LLM 同路径；AUTO 权限无门禁，S4.2 程序化先例） */
    fun scan() = execCommand(MusicCommand.Scan, refreshAfter = true)

    // ═══ MU2.3：播放控制（全走 FC 通道——与 LLM 同路径）═══

    private fun execCommand(command: MusicCommand, refreshAfter: Boolean = false) {
        viewModelScope.launch {
            commandMutex.withLock {
                _state.update { it.copy(busy = true, operationError = null) }
                try {
                    val result = commandDispatcher.execute(command)
                    if (result.success) {
                        if (command == MusicCommand.Scan) _state.update { it.copy(lastScanResult = result.data) }
                        if (refreshAfter) readLibrary()
                    } else {
                        _state.update { it.copy(operationError = result.errorMessage?.takeIf(String::isNotBlank)
                            ?: result.data.takeIf(String::isNotBlank) ?: "音乐操作未完成，请重试。") }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    _state.update { it.copy(operationError = "音乐操作失败，请重试。") }
                    traceManager.record("ui_music", NodeType.ERROR, "music",
                        "ui_command_failed: ${command::class.simpleName} ${failure.javaClass.simpleName}")
                } finally {
                    _state.update { it.copy(busy = false) }
                }
            }
        }
    }

    fun togglePlayPause() = execCommand(if (_state.value.playback.isPlaying) MusicCommand.Pause else MusicCommand.Resume)
    fun next() = execCommand(MusicCommand.Next)
    fun prev() = execCommand(MusicCommand.Previous)
    fun toggleShuffle() = execCommand(MusicCommand.Shuffle(!_state.value.playback.shuffle))
    fun cycleRepeat() = execCommand(MusicCommand.Repeat(when (_state.value.playback.repeatMode) {
        PlaybackStateRepeat.OFF -> "all"; PlaybackStateRepeat.ALL -> "one"; else -> "off"
    }))
    fun seekTo(ms: Long) = execCommand(MusicCommand.Seek(ms))

    // ═══ MU3.2：歌单（全走 FC 通道；删除/建单对话框承担 CONFIRM 的用户确认角色）═══

    fun playPlaylist(id: String) = execCommand(MusicCommand.Play(id))
    fun deletePlaylist(id: String) {
        execCommand(MusicCommand.DeletePlaylist(id), refreshAfter = true)
    }

    // ═══ MU4.3：定时播放（全走 FC 通道；新建/取消对话框承担 CONFIRM 确认角色）═══

    fun setSchedule(time: String, mode: String, playlistId: String?, hint: String?,
                    recurring: Boolean, intervalSec: Long?) {
        execCommand(MusicCommand.Schedule(buildMap<String, Any?> {
            put("time", time); put("mode", mode)
            playlistId?.let { put("playlist_id", it) }
            hint?.let { put("hint", it) }
            if (recurring) { put("recurring", true); intervalSec?.let { put("interval_seconds", it) } }
        }), refreshAfter = true)
    }

    fun cancelSchedule(id: String) {
        execCommand(MusicCommand.CancelSchedule(id), refreshAfter = true)
    }

    /** MU4.3-fix（L3 实测）：终态记录清理（fired/missed/cancelled——无破坏性直接删） */
    fun removeSchedule(id: String) {
        viewModelScope.launch {
            commandMutex.withLock {
                _state.update { it.copy(busy = true, operationError = null) }
                try {
                    val removed = withContext(Dispatchers.IO) { musicDao.deleteSchedule(id) }
                    if (removed == 0) {
                        _state.update { it.copy(operationError = "记录状态已变化，只有已结束的定时记录可清除。") }
                        readLibrary()
                        return@withLock
                    }
                    traceManager.record("ui_music", NodeType.SYSTEM_EVENT, "music", "schedule_removed")
                    readLibrary()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    _state.update { it.copy(operationError = "无法清除定时记录，请重试。") }
                    traceManager.record("ui_music", NodeType.ERROR, "music", "ui_remove_failed: ${failure.javaClass.simpleName}")
                } finally {
                    _state.update { it.copy(busy = false) }
                }
            }
        }
    }

    fun createPlaylistFrom(name: String, trackIds: List<String>) {
        execCommand(MusicCommand.CreatePlaylist(name, trackIds), refreshAfter = true)
    }

    /** MU2.3-fix2（L3 实测 09-12：文件夹 4 首队列仅 3）：原 drop(index) 语义=从点按曲到
     * 列表尾——点按非首曲时天然缺前面曲目。改**轮转**：点按曲置顶 + 其余按原序跟随
     * （整个可见列表入队，音乐 App 标准语义；repeat all 可轮回前曲）。
     */
    fun playFrom(index: Int, visibleIds: List<String>) {
        if (visibleIds.isEmpty() || index !in visibleIds.indices) return
        val ids = visibleIds.subList(index, visibleIds.size) + visibleIds.subList(0, index)
        execCommand(MusicCommand.PlayTracks(ids))
    }
}

/** repeat 模式常量镜像（避免 UI 层直接依赖 media3 Player） */
private object PlaybackStateRepeat {
    const val OFF = 0
    const val ONE = 1
    const val ALL = 2
}

/**
 * 新建定时对话框（MU4.3）：时间文本（+1m / 21:30 / 完整时间）+ 模式（FIXED 歌单 / AUTO 意图）
 * + 周期。提交走 FC 通道 music_schedule（对话框确认=用户主权确认）。
 */
@Composable
private fun ScheduleDialog(
    playlists: List<Pair<PlaylistEntity, Int>>,
    onDismiss: () -> Unit,
    onConfirm: (time: String, mode: String, playlistId: String?, hint: String?,
                recurring: Boolean, intervalSec: Long?) -> Unit
) {
    var time by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(ScheduledPlaybackEntity.MODE_FIXED) }
    var selectedPlaylist by rememberSaveable { mutableStateOf<String?>(null) }
    var hint by rememberSaveable { mutableStateOf("") }
    var recurring by rememberSaveable { mutableStateOf(false) }
    var interval by rememberSaveable { mutableStateOf("86400") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建定时播放") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MusicScheduleTimeField(time) { time = it }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mode == ScheduledPlaybackEntity.MODE_FIXED,
                        onClick = { mode = ScheduledPlaybackEntity.MODE_FIXED },
                        label = { Text("指定歌单") }, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp))
                    FilterChip(selected = mode == ScheduledPlaybackEntity.MODE_AUTO,
                        onClick = { mode = ScheduledPlaybackEntity.MODE_AUTO },
                        label = { Text("智能选曲") }, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp))
                }
                if (mode == ScheduledPlaybackEntity.MODE_FIXED) {
                    if (playlists.isEmpty()) {
                        Text("暂无歌单——先在上方创建", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error)
                    } else {
                        Text("歌单", style = MaterialTheme.typography.labelSmall)
                        playlists.forEach { (pl, count) ->
                            FilterChip(
                                selected = selectedPlaylist == pl.id,
                                onClick = { selectedPlaylist = if (selectedPlaylist == pl.id) null else pl.id },
                                label = { Text("《${pl.name}》$count 首") }, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp))
                        }
                    }
                } else {
                    OutlinedTextField(value = hint, onValueChange = { hint = it },
                        label = { Text("建单意图（如 学习轻音乐）") }, singleLine = true)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = recurring, onCheckedChange = { recurring = it })
                    Text("周期", style = MaterialTheme.typography.bodySmall)
                    if (recurring) {
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(value = interval, onValueChange = { interval = it },
                            label = { Text("间隔秒") }, singleLine = true,
                            modifier = Modifier.width(110.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(time.trim(), mode,
                        if (mode == ScheduledPlaybackEntity.MODE_FIXED) selectedPlaylist else null,
                        if (mode == ScheduledPlaybackEntity.MODE_AUTO) hint.trim().ifBlank { null } else null,
                        recurring, interval.toLongOrNull())
                },
                enabled = time.isNotBlank() &&
                        (mode == ScheduledPlaybackEntity.MODE_AUTO || selectedPlaylist != null)
            ) { Text("设置") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicScreen(onBack: () -> Unit, viewModel: MusicViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    MusicLibraryContent(state, onBack, viewModel)
}

@Composable
internal fun MusicLibraryContent(state: MusicLibraryUiState, onBack: () -> Unit, viewModel: MusicViewModel) {
    var query by rememberSaveable { mutableStateOf("") }

    val visible = remember(state.tracks, query) {
        if (query.isBlank()) state.tracks
        else state.tracks.filter {
            it.displayTitle.contains(query, true) ||
                    it.relativePath.contains(query, true) ||
                    it.artist?.contains(query, true) == true
        }
    }

    Scaffold(topBar = { ZeroPageHeader("音乐库", onBack) }) { padding ->
        // MU4.3-fix（L3 实测：歌单/定时区非 Lazy 展开挤死曲目列表至一行）：
        // 整页单一 LazyColumn——全部区块 item 化，页面整体滚动
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.loading) item { Text("正在读取音乐库…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            state.readError?.let { message ->
                item {
                    ZeroCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(message)
                            TextButton(onClick = viewModel::refresh, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                                Text("重新读取")
                            }
                        }
                    }
                }
            }
            state.operationError?.let { message ->
                item {
                    ZeroCard(Modifier.fillMaxWidth(), MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer) {
                        Column(Modifier.padding(16.dp)) { Text("操作未完成", style = MaterialTheme.typography.titleSmall); Text(message) }
                    }
                }
            }
            // ═══ 扫描/引导卡 ═══
            item {
            ZeroCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.LibraryMusic, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("音乐库 · ${state.tracks.size} 首",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (state.busy) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        FilledTonalButton(onClick = { viewModel.scan() }, enabled = !state.busy) {
                            Icon(Icons.Filled.Refresh, contentDescription = null,
                                Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("扫描")
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (state.dirConfigured)
                            "将音频放入工作目录的 llmmusic 文件夹，扫描后即可播放。支持子文件夹。"
                        else "未设置工作目录 — 先到 资料 → 知识与工作文件 选择目录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    state.lastScanResult?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            }

            // ═══ now-playing（MU2.3：曲名/位置/进度/控制/队列序号）═══
            item {
            ZeroCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    val pb = state.playback
                    if (pb.trackId == null && pb.queueSize == 0) {
                        Text("尚未播放。选择曲目，或在对话中让 Zero 播放。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(pb.title ?: "-", style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold)
                                Text(
                                    buildString {
                                        append(pb.artist ?: "未知艺术家")
                                        append(" · 队列 ${pb.queueIndex + 1}/${pb.queueSize}")
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            // 播放/暂停
                            FilledIconButton(onClick = { viewModel.togglePlayPause() }, enabled = !state.busy,
                                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                                Icon(
                                    if (pb.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = if (pb.isPlaying) "暂停" else "播放"
                                )
                            }
                        }
                        // 进度（拖动结束 seek；播放中每秒 ticker 刷新）
                        var dragging by remember { mutableStateOf(false) }
                        var dragPos by remember { mutableFloatStateOf(0f) }
                        Slider(
                            value = if (dragging) dragPos
                            else if (state.durMs > 0) state.posMs.toFloat() / state.durMs
                            else 0f,
                            onValueChange = { dragging = true; dragPos = it },
                            onValueChangeFinished = {
                                dragging = false
                                viewModel.seekTo((dragPos * state.durMs).toLong())
                            },
                            modifier = Modifier.fillMaxWidth(), enabled = !state.busy
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${state.posMs / 1000}s", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row {
                                IconButton(onClick = { viewModel.toggleShuffle() }) {
                                    Icon(Icons.Filled.Shuffle, contentDescription = "随机",
                                        tint = if (pb.shuffle) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { viewModel.cycleRepeat() }) {
                                    Icon(
                                        if (pb.repeatMode == 1) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                                        contentDescription = "循环",
                                        tint = if (pb.repeatMode != 0) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Text("${state.durMs / 1000}s", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // 上一首/下一首
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            IconButton(onClick = { viewModel.prev() }) {
                                Icon(Icons.Filled.SkipPrevious, contentDescription = "上一首")
                            }
                            Spacer(Modifier.width(24.dp))
                            IconButton(onClick = { viewModel.next() }) {
                                Icon(Icons.Filled.SkipNext, contentDescription = "下一首")
                            }
                        }
                    }
                }
            }
            }

            // ═══ 搜索框 ═══
            item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索标题 / 艺术家 / 路径") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true
            )
            }

            // ═══ 歌单区（MU3.2）═══
            if (state.playlists.isNotEmpty() || state.tracks.isNotEmpty()) {
                item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("歌单 · ${state.playlists.size}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    var showCreate by rememberSaveable { mutableStateOf(false) }
                    var newName by rememberSaveable { mutableStateOf("") }
                    TextButton(onClick = { showCreate = true }, enabled = visible.isNotEmpty()) {
                        Text("从列表建单")
                    }
                    if (showCreate) {
                        AlertDialog(
                            onDismissRequest = { showCreate = false },
                            title = { Text("创建歌单") },
                            text = {
                                OutlinedTextField(value = newName, onValueChange = { newName = it },
                                    label = { Text("歌单名") }, singleLine = true)
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    if (newName.isNotBlank()) {
                                        viewModel.createPlaylistFrom(newName.trim(), visible.map { it.id })
                                        showCreate = false; newName = ""
                                    }
                                }) { Text("创建") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showCreate = false }) { Text("取消") }
                            }
                        )
                    }
                }
                }
                state.playlists.forEach { (pl, count) ->
                    item(key = pl.id) {
                    var confirmDelete by rememberSaveable { mutableStateOf(false) }
                    ZeroCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("《${pl.name}》$count 首",
                                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                pl.sourcePrompt?.let {
                                    Text("源于「${it.take(24)}」", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            FilledTonalButton(onClick = { viewModel.playPlaylist(pl.id) }) { Text("播放") }
                            Spacer(Modifier.width(4.dp))
                            IconButton(onClick = { confirmDelete = true }) {
                                Icon(Icons.Filled.Delete,
                                    contentDescription = "删除歌单",
                                    tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    if (confirmDelete) {
                        AlertDialog(
                            onDismissRequest = { confirmDelete = false },
                            title = { Text("删除歌单") },
                            text = { Text("确定删除《${pl.name}》（$count 首）？仅删歌单引用，音乐文件保留。") },
                            confirmButton = {
                                TextButton(onClick = {
                                    viewModel.deletePlaylist(pl.id); confirmDelete = false
                                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                            },
                            dismissButton = {
                                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
                            }
                        )
                    }
                    }
                }
            }

            // ═══ 定时播放区（MU4.3）═══
            item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("定时播放 · ${state.schedules.size}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                var showAdd by rememberSaveable { mutableStateOf(false) }
                TextButton(onClick = { showAdd = true }) { Text("新建") }
                if (showAdd) {
                    ScheduleDialog(
                        playlists = state.playlists,
                        onDismiss = { showAdd = false },
                        onConfirm = { time, mode, plId, hint, rec, intervalSec ->
                            viewModel.setSchedule(time, mode, plId, hint, rec, intervalSec)
                            showAdd = false
                        })
                }
            }
            }
            if (state.schedules.isEmpty()) {
                item {
                Text("暂无定时播放。可以新建，或在对话中设置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            state.schedules.forEach { s ->
                item(key = s.id) {
                var confirmCancel by rememberSaveable { mutableStateOf(false) }
                ZeroCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                                    .format(java.util.Date(s.triggerAt)) +
                                        (if (s.isRecurring) " · 每${s.recurringMs / 1000}s" else ""),
                                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                "${s.mode} · ${
                                    when (s.status) {
                                        ScheduledPlaybackEntity.STATUS_ACTIVE -> "待触发"
                                        ScheduledPlaybackEntity.STATUS_FIRING -> "触发中"
                                        ScheduledPlaybackEntity.STATUS_FIRED -> "已完成"
                                        ScheduledPlaybackEntity.STATUS_FAILED -> "播放失败 · 查看运行记录"
                                        ScheduledPlaybackEntity.STATUS_MISSED -> "已错过"
                                        ScheduledPlaybackEntity.STATUS_SKIPPED_CAP -> "触顶跳过"
                                        ScheduledPlaybackEntity.STATUS_CANCELLED -> "已取消"
                                        else -> s.status
                                    }
                                } · " + (s.playlistId?.let { state.playlistNames[it] ?: "歌单已删" }
                                    ?: s.hint?.take(16) ?: "无歌单"),
                                style = MaterialTheme.typography.labelSmall,
                                color = when (s.status) {
                                    ScheduledPlaybackEntity.STATUS_ACTIVE -> MaterialTheme.colorScheme.primary
                                    ScheduledPlaybackEntity.STATUS_FAILED,
                                    ScheduledPlaybackEntity.STATUS_MISSED,
                                    ScheduledPlaybackEntity.STATUS_SKIPPED_CAP -> MaterialTheme.colorScheme.error
                                    ScheduledPlaybackEntity.STATUS_CANCELLED,
                                    ScheduledPlaybackEntity.STATUS_FIRED -> MaterialTheme.colorScheme.onSurfaceVariant
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                })
                        }
                        if (s.status == ScheduledPlaybackEntity.STATUS_ACTIVE) {
                            IconButton(onClick = { confirmCancel = true }) {
                                Icon(Icons.Filled.Delete, contentDescription = "取消定时",
                                    tint = MaterialTheme.colorScheme.error)
                            }
                        } else if (s.status in setOf(ScheduledPlaybackEntity.STATUS_FIRED, ScheduledPlaybackEntity.STATUS_FAILED, ScheduledPlaybackEntity.STATUS_MISSED,
                                ScheduledPlaybackEntity.STATUS_SKIPPED_CAP, ScheduledPlaybackEntity.STATUS_CANCELLED)) {
                            // MU4.3-fix（L3 实测：终态项不可删挤占页面）：终态记录直接清除
                            //（fired/missed/cancelled 已无业务价值，无破坏性免确认）
                            IconButton(onClick = { viewModel.removeSchedule(s.id) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "清除记录",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (confirmCancel) {
                    AlertDialog(
                        onDismissRequest = { confirmCancel = false },
                        title = { Text("取消定时") },
                        text = { Text("确定取消这条定时播放？") },
                        confirmButton = {
                            TextButton(onClick = {
                                viewModel.cancelSchedule(s.id); confirmCancel = false
                            }) { Text("取消定时", color = MaterialTheme.colorScheme.error) }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmCancel = false }) { Text("保留") }
                        }
                    )
                }
                }
            }

            // ═══ 库列表（同页 LazyColumn 尾部——不再嵌套第二 LazyColumn）═══
            if (visible.isEmpty()) {
                item {
                Text(
                    if (state.loading) "正在读取曲目…"
                    else if (state.readError != null && state.tracks.isEmpty()) "读取未完成，请重新读取。"
                    else if (state.tracks.isEmpty()) "暂无曲目，扫描后显示音乐。"
                    else "无匹配「$query」",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
                }
            } else {
                    itemsIndexed(visible, key = { _, t -> t.id }) { index, t ->
                        ZeroCard(
                            Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).clickable(enabled = !state.busy) {
                                viewModel.playFrom(index, visible.map { it.id })
                            }) {
                            Column(Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        t.displayTitle.ifBlank { t.relativePath.substringAfterLast('/') },
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (t.missCount > 0) {
                                        Text("缺失中", style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error)
                                    }
                                }
                                Text(
                                    buildString {
                                        append(t.relativePath)
                                        t.artist?.let { append(" · $it") }
                                        append(" · ${t.durationMs / 1000 / 60}:${"%02d".format(t.durationMs / 1000 % 60)}")
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
            }
        }
    }
}
