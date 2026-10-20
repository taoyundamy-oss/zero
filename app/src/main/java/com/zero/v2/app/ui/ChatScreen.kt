package com.zero.v2.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.hilt.navigation.compose.hiltViewModel
import com.zero.v2.app.ui.chat.ConversationHistoryDialog
import com.zero.v2.app.ui.GenerationState
import com.zero.v2.app.ui.theme.ZeroAdaptiveLayout
import com.zero.v2.app.ui.theme.ZeroShapes
import com.zero.v2.app.ui.theme.ZeroSpacing
import com.zero.v2.app.ui.trace.AgentTraceTimeline
import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.MessageRole
import com.zero.v2.core.common.MessageSource

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    onOpenLive: () -> Unit,
    liveActive: Boolean = false,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val isSwitchingConversation by viewModel.isSwitchingConversation.collectAsState()
    val sendState by viewModel.sendState.collectAsState()
    val retryUserId by viewModel.retryUserId.collectAsState()
    val traceTraces by viewModel.traceTraces.collectAsState()
    val traceExpanded by viewModel.traceExpanded.collectAsState()
    val conversationList by viewModel.conversationList.collectAsState()
    val currentConversationId by viewModel.currentConversationId.collectAsState()
    val pendingAttachment by viewModel.pendingAttachment.collectAsState()
    val activeCharacterName by viewModel.activeCharacterName.collectAsState()
    val listState = rememberLazyListState()
    var showHistoryDialog by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.refreshActiveCharacter()
    }

    // 系统图片选择器
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) { /* Some document providers only grant temporary access. Read errors remain visible. */ }
            viewModel.setAttachment(uri.toString(), context.contentResolver.getType(uri) ?: "application/octet-stream")
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            try {
                listState.animateScrollToItem(messages.size - 1)
            } catch (_: Exception) {
                // LazyColumn 尚未完成布局时忽略滚动异常
            }
        }
    }

    // U2：折叠屏展开/大屏 → 内容居中 + 最大宽度约束（避免单行过长/composer 横跨整屏）；
    // 手机竖屏（COMPACT）→ widthIn(max=720) 不约束，全宽。IME/insets 不变。
    Box(modifier = Modifier.fillMaxSize().imePadding().navigationBarsPadding()) {
        Column(
            modifier = Modifier
                .widthIn(max = ZeroAdaptiveLayout.CHAT_MAX_WIDTH_DP.dp)
                .fillMaxSize()
                .align(Alignment.Center)  // BoxScope.align 需 2D Alignment；fillMaxSize 下垂直无效，仅水平居中
        ) {
        // 顶栏
        // S8.3（图纸 A1 · 基线 2）：左上=当前角色名，不含 "ZeroV2" 字样；无角色时显示 Zero
        com.zero.v2.app.ui.theme.ZeroPageHeader(activeCharacterName.ifBlank { "Zero" }, actions = {
            TextButton(onClick = { showHistoryDialog = true }) { Text("历史") }
            IconButton(onClick = { viewModel.regenerate() }, enabled = !isGenerating && !isSwitchingConversation) { Icon(Icons.Filled.Refresh, "重新生成") }
        })
        // 消息列表
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = ZeroSpacing.m),  // U2：tokens.space.m
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            itemsIndexed(messages, key = { _, m -> m.id.ifEmpty { "msg_${m.timestamp}_${m.role}" } }) { _, msg ->
                MessageBubble(msg)
            }
            if (traceTraces.isNotEmpty()) {
                item(key = "agent_trace") {
                    AgentTraceTimeline(
                        traces = traceTraces,
                        isExpanded = traceExpanded,
                        onToggle = { viewModel.toggleTrace() }
                    )
                }
            }
            if (sendState != GenerationState.IDLE) {
                item {
                    com.zero.v2.app.ui.chat.ChatGenerationStatus(sendState,
                        onRetry = if (retryUserId != null) viewModel::retryFailedTurn else null,
                        retryEnabled = !isGenerating && !isSwitchingConversation && !liveActive)
                }
            }
        }
        // 附件预览
        if (pendingAttachment != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                com.zero.v2.app.ui.chat.AttachmentTile(pendingAttachment!!, viewModel::clearAttachment)
            }
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.zero.v2.core.common.MediaRoute.entries.forEach { route ->
                    FilterChip(selected = pendingAttachment?.route == route,
                        onClick = { viewModel.setAttachmentRoute(route) },
                        label = { Text(when (route) {
                            com.zero.v2.core.common.MediaRoute.AUTO -> "自动"
                            com.zero.v2.core.common.MediaRoute.NATIVE -> "多模态"
                            com.zero.v2.core.common.MediaRoute.TOOLS -> "工具处理"
                        }) })
                }
            }
        }
        // 输入栏（R3：Live 进行中禁用文本输入，防 ChatBot 互斥锁竞争）
        com.zero.v2.app.ui.chat.ChatComposer(
            inputText, viewModel::setInput, liveActive,
            (inputText.isNotBlank() || pendingAttachment != null) && !isGenerating && !isSwitchingConversation && !liveActive,
            onAttach = { imagePickerLauncher.launch(arrayOf("image/*", "audio/*", "video/*")) },
            onOpenLive = onOpenLive,
            onSend = { viewModel.sendMessage(); keyboardController?.hide() },
            isGenerating = isGenerating, stopping = sendState == GenerationState.STOPPING, onStop = viewModel::stopGeneration)
    }
    }  // U2: Box 折叠自适应容器闭合

    // 对话历史对话框
    if (showHistoryDialog) {
        ConversationHistoryDialog(
            conversations = conversationList,
            currentConversationId = currentConversationId,
            onSelectConversation = { id ->
                viewModel.loadConversation(id)
                showHistoryDialog = false
            },
            onDeleteConversation = { id ->
                viewModel.deleteConversation(id)
            },
            onNewConversation = {
                viewModel.clearConversation()
                showHistoryDialog = false
            },
            onDismiss = { showHistoryDialog = false }
        )
    }
}

