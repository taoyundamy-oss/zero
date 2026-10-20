package com.zero.v2.app.ui.trace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.*
import com.zero.v2.core.trace.AgentTrace
import com.zero.v2.core.trace.NodeType

/**
 * Agent 思维链时间轴组件 — Zero v2.2。
 *
 * 对标 Alife 对话看板的白盒可观测性。
 * 订阅 AgentTraceManager 的输出，按 traceId 分组展示每一步 LLM 推理和 FC 调用。
 *
 * 零反射：UI 只遍历 AgentTrace 列表，不依赖反射。
 */
@Composable
fun AgentTraceTimeline(
    traces: List<AgentTrace>,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (traces.isEmpty()) return

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = com.zero.v2.app.ui.theme.ZeroShapes.card,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            // 摘要行（v5.7: 摘要区 weight+两行换行，修复 "28057t" 被截为 "nt"）
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onToggle)
                .clearAndSetSemantics {
                    role = Role.Button
                    contentDescription = "运行记录，${buildTraceSummary(traces)}"
                    stateDescription = if (isExpanded) "已展开" else "已收起"
                    onClick(label = if (isExpanded) "收起运行记录" else "展开运行记录") { onToggle(); true }
                },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("运行记录", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                    Text(buildTraceSummary(traces), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (isExpanded) "收起运行记录" else "展开运行记录",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // 展开详情
            AnimatedVisibility(visible = isExpanded) {
                Column(Modifier.padding(top = 8.dp)) {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.heightIn(max = 300.dp)
                    ) {
                        items(traces, key = { "${it.traceId}_${it.sequence}" }) { trace ->
                            TraceNode(trace)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TraceNode(trace: AgentTrace) {
    val (icon, color) = when (trace.nodeType) {
        NodeType.USER_INPUT -> Icons.Outlined.PersonOutline to MaterialTheme.colorScheme.primary
        NodeType.LLM_REASONING -> Icons.Outlined.Psychology to MaterialTheme.colorScheme.tertiary
        NodeType.LLM_OUTPUT -> Icons.Outlined.ChatBubbleOutline to MaterialTheme.colorScheme.secondary
        NodeType.TOOL_CALL_START -> Icons.Outlined.Build to MaterialTheme.colorScheme.primary
        NodeType.TOOL_CALL_RESULT -> Icons.Outlined.CheckCircleOutline to MaterialTheme.colorScheme.primary
        NodeType.ERROR -> Icons.Outlined.ErrorOutline to MaterialTheme.colorScheme.error
        NodeType.COMPRESSION -> Icons.Outlined.Inventory2 to MaterialTheme.colorScheme.onSurfaceVariant
        NodeType.GUARD_CHECK -> Icons.Outlined.Security to MaterialTheme.colorScheme.onSurfaceVariant
        NodeType.POKE_RECEIVED -> Icons.Outlined.FlashOn to MaterialTheme.colorScheme.tertiary
        NodeType.POKE_SENT -> Icons.Outlined.Outbound to MaterialTheme.colorScheme.tertiary
        NodeType.SYSTEM_EVENT -> Icons.Outlined.Notifications to MaterialTheme.colorScheme.onSurfaceVariant
        NodeType.CONFIG_CHANGE -> Icons.Outlined.Settings to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "[${trace.sequence}] ${trace.summary}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = color, maxLines = 2
            )
            if (trace.tokensUsed != null || trace.durationMs != null) {
                val meta = buildString {
                    if (trace.tokensUsed != null) append("${trace.tokensUsed} tokens")
                    if (trace.tokensUsed != null && trace.durationMs != null) append(" · ")
                    if (trace.durationMs != null) append("${trace.durationMs} ms")
                }
                Text(meta, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun buildTraceSummary(traces: List<AgentTrace>): String {
    val toolCalls = traces.count { it.nodeType == NodeType.TOOL_CALL_START }
    val errors = traces.count { it.nodeType == NodeType.ERROR }
    val totalTokens = traces.mapNotNull { it.tokensUsed }.sum()
    return buildString {
        append("${traces.size} 步")
        if (toolCalls > 0) append(" · $toolCalls 工具")
        if (totalTokens > 0) append(" · ${totalTokens}t")
        if (errors > 0) append(" · $errors 错误")
    }
}
