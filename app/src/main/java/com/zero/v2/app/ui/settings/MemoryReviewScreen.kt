package com.zero.v2.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zero.v2.app.ui.theme.ZeroPageHeader
import com.zero.v2.core.common.MemoryStatus
import com.zero.v2.core.common.MemoryType
import com.zero.v2.core.data.entity.MemoryConflictEntity
import com.zero.v2.core.data.entity.MemoryEntity
import com.zero.v2.core.data.entity.MemoryReviewEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MemoryReviewScreen(onBack: () -> Unit, viewModel: MemoryReviewViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    MemoryReviewContent(state, onBack, viewModel::retry, viewModel::propose, viewModel::decide, viewModel::source, viewModel::suggestPreferences, viewModel::searchHistory, viewModel::importHistory, viewModel::proposeEdited)
}

@Composable
internal fun MemoryReviewContent(
    state: MemoryReviewState, onBack: () -> Unit, onRetry: () -> Unit,
    onPropose: (MemoryEntity, String, MemoryType, String, String) -> Unit,
    onDecide: (String, Boolean) -> Unit,
    readSource: suspend (String) -> String,
    onSuggestPreferences: () -> Unit = {},
    onSearchHistory: (String) -> Unit = {},
    onImportHistory: (com.zero.v2.core.data.entity.MessageEntity) -> Unit = {},
    onProposeEdited: ((MemoryEntity, String, MemoryType, String, String, com.zero.v2.core.data.entity.MemoryReviewTimes) -> Unit)? = null,
) {
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    var draft by remember { mutableStateOf<MemoryEntity?>(null) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.notice) {
        if (state.notice?.startsWith("提案已保存") == true) draft = null
    }
    Scaffold(topBar = { ZeroPageHeader("历史记忆复核", onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("仅保存提案不会改变记忆。逐条确认后生成新版本并归档旧原文；无来源记录不冒充历史原话。")
                Text("复核记录保存在本机，并随新版数据备份导出；恢复保留本机已有决定。")
                TextButton(onClick = { historyOpen = true }, enabled = !state.busy) { Text("迁移历史聊天原文") }
                Button(onClick = { choosing = true }, enabled = !state.loading && !state.busy && state.error == null) {
                    Text("选择记忆创建提案")
                }
                TextButton(onClick = onSuggestPreferences, enabled = !state.loading && !state.busy && state.error == null) {
                    Text("查找重复偏好建议（仅本地）")
                }
                Text("仅比较不同用户消息中完全相同的明示偏好和范围，不推断规范或跨场景偏好；仍需核对反例。")
                state.preferenceCandidates.forEach { candidate ->
                    PreferenceSuggestionCard(candidate, state.busy, readSource) { draft = candidate.memory }
                }
                if (state.loading) Text("正在读取")
                state.error?.let { Text(it); TextButton(onClick = onRetry) { Text("重新读取") } }
                state.notice?.let { Text(it) }
                if (!state.loading && state.error == null && state.proposals.isEmpty()) Text("暂无复核提案")
            }
            items(state.proposals, key = { it.id }) { p ->
                OutlinedCard(onClick = { selected = p.id }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("${reviewStateLabel(p.state)} · ${reviewTypeLabel(p.proposedType)}")
                        Text(reviewBody(p.proposedContent), maxLines = 3)
                        Text("范围：${p.proposedScope} · ${reviewDate(p.createdAt)}")
                    }
                }
            }
        }
    }
    if (historyOpen) HistoricalSourceDialog(state, onSearchHistory, onImportHistory) { historyOpen = false }
    if (choosing) MemoryReviewPicker(state.memories, { choosing = false }) {
        draft = it; choosing = false
    }
    draft?.let { m ->
        MemoryReviewEditDialog(m, state.busy, state.notice, { draft = null }) { content, type, scope, reason, times ->
            if (onProposeEdited != null) onProposeEdited(m, content, type, scope, reason, times)
            else onPropose(m, content, type, scope, reason)
        }
    }
    state.proposals.find { it.id == selected }?.let { p ->
        val m = state.memories.find { it.id == p.memoryId }
        MemoryReviewDetail(p, m, state.busy, readSource, { selected = null }) { accept ->
            onDecide(p.id, accept); selected = null
        }
    }
}