@Composable
internal fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == MessageRole.USER
    // S2R.5（§2.3 UI · v3.1 统一右位）：系统注入消息卡——图标+来源名+时间+折叠正文，
    // 来源标签与主题语义强调色共用映射，正文保持中性。
    // （sys_ id 旧路径随 S2R.4 systemBubbles 合流层退役——载体统一为消息本体）
    if (isUser && message.source == MessageSource.AUTONOMOUS) {
        SystemEventCard(message)
        return
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        // S2.3/D117：单一折叠执行轨迹卡（thinking + 工具调用合并——不做双轨展示）；
        // 数据取自消息自身字段（thinking/toolCalls，流式期间实时更新）——chatHistory/VM 零改动
        if (!isUser && (!message.thinking.isNullOrBlank() || message.toolCalls.isNotEmpty())) {
            ExecutionTraceCard(message)
        }
        Text(
            // S8.3（裁定 2）：署名 You→「我」；Zero 侧保留
            text = if (isUser) "我" else "Zero",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(2.dp))
        message.attachments.forEach { attachment ->
            com.zero.v2.app.ui.chat.AttachmentTile(attachment)
            Spacer(Modifier.height(8.dp))
        }
        Card(
            shape = ZeroShapes.userMessage,
            colors = CardDefaults.cardColors(
                containerColor = when {
                    isUser -> MaterialTheme.colorScheme.surfaceContainerHigh
                    else -> Color.Transparent
                },
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        ) {
            // v6.8: 可长按选择复制文字（P2）；链接层（修复轮+）：自动识别 URL/深链 → 点击直达
            androidx.compose.foundation.text.selection.SelectionContainer {
                com.zero.v2.app.ui.chat.MessageBody(message.content.orEmpty(), Modifier.padding(12.dp)) { body ->
                    LinkifiedText(
                        text = body,
                        color = MaterialTheme.colorScheme.onSurface,
                        linkColor = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/**
 * 链接化文本（修复轮+，2026-08-31）：自动识别 https/http、深链（amapuri/geo/tel/mailto），
 * 点击 → ACTION_VIEW 直达（深链开特定 App；https 开默认浏览器；深链无对应 App 时
 * 回退 https 网关，仍失败 Toast 提示）。长按选择复制不受影响（SelectionContainer 外裹）。
 */
@Composable
internal fun LinkifiedText(
    text: String,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color,
    linkColor: androidx.compose.ui.graphics.Color
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    /** 点击处理：原样 ACTION_VIEW（https→默认浏览器；amapuri→高德 App；tel/mailto→系统应用）；
     *  ActivityNotFound（深链无对应 App）→ 回退 https 网关；仍失败 Toast */
    fun openLink(url: String) {
        try {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: android.content.ActivityNotFoundException) {
        } catch (_: Exception) {
        }
        val webFallback = when {
            url.startsWith("amapuri://") -> "https://uri.amap.com/" + url.removePrefix("amapuri://")
            url.startsWith("geo:") -> url.removePrefix("geo:").split(",").let { c ->
                "https://uri.amap.com/navigation?to=${c.getOrNull(1) ?: ""},${c.getOrNull(0) ?: ""}&callnative=1" }
            else -> null
        }
        if (webFallback != null) {
            try {
                context.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(webFallback))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
            }
        }
        android.widget.Toast.makeText(context, "未找到可打开「$url」的应用", android.widget.Toast.LENGTH_SHORT).show()
    }

    val annotated = remember(text, linkColor) {
        androidx.compose.ui.text.buildAnnotatedString {
            com.zero.v2.app.ui.chat.InlineMessageText.parse(text).forEach { part ->
                val begin = length
                pushStyle(androidx.compose.ui.text.SpanStyle(
                    fontWeight = if (part.bold) androidx.compose.ui.text.font.FontWeight.SemiBold else null,
                    fontFamily = if (part.code) androidx.compose.ui.text.font.FontFamily.Monospace else null,
                ))
                append(part.text)
                pop()
                part.url?.let { url ->
                    addLink(androidx.compose.ui.text.LinkAnnotation.Clickable(
                        tag = url,
                        styles = androidx.compose.ui.text.TextLinkStyles(style = androidx.compose.ui.text.SpanStyle(
                            color = linkColor, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)),
                    ) { openLink(url) }, begin, length)
                }
            }
        }
    }
    Text(text = annotated, modifier = modifier, color = color)
}

// ═══ S2R.5（§2.3 UI · v3.1 统一右位）：系统消息卡 ═══

/** 来源细名 → 图标+短名（D-P1：UI 同源取图标/配色） */
private fun systemSourceLabel(source: String?): String = when (source) {
    "WECHAT_PRIVATE" -> "💬 微信"
    "QQ_PRIVATE" -> "🐧 QQ"
    "WECOM_PRIVATE" -> "🏢 企业微信"
    "SELF_DRIVE" -> "🧠 自驱动"
    "CLOUD_TASK" -> "⚡ 云任务"
    "SCHEDULED_PLAYBACK" -> "🎵 定时播放"
    "NOTIFICATION" -> "📱 应用通知"
    "BATTERY_FULL" -> "🔋 充电完成"
    "BATTERY_LOW" -> "🪫 电量低"
    "NIGHT_SCREEN" -> "🌙 夜间亮屏"
    "CONTINUOUS_USE" -> "⏱ 连续使用"
    "BACKLOG" -> "🔄 启动补检"
    else -> "📌 ${source ?: "事件"}"
}

/**
 * S2R.5 系统消息卡（替代 S8.3 SystemBubbleCard——载体从会话级气泡改为消息本体）：
 * 头部=图标+来源名+时间（HH:mm），正文=净载荷默认折叠 40 字点击展开（图纸 A 沿用）；
 * 统一右位；主题中性容器与来源强调色。消息内容为 VM 投影后可见正文（≪sys≫ 指令段已剥离）。
 */
@Composable
internal fun SystemEventCard(message: ChatMessage) {
    var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
    val colors = com.zero.v2.app.ui.theme.systemEventColors(MaterialTheme.colorScheme, message.systemSource)
    // 净载荷 = 可见正文剥硬头（"[事件·X] "）——硬头语义已由图标+来源名呈现
    val content = message.content.orEmpty()
    val payload = content.substringAfter("] ").let { if (it == content) content else it }
    val time = remember(message.id) {
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(message.timestamp))
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = ZeroShapes.card,
            colors = CardDefaults.cardColors(containerColor = colors.container, contentColor = colors.body)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        systemSourceLabel(message.systemSource),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.content
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        time,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.meta
                    )
                }
                if (payload.isNotBlank()) {
                    Text(
                        if (expanded) payload
                        else payload.take(40) + if (payload.length > 40) "…" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.body,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    if (payload.length > 40) {
                        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(if (expanded) "收起" else "展开事件")
                        }
                    }
                }
            }
        }
    }
}

