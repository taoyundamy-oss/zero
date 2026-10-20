package com.zero.v2.services.module

import com.zero.v2.core.bus.ChatBus
import com.zero.v2.core.common.ActionPermission
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.PokeEvent
import com.zero.v2.core.common.PokePriority
import com.zero.v2.core.data.dao.AutomationDao
import com.zero.v2.core.data.entity.AutomationEntity
import com.zero.v2.core.moduleapi.*
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import com.zero.v2.services.agent.AgentJob
import com.zero.v2.services.llm.ModelCapability
import com.zero.v2.services.llm.ModelSelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自动化模块 — Zero v3.5 Plan-Execute 引擎（真实实现）。
 *
 * 对标 Claude Code 的 Plan-Execute 循环:
 *   create_automation → LLM 生成 ExecutionPlan（JsonExtractor 提取，失败走 RuleBasedTaskParser）
 *                     → Room 持久化（automations 表）
 *   runAutomation()   → ExecutionGuard 守护 + 波次执行（dependsOn 同波并发，
 *                       工具步骤走 tools.execute，推理步骤走 chatSync）
 *
 * v3.5: 实现 AgentJob — 定时自动化（DAILY/INTERVAL）由 Scheduler 在 AgentRuntime tick 上触发，
 *       到期自动执行并以 Poke 通知用户。
 */
