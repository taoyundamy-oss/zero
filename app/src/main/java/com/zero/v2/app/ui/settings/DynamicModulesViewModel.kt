package com.zero.v2.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zero.v2.core.data.dao.DynamicModuleDao
import com.zero.v2.core.data.entity.DynamicModuleEntity
import com.zero.v2.core.data.store.ModuleConfigStore
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import com.zero.v2.services.mcp.McpClientModule
import com.zero.v2.core.modulemanager.ModuleLifecycleManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

/**
 * D68 manifest configSchema 字段（text|number|toggle 三型——@ConfigField 精神）。
 * min/max 仅 number 有意义（成对存在 → 详情页渲染 Slider，否则数值输入框）。
 */
data class DynConfigField(
    val key: String,
    val label: String,
    val type: String,           // text | number | toggle
    val default: String,
    val min: Double?,
    val max: Double?,
    val description: String
)

data class DynamicModulesUiState(
    val loading: Boolean = true,
    val readError: String? = null,
    val detailLoading: Boolean = false,
    val detailId: String? = null,
    val detailError: String? = null,
    val actionError: String? = null,
    val actionMessage: String? = null,
    val modules: List<DynamicModuleEntity> = emptyList(),
    /** 已注册 FC 总数（mcp_* + dyn_*——UI 启停后实时增减的可视指标） */
    val toolCount: Int = 0,
    /** 操作中的模块 id（启停/保存——mount 含 supervisor spawn+探活可达 15s，防双击） */
    val busyIds: Set<String> = emptySet(),
    // ═══ 详情页（D68 配置）═══
    val detail: DynamicModuleEntity? = null,
    val detailFields: List<DynConfigField> = emptyList(),
    /** 字段初值（存储值 > manifest default；详情页 remember 初始化用） */
    val detailValues: Map<String, String> = emptyMap(),
    val saving: Boolean = false
)

/**
 * 动态模块管理 ViewModel — M2.4（方案 §5.3 D68 · 计划 §三）。
 *
 * 列表：DynamicModuleDao.observeAll（Room Flow——启停后 DB 状态变即刷新）；
 * 启停：McpClientModule.mount/unmountDynamicModule（supervisor 启停 + FC 注册注销 + DB 状态）；
 * 配置：ModuleConfigStore（复用零新表）——保存即生效路径 = ACTIVE 模块 unmount+mount
 * （mount 经 M2.2 契约读 store 注入 env ZERO_MOD_CONFIG，D68 决策③）。
 * E28：UI 动作全埋 ui_dyn trace。
 */
