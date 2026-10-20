package com.zero.v2.app.ui.automation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zero.v2.core.data.dao.AutomationDao
import com.zero.v2.core.data.entity.AutomationEntity
import com.zero.v2.core.moduleapi.ToolDispatcher
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import com.zero.v2.services.module.AutomationModule
import com.zero.v2.services.module.ExecutionPlan
import com.zero.v2.services.module.RunProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.json.JSONObject
import javax.inject.Inject

/** 自动化 Tab UI 状态 */
data class AutomationUiState(
    val tasks: List<AutomationEntity> = emptyList(),
    val loading: Boolean = false,
    val readError: String? = null,
    /** create_automation 含 LLM 计划生成，耗时可达数十秒 */
    val creating: Boolean = false,
    val runningId: String? = null,
    val message: String? = null,
    val messageIsError: Boolean = false
)

/** 任务草稿（LLM 生成 → 用户编辑 → 确认创建） */
data class AutomationDraft(
    val title: String = "",
    val description: String = "",
    val scheduleType: String = "MANUAL",
    val scheduleValue: String = "",
    /** v3.4 (D41): 执行模式 PLAN/REACT，默认 REACT（智能执行） */
    val execMode: String = "REACT",
    /** 预览用的计划步骤描述 */
    val steps: List<String> = emptyList(),
    /** 确认创建时透传（所见即所得，不二次生成） */
    val planJson: String = ""
)

/**
 * 自动化任务 ViewModel — Step 3.6。
 *
 * 动作统一走 functionRegistry.execute（与 LLM FC 同路径：继承统一权限检查与埋点）；
 * 列表数据直接读 AutomationDao（同 ChatViewModel → CharacterDao 模式）。
 *
 * 创建流: draft_automation（LLM 生成结构化草稿）→ 用户编辑 → create_automation（带 plan_json）。
 */