@Singleton
class AutomationModule
    @Inject
    constructor(
        private val toolRuntime: com.zero.v2.core.moduleapi.ToolRuntime,


        private val scheduleGateway: com.zero.v2.services.agent.AgentScheduleGateway,
        private val chatBus: ChatBus,
        private val modelSelector: ModelSelector,
        private val automationDao: AutomationDao,
        private val traceManager: AgentTraceManager,

        // SD3：SELF_DRIVE 执行模式——到点把任务交给自驱动回合（共享主对话上下文，TriggerHub 不依赖本模块无环）

    ) : IZeroModule, AgentJob, com.zero.v2.core.moduleapi.ToolContributor {
        companion object {
            private const val HISTORY_PER_AUTOMATION = 10

            /** 规划时排除的元工具 — 防止 LLM 把"创建/管理自动化"递归规划进执行计划 */
            private val PLAN_EXCLUDED_TOOLS =
                setOf(
                    "create_automation",
                    "list_automations",
                    "manage_automation",
                    "run_automation_now",
                    "get_automation_results",
                    "draft_automation",
                    "update_automation",
                )
        }

        override val metadata =
            ModuleMetadata(
                id = "automation",
                displayName = "自动化",
                description = "创建和管理自动化任务。定时搜索、生成摘要、设置提醒。",
                category = ModuleCategory.AUTOMATION,
                launchOrder = 150,
            )

        private val _lifecycle = MutableStateFlow(ModuleLifecycle.UNINITIALIZED)
        override val lifecycle = _lifecycle.asStateFlow()
        override var isEnabled: Boolean = true
        override val requiredPermissions: List<String> = emptyList()

        /** v5.2: ExecutionGuard 参数用户化（原硬编码） */
        override val config = AutomationModuleConfig()

        /** 执行历史（内存态，最近 N 次；持久化历史由 Step 3.7 AgentTraceEvent 承接） */
        private val runHistory = mutableMapOf<String, MutableList<String>>()

        /** 运行进度（UI 实时展示 — 详情对话框逐步刷新） */
        private val _runProgress = MutableStateFlow<RunProgress?>(null)
        val runProgress: kotlinx.coroutines.flow.StateFlow<RunProgress?> = _runProgress.asStateFlow()

        /** AgentJob 到期缓存（避免 nextRunTime getter 打 DB） */
        @Volatile private var cachedNextRun: Long = Long.MAX_VALUE

        /** 一次性准点唤醒（非心跳 — 到期时刻 kick AgentRuntime，D19 tick 仍单源） */
        private val wakeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private var wakeJob: Job? = null

        override suspend fun onAwake() {

            _lifecycle.value = ModuleLifecycle.AWAKE
        }

        override suspend fun onStart() {
            refreshNextRun()
            _lifecycle.value = ModuleLifecycle.STARTED
        }

        override suspend fun onDestroy() {
            wakeScope.cancel()
            _lifecycle.value = ModuleLifecycle.DESTROYED
        }

        override suspend fun onConfigChanged(newConfig: ModuleConfigBase) {}

        override suspend fun checkAvailability(): Boolean = true

        override fun markDisabled() {
            _lifecycle.value = ModuleLifecycle.DISABLED
        }

        // ═══════════════════════════════════════════════════
        // AgentJob — 定时自动化调度（由 Scheduler 触发）
        // ═══════════════════════════════════════════════════

        override val id: String = "automation_scheduler"

        override val nextRunTime: Long get() = cachedNextRun

        // Alarm and heartbeat share one in-process owner. Re-read due rows after waiting:
        // locking only each run would still execute a stale row from a concurrent scan.
        private val dispatchMutex = Mutex()
        private val executionClaims = AutomationClaims()

        override suspend fun execute() = dispatchMutex.withLock {
            val due = automationDao.getDue(System.currentTimeMillis())
            if (due.isEmpty()) {
                refreshNextRun()
                return@withLock
            }
            for (entity in due) {
                try {
                    runWithClaim(entity, autonomous = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    traceManager.record(
                        "automation",
                        NodeType.ERROR,
                        metadata.id,
                        "automation_auto_failed: id=${entity.id} error=${e.message?.take(60)}",
                    )
                }
            }
            refreshNextRun()
        }

        // ═══════════════════════════════════════════════════
        // FC 注册（5 个函数 — v3.5 真实实现 + ActionPermission）
        // ═══════════════════════════════════════════════════

        override fun contributions(): List<com.zero.v2.core.moduleapi.ToolContribution> =
            com.zero.v2.core.moduleapi.toolDeclarations(metadata.id) {
            val functionRegistry = this
            // 默认参数必须是合法 JSON Schema（DeepSeek 严格校验: 空对象 {} 会 400）
            val emptyObjectSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())

            fun reg(
                name: String,
                desc: String,
                params: Map<String, Any?> = emptyObjectSchema,
                permission: ActionPermission,
                handler: suspend (Map<String, Any?>) -> FunctionResult,
            ) {
                functionRegistry.register(FunctionDefinition(name, desc, params), metadata.id, permission, handler)
            }

            reg(
                "create_automation",
                "创建自动化任务。如'每天早上9点搜索AI新闻'。",
                mapOf(
                    "type" to "object",
                    "properties" to
                        mapOf(
                            "description" to mapOf("type" to "string", "description" to "任务的自然语言描述"),
                            "schedule_type" to mapOf("type" to "string", "description" to "MANUAL(仅手动)/DAILY(每日定时)/INTERVAL(固定间隔)"),
                            "schedule_value" to mapOf("type" to "string", "description" to "DAILY:'HH:mm'；INTERVAL:秒数"),
                            "exec_mode" to mapOf("type" to "string", "description" to "PLAN(静态步骤)/REACT(LLM独立循环智能执行)/SELF_DRIVE(到点交自驱动回合——共享对话上下文全权处理，适合开放性复杂工作流)"),
                            "plan_json" to mapOf("type" to "string", "description" to "预生成计划 JSON（仅供 UI 草稿确认流传入，AI 调用时忽略）"),
                        ),
                    "required" to listOf("description"),
                ),
                ActionPermission.CONFIRM,
            ) { createAutomation(it) }

            reg("list_automations", "列出所有活跃的自动化任务。", permission = ActionPermission.AUTO) { listAutomations() }

            reg(
                "draft_automation",
                "根据自然语言需求生成结构化任务草稿（计划步骤 + 调度建议），不创建任务。UI 草稿确认流使用。",
                mapOf(
                    "type" to "object",
                    "properties" to
                        mapOf(
                            "description" to mapOf("type" to "string", "description" to "任务的自然语言描述"),
                        ),
                    "required" to listOf("description"),
                ),
                ActionPermission.AUTO,
            ) { draftAutomation(it) }

            reg(
                "update_automation",
                "编辑自动化任务（标题/调度方式）。",
                mapOf(
                    "type" to "object",
                    "properties" to
                        mapOf(
                            "automation_id" to mapOf("type" to "string", "description" to "任务ID"),
                            "schedule_type" to mapOf("type" to "string", "description" to "MANUAL/DAILY/INTERVAL"),
                            "schedule_value" to mapOf("type" to "string", "description" to "DAILY:'HH:mm'；INTERVAL:秒数"),
                            "title" to mapOf("type" to "string", "description" to "新标题（可选）"),
                        ),
                    "required" to listOf("automation_id", "schedule_type"),
                ),
                ActionPermission.CONFIRM,
            ) { updateAutomation(it) }

            reg(
                "manage_automation",
                "管理自动化任务：暂停、恢复、删除。",
                mapOf(
                    "type" to "object",
                    "properties" to
                        mapOf(
                            "automation_id" to mapOf("type" to "string", "description" to "任务ID"),
                            "action" to mapOf("type" to "string", "description" to "pause/resume/delete"),
                        ),
                    "required" to listOf("automation_id", "action"),
                ),
                ActionPermission.CONFIRM,
            ) { manageAutomation(it) }

            reg(
                "run_automation_now",
                "立即执行指定的自动化任务。",
                mapOf(
                    "type" to "object",
                    "properties" to
                        mapOf(
                            "automation_id" to mapOf("type" to "string", "description" to "任务ID"),
                        ),
                    "required" to listOf("automation_id"),
                ),
                ActionPermission.AUTO,
            ) { runNow(it) }

            reg(
                "get_automation_results",
                "查询自动化任务的执行历史和结果。",
                mapOf(
                    "type" to "object",
                    "properties" to
                        mapOf(
                            "automation_id" to mapOf("type" to "string", "description" to "任务ID"),
                        ),
                    "required" to listOf("automation_id"),
                ),
                ActionPermission.AUTO,
            ) { getResults(it) }
        }

        // ═══════════════════════════════════════════════════
        // FC 实现
        // ═══════════════════════════════════════════════════

        private suspend fun createAutomation(args: Map<String, Any?>): FunctionResult {
            val description = (args["description"] as? String)?.trim().orEmpty()
            if (description.isBlank()) return FunctionResult.failure("description 不能为空")
            val scheduleType = (args["schedule_type"] as? String ?: "MANUAL").uppercase()
            // v3.4: 全角冒号归一（"07：30"）
            val scheduleValue = (args["schedule_value"] as? String ?: "").trim().replace('：', ':')
            // v3.4 (D41): PLAN（静态步骤）/ REACT（LLM 循环执行器）
            // SD3-fix（2026-09-18 真机反馈）：白名单补 SELF_DRIVE——原只认 REACT，
            // LLM 传 SELF_DRIVE 被静默回落 PLAN（回执"PLAN模式"即此——静默失败类）
            val execMode =
                (args["exec_mode"] as? String ?: "PLAN").uppercase().let {
                    if (it == "REACT" || it == "SELF_DRIVE") it else "PLAN"
                }

            // plan_json（UI 草稿确认流）: 直接使用用户确认过的计划，不二次生成
            val planJsonArg = (args["plan_json"] as? String)?.takeIf { it.isNotBlank() }
            val plan: ExecutionPlan? =
                if (planJsonArg != null) {
                    ExecutionPlan.fromJson(planJsonArg)
                        ?: return FunctionResult.failure("plan_json 无效")
                } else if (execMode == "PLAN") {
                    generatePlan(description)
                } else {
                    null // REACT 模式无需预生成计划（目标即描述）
                }
            plan?.validationError()?.let { return FunctionResult.failure("非法计划: $it") }
            if (plan != null && plan.steps.size > config.maxSteps) {
                return FunctionResult.failure("计划步骤 ${plan.steps.size} 超过上限 ${config.maxSteps}")
            }

            val id = "auto_${System.currentTimeMillis().toString(36)}"
            val nextRun = AutomationSchedulePolicy.nextRun(scheduleType, scheduleValue)
            val entity =
                AutomationEntity(
                    id = id,
                    title = description.take(30),
                    description = description,
                    planJson = plan?.toJson() ?: """{"goal":${org.json.JSONObject.quote(description)},"steps":[]}""",
                    execMode = execMode,
                    scheduleType = scheduleType,
                    scheduleValue = scheduleValue,
                    nextRunAt = nextRun,
                )
            automationDao.insert(entity)
            refreshNextRun()
            traceManager.record(
                "automation",
                NodeType.SYSTEM_EVENT,
                metadata.id,
                "automation_created: id=$id mode=$execMode steps=${plan?.steps?.size ?: 0} " +
                    "schedule=$scheduleType/$scheduleValue nextRun=$nextRun",
            )
            return FunctionResult.success(
                "任务已创建 [$id]: ${entity.title}（${execMode}模式${plan?.steps?.size?.let { "，$it 步" } ?: "，智能执行"}）" +
                    if (nextRun == Long.MAX_VALUE) "，手动执行" else "，下次自动执行",
            )
        }

        /**
         * 草稿生成（UI 确认流）: LLM 计划 + 调度推断，不落库。
         * 返回 JSON: {"title","schedule_type","schedule_value","plan":{...}}
         */
        private suspend fun draftAutomation(args: Map<String, Any?>): FunctionResult {
            val description = (args["description"] as? String)?.trim().orEmpty()
            if (description.isBlank()) return FunctionResult.failure("description 不能为空")

            val plan = generatePlan(description)
            val (scheduleType, scheduleValue) = AutomationSchedulePolicy.derive(description)

            val draft =
                org.json.JSONObject().apply {
                    put("title", plan.goal.ifBlank { description }.take(24))
                    put("schedule_type", scheduleType)
                    put("schedule_value", scheduleValue)
                    put("plan", org.json.JSONObject(plan.toJson()))
                }
            traceManager.record(
                "automation",
                NodeType.SYSTEM_EVENT,
                metadata.id,
                "automation_drafted: steps=${plan.steps.size} schedule=$scheduleType/$scheduleValue",
            )
            return FunctionResult.success(draft.toString())
        }

        /** 编辑任务（标题/调度），重算 nextRunAt */
        private suspend fun updateAutomation(args: Map<String, Any?>): FunctionResult {
            val id = args["automation_id"] as? String ?: return FunctionResult.failure("automation_id 不能为空")
            val entity = automationDao.getById(id) ?: return FunctionResult.failure("未找到任务 [$id]")
            val scheduleType = (args["schedule_type"] as? String ?: entity.scheduleType).uppercase()
            val scheduleValue = args["schedule_value"] as? String ?: entity.scheduleValue
            val newTitle = (args["title"] as? String)?.takeIf { it.isNotBlank() } ?: entity.title

            val nextRun = AutomationSchedulePolicy.nextRun(scheduleType, scheduleValue)
            if (entity.execMode == "PLAN" && ExecutionPlan.fromJson(entity.planJson) == null) {
                return FunctionResult.failure("原计划无效，请重新创建任务")
            }
            automationDao.insert(
                entity.copy(
                    title = newTitle,
                    scheduleType = scheduleType,
                    scheduleValue = scheduleValue,
                    nextRunAt = nextRun,
                ),
            )
            refreshNextRun()
            traceManager.record(
                "automation",
                NodeType.SYSTEM_EVENT,
                metadata.id,
                "automation_updated: id=$id schedule=$scheduleType/$scheduleValue nextRun=$nextRun",
            )
            return FunctionResult.success(
                "已更新任务 [$id] → $scheduleType/$scheduleValue" +
                    if (nextRun == Long.MAX_VALUE) "（手动）" else "",
            )
        }

        private suspend fun listAutomations(): FunctionResult {
            val list = automationDao.getActive()
            if (list.isEmpty()) return FunctionResult.success("📋 暂无自动化任务")
            return FunctionResult.success(
                list.joinToString("\n") { e ->
                    "[${e.id}] ${e.title} · ${e.scheduleType}" +
                        (if (e.scheduleValue.isNotBlank()) "/${e.scheduleValue}" else "") +
                        " · 已运行${e.runCount}次"
                },
            )
        }

        private suspend fun manageAutomation(args: Map<String, Any?>): FunctionResult {
            val id = args["automation_id"] as? String ?: return FunctionResult.failure("automation_id 不能为空")
            val action = (args["action"] as? String ?: "").lowercase()
            val status =
                when (action) {
                    "pause" -> "PAUSED"
                    "resume" -> "ACTIVE"
                    "delete" -> "DELETED"
                    else -> return FunctionResult.failure("action 须为 pause/resume/delete")
                }
            automationDao.setStatus(id, status)
            refreshNextRun()
            traceManager.record(
                "automation",
                NodeType.SYSTEM_EVENT,
                metadata.id,
                "automation_managed: id=$id action=$action",
            )
            return FunctionResult.success("已$action 任务 [$id]")
        }

        private suspend fun runNow(args: Map<String, Any?>): FunctionResult {
            val id = args["automation_id"] as? String ?: return FunctionResult.failure("automation_id 不能为空")
            val entity =
                automationDao.getById(id)
                    ?: return FunctionResult.failure("未找到任务 [$id]")
            val summary =
                try {
                    runWithClaim(entity, autonomous = false)
                } catch (e: GuardException) {
                    return FunctionResult.failure("执行被守护拦截: ${e.message}")
                }
            return FunctionResult.success(summary)
        }

        private suspend fun getResults(args: Map<String, Any?>): FunctionResult {
            val id = args["automation_id"] as? String ?: return FunctionResult.failure("automation_id 不能为空")
            val history = runHistory[id]
            if (history.isNullOrEmpty()) return FunctionResult.success("任务 [$id] 暂无执行记录（重启后历史清空，持久化历史见运行日志）")
            return FunctionResult.success(history.reversed().joinToString("\n---\n"))
        }

        // ═══════════════════════════════════════════════════
        // Plan-Execute 引擎
        // ═══════════════════════════════════════════════════

        /** LLM 生成计划；失败降级 RuleBasedTaskParser（DESIGN §7.6） */
        private suspend fun generatePlan(description: String): ExecutionPlan {
            val model =
                modelSelector.select(setOf(ModelCapability.CHAT))
                    ?: return RuleBasedTaskParser.parse(description).also {
                        traceManager.record(
                            "automation", NodeType.SYSTEM_EVENT, metadata.id,
                            "plan_fallback: 无可用模型，走规则解析",
                        )
                    }
            // 排除自动化元工具 — 防止 LLM 在计划里递归创建/管理自动化
            val toolList =
                toolRuntime.getAllDefinitions()
                    .filter { it.name !in PLAN_EXCLUDED_TOOLS }
                    .joinToString("\n") { "- ${it.name}: ${it.description}" }
            val prompt =
                """
                将以下任务分解为可执行步骤，输出严格 JSON（无多余文字）:
                {"goal":"...","steps":[{"index":1,"description":"...","tool":"web_search 或 null","arguments":{},"dependsOn":[]}]}

                可用工具:
                $toolList

                任务: $description

                规则:
                1. 最多 ${config.maxSteps} 步。典型结构 = 信息获取(工具) → 整合推理(tool=null) → 送达
                2. 任务含"推送/通知"诉求时，最后一步必须用 push_notification，content 写 "{N}"（N=整合步骤序号）
                3. 任何工具参数值可写 "{N}" 引用第 N 步的完整输出（如搜索后总结再推送）
                4. dependsOn 为 1-based 序号数组；纯推理/总结步骤 tool 为 null；参数键须与工具定义一致
                5. web_search 的 query 只包含搜索关键词（如"邵阳 明日天气"），剔除时间与动作描述（如"每天9点搜索...并推送"）
                """.trimIndent()

            return try {
                val raw = model.chatSync(prompt, "你是任务规划引擎，只输出 JSON。")
                val json = JsonExtractor.extract(raw)
                val plan = json?.let { ExecutionPlan.fromJson(it) }
                if (plan != null) {
                    traceManager.record(
                        "automation",
                        NodeType.SYSTEM_EVENT,
                        metadata.id,
                        "plan_generated: llm steps=${plan.steps.size} tools=${plan.steps.count { it.tool != null }}",
                    )
                    plan
                } else {
                    traceManager.record(
                        "automation",
                        NodeType.SYSTEM_EVENT,
                        metadata.id,
                        "plan_fallback: LLM 输出不可解析，走规则解析",
                    )
                    RuleBasedTaskParser.parse(description)
                }
            } catch (e: Exception) {
                traceManager.record(
                    "automation",
                    NodeType.ERROR,
                    metadata.id,
                    "plan_fallback: LLM 调用失败 ${e.message?.take(60)}，走规则解析",
                )
                RuleBasedTaskParser.parse(description)
            }
        }

        /**
         * 执行一个自动化任务（v3.4 D41 分派: REACT / PLAN 两模式）。
         */
        private suspend fun runWithClaim(entity: AutomationEntity, autonomous: Boolean): String =
            executionClaims.run(entity.id) {
                val fresh = automationDao.getById(entity.id) ?: return@run "任务已删除 [${entity.id}]"
                if (fresh.status == "DELETED") return@run "任务已删除 [${entity.id}]"
                val changed = fresh.nextRunAt != entity.nextRunAt || fresh.status != "ACTIVE"
                if (autonomous && (changed || fresh.nextRunAt > System.currentTimeMillis())) {
                    return@run "任务调度已变更，跳过旧触发 [${entity.id}]"
                }
                try {
                    runAutomation(fresh, autonomous)
                } catch (cancelled: CancellationException) {
                    traceManager.record("automation", NodeType.SYSTEM_EVENT, metadata.id,
                        "run_cancelled: id=${entity.id}")
                    throw cancelled
                }
            } ?: "任务正在执行，本次触发已合并 [${entity.id}]"

        private suspend fun runAutomation(
            entity: AutomationEntity,
            autonomous: Boolean,
        ): String {
            // N5：预算守卫运行前检查（REACT/PLAN 两模式同拦；fail-open X8）
            when (scheduleGateway.preCheck("automation")) {
                com.zero.v2.services.agent.BudgetGuard.Verdict.DAILY_EXCEEDED -> {
                    traceManager.record(
                        "automation",
                        NodeType.SYSTEM_EVENT,
                        metadata.id,
                        "budget_blocked: DAILY_EXCEEDED id=${entity.id}",
                    )
                    return "⏸ 今日 Token 预算已用尽，自动化已跳过（设置 → Agent 行为 可调）"
                }
                com.zero.v2.services.agent.BudgetGuard.Verdict.MONTHLY_EXCEEDED -> {
                    traceManager.record(
                        "automation",
                        NodeType.SYSTEM_EVENT,
                        metadata.id,
                        "budget_blocked: MONTHLY_EXCEEDED id=${entity.id}",
                    )
                    return "⏸ 本月费用上限已到，自动化已跳过（设置 → Agent 行为 可调）"
                }
                else -> {}
            }
            if (entity.execMode == "REACT") {
                return runReactAutomation(entity, autonomous)
            }
            // SD3（2026-09-18 用户裁定）：SELF_DRIVE——到点不做固定动作，任务全权交给
            // 自驱动回合（共享主对话上下文 + 全套工具 + 用户可打断 D68 + 每日触发上限）。
            // 与 REACT 差异：REACT=独立任务域循环（goal 封闭）；SELF_DRIVE=主对话上下文内
            // 自主行动（记忆连续，适合"类 Claude Code"的开放性工作流）。
            if (entity.execMode == "SELF_DRIVE") {
                val fired =
                    scheduleGateway.fireSelfDrive(
                        "自动化任务「${entity.title}」到点：${entity.description}",
                        "automation:${entity.id}",
                    )
                val now = System.currentTimeMillis()
                automationDao.markRun(entity.id, now, nextRunAfter(entity, now))
                recordHistory(entity.id, if (fired) "🧠 已交自驱动回合执行（共享对话上下文）" else "⏸ 自驱动开关未开/日顶已满——仅记账未触发")
                refreshNextRun()
                traceManager.record(
                    "automation",
                    NodeType.SYSTEM_EVENT,
                    metadata.id,
                    "self_drive_dispatched: id=${entity.id} fired=$fired",
                )
                return if (fired) {
                    "已交自驱动回合 [${entity.id}]"
                } else {
                    "自驱动未触发（开关关/日顶满）[${entity.id}]"
                }
            }
            val plan =
                ExecutionPlan.fromJson(entity.planJson)
                    ?: return "计划解析失败 [${entity.id}]"
            // v5.2: 守护参数从 ConfigField 读取（原硬编码 10/50K/5min）
            val guard =
                ExecutionGuard(
                    maxSteps = config.maxSteps,
                    maxTokensPerRun = config.maxTokensPerRun.toLong(),
                    maxDurationMs = config.maxDurationMin * 60_000L,
                )
            guard.start()
            guard.enforceStepLimit(plan.steps.size)

            traceManager.record(
                "automation",
                NodeType.TOOL_CALL_START,
                metadata.id,
                "automation_run_start: id=${entity.id} goal=${plan.goal.take(40)} steps=${plan.steps.size} autonomous=$autonomous",
            )
            _runProgress.value = RunProgress(entity.id, entity.title, 0, plan.steps.size, "")

            val results = java.util.concurrent.ConcurrentHashMap<Int, String>()
            val ordered = plan.steps.sortedBy { it.index }.toMutableList()

            while (ordered.isNotEmpty()) {
                guard.checkTimeout()
                // 非法图已在解析/保存时拒绝；运行时仍不允许缺依赖的步骤执行。
                val wave =
                    ordered.filter { s -> s.dependsOn.all { results.containsKey(it) } }
                        .also { check(it.isNotEmpty()) { "非法依赖图：无就绪步骤" } }
                coroutineScope {
                    wave.map { step ->
                        async {
                            // v3.4 (D42): 依赖上下文 400→2500 字（实测搜索正文被截致推理拿不到数据）
                            val depContext =
                                step.dependsOn.joinToString("\n") {
                                    "[步骤$it 结果] ${results[it]?.take(2500) ?: "(未执行)"}"
                                }
                            val result =
                                try {
                                    executeStep(plan, step, depContext, results)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    "[步骤异常] ${e.message?.take(60)}"
                                }
                            results[step.index] = result
                            // 实时进度（UI 逐步刷新，stepResults 携带完整结果供折叠查看）
                            _runProgress.value =
                                RunProgress(
                                    entity.id, entity.title, results.size, plan.steps.size,
                                    result.take(120), stepResults = results.toMap(),
                                )
                        }
                    }.awaitAll()
                }
                ordered.removeAll(wave.toSet())
                // Token 预算守护（粗估: 2 字符 ≈ 1 token）
                guard.checkTokens(results.values.sumOf { it.length / 2L })
            }

            // v3.4 (D42): 最终内容 = 最后一次成功推送的全文（结果页下部主体）
            val finalContent =
                plan.steps
                    .filter { it.tool == "push_notification" && results.containsKey(it.index) }
                    .lastOrNull { results[it.index]?.startsWith("🔔 [推送成功]") == true }
                    ?.let { results[it.index]!!.substringAfter("\n") }

            val summary =
                buildString {
                    appendLine("✅ 任务 [${entity.title}] 执行完成（${plan.steps.size} 步）")
                    if (finalContent != null) {
                        appendLine("【最终推送】")
                        appendLine(finalContent)
                    }
                    appendLine("【过程概览】")
                    results.toSortedMap().forEach { (idx, r) ->
                        appendLine("步骤$idx: ${r.replace(Regex("\\s+"), " ").take(120)}")
                    }
                }.trim()

            // 调度推进 + 历史 + 通知
            val now = System.currentTimeMillis()
            automationDao.markRun(entity.id, now, nextRunAfter(entity, now))
            recordHistory(entity.id, summary)
            refreshNextRun()
            _runProgress.value =
                RunProgress(
                    entity.id, entity.title, plan.steps.size, plan.steps.size,
                    "", stepResults = results.toMap(), done = true,
                    summary = summary.take(3000), finalContent = finalContent,
                )

            traceManager.record(
                "automation",
                NodeType.TOOL_CALL_RESULT,
                metadata.id,
                "automation_run_done: id=${entity.id} steps=${plan.steps.size} ok=${results.values.count { !it.startsWith("[步骤异常]") && !it.startsWith("[工具失败]") }}",
            )

            if (autonomous) {
                chatBus.onPokeSend.emit(
                    PokeEvent(
                        message = summary.take(500),
                        source = metadata.id,
                        priority = PokePriority.NORMAL,
                    ),
                )
            }
            return summary
        }

        // ═══════════════════════════════════════════════════════
        // v3.4 (D41): REACT 执行器 — LLM 驱动的自主子任务循环（类 Claude Code agent loop）
        // ═══════════════════════════════════════════════════════

        /** REACT 单轮动作（LLM 输出解析结果） */
        private sealed interface ReactAction {
            data class Tool(val tool: String, val arguments: Map<String, Any?>, val thought: String) : ReactAction

            data class Final(val result: String) : ReactAction
        }

        /**
         * REACT 模式执行：观察（transcript）→ 决策（LLM 选工具/结束）→ 执行 → 循环。
         * 守护: maxSteps 轮 / Token / 超时（同 PLAN）；禁用自动化元工具（防递归自指）。
         */
        private suspend fun runReactAutomation(
            entity: AutomationEntity,
            autonomous: Boolean,
        ): String {
            val goal = entity.description
            val guard =
                ExecutionGuard(
                    maxSteps = config.maxSteps,
                    maxTokensPerRun = config.maxTokensPerRun.toLong(),
                    maxDurationMs = config.maxDurationMin * 60_000L,
                )
            guard.start()
            traceManager.record(
                "automation",
                NodeType.TOOL_CALL_START,
                metadata.id,
                "react_start: id=${entity.id} goal=${goal.take(40)} maxRounds=${config.maxSteps} autonomous=$autonomous",
            )
            _runProgress.value = RunProgress(entity.id, entity.title, 0, config.maxSteps, "")

            val model =
                try {
                    modelSelector.select(setOf(ModelCapability.CHAT))
                } catch (_: Exception) {
                    null
                }
                    ?: return "无可用模型，REACT 任务无法执行 [${entity.id}]"
            val tools =
                toolRuntime.getAllDefinitions()
                    .filter { it.name !in PLAN_EXCLUDED_TOOLS }

            val transcript = StringBuilder()
            var round = 0
            var consecutiveParseFail = 0
            var finalContent: String? = null
            val results = java.util.concurrent.ConcurrentHashMap<Int, String>()

            while (round < config.maxSteps) {
                round++
                guard.checkTimeout()
                val prompt =
                    """
                    任务目标: $goal

                    已执行过程:
                    ${if (transcript.isBlank()) "（尚未开始）" else transcript.toString().takeLast(6000)}

                    可用工具:
                    ${tools.joinToString("\n") { "- ${it.name}: ${it.description.take(80)}" }}

                    请决定下一步。输出严格 JSON（无其他文字）:
                    调用工具: {"action":"tool","thought":"为什么","tool":"web_search","arguments":{"query":"..."}}
                    任务完成: {"action":"final","thought":"为什么完成","result":"给用户看的最终结果全文（如需推送，这里就是推送内容）"}

                    规则: 工具参数值可用 "{{上一步结果}}" 引用上一步输出；需要告知/推送用户时直接 final（推送由引擎处理）；不要重复调用相同参数的工具。
                    """.trimIndent()

                val action =
                    try {
                        parseReactAction(model.chatSync(prompt, "你是任务执行引擎，每轮只输出一个 JSON 对象。"))
                    } catch (e: Exception) {
                        transcript.append("\n第${round}轮 [引擎异常] ${e.message?.take(60)}")
                        break
                    }
                if (action == null) {
                    consecutiveParseFail++
                    transcript.append("\n第${round}轮 [输出不可解析，重试]")
                    if (consecutiveParseFail >= 3) break else continue
                }
                consecutiveParseFail = 0

                when (action) {
                    is ReactAction.Final -> {
                        finalContent = action.result.trim().take(4000)
                        transcript.append("\n第${round}轮 [完成] ${finalContent.take(120)}")
                        results[round] = "🔔 [完成] $finalContent"
                    }
                    is ReactAction.Tool -> {
                        transcript.append("\n第${round}轮 [${action.tool}] ${action.thought.take(60)}")
                        val resolved = resolveReactArgs(action.arguments, transcript.toString())
                        val r =
                            try {
                                toolRuntime.execute(action.tool, resolved)
                            } catch (e: Exception) {
                                FunctionResult.failure("执行异常: ${e.message?.take(60)}")
                            }
                        val record = if (r.success) r.data.take(2500) else "[工具失败] ${r.errorMessage}"
                        transcript.append(" → ${record.take(300)}")
                        results[round] = "第${round}轮 [${action.tool}]\n$record"
                        // 推送工具成功 → 记录推送全文为候选最终内容
                        if (action.tool == "push_notification" && r.success) {
                            finalContent = "${resolved["title"]}\n${resolved["content"]}"
                            results[round] = "🔔 [推送成功] ${resolved["title"]}\n${resolved["content"]}"
                        }
                    }
                }
                if (finalContent != null && action is ReactAction.Final) break
                _runProgress.value =
                    RunProgress(
                        entity.id, entity.title, round, config.maxSteps,
                        results[round]?.take(120) ?: "", stepResults = results.toMap(),
                    )
                guard.checkTokens(transcript.length / 2L)
            }

            val finalText = finalContent ?: transcript.toString().takeLast(2000).ifBlank { "任务达到轮次上限" }
            val summary =
                buildString {
                    appendLine("✅ 任务 [${entity.title}] REACT 执行完成（$round 轮）")
                    appendLine("【最终结果】")
                    appendLine(finalText)
                    appendLine("【过程概览】")
                    results.toSortedMap().forEach { (i, r) -> appendLine("第${i}轮: ${r.replace(Regex("\\s+"), " ").take(120)}") }
                }.trim()

            val now = System.currentTimeMillis()
            automationDao.markRun(entity.id, now, nextRunAfter(entity, now))
            recordHistory(entity.id, summary)
            refreshNextRun()
            _runProgress.value =
                RunProgress(
                    entity.id, entity.title, round, config.maxSteps,
                    "", stepResults = results.toMap(), done = true,
                    summary = summary.take(3000), finalContent = finalText,
                )

            traceManager.record(
                "automation",
                NodeType.TOOL_CALL_RESULT,
                metadata.id,
                "react_done: id=${entity.id} rounds=$round final=${finalContent != null}",
            )

            if (autonomous) {
                chatBus.onPokeSend.emit(PokeEvent(message = finalText.take(500), source = metadata.id, priority = PokePriority.NORMAL))
            }
            return summary
        }

        /** REACT 动作解析（三层容错：整体 JSON → 直接 JSON → 正则抠字段） */
        private fun parseReactAction(raw: String): ReactAction? {
            val obj =
                JsonExtractor.extract(raw)?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
                    ?: runCatching { org.json.JSONObject(raw.trim()) }.getOrNull()
                    ?: return null
            val action = obj.optString("action", "tool")
            return if (action == "final") {
                val result = obj.optString("result").ifBlank { obj.optString("thought") }
                if (result.isBlank()) null else ReactAction.Final(result)
            } else {
                val tool = obj.optString("tool").trim()
                if (tool.isBlank() || tool in PLAN_EXCLUDED_TOOLS) {
                    null
                } else {
                    val args =
                        obj.optJSONObject("arguments")?.let { a ->
                            a.keys().asSequence().associateWith { k -> a.get(k) }
                        } ?: emptyMap()
                    ReactAction.Tool(tool, args, obj.optString("thought"))
                }
            }
        }

        /** REACT 参数引用: "{{上一步结果}}"（含花括号双写）→ 上一步输出尾部 */
        private fun resolveReactArgs(
            args: Map<String, Any?>,
            transcript: String,
        ): Map<String, Any?> {
            val ref = Regex("""\{\{[^}]*\}\}""")
            val lastOutput = transcript.substringAfterLast(" → ").trim()
            return args.mapValues { (_, v) ->
                if (v is String && ref.containsMatchIn(v)) ref.replace(v) { lastOutput.take(1500) } else v
            }
        }

        private suspend fun executeStep(
            plan: ExecutionPlan,
            step: PlanStep,
            depContext: String,
            results: Map<Int, String>,
        ): String {
            return if (step.tool != null) {
                val resolved = resolveArgs(step.arguments, results)
                val r = toolRuntime.execute(step.tool, resolved)
                if (r.success) {
                    // v3.4 (D42): 推送步骤附带完整推送内容（结果页"最终结果"数据源）
                    if (step.tool == "push_notification") {
                        "🔔 [推送成功] ${resolved["title"]}\n${resolved["content"]}"
                    } else {
                        r.data
                    }
                } else {
                    "[工具失败] ${r.errorMessage ?: "未知错误"}"
                }
            } else {
                val model =
                    modelSelector.select(setOf(ModelCapability.CHAT))
                        ?: return "[无可用模型，推理步骤跳过]"
                model.chatSync(
                    "任务: ${plan.goal}\n当前步骤: ${step.description}\n\n依赖结果:\n$depContext\n\n请完成本步骤，直接输出结果。",
                    "你是任务执行引擎，基于已有工具结果完成当前步骤。",
                )
            }
        }

        /**
         * 参数结果引用: 字符串值中的 "{N}"（整串或嵌入，如 "{1} 明日天气"）替换为第 N 步输出。
         * 使计划能表达"搜索 → 整合 → 推送(内容=整合结果)"的链式结构。
         */
        private fun resolveArgs(
            args: Map<String, Any?>,
            results: Map<Int, String>,
        ): Map<String, Any?> {
            val refPattern = Regex("""\{(\d+)\}""")
            return args.mapValues { (_, v) ->
                if (v is String && refPattern.containsMatchIn(v)) {
                    refPattern.replace(v) { m ->
                        results[m.groupValues[1].toInt()] ?: m.value
                    }
                } else {
                    v
                }
            }
        }

        // ═══════════════════════════════════════════════════
        // 调度计算
        // ═══════════════════════════════════════════════════

        /** 执行后推进下次时间 */
        private fun nextRunAfter(
            entity: AutomationEntity,
            now: Long,
        ): Long =
            when (entity.scheduleType) {
                "DAILY" -> AutomationSchedulePolicy.nextRun("DAILY", entity.scheduleValue, now).takeIf { it > now } ?: (now + 86_400_000L)
                "INTERVAL" -> AutomationSchedulePolicy.nextRun("INTERVAL", entity.scheduleValue, now)
                else -> Long.MAX_VALUE
            }

        private suspend fun refreshNextRun() {
            cachedNextRun = automationDao.getNextRunAt() ?: Long.MAX_VALUE
            // v3.5: 按下次到期安排准点唤醒（tick 节奏最长 ~2.7h，等 tick 会迟到）
            wakeJob?.cancel()
            val wait = cachedNextRun - System.currentTimeMillis()
            if (wait > 0) {
                // v3.4 (D40): 精确闹钟为主（进程冻结仍准点）+ 进程内 kick 为辅（前台更快）
                scheduleGateway.scheduleAutomation(cachedNextRun)
                wakeJob =
                    wakeScope.launch {
                        delay(wait)
                        scheduleGateway.kick()
                    }
            }
        }

        private fun recordHistory(
            id: String,
            summary: String,
        ) {
            val list = runHistory.getOrPut(id) { mutableListOf() }
            list.add(summary)
            while (list.size > HISTORY_PER_AUTOMATION) list.removeAt(0)
        }
    }

