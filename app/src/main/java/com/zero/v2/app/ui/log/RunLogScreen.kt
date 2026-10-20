package com.zero.v2.app.ui.log

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zero.v2.app.ui.theme.ZeroCard
import com.zero.v2.app.ui.theme.ZeroPageHeader
import com.zero.v2.app.ui.theme.minTouchTarget
import com.zero.v2.core.trace.AgentTrace
import com.zero.v2.core.trace.NodeType

/** Read-only production timeline. Personal traces remain owned by the existing repository. */
@Composable
fun RunLogScreen(onBack: () -> Unit, viewModel: RunLogViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(Unit) { viewModel.reload() }
    RunLogContent(state, onBack, viewModel::reload) { clipboard.setText(AnnotatedString(it)) }
}

@Composable
internal fun RunLogContent(state: RunLogState, onBack: () -> Unit, onReload: () -> Unit, onCopy: (String) -> Unit) {
    var selectedTraceId by rememberSaveable { mutableStateOf<String?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val filtered = state.traceIds.filter { id ->
        searchQuery.isBlank() || id.contains(searchQuery, true) || state.traces[id]?.any {
            it.callId?.contains(searchQuery, true) == true || it.functionName?.contains(searchQuery, true) == true ||
                it.summary.contains(searchQuery, true) || it.detail?.contains(searchQuery, true) == true
        } == true
    }
    Scaffold(topBar = { ZeroPageHeader("运行记录", onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(), state = listState,
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "controls") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("查看对话与任务的执行过程", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(searchQuery, { searchQuery = it }, Modifier.fillMaxWidth(),
                        label = { Text("搜索运行记录") }, placeholder = { Text("记录标识、工具调用或内容") }, singleLine = true)
                    TextButton(onClick = onReload, enabled = !state.loading, modifier = Modifier.minTouchTarget()) { Text("重新读取") }
                    TextButton(onClick = { onCopy(state.traceIds.joinToString("\n\n") { formatTrace(state, it) }) },
                        enabled = state.traceIds.isNotEmpty(), modifier = Modifier.minTouchTarget()) { Text("复制全部记录") }
                }
            }
            if (state.historyError != null || state.loading) item(key = "feedback") {
                ZeroCard(Modifier.fillMaxWidth()) { Text(state.historyError ?: "正在读取运行记录…",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (state.traceIds.isEmpty() && !state.loading && state.historyError == null) item(key = "empty") {
                Text("暂无运行记录", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (state.traceIds.isNotEmpty() && filtered.isEmpty()) item(key = "unmatched") {
                Text("没有匹配的运行记录", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            filtered.forEach { id ->
                val summary = state.summaries[id]
                val expanded = searchQuery.isNotBlank() || selectedTraceId == id
                item(key = "record:$id") {
                    ZeroCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (id in state.historyIds) "历史记录" else "当前记录", style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            SelectionContainer { Text(id, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurface) }
                            summary?.let {
                                Text("${it.eventCount} 个事件 · ${it.totalTokensUsed} tokens · ${it.totalDurationMs} ms", style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (it.errorCount > 0) Text("${it.errorCount} 次错误", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                            }
                            if (searchQuery.isBlank()) TextButton(onClick = { selectedTraceId = if (expanded) null else id },
                                modifier = Modifier.minTouchTarget().semantics { contentDescription = "${if (expanded) "收起" else "展开"}记录 $id" }) { Text(if (expanded) "收起详情" else "展开详情") }
                            TextButton(onClick = { onCopy(formatTrace(state, id)) },
                                modifier = Modifier.minTouchTarget().semantics { contentDescription = "复制记录 $id" }) { Text("复制此记录") }
                        }
                    }
                }
                if (expanded) itemsIndexed(state.traces[id].orEmpty(), key = { index, _ -> "event:$id:$index" }) { _, event -> TraceLine(event) }
            }
        }
    }
}

/** 格式化单条 Trace 为可复制文本 */
internal fun formatTrace(state: RunLogState, traceId: String): String {
    val summary = state.summaries[traceId]
    val traces = state.traces[traceId] ?: return "Trace: $traceId"
    return buildString {
        appendLine("Trace: $traceId")
        if (summary != null) {
            appendLine("Token: ${summary.totalTokensUsed} · ${summary.totalDurationMs}ms" +
                (if (summary.errorCount > 0) " · 错误:${summary.errorCount}次" else ""))
        }
        traces.forEach { trace ->
            appendLine("  [${trace.sequence}] ${trace.functionName?.let { "$it${trace.callId?.let { id -> "#$id" } ?: ""}: " } ?: ""}${trace.summary}" +
                (if (trace.detail != null) " | ${trace.detail}" else ""))
        }
    }.trimEnd()
}

@Composable
private fun TraceLine(trace: AgentTrace) {
    val label = when (trace.nodeType) {
        NodeType.USER_INPUT -> "用户输入"
        NodeType.LLM_REASONING -> "思考过程"
        NodeType.LLM_OUTPUT -> "回复"
        NodeType.TOOL_CALL_START -> "工具调用"
        NodeType.TOOL_CALL_RESULT -> "工具结果"
        NodeType.ERROR -> "执行错误"
        NodeType.COMPRESSION -> "上下文整理"
        NodeType.GUARD_CHECK -> "规则检查"
        NodeType.POKE_RECEIVED -> "收到事件"
        NodeType.POKE_SENT -> "发送事件"
        NodeType.SYSTEM_EVENT -> "系统事件"
        NodeType.CONFIG_CHANGE -> "配置变更"
    }
    ZeroCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${trace.sequence} · $label", style = MaterialTheme.typography.labelLarge,
                color = if (trace.nodeType == NodeType.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            trace.functionName?.let { Text("工具：$it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
            trace.callId?.let { SelectionContainer { Text("调用：$it", style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            SelectionContainer { Text(trace.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
            trace.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                Text("详细内容", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer { Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
            }
        }
    }
}
