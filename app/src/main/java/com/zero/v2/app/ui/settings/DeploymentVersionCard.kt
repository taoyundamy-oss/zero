package com.zero.v2.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zero.v2.app.ui.theme.ZeroCard
import com.zero.v2.app.ui.theme.minTouchTarget
import com.zero.v2.core.moduleapi.ReleaseVersionStatus
import com.zero.v2.core.moduleapi.StatusFact
import com.zero.v2.core.moduleapi.SystemStateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** 部署状态（U3 部署版本卡数据源——绑定真实 ReleaseManager journal）。 */
data class DeploymentStatus(
    val active: ReleaseVersionStatus? = null,
    val history: List<ReleaseVersionStatus> = emptyList(),
    val freshness: StatusFact = StatusFact.UNKNOWN,
    val loaded: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class DeploymentStatusViewModel @Inject constructor(
    private val repository: SystemStateRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DeploymentStatus())
    val state: StateFlow<DeploymentStatus> = _state.asStateFlow()
    private var loadJob: Job? = null

    fun load(moduleId: String) {
        loadJob?.cancel()
        _state.value = DeploymentStatus()
        loadJob = viewModelScope.launch {
            try {
                repository.snapshots.collect { snapshot ->
                    val observation = snapshot.termux.deployments.find { it.moduleId == moduleId }
                    _state.value = DeploymentStatus(active = observation?.active,
                        history = observation?.recent.orEmpty(), freshness = observation?.freshness ?: StatusFact.UNKNOWN,
                        loaded = true, error = when (observation?.freshness) {
                            StatusFact.LIVE -> null
                            StatusFact.STALE -> "部署观测已过期，等待后台更新"
                            else -> "部署状态未知，等待运行端回报"
                        })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.value = DeploymentStatus(loaded = true, error = "部署记录读取失败，请重试")
            }
        }
    }
}

/**
 * 部署版本卡（U3 / 设计§7）——绑定真实部署 journal（E1 ReleaseManager）。
 *
 * 展示模块当前活动版本（contentHash/state/时间）+ 最近部署历史——落实"参考图状态是样例，
 * 正式 UI 绑定真实系统状态"。非硬编码。
 */
@Composable
fun DeploymentVersionCard(
    moduleId: String,
    viewModel: DeploymentStatusViewModel = hiltViewModel(),
) {
    val visible = com.zero.v2.app.ui.theme.rememberMotionActive()
    LaunchedEffect(moduleId, visible) { if (visible) viewModel.load(moduleId) }
    val status by viewModel.state.collectAsState()

    DeploymentVersionContent(status) { viewModel.load(moduleId) }
}

/** Same production projection, without repository ownership in rendering tests. */
@Composable
internal fun DeploymentVersionContent(status: DeploymentStatus, onReload: () -> Unit) {
    ZeroCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("部署版本", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            TextButton(onClick = onReload, modifier = Modifier.minTouchTarget()) { Text("重新读取状态") }
            val active = status.active
            if (active == null) {
                Text(status.error ?: if (status.loaded) "暂无受管活动版本" else "正在读取部署状态…",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val live = status.loaded && status.freshness == StatusFact.LIVE && status.error == null
                Text(if (live) "当前活动版本" else "上次观测版本", style = MaterialTheme.typography.bodyMedium)
                VersionIdentity(active)
                Text(if (live) "当前部署状态" else "上次观测状态", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                DeploymentStateBadge(active.state)
                Text("更新：${formatTime(active.updatedAt)}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!live) Text(when (status.freshness) {
                    StatusFact.STALE -> "观测已过期"
                    else -> "当前部署状态尚未确认"
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                status.error?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            val recent = status.history.filter { it.deploymentId != active?.deploymentId }.take(3)
            if (recent.isNotEmpty()) {
                Text("最近部署", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                recent.forEach { entry ->
                    Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            VersionIdentity(entry)
                            Text("历史部署状态", style = MaterialTheme.typography.labelMedium)
                            DeploymentStateBadge(entry.state)
                            Text("更新：${formatTime(entry.updatedAt)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VersionIdentity(version: ReleaseVersionStatus) {
    Text(version.contentHash.take(8).ifBlank { "版本摘要未提供" },
        style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    Text("部署：${version.deploymentId.take(12).ifBlank { "标识未提供" }}",
        style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun DeploymentStateBadge(state: String) {
    val c = MaterialTheme.colorScheme
    val (container, content) = when (state) {
        "ACTIVE" -> c.primaryContainer to c.onPrimaryContainer
        "READY", "CANARY" -> c.tertiaryContainer to c.onTertiaryContainer
        "FAILED", "ROLLING_BACK" -> c.errorContainer to c.onErrorContainer
        else -> c.surfaceVariant to c.onSurfaceVariant
    }
    val label = when (state) {
        "ACTIVE" -> "已启用"
        "READY" -> "准备就绪"
        "CANARY" -> "灰度观察中"
        "FAILED" -> "部署失败"
        "ROLLING_BACK" -> "正在回退"
        "ROLLED_BACK" -> "已回退"
        "BLOCKED" -> "部署受阻"
        else -> "状态未识别：${state.ifBlank { "未提供" }}"
    }
    Surface(shape = MaterialTheme.shapes.medium, color = container, contentColor = content) {
        Text(label, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
    }
}

private fun formatTime(ts: Long): String = if (ts > 0)
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts)) else "更新时间未知"
