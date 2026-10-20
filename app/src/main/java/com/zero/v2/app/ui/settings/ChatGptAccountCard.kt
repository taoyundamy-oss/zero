package com.zero.v2.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zero.v2.app.ui.theme.ZeroCard
import com.zero.v2.services.chatgpt.ChatGptAuth
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@HiltViewModel
class ChatGptAccountViewModel @Inject constructor(private val auth: ChatGptAuth,
    private val catalog: com.zero.v2.services.chatgpt.ChatGptCatalog,
    private val store: com.zero.v2.services.llm.LlmProfileStore,
    private val module: com.zero.v2.services.llm.OpenAiChatModule,
) : ViewModel() {
    val state = auth.state
    private var operation: Job? = null
    val models = kotlinx.coroutines.flow.MutableStateFlow<List<com.zero.v2.services.llm.LlmProfile>>(emptyList())
    val catalogMessage = kotlinx.coroutines.flow.MutableStateFlow("")
    private fun clearCatalog() { models.value = emptyList(); catalogMessage.value = "" }
    init { viewModelScope.launch { auth.reload() } }
    fun signIn(client: String?, openBrowser: (String) -> Unit) {
        if (operation?.isActive == true) return
        clearCatalog()
        operation = viewModelScope.launch { auth.signIn(client, openBrowser) }
    }
    fun cancel() { operation?.cancel() }
    fun select(client: String) { clearCatalog(); operation = viewModelScope.launch { auth.select(client) } }
    fun signOut(client: String) { clearCatalog(); operation = viewModelScope.launch { auth.signOut(client) } }
    fun reload() { viewModelScope.launch { auth.reload() } }
    fun fetch(client: String) {
        if (operation?.isActive == true) return
        clearCatalog()
        operation = viewModelScope.launch {
            catalogMessage.value = "正在读取账号模型目录…"
            runCatching { catalog.fetch(client) }.onSuccess {
                models.value = it
                catalogMessage.value = "目录不代表推理验收；添加后默认停用，请核对能力与上下文。"
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                catalogMessage.value = "模型目录读取失败，请检查登录和套餐授权后重试。"
            }
        }
    }
    fun add(profile: com.zero.v2.services.llm.LlmProfile) {
        runCatching {
            check(auth.ready(profile.connectionId))
            if (store.get(profile.id) == null) store.upsert(profile)
            module.refreshProfiles()
        }.onSuccess { catalogMessage.value = "已添加（已有模型保持原配置）；请在模型列表编辑并主动启用。" }
            .onFailure { catalogMessage.value = "添加未保存，请检查账号或模型存储。" }
    }
}

@Composable
fun ChatGptAccountCard(viewModel: ChatGptAccountViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val models by viewModel.models.collectAsState()
    val catalogMessage by viewModel.catalogMessage.collectAsState()
    val context = LocalContext.current
    val browser: (String) -> Unit = { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    ZeroCard {
        Column(Modifier.padding(16.dp)) {
            Text("ChatGPT账号", style = MaterialTheme.typography.titleMedium)
            Text("独立套餐连接；添加模型后需主动启用，现有模型保持不变。")
            Text(state.message, style = MaterialTheme.typography.bodySmall)
            if (state.canCancel) {
                TextButton(onClick = viewModel::cancel) { Text("取消登录") }
            } else if (!state.busy) {
                Button(onClick = { viewModel.signIn(null, browser) }) { Text("Continue with ChatGPT") }
                TextButton(onClick = viewModel::reload) { Text("重新读取账号") }
            }
            state.accounts.forEach { account ->
                Text((if (state.active == account.clientId) "当前 · " else "") + account.label)
                Text(if (!account.signedIn) "已退出" else if (account.planAllowed) "已获套餐授权 · 以实际请求为准" else
                    "仅身份登录 · 未获套餐授权", style = MaterialTheme.typography.bodySmall)
                if (!state.busy) Row {
                    if (account.signedIn && state.active != account.clientId) {
                        TextButton(onClick = { viewModel.select(account.clientId) }) { Text("选择账号") }
                    }
                    TextButton(onClick = { viewModel.signIn(account.clientId, browser) }) { Text("重新登录") }
                    if (account.signedIn) {
                        TextButton(onClick = { viewModel.signOut(account.clientId) }) { Text("退出") }
                    }
                }
                if (!state.busy && state.active == account.clientId && account.signedIn && account.planAllowed) {
                    TextButton(onClick = { viewModel.fetch(account.clientId) }) { Text("读取 / 刷新账号模型目录") }
                }
            }
            Text(catalogMessage, style = MaterialTheme.typography.bodySmall)
            models.filter { it.connectionId == state.active }.forEach { model ->
                TextButton(onClick = { viewModel.add(model) }) { Text("添加 · ${model.displayName}") }
            }
        }
    }
}
