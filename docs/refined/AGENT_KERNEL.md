# Agent Kernel 架构设计

> 对 DEVELOPMENT_DESIGN.md v3.0 的战略性架构升级
> 核心思想：将分散在 Module/Automation/Memory/Runtime 中的"决策权"统一到 Agent Kernel
> 2026-08-11

---

## 一、问题诊断

### 1.1 v3.0 的架构问题

v3.0 设计了四个子系统：

```
Module Framework  → 管理模块生命周期、FC 注册
Automation Engine → Plan-Execute 循环、状态机
Memory System     → 记忆存储/检索/演化
Agent Runtime     → 自主唤醒→观察→反思→决策→行动
```

问题在于：**"谁来做决策"被分散了**。

- `AgentRuntime.decide()` 决定是否自主行动
- `ExecutionGuard` 决定是否允许执行
- `FunctionRegistry.executeWithSecurity()` 决定 SENSITIVE 操作是否放行
- `MemoryConflictDetector` 决定记忆如何解决冲突

这些决策点互相不知道对方的存在。结果：

| 场景 | 会发生什么 |
|------|-----------|
| Agent 凌晨自主反思，调用 LLM 消耗 50000 tokens | 没有预算控制 — ExecutionGuard 只管单次自动化 |
| Agent 决定"帮用户整理文件" | SAFE/SENSITIVE 是给 FC 函数用的，自主行动的审批模型完全不同 |
| 用户关闭通知权限后 | Agent Runtime 仍然在后台全速运行，白白消耗电量和 API 费用 |
| 厂商杀进程 | `START_STICKY` 强行拉活，用户看到常驻通知感到被侵扰 |

### 1.2 需要一个统一的"大脑"

```
v3.0（决策分散）:
  AgentRuntime ──→ decide()
  ExecutionGuard ─→ canExecute()
  SecurityInterceptor ─→ SAFE/SENSITIVE
  MemoryConflictDetector ─→ resolve()

v4.0（决策统一）:
  Agent Kernel ──→ 所有决策在这里统一
    ├── Policy Engine（权限 + 预算 + 模式）
    ├── Planner（Plan-Execute）
    ├── Memory（存储 + 演化）
    └── Runtime（唤醒 + 循环）
```

---

## 二、Agent Kernel 架构

### 2.1 全景

```
                        ┌─────────────────────────────────┐
                        │         Agent Kernel             │
                        │                                 │
                        │   ┌─────────────────────────┐   │
                        │   │      Core Loop (OODA)    │   │
                        │   │                         │   │
                        │   │  Observe ──→ Orient      │   │
                        │   │     ↑           ↓        │   │
                        │   │     │        Decide      │   │
                        │   │     │           ↓        │   │
                        │   │     └──── Act ←─┘        │   │
                        │   └─────────────────────────┘   │
                        │                                 │
                        │   ┌──────────┐ ┌──────────────┐ │
                        │   │  Policy   │ │   Planner    │ │
                        │   │  Engine   │ │   Engine     │ │
                        │   └──────────┘ └──────────────┘ │
                        │   ┌──────────┐ ┌──────────────┐ │
                        │   │  Memory   │ │   Runtime    │ │
                        │   │  System   │ │   Manager    │ │
                        │   └──────────┘ └──────────────┘ │
                        └────────────┬────────────────────┘
                                     │
                        ┌────────────┴────────────────────┐
                        │        Capability Layer          │
                        │                                 │
                        │  Tools    Modules    MCP    APIs │
                        │  (FC)   (IZeroMod) (ext) (Android)│
                        └─────────────────────────────────┘
```

**Agent Kernel 是"大脑"**：负责思考、记忆、决策、权限、成本控制。
**Capability Layer 是"身体"**：提供工具、模块、外部服务、Android 原生能力。

### 2.2 Kernel 的四个子系统

| 子系统 | 职责 | 包含 |
|--------|------|------|
| **Policy Engine** | 所有"能不能做"的决策 | AgentMode, AgentBudget, ActionPermission, 成本核算 |
| **Planner Engine** | 所有"怎么做"的决策 | Plan-Execute 循环, ExecutionGuard, 状态机, TaskParser |
| **Memory System** | 所有"记住什么"的决策 | 存储/检索/演化, 冲突解决, 反思任务, Embedding |
| **Runtime Manager** | 所有"什么时候做"的决策 | 唤醒调度, OODA 循环, 电源管理, 生命周期 |

### 2.3 Kernel 核心接口

```kotlin
/**
 * Agent Kernel — 统一的 Agent 决策中心。
 *
 * 所有自主行为的决策都经过 Kernel，不外泄到各模块。
 * Module/Tool/MCP 只负责执行，不负责决策。
 */
interface IAgentKernel {
    // ═══ 生命周期 ═══
    val mode: StateFlow<AgentMode>
    suspend fun start()
    suspend fun stop()
    suspend fun setMode(mode: AgentMode)

    // ═══ 决策入口 ═══
    /** 观察 → 反思 → 决策 → 行动（一次完整 OODA 循环） */
    suspend fun runCycle(): CycleResult

    // ═══ 子系统 ═══
    val policy: IPolicyEngine
    val planner: IPlannerEngine
    val memory: IMemorySystem
    val runtime: IRuntimeManager
}
```