@HiltViewModel
class DynamicModulesViewModel internal constructor(
    private val dao: DynamicModuleDao,
    private val actions: DynamicModuleActions,
    private val configStore: ModuleConfigStore,
    private val traceManager: AgentTraceManager
) : ViewModel() {
    @Inject constructor(dao: DynamicModuleDao, mcpClient: McpClientModule,
        configStore: ModuleConfigStore, traceManager: AgentTraceManager, lifecycle: ModuleLifecycleManager) :
        this(dao, McpDynamicModuleActions(mcpClient, lifecycle), configStore, traceManager)

    private val _state = MutableStateFlow(DynamicModulesUiState())
    val state: StateFlow<DynamicModulesUiState> = _state.asStateFlow()
    private var observation: Job? = null
    private var detailJob: Job? = null
    private var detailRevision = 0L

    init {
        retryModules()
        viewModelScope.launch {
            actions.discoveredCount.collect { count ->
                _state.update { it.copy(toolCount = count) }
            }
        }
    }

    fun retryModules() {
        if (observation?.isActive == true) return
        _state.update { it.copy(loading = true, readError = null) }
        observation = viewModelScope.launch {
            try {
                dao.observeAll().collect { modules ->
                    _state.update { current -> current.copy(modules = modules, loading = false, readError = null,
                        detail = modules.firstOrNull { it.id == current.detail?.id } ?: current.detail) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _state.update { it.copy(readError = "模块列表读取失败，请重新读取") }
                recordFailure("read", e)
            } finally { _state.update { it.copy(loading = false) } }
        }
    }

    fun clearActionMessage() { _state.update { it.copy(actionError = null, actionMessage = null) } }

    private fun recordFailure(operation: String, e: Exception) {
        traceManager.record("ui_dyn", NodeType.ERROR, "settings", "$operation: ${e.javaClass.simpleName}")
    }

    /** 启停切换：ACTIVE→unmount（DISABLED）；DISABLED/CRASHED→mount（P1-6 用户再启用路径）。 */
    fun toggle(id: String) {
        if (id in _state.value.busyIds) return
        _state.update { it.copy(busyIds = it.busyIds + id, actionError = null, actionMessage = null) }
        viewModelScope.launch {
            try {
                val entity = requireNotNull(dao.getById(id)) { "Module missing" }
                val stopping = entity.state == "ACTIVE"
                val ok = if (stopping) actions.unmount(entity) else actions.mount(entity)
                if (!ok) _state.update { it.copy(actionError = if (stopping) "停止模块失败，请重试或查看运行记录" else "启用模块失败，请重试或查看运行记录") }
                else _state.update { it.copy(actionMessage = if (stopping) "模块已停止" else "模块已启用") }
                traceManager.record("ui_dyn", if (ok) NodeType.SYSTEM_EVENT else NodeType.ERROR,
                    "settings", "ui_toggle: $id stopping=$stopping ok=$ok")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _state.update { it.copy(actionError = "模块操作失败，请重试或查看运行记录") }
                recordFailure("toggle", e)
            } finally {
                _state.update { it.copy(busyIds = it.busyIds - id) }
            }
        }
    }

    /** 详情页加载：实体 + configSchema 解析 + 字段初值（存储值 > default）。 */
    fun loadDetail(id: String) {
        detailJob?.cancel()
        val revision = ++detailRevision
        _state.update { it.copy(detailId = id, detailLoading = true, detailError = null,
            detail = it.detail?.takeIf { entity -> entity.id == id },
            detailFields = if (it.detailId == id) it.detailFields else emptyList(),
            detailValues = if (it.detailId == id) it.detailValues else emptyMap()) }
        detailJob = viewModelScope.launch {
            try {
                val entity = dao.getById(id)
                if (entity == null) {
                    if (revision == detailRevision) _state.update { it.copy(detail = null, detailError = "模块已不存在，请返回列表确认") }
                    return@launch
                }
                val fields = parseConfigSchema(entity.configSchema)
                val stored = configStore.get(id)?.let { JSONObject(it) }
                val values = fields.associate { f -> f.key to formatValue(stored?.opt(f.key) ?: f.default) }
                if (revision == detailRevision) _state.update { it.copy(detail = entity, detailFields = fields, detailValues = values) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (revision == detailRevision) _state.update { it.copy(detailError = "模块详情或配置读取失败，保留现有内容，请重新读取") }
                recordFailure("detail", e)
            } finally {
                if (revision == detailRevision) _state.update { it.copy(detailLoading = false) }
            }
        }
    }

    /**
     * D68 保存：值按 schema 类型整形入 JSON（toggle→Boolean/number→数值/text→String）→
     * ModuleConfigStore.saveNow → **ACTIVE 模块立即重启生效**（unmount+mount——
     * mount 读 store 注入 env ZERO_MOD_CONFIG）。P1-4：Secret 不入此通道。
     */
    fun saveConfig(id: String, values: Map<String, String>) {
        if (_state.value.saving || id in _state.value.busyIds) return
        _state.update { it.copy(saving = true, busyIds = it.busyIds + id, actionError = null, actionMessage = null) }
        viewModelScope.launch {
            var persisted = false
            try {
                val entity = requireNotNull(dao.getById(id)) { "Module missing" }
                val fields = parseConfigSchema(entity.configSchema)
                // Preserve stored fields not exposed by the current form schema.
                val obj = configStore.get(id)?.let { JSONObject(it) } ?: JSONObject()
                for (f in fields) {
                    val raw = values[f.key] ?: f.default
                    when (f.type) {
                        "toggle" -> {
                            require(raw == "true" || raw == "false") { "Invalid toggle" }
                            obj.put(f.key, raw == "true")
                        }
                        "number" -> {
                            val d = raw.toDoubleOrNull()
                            require(d != null && d.isFinite() && (f.min == null || d >= f.min) && (f.max == null || d <= f.max)) { "Invalid number" }
                            // 整数值存整型（env JSON 干净；shim 侧 int/float 通吃）
                            if (d == d.toLong().toDouble()) obj.put(f.key, d.toLong()) else obj.put(f.key, d)
                        }
                        else -> obj.put(f.key, raw)
                    }
                }
                configStore.saveNow(id, obj.toString())
                persisted = true
                traceManager.record("ui_dyn", NodeType.SYSTEM_EVENT, "settings",
                    "dyn_config_saved: $id fields=${fields.size} active=${entity.state == "ACTIVE"}")
                if (entity.state == "ACTIVE") {
                    // D68 决策③：保存后模块重启（配置页明示）——CRASHED/DISABLED 只存不启
                    if (!actions.unmount(entity)) {
                        _state.update { it.copy(actionError = "配置已保存，但停止模块失败，新配置尚未生效") }
                        traceManager.record("ui_dyn", NodeType.ERROR, "settings", "config_activation_stop_failed: $id")
                        return@launch
                    }
                    val re = requireNotNull(dao.getById(id)) { "Module missing" }
                    if (!actions.mount(re)) {
                        _state.update { it.copy(actionError = "配置已保存，但模块启动失败，请重试启用并查看运行记录") }
                        traceManager.record("ui_dyn", NodeType.ERROR, "settings", "config_activation_start_failed: $id")
                        return@launch
                    }
                }
                _state.update { it.copy(actionMessage = if (entity.state == "ACTIVE") "配置已保存并生效" else "配置已保存，启用模块时生效") }
                // 刷新详情（重启后 state/endpoint 可能变化）
                loadDetail(id)
            } catch (e: CancellationException) {
                if (persisted) _state.update { it.copy(actionMessage = "配置已保存，生效流程已取消，请核对模块状态") }
                throw e
            }
            catch (e: Exception) {
                _state.update { it.copy(actionError = if (e is IllegalArgumentException) "配置无效或模块已不存在，请检查输入后重试" else "配置保存失败，请重试或查看运行记录") }
                recordFailure("save", e)
            } finally {
                _state.update { it.copy(saving = false, busyIds = it.busyIds - id) }
            }
        }
    }

    /** manifest configSchema（JSON 数组）→ 字段列表；坏 schema 容错为空（不渲染配置区）。 */
    private fun parseConfigSchema(json: String): List<DynConfigField> {
        val arr = JSONArray(json.ifBlank { "[]" })
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val key = o.optString("key")
            if (key.isBlank()) return@mapNotNull null
            DynConfigField(
                key = key,
                label = o.optString("label").ifBlank { key },
                type = o.optString("type", "text"),
                default = formatValue(o.opt("default")),
                min = if (o.has("min") && !o.isNull("min")) o.optDouble("min") else null,
                max = if (o.has("max") && !o.isNull("max")) o.optDouble("max") else null,
                description = o.optString("description")
            )
        }
    }

    /** 任意 JSON 值 → UI 字符串（初值统一 String 形态，保存时再按类型整形回去）。 */
    private fun formatValue(v: Any?): String = when (v) {
        null -> ""
        is Boolean -> v.toString()
        is Double -> if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
        is Float -> formatValue(v.toDouble())
        else -> v.toString()
    }
}