/**
 * 执行守护 — 对标 Claude Code 的权限/成本限制。
 *
 * v2.1 增强：
 * - maxReplanCount: 最大重规划次数（默认 2），防止 LLM 无限建议新步骤
 * - remainingTokens(): Token 预算剩余查询
 */
class ExecutionGuard(
    val maxSteps: Int = 10,
    val maxReplanCount: Int = 2,
    val maxTokensPerRun: Long = 50_000,
    val maxDurationMs: Long = 300_000,
) {
    private var stepsExecuted = 0
    private var replanCount = 0
    private var startTime = 0L
    private var tokensUsed = 0L

    fun start() {
        startTime = System.currentTimeMillis()
    }

    fun canExecute(): Boolean = true

    fun enforceStepLimit(plannedSteps: Int) {
        if (plannedSteps > maxSteps) throw GuardException("计划步骤 $plannedSteps 超过上限 $maxSteps")
    }

    fun checkTimeout() {
        if (System.currentTimeMillis() - startTime > maxDurationMs) throw GuardException("执行超时")
    }

    fun checkTokens(used: Long) {
        tokensUsed = used
        if (tokensUsed > maxTokensPerRun) throw GuardException("Token 超限 $tokensUsed/$maxTokensPerRun")
    }

    fun remainingTokens(): Long = maxTokensPerRun - tokensUsed

    fun canReplan(): Boolean {
        replanCount++
        return replanCount <= maxReplanCount
    }
}

