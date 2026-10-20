package com.zero.v2.app.ui.module

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import com.zero.v2.app.ui.theme.ZeroCard
import com.zero.v2.app.ui.theme.ZeroPageHeader
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * 模块详情屏幕 — 含 API Key 配置 + AutoForm 参数 + 模型选择。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleDetailScreen(
    moduleId: String,
    onBack: () -> Unit,
    onOpenSettings: (String) -> Unit,
    viewModel: ModuleDetailViewModel = hiltViewModel()
) {
    LaunchedEffect(moduleId) { viewModel.load(moduleId) }
    val state by viewModel.state.collectAsState()
    var editorBack by remember { mutableStateOf<(() -> Unit)?>(null) }

    Scaffold(
        topBar = {
            ZeroPageHeader(state.displayName.ifBlank { "模块详情" },
                onBack = { editorBack?.invoke() ?: onBack() })
        }
    ) { padding ->
        if (state.module == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (state.isLoading) CircularProgressIndicator()
                else ModuleDetailUnavailable(state.loadError ?: "模块暂不可用。", onRetry = { viewModel.load(moduleId) })
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 基本信息
                ZeroCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(state.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(state.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text("ID: ${state.moduleId} · v${state.version} · ${state.category}",
                            style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        Text("状态: ${if (state.isEnabled) "已启用" else "已禁用"}",
                            style = MaterialTheme.typography.labelSmall)
                    }
                }

                // API Key 管理区
                if (state.apiKeyFields.isNotEmpty()) {
                    Text("接口密钥", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                    ZeroCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.apiKeyFields.forEach { field ->
                                ApiKeyField(
                                    label = field.label,
                                    currentValue = field.currentValue,
                                    onSave = { newValue -> viewModel.saveApiKey(field.key, newValue) }
                                )
                            }
                        }
                    }
                }

                if (com.zero.v2.app.ui.settings.SettingsOwnership.dedicatedFields(state.moduleId).isNotEmpty()) {
                    com.zero.v2.app.ui.theme.ZeroNavigationRow("自主行为设置",
                        "报点节奏与记忆整理统一在此配置") { onOpenSettings("agent_behavior") }
                }
                // Remaining module-specific parameters; dedicated fields have one editor.
                if (state.configFields.isNotEmpty()) {
                    Text("参数配置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    state.saveError?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = viewModel::save, modifier = Modifier.fillMaxWidth()) { Text("重试保存") }
                    }
                    ZeroCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.configFields.forEach { field ->
                                key(state.moduleId, field.key) {
                                    ConfigFieldRow(field, onChanged = { key, value ->
                                        viewModel.updateConfigField(key, value)
                                    })
                                }
                            }
                        }
                    }
                }

                // S1.1/S1.2（D115 模块自治）：LLM 消费模块详情页内嵌模型管理区——
                // 端点/模型/Key 三件套经 profile 编辑子页（单入口裁定沿用 S1.1-fix2）。
                // S1.2-d 挂载：TTS/记忆（embedding 消费方）/视觉/搜索；S4.1 挂载 ASR
                val profilesKind = when (state.moduleId) {
                    "openai_chat" -> com.zero.v2.services.llm.LlmProfile.Kind.CHAT
                    "speech_tts" -> com.zero.v2.services.llm.LlmProfile.Kind.TTS
                    "memory" -> com.zero.v2.services.llm.LlmProfile.Kind.EMBEDDING
                    "vision_analyze" -> com.zero.v2.services.llm.LlmProfile.Kind.VISION
                    "web_search" -> com.zero.v2.services.llm.LlmProfile.Kind.SEARCH
                    "speech_asr" -> com.zero.v2.services.llm.LlmProfile.Kind.ASR
                    else -> null
                }
                if (profilesKind != null) {
                    com.zero.v2.app.ui.settings.ModuleProfilesSection(kind = profilesKind,
                        onEditorBackChanged = { editorBack = it })
                    // KB0.3（KA11/裁-4）：记忆详情页后台任务模型只读卡——与 Job 同源 select(CHAT)；
                    // 仅 memory 分支挂载，其他模块详情页零改动
                    if (state.moduleId == "memory") {
                        com.zero.v2.app.ui.settings.MemoryBackgroundModelCard()
                    }
                }

                if (state.moduleId == "skills") {
                    com.zero.v2.app.skills.SkillVerificationCard()
                }

                // 模块工具目录；系统权限和业务校验由执行端负责。
                if (state.functions.isNotEmpty()) {
                    Text("可用工具", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    ZeroCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.functions.forEach { fn ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(fn.name, style = MaterialTheme.typography.labelMedium,
                                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                                    Spacer(Modifier.weight(1f))
                                }
                                Text(fn.description, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}



/** API Key 字段 — 字段级保存按钮（v5.7: 显式保存替代失焦触发，顶栏保存已移除） */
@Composable
private fun ApiKeyField(label: String, currentValue: String, onSave: (String) -> Unit) {
    val isConfigured = currentValue.isNotBlank()
    var text by remember { mutableStateOf("") }
    var showClear by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = text,
        onValueChange = { text = it; saved = false },
        label = { Text(label) },
        placeholder = { Text(if (isConfigured) "已配置，输入新值覆盖" else "输入 API Key") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = if (showClear) VisualTransformation.None
                              else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (text.isNotEmpty()) {
                    IconButton(onClick = { showClear = !showClear }) {
                        Icon(if (showClear) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (showClear) "隐藏" else "显示")
                    }
                    // v5.7: 字段级保存按钮 — 输入后立即可存，无需切换焦点
                    IconButton(onClick = {
                        if (text.isNotBlank()) {
                            onSave(text.trim())
                            saved = true
                            text = ""
                            showClear = false
                        }
                    }) {
                        Icon(Icons.Filled.Check, contentDescription = "保存",
                            tint = if (text.isNotBlank()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        supportingText = {
            when {
                saved -> Text("✅ 已保存", color = MaterialTheme.colorScheme.primary)
                isConfigured && text.isEmpty() -> Text("已配置 (输入后点 ✓ 保存)")
                else -> Text("输入后点右侧 ✓ 保存")
            }
        }
    )
}

/** AutoForm 字段渲染（零反射） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigFieldRow(field: com.zero.v2.core.modulemanager.ModuleConfigParser.FormFieldState,
                            onChanged: (String, String) -> Unit) {
    Column {
        Text(field.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
        if (field.description.isNotBlank()) {
            Text(field.description, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when (field.fieldType) {
            com.zero.v2.core.moduleapi.ConfigFieldType.TOGGLE -> {
                var checked by remember(field.key, field.currentValue) {
                    mutableStateOf(field.currentValue.toBooleanStrictOrNull() ?: false)
                }
                Switch(checked = checked, onCheckedChange = { checked = it; onChanged(field.key, checked.toString()) })
            }
            com.zero.v2.core.moduleapi.ConfigFieldType.SECRET -> {
                var editing by remember(field.key) { mutableStateOf(false) }
                if (editing) {
                    var text by remember(field.key, field.currentValue) { mutableStateOf(field.currentValue) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = text, onValueChange = { text = it; onChanged(field.key, text) },
                            modifier = Modifier.weight(1f), singleLine = true,
                            label = { Text(field.label) }
                        )
                        TextButton(onClick = { editing = false }) { Text("完成") }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("********", modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace)
                        TextButton(onClick = { editing = true }) { Text("修改") }
                    }
                }
            }
            com.zero.v2.core.moduleapi.ConfigFieldType.TEXT -> {
                var text by remember(field.key, field.currentValue) { mutableStateOf(field.currentValue) }
                OutlinedTextField(value = text, onValueChange = { text = it; onChanged(field.key, text) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
            com.zero.v2.core.moduleapi.ConfigFieldType.NUMBER -> {
                var text by remember(field.key, field.currentValue) { mutableStateOf(field.currentValue) }
                OutlinedTextField(value = text, onValueChange = { text = it; onChanged(field.key, text) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            com.zero.v2.core.moduleapi.ConfigFieldType.MULTILINE -> {
                var text by remember(field.key, field.currentValue) { mutableStateOf(field.currentValue) }
                OutlinedTextField(value = text, onValueChange = { text = it; onChanged(field.key, text) },
                    modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5)
            }
            com.zero.v2.core.moduleapi.ConfigFieldType.DROPDOWN -> {
                var expanded by remember { mutableStateOf(false) }
                var selected by remember(field.key, field.currentValue) {
                    mutableStateOf(field.currentValue.ifBlank { field.options.firstOrNull() ?: "" })
                }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    OutlinedTextField(
                        value = selected, onValueChange = {}, readOnly = true,
                        modifier = Modifier.fillMaxWidth().menuAnchor(), singleLine = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) })
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        field.options.forEach { opt ->
                            DropdownMenuItem(text = { Text(opt) }, onClick = {
                                selected = opt; expanded = false; onChanged(field.key, opt)
                            })
                        }
                    }
                }
            }
            com.zero.v2.core.moduleapi.ConfigFieldType.SLIDER -> {
                var value by remember(field.key, field.currentValue) {
                    mutableStateOf(field.currentValue.toFloatOrNull() ?: 0f)
                }
                Slider(value = value, onValueChange = { value = it; onChanged(field.key, value.toString()) },
                    valueRange = field.min.toFloat()..field.max.toFloat())
                Text("${"%.1f".format(value)}", style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}


@Composable
internal fun ModuleDetailUnavailable(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(message, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("重试") }
    }
}