@Composable
private fun MemoryReviewPicker(memories: List<MemoryEntity>, onDismiss: () -> Unit, onSelect: (MemoryEntity) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择原记忆") }, text = {
        Column {
            OutlinedTextField(query, { query = it }, label = { Text("搜索记忆") }, modifier = Modifier.fillMaxWidth())
            val rows = memories.filter { it.status == MemoryStatus.ACTIVE && it.content.contains(query, true) }
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                if (rows.isEmpty()) item { Text("没有匹配的有效记忆") }
                items(rows, key = { it.id }) { m ->
                    TextButton(onClick = { onSelect(m) }, modifier = Modifier.fillMaxWidth()) {
                        Text(m.content, maxLines = 3)
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun MemoryReviewDetail(p: MemoryReviewEntity, m: MemoryEntity?, busy: Boolean,
    readSource: suspend (String) -> String, onDismiss: () -> Unit, onDecide: (Boolean) -> Unit) {
    var confirmed by rememberSaveable(p.id) { mutableStateOf(false) }
    var source by remember(p.id) { mutableStateOf("正在核对来源") }
    LaunchedEffect(p.id) {
        source = p.sourceMessageId?.let { readSource(it) } ?: "历史来源未知；确认会形成新的用户证据"
    }
    val valid = m?.status == MemoryStatus.ACTIVE && MemoryConflictEntity.version(m) == p.baseVersion
    AlertDialog(onDismissRequest = onDismiss, title = { Text("${reviewStateLabel(p.state)} · 复核详情") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("原记录：${m?.content?.let(::reviewBody) ?: "已不可用"}")
            Text("原分类：${m?.let { reviewTypeLabel(it.type) } ?: "未知"} · 记录于${m?.createdAt?.let(::reviewDate) ?: "未知"}")
            Text(source)
            Text("拟确认：${reviewBody(p.proposedContent)}\n分类：${reviewTypeLabel(p.proposedType)}\n范围：${p.proposedScope}")
            Text("理由：${p.reason}\n提案日期：${reviewDate(p.createdAt)}")
            if (p.editTimes) Text("拟确认发生时间：${p.proposedOccurredAt?.let(::reviewDate) ?: "未知"}\n拟确认有效期：" +
                "${p.proposedValidFrom?.let(::reviewDate) ?: "未指定"} 至 ${p.proposedValidUntil?.let(::reviewDate) ?: "未指定"}")
            Text("发生时间：${m?.occurredAt?.let(::reviewDate) ?: "未知"}\n有效期：" +
                "${m?.validFrom?.let(::reviewDate) ?: "未指定"} 至 ${m?.validUntil?.let(::reviewDate) ?: "未指定"}")
            p.decidedAt?.let { Text("处理日期：${reviewDate(it)}") }
            if (p.state == "PENDING") {
                Text("确认会归档旧记录及依赖它的摘要；原文保留。")
                if (!valid) Text("原记忆已变化或不可用；不能确认此提案")
                Row {
                    Checkbox(confirmed, { confirmed = it }, enabled = valid && !busy)
                    Text(if (p.proposedType == MemoryType.DIRECTIVE) "我明确要求长期遵循此规范，并确认归档旧版本"
                        else "我确认内容、分类、范围与时间，并同意归档旧版本")
                }
            } else Text(if (p.state == "ACCEPTED") "已保存本次用户确认；未认证历史原话" else "本提案未改写历史记忆")
        }
    }, confirmButton = {
        if (p.state == "PENDING") TextButton(onClick = { onDecide(true) }, enabled = valid && confirmed && !busy) {
            Text("确认并生成新版本")
        } else TextButton(onClick = onDismiss) { Text("关闭") }
    }, dismissButton = {
        Row {
            if (p.state == "PENDING") TextButton(onClick = { onDecide(false) }, enabled = !busy) { Text("拒绝提案") }
            TextButton(onClick = onDismiss) { Text("返回") }
        }
    })
}

private fun reviewDate(time: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(time))
private fun reviewStateLabel(value: String) = when (value) {
    "PENDING" -> "待确认"; "ACCEPTED" -> "已确认"; "REJECTED" -> "已拒绝"; else -> "已失效"
}
internal fun reviewTypeLabel(type: MemoryType) = when (type) {
    MemoryType.FACT -> "事实"; MemoryType.EVENT -> "事件"; MemoryType.PREFERENCE -> "偏好"
    MemoryType.DIRECTIVE -> "规范"; MemoryType.RELATIONSHIP -> "关系"; MemoryType.TASK -> "任务"; else -> type.name
}


@Composable
private fun PreferenceSuggestionCard(candidate: PreferenceSuggestions.Candidate, busy: Boolean,
    readSource: suspend (String) -> String, onSelect: () -> Unit) {
    var expanded by remember(candidate.memory.id) { mutableStateOf(false) }
    var evidence by remember(candidate.memory.id) { mutableStateOf("正在读取来源") }
    LaunchedEffect(candidate, expanded) {
        if (expanded) evidence = candidate.supportingMessages.map { readSource(it) }.joinToString("\n\n")
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("重复偏好候选：${candidate.memory.content}")
            Text("${candidate.supportingMessages.size}条独立消息；范围：${candidate.memory.scope}")
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起来源" else "查看支持来源") }
            if (expanded) Text(evidence)
            TextButton(onClick = onSelect, enabled = !busy) { Text("编辑为人工复核提案") }
        }
    }
}

internal fun reviewBody(content: String): String = com.zero.v2.core.common.TaskMemory.decode(content)?.let {
    "任务：${it.text}\n状态：${it.status}\n下一步：${it.next}\n验收：${it.acceptance}\n资料：${it.pointer.orEmpty()}"
} ?: content
