package com.zero.v2.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.zero.v2.app.ui.theme.ZeroPageHeader
import com.zero.v2.app.ui.theme.ZeroCard
import com.zero.v2.app.ui.theme.minTouchTarget
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zero.v2.core.data.entity.DynamicModuleEntity

/**
 * 动态模块管理页 — M2.4（方案 §5.3 ④ · D64/D67/D68）。
 *
 * 列表卡：状态徽标（ACTIVE/DISABLED/CRASHED）+ origin 标签 + 启停开关 +
 * 端点/transport/依赖信息（lastEndpoint=上次运行调试值，P0-4——真实端点 runtime 回报）+
 * 源码只读查看 + 详情入口（D68 配置页）。
 * perm 恒 AUTO（D67）——列表不设权限位；启停即唯一控制面（M4.1 收口语义）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DynamicModulesScreen(
    onBack: () -> Unit,
    onOpenDetail: (String) -> Unit,
    viewModel: DynamicModulesViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    DynamicModulesContent(state, onBack, onOpenDetail, viewModel::toggle,
        viewModel::retryModules, viewModel::clearActionMessage)
}

/** Shared production projection; test fixtures replace only data and command callbacks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DynamicModulesContent(
    state: DynamicModulesUiState,
    onBack: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onToggle: (String) -> Unit,
    onRetry: () -> Unit,
    onDismissFeedback: () -> Unit,
) {
    var viewingSourceId by rememberSaveable { mutableStateOf<String?>(null) }
    val viewingSource = state.modules.firstOrNull { it.id == viewingSourceId }
    val listState = rememberLazyListState()
    val sourceStateHolder = rememberSaveableStateHolder()
    fun closeSource() {
        viewingSourceId?.let(sourceStateHolder::removeState)
        viewingSourceId = null
    }

    Scaffold(
        topBar = { ZeroPageHeader("动态模块", onBack) }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                DynamicModuleStatus(state, onRetry, onDismissFeedback)
            }
            item {
                Text(
                    "可用工具 ${state.toolCount} 个",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (state.modules.isEmpty() && !state.loading && state.readError == null) {
                item {
                    Text(
                        "暂无动态模块。工作站就绪后，可在对话中创建或加载模块。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(state.modules, key = { it.id }) { module ->
                DynModuleCard(
                    module = module,
                    busy = module.id in state.busyIds,
                    onToggle = { onToggle(module.id) },
                    onDetail = { onOpenDetail(module.id) },
                    onViewSource = { viewingSourceId = module.id }
                )
            }
        }
    }

    // 源码只读查看（M2.4 计划项）
    viewingSource?.let { module ->
        sourceStateHolder.SaveableStateProvider(module.id) {
        AlertDialog(
            onDismissRequest = ::closeSource,
            title = { Text("模块源码") },
            text = {
                Column(
                    Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(module.id, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SelectionContainer {
                        Text(
                            module.source,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = ::closeSource, modifier = Modifier.minTouchTarget()) { Text("关闭") } }
        )
        }
    }
}

@Composable
private fun DynModuleCard(
    module: DynamicModuleEntity,
    busy: Boolean,
    onToggle: () -> Unit,
    onDetail: () -> Unit,
    onViewSource: () -> Unit
) {
    ZeroCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(module.id, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                StateBadge(module.state, Modifier.weight(1f, fill = false))
                if (busy) {
                    CircularProgressIndicator(Modifier.size(24.dp)
                        .semantics { contentDescription = "正在更新 ${module.id}" }, strokeWidth = 2.dp)
                } else {
                    Switch(checked = module.state == "ACTIVE", onCheckedChange = { onToggle() },
                        modifier = Modifier.minTouchTarget().semantics { contentDescription = "启停 ${module.id}" })
                }
            }
            Text("来源 ${module.origin} · ${module.transport} · 依赖 ${module.depsList().size + module.termuxDepsList().size} 项",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Runtime endpoints belong in detail diagnostics; no stale endpoint presented as live here.
            Column(Modifier.fillMaxWidth()) {
                TextButton(onClick = onDetail, enabled = !busy, modifier = Modifier.minTouchTarget().semantics { contentDescription = "详情与配置 ${module.id}" }) {
                    Text("详情与配置")
                }
                TextButton(onClick = onViewSource, modifier = Modifier.minTouchTarget().semantics { contentDescription = "查看源码 ${module.id}" }) { Text("查看源码") }
            }
        }
    }
}

/** Room enablement projection; ACTIVE is intent, not proof of a live process or a mounted connection. */
@Composable
fun StateBadge(state: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val label = when (state) {
        "ACTIVE" -> "已启用"
        "CRASHED" -> "运行异常"
        "STOPPED" -> "已停止"
        "DISABLED" -> "已停用"
        "STARTING" -> "启动中"
        "STOPPING" -> "停止中"
        else -> "状态未识别：${state.ifBlank { "未提供" }}"
    }
    val container = when (state) {
        "ACTIVE" -> colors.primaryContainer
        "CRASHED" -> colors.errorContainer
        else -> colors.surfaceVariant
    }
    val content = when (state) {
        "ACTIVE" -> colors.onPrimaryContainer
        "CRASHED" -> colors.onErrorContainer
        else -> colors.onSurfaceVariant
    }
    Surface(modifier = modifier, shape = MaterialTheme.shapes.medium, color = container, contentColor = content) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}