@HiltViewModel
class AutomationViewModel @Inject constructor(
    private val automationDao: AutomationDao,
    private val functionRegistry: ToolDispatcher,
    private val traceManager: AgentTraceManager,
    automationModule: AutomationModule
) : ViewModel() {

    private val commandDispatcher = AutomationCommandDispatcher(functionRegistry)

    private val _state = MutableStateFlow(AutomationUiState())
    val state: StateFlow<AutomationUiState> = _state.asStateFlow()
    private var readJob: Job? = null
    private var screenEntered = false

    /** 引擎实时进度（对话框逐步刷新） */
    val runProgress: StateFlow<RunProgress?> = automationModule.runProgress

    /** 每个任务最近一次执行摘要（固定展示，不自动清除） */
    private val _lastResults = MutableStateFlow<Map<String, String>>(emptyMap())
    val lastResults: StateFlow<Map<String, String>> = _lastResults.asStateFlow()

    /** v3.4 (D42): 每个任务最近一次最终结果全文（推送内容） */
    private val _lastFinals = MutableStateFlow<Map<String, String>>(emptyMap())
    val lastFinals: StateFlow<Map<String, String>> = _lastFinals.asStateFlow()

    /** 每个任务最近一次的逐步完整结果（折叠查看） */
    private val _lastStepResults = MutableStateFlow<Map<String, Map<Int, String>>>(emptyMap())
    val lastStepResults: StateFlow<Map<String, Map<Int, String>>> = _lastStepResults.asStateFlow()

    /** 草稿（null = 未进入草稿阶段） */
    private val _draft = MutableStateFlow<AutomationDraft?>(null)
    val draft: StateFlow<AutomationDraft?> = _draft.asStateFlow()
    val drafting = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            automationModule.runProgress.collect { p ->
                if (p?.done == true) {
                    _lastResults.update { it + (p.automationId to (p.summary ?: "")) }
                    _lastStepResults.update { it + (p.automationId to p.stepResults) }
                    p.finalContent?.let { fc ->
                        _lastFinals.update { it + (p.automationId to fc) }
                    }
                }
            }
        }
        refresh()
    }

    fun refresh() {
        if (readJob?.isActive == true) return
        _state.update { it.copy(loading = true, readError = null) }
        readJob = viewModelScope.launch {
            try {
                val tasks = automationDao.getActive()
                _state.update { it.copy(tasks = tasks) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(readError = "无法读取任务，请重试；已加载的任务仍保留") }
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    /** init owns the first load; re-entering a retained destination still refreshes it. */
    fun onScreenEntered() {
        if (screenEntered) refresh() else screenEntered = true
    }

    // ═══ 草稿确认流 ═══

    /** 生成结构化草稿（LLM 计划 + 调度推断，不落库） */
    fun generateDraft(description: String) {
        if (description.isBlank() || drafting.value) return
        drafting.value = true
        _state.update { it.copy(message = null, messageIsError = false) }
        viewModelScope.launch {
            try {
                val result = executeUiCommand(AutomationCommand.Draft(description), "草稿生成失败")
                if (result.success) _draft.value = parseDraft(result.data, description)
            } finally {
                drafting.value = false
            }
        }
    }

    /** 用户编辑草稿字段 */
    fun updateDraft(title: String = _draft.value?.title ?: "",
                    scheduleType: String = _draft.value?.scheduleType ?: "MANUAL",
                    scheduleValue: String = _draft.value?.scheduleValue ?: "",
                    execMode: String = _draft.value?.execMode ?: "REACT") {
        _draft.update { d ->
            d?.copy(title = title, scheduleType = scheduleType,
                scheduleValue = scheduleValue, execMode = execMode)
        }
    }

    fun discardDraft() { _draft.value = null }

    /** 确认创建（透传用户确认过的计划与执行模式） */
    fun confirmCreateDraft(onDone: () -> Unit) {
        val d = _draft.value ?: return
        if (_state.value.creating) return
        _state.update { it.copy(creating = true, message = null, messageIsError = false) }
        viewModelScope.launch {
            try {
                val result = executeUiCommand(AutomationCommand.Create(
                    description = d.description,
                    scheduleType = d.scheduleType,
                    scheduleValue = normalizedScheduleValue(d.scheduleType, d.scheduleValue),
                    execMode = d.execMode,
                    planJson = d.planJson
                ), "创建失败")
                if (result.success) {
                    _draft.value = null
                    refresh()
                    onDone()
                }
            } finally {
                _state.update { it.copy(creating = false) }
            }
        }
    }

    // ═══ 任务操作 ═══

    /** 编辑任务调度/标题 */
    fun updateSchedule(id: String, scheduleType: String, scheduleValue: String, title: String?, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val result = executeUiCommand(AutomationCommand.Update(
                id, scheduleType, normalizedScheduleValue(scheduleType, scheduleValue), title ?: ""
            ), "修改调度失败")
            if (result.success) { refresh(); onDone() }
        }
    }

    /** 立即执行 */
    fun runNow(id: String) {
        if (_state.value.runningId != null) return
        _state.update { it.copy(runningId = id, message = null, messageIsError = false) }
        viewModelScope.launch {
            try {
                val result = executeUiCommand(AutomationCommand.RunNow(id), "执行失败")
                if (result.success) refresh()
            } finally {
                _state.update { it.copy(runningId = null) }
            }
        }
    }

    /** 暂停/恢复/删除 */
    fun manage(id: String, action: String, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val result = executeUiCommand(AutomationCommand.Manage(id, action), "任务操作失败")
            if (result.success) { refresh(); onDone() }
        }
    }

    fun clearMessage() { _state.update { it.copy(message = null, messageIsError = false) } }

    private suspend fun executeUiCommand(command: AutomationCommand, fallback: String): FunctionResult {
        val result = try {
            commandDispatcher.execute(command)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            traceManager.record("ui_automation", NodeType.ERROR, "automation_ui", "command_failed: ${failure.javaClass.simpleName}")
            FunctionResult.failure(fallback)
        }
        _state.update { it.copy(message = if (result.success) {
            if (command is AutomationCommand.Draft) null else result.data.take(300)
        } else result.errorMessage ?: fallback,
            messageIsError = !result.success) }
        return result
    }

    // ═══ 内部 ═══

    private fun parseDraft(json: String, description: String): AutomationDraft {
        return try {
            val obj = JSONObject(json)
            val planJson = obj.optJSONObject("plan")?.toString() ?: ""
            val steps = ExecutionPlan.fromJson(planJson)?.steps?.map { s ->
                "${s.index}. ${s.description} [${s.tool ?: "推理"}]"
            } ?: emptyList()
            val type = obj.optString("schedule_type", "MANUAL")
            // 引擎 INTERVAL 值为秒 → UI 展示为分钟（确认时再归一化回秒）
            val value = if (type == "INTERVAL")
                ((obj.optString("schedule_value", "").toLongOrNull() ?: 0) / 60).toString()
            else obj.optString("schedule_value", "")
            AutomationDraft(
                title = obj.optString("title", description.take(24)),
                description = description,
                scheduleType = type,
                scheduleValue = value,
                steps = steps,
                planJson = planJson
            )
        } catch (_: Exception) {
            AutomationDraft(title = description.take(24), description = description)
        }
    }

    /** DAILY "12" → "12:00"；INTERVAL 分钟 → 秒（UI 输入恒为分钟） */
    private fun normalizedScheduleValue(type: String, value: String): String {
        val v = value.trim()
        return when (type) {
            "DAILY" -> Regex("""^(\d{1,2})$""").find(v)?.let { "${it.groupValues[1]}:00" } ?: v
            "INTERVAL" -> v.toLongOrNull()?.let { if (it > 0) (it * 60).toString() else v } ?: v
            else -> v
        }
    }
}
