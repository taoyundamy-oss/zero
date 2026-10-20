package com.zero.v2.app.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zero.v2.core.common.MemoryType
import com.zero.v2.core.common.TaskMemory
import com.zero.v2.core.data.entity.MemoryEntity
import com.zero.v2.app.ui.theme.ZeroCard
import com.zero.v2.app.ui.theme.ZeroPageHeader
import com.zero.v2.app.ui.theme.minTouchTarget
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 记忆管理页 — Step 4.12（DESIGN: 浏览 + 搜索 + FilterChip(type) + 详情）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryManageScreen(
    onBack: () -> Unit,
    viewModel: MemoryManageViewModel = hiltViewModel(),
    onReview: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    MemoryManageContent(state, onBack, viewModel::setQuery, viewModel::setTypeFilter,
        viewModel::delete, viewModel::clearDeleteError, viewModel::retryRead, viewModel::resolveConflict, onReview)
}

/** Production projection; data and transactional deletion remain owned by the ViewModel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemoryManageContent(
    state: MemoryUiState,
    onBack: () -> Unit,
    onQuery: (String) -> Unit,
    onTypeFilter: (MemoryType?) -> Unit,
    onDelete: (String) -> Unit,
    onClearDeleteError: () -> Unit,
    onRetryRead: () -> Unit = {},
    onResolveConflict: (String, String) -> Unit = { _, _ -> },
    onReview: () -> Unit = {},
) {
    var detail by remember { mutableStateOf<MemoryEntity?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.deleteError) {
        state.deleteError?.let { error ->
            snackbar.showSnackbar(error)
            onClearDeleteError()
        }
    }

    val filtered = remember(state.all, state.query, state.typeFilter) {
        state.all.asSequence()
            .filter { m -> state.typeFilter == null || m.type == state.typeFilter }
            .filter { m ->
                state.query.isBlank() ||
                        m.content.contains(state.query, ignoreCase = true) ||
                        (m.keywords?.contains(state.query, ignoreCase = true) == true)
            }
            .sortedByDescending { it.createdAt }
            .toList()
    }
    val listState = rememberLazyListState()
    LaunchedEffect(state.query, state.typeFilter) { listState.scrollToItem(0) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            ZeroPageHeader("记忆管理", onBack)
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            TextButton(onClick = onReview, modifier = Modifier.padding(horizontal = 16.dp)) { Text("历史记忆复核") }
            val pending = state.conflicts.firstOrNull { c ->
                state.all.any { it.id == c.leftId && it.status == com.zero.v2.core.common.MemoryStatus.ACTIVE } &&
                    state.all.any { it.id == c.rightId && it.status == com.zero.v2.core.common.MemoryStatus.ACTIVE }
            }
            pending?.let { c ->
                ZeroCard(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("待确认的记忆关系 · 模型判断仅供参考", style = MaterialTheme.typography.titleSmall)
                        Text("A：${memoryDisplayText(state.all.first { it.id == c.leftId })}", maxLines = 3,
                            modifier = Modifier.clickable { detail = state.all.first { it.id == c.leftId } })
                        Text("B：${memoryDisplayText(state.all.first { it.id == c.rightId })}", maxLines = 3,
                            modifier = Modifier.clickable { detail = state.all.first { it.id == c.rightId } })
                        Text("点击条目查看完整原文及日期。", style = MaterialTheme.typography.bodySmall)
                        Text("选择保留一条会归档另一条，原文仍保留。", style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { onResolveConflict(c.id, "LEFT") }) { Text("保留A") }
                            TextButton(onClick = { onResolveConflict(c.id, "RIGHT") }) { Text("保留B") }
                            TextButton(onClick = { onResolveConflict(c.id, "BOTH") }) { Text("两条均保留") }
                        }
                    }
                }
            }
            Text(when {
                state.all.isEmpty() && state.isLoading -> "正在读取记忆"
                state.all.isEmpty() && state.readError != null -> "记忆读取未完成"
                else -> "${state.all.size} 条记忆 · 保留来源与核验状态"
            },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            // 搜索栏
            OutlinedTextField(
                value = state.query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text("搜索记忆内容/关键词") }
            )
            state.readError?.let { error ->
                ZeroCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(error, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface)
                        TextButton(onClick = onRetryRead, enabled = !state.isLoading,
                            modifier = Modifier.minTouchTarget()) { Text("重新读取") }
                    }
                }
            }
            // 类型筛选
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    modifier = Modifier.minTouchTarget(),
                    selected = state.typeFilter == null,
                    onClick = { onTypeFilter(null) },
                    label = { Text("全部") })
                MemoryType.entries.forEach { type ->
                    FilterChip(
                        modifier = Modifier.minTouchTarget(),
                        selected = state.typeFilter == type,
                        onClick = { onTypeFilter(type) },
                        label = { Text(typeLabel(type)) })
                }
            }

            if (state.isLoading && state.all.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.readError != null && state.all.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("读取未完成", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (state.all.isEmpty()) "暂无记忆\n可以在对话中保存值得记住的内容"
                        else "无匹配结果",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { it.id }) { memory ->
                        MemoryCard(memory = memory, onClick = { detail = memory })
                    }
                }
            }
        }
    }

    detail?.let { memory ->
        MemoryDetailDialog(
            memory = memory,
            onDelete = {
                onDelete(memory.id)
                detail = null
            },
            onDismiss = { detail = null }
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun MemoryCard(memory: MemoryEntity, onClick: () -> Unit) {
    ZeroCard(modifier = Modifier.fillMaxWidth().minTouchTarget().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (memory.tier == 2) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = if (memory.tier == 2) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface
                ) {
                    Text("T${memory.tier}", style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                }
                Surface(shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
                    Text(typeLabel(memory.type), style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                }
                if (memory.status.name == "ARCHIVED") {
                    Text("已归档", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("×${memory.reinforcementCount}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(memorySourceLabel(memory), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                memoryDisplayText(memory),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun MemoryDetailDialog(memory: MemoryEntity, onDelete: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记忆详情") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SelectionContainer { Text(memoryDisplayText(memory), style = MaterialTheme.typography.bodyMedium) }
                HorizontalDivider()
                Text(
                    "层级 ${memory.tier} · ${typeLabel(memory.type)} · ${if (memory.status.name == "ARCHIVED") "已归档" else "有效"}\n" +
                            "${memorySourceLabel(memory)} · 重要性 ${"%.2f".format(memory.importance)} · 强化 ×${memory.reinforcementCount}\n" +
                            "压缩层级 L${memory.level} · 记录于 ${formatDate(memory.createdAt)}\n" +
                            "发生时间：${memory.occurredAt?.let(::formatDate) ?: "未知"} · 版本 ${memory.revision}\n" +
                            "有效期：${memory.validFrom?.let(::formatDate) ?: "未指定"} — ${memory.validUntil?.let(::formatDate) ?: "未指定"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDelete, modifier = Modifier.minTouchTarget()) {
                Icon(Icons.Outlined.Delete, contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.minTouchTarget()) { Text("关闭") } }
    )
}

private fun memoryDisplayText(memory: MemoryEntity): String {
    val task = if (memory.type == MemoryType.TASK) TaskMemory.decode(memory.content) else null
    if (task == null) return memory.content
    val status = when (task.status) {
        "ACTIVE" -> "进行中"
        "WAITING" -> "等待中"
        "DONE" -> "已完成"
        "CANCELLED" -> "已撤销"
        else -> task.status
    }
    return "状态：$status\n${task.text}\n下一步：${task.next}\n验收：${task.acceptance}" +
        (task.pointer?.let { "\n资料：$it" } ?: "")
}

private fun memorySourceLabel(memory: MemoryEntity): String =
    if (memory.origin == "USER_QUOTE" && memory.sourceMessageId != null ||
        com.zero.v2.core.data.entity.GraphMemoryEvidence.eligible(memory)) "用户原文提取"
    else if (memory.origin == "USER_CONFIRMED") "用户复核确认，非历史原话认证"
    else if (memory.origin == "MODEL_NOTE") "模型笔记，未获用户确认" else "来源未核验"

private fun typeLabel(type: MemoryType): String = when (type) {
    MemoryType.DIRECTIVE -> "规范"
    MemoryType.FACT -> "事实"
    MemoryType.PREFERENCE -> "偏好"
    MemoryType.EVENT -> "事件"
    MemoryType.RELATIONSHIP -> "关系"
    MemoryType.SKILL -> "技能"
    MemoryType.TASK -> "任务"
    MemoryType.INSIGHT -> "洞察"
}

private fun formatDate(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))