---

## 三、Policy Engine — 所有"能不能"的决策

### 3.1 Agent Mode

```kotlin
/**
 * Agent 自主运行模式。
 *
 * OFF:    Agent 完全被动，仅在用户发送消息时响应。不执行任何自主行为。
 *         适合：隐私敏感场景、省电模式、用户主动关闭。
 *
 * SMART:  智能模式（默认）。Agent 在以下条件同时满足时自主运行：
 *         1. 用户近期有交互（24h 内有对话）
 *         2. 网络为 WiFi 或用户允许移动数据
 *         3. 电量 > 20% 或正在充电
 *         4. 不在用户设置的"勿扰时段"
 *         5. 未超出每日 Token 预算
 *
 * ALWAYS: 最大自主模式。Agent 持续运行，仅在电量 < 10% 且未充电时暂停。
 *         适合：需要 Agent 全天候主动服务的重度用户。
 */
enum class AgentMode {
    OFF,
    SMART,     // ← 默认
    ALWAYS_ON
}

/**
 * SMART 模式的激活条件。
 */
data class SmartModeConditions(
    val requireRecentInteraction: Boolean = true,     // 24h 内有对话
    val requireWifiOrAllowed: Boolean = true,          // WiFi 或用户允许移动数据
    val minBatteryLevel: Float = 0.2f,                 // 电量 > 20%
    val respectDoNotDisturb: Boolean = true,           // 勿扰时段不运行
    val respectBudget: Boolean = true                  // 不超出预算
) {
    fun isSatisfied(state: DeviceState, budget: AgentBudget): Boolean {
        if (requireRecentInteraction && state.hoursSinceLastInteraction > 24) return false
        if (requireWifiOrAllowed && !state.isWifi && !state.isMobileDataAllowed) return false
        if (state.batteryLevel < minBatteryLevel && !state.isCharging) return false
        if (respectDoNotDisturb && state.isDoNotDisturb) return false
        if (respectBudget && budget.isExhausted()) return false
        return true
    }
}
```

### 3.2 Agent Budget

