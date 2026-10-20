package com.zero.v2.app.ui.module

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zero.v2.core.common.ActionPermission
import com.zero.v2.core.moduleapi.IZeroModule
import com.zero.v2.core.moduleapi.runtimeState
import com.zero.v2.core.moduleapi.FunctionCatalog
import com.zero.v2.core.modulemanager.ModuleConfigParser
import com.zero.v2.core.modulemanager.ModuleConfigParser.FormFieldState
import com.zero.v2.core.modulemanager.ModuleLifecycleManager
import com.zero.v2.core.data.store.ModuleConfigStore
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import com.zero.v2.services.security.CredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ModuleDetailState(
    val moduleId: String = "",
    val displayName: String = "",
    val description: String = "",
    val version: String = "",
    val category: String = "",
    val isEnabled: Boolean = false,
    val configFields: List<FormFieldState> = emptyList(),
    val apiKeyFields: List<ApiKeyFieldState> = emptyList(),
    val functions: List<FunctionInfoState> = emptyList(),
    val isLoading: Boolean = true,
    val loadError: String? = null,
    val saveError: String? = null,
    val module: IZeroModule? = null
)

data class ApiKeyFieldState(
    val key: String,        // "llm_client:deepseek"
    val label: String,      // "DeepSeek API Key"
    val currentValue: String // 掩码值
)

/** 已注册 FC 函数摘要（v2.4: 权限标签展示） */
data class FunctionInfoState(
    val name: String,
    val description: String,
    val permission: ActionPermission
)

