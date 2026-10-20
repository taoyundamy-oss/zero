package com.zero.v2.app.ui.live

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zero.v2.app.ui.theme.ZeroCard
import com.zero.v2.app.ui.theme.ZeroPageHeader
import com.zero.v2.app.ui.theme.ZeroMotion
import com.zero.v2.app.ui.theme.rememberMotionActive
import com.zero.v2.app.ui.theme.rememberReduceMotion
import com.zero.v2.services.realtime.RealtimeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** All service and privacy writes continue through the existing LiveSessionViewModel. */
@Composable
fun LiveSessionScreen(onBack: () -> Unit, viewModel: LiveSessionViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var denied by rememberSaveable { mutableStateOf(false) }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        if (granted) viewModel.startSession()
    }
    val start = {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            denied = false
            viewModel.startSession()
        } else mic.launch(Manifest.permission.RECORD_AUDIO)
    }
    // Entering Live is explicit consent; restoring its saved route after OS death is not.
    var entryStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!entryStarted) {
            entryStarted = true
            start()
        }
    }
    LiveSessionContent(state, LivePageActions(onBack, start, viewModel::stopSpeaking,
        viewModel::toggleScreenShare, { viewModel.endSession(); onBack() }, viewModel::setWhitelist),
        initialWhitelist = viewModel.whitelist(), loadApps = viewModel::loadAppEntries, permissionDenied = denied)
}

data class LivePageActions(
    val back: () -> Unit,
    val start: () -> Unit,
    val interrupt: () -> Unit,
    val share: () -> Unit,
    val end: () -> Unit,
    val saveWhitelist: (Set<String>) -> Unit
)

/** Production renderer with isolated callback boundary for visual and restoration acceptance. */
@Composable
fun LiveSessionContent(state: LiveUiState, actions: LivePageActions,
    initialWhitelist: Set<String>, loadApps: () -> List<AppEntry>, permissionDenied: Boolean = false,
    reduceMotion: Boolean = rememberReduceMotion()) {
    var showWhitelist by rememberSaveable { mutableStateOf(false) }
    var committedWhitelist by remember(initialWhitelist) { mutableStateOf(initialWhitelist) }
    val resumed = rememberMotionActive()
    BackHandler(onBack = actions.back)
    Scaffold(
        topBar = { ZeroPageHeader("Zero Live", actions.back) },
        bottomBar = { LiveControls(state, actions) { showWhitelist = true } },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp, vertical = 12.dp)) {
            val orbSize = minOf(maxWidth.value - 16f, maxHeight.value * .38f, 240f).coerceAtLeast(48f).dp
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (permissionDenied) "需要麦克风权限，点击状态球重新授权" else liveStatusLabel(state),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                StateOrb(state.state, orbSize,
                    pulse = LiveMotionPolicy.shouldPulse(state.state, state.sessionActive, resumed, reduceMotion, showWhitelist),
                    animateState = resumed && !reduceMotion && !showWhitelist,
                    active = state.sessionActive, onStart = actions.start)
                LiveTranscript(state.transcript, Modifier.weight(1f).fillMaxWidth())
            }
        }
        if (showWhitelist) LiveWhitelistDialog(committedWhitelist, loadApps,
            onDismiss = { showWhitelist = false }, onSave = {
                actions.saveWhitelist(it)
                committedWhitelist = it
                showWhitelist = false
            })
    }
}

fun liveStatusLabel(state: LiveUiState): String = when (state.state) {
    RealtimeState.IDLE -> if (state.sessionActive) "正在连接…" else "点击状态球开始"
    RealtimeState.LISTENING -> "正在听你说"
    RealtimeState.THINKING -> "正在思考"
    RealtimeState.SPEAKING -> "正在回答"
    RealtimeState.INTERRUPTED -> "已打断"
    RealtimeState.OBSERVING_SCREEN -> "正在阅读屏幕"
}

