package com.zero.v2.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.zero.v2.app.ui.theme.ZeroCard
import com.zero.v2.app.ui.theme.ZeroPageHeader
import com.zero.v2.app.ui.theme.minTouchTarget
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 动态模块详情/配置页 — M2.4（D68 配置协议 ④）。
 *
 * 模块信息卡（id/origin/状态/传输/端点/依赖/创建时间）+ configSchema 动态渲染三型控件
 * （text=OutlinedTextField / number=Slider+数值（有 min·max）或数值输入框 / toggle=Switch）
 * ——AutoForm 同款精神，动态 schema 手写渲染零反射；**无 configSchema 不渲染配置区**。
 * 保存 → ModuleConfigStore → ACTIVE 模块重启注入 env ZERO_MOD_CONFIG（页面明示，D68 ③）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DynModuleDetailScreen(
    moduleId: String,
    onBack: () -> Unit,
    viewModel: DynamicModulesViewModel = hiltViewModel()
) {
    LaunchedEffect(moduleId) { viewModel.loadDetail(moduleId) }
    val state by viewModel.state.collectAsState()
    DynModuleDetailContent(moduleId, state, onBack, { viewModel.loadDetail(moduleId) },
        viewModel::clearActionMessage, { values -> viewModel.saveConfig(moduleId, values) })
}

/** Production projection; isolated tests replace only deployment observation and command callbacks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DynModuleDetailContent(
    moduleId: String,
    state: DynamicModulesUiState,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onDismissFeedback: () -> Unit,
    onSave: (Map<String, String>) -> Unit,
    deploymentVersion: @Composable (String) -> Unit = { DeploymentVersionCard(it) },
) {
    val module = state.detail
    // 字段编辑态（key→String；保存时 VM 按 schema 类型整形回 JSON）
    var fieldValues by rememberSaveable(moduleId) { mutableStateOf(hashMapOf<String, String>()) }
    var diagnosticsExpanded by rememberSaveable(moduleId) { mutableStateOf(false) }
    var sourceExpanded by rememberSaveable(moduleId) { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    // Stored values that match an edit acknowledge that value. Other edits remain authoritative.
    LaunchedEffect(moduleId, state.detailValues) {
        fieldValues = HashMap(fieldValues.filter { (key, value) -> state.detailValues[key] != value })
    }
    val canEdit = !state.saving && !state.detailLoading && state.detailError == null && moduleId !in state.busyIds

    Scaffold(
        topBar = { ZeroPageHeader("模块详情", onBack) }
    ) { padding ->
        if (module == null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                DynamicModuleStatus(state, onReload, onDismissFeedback, detail = true)
                if (!state.detailLoading && state.detailError == null) {
                    Text("详情尚未加载")
                    TextButton(onClick = onReload) { Text("重新读取") }
                }
            }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding()
                .verticalScroll(scrollState).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DynamicModuleStatus(state, onReload, onDismissFeedback, detail = true)
            // ═══ 模块信息卡 ═══
            ZeroCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(module.id, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                    StateBadge(module.state)
                    val origin = when (module.origin) {
                        "HANDWRITTEN" -> "手写"
                        "GENERATED" -> "生成"
                        else -> module.origin
                    }
                    Text("来源：$origin · 运行时：${module.runtime}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { diagnosticsExpanded = !diagnosticsExpanded }, modifier = Modifier.minTouchTarget()) {
                        Text(if (diagnosticsExpanded) "收起运行信息" else "查看运行信息")
                    }
                    if (diagnosticsExpanded) {
                    Text("上次运行端点（诊断记录）：${module.lastEndpoint.ifBlank { "—" }} · 传输：${module.transport}",
                        style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val deps = module.depsList() + module.termuxDepsList().map { "pkg:$it" }
                    if (deps.isNotEmpty()) {
                        Text("依赖: ${deps.joinToString(" · ")}",
                            style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        "创建: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(module.createdAt))}" +
                            if (module.lastCrashAt > 0) " · 最近崩溃: ${
                                SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(module.lastCrashAt))
                            }" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    }
                }
            }

            // ═══ D68 配置区（无 configSchema 不渲染）═══
            if (state.detailFields.isNotEmpty()) {
                Text("模块配置", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                ZeroCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.detailFields.forEach { field ->
                            DynConfigFieldRow(
                                field = field,
                                initial = fieldValues[field.key] ?: state.detailValues[field.key] ?: field.default,
                                enabled = canEdit,
                                onChanged = { value -> fieldValues = HashMap(fieldValues).apply { put(field.key, value) } }
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        // D68 ③：保存后模块将重启——页面明示
                        Text(
                            if (module.state == "ACTIVE") "保存后将重启模块，使新配置生效。" else "保存后将在下次启用模块时生效。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = {
                                onSave(buildMap {
                                    state.detailFields.forEach { f ->
                                        put(f.key, fieldValues[f.key] ?: state.detailValues[f.key] ?: f.default)
                                    }
                                })
                            },
                            enabled = canEdit,
                            modifier = Modifier.fillMaxWidth().minTouchTarget()
                        ) {
                            if (state.saving) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (state.saving) "保存中…" else "保存配置")
                        }
                    }
                }
            } else {
                Text("此模块没有可调整的配置。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // ═══ U3：部署版本卡（绑定真实 ReleaseManager journal——E1-E3 受控自迭代基建投影）═══
            deploymentVersion(module.id)

            ZeroCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("模块源码", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    TextButton(onClick = { sourceExpanded = !sourceExpanded }, modifier = Modifier.minTouchTarget()) {
                        Text(if (sourceExpanded) "收起源码" else "查看源码")
                    }
                    if (sourceExpanded) SelectionContainer {
                        Text(module.source, style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

/** Controlled inputs: the form owns each value, including exact numeric input. */
@Composable
private fun DynConfigFieldRow(field: DynConfigField, initial: String, enabled: Boolean, onChanged: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(field.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
        if (field.description.isNotBlank()) {
            Text(field.description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when (field.type) {
            "toggle" -> Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = initial == "true", onCheckedChange = { onChanged(it.toString()) }, enabled = enabled,
                    modifier = Modifier.minTouchTarget().semantics { contentDescription = "切换 ${field.label}" })
                Spacer(Modifier.width(8.dp))
                Text(if (initial == "true") "开" else "关", style = MaterialTheme.typography.bodySmall)
            }
            else -> {
                if (field.type == "number" && field.min != null && field.max != null &&
                    field.min.toFloat().isFinite() && field.max.toFloat().isFinite() && field.max > field.min) {
                    val value = initial.toFloatOrNull()?.takeIf { it.isFinite() }
                        ?.coerceIn(field.min.toFloat(), field.max.toFloat()) ?: field.min.toFloat()
                    Slider(value = value, onValueChange = { onChanged(it.toString()) }, enabled = enabled,
                        valueRange = field.min.toFloat()..field.max.toFloat(),
                        modifier = Modifier.minTouchTarget().semantics { contentDescription = "调整 ${field.label}" })
                    Text("范围：${field.min} — ${field.max}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(value = initial, onValueChange = onChanged, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "编辑 ${field.label}" },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = if (field.type == "number") KeyboardType.Decimal else KeyboardType.Text))
            }
        }
    }
}