class GuardException(message: String) : Exception(message)

/** 运行进度 — UI 实时展示（AutomationScreen 详情对话框逐步刷新） */
data class RunProgress(
    val automationId: String,
    val title: String,
    /** 已完成的步骤数 */
    val stepIndex: Int,
    val totalSteps: Int,
    /** 最近一步结果摘要 */
    val lastResult: String,
    /** 各步骤完整结果（index → 全文），UI 折叠查看 */
    val stepResults: Map<Int, String> = emptyMap(),
    val done: Boolean = false,
    /** done 时的执行摘要 */
    val summary: String? = null,
    /** v3.4 (D42): 最终结果全文（推送内容）— 结果页下部主体 */
    val finalContent: String? = null,
)

/**
 * 自动化模块配置 — v5.2（ExecutionGuard 参数用户化，DESIGN §1.3）。
 * 注: 数值全用 Int（D30 反射写入仅转换 Float/Int/Boolean）。
 */
class AutomationModuleConfig : ModuleConfigBase() {
    @ConfigField(
        label = "最大计划步骤",
        fieldType = ConfigFieldType.NUMBER,
        min = 3.0,
        max = 100.0,
        description = "单个自动化任务的计划步骤上限；>100 步易失控烧 Token（Token 预算独立兜底）",
    )
    var maxSteps: Int = 10