@Composable
private fun LiveTranscript(entries: List<TranscriptEntry>, modifier: Modifier) {
    val list = rememberLazyListState()
    var previousHead by remember { mutableStateOf(entries.firstOrNull()?.id) }
    // Capture the old layout before LazyColumn applies the incoming bounded list.
    val readingKey = list.layoutInfo.visibleItemsInfo.firstOrNull()?.key
    LaunchedEffect(entries.firstOrNull()?.id) {
        if (previousHead != entries.firstOrNull()?.id && readingKey != null &&
            entries.isNotEmpty() && entries.none { it.id == readingKey }) {
            list.scrollToItem(0)
        }
        previousHead = entries.firstOrNull()?.id
    }
    var previousTail by remember { mutableStateOf(entries.lastOrNull()?.id) }
    LaunchedEffect(entries.lastOrNull()?.id) {
        if (entries.isNotEmpty() && !list.isScrollInProgress &&
            previousTail != entries.last().id &&
            list.layoutInfo.visibleItemsInfo.lastOrNull()?.key == previousTail) {
            list.animateScrollToItem(entries.lastIndex)
        }
        previousTail = entries.lastOrNull()?.id
    }
    ZeroCard(modifier) {
        if (entries.isEmpty()) Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Text("对话转写将在这里显示", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else LazyColumn(Modifier.fillMaxSize().padding(16.dp), state = list,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(entries, key = { it.id }) { entry ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (entry.isUser) "你" else "Zero", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(entry.text, style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveControls(state: LiveUiState, actions: LivePageActions, showWhitelist: () -> Unit) {
    FlowRow(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalIconButton(onClick = showWhitelist, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Apps, contentDescription = "屏幕阅读白名单")
        }
        FilledTonalIconButton(onClick = actions.interrupt, enabled = state.state == RealtimeState.SPEAKING,
            modifier = Modifier.size(48.dp)) { Icon(Icons.Default.StopCircle, contentDescription = "停止说话") }
        FilledTonalIconButton(onClick = actions.interrupt, enabled = state.sessionActive,
            modifier = Modifier.size(48.dp)) { Icon(Icons.Default.MicOff, contentDescription = "打断") }
        FilledTonalIconButton(onClick = actions.share, enabled = state.sessionActive, modifier = Modifier.size(48.dp)) {
            Icon(if (state.screenSharing) Icons.Default.StopScreenShare else Icons.AutoMirrored.Filled.ScreenShare,
                contentDescription = if (state.screenSharing) "关闭屏幕共享" else "屏幕共享")
        }
        FilledIconButton(onClick = actions.end, modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError)) {
            Icon(Icons.Default.CallEnd, contentDescription = "结束 Live")
        }
    }
}

/** Theme-derived light-to-accent orb; pulse exists only for the visible, active destination. */
@Composable
private fun StateOrb(state: RealtimeState, size: Dp, pulse: Boolean, animateState: Boolean,
    active: Boolean, onStart: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val accent = when (state) {
        RealtimeState.THINKING -> colors.tertiary
        RealtimeState.SPEAKING -> colors.secondary
        else -> colors.primary
    }
    val displayedAccent by animateColorAsState(accent,
        animationSpec = ZeroMotion.stateSpec(!animateState), label = "liveStateColor")
    val scale = if (pulse) {
        val transition = rememberInfiniteTransition(label = "liveOrb")
        val value by transition.animateFloat(1f, 1.055f,
            infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "livePulse")
        value
    } else 1f
    Box(Modifier.size(size).padding(8.dp).scale(scale)
        .background(Brush.verticalGradient(listOf(colors.surfaceContainerLowest, colors.primaryContainer, displayedAccent)), CircleShape)
        .clickable(enabled = !active, role = Role.Button, onClickLabel = "开始 Live", onClick = onStart)
        .semantics { contentDescription = if (active) "Live 状态球" else "开始 Live" })
}

@Composable
private fun LiveWhitelistDialog(initial: Set<String>, loadApps: () -> List<AppEntry>,
    onDismiss: () -> Unit, onSave: (Set<String>) -> Unit) {
    var entries by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var selected by rememberSaveable(stateSaver = listSaver<Set<String>, String>(
        save = { it.toList() }, restore = { it.toSet() })) { mutableStateOf(initial) }
    var search by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { entries = withContext(Dispatchers.IO) { loadApps() } }
    AlertDialog(modifier = Modifier.imePadding(), onDismissRequest = onDismiss,
        title = { Text("屏幕阅读白名单") }, text = {
        Column(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("仅选中的应用允许读取屏幕文本。保存后生效。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(search, { search = it }, label = { Text("搜索应用") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            val filtered = entries.filter { search.isBlank() || it.label.contains(search, true) || it.packageName.contains(search, true) }
            LazyColumn(Modifier.weight(1f)) {
                items(filtered, key = { it.packageName }) { app ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .toggleable(app.packageName in selected, role = Role.Checkbox) {
                            selected = if (it) selected + app.packageName else selected - app.packageName
                        }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(app.packageName in selected, onCheckedChange = null)
                        Column(Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.bodyMedium)
                            Text(app.packageName, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { onSave(selected) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