@HiltViewModel
class ModuleDetailViewModel @Inject constructor(
    private val moduleManager: ModuleLifecycleManager,
    private val secureKeyStore: CredentialStore,
    private val moduleConfigStore: ModuleConfigStore,
    private val functionRegistry: FunctionCatalog,
    private val traceManager: AgentTraceManager
) : ViewModel() {

    private val _state = MutableStateFlow(ModuleDetailState())
    val state: StateFlow<ModuleDetailState> = _state.asStateFlow()

    private val saveJobs = mutableMapOf<String, Job>()
    private val saveMutex = Mutex()
    private val saveErrors = mutableMapOf<String, String>()
    private var runtimeStateJob: Job? = null
    private var loadJob: Job? = null
    private val pendingConfigChanges = mutableMapOf<String, MutableMap<String, String>>()

    fun load(moduleId: String) {
        loadJob?.cancel()
        _state.value = ModuleDetailState(moduleId = moduleId)
        runtimeStateJob?.cancel()
        runtimeStateJob = viewModelScope.launch {
            moduleManager.runtimeStates.collect { statuses ->
                val current = _state.value
                if (current.moduleId == moduleId) {
                    val status = statuses[moduleId] ?: return@collect
                    _state.value = current.copy(isEnabled = status.operational)
                }
            }
        }
        loadJob = viewModelScope.launch {
            val module = moduleManager.getById(moduleId)
            if (module == null) {
                _state.value = ModuleDetailState(moduleId = moduleId, isLoading = false,
                    loadError = "模块暂不可用，请返回列表或重试。")
                return@launch
            }
            val meta = module.metadata

            // 从持久化存储恢复配置（若有），否则使用模块默认 config
            try {
                val savedJson = moduleConfigStore.get(moduleId)
                traceManager.record("cfg_$moduleId", NodeType.SYSTEM_EVENT, "config",
                    "CONFIG_LOADED: $moduleId chars=${savedJson?.length ?: 0}")
                if (!savedJson.isNullOrBlank()) {
                    // v5.5 (D8): 迁移链 — 版本落后时显式升级并落库
                    val migrated = module.config.migrateJson(savedJson)
                    module.config.fromJson(migrated.json)
                    if (migrated.changed) {
                        try {
                            moduleConfigStore.saveNow(moduleId, migrated.json)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {}
                        traceManager.record("cfg_$moduleId", NodeType.CONFIG_CHANGE, "config",
                            "config_migrated: $moduleId ${migrated.fromVersion}->${migrated.toVersion}")
                    }
                    traceManager.record("cfg_$moduleId", NodeType.SYSTEM_EVENT, "config",
                        "CONFIG_RESTORED: $moduleId fromJsonDone=true")
                } else {
                    traceManager.record("cfg_$moduleId", NodeType.SYSTEM_EVENT, "config",
                        "CONFIG_RESTORED: $moduleId noSavedJson (使用默认值)")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                traceManager.record("cfg_$moduleId", NodeType.ERROR, "config",
                    "CONFIG_LOAD_FAILED: $moduleId error=${e.message}")
            }

            // 零反射解析配置字段
            val configFields = try { ModuleConfigParser.parse(module.config).filterNot {
                it.key in com.zero.v2.app.ui.settings.SettingsOwnership.dedicatedFields(moduleId)
            } }
                catch (_: Exception) { emptyList() }

            // 为需要 API Key 的模块生成 API Key 字段
            val apiKeyFields = buildApiKeyFields(moduleId, module)

            // v2.4: 已注册 FC 函数 + 统一权限（模块详情权限标签展示）
            val functions = functionRegistry.getEntriesByModule(moduleId).map {
                FunctionInfoState(
                    name = it.definition.name,
                    description = it.definition.description,
                    permission = it.permission
                )
            }
            traceManager.record("cfg_$moduleId", NodeType.SYSTEM_EVENT, "config",
                "FC_LIST_LOADED: $moduleId count=${functions.size} " +
                        "permissions=${functions.joinToString { f -> "${f.name}=${f.permission}" }}")

            _state.value = ModuleDetailState(
                moduleId = meta.id, displayName = meta.displayName,
                description = meta.description, version = meta.version,
                category = meta.category.label, isEnabled = module.runtimeState().operational,
                configFields = configFields.map { field ->
                    field.copy(currentValue = pendingConfigChanges[moduleId]?.get(field.key) ?: field.currentValue)
                }, apiKeyFields = apiKeyFields, saveError = saveErrors[moduleId],
                functions = functions, module = module, isLoading = false
            )
        }
    }

    /** 根据模块 ID 生成对应的 API Key 配置字段 */
    private fun buildApiKeyFields(moduleId: String, module: IZeroModule): List<ApiKeyFieldState> {
        val fields = mutableListOf<ApiKeyFieldState>()

        when (moduleId) {
            // S1.1-fix2（用户裁定 09-09）：openai_chat 的 API Keys 卡片退役——Key 唯一入口 =
            // 模型编辑子页（ProfileEditorPage apiKey 字段，同 ref：llm_client:deepseek/qwen，存量 Key 零迁移）
            "llm_client" -> {
                fields.add(ApiKeyFieldState("llm_client:deepseek", "DeepSeek API Key",
                    maskKey(runCatching { secureKeyStore.getString("llm_client:deepseek") }.getOrNull())))
                fields.add(ApiKeyFieldState("llm_client:qwen", "Qwen API Key",
                    maskKey(runCatching { secureKeyStore.getString("llm_client:qwen") }.getOrNull())))
            }
            // S1.2-d（单入口裁定沿用 S1.1-fix2）：speech_tts / vision_analyze / web_search 的
            // API Keys 卡片退役——Key 唯一入口 = 各模块详情页「模型管理」编辑子页 apiKey 字段
            // （同 ref：tts:mimo / vision_analyze:qwen / web_search:qwen，存量 Key 零迁移）
            // Step 8.1（D57）：Jina Reader Key（可选——匿名 20 RPM，免费 Key 200 RPM）
            "web_fetch" -> {
                fields.add(ApiKeyFieldState("webfetch:jina", "Jina Reader API Key（可选，动态页抓取提速）",
                    maskKey(runCatching { secureKeyStore.getString("webfetch:jina") }.getOrNull())))
            }
        }
        return fields
    }

    /** 保存 API Key 到 SecureKeyStore。自动去除首尾空白字符。 */
    fun saveApiKey(key: String, value: String) {
        val trimmed = value.trim()
        if (trimmed.isNotBlank()) {
            runCatching { secureKeyStore.put(key, trimmed) }.onFailure {
                _state.value = _state.value.copy(saveError = "安全凭据保存失败，原数据保留；请解锁后在模型设置重试。")
            }
        }
    }

    /** The debounce and its draft retain their originating module across navigation. */
    fun updateConfigField(key: String, value: String) {
        val current = _state.value
        if (current.module == null) return
        val moduleId = current.moduleId
        pendingConfigChanges.getOrPut(moduleId) { mutableMapOf() }[key] = value
        saveJobs.remove(moduleId)?.cancel()
        saveJobs[moduleId] = viewModelScope.launch {
            delay(300)
            // A later edit cancels only the debounce, never a confirmed write already in flight.
            viewModelScope.launch { flushConfigChanges(moduleId) }
        }
    }

    fun save() {
        val moduleId = _state.value.moduleId
        saveJobs.remove(moduleId)?.cancel()
        viewModelScope.launch { flushConfigChanges(moduleId) }
    }

    private suspend fun flushConfigChanges(moduleId: String) = saveMutex.withLock {
        val pending = pendingConfigChanges[moduleId] ?: return@withLock
        if (pending.isEmpty()) return@withLock
        val batch = pending.toMap()
        val module = moduleManager.getById(moduleId) ?: return@withLock
        var committed = false
        try {
            val json = moduleConfigCandidate(module.config, batch)
            moduleConfigStore.saveConfirmed(moduleId, json)
            committed = true
            module.config.fromJson(json)
            val applied = moduleManager.safeCall(module, "onConfigChanged") {
                module.onConfigChanged(module.config)
                true
            } == true
            check(applied)
            for ((key, value) in batch) {
                if (pending[key] == value) pending.remove(key)
            }
            saveErrors.remove(moduleId)
            if (_state.value.moduleId == moduleId) _state.value = _state.value.copy(saveError = null)
            traceManager.record("cfg_$moduleId", NodeType.CONFIG_CHANGE, "config", "config_saved: $moduleId")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = if (committed) "配置已保存，但模块应用失败；请重试。"
                else "参数未保存，请检查输入后重试。"
            saveErrors[moduleId] = message
            if (_state.value.moduleId == moduleId) _state.value = _state.value.copy(saveError = message)
            traceManager.record("cfg_$moduleId", NodeType.ERROR, "config",
                "config_save_failed: ${e.javaClass.simpleName} committed=$committed")
        }
    }

    override fun onCleared() {
        loadJob?.cancel()
        runtimeStateJob?.cancel()
        saveJobs.values.forEach { it.cancel() }
    }

    private fun maskKey(key: String?): String {
        if (key.isNullOrBlank()) return ""
        if (key.length <= 6) return "****"
        return "${key.take(3)}****${key.takeLast(3)}"
    }
}