    @ConfigField(
        label = "单次执行 Token 上限",
        fieldType = ConfigFieldType.NUMBER,
        min = 50_000.0,
        max = 2_000_000.0,
        description = "单次自动化执行的 Token 预算（超出即守护终止）。REACT 逐步累积：10 步≈27 万；默认 50 万",
    )
    var maxTokensPerRun: Int = 500_000

    @ConfigField(
        label = "单次执行超时(分钟)",
        fieldType = ConfigFieldType.NUMBER,
        min = 1.0,
        max = 240.0,
        description = "单次自动化执行的最大时长",
    )
    var maxDurationMin: Int = 5

    override fun toJson(): String {
        return """{"maxSteps":$maxSteps,"maxTokensPerRun":$maxTokensPerRun,"maxDurationMin":$maxDurationMin}"""
    }

    override fun fromJson(json: String) {
        try {
            val obj = JSONObject(json)
            // N4.2：钳位兜底 + 修历史 BUG（旧默认 50000 越界 @ConfigField max=100 脚枪；50000→500000 见 §7.3）
            maxSteps = obj.optInt("maxSteps", 10).coerceIn(3, 100)
            maxTokensPerRun = obj.optLong("maxTokensPerRun", 500_000).coerceIn(50_000L, 2_000_000L).toInt()
            maxDurationMin = obj.optInt("maxDurationMin", 5).coerceIn(1, 240)
        } catch (_: Exception) {
        }
    }

    override fun defaultConfig(): ModuleConfigBase = AutomationModuleConfig()
}

/**
 * v2.2: JSON 健壮提取器。
 *
 * LLM 返回的 JSON 可能有 Markdown 包装、解释性前缀、尾部注释。
 * 4 层分层提取策略确保容错。
 */
object JsonExtractor {
    private val codeBlockPattern = Regex("```(?:json)?\\n?([\\s\\S]*?)\\n?```")
    private val bracesPattern = Regex("\\{(?:[^{}]|\\{[^{}]*\\})*\\}")

    fun extract(rawText: String): String? {
        // 1. 直接解析
        if (rawText.trimStart().startsWith("{")) return rawText.trim()
        // 2. 移除 Markdown 代码块
        codeBlockPattern.find(rawText)?.groupValues?.get(1)?.trim()?.let { return it }
        // 3. 正则提取最外层 {}
        bracesPattern.find(rawText)?.value?.let { return it }
        // 4. 无法提取
        return null
    }
}