/**
 * S2.3/D117：执行轨迹卡——thinking 与工具调用合并为单一折叠卡（用户裁定"对话清洁"：
 * 不做双轨展示）。默认折叠一行摘要；展开按时间序穿插思考段/工具段。
 * G5：工具行附 toolCallId（前 8 位）——摘要不可还原全量，"查看完整"经 RunLog 按 id 检索。
 * 数据取自消息自身（thinking/toolCalls 流式期间实时更新）——chatHistory/VM 零改动。
 */
@Composable
internal fun ExecutionTraceCard(message: ChatMessage) {
    var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
    val thinking = message.thinking.orEmpty()
    val tools = message.toolCalls
    val thinkingLabel = if (thinking.isBlank()) "" else "思考 ${thinking.length}字"
    val header = buildString {
        append("执行轨迹")
        if (tools.isNotEmpty()) append(" · ${tools.size} 工具")
        if (thinkingLabel.isNotBlank()) append(" · $thinkingLabel")
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        shape = ZeroShapes.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(header, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text(if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary)
            }
            if (expanded) {
                if (thinking.isNotBlank()) {
                    Text(thinking, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface)
                }
                tools.forEach { tc ->
                    Column(modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            "${tc.name}（id=${tc.id.takeLast(8)}）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        if (!tc.arguments.isNullOrBlank()) {
                            Text(tc.arguments.take(80) + if (tc.arguments.length > 80) "…" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (tools.isNotEmpty()) {
                    Text(
                        "完整参数与结果可在运行记录中查看",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}