```kotlin
/**
 * Agent 自主行为预算。
 *
 * 防止 Agent 在无人监管时消耗过多 API 费用。
 * 预算仅限制"自主行为"（Agent 自己发起的），用户主动触发的对话不受限。
 */
data class AgentBudget(
    /** 每日 Token 上限（自主行为）。用户可在设置中调整。默认 50000 */
    val dailyTokenLimit: Long = 50_000,

    /** 每月费用上限（USD，按各模型定价估算）。用户可在设置中调整。默认 $5 */
    val monthlyCostLimitUsd: Float = 5.0f,

    /** 每日最大自主行动次数。用户可在设置中调整。默认 20 */
    val maxAutonomousActionsPerDay: Int = 20,

    /** 单次自主行动最大 Token。用户可在设置中调整。默认 10000 */
    val maxTokensPerAction: Long = 10_000,

    /** 每小时最大自主行动次数（防止高频骚扰）。用户可在设置中调整。默认 3 */
    val maxActionsPerHour: Int = 3
)

/**
 * AgentBudget 的 DataStore 持久化 key。
 * 所有字段通过 PreferencesManager 读写，设置页实时生效。
 */
object AgentBudgetKeys {
    const val DAILY_TOKEN_LIMIT         = "agent_budget_daily_token_limit"
    const val MONTHLY_COST_LIMIT_USD    = "agent_budget_monthly_cost_limit_usd"
    const val MAX_AUTONOMOUS_ACTIONS    = "agent_budget_max_autonomous_actions"
    const val MAX_TOKENS_PER_ACTION     = "agent_budget_max_tokens_per_action"
    const val MAX_ACTIONS_PER_HOUR      = "agent_budget_max_actions_per_hour"
}

/**
 * 将 AgentBudget 加载/保存到 DataStore。
 */
@Singleton
class AgentBudgetStore @Inject constructor(
    private val preferencesManager: PreferencesManager
) {
    val budget: Flow<AgentBudget> = combine(
        preferencesManager.getLong(AgentBudgetKeys.DAILY_TOKEN_LIMIT, 50_000),
        preferencesManager.getFloat(AgentBudgetKeys.MONTHLY_COST_LIMIT_USD, 5.0f),
        preferencesManager.getInt(AgentBudgetKeys.MAX_AUTONOMOUS_ACTIONS, 20),
        preferencesManager.getLong(AgentBudgetKeys.MAX_TOKENS_PER_ACTION, 10_000),
        preferencesManager.getInt(AgentBudgetKeys.MAX_ACTIONS_PER_HOUR, 3)
    ) { values ->
        AgentBudget(
            dailyTokenLimit = values[0] as Long,
            monthlyCostLimitUsd = values[1] as Float,
            maxAutonomousActionsPerDay = values[2] as Int,
            maxTokensPerAction = values[3] as Long,
            maxActionsPerHour = values[4] as Int
        )
    }

    suspend fun updateDailyTokenLimit(value: Long) {
        preferencesManager.putLong(AgentBudgetKeys.DAILY_TOKEN_LIMIT, value)
    }

    suspend fun updateMonthlyCostLimit(value: Float) {
        preferencesManager.putFloat(AgentBudgetKeys.MONTHLY_COST_LIMIT_USD, value)
    }

    suspend fun updateMaxAutonomousActions(value: Int) {
        preferencesManager.putInt(AgentBudgetKeys.MAX_AUTONOMOUS_ACTIONS, value)
    }

    suspend fun updateMaxTokensPerAction(value: Long) {
        preferencesManager.putLong(AgentBudgetKeys.MAX_TOKENS_PER_ACTION, value)
    }

    suspend fun updateMaxActionsPerHour(value: Int) {
        preferencesManager.putInt(AgentBudgetKeys.MAX_ACTIONS_PER_HOUR, value)
    }
}

/**
 * 预算追踪器。
 */
@Singleton
class AgentBudgetTracker @Inject constructor(
    private val budgetDao: AgentBudgetDao,
    private val modelSelector: ModelSelector
) {
    private val _dailyUsage = MutableStateFlow(DailyUsage())
    val dailyUsage: StateFlow<DailyUsage> = _dailyUsage.asStateFlow()

    /** 检查是否允许一次新的自主行动 */
    suspend fun canAct(estimatedTokens: Long): BudgetDecision {
        val daily = getDailyUsage()
        return when {
            daily.tokenUsed + estimatedTokens > budget.dailyTokenLimit ->
                BudgetDecision.Denied("超过每日 Token 上限 (${daily.tokenUsed}/${budget.dailyTokenLimit})")
            daily.actionCount >= budget.maxAutonomousActionsPerDay ->
                BudgetDecision.Denied("超过每日行动次数上限 (${daily.actionCount}/${budget.maxAutonomousActionsPerDay})")
            daily.hourlyActionCount >= budget.maxActionsPerHour ->
                BudgetDecision.Denied("本小时行动过于频繁，请稍后再试")
            estimatedTokens > budget.maxTokensPerAction ->
                BudgetDecision.Reduced(budget.maxTokensPerAction) // 自动裁剪
            else ->
                BudgetDecision.Approved(estimatedTokens)
        }
    }

    /** 记录一次自主行动的消耗 */
    suspend fun record(action: AutonomousAction) {
        budgetDao.insert(action)
        _dailyUsage.value = getDailyUsage()
    }

    fun isExhausted(): Boolean {
        val daily = _dailyUsage.value
        return daily.tokenUsed >= budget.dailyTokenLimit ||
               daily.actionCount >= budget.maxAutonomousActionsPerDay
    }

    private suspend fun getDailyUsage(): DailyUsage {
        val todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toEpochSecond() * 1000
        val actions = budgetDao.getActionsSince(todayStart)
        val hourAgo = System.currentTimeMillis() - 3600_000
        return DailyUsage(
            tokenUsed = actions.sumOf { it.tokensUsed },
            costUsd = actions.sumOf { it.costUsd },
            actionCount = actions.size,
            hourlyActionCount = actions.count { it.timestamp > hourAgo }
        )
    }
}

### 3.2.1 设置页入口

所有预算数值通过设置页让用户自行控制，不硬编码：

```
设置 → Agent 自主行为 → 成本控制

