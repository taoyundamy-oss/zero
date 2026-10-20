package com.zero.v2.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.foundation.clickable
import com.zero.v2.app.ui.theme.ZeroCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import com.zero.v2.services.llm.effectiveGatewayMode
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zero.v2.services.llm.LlmProfile
import com.zero.v2.services.llm.LlmProfileStore
import com.zero.v2.services.llm.ModelCapability
import com.zero.v2.services.llm.ModelSelector
import com.zero.v2.services.llm.OpenAiChatModule
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * 模块模型管理区 — S1.1 模块自治改造（原全局 LlmProfilesScreen 退役，D115 用户修订）。
 *
 * 嵌入各消费模块的详情页（ModuleDetailScreen），按 kind 只显示本模块的 profile：
 * 多模型保存（编辑 baseUrl/modelId/参数）+ 启停 Switch + **「设为主力」手动切换按钮**
 * （单激活：同 kind 其余停用——用户裁定 2026-09-09）。Key 在编辑子页 apiKey 字段配置
 * （S1.1-fix2 裁定：详情页既有 API Keys 卡片退役，编辑子页为唯一入口；SecureKeyStore ref 不变）。
 * 存储：LlmProfileStore 专用存储（kind 字段即模块分区）；CHAT 启停/切换即时刷新 ModelSelector。
 */
