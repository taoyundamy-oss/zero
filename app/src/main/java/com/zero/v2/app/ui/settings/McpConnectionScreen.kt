package com.zero.v2.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.zero.v2.app.ui.theme.ZeroCard
import com.zero.v2.app.ui.theme.ZeroPageHeader
import com.zero.v2.app.ui.theme.ZeroShapes
import com.zero.v2.services.mcp.McpEndpoint
import com.zero.v2.services.mcp.McpServerConfig

@Composable
fun McpServersScreen(onBack: () -> Unit, viewModel: McpServersViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var adding by rememberSaveable { mutableStateOf(false) }
    var editorId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    val initial = remember(editorId) { state.servers.firstOrNull { it.id == editorId } }
    Scaffold(topBar = { ZeroPageHeader("外部工具连接", onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                ZeroCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("连接状态", style = MaterialTheme.typography.titleMedium)
                        Text("已发现 ${state.discoveredTools} 个工具", style = MaterialTheme.typography.bodyLarge)
                        state.status?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (state.refreshing) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            OutlinedButton(viewModel::cancelRefresh, Modifier.fillMaxWidth()) { Text("取消刷新") }
                        } else OutlinedButton(viewModel::refresh, Modifier.fillMaxWidth(), enabled = !state.saving) {
                            Text("刷新工具")
                        }
                        FilledTonalButton({ adding = true }, Modifier.fillMaxWidth(), enabled = !state.saving) {
                            Text("添加连接")
                        }
                    }
                }
            }
            if (state.servers.isEmpty()) item {
                Text("添加连接后，即可在对话中使用它提供的工具。", style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(state.servers, key = { it.id }) { server ->
                ZeroCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(server.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Switch(server.enabled, { viewModel.toggle(server.id) }, enabled = !state.saving,
                                modifier = Modifier.padding(start = 8.dp).semantics {
                                    contentDescription = "${server.name}连接开关"
                                })
                        }
                        Text(McpEndpoint.display(server.url), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (server.autoPermission) "工具可自动执行" else "执行前请求确认",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton({ editorId = server.id }, enabled = !state.saving) { Text("编辑") }
                            TextButton({ deletingId = server.id }, enabled = !state.saving) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
    if (adding || editorId != null) {
        val dismiss = { adding = false; editorId = null }
        if (!adding && initial == null) AlertDialog(onDismissRequest = dismiss,
            title = { Text("连接已不存在") }, text = { Text("该连接已被删除，请返回列表。") },
            confirmButton = { TextButton(dismiss) { Text("返回列表") } })
        else McpServerEditor(initial, state.saving, state.error, dismiss) { name, url, key, cert ->
            if (adding) viewModel.addServer(name, url, key, cert, onSaved = dismiss)
            else viewModel.updateServer(requireNotNull(initial), url, name, key, cert, onSaved = dismiss)
        }
    }
    deletingId?.let { id ->
        AlertDialog(onDismissRequest = { deletingId = null }, title = { Text("删除连接？") },
            text = { Text("删除后不再发现这个连接的工具。已存凭据保留，避免影响兼容引用。") },
            confirmButton = { TextButton({ viewModel.remove(id); deletingId = null }) { Text("删除连接") } },
            dismissButton = { TextButton({ deletingId = null }) { Text("取消") } })
    }
}

private val endpointDraftSaver = Saver<String, String>(
    save = { if (McpEndpoint.canSaveDraft(it)) it else null }, restore = { it },
)

@Composable
internal fun McpServerEditor(initial: McpServerConfig?, saving: Boolean, error: String?, onDismiss: () -> Unit,
    onSave: (String, String, String?, String?) -> Unit) {
    var name by rememberSaveable(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var url by rememberSaveable(initial?.id, stateSaver = endpointDraftSaver) { mutableStateOf(initial?.url.orEmpty()) }
    var key by remember(initial?.id) { mutableStateOf("") }
    var fingerprint by rememberSaveable(initial?.id) { mutableStateOf(initial?.certSha256.orEmpty()) }
    var discard by remember { mutableStateOf(false) }
    val dirty = name != initial?.name.orEmpty() || url != initial?.url.orEmpty() || key.isNotEmpty() ||
        fingerprint != initial?.certSha256.orEmpty()
    val back = { if (!saving) { if (dirty) discard = true else onDismiss() } }
    Dialog(onDismissRequest = back, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler { back() }
        Surface(Modifier.widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight(0.94f), shape = ZeroShapes.card,
            color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) {
            Column {
                val title = if (initial == null) "添加连接" else "编辑连接"
                ZeroPageHeader(title, back)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), enabled = !saving,
                        label = { Text("名称") })
                    OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), enabled = !saving,
                        label = { Text("端点 URL") },
                        supportingText = { Text("含查询参数、用户信息或片段的地址草稿不会随页面重建保存") })
                    OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), enabled = !saving,
                        label = { Text("API Key") }, visualTransformation = PasswordVisualTransformation(),
                        supportingText = { Text("留空保留已存密钥。新密钥在确认保存时更新，页面重建后需重新填写。") })
                    OutlinedTextField(fingerprint, { fingerprint = it }, Modifier.fillMaxWidth(), enabled = !saving,
                        label = { Text("证书指纹 SHA-256") },
                        supportingText = { Text("自签服务器可填写64位指纹；公共 CA 服务器通常无需填写。") })
                    Text("保存连接和发现工具分别显示结果；发现失败可稍后重试。",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ onSave(name.trim(), url.trim(), key.ifBlank { null }, fingerprint.ifBlank { null }) },
                        Modifier.fillMaxWidth(), enabled = !saving && name.isNotBlank() && url.isNotBlank()) {
                        Text(if (saving) "保存中…" else "保存连接")
                    }
                    OutlinedButton(back, Modifier.fillMaxWidth(), enabled = !saving) { Text("返回") }
                }
            }
        }
        if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃未保存的修改？") },
            text = { Text("返回后，本次草稿将被清除。") },
            confirmButton = { TextButton(onDismiss) { Text("放弃草稿") } },
            dismissButton = { TextButton({ discard = false }) { Text("继续编辑") } })
    }
}