┌──────────────────────────────────────────┐
│  ← 返回     Agent 自主行为成本控制        │
├──────────────────────────────────────────┤
│                                          │
│  每日 Token 上限                          │
│  控制 Agent 每天最多使用多少 Token         │
│  ┌──────────────────────────────────┐    │
│  │  50000                    [滑块] │    │
│  │  ████████████░░░░░░░░░░░░░░░░░░  │    │
│  │  1K    10K    50K   100K   500K  │    │
│  └──────────────────────────────────┘    │
│  约等于 API 费用：~$0.15 / 天             │
│                                          │
│  每月费用上限                             │
│  ┌──────────────────────────────────┐    │
│  │  $5.00                    [滑块] │    │
│  │  ██░░░░░░░░░░░░░░░░░░░░░░░░░░░  │    │
│  │  $0   $2   $5   $10   $20  $50  │    │
│  └──────────────────────────────────┘    │
│                                          │
│  每日最大自主行动次数                      │
│  ┌─────┬─────┬─────┬─────┬─────┐        │
│  │  5  │ 10  │ 20✓ │ 50  │ 100 │        │
│  └─────┴─────┴─────┴─────┴─────┘        │
│                                          │
│  每小时最大行动次数                        │
│  ┌─────┬─────┬─────┬─────┐              │
│  │  1  │  3✓ │  5  │ 10  │              │
│  └─────┴─────┴─────┴─────┘              │
│                                          │
│  单次行动最大 Token                       │
│  ┌──────────────────────────────────┐    │
│  │  10000                    [滑块] │    │
│  └──────────────────────────────────┘    │
│                                          │
│  ─────────────────────────────────────  │
│  本月统计                                 │
│  Token 已用: 125,000 / 500,000           │
│  费用估算:   $1.25 / $5.00               │
│  行动次数:   42 / 600                    │
│                                          │
│  [重置本月统计]                           │
└──────────────────────────────────────────┘
```

对应的 Compose 代码：

```kotlin
@Composable
fun AgentBudgetSettingsScreen(viewModel: AgentBudgetViewModel = hiltViewModel()) {
    val budget by viewModel.budget.collectAsState()
    val usage by viewModel.dailyUsage.collectAsState()

    Scaffold(topBar = { TopAppBar(title = { Text("成本控制") }) }) { padding ->
        LazyColumn(Modifier.padding(padding).padding(16.dp)) {

            // 每日 Token 上限
            item {
                BudgetSlider(
                    label = "每日 Token 上限",
                    value = budget.dailyTokenLimit.toFloat(),
                    range = 1_000f..500_000f,
                    steps = 4, // 1K, 10K, 50K, 100K, 500K
                    onValueChange = { viewModel.updateDailyTokenLimit(it.toLong()) },
                    hint = "约等于 API 费用：~$${String.format("%.2f", budget.dailyTokenLimit * 0.000003)} / 天"
                )
            }

            // 每月费用上限
            item {
                BudgetSlider(
                    label = "每月费用上限",
                    value = budget.monthlyCostLimitUsd,
                    range = 1f..50f,
                    steps = 4, // $1, $2, $5, $10, $20, $50
                    onValueChange = { viewModel.updateMonthlyCostLimit(it) },
                    suffix = "USD"
                )
            }

            // 每日最大行动次数（分段按钮）
            item {
                SegmentedChoice(
                    label = "每日最大自主行动次数",
                    value = budget.maxAutonomousActionsPerDay,
                    options = listOf(5, 10, 20, 50, 100),
                    onSelect = { viewModel.updateMaxAutonomousActions(it) }
                )
            }

            // 每小时最大行动次数
            item {
                SegmentedChoice(
                    label = "每小时最大行动次数",
                    value = budget.maxActionsPerHour,
                    options = listOf(1, 3, 5, 10),
                    onSelect = { viewModel.updateMaxActionsPerHour(it) }
                )
            }

            // 单次最大 Token
            item {
                BudgetSlider(
                    label = "单次行动最大 Token",
                    value = budget.maxTokensPerAction.toFloat(),
                    range = 1_000f..50_000f,
                    steps = 3,
                    onValueChange = { viewModel.updateMaxTokensPerAction(it.toLong()) }
                )
            }

            // 本月统计
            item {
                Divider(Modifier.padding(vertical = 8.dp))
                Text("本月统计", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                UsageRow("Token 已用", "${formatNumber(usage.tokenUsed)} / ${formatNumber(budget.dailyTokenLimit * 30)}")
                UsageRow("费用估算", "$${String.format("%.2f", usage.costUsd)} / $${String.format("%.2f", budget.monthlyCostLimitUsd)}")
                UsageRow("行动次数", "${usage.actionCount} / ${budget.maxAutonomousActionsPerDay * 30}")
                TextButton(onClick = { viewModel.resetMonthlyStats() }) {
                    Text("重置本月统计")
                }
            }
        }
    }
}
```

```kotlin
@HiltViewModel
class AgentBudgetViewModel @Inject constructor(
    private val budgetStore: AgentBudgetStore,
    private val budgetTracker: AgentBudgetTracker
) : ViewModel() {

    val budget: StateFlow<AgentBudget> = budgetStore.budget
        .stateIn(viewModelScope, SharingStarted.Eagerly, AgentBudget())
    val dailyUsage: StateFlow<DailyUsage> = budgetTracker.dailyUsage

    fun updateDailyTokenLimit(value: Long) {
        viewModelScope.launch { budgetStore.updateDailyTokenLimit(value) }
    }
    fun updateMonthlyCostLimit(value: Float) {
        viewModelScope.launch { budgetStore.updateMonthlyCostLimit(value) }
    }
    fun updateMaxAutonomousActions(value: Int) {
        viewModelScope.launch { budgetStore.updateMaxAutonomousActions(value) }
    }
    fun updateMaxTokensPerAction(value: Long) {
        viewModelScope.launch { budgetStore.updateMaxTokensPerAction(value) }
    }
    fun updateMaxActionsPerHour(value: Int) {
        viewModelScope.launch { budgetStore.updateMaxActionsPerHour(value) }
    }
    fun resetMonthlyStats() {
        viewModelScope.launch { budgetTracker.resetMonthlyStats() }
    }
}
```

> 设置页入口路径：`设置 → Agent 自主行为 → 成本控制`，作为 `GeneralSettingsScreen` 的子页面。
> 所有预算数值通过 `PreferencesManager` (DataStore) 持久化，修改后通过 `StateFlow` 实时同步到 `AgentBudgetTracker`，无需重启。

sealed class BudgetDecision {
    data class Approved(val allowedTokens: Long) : BudgetDecision()
    data class Reduced(val maxTokens: Long) : BudgetDecision()
    data class Denied(val reason: String) : BudgetDecision()
}

@Entity(tableName = "autonomous_actions")
data class AutonomousAction(
    @PrimaryKey val id: String,
    val type: ActionType,              // REFLECTION / SUMMARIZE / NOTIFY / AUTOMATION
    val description: String,
    val tokensUsed: Long,
    val costUsd: Float,
    val modelId: String,
    val timestamp: Long
)

data class DailyUsage(
    val tokenUsed: Long = 0,
    val costUsd: Float = 0f,
    val actionCount: Int = 0,
    val hourlyActionCount: Int = 0
)
```

### 3.3 Action Permission — 三级审批模型

```kotlin
/**
 * 自主行动权限级别。
 *
 * 比 SAFE/SENSITIVE 更细粒度，专为自主行为设计。
 * SAFE/SENSITIVE 用于 FC 函数（LLM 调用工具的权限），
 * ActionPermission 用于 Agent 自主发起的行动（Kernel 决策的权限）。
 */
enum class ActionPermission {
    /**
     * AUTO — 自动执行，无需用户确认。
     *
     * 适用于：
     *   - 记忆整理（合并/归档/降权）
     *   - 生成内部摘要（不推送给用户）
     *   - 更新记忆权重
     *   - 检测记忆冲突（仅检测，不自动解决）
     *   - 系统状态检查
     */
    AUTO,

    /**
     * ASK — 需要用户确认后执行。
     *
     * 适用于：
     *   - 主动推送通知
     *   - 创建定期自动化任务
     *   - 修改/删除用户数据
     *   - 发送消息到对话
     *   - 自动解决记忆冲突
     *   - 访问敏感信息（位置、联系人）
     */
    ASK,

    /**
     * FORBIDDEN — 禁止自主执行，仅用户手动触发。
     *
     * 适用于：
     *   - 删除对话历史
     *   - 修改系统设置
     *   - 导出/分享用户数据
     *   - 财务相关操作
     *   - 卸载/禁用模块
     */
    FORBIDDEN
}

/**
 * 自主行动类型 → 权限级别映射。
 */
object ActionPermissionPolicy {
    fun getPermission(actionType: AutonomousActionType): ActionPermission {
        return when (actionType) {
            // AUTO
            AutonomousActionType.MEMORY_CONSOLIDATE  -> ActionPermission.AUTO
            AutonomousActionType.MEMORY_ARCHIVE       -> ActionPermission.AUTO
            AutonomousActionType.MEMORY_DETECT_CONFLICT -> ActionPermission.AUTO
            AutonomousActionType.GENERATE_INSIGHT     -> ActionPermission.AUTO
            AutonomousActionType.STATE_CHECK          -> ActionPermission.AUTO
            AutonomousActionType.WEIGHT_ADJUST        -> ActionPermission.AUTO

            // ASK
            AutonomousActionType.PUSH_NOTIFICATION    -> ActionPermission.ASK
            AutonomousActionType.CREATE_AUTOMATION    -> ActionPermission.ASK
            AutonomousActionType.MODIFY_USER_DATA     -> ActionPermission.ASK
            AutonomousActionType.SEND_CHAT_MESSAGE    -> ActionPermission.ASK
            AutonomousActionType.RESOLVE_CONFLICT     -> ActionPermission.ASK
            AutonomousActionType.ACCESS_LOCATION      -> ActionPermission.ASK
            AutonomousActionType.ACCESS_CONTACTS      -> ActionPermission.ASK

            // FORBIDDEN
            AutonomousActionType.DELETE_CONVERSATION  -> ActionPermission.FORBIDDEN
            AutonomousActionType.MODIFY_SETTINGS      -> ActionPermission.FORBIDDEN
            AutonomousActionType.EXPORT_USER_DATA     -> ActionPermission.FORBIDDEN
            AutonomousActionType.FINANCIAL_OPERATION  -> ActionPermission.FORBIDDEN
            AutonomousActionType.UNINSTALL_MODULE     -> ActionPermission.FORBIDDEN
        }
    }
}

/**
 * Policy Engine 的决策流程。
 */
class PolicyEngine(
    private val budgetTracker: AgentBudgetTracker,
    private val deviceState: DeviceStateProvider
) : IPolicyEngine {

    /**
     * 判断是否允许一次自主行动。
     *
     * 决策链：
     *   1. AgentMode == OFF？→ 拒绝
     *   2. SMART 模式条件不满足？→ 拒绝
     *   3. ActionPermission == FORBIDDEN？→ 拒绝
     *   4. 超出预算？→ 拒绝
     *   5. ActionPermission == ASK？→ 发出确认请求
     *   6. 全部通过 → 批准
     */
    suspend fun evaluate(action: AutonomousAction): PolicyDecision {
        // Layer 1: Mode check
        if (kernel.mode.value == AgentMode.OFF) {
            return PolicyDecision.Denied("Agent 模式已关闭")
        }

        // Layer 2: SMART conditions
        if (kernel.mode.value == AgentMode.SMART) {
            val conditions = smartConditions.isSatisfied(deviceState.get(), budgetTracker.dailyUsage.value)
            if (!conditions) {
                return PolicyDecision.Deferred("SMART 模式条件不满足")
            }
        }

        // Layer 3: Permission check
        val permission = ActionPermissionPolicy.getPermission(action.type)
        when (permission) {
            ActionPermission.FORBIDDEN ->
                return PolicyDecision.Denied("此操作被禁止自主执行: ${action.type}")
            ActionPermission.ASK ->
                return PolicyDecision.ConfirmationRequired(action)
            ActionPermission.AUTO -> { /* 继续 */ }
        }

        // Layer 4: Budget check
        val budgetDecision = budgetTracker.canAct(action.estimatedTokens)
        when (budgetDecision) {
            is BudgetDecision.Denied ->
                return PolicyDecision.Denied(budgetDecision.reason)
            is BudgetDecision.Reduced ->
                action.maxTokens = budgetDecision.maxTokens
            is BudgetDecision.Approved -> { /* OK */ }
        }

        return PolicyDecision.Approved(action)
    }
}

sealed class PolicyDecision {
    data class Approved(val action: AutonomousAction) : PolicyDecision()
    data class Denied(val reason: String) : PolicyDecision()
    data class Deferred(val reason: String) : PolicyDecision()  // 暂缓，等条件满足再试
    data class ConfirmationRequired(val action: AutonomousAction) : PolicyDecision()
}
```

---

## 四、Planner Engine — 所有"怎么做"的决策

Planner Engine 基本对应 v3.0 的 Automation Engine，但明确归属于 Kernel：

```kotlin
interface IPlannerEngine {
    /** 将自然语言分解为执行计划 */
    suspend fun generatePlan(description: String, context: PlanContext): ExecutionPlan

    /** 执行一个计划（含状态机 + Guard） */
    suspend fun execute(plan: ExecutionPlan): ExecutionResult

    /** 重新规划（基于中间结果调整后续步骤） */
    suspend fun replan(plan: ExecutionPlan, resultsSoFar: List<StepResult>): ExecutionPlan

    /** 合成最终结果 */
    suspend fun synthesize(task: String, results: List<StepResult>): SynthesizeResult
}
```

核心变化：Planner 不再是一个独立的 AutomationModule，而是 Kernel 的一个子系统。AutomationModule 降级为 Capability Layer 中的能力提供者（注册 `create_automation` 等 FC 函数）。

---

## 五、Memory System — 所有"记住什么"的决策

对应 v3.0 的 Memory Evolution 设计，明确归属于 Kernel：

```kotlin
interface IMemorySystem {
    // 基础操作
    suspend fun remember(content: String, type: MemoryType, importance: Float): MemoryEntity
    suspend fun recall(query: String, topK: Int): List<MemoryEntity>
    suspend fun forget(memoryId: String)

    // 演化操作
    suspend fun detectConflicts(): List<MemoryPair>
    suspend fun resolveConflict(pair: MemoryPair, strategy: ConflictResolution)
    suspend fun archiveExpired(): Int
    suspend fun mergeDuplicates(): Int
    suspend fun generateInsights(): List<MemoryEntity>

    // 评分
    fun score(memory: MemoryEntity, queryRelevance: Float): Float
}
```

核心变化：Memory 不再是"数据库 + 检索"，而是 Kernel 的长期知识子系统，有自己的演化周期。

---

## 六、Runtime Manager — 所有"什么时候做"的决策

对应 v3.0 的 AgentRuntime，但增加了电源管理和自适应策略：

```kotlin
interface IRuntimeManager {
    val mode: StateFlow<AgentMode>

    /** 启动/停止自主循环 */
    suspend fun start()
    suspend fun stop()

    /** 一次完整循环 */
    suspend fun runCycle(): CycleResult

    /** 调度下一次唤醒 */
    fun scheduleNextWakeup()

    /** 电源管理：根据电量和充电状态调整频率 */
    fun getPowerProfile(): PowerProfile
}

enum class PowerProfile {
    /** 活跃 — 插电或电量 > 50%，高频循环 */
    ACTIVE,
    /** 平衡 — 电量 20-50%，标准频率 */
    BALANCED,
    /** 省电 — 电量 < 20% 且未充电，最低频率 */
    LOW_POWER,
    /** 休眠 — 电量 < 10% 且未充电，暂停所有自主行为 */
    SLEEP
}
```

### 6.1 自适应循环频率

```kotlin
fun calculateCycleInterval(profile: PowerProfile, mode: AgentMode): Long {
    return when (mode) {
        AgentMode.OFF -> Long.MAX_VALUE  // 不循环
        AgentMode.SMART -> when (profile) {
            PowerProfile.ACTIVE     -> 5 * 60 * 1000L   // 5 分钟
            PowerProfile.BALANCED   -> 15 * 60 * 1000L  // 15 分钟
            PowerProfile.LOW_POWER  -> 30 * 60 * 1000L  // 30 分钟
            PowerProfile.SLEEP      -> Long.MAX_VALUE   // 暂停
        }
        AgentMode.ALWAYS_ON -> when (profile) {
            PowerProfile.ACTIVE     -> 3 * 60 * 1000L   // 3 分钟
            PowerProfile.BALANCED   -> 10 * 60 * 1000L  // 10 分钟
            PowerProfile.LOW_POWER  -> 20 * 60 * 1000L  // 20 分钟
            PowerProfile.SLEEP      -> 60 * 60 * 1000L  // 1 小时（最小保活）
        }
    }
}
```

---

## 七、Kernel 初始化流程

```
ZeroApplication.onCreate()
  │
  ├── 1. ModuleLifecycleManager.initializeAll()
  │     └── 所有 IZeroModule onAwake + onStart
  │
  ├── 2. AgentKernel.start()
  │     ├── policy.initialize()    ← 加载预算数据、权限配置
  │     ├── memory.initialize()    ← 加载 FTS5、初始化 Embedding
  │     ├── planner.initialize()   ← 注册 FC 函数
  │     └── runtime.start()        ← 启动自主循环（如果 Mode != OFF）
  │
  └── 3. 用户看到对话界面
```

### 7.1 OODA 循环（在 Kernel 层统一）

```kotlin
// AgentKernel.runCycle() — 这是整个系统最核心的方法
override suspend fun runCycle(): CycleResult {
    val cycleId = UUID.randomUUID().toString()

    // ═══ Phase 1: Observe ═══
    // 收集环境信息，所有观察结果汇总到 Kernel
    val observations = Observations.collect(
        conversations = conversationRepo.getMessagesSince(runtime.lastCycleTime),
        reminders = systemBus.getPendingReminders(),
        deviceState = DeviceState.current(),
        staleMemories = memory.getStaleMemories(),
        conflictingMemories = memory.getConflictingMemories(),
        budgetStatus = budgetTracker.dailyUsage.value
    )

    if (observations.isEmpty()) {
        return CycleResult.NoOp
    }

    // ═══ Phase 2: Orient ═══
    // 分析观察结果，更新内部认知模型
    val reflection = planner.reflect(observations)
    memory.evolve(reflection.memoryUpdates)

    // ═══ Phase 3: Decide ═══
    // 所有"能不能做"的决策在这里统一
    for (action in reflection.suggestedActions) {
        val decision = policy.evaluate(action)
        when (decision) {
            is PolicyDecision.Approved -> {
                // ═══ Phase 4: Act ═══
                val result = planner.execute(action.toPlan())
                budgetTracker.record(action.toRecord(result))
                emitActionResult(result)
            }
            is PolicyDecision.ConfirmationRequired -> {
                emitConfirmationRequest(decision.action)
            }
            is PolicyDecision.Denied -> {
                logDecision(decision)
            }
            is PolicyDecision.Deferred -> {
                scheduleRetry(decision, action)
            }
        }
    }

    runtime.scheduleNextWakeup()
    return CycleResult.Completed
}
```

---

## 八、与 v3.0 的模块映射

| v3.0 位置 | v4.0 Kernel 位置 | 变化 |
|-----------|-----------------|------|
| `AgentRuntime.decide()` | `PolicyEngine.evaluate()` | 决策统一到 Policy，不再是 Runtime 自己做 |
| `ExecutionGuard.canExecute()` | `PolicyEngine.evaluate()` → 预算层 | Guard 聚焦于步骤级控制，预算聚焦于全局控制 |
| `FunctionRegistry.executeWithSecurity()` | `PolicyEngine.evaluate()` → 权限层 | SAFE/SENSITIVE 用于 FC 调用；AUTO/ASK/FORBIDDEN 用于自主行为 |
| `AgentRuntime.observe()` | `Kernel.runCycle()` → Observe | 观察结果统一汇总到 Kernel |
| `AgentRuntime.reflect()` | `Planner.reflect()` | 反思由 Planner 执行，但触发由 Kernel 控制 |
| `MemoryConflictDetector` | `IMemorySystem.detectConflicts()` | 归属到 Memory System 内部 |
| `MemoryReflectionJob` | `IMemorySystem.evolve()` | 归属到 Memory System 内部 |
| `AutomationModule` (stub) | Capability Layer — FC 提供者 | 降级为能力层，不再做决策 |
| `ChatBotModule` | 不受影响 — 用户触发的对话不经过 Kernel 的 Policy 判断 | 用户主动对话 = 用户的决策，不是 Agent 的决策 |

---

## 九、关键架构决策

| # | 决策 | 结论 |
|---|------|------|
| K1 | **决策集中** | 所有自主行为的决策经过 `PolicyEngine.evaluate()`，不外泄到模块 |
| K2 | **三层模式** | OFF（被动）/ SMART（智能，默认）/ ALWAYS_ON（最大自主） |
| K3 | **预算独立于 ExecutionGuard** | Guard 管执行步骤；Budget 管全局消耗。两者独立运作 |
| K4 | **权限与 SecurityInterceptor 分层** | SAFE/SENSITIVE 管 FC 调用；AUTO/ASK/FORBIDDEN 管自主行为 |
| K5 | **Kernel 是单例，不替代 ModuleLifecycleManager** | ModuleLifecycleManager 继续管理模块生命周期；Kernel 管理 Agent 决策 |
| K6 | **Planner 归属 Kernel，AutomationModule 降级** | AutomationModule 变为 Capability Layer 的能力提供者，不再独立做决策 |
| K7 | **Memory System 归属 Kernel** | 记忆的演化、冲突解决、洞察生成都是 Kernel 的内部行为 |
| K8 | **用户对话永远不经过 Policy** | 用户主动发消息 = 用户在做决策，Agent 只是响应，不需要 Policy 审批 |

---

## 十、最小可行实现（MVP Kernel）

考虑到工程节奏，建议 Kernel 分两步实现：

### Kernel MVP（Phase 3 内实现）

```
AgentKernel MVP:
  ├── PolicyEngine
  │     ├── AgentMode（OFF/SMART/ALWAYS_ON）
  │     ├── AgentBudget（dailyTokenLimit + maxAutonomousActionsPerDay）
  │     └── ActionPermission（AUTO/ASK/FORBIDDEN 基础映射）
  │
  ├── RuntimeManager
  │     ├── PowerProfile（基础电量感知）
  │     └── 自适应循环频率
  │
  └── Core Loop（简化版 OODA）
        └── Observe → Policy.evaluate → Planner.execute 或 Defer
```

### Kernel Full（Phase 5+ 完成）

```
AgentKernel Full:
  ├── 完整的 Policy Engine（月度成本估算 + 用户自定义权限映射）
  ├── Planner Engine（多方案备选 + 替代方案对比）
  ├── Memory System（完整演化周期 + 每日 ReflectionJob）
  ├── Runtime Manager（三级唤醒源 + Doze 适配）
  └── 完整的 OODA 循环（含 Orientation 阶段 LLM 反思）
```

---

## 附录 A：Kernel vs 非 Kernel 场景判断

```
┌─────────────────────────────────────────────────────┐
│  这是用户主动触发的吗？                               │
│    │                                                 │
│    ├── 是 → 不经过 Kernel Policy                     │
│    │       ChatBotModule 直接处理                     │
│    │       FC 调用只检查 SAFE/SENSITIVE               │
│    │       Token 消耗记入用户对话预算（无硬上限）      │
│    │                                                  │
│    └── 否（Agent 自主触发）→ 经过 Kernel Policy       │
│            ├── AgentMode 检查                         │
│            ├── SMART 条件检查                         │
│            ├── ActionPermission 检查                  │
│            ├── AgentBudget 检查                       │
│            └── 全部通过 → Planner 执行                │
│                  任一拒绝 → 拒绝/暂缓/确认             │
                  任一拒绝 → 拒绝/暂缓/确认             │
└─────────────────────────────────────────────────────┘
```

---

## 附录 B：Agent 相关设置页导航

所有 Agent 自主行为相关的设置项入口统一放在 `设置 → Agent 自主行为`：

```
设置
├── 通用设置
│   ├── 主题（浅色/深色/跟随系统）
│   ├── 默认 LLM 模型
│   └── ...
│
├── Agent 自主行为          ← 新增
│   ├── Agent 模式
│   │   └── OFF / SMART（默认）/ ALWAYS_ON
│   │
│   ├── 成本控制             ← 用户自行决定
│   │   ├── 每日 Token 上限（默认 50000）
│   │   ├── 每月费用上限（默认 $5）
│   │   ├── 每日最大行动次数（默认 20）
│   │   ├── 每小时最大行动次数（默认 3）
│   │   ├── 单次行动最大 Token（默认 10000）
│   │   └── 本月统计 + 重置按钮
│   │
│   └── 自主行为权限         ← 用户自行决定
│       ├── 记忆整理: AUTO
│       ├── 生成摘要: AUTO
│       ├── 推送通知: ASK
│       ├── 创建自动化: ASK
│       ├── 修改数据: ASK
│       └── ...（各类行为的 AUTO/ASK/FORBIDDEN）
│
├── 模块管理
├── 角色管理
├── 记忆管理
└── 关于
```

`PreferencesManager` 中对应的 DataStore keys：

```kotlin
// Agent 相关 DataStore keys — 全部由用户控制
object AgentPrefKeys {
    // Mode
    const val AGENT_MODE = "agent_mode"  // "OFF" | "SMART" | "ALWAYS_ON"

    // Budget
    const val BUDGET_DAILY_TOKEN_LIMIT      = "agent_budget_daily_token"
    const val BUDGET_MONTHLY_COST_LIMIT     = "agent_budget_monthly_cost"
    const val BUDGET_MAX_ACTIONS_PER_DAY    = "agent_budget_max_actions_day"
    const val BUDGET_MAX_ACTIONS_PER_HOUR   = "agent_budget_max_actions_hour"
    const val BUDGET_MAX_TOKENS_PER_ACTION  = "agent_budget_max_tokens_action"

    // Permission overrides（用户可覆盖默认的 AUTO/ASK/FORBIDDEN 映射）
    const val PERMISSION_PREFIX = "agent_permission_"  // + actionType.name
}
```
