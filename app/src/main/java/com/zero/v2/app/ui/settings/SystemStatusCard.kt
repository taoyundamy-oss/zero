package com.zero.v2.app.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zero.v2.app.ui.theme.ZeroMotion
import com.zero.v2.app.ui.theme.ZeroSpacing
import com.zero.v2.app.ui.theme.rememberReduceMotion
import com.zero.v2.app.ui.theme.rememberMotionActive
import com.zero.v2.core.moduleapi.StatusFact
import com.zero.v2.core.moduleapi.SystemSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 系统状态卡（U3 / 设计§7 + 总纲§4）——绑定真实 [SystemSnapshot]（S1 SystemStateRepository 投影）。
 *
 * 展示：运行态/模型路由/预算档位/termux 健康/语音会话——全部读真实状态，非硬编码
 * （落实"参考图中的状态是样例，正式 UI 必须绑定真实系统状态"）。
 *
 * 动效：状态变化时 [animateContentSize]（reduce-motion 时即时，无障碍"减少动态"铁律）。
 * 无障碍：整卡 contentDescription 汇总状态；色点带语义描述（非仅色觉传达）。
 */
@Composable
fun SystemStatusCard(snapshot: SystemSnapshot?) {
    val reduced = rememberReduceMotion()
    val active = rememberMotionActive()
    val reduceMotion = reduced || !active

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = androidx.compose.animation.core.tween(
                    durationMillis = ZeroMotion.durationMs(reduceMotion, com.zero.v2.app.ui.theme.ZeroTokens.MOTION_STATE_MS)
                )),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        if (snapshot == null) {
            Box(Modifier.padding(ZeroSpacing.l), contentAlignment = Alignment.Center) {
                Text("正在获取系统状态…", style = MaterialTheme.typography.bodyMedium)
            }
            return@Card
        }

        val desc = buildString {
            append("系统状态。运行态：${snapshot.runtime.turnOwner}，待执行任务 ${snapshot.runtime.pendingJobs ?: "未知"}。")
            append("${ModelStatusPresentation.text(snapshot.modelRoutes)}。")
            append("预算档位 ${snapshot.budgets.remainingClass}。")
            append("Termux ${snapshot.termux.connection.name}。")
            append(if (snapshot.voice.active) "语音会话进行中。" else "语音空闲。")
            snapshot.retrieval.domains.forEach { append("${it.domain}：${RetrievalStatusPresentation.text(it)}。") }
        }

        Column(
            modifier = Modifier
                .padding(ZeroSpacing.l)
                .semantics { contentDescription = desc },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "系统状态",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                val time = remember(snapshot.capturedAt) {
                    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(snapshot.capturedAt))
                }
                Text(time, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(ZeroSpacing.s))
            // 运行态
            StatusRow(
                label = "运行",
                value = "${snapshot.runtime.turnOwner} · 待执行 ${snapshot.runtime.pendingJobs ?: "未知"}",
            )
            // 模型路由
            StatusRow(
                label = "模型",
                value = ModelStatusPresentation.text(snapshot.modelRoutes),
            )
            StatusRow("记忆", RetrievalStatusPresentation.text(snapshot.retrieval.domains.find { it.domain == "memory" }))
            StatusRow("知识库", RetrievalStatusPresentation.text(snapshot.retrieval.domains.find { it.domain == "knowledge" }))
            val extractionFreshness = com.zero.v2.core.moduleapi.StatusProjection.freshness(
                snapshot.retrieval.extractionObservedAt, snapshot.capturedAt,
                com.zero.v2.core.moduleapi.SystemStateAssembler.TTL_RETRIEVAL_MS)
            StatusRow("提取作业", when (extractionFreshness) {
                StatusFact.LIVE -> snapshot.retrieval.extractionJobs?.toString() ?: "未知"
                StatusFact.STALE -> "观测已过期"
                StatusFact.UNKNOWN -> "未知"
            })
            // 预算档位
            val (budgetLabel, budgetColor) = budgetDisplay(snapshot.budgets.remainingClass)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(budgetColor, "预算档位 $budgetLabel")
                Spacer(Modifier.width(ZeroSpacing.s))
                Text("预算", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.widthIn(min = 56.dp).padding(end = 12.dp))
                Text(budgetLabel, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            }
            // Termux
            val (termuxLabel, termuxColor) = termuxDisplay(snapshot.termux.connection)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(termuxColor, "Termux $termuxLabel")
                Spacer(Modifier.width(ZeroSpacing.s))
                Text("Termux", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.widthIn(min = 56.dp).padding(end = 12.dp))
                Text(termuxLabel, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            }
            StatusRow("工作站", "SDK ${snapshot.termux.sdkVersion ?: "未知"} · 发布协议 ${snapshot.termux.releaseProtocol ?: "未知"}")
            StatusRow("动态模块", if (snapshot.termux.connection == StatusFact.LIVE)
                snapshot.termux.runtimeModules?.let { "${it.count { item -> item.state == "ACTIVE" }}/${it.size} 运行中" } ?: "未知"
                else "状态未知或过期")
            // 语音会话
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val voiceLabel = if (snapshot.voice.active) "进行中" else "空闲"
                StatusDot(
                    if (snapshot.voice.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    "语音 $voiceLabel",
                )
                Spacer(Modifier.width(ZeroSpacing.s))
                Text("语音", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.widthIn(min = 56.dp).padding(end = 12.dp))
                Text("$voiceLabel · ${snapshot.voice.state}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.widthIn(min = 56.dp).padding(end = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StatusDot(color: Color, desc: String) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color, CircleShape)
            .semantics { contentDescription = desc },
    )
}

@Composable
private fun budgetDisplay(remainingClass: String): Pair<String, Color> = when (remainingClass.lowercase()) {
    "ok" -> "充足" to MaterialTheme.colorScheme.primary
    "low" -> "偏低" to MaterialTheme.colorScheme.tertiary
    "exceeded" -> "超限" to MaterialTheme.colorScheme.error
    else -> "未知" to MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun termuxDisplay(connection: StatusFact): Pair<String, Color> = when (connection) {
    StatusFact.LIVE -> "已连接" to MaterialTheme.colorScheme.primary
    StatusFact.UNKNOWN -> "未知" to MaterialTheme.colorScheme.outline
    StatusFact.STALE -> "过期" to MaterialTheme.colorScheme.outline
}
