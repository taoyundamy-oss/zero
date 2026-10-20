package com.zero.v2.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zero.v2.core.modulemanager.ModuleLifecycleManager
import com.zero.v2.services.mcp.McpClientModule
import com.zero.v2.services.mcp.McpCredentialReference
import com.zero.v2.services.mcp.McpServerConfig
import com.zero.v2.services.mcp.McpServerStore
import com.zero.v2.services.security.CredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class McpServersUiState(
    val servers: List<McpServerConfig> = emptyList(),
    val discoveredTools: Int = 0,
    val refreshing: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val status: String? = null,
)

@HiltViewModel
class McpServersViewModel internal constructor(
    private val serverStore: McpServerStore,
    private val secureKeyStore: CredentialStore,
    private val actions: McpConnectionActions,
) : ViewModel() {
    @Inject constructor(serverStore: McpServerStore, mcpClient: McpClientModule,
        secureKeyStore: CredentialStore, lifecycle: ModuleLifecycleManager) :
        this(serverStore, secureKeyStore, ManagedMcpConnectionActions(mcpClient, lifecycle))
    private val _state = MutableStateFlow(McpServersUiState())
    val state = _state.asStateFlow()
    private var saveJob: Job? = null
    private var refreshJob: Job? = null
    private var refreshGeneration = 0L

    init {
        viewModelScope.launch { serverStore.servers.collect { _state.value = _state.value.copy(servers = it) } }
        viewModelScope.launch { actions.discoveredCount.collect { _state.value = _state.value.copy(discoveredTools = it) } }
    }

    fun toggle(id: String) = mutate({ serverStore.toggle(id) })
    fun remove(id: String) = mutate({ serverStore.remove(id) })

    fun refresh() {
        if (refreshJob?.isActive == true || _state.value.saving) return
        val generation = ++refreshGeneration
        refreshJob = viewModelScope.launch {
            _state.value = _state.value.copy(refreshing = true, error = null, status = null)
            try {
                val report = actions.refresh()
                ensureActive()
                if (generation == refreshGeneration) _state.value = _state.value.copy(
                    error = if (report.successful) null else report.message(),
                    status = if (report.successful) report.message() else null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == refreshGeneration) _state.value = _state.value.copy(error = "工具发现失败，请检查连接后重试")
            } finally {
                if (generation == refreshGeneration) _state.value = _state.value.copy(refreshing = false)
            }
        }
    }

    fun cancelRefresh() {
        refreshGeneration++
        refreshJob?.cancel()
        _state.value = _state.value.copy(refreshing = false, error = null, status = "刷新已取消")
    }

    fun addServer(name: String, url: String, apiKey: String?, certSha256: String? = null, onSaved: () -> Unit = {}) {
        save(McpServerConfig(McpCredentialReference.newConnectionId(), name.trim(), url.trim(),
            certSha256 = certSha256?.trim()?.uppercase().orEmpty()), apiKey, onSaved)
    }

    fun updateServer(server: McpServerConfig, newUrl: String, newName: String,
        apiKey: String?, certSha256: String?, onSaved: () -> Unit = {}) {
        save(server.copy(name = newName.trim(), url = newUrl.trim(),
            certSha256 = certSha256?.trim()?.uppercase().orEmpty()), apiKey, onSaved, expected = server)
    }

    private fun save(server: McpServerConfig, apiKey: String?, onSaved: () -> Unit, expected: McpServerConfig? = null) {
        val validUrl = server.url.toHttpUrlOrNull()
        if (server.name.isBlank() || validUrl == null ||
            (server.certSha256.isNotBlank() && !server.certSha256.matches(Regex("[0-9A-F]{64}")))) {
            _state.value = _state.value.copy(error = "请填写名称、有效的 HTTP(S) 地址及可选的64位证书指纹")
            return
        }
        mutate({ serverStore.saveWithCredential(server, apiKey, secureKeyStore, expected) }, onSaved)
    }

    private fun mutate(write: () -> Unit, onSaved: () -> Unit = {}) {
        if (saveJob?.isActive == true) return
        cancelRefresh()
        saveJob = viewModelScope.launch {
            _state.value = _state.value.copy(saving = true, error = null, status = null)
            var confirmed = false
            try {
                withContext(Dispatchers.IO) { write() }
                confirmed = true
                ensureActive()
                onSaved()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.value = _state.value.copy(error = "保存失败；草稿已保留，请检查存储与凭据后重试")
            } finally { _state.value = _state.value.copy(saving = false) }
            if (confirmed) refresh()
        }
    }
}
