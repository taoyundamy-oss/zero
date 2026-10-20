package com.zero.v2.services.mcp

import android.content.Context
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import com.zero.v2.services.security.CredentialStore
import com.zero.v2.services.security.writePreferenceConfirmed
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MCP 服务器注册表 — Step 8.3。
 *
 * 专用存储（不扭曲扁平 ConfigField 体系——服务器列表是数组结构，D57 设计审查确认）。
 * SharedPreferences + JSON 数组持久化；StateFlow 供 UI 响应式刷新。
 * 鉴权 Key 走 SecureKeyStore（条目名 = apiKeyRef）。
 */
data class McpServerConfig(
    val id: String,              // 唯一标识（uuid 短码）
    val name: String,            // 显示名（如 "高德地图"）
    val url: String,             // Streamable HTTP 端点
    val apiKeyRef: String = "",  // SecureKeyStore 条目名（空 = 无鉴权）
    val enabled: Boolean = true,
    /** true = 全部工具 AUTO；false = CONFIRM（D9/D24 用户主权） */
    val autoPermission: Boolean = false,
    /** S7.2（D120/SR8）：自签服务器证书 SHA-256 指纹（64 hex；空 = 不参与 pin——公共 CA 服务器无需填）。
     *  TrustConfig 据此并入 ZeroCloudTrust 接受集（换服务器 = 改条目 URL+指纹，零代码） */
    val certSha256: String = ""
)

@Singleton
class McpServerStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val traceManager: AgentTraceManager
) {

    companion object {
        private const val PREFS = "zero_mcp_servers"
        private const val KEY_SERVERS = "servers_json"
    }

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val _servers = MutableStateFlow<List<McpServerConfig>>(emptyList())
    private var generation = 0L
    private val versions = mutableMapOf<String, Long>()
    val servers: StateFlow<List<McpServerConfig>> = _servers.asStateFlow()

    init { load() }

    private fun load() {
        try {
            val json = prefs.getString(KEY_SERVERS, null) ?: return
            val arr = JSONArray(json)
            val list = mutableListOf<McpServerConfig>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(McpServerConfig(
                    id = obj.optString("id"),
                    name = obj.optString("name"),
                    url = obj.optString("url"),
                    apiKeyRef = obj.optString("apiKeyRef", ""),
                    enabled = obj.optBoolean("enabled", true),
                    autoPermission = obj.optBoolean("autoPermission", false),
                    certSha256 = obj.optString("certSha256", "")
                ))
            }
            _servers.value = list
        } catch (_: Exception) { _servers.value = emptyList() }
    }

    private fun persist(candidate: List<McpServerConfig>) {
        val arr = JSONArray()
        candidate.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id); put("name", s.name); put("url", s.url)
                put("apiKeyRef", s.apiKeyRef); put("enabled", s.enabled)
                put("autoPermission", s.autoPermission)
                if (s.certSha256.isNotBlank()) put("certSha256", s.certSha256)
            })
        }
        writePreferenceConfirmed(prefs, KEY_SERVERS, arr.toString(), "MCP configuration was not saved; retry required")
        _servers.value = candidate
        generation++
    }

    /** 添加/更新服务器（按 id 匹配） */
    @Synchronized
    fun upsert(server: McpServerConfig) {
        val current = _servers.value.toMutableList()
        val idx = current.indexOfFirst { it.id == server.id }
        if (idx >= 0) current[idx] = server else current.add(server)
        persist(current)
        versions[server.id] = generation
        traceManager.record("mcp_store", NodeType.SYSTEM_EVENT, "mcp_client",
            "server_upserted: id=${server.id}")
    }

    /** One owner for the connection and its credential; compensation is explicit, not a cross-file transaction. */
    @Synchronized
    fun saveWithCredential(server: McpServerConfig, newKey: String?, credentials: CredentialStore,
        expected: McpServerConfig? = null) {
        check(expected == null || getById(expected.id) == expected) {
            "Connection changed; reopen the editor before saving"
        }
        val key = newKey?.trim()?.takeIf { it.isNotEmpty() }
        val ref = McpCredentialReference.forWrite(server, key != null, _servers.value)
        val previous = if (key != null) credentials.getString(ref) else null
        var saved = false
        try {
            if (key != null) credentials.put(ref, key)
            upsert(McpEndpoint.forStorage(server.copy(apiKeyRef = ref), key != null))
            saved = true
        } finally {
            if (!saved && key != null) restoreCredential(credentials, ref, previous)
        }
    }

    /** 删除服务器 */
    @Synchronized
    fun remove(id: String) {
        val current = _servers.value.toMutableList()
        current.removeAll { it.id == id }
        persist(current)
        versions[id] = generation
        traceManager.record("mcp_store", NodeType.SYSTEM_EVENT, "mcp_client", "server_removed: id=$id")
    }

    /** 启停切换 */
    @Synchronized
    fun toggle(id: String) {
        val current = _servers.value.map {
            if (it.id == id) it.copy(enabled = !it.enabled) else it
        }
        persist(current)
        versions[id] = generation
    }

    fun getById(id: String): McpServerConfig? = _servers.value.find { it.id == id }
    val enabledServers: List<McpServerConfig> get() = _servers.value.filter { it.enabled }

    @Synchronized
    fun snapshot() = McpConnectionSnapshot(generation, enabledServers, versions.toMap())

    /** Validation and tool publication share the connection write lock; no network work occurs here. */
    @Synchronized
    fun publishIfCurrent(snapshot: McpConnectionSnapshot, publish: () -> Unit): Boolean {
        if (generation != snapshot.generation) return false
        publish()
        return true
    }

    @Synchronized
    fun isCurrent(server: McpServerConfig, version: Long): Boolean =
        server.enabled && getById(server.id) == server && (versions[server.id] ?: 0L) == version
}

private fun restoreCredential(credentials: CredentialStore, ref: String, previous: String?) {
    val recovery = runCatching {
        if (previous == null) credentials.delete(ref) else credentials.put(ref, previous)
    }
    check(recovery.isSuccess) {
        "Connection was not saved and credential recovery failed; check the credential before retrying"
    }
}

data class McpConnectionSnapshot(
    val generation: Long,
    val servers: List<McpServerConfig>,
    val versions: Map<String, Long>,
)