@HiltViewModel
class ModuleProfilesViewModel @Inject constructor(
    private val store: LlmProfileStore,
    private val chatModule: OpenAiChatModule,
    private val secureKeyStore: com.zero.v2.services.security.CredentialStore
) : ViewModel() {

    private val kind = MutableStateFlow<LlmProfile.Kind?>(null)
    val storageError = MutableStateFlow(store.storageError)
    val credentialError = MutableStateFlow<String?>(null)
    fun recoverCredentials() {
        runCatching { secureKeyStore.recoverStorage() }
            .onSuccess { credentialError.value = null }
            .onFailure { credentialError.value = "安全存储恢复失败；请解锁后重试，原数据保留，不要清除应用数据。" }
    }
    private fun mutate(action: () -> Unit): Boolean = runCatching {
        action()
        storageError.value = null
        chatModule.refreshProfiles()
    }.fold({ true }, { storageError.value = it.message ?: "模型配置保存失败"; false })

    val profiles = combine(store.profiles, kind) { list, k ->
        k?.let { kind -> list.filter { it.kind == kind } } ?: emptyList()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun setKind(k: LlmProfile.Kind) { kind.value = k }

    /** 当前 Key 掩码（S1.1-rev：apiKey 与模型编辑同地配置——用户裁定 09-09）
     *  S1.2-a：支持 fallback 链显示（embedding/vision/search 主 ref 空时显示兜底 ref 值，
     *  与 OpenAiCompatibleClient.resolveApiKey 同语义——否则回退链生效中却显示"未配置"） */
    fun maskedKey(ref: String, fallback: String? = null): String {
        val v = try {
            secureKeyStore.getString(ref) ?: fallback?.let { secureKeyStore.getString(it) }
        } catch (_: Exception) {
            credentialError.value = secureKeyStore.storageError ?: "安全存储读取失败"
            return "读取失败（原凭据保留）"
        } ?: return "未配置"
        return if (v.length <= 8) "••••" else "${v.take(4)}••••${v.takeLast(4)}"
    }

    fun saveApiKey(ref: String, value: String) {
        if (value.isNotBlank()) secureKeyStore.put(ref, value.trim())
    }

    fun toggle(profile: LlmProfile) { mutate { store.upsert(profile.copy(enabled = !profile.enabled)) } }

    /** 手动切换主力：同 kind 单激活（其余停用），即时生效 */
    fun setPrimary(id: String) {
        val k = kind.value ?: return
        mutate { store.setPrimary(id, k) }
    }

    fun save(profile: LlmProfile, shared: Boolean = false): Boolean = mutate {
        if (profile.kind == LlmProfile.Kind.CHAT) store.manage(com.zero.v2.services.llm.ProfileEdit.Save(profile, shared))
        else store.upsert(profile)
    }
    fun remove(id: String): Boolean = mutate { store.manage(com.zero.v2.services.llm.ProfileEdit.Remove(id)) }

    fun reset(id: String) {
        mutate { store.resetToDefault(id) }
    }
}

/** 模块详情页内嵌区（S1.1：openai_chat=CHAT；S1.2 各语音/视觉模块按 kind 接入）
 *  S1.1-rev：卡片点击 → 编辑子页（整区切换非对话框）；apiKey 同地；版式松化 */
@Composable
fun ModuleProfilesSection(
    kind: LlmProfile.Kind,
    viewModel: ModuleProfilesViewModel = hiltViewModel(),
    onEditorBackChanged: ((() -> Unit)?) -> Unit = {},
) {
    LaunchedEffect(kind) { viewModel.setKind(kind) }
    val profiles by viewModel.profiles.collectAsState()
    var editingId by rememberSaveable(kind) { mutableStateOf<String?>(null) }
    var pendingId by rememberSaveable(kind) { mutableStateOf<String?>(null) }
    var copySource by rememberSaveable(kind) { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable(kind) { mutableStateOf<String?>(null) }
    val closeEditor = { editingId = null; pendingId = null; copySource = null }
    deleteId?.let { id ->
        AlertDialog(onDismissRequest = { deleteId = null }, title = { Text("删除停用模型？") },
            text = { Text("仅删除此模型配置；其他模型、共享连接和已保存密钥保留。") },
            confirmButton = { TextButton(onClick = { if (viewModel.remove(id)) deleteId = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("取消") } })
    }

    val credentialError by viewModel.credentialError.collectAsState()
    credentialError?.let {
        Text(it, color = MaterialTheme.colorScheme.error)
        Text("若存在旧明文凭据，重试会创建并核对加密副本后切换，原件保留供恢复。")
        TextButton(onClick = viewModel::recoverCredentials) { Text("重试安全存储 / 迁移旧凭据") }
    }
    val storageError by viewModel.storageError.collectAsState()
    storageError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    val current = profiles.firstOrNull { it.id == editingId } ?: pendingId?.let { id ->
        com.zero.v2.services.llm.ProfileManagement.draft(id, profiles.find { it.id == copySource })
    }
    if (current != null) {
        if (com.zero.v2.services.llm.ChatGptPlanProtocol.enabled(current)) {
            ChatGptModelEditor(current, { if (viewModel.save(it)) closeEditor() }, closeEditor)
            return
        }
        ProfileEditorPage(
            profile = current,
            maskedKey = { viewModel.maskedKey(current.apiKeyRef, current.apiKeyRefFallback) },
            onSaveKey = { viewModel.saveApiKey(current.apiKeyRef, it) },
            onSave = { if (viewModel.save(it)) closeEditor() },
            onBack = closeEditor,
            onBackRequestReady = onEditorBackChanged,
            connections = profiles,
            onSaveManaged = { profile, shared -> if (viewModel.save(profile, shared)) closeEditor() },
            onConnectionKey = { ref, value -> viewModel.saveApiKey(ref, value) },
        )
        return
    }

    LlmSectionHeading("LLM", "模型库 · ${kindLabel(kind)}",
        "${profiles.size} 个模型 · ${profiles.count { it.enabled }} 个启用")
    if (kind == LlmProfile.Kind.CHAT) {
        ChatGptAccountCard()
        Button(onClick = { pendingId = java.util.UUID.randomUUID().toString(); copySource = null }) { Text("＋ 添加模型") }
        Text(
            "启用多个模型可按能力自动选择；设为主力会切换为单模型。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    profiles.forEach { p ->
        ProfileCard(
            profile = p,
            // S8.2 生效性收口（候审#1）：启停 Switch/「设为主力」是多 profile 语义（仅 CHAT）——
            // 非 CHAT 单 profile 下二者无实际效果（模块解析兜底仍用该 profile），按"可见即生效"隐藏
            multi = kind == LlmProfile.Kind.CHAT,
            onOpen = { editingId = p.id },
            onToggle = { viewModel.toggle(p) },
            onPrimary = { viewModel.setPrimary(p.id) },
            onReset = { viewModel.reset(p.id) },
            onCopy = { copySource = p.id; pendingId = java.util.UUID.randomUUID().toString() },
            onDelete = { deleteId = p.id },
        )
    }
}

/**
 * KB0.3（设计 §十六.0 方案 3 · KA11/裁-4 + AA5 + GA4）：后台任务模型只读卡专用 VM。
 * 数据源**与 Job 同源**——modelSelector.select(CHAT) 实际结果（勿自造 filter enabled+first
 * 两套逻辑：select 按能力 jaccard 择优非首个，多 CHAT profile 并存时结果相异）；
 * 独立于 ModuleProfilesViewModel（其 setKind 单值态会与同屏 EMBEDDING 管理区互踩，AA5/SR13）。
 */
@HiltViewModel
class MemoryBgModelViewModel @Inject constructor(
    modelSelector: ModelSelector,
    store: LlmProfileStore
) : ViewModel() {
    /** 当前对话模型 modelId；null = 无启用 CHAT（后台 Reflection/图谱将跳过，SR12） */
    val currentChatName: StateFlow<String?> = store.profiles
        .map { modelSelector.select(setOf(ModelCapability.CHAT))?.modelId }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

/**
 * KB0.3：记忆模块详情页"后台任务模型"只读卡——Reflection · 图谱提取 · 事实提炼
 * 跟随当前对话模型（与 Job 同源 select(CHAT) 结果）；不设每任务模型选择器（裁定"绑定即可"，
 * 模型切换经 openai_chat 模块详情页既有模型管理，N2.6c 即时生效）。
 */
@Composable
fun MemoryBackgroundModelCard(viewModel: MemoryBgModelViewModel = hiltViewModel()) {
    val current by viewModel.currentChatName.collectAsState()
    Text("后台任务模型", style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary)
    Text(
        "Reflection · 图谱提取 · 事实提炼 → 跟随当前对话模型" +
            (current?.let { "（$it）" } ?: "（未启用对话模型——后台任务将跳过）"),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun kindLabel(k: LlmProfile.Kind): String = when (k) {
    LlmProfile.Kind.CHAT -> "对话"
    LlmProfile.Kind.TTS -> "语音合成"
    LlmProfile.Kind.EMBEDDING -> "向量嵌入"
    LlmProfile.Kind.VISION -> "视觉"
    LlmProfile.Kind.SEARCH -> "联网搜索"
    LlmProfile.Kind.ASR -> "语音识别"
}

@Composable
internal fun ProfileCard(
    profile: LlmProfile,
    multi: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onPrimary: () -> Unit,
    onReset: () -> Unit,
    onCopy: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    val canReset = remember(profile.id) { LlmProfile.defaults().any { it.id == profile.id } }
    var menu by remember { mutableStateOf(false) }
    ZeroCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                    Text(profile.displayName.take(2).uppercase(), Modifier.padding(14.dp),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(profile.displayName, style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(if (!multi) "模块专用模型" else if (profile.enabled) "已启用 · 可参与自动选模" else "已停用",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (multi) Switch(checked = profile.enabled, onCheckedChange = { onToggle() },
                    modifier = Modifier.semantics { contentDescription = "${profile.displayName} 启用状态" })
            }
            Text(profile.modelId, style = MaterialTheme.typography.bodyMedium,
                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            val host = runCatching { java.net.URI(profile.baseUrl).host }.getOrNull().orEmpty()
            Text(host.ifBlank { "尚未配置连接" }, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (multi) androidx.compose.material3.FilledTonalButton(onClick = onPrimary) { Text("设为主力") }
                TextButton(onClick = onOpen, modifier = Modifier.weight(1f)) { Text("编辑模型") }
                if (multi || canReset) Column {
                    TextButton(onClick = { menu = true }) { Text("更多") }
                    androidx.compose.material3.DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        if (multi) {
                            androidx.compose.material3.DropdownMenuItem(text = { Text("复制模型（共享连接）") },
                                onClick = { menu = false; onCopy() })
                            androidx.compose.material3.DropdownMenuItem(text = { Text("删除停用模型") },
                                enabled = !profile.enabled, onClick = { menu = false; onDelete() })
                        }
                        if (canReset) androidx.compose.material3.DropdownMenuItem(text = { Text("恢复默认") },
                            onClick = { menu = false; onReset() })
                    }
                }
            }
        }
    }
}

/**
 * S1.1-rev 编辑子页（用户裁定 09-09）：apiKey 与模型编辑同地；版式松化——
 * 输入框 label=字段名、supportingText=说明（描述不再挤占输入框内空间）。
 * S1.1-fix2：apiKey 字段沿用既有 API Keys 卡片保存风格——掩码/眼睛切换 +
 * 字段级 ✓ 立即保存（存后清空+反馈）；「保存全部」保留兜底同存（Key 唯一入口，卡片已退役）。
 */
@Composable
internal fun ProfileEditorPage(
    profile: LlmProfile,
    maskedKey: () -> String,
    onSaveKey: (String) -> Unit,
    onSave: (LlmProfile) -> Unit,
    onBack: () -> Unit,
    onBackRequestReady: ((() -> Unit)?) -> Unit = {},
    connections: List<LlmProfile> = emptyList(),
    onSaveManaged: ((LlmProfile, Boolean) -> Unit)? = null,
    onConnectionKey: ((String, String) -> Unit)? = null,
) {
    var pricesJson by rememberSaveable(profile.id) { mutableStateOf(profile.tokenPrices.toJson().toString()) }
    val prices = com.zero.v2.services.llm.ModelTokenPrices.fromJson(org.json.JSONObject(pricesJson))
    var connectionId by rememberSaveable(profile.id) { mutableStateOf(profile.connectionId) }
    var keyRef by rememberSaveable(profile.id) { mutableStateOf(profile.apiKeyRef) }
    var fallbackRef by rememberSaveable(profile.id) { mutableStateOf(profile.apiKeyRefFallback) }
    var priorityText by rememberSaveable(profile.id) { mutableStateOf(profile.priority.toString()) }
    var updateShared by rememberSaveable(profile.id) { mutableStateOf(false) }
    var capabilityNames by rememberSaveable(profile.id) { mutableStateOf(profile.capabilities.map { it.name }.sorted()) }
    var displayName by rememberSaveable(profile.id) { mutableStateOf(profile.displayName) }
    var modelId by rememberSaveable(profile.id) { mutableStateOf(profile.modelId) }
    var baseUrl by rememberSaveable(profile.id) { mutableStateOf(profile.baseUrl) }
    var llamaPreflight by rememberSaveable(profile.id) { mutableStateOf(profile.localLlamaPreflight) }
    var gatewayMode by rememberSaveable(profile.id) { mutableStateOf(profile.effectiveGatewayMode()) }
    var cloudflareByok by rememberSaveable(profile.id) { mutableStateOf(profile.cloudflareByok) }
    var cloudflareGatewayId by rememberSaveable(profile.id) { mutableStateOf(profile.cloudflareGatewayId) }
    var authMode by rememberSaveable(profile.id) { mutableStateOf(profile.authMode) }
    var authHeader by rememberSaveable(profile.id) { mutableStateOf(profile.authHeader) }
    var contractId by rememberSaveable(profile.id) { mutableStateOf(profile.contractId) }
    var parameterJson by rememberSaveable(profile.id) { mutableStateOf(org.json.JSONObject(profile.parameters).toString()) }
    var contextWindow by rememberSaveable(profile.id) { mutableStateOf(profile.contextWindow.toString()) }
    var maxOutputTokens by rememberSaveable(profile.id) { mutableStateOf(profile.maxOutputTokens.toString()) }
    var apiKey by remember { mutableStateOf("") }
    // S1.1-fix2：apiKey 字段沿用既有 API Keys 卡片保存风格（眼睛切换 + 字段级 ✓ 立即保存）
    var showKey by remember { mutableStateOf(false) }
    var keySaved by remember { mutableStateOf(false) }
    var voice by rememberSaveable(profile.id) { mutableStateOf(profile.voice) }
    // N4.x 全量参数（§7.3 定界；保存经 LlmProfileStore.sanitize 钳位兜底）
    var temperature by rememberSaveable(profile.id) { mutableStateOf(profile.temperature.toString()) }
    var maxTokens by rememberSaveable(profile.id) { mutableStateOf(profile.maxTokens.toString()) }
    var enableThinking by rememberSaveable(profile.id) { mutableStateOf(profile.enableThinking) }
    var reasoningEffort by rememberSaveable(profile.id) { mutableStateOf(profile.reasoningEffort) }
    var thinkingBudget by rememberSaveable(profile.id) { mutableStateOf(profile.thinkingBudget.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val requestBack = {
        val dirty = prices != profile.tokenPrices || displayName != profile.displayName || modelId != profile.modelId ||
        baseUrl != profile.baseUrl || voice != profile.voice || apiKey.isNotBlank() ||
        temperature != profile.temperature.toString() || maxTokens != profile.maxTokens.toString() ||
        enableThinking != profile.enableThinking || reasoningEffort != profile.reasoningEffort ||
        thinkingBudget != profile.thinkingBudget.toString() ||
        llamaPreflight != profile.localLlamaPreflight || gatewayMode != profile.effectiveGatewayMode() || cloudflareByok != profile.cloudflareByok || cloudflareGatewayId != profile.cloudflareGatewayId ||
        authMode != profile.authMode || authHeader != profile.authHeader ||
        contractId != profile.contractId || parameterJson != org.json.JSONObject(profile.parameters).toString() ||
        contextWindow != profile.contextWindow.toString() || maxOutputTokens != profile.maxOutputTokens.toString() ||
        priorityText != profile.priority.toString() || connectionId != profile.connectionId || capabilityNames != profile.capabilities.map { it.name }.sorted()
        if (dirty) confirmDiscard = true else onBack()
    }
    val currentBack by rememberUpdatedState(requestBack)
    val registeredBack = remember(profile.id) { { currentBack() } }
    DisposableEffect(profile.id) {
        onBackRequestReady(registeredBack)
        onDispose { onBackRequestReady(null) }
    }
    BackHandler(onBack = requestBack)
    if (confirmDiscard) {
        AlertDialog(onDismissRequest = { confirmDiscard = false },
            title = { Text("放弃未保存的修改？") },
            text = { Text("已单独保存的密钥不会撤销，其余草稿将丢弃。") },
            confirmButton = { TextButton(onClick = onBack) { Text("放弃修改") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") } })
    }
    val isChat = profile.kind == LlmProfile.Kind.CHAT
    // SR9：视觉/搜索为 DashScope 原生协议族——端点/模型提示与 OpenAI 兼容族区分
    val nativeDashScope = profile.kind == LlmProfile.Kind.VISION || profile.kind == LlmProfile.Kind.SEARCH

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("模型设置",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f))
            TextButton(onClick = requestBack) { Text("返回列表") }
        }

        ZeroCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                LlmSectionHeading("01", "模型身份", "为模型命名，并填写服务商提供的完整模型ID。")
                OutlinedTextField(value = displayName, onValueChange = { displayName = it },
                    label = { Text("显示名称") }, singleLine = true,
                    shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = modelId, onValueChange = { modelId = it },
                    label = { Text("模型 ID") }, singleLine = true,
                    supportingText = { Text(if (nativeDashScope) "DashScope 系原生模型（如 qwen3-vl-flash / qwen3.5-flash）"
                        else "填写服务提供的模型标识") },
                    shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth())
            }
        }
        ZeroCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                LlmSectionHeading("02", "连接与凭据", "设置服务地址、网关和认证；留空密钥保留当前值。")
                if (isChat) {
                    ProviderTemplatePicker { template ->
                        val connection = template.connection(java.util.UUID.randomUUID().toString())
                        llamaPreflight = template.llamaPreflight
                        connectionId = connection.id; keyRef = connection.apiKeyRef; fallbackRef = null
                        baseUrl = connection.baseUrl; authMode = connection.authMode; authHeader = connection.authHeader
                        gatewayMode = connection.gatewayMode; cloudflareByok = connection.cloudflareByok
                        cloudflareGatewayId = connection.cloudflareGatewayId
                        apiKey = ""; keySaved = false; updateShared = false
                    }
                    ProfileConnectionPicker(connectionId, connections, updateShared, { updateShared = it }, { selected ->
                        connectionId = selected.connectionId
                        gatewayMode = selected.effectiveGatewayMode()
                        baseUrl = selected.baseUrl; authMode = selected.authMode; authHeader = selected.authHeader
                        cloudflareByok = selected.cloudflareByok; cloudflareGatewayId = selected.cloudflareGatewayId
                        keyRef = selected.apiKeyRef; fallbackRef = selected.apiKeyRefFallback
                        apiKey = ""; keySaved = false; updateShared = false
                    }, {
                        val id = java.util.UUID.randomUUID().toString()
                        connectionId = "connection:$id"; keyRef = "llm_connection:$id"; fallbackRef = null
                        apiKey = ""; keySaved = false; updateShared = false
                    })
                }
                OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it },
                    label = { Text("Base URL") }, singleLine = true,
                    supportingText = { Text(if (nativeDashScope) "DashScope 原生端点根（含 /api/v1）"
                        else "OpenAI 兼容根（含 /v1）；本地开发连接使用已配置地址") },
                    shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth())
                if (isChat) {
                    Text("连接认证（NONE 适用于无需密钥的本地服务）")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        com.zero.v2.services.llm.ConnectionAuth.entries.forEach { mode ->
                            androidx.compose.material3.FilterChip(selected = authMode == mode,
                                onClick = { authMode = mode }, label = { Text(mode.name) })
                        }
                    }
                    if (authMode == com.zero.v2.services.llm.ConnectionAuth.HEADER) {
                        OutlinedTextField(value = authHeader, onValueChange = { authHeader = it },
                            label = { Text("认证头名称") }, singleLine = true, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth())
                    }
                }
                if (isChat) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = llamaPreflight, onCheckedChange = { llamaPreflight = it })
                        Text("llama.cpp 实际上下文预检（仅支持 /props、/apply-template、/tokenize 的部署）")
                    }
                    GatewayModePicker(gatewayMode) { mode ->
                        gatewayMode = mode
                        cloudflareByok = mode == com.zero.v2.services.llm.GatewayMode.CF_BYOK
                        if (mode != com.zero.v2.services.llm.GatewayMode.DIRECT) authMode = com.zero.v2.services.llm.ConnectionAuth.BEARER
                    }
                    if (gatewayMode != com.zero.v2.services.llm.GatewayMode.DIRECT) {
                        Text("API Key 填 Cloudflare Token；模型 ID 按该入口的目录原样填写。")
                        Text(if (gatewayMode == com.zero.v2.services.llm.GatewayMode.CF_WORKERS)
                            "模型 ID 使用 @cf/...；填写 Gateway ID 才通过网关。额度与费用由 Cloudflare 账户策略决定。"
                            else "REST 按网关默认密钥与计费策略路由；BYOK 缺失可能转统一计费。Zero 不能保证免费或强制 BYOK，请在网关核对策略。")
                        OutlinedTextField(value = cloudflareGatewayId,
                            onValueChange = { cloudflareGatewayId = it },
                            label = { Text("Gateway ID") }, singleLine = true,
                            supportingText = { Text("Workers 直连可留空；第三方 REST 必填；旧 compat 使用 URL 中网关。") },
                            shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth())
                    }
                }

                OutlinedTextField(value = apiKey, onValueChange = { apiKey = it; keySaved = false },
                    label = { Text("API Key") }, singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (apiKey.isNotEmpty()) {
                                IconButton(onClick = { showKey = !showKey }) {
                                    Icon(if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = if (showKey) "隐藏" else "显示")
                                }
                                // 字段级 ✓ 立即保存（同既有卡片：存后清空输入、显示反馈）
                                IconButton(onClick = {
                                    if (apiKey.isNotBlank()) {
                                        try {
                                            if (onConnectionKey != null) onConnectionKey(keyRef, apiKey.trim()) else onSaveKey(apiKey.trim())
                                            keySaved = true
                                            apiKey = ""
                                            showKey = false
                                            error = null
                                        } catch (_: Exception) {
                                            error = "密钥保存失败，请重试；草稿已保留。"
                                        }
                                    }
                                }) {
                                    Icon(Icons.Filled.Check, contentDescription = "保存",
                                        tint = if (apiKey.isNotBlank()) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    },
                    supportingText = {
                        when {
                            keySaved -> Text("密钥已保存", color = MaterialTheme.colorScheme.primary)
                            else -> Text("当前：${if (keyRef == profile.apiKeyRef) maskedKey() else "已选择其他连接；保存密钥将更新该连接"}；留空不改，输入后点 ✓ 立即保存")
                        }
                    },
                    shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth())
                if (profile.kind == LlmProfile.Kind.TTS) {
                    OutlinedTextField(value = voice, onValueChange = { voice = it },
                        label = { Text("音色") }, singleLine = true,
                        supportingText = { Text("MiMo：冰糖/茉莉=女声，苏打/白桦=男声") },
                        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        if (isChat) {
            ZeroCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    LlmSectionHeading("03", "生成与思考", "先选协议契约，再调整输出与思考参数。")
                    ContractParameterFields(contractId, parameterJson,
                        onContract = { contractId = it }, onParameters = { parameterJson = it })
                    LlmProtocolSummary(contractId, modelId)
                    if (com.zero.v2.services.llm.ModelContracts.all.find { it.id == contractId }?.temperature != false) {
                    OutlinedTextField(value = temperature, onValueChange = { temperature = it },
                        label = { Text("温度") }, singleLine = true,
                        supportingText = { Text("0=确定 ~ 2=极随机；日常推荐 0.7") },
                        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth())
                    } else Text("此契约不发送温度；原温度值保留。")
                    OutlinedTextField(value = maxTokens, onValueChange = { maxTokens = it },
                        label = { Text("单次回复最大 Token") }, singleLine = true,
                        supportingText = { Text(if (contractId in setOf("cf-luna-responses-v1", "cf-openai-responses-v1"))
                            "包含思考和正文。无输出且预算耗尽时最多重试一次、预算翻倍，上限16384及模型输出限制。"
                            else "不超过下方模型输出限制，且须小于上下文容量") },
                        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = contextWindow, onValueChange = { contextWindow = it },
                        label = { Text("模型上下文容量 Token") }, singleLine = true, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = maxOutputTokens, onValueChange = { maxOutputTokens = it },
                        label = { Text("模型输出限制 Token") }, singleLine = true, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth())
                }
            }
            ZeroCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    LlmSectionHeading("04", "能力与路由", "仅声明模型实际支持的能力。")
                    OutlinedTextField(value = priorityText, onValueChange = { priorityText = it },
                        label = { Text("路由优先级（整数，越大越优先）") }, singleLine = true, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth())
                    LlmCapabilityChoices(capabilityNames) { capabilityNames = it }
                }
            }
        }

        ModelPriceFields(prices) { pricesJson = it.toJson().toString() }
        error?.let {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(modifier = Modifier.fillMaxWidth(), onClick = {
                val draft = ProfileEditorDraft(displayName, modelId, baseUrl, voice, temperature,
                    maxTokens, enableThinking, reasoningEffort, thinkingBudget, cloudflareByok, cloudflareGatewayId,
                    authMode, authHeader, contractId,
                    org.json.JSONObject(parameterJson).let { values ->
                        values.keys().asSequence().associateWith { values.getString(it) }
                    }, contextWindow, maxOutputTokens, gatewayMode, llamaPreflight)
                error = prices.validationError() ?: draft.validationError(profile) ?: if (priorityText.toIntOrNull() == null) "路由优先级须为整数" else null
                if (error == null) {
                    try {
                        if (apiKey.isNotBlank()) {
                            if (onConnectionKey != null) onConnectionKey(keyRef, apiKey.trim()) else onSaveKey(apiKey.trim())
                            apiKey = ""
                            keySaved = true
                            showKey = false
                        }
                        val saved = draft.toProfile(profile).copy(tokenPrices = prices, connectionId = connectionId, priority = priorityText.toInt(),
                            apiKeyRef = keyRef, apiKeyRefFallback = fallbackRef,
                            capabilities = capabilityNames.map { ModelCapability.valueOf(it) }.toSet())
                        if (onSaveManaged != null) onSaveManaged(saved, updateShared) else onSave(saved)
                    } catch (_: Exception) {
                        error = if (keySaved) "模型保存失败，密钥已保存；模型草稿已保留，请重试。"
                            else "模型保存失败；草稿已保留，请重试。"
                    }
                }
            }) { Text("保存全部") }
            OutlinedButton(onClick = requestBack, modifier = Modifier.fillMaxWidth()) { Text("取消") }
        }
        Text("参数须在接口支持范围内；修改后保存生效。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
