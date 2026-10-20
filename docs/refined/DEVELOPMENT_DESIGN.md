# Zero V2 完整开发设计方案（v3.1 — 修订版）

> 基于 V1_V2_GAP_ANALYSIS.md + ARCHITECTURE_FINAL.md + MODULE_REFACTORING_PLAN.md + AGENT_KERNEL.md + CODEBASE_AUDIT.md
> v3.0 → v3.1：Accessibility Service 替代前台 Service、用户图谱、Alife 自适应报点、用户主权原则
> v3.5 起 Phase 顺序以 ROADMAP_P5_P8.md 为权威（P6 Realtime / P7 Platform / P8 MCP / P9 多Agent）。
> **文档权威层级**：DEVELOPMENT_DESIGN（架构/决策）> ROADMAP（Phase 顺序与 Step 定义）> IMPLEMENTATION_PLAN（实施细节）。冲突时按此序裁决，禁止从过时章节反推 Phase 结构。
> 2026-08-11

---

## 一、战略定位：从 "AI 聊天工具" 到 "个人 AI 生命体"

### 1.1 当前方案的能力边界

按 v2.0 设计完整实现后：

```
能力层:
  ✅ 多模型智能选择（Capability Selector）
  ✅ 角色人格系统（Character）
  ✅ 对话管理（历史/搜索/Token/附件）
  ✅ Agent 自动化（Plan-Execute，状态机，思维链）
  ✅ Function Calling（工具调用 + 安全分级）
  ✅ 记忆系统（混合检索 + LLM 压缩）
  ✅ 模块化平台（每提供商独立模块）

缺一层:
  ❌ Agent 不是"生命体" — 用户不触发就不运行
  ❌ Memory 不会自我演化 — 旧记忆不会合并/降权/冲突解决
  ❌ 缺少用户图谱（UserGraph）— 无法结构化理解"用户是谁"
  ❌ 没有 MCP 兼容层 — 工具生态与 PC Agent 不互通
  ❌ 没有多 Agent 协作 — 复杂任务无法分解给多个专业 Agent
```

### 1.2 Agent Core 统一架构

```
                      ┌──────────────────────────────────┐
                      │       Agent Runtime               │
                      │   (AccessibilityService 驱动)     │
                      │                                  │
                      │  Alife 自适应报点算法              │
                      │  base=90s, mult=3x, max=4x       │
                      │  用户打断→重置为 0                 │
                      │                                  │
                      │  只做一件事:                       │
                      │  delay(interval) → emit(tick)     │
                      │       ↑              │            │
                      │       │     systemBus.onTick      │
                      │       │         ↓                 │
                      │  用户活跃→重置    各模块自主响应    │
                      └──────────────────────────────────┘
                                      │
        ┌─────────────┬───────────────┼───────────────┬─────────────┐
        │             │               │               │             │
   ┌────▼────┐  ┌─────▼──────┐  ┌─────▼─────┐  ┌─────▼─────┐  ┌───▼────┐
   │ Module  │  │ Automation │  │  Memory   │  │ Character│  │  MCP   │
   │Framework│  │  Engine    │  │  System   │  │  System   │  │ Layer  │
   └─────────┘  └────────────┘  │           │  └───────────┘  └────────┘
                                │ ┌───────┐ │
                                │ │Store   │ │  ← 情景记忆（FTS5+Embedding）
                                │ │Graph   │ │  ← 用户图谱（结构化知识）
                                │ └───────┘ │
                                └───────────┘
```

**Agent Core 的四个子系统**：

| 子系统 | 职责 | 驱动方式 |
|--------|------|---------|
| **Module Framework** | 模块生命周期、FC 注册、工具管理 | ModuleLifecycleManager |
| **Automation Engine** | Plan-Execute 循环、状态机、思维链 | Plan-Execute 引擎 |
| **Memory System** | 情景记忆 + **用户图谱**、演化、冲突解决 | LLM + FTS5 + Embedding |
| **Agent Runtime** | 定时发 tick + 自适应报点（各模块订阅 tick 自主行动） | **AccessibilityService**（默认） |

### 1.3 用户主权原则（v3.1 新增）

> **所有由 Agent 自主做出的定时/频率/策略决策，用户都拥有最终控制权。开发者不替用户做长期选择。**

具体体现在：

| 决策点 | 默认值 | 用户可修改的位置 |
|--------|--------|----------------|
| 自动报点基础间隔 | 90s | 设置 → Agent 自主行为 → 报点参数 |
| 报点间隔倍数 | 3x | 同上 |
| 最大翻倍次数 | 4 | 同上 |
| Memory 冲突检测频率 | 每周 1 次 | 记忆模块详情页 → ConfigField |
| Memory 过期归档间隔 | 90 天 | 同上 |
| LLM 压缩触发阈值 | 100 条/级 | 同上 |
| 每日 Token 预算 | 50000 | 设置 → 成本控制 |
| 每月费用上限 | $5 | 同上 |
| Reflection 开关 | 关闭 | 设置 → Agent 自主行为 |
| Agent 模式 | SMART | 设置 → Agent 自主行为 |

> **核心理念**：`baseInterval=90s` 和 `maxDoublings=4` 是**工程上的合理默认值**，但用户有权改为 30s/2x（高频响应）或 300s/6x（极致省电）。不应由开发者一次性决定。

### 1.4 能力分层实现路径

```
Level 1: 超越普通 ChatGPT App ← Phase 0-2 实现
  ├── 多模型智能选择
  ├── 角色人格系统
  ├── 对话管理（历史/搜索/附件/Token）
  └── 基础 Function Calling

Level 2: 接近 Alife PC 核心能力 ← Phase 3-5 实现
  ├── Agent Automation（Plan-Execute + 状态机）
  ├── 工具生态（WebSearch/Vision/ContentExtractor）
  ├── 记忆系统（FTS5 + Embedding + 演化 + 用户图谱）
  ├── Agent Runtime（AccessibilityService + 自适应报点）
  └── 长期人格（跨会话记忆连续性 + 结构化知识）

Level 3: 超越 Alife 某些方面 ← Phase 6+ 实现
  ├── Android 原生能力（通知/摄像头/位置/传感器/后台）
  ├── MCP 兼容层（工具生态互通）
  ├── 多 Agent 协作（Manager/Worker 模式）
  └── Mobile Agent Runtime（主动场景感知）
```

---

## 二、Agent Runtime：Accessibility Service + 自适应报点

### 2.1 为什么不用前台 Service

| 方案 | 被杀风险 | 常驻通知 | 国产 ROM 兼容 | 唤醒可靠性 |
|------|:---:|:---:|:---:|:---:|
| 前台 Service + WorkManager | 高 | 必须有 | 差（自启动管理拦截） | 低（Doze 限制） |
| **AccessibilityService** | **极低** | **不需要** | **好**（无障碍不拦截） | **高**（系统级唤醒） |

AccessibilityService 被 Android 系统视为无障碍需求，优先级等同于系统服务。国产 ROM 的"自启动管理"和"后台冻结"通常不拦截辅助功能服务。对标 Alife 在 Android 上的后台策略。

### 2.2 AgentRuntimeService 设计

```kotlin
/**
 * Agent Runtime — 基于 AccessibilityService 的自主运行引擎。
 *
 * 对标 Alife 的后台持续运行机制。
 * 使用辅助功能服务而非前台 Service —— 不需要常驻通知，不被系统杀进程。
 *
 * 自适应报点算法（对标 Alife）:
 *   下次间隔 = (基础间隔 + 随机偏移) × 间隔倍数^min(连续触发次数, 最大翻倍次数)
 *   收到打断型消息后连续触发次数重置为 0
 *
 * 默认参数（用户可在模块详情页修改）:
 *   baseIntervalSec = 90, randomOffsetSec = 30
 *   intervalMultiplier = 3, maxDoublings = 4
 */
class AgentRuntimeService : AccessibilityService() {

    @Inject lateinit var agentRuntime: AgentRuntime

    override fun onServiceConnected() {
        // 辅助功能服务创建成功 → 启动 tick 循环
        // 注意：此时 ModuleLifecycleManager.initializeAll() 可能尚未完成。
        // 但 SystemBus/ChatBus 是 Hilt 单例（构造时已存在），emit/collect 在首个 tick 前安全。
        // 首个 tick 的订阅者（SystemEventModule/MemoryModule）如尚未 onStart()，会错过首个 tick，
        // 但后续 tick 会正常送达——首个间隔最长 120s，错过一次不影响。
        agentRuntime.startLoop()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 感知窗口变化 → 检测用户活跃 → 重置报点间隔
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            agentRuntime.onUserActivity()
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        agentRuntime.stopLoop()
        super.onDestroy()
    }
}

/**
 * Agent Runtime 核心引擎。
 *
 * 职责极简：只做定时发 tick + 管理报点间隔。
 * 不做 Memory 检查、不做 Graph 提取触发、不做 Reflection 决策。
 * 这些是各模块自己的事——模块订阅 systemBus.onTick，自己决定做什么。
 *
 * 实现 IZeroModule —— 报点参数通过 @ConfigField 暴露在模块详情页。
 * 但 tick 循环不由 onStart() 启动，而是等待 AccessibilityService 连接后调用 start()。
 */
@Singleton
class AgentRuntime @Inject constructor(
    private val systemBus: SystemBus,
    private val chatBus: ChatBus,
    private val logManager: LogManager
) : IZeroModule {

    override val metadata = ModuleMetadata(
        id = "agent_runtime",
        displayName = "Agent 运行时",
        description = "自适应报点引擎，驱动所有模块的定时行为",
        category = ModuleCategory.CORE,
        launchOrder = -180  // 在 ChatBotModule(-200) 之后、其他模块之前
    )

    private val _lifecycle = MutableStateFlow(ModuleLifecycle.UNINITIALIZED)
    override val lifecycle = _lifecycle.asStateFlow()
    override var isEnabled: Boolean = true
    override val requiredPermissions: List<String> = emptyList()

    // ═══ Alife 自适应报点参数（命名类，确保 ModuleConfigParser 反射可发现） ═══
    override val config = AgentRuntimeConfig()

    class AgentRuntimeConfig : ModuleConfigBase() {
        @ConfigField("基础间隔(秒)", NUMBER, min = 30.0, max = 600.0)
        var baseIntervalSec: Int = 90

        @ConfigField("随机偏移范围(秒)", NUMBER, min = 5.0, max = 120.0)
        var randomOffsetSec: Int = 30

        @ConfigField("间隔倍数", NUMBER, min = 1.5, max = 5.0)
        var intervalMultiplier: Double = 3.0

        @ConfigField("最大翻倍次数", NUMBER, min = 1.0, max = 10.0)
        var maxDoublings: Int = 4

        override fun toJson(): String = """{"baseIntervalSec":$baseIntervalSec,"randomOffsetSec":$randomOffsetSec,"intervalMultiplier":$intervalMultiplier,"maxDoublings":$maxDoublings}"""
        override fun fromJson(json: String) {
            try { val o = JSONObject(json); baseIntervalSec = o.optInt("baseIntervalSec", 90); randomOffsetSec = o.optInt("randomOffsetSec", 30); intervalMultiplier = o.optDouble("intervalMultiplier", 3.0); maxDoublings = o.optInt("maxDoublings", 4) } catch (_: Exception) {}
        }
        override fun defaultConfig() = AgentRuntimeConfig()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var continuousTimerCount = 0
    private var isRunning = false

    // Agent 自身状态（§2.5）
    private val _agentState = MutableStateFlow(AgentState.IDLE)
    val agentState: StateFlow<AgentState> = _agentState.asStateFlow()

    override suspend fun onAwake() { _lifecycle.value = ModuleLifecycle.AWAKE }
    override suspend fun onStart() {
        // 不在这里启动 tick 循环。等待 AccessibilityService 连接后调用 startLoop()。
        _lifecycle.value = ModuleLifecycle.STARTED
    }
    override suspend fun onDestroy() { stopLoop(); scope.cancel(); _lifecycle.value = ModuleLifecycle.DESTROYED }
    override suspend fun onConfigChanged(newConfig: ModuleConfigBase) { /* 参数热更新，无需重启 */ }
    override suspend fun registerFunctions() {}
    override suspend fun checkAvailability(): Boolean = true
    override fun markDisabled() { stopLoop(); _lifecycle.value = ModuleLifecycle.DISABLED }

    /** AccessibilityService 连接后调用 */
    fun startLoop() {
        if (isRunning) return
        isRunning = true
        scope.launch {
            chatBus.onChatSent.collect { continuousTimerCount = 0 }
        }
        scope.launch {
            while (isRunning) {
                try {
                    _agentState.value = AgentState.OBSERVING
                    systemBus.onTick.emit(System.currentTimeMillis())
                } catch (e: Exception) {
                    logManager.error("AgentRuntime", "Tick emit failed", e)
                }
                delay(calculateNextInterval())
                continuousTimerCount++
                _agentState.value = AgentState.IDLE
            }
        }
    }

    fun stopLoop() { isRunning = false }

    /** AccessibilityService 感知窗口变化 → 重置 */
    fun onUserActivity() { continuousTimerCount = 0 }

    fun calculateNextInterval(): Long {
        val cfg = config as AgentRuntimeConfig
        val base = cfg.baseIntervalSec +
            Random.nextInt(-cfg.randomOffsetSec, cfg.randomOffsetSec + 1)
        val safeBase = maxOf(base, 10)
        val doubled = minOf(continuousTimerCount, cfg.maxDoublings)
        val multiplier = Math.pow(cfg.intervalMultiplier, doubled.toDouble()).toLong()
        return maxOf(safeBase * multiplier * 1000L, 10_000L)
    }
}
```

### 2.3 自适应间隔效果（默认参数 90s/±30s/3x/4x）

```
用户活跃期（连续触发=0）: ~60-120s   → Agent 高频响应，像真人在旁边
轻闲期（连续触发=1）:    ~180-360s  → 3-6 分钟，轻度降频
安静期（连续触发=2）:    ~540-1080s → 9-18 分钟
深度休眠（连续触发=3）:  ~1620-3240s→ 27-54 分钟
最大间隔（连续触发≥4）:  ~4860-9720s→ 1.35-2.7 小时

收到用户消息 → continuousTimerCount 重置为 0 → 立刻回到 60-120s
```

所有参数通过 `@ConfigField` 暴露在模块详情页，用户可调。

### 2.4 Scheduler — tick 到任务的 thin dispatch 层

随着模块增加，每个模块在 `onTick` 里自己做 `if (shouldRun())` 会导致轮询碎片化。在 AgentRuntime 和各模块之间增加一个薄薄的 Scheduler 层：

```
AgentRuntime.tick
      │
      ▼
  Scheduler                    ← 唯一订阅 systemBus.onTick
      │
      ├── MemoryReflectionJob   (nextRun: 每天 02:00)
      ├── GraphExtractionJob    (nextRun: 每 N 条新记忆)
      ├── MemoryArchiveJob      (nextRun: 每周日 03:00)
      ├── ReminderJob           (nextRun: SystemEventModule 计算)
      ├── AutomationJob          (nextRun: WorkManager 调度)
      └── [未来] EvaluationJob, MCPHealthCheck...
```

```kotlin
interface AgentJob {
    val id: String
    val nextRunTime: Long       // 下次执行时间 (epoch ms)，Long.MAX_VALUE = 暂停
    suspend fun execute()       // 执行后自己更新 nextRunTime
}

@Singleton
class Scheduler @Inject constructor(
    private val jobs: Set<@JvmSuppressWildcards AgentJob>  // Hilt @IntoSet 收集
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start(systemBus: SystemBus) {
        scope.launch {
            systemBus.onTick.collect { now ->
                for (job in jobs) {
                    if (job.nextRunTime <= now) {
                        try { job.execute() } catch (e: Exception) { /* 记录，不阻塞其他 job */ }
                    }
                }
            }
        }
    }
}
```

Scheduler 不替代 SystemEventModule 的 PriorityQueue——SystemEventModule 继续管理提醒/报点/自动化调度，但改为实现 `AgentJob` 接口，由 Scheduler 统一触发。

### 2.5 AgentState — Agent 自身的状态模型

AutomationStateMachine 管理的是**自动化任务**的状态。Agent 自身还需要一个独立的状态模型，用于 UI 展示和日志：

```kotlin
enum class AgentState {
    IDLE,        // 无任务，等待下一次 tick
    OBSERVING,   // 扫描环境（新对话、通知、系统状态）
    EXECUTING,   // 执行自主行动中
    REFLECTING,  // LLM Reflection 中
    WAITING      // 等待用户确认（CONFIRM 权限的自主行为）
}

// AgentRuntime 中暴露
private val _agentState = MutableStateFlow(AgentState.IDLE)
val agentState: StateFlow<AgentState> = _agentState.asStateFlow()
```

UI 顶栏可用：`"Zero · 正在整理记忆"`（REFLECTING）；AgentTrace 事件中记录状态变更。

### 2.6 模块如何响应 tick（去中心化）

AgentRuntime 只发 tick。Scheduler 统一分发到各 `AgentJob`。各模块不再直接订阅 `systemBus.onTick`：

```
AccessibilityService
      │
AgentRuntime  (节奏控制)
      │
  systemBus.onTick (唯一信号源)
      │
  Scheduler     (任务调度)
      │
  ┌───┼───┬──────────┬──────────┐
  │   │   │          │          │
 Reminder MemoryJob AutoJob [未来Job]
```

**关键变化**：SystemEventModule 移除自己的内驱心跳。`onTick` 只有一个触发源。`AgentJob` 接口确保每个任务声明自己的 `nextRunTime`，模块不需要在 `onTick` 里做 `if (shouldRun())` 判断。

### 2.7 与 AccessibilityService 的配合

```
AccessibilityService.onAccessibilityEvent(TYPE_WINDOW_STATE_CHANGED)
  → agentRuntime.onUserActivity()
  → 报点重置 → 下次 tick 回到 60-120s 内

ChatBus.onChatSent（AgentRuntime 内部订阅）
  → continuousTimerCount = 0
  → 报点重置 → 用户正在聊天，Agent 高频待命

Scheduler 分发 tick → AgentJob 各自执行
  → MemoryReflectionJob 检查是否到了凌晨 2:00
  → GraphExtractionJob 检查是否累积够了新记忆
  → ReminderJob 检查是否有到期提醒
```

---

## 三、Memory System：情景记忆 + 用户图谱

> **⚠️ v3.2 修订（2026-08-20，D31/D32）**：本章两处经实测证实的缺陷已修订——
> ① **检索地基**（§3.7 评分公式的 `queryRelevance` 与 Phase 4 混合检索的数据源）：FTS5 对中文无效（unicode61 不分词），主径改为 **Embedding 余弦 + bigram 降级 + Tier 2 兜底**（详见决策记录 D31）；
> ② **源头治理范围**（§3.1）：原设计只治理用户消息侧，AI 回复的易逝内容（天气播报/搜索摘要）同样必须 T0 过滤，长期知识经 Reflection 提炼入库（详见 D32）。
> Phase 4 实施顺序相应调整：检索地基定案为 Step 4.6 的前置条件。

### 3.1 记忆分层（Tier System）— 源头治理

**问题**：所有对话无差别进入 Memory。"今天中午吃面"和"用户是软件工程师"在同一池子里，几年后必然污染检索。当前 L0-L8 压缩是事后补救，分层是源头治理。

**方案**：写入记忆时先分类，不同 tier 不同存储策略：

```
Memory Extraction（intelligentMemorize → shouldMemorize 启发式规则）
      │
      ▼
Memory Classifier（规则驱动，不调用 LLM）
      │
      ├── Tier 0: 临时上下文    → 不进 Memory Store，仅在当前会话有效
      │     例: "今天午饭吃面"、"帮我查一下天气"
      │     生命周期: 当前对话结束即丢弃
      │
      ├── Tier 1: 事件记忆      → 进入 Memory Store，定期重新评估
      │     例: "上周参加了面试"、"昨天看了一部电影"
      │     生命周期: 月级，过期后评估是否升级/降级
      │
      ├── Tier 2: 长期事实      → 进入 Memory Store，高权重，参与所有检索
      │     例: "用户是软件工程师"、"用户喜欢科幻小说"、"用户住在上海"
      │     生命周期: 永久，但会被新事实覆盖（旧版本归档）
      │
      └── Tier 3: 用户图谱      → 进入 UserGraph，结构化存储
            例: User.occupation, User.skills, Person.张三.relation
            生命周期: 永久，属性带时间区间
```

**分类规则（纯规则，零 LLM 成本）**：

```kotlin
fun classifyTier(content: String, conversationContext: List<String>): MemoryTier {
    // T2 关键词: 身份/偏好/技能/关系声明
    val t2Patterns = listOf(
        "我是", "我做", "我的职业", "我住在", "我在.*工作",
        "我喜欢", "我不喜欢", "我讨厌", "我热爱",
        "我会", "我擅长", "我学过", "我的.*是"
    )
    if (t2Patterns.any { Regex(it).containsMatchIn(content) }) return Tier2

    // T0: 临时/一次性
    val t0Patterns = listOf(
        "帮我查", "搜索", "今天天气", "几点了", "现在.*时间",
        "午饭", "晚饭", "早餐", "吃什么"
    )
    if (t0Patterns.any { Regex(it).containsMatchIn(content) }) return Tier0
    if (content.length < 20 && !content.containsAny("决定", "计划", "以后", "以前")) return Tier0

    // 默认 T1
    return Tier1
}
```

> LLM 分类作为可选的增强（用户可开启），但默认规则已覆盖 ~80% 场景。

### 3.2 两类记忆的分工

```
Memory Store（情景记忆 — Tier 1 + Tier 2）:    User Graph（语义知识 — Tier 3）:
─────────────────────────────────────────     ────────────────────────────────
T2: "用户是软件工程师"                          User.occupation = "软件工程师"
T2: "用户喜欢科幻小说"                          User.preferences = {书: 科幻}
T2: "用户住在上海"                              User.location = "上海" (current)
T1: "用户上周面试了字节跳动"                     User.location_history:
T1: "用户昨天说想买一台 MacBook Pro"             [北京(2020-2023), 上海(2023-现在)]
                                                Person.张三 = {关系: 同事}

Memory 回答: "用户说过什么"                     Graph 回答: "用户是谁"
```

只靠 Memory 无法回答"用户现在住在哪个城市？"——因为"上海"和"北京"都在记忆里，没有 current/history 的版本化标记。UserGraph 填补了这个空白。

### 3.2 UserGraph 核心实体

```kotlin
enum class EntityType {
    PERSON,        // 人
    PLACE,         // 地点
    ORGANIZATION,  // 组织
    SKILL,         // 技能
    INTEREST,      // 兴趣
    PROJECT,       // 项目
    EVENT,         // 事件
    CONCEPT        // 抽象概念
}

/**
 * 属性 — 带时间区间的版本化知识。
 * effectiveFrom/effectiveTo 天然支持"2020-2023 住北京，2023 至今住上海"。
 * 不需要冲突解决——两条属性自然共存，新版覆盖旧版。
 */
data class UserGraphAttribute(
    val entityId: String,
    val key: String,              // "location", "occupation", "skill.Python.level"
    val value: String,
    val source: AttributeSource,  // USER_STATED / INFERRED / EXTRACTED
    val confidence: Float,        // 0.0-1.0
    val effectiveFrom: Long,
    val effectiveTo: Long?,       // null = 当前仍有效
    val evidence: String?,        // 来源对话/记忆 ID
    val createdAt: Long
)

enum class AttributeSource {
    USER_STATED,   // 用户明确说的（confidence 高）
    INFERRED,      // 从对话推断的（confidence 中）
    EXTRACTED      // 从 Memory 提取的（confidence 中低）
}

/**
 * 关系 — 实体之间的边。
 */
data class UserGraphRelationship(
    val fromEntityId: String,
    val toEntityId: String,
    val type: String,             // "colleague", "family", "friend", "owns", "works_at"
    val metadata: Map<String, String>?,
    val effectiveFrom: Long,
    val effectiveTo: Long?
)
```

### 3.3 Graph 从 Memory 的提取路径（由 MemoryModule 自己触发）

```
MemoryModule 内部:
  private val newMemoryCounter = AtomicInteger(0)
  private var lastExtractionTime: Long = 0

  // 每次写入记忆后递增
  fun onMemoryWritten() {
      newMemoryCounter.incrementAndGet()
  }

  // 订阅 systemBus.onTick → 收到 tick 后自己检查
  scope.launch {
      systemBus.onTick.collect {
          val count = newMemoryCounter.get()
          if (count >= graphExtractionThreshold) {
              triggerGraphExtraction()
              newMemoryCounter.set(0)
              lastExtractionTime = System.currentTimeMillis()
          }
          // 检查是否需要冲突检测
          if (shouldRunConflictDetection()) {
              triggerConflictDetection()
          }
          // 检查是否需要过期归档
          if (shouldRunArchive()) {
              triggerArchive()
          }
      }
  }
```

LLM 提取过程：
```
  → ModelSelector.select(CHAT)  ← MemoryModule 注入 ModelSelector（Phase 4 新增依赖）
  → LLM 提取: "从以下最近记忆中提取实体/属性/关系..."
  → Graph.upsertAttribute("user", "skill.Rust.level", "beginner", INFERRED)
  → Graph.upsertAttribute("user", "skill.Rust.practice", "3h/week", INFERRED)
  → Graph.addRelationship("user", "person.张老师", "studies_with")
```

成本：DSv4 Flash 每次提取 ~2000 tokens，~$0.0006。每 50 条新记忆触发一次。
   典型用户月增 200 条记忆 = 4 次提取 = ~$0.0024/月，可忽略。

### 3.4 MemoryModule ConfigField（用户可在模块详情页调整）

```kotlin
class MemoryModuleConfig : ModuleConfigBase() {
    @ConfigField("图谱提取触发阈值", NUMBER, min = 10.0, max = 500.0,
        description = "累积多少条新记忆后触发一次用户图谱提取")
    var graphExtractionThreshold: Int = 50

    @ConfigField("图谱提取模型", DROPDOWN, options = ["deepseek-chat", "deepseek-reasoner", "qwen-turbo"])
    var graphExtractionModel: String = "deepseek-chat"

    @ConfigField("冲突检测频率", DROPDOWN, options = ["每天", "每周", "每月", "手动"],
        description = "多频繁检查记忆矛盾")
    var conflictDetectionFrequency: String = "每周"

    @ConfigField("过期归档间隔(天)", NUMBER, min = 7.0, max = 365.0,
        description = "多少天未访问的记忆视为过期")
    var archiveAfterDays: Int = 90

    @ConfigField("LLM 压缩触发阈值", NUMBER, min = 20.0, max = 500.0,
        description = "一个等级积累多少条记忆后触发 LLM 压缩")
    var compressionThreshold: Int = 100

    @ConfigField("每日 Reflection 开关", TOGGLE,
        description = "开启后 Agent 每天凌晨会反思最近的对话并生成洞察")
    var enableReflection: Boolean = false

    @ConfigField("Reflection 使用模型", DROPDOWN, options = ["deepseek-chat", "deepseek-reasoner"])
    var reflectionModel: String = "deepseek-chat"

    override fun toJson(): String = """{"graphExtractionThreshold":$graphExtractionThreshold,"graphExtractionModel":"$graphExtractionModel","conflictDetectionFrequency":"$conflictDetectionFrequency","archiveAfterDays":$archiveAfterDays,"compressionThreshold":$compressionThreshold,"enableReflection":$enableReflection,"reflectionModel":"$reflectionModel"}"""
    override fun fromJson(json: String) {
        try { val o = JSONObject(json); graphExtractionThreshold = o.optInt("graphExtractionThreshold", 50); graphExtractionModel = o.optString("graphExtractionModel", "deepseek-chat"); conflictDetectionFrequency = o.optString("conflictDetectionFrequency", "每周"); archiveAfterDays = o.optInt("archiveAfterDays", 90); compressionThreshold = o.optInt("compressionThreshold", 100); enableReflection = o.optBoolean("enableReflection", false); reflectionModel = o.optString("reflectionModel", "deepseek-chat") } catch (_: Exception) {}
    }
    override fun defaultConfig() = MemoryModuleConfig()
}
```

**所有参数用户可调。** 默认值只是工程上的合理起点。

### 3.5 Memory 冲突检测与解决

```kotlin
class MemoryConflictDetector @Inject constructor(
    private val memoryDao: MemoryDao,
    private val embeddingProvider: EmbeddingProvider,
    private val llmClient: ModelSelector  // 通过 ModelSelector 获取推理模型
) {
    suspend fun detectConflicts(): List<MemoryPair> {
        val facts = memoryDao.getByType(MemoryType.FACT, status = MemoryStatus.ACTIVE)
        val conflicts = mutableListOf<MemoryPair>()
        for (fact in facts) {
            val similars = memoryDao.findSimilar(fact.id, minSimilarity = 0.8f, limit = 3)
            for (similar in similars) {
                val isConflict = checkConflictWithLLM(fact, similar)
                if (isConflict) conflicts.add(MemoryPair(fact, similar))
            }
        }
        return conflicts
    }

    private suspend fun checkConflictWithLLM(a: MemoryEntity, b: MemoryEntity): Boolean {
        val model = llmClient.select(setOf(ModelCapability.CHAT)) ?: return false
        val prompt = """
判断两条记忆是否矛盾: A:"${a.content}" B:"${b.content}"
返回 JSON: {"judgment": "CONTRADICTION|OUTDATED|COMPATIBLE|DUPLICATE"}
        """.trimIndent()
        val response = model.chatSync(prompt)
        val judgment = JsonExtractor.extract(response) ?: return false
        return judgment.contains("CONTRADICTION") || judgment.contains("OUTDATED")
    }
}

enum class ConflictResolution { KEEP_NEWER, KEEP_HIGHER_CONFIDENCE, MERGE, ASK_USER, KEEP_BOTH_DOWNGRADE }
```

### 3.6 MemoryReflectionJob — 深度记忆整理

```kotlin
class MemoryReflectionJob @Inject constructor(
    private val memorySystem: MemorySystem,
    private val conflictDetector: MemoryConflictDetector,
    private val logManager: LogManager
) {
    suspend fun executeDeepReflection() {
        // Step 1: 检测冲突 → 自动解决可自动解决的
        val conflicts = conflictDetector.detectConflicts()
        conflicts.filter { it.canAutoResolve() }.forEach {
            memorySystem.resolveConflict(it, ConflictResolution.KEEP_NEWER)
        }
        // Step 2: 归档过期记忆
        memorySystem.archiveExpired()
        // Step 3: 合并重复
        memorySystem.mergeDuplicates()
    }
}
```

### 3.7 记忆权重动态调整

```kotlin
class MemoryScoringEngine {
    fun score(memory: MemoryEntity, queryRelevance: Float, now: Long = System.currentTimeMillis()): Float {
        val typeWeight = when (memory.type) {
            MemoryType.FACT -> 0.8f; MemoryType.PREFERENCE -> 1.0f
            MemoryType.EVENT -> 0.6f; MemoryType.RELATIONSHIP -> 0.9f
            MemoryType.SKILL -> 0.85f; MemoryType.TASK -> 0.7f; MemoryType.INSIGHT -> 0.75f
        }
        return queryRelevance.coerceIn(0f, 1f) * typeWeight *
            timeDecay(memory, now) * memory.importance.coerceIn(0f, 1f) *
            memory.confidence.coerceIn(0f, 1f) * reinforcementBonus(memory.reinforcementCount)
    }
    private fun timeDecay(memory: MemoryEntity, now: Long): Float {
        val halfLifeDays = when (memory.type) { MemoryType.EVENT -> 7f; MemoryType.TASK -> 14f; else -> 90f }
        val daysSinceAccess = (now - memory.lastUsedAt) / (24 * 3600_000f)
        return exp(-daysSinceAccess * ln(2f) / halfLifeDays)
    }
    private fun reinforcementBonus(count: Int): Float = minOf(1f + count * 0.1f, 2.0f)
}
```

> Graph 中的属性也应影响 Memory 检索权重——如果 Graph 显示用户当前住在上海，搜索"用户住在哪"时，"上海"相关的 Memory 加权。

---

## 四、统一的权限模型

### 4.1 问题

v3.0 有两套权限系统并行：

```
SAFE/SENSITIVE（FC 调用）        AUTO/ASK/FORBIDDEN（自主行为）
─────────────────────────        ────────────────────────────
memory_forget: SENSITIVE         MEMORY_ARCHIVE: AUTO
web_search: SAFE                 深度搜索: ASK
```

当 AgentRuntime 发出 tick → MemoryModule 自主归档 → 调用 `memory_forget` FC → SENSITIVE 拦截弹窗。用户视角："设置里开了自动整理，为什么还问我？"

### 4.2 合并方案

一个 FC 函数只声明一次权限，不论调用来源走同一套检查。

**Phase 0-2 简化**：仅使用 `AUTO` / `CONFIRM` 两级（统一命名，不再使用 `USER_ONLY`）。`FORBIDDEN` 保留枚举定义，Phase 3+ 自动化激活后再启用。`ToolDefinition.permission` 字段（详见 §5.1）统一承载。

---

## 五、Tool Manifest 与 MCP 兼容层

（ToolDefinition 使用统一的 `permission: ActionPermission` 字段。）

### 5.1 Tool Manifest 设计

```kotlin
/**
 * 统一的行动权限。替代 SAFE/SENSITIVE 和 AUTO/ASK/FORBIDDEN 双轨。
 */
enum class ActionPermission {
    AUTO,       // 始终自动放行，不弹窗
    CONFIRM,    // 需要用户确认
    FORBIDDEN   // 禁止自主执行，仅用户手动触发
}

data class ToolDefinition(
    val name: String,
    val displayName: String,
    val description: String,
    val moduleId: String,
    val category: ToolCategory,
    val parameters: JsonObject,
    val permission: ActionPermission,     // 统一权限（§4）
    val supportsParallel: Boolean = true,
    val dependsOn: List<String> = emptyList(),
    val estimatedLatencyMs: Long = 500,
    val estimatedTokens: Int = 100,
    val version: Int = 1
)
```

| FC 函数 | 权限 | 说明 |
|---------|:---:|------|
| web_search | AUTO | 只读，始终放行 |
| vision_analyze | AUTO | 只读 |
| memory_recall | AUTO | 只读 |
| read_notepad | AUTO | 只读 |
| list_reminders | AUTO | 只读 |
| get_screen_time | AUTO | 只读 |
| memory_remember | CONFIRM | 写入记忆 |
| write_notepad | CONFIRM | 修改记事本 |
| push_notification | CONFIRM | 推送通知 |
| set_reminder | CONFIRM | 创建提醒 |
| memory_forget | CONFIRM | 自主归档时走 MemoryConflictDetector，不单独弹窗 |
| create_automation | CONFIRM | 创建自动化 |
| clear_notepad | FORBIDDEN | 仅用户手动触发 |
| delete_conversation | FORBIDDEN | 仅用户手动触发 |

```kotlin
@Singleton
class ToolRegistry @Inject constructor() {
    private val tools = ConcurrentHashMap<String, ToolDefinition>()
    private val handlers = ConcurrentHashMap<String, suspend (Map<String, Any?>) -> FunctionResult>()

    fun register(def: ToolDefinition, handler: suspend (Map<String, Any?>) -> FunctionResult) {
        tools[def.name] = def
        handlers[def.name] = handler
    }

    fun getDefinition(name: String): ToolDefinition? = tools[name]
    fun getAllDefinitions(): List<ToolDefinition> = tools.values.toList()
}
```

### 5.2 MCP 兼容层

（保留 v3.0 的 MCPClientModule + MCPToolAdapter 设计，MCP 工具适配后使用统一 ActionPermission。）

---

## 六、多 Agent 架构（接口预留）

（保留 v3.0 设计，无变更）

---

## 七、修订后的完整 Phase 计划

### 总览

```
Phase 0: MVP 产品闭环           ← 让 App 先能用
Phase 1: 模块框架               ← LLM 拆分 + Capability + Secret 分离 + AccessibilityService 骨架
Phase 2: Function Calling       ← FC Loop + 思考渲染 + 附件入口 + 角色集成 + Tool Manifest + Memory 分层 + 缓存优化
Phase 3: 自动化 + Agent Runtime  ← Plan-Execute + 状态机 + 自适应报点 + AccessibilityService
Phase 4: Memory Evolution       ← 情景记忆 + UserGraph + 冲突解决 + ReflectionJob
Phase 5: 设置完善 & 打磨        ← ConfigMigration + 权限 + MCP 预留 + 所有模块 ConfigField 用户化
Phase 6: MCP 生态 & 多 Agent    ← MCP Client + AgentOrchestrator（未来）
```

### Phase 0: MVP 产品闭环（1 周）

| 任务 | 内容 |
|------|------|
| 数据层 | ConversationEntity/DAO, MessageEntity/DAO, CharacterEntity/DAO |
| ChatViewModel | 发送/接收/流式渲染/历史加载/角色切换 |
| ChatScreen（重写） | V1 风格气泡 + 输入栏 + 附件按钮 + 长按复制 |
| ConversationHistoryDialog | 历史对话列表 + 切换 |
| SettingsScreen（骨架） | API Key 配置 + 主题 + 关于 |
| CharacterManageScreen（骨架） | 预设角色 + 新建/编辑 |
| LLMClient（临时） | 直连 DashScope/DeepSeek HTTP |
| PreferencesManager | DataStore 偏好 |

### Phase 1: 模块框架（1.5 周）

| 任务 | 内容 |
|------|------|
| ILanguageModel + capabilities | 接口 + ModelCapability 枚举 |
| ModelSelector | Capability 匹配 + Jaccard 排序 |
| IModuleUI | 模块子页面 Composable 接口 |
| ConfigMigration 接口 | fromVersion/toVersion/migrate() |
| 删除集中式 LLM | LLMClientModule, ModelRouter, Adapters |
| DeepSeekChatModule + Config | apiKeyRef → SecureKeyStore |
| QwenChatModule + Config | 同上 |
| **AgentRuntimeService（骨架）** | **AccessibilityService 注册 + Manifest 声明** |
| ModuleDetailScreen（改） | AutoForm + 子功能 Tab + Secret 字段 + **所有 ConfigField 直接反映为用户可控参数** |
| ModuleCenterScreen（迁） | 从 Tab 迁入设置子页面 |
| SecureKeyStore（增强） | apiKeyRef 映射 |

### Phase 2: FC + 思考渲染 + 附件入口 + 角色集成 + Memory + 缓存优化（3 周）

| 任务 | 内容 |
|------|------|
| **ChatViewModel FC 接入** | `emptyList<FunctionDefinition>()` → `functionRegistry.getAllDefinitions()` |
| **ChatBotModule FC 循环** | ToolCall → executeBatch → continueAfterToolCall 完整闭环 |
| **🆕 附件入口 + 图片选择** | ChatScreen 输入栏 📎 按钮 → 系统图片选择器 → `ChatMessage.attachments`；新建 `Attachment` 数据模型。**仅在 FC Loop 完成后有意义** |
| **🆕 思考过程流式渲染 + 强度控制** | **ChatBotModule**: `applyStreamChunk()` 处理 `thinkingDelta` → 增量累积 `ChatMessage.thinking`。**DeepSeek**: `thinking.type` 开关 + `reasoning_effort = none/low/high/max` 强度分级（DROPDOWN）。**Qwen**: `enable_thinking` 开关 + `thinking_budget` Token 预算（SLIDER, 256-4096, 默认 1024）。**UI**: `MessageBubble` 上方渲染可折叠思考卡片 "🤔 思考过程 [展开/收起]"，默认折叠，流式时实时展开 |
| **🆕 角色系统集成** | CharacterEntity 加 `isActive`；CharacterManageScreen 加「设为当前」RadioButton；ChatViewModel 读取活跃角色 systemPrompt 替代硬编码；TopAppBar 显示当前角色名。**角色在设置中固定选择，对话内不切换，以保障 systemPrompt 全局一致** |
| **🆕 Prompt Cache 优化设计** | 固定角色 → systemPrompt 永不变化；消息只追加不修改 → 前缀完全稳定；DeepSeek/Qwen API 自动缓存前缀；AgentTrace 记录 `cachedTokens` 实现可观测。**目标：对标 Alife 95%+ 缓存命中率** |
| ToolRegistry | 统一 ToolDefinition + ActionPermission 注册中心 |
| FunctionRegistryModule（完善） | FC 执行 + 并行 + SecurityInterceptor |
| QwenWebSearchModule + Config | 独立搜索模块（已有，按 ToolDefinition 重构注册） |
| QwenVisionModule + Config | 独立视觉模块（同上） |
| ContentExtractor | Readability 提纯 + 降级管道 |
| NotificationModule（实现） | 从 stub 改为真实 NotificationManager 调用 |
| UsageStatsModule（实现） | 从 stub 改为真实 UsageStatsManager 调用 |
| **Memory Tier 分类器** | **T0-T3 规则驱动分层（§3.1），`classifyTier()` 纯规则，零 LLM 成本。T0 不进入 Memory Store。集成到 intelligentMemorize 写入路径** |
| **MemoryEntity 增加 tier 字段** | **Room 实体新增 `tier: MemoryTier` 列** |

> **附件/Vision 依赖说明**：附件入口是 Vision 模块的前置条件（无图片输入 → vision_analyze 无法触发）；FC Loop 是附件入口的前置条件（无 FC 回路 → LLM 返回的 tool_calls 无法执行 → 无法端到端验证）。Web/Vision 模块代码已完整但未经强度测试，将在 Phase 2 集成验证（Step 2.6）中完成首次闭环测试。

> **角色集成 + Prompt Cache 优化设计（D26/D27）**：
>
> **缓存原理**：DeepSeek/Qwen API 自动检测请求前缀与历史请求的匹配度。匹配的前缀部分（systemPrompt + 早期消息）按 `prompt_cache_hit_tokens` 计费（约原价 10%），未匹配的尾部按全价计费。
>
> **最大化命中率的设计约束**：
> 1. **角色固定**：角色在设置中选择，systemPrompt 全局一致。不在对话中切换角色 → systemPrompt 永不变化 → 每次请求首段完全匹配
> 2. **消息只追加**：ChatBotModule 的 `_chatHistory` 只追加新消息，不修改历史 → 前缀完全稳定
> 3. **切换角色 = 新建对话**：不同角色使用不同 Conversation，旧对话保留原角色关联
>
> **可观测性**：
> - `DeepSeekAdapter.kt` 已在 `TokenUsage` 中收集 `prompt_cache_hit_tokens`
> - 对话完成后，通过 AgentTrace / RunLogScreen 可查看 `cachedTokens / totalTokens` 比例
> - Phase 2 集成验证阶段确认缓存命中率达标（目标 ≥90%，对标 Alife 95%）
>
> **当前状态**：消息只追加模式已天然存在（`_chatHistory` 不可变 + `mutex` 保护），缓存友好。差距在于 systemPrompt 当前硬编码而非通过角色系统管理，以及缺少缓存命中率监控。

### Phase 3: 自动化 + Agent Runtime（2.5 周）

| 任务 | 内容 |
|------|------|
| AutomationModule | Plan-Execute 引擎 + ExecutionGuard |
| AutomationStateMachine | 9 状态完整状态机 |
| AutomationWorker | WorkManager 集成 + 恢复 |
| **AccessibilityService 接入** | **引导用户开启辅助功能** |
| **AgentRuntime（极简）** | **只发 tick + 自适应报点 + AgentState 状态流，3 依赖** |
| **Scheduler** | **thin dispatch 层。订阅 systemBus.onTick → 遍历 List<AgentJob> → nextRunTime <= now → execute()** |
| **SystemEventModule/ReminderJob** | **改为实现 AgentJob 接口，由 Scheduler 统一触发。移除内部心跳循环** |
| AutomationScreen | 任务列表 + 状态卡片 |
| CreateAutomationScreen | Plan-Review-Execute 三步 |
| AutomationDetailScreen | 运行历史 + 执行面板 + AgentTrace Tab |
| AgentTraceEvent（独立实体） | 不入 Message 表 |
| TaskParserLLM + RuleBasedTaskParser | 移植 V1 |

### Phase 4: Memory Evolution（2.5 周）

> **架构修正**: MemoryModule 不再直接订阅 systemBus.onTick，改为通过 AgentJob 实现（MemoryReflectionJob / GraphExtractionJob / MemoryArchiveJob），统一由 Scheduler 调度（D21/D22）。避免双轨调度模型。

| 任务 | 内容 |
|------|------|
| MemoryType / MemoryStatus 枚举 | 记忆分类系统（T0-T3 已在 Phase 2.2 实现） |
| MemoryEntity（增强） | type/status/confidence/reinforcement 字段 |
| MemoryDao 增强 | getByType/getByStatus/findSimilar/getExpired |
| MemoryScoringEngine | typeWeight + timeDecay + reinforcement + confidence（实现 §3.7 评分公式；queryRelevance 由 Embedding 余弦供给 — D31） |
| EmbeddingProvider 接口 | Remote 实现 + Local 预留（**D31: 升级为检索主径，含存量回填与离线降级定义**） |
| MemoryConflictDetector | 语义相似度 + LLM 矛盾判断（`findSimilar` 基于 Embedding，非 FTS5 — D31） |
| MemoryManager（增强） | ~~FTS5~~ **Embedding 余弦主径 + bigram 降级 + Tier 2 兜底（D31 修订，RRF 融合 Embedding/bigram 两路）** + type 加权 |
| HistoryCompressor | LLM 驱动对话历史压缩 |
| MemoryReflectionJob（AgentJob） | 频率由 ConfigField 控制；执行冲突检测 + 过期归档 + 重复合并 |
| GraphExtractionJob（AgentJob） | 新记忆累积 ≥N 条触发；LLM 提取实体/属性/关系 |
| **UserGraph 系统** | EntityType + UserGraphEntity + UserGraphAttribute + Relationship + DAO |
| Graph 可信度更新 + MemoryArchive | 每月未确认衰减 0.05 + 90 天未访问归档 |
| MemoryManageScreen | 浏览 + 搜索 + FilterChip(type) + 详情 + **图谱可视化** |

### Phase 5: 设置完善 & 打磨 + 用户主权落地（2 周）

| 任务 | 内容 |
|------|------|
| **Agent 自主行为设置** | **Agent 模式 + 报点参数(90s/3x/4x 全可调) + 成本控制 + Reflection 开关** |
| **所有模块 ConfigField 全面用户化** | **§1.3 表中每个参数都暴露为 @ConfigField——AgentRuntime/Memory/DeepSeek/Qwen 的定时/频率/预算参数全部用户可调** |
| GeneralSettingsScreen | 主题/模型/参数全局设置 |
| ModulePermission | 模块权限声明与授权管理 UI |
| 暗色主题 | Material 3 Dark Color Scheme |
| ConfigMigration（各模块） | fromVersion/toVersion/migrate() 链式迁移 |
| 数据导出/导入 | Conversation + Message + Memory JSON 导出/导入 + 校验 |

---

## 七、架构决策记录

| # | 决策 | 结论 |
|---|------|------|
| D1 | Phase 顺序 | Phase 0 MVP → 平台渐进 |
| D2 | 导航结构 | 3 Tab: 对话/自动化/设置 |
| D3 | 思维链归属 | 思考过程 (thinkingDelta) → ChatMessage.thinking 字段；AgentTrace 事件 → AgentTraceEvent 独立表（Phase 3.7）。两者分离，思考用于 UI 展示，Trace 用于调试 |
| D4 | LLM 选择 | Capability 匹配 + ModelSelector |
| D5 | API Key 存储 | Config.apiKeyRef → SecureKeyStore |
| D6 | 自动化状态 | 9 状态完整状态机 |
| D7 | 记忆嵌入 | EmbeddingProvider 接口 |
| D8 | Config 升级 | ConfigMigration 链式迁移 |
| D9 | 权限模型 | **统一 ActionPermission（AUTO/CONFIRM/FORBIDDEN）。SecurityInterceptor 和 PolicyEngine 读同一来源。替代 SAFE/SENSITIVE 和 AUTO/ASK/FORBIDDEN 双轨** |
| D10 | Agent 自主 | AccessibilityService + 自适应报点 |
| D11 | Memory 演化 | MemoryType + 冲突检测 + ReflectionJob + UserGraph |
| D12 | MCP 兼容 | Phase 6 |
| D13 | 多 Agent | Phase 7 |
| D14 | Tool Manifest | ToolDefinition 声明式 |
| ★ D15 | **后台驱动** | **AccessibilityService 替代前台 Service。不被杀、无通知、国产 ROM 兼容** |
| ★ D16 | **用户图谱** | **UserGraph 作为 Memory System 子系统。属性带时间区间、来源和证据链** |
| ★ D17 | **用户主权** | **所有定时/频率/策略参数均为 ConfigField。默认值只是工程建议，用户拥有最终控制权** |
| ★ D18 | **AgentRuntime 极简化** | **只做 tick 发报。3 个依赖（SystemBus/ChatBus/LogManager）。模块自己订阅 tick 决定做什么** |
| ★ D19 | **SystemEventModule 去内驱** | **移除内部心跳循环。onTick 只由 AgentRuntime 单源触发。避免双源竞态** |
| ~~★ D20~~ | ~~MemoryModule 自驱动~~ | **SUPERSEDED by D21（v3.5 标记废弃）**：Memory 后台任务已全部实现 AgentJob（MemoryReflectionJob/GraphExtractionJob），由 Scheduler 统一分发。禁止任何模块直接订阅 systemBus.onTick |
| ★ D21 | **Scheduler 层** | **AgentRuntime 只发 tick。Scheduler 统一分发到各 AgentJob。避免模块轮询碎片化** |
| ★ D22 | **AgentState** | **独立于 AutomationStateMachine 的 Agent 自身状态模型。IDLE/OBSERVING/EXECUTING/REFLECTING/WAITING** |
| ★ D23 | **Memory Tier 分层** | **T0(临时)→不进Store, T1(事件)→月级, T2(事实)→永久, T3→UserGraph。规则驱动分类，零 LLM 成本。Phase 2 实现** |
| ★ D24 | **权限简化** | **Phase 0-2 仅 AUTO/CONFIRM（统一命名）。FORBIDDEN 保留定义，Phase 3+ 激活。单用户 Agent 不需要企业级权限** |
| ★ D25 | **Graph 可信度更新** | **USER_STATED 初始 0.9, INFERRED 初始 0.5。每月未确认衰减 0.05。规则写在 Attribute 层，不引入独立模块** |
| ★ D26 | **角色固定人格** | **角色在设置中选择（非对话内切换）。systemPrompt 全局一致，对话只追加不修改前缀。切换角色 = 新建对话。保障 DeepSeek/Qwen API 自动缓存命中率最大化** |
| ★ D27 | **Prompt Cache 优化** | **通过 D26 的角色固定 + 消息只追加确保前缀稳定。DeepSeek/Qwen API 自动缓存匹配前缀。AgentTrace 记录 cachedTokens 实现可观测。目标 ≥90% 缓存命中率（对标 Alife 95%）** |
| ★ D28 | **思考模式分离设计** | **ChatBotModule 统一处理 thinkingDelta（与 LLM 无关）。DeepSeek 使用 `thinking.type` + `reasoning_effort`（强度分级），Qwen 使用 `enable_thinking` + `thinking_budget`（Token 预算）。不同 API 的不同控制力在各自的 ModuleConfig 中独立暴露，符合 Phase 1 模块化原则。思考过程在 UI 中可折叠，默认折叠以减少视觉干扰** |
| D29 | **附件实现延后原则** | **附件 UI 和数据结构（`ChatMessage.attachments`）在 Step 2.1c 实现。Vision 端到端能力依赖 Step 2.1 FC Loop + Step 2.1b 思考管线闭合 → 图片链路验证在 Step 2.6 集成验证中完成。设计与实施计划的阶段差异已通过此决策明确** |
| ★ D30 | **ConfigField 统一规范（方案B）** | **所有 `@ConfigField` 注解的解析与写入必须使用 kotlin-reflect `KProperty`（`findAnnotation` + `getter.call` + `setter.call`），禁止使用 Java 反射（`declaredFields`/`getDeclaredField`/`methods`）。根因：Kotlin PROPERTY 目标注解存储于合成成员（`xxx$annotations`），Java 反射无法稳定读写。此前 4 轮修复均因 Java 反射与 Kotlin 注解存储模型不匹配而失效** |
| ★ D31 | **检索地基：放弃 FTS5 主径，Embedding 余弦 + bigram 降级**（v3.2 修订） | **SQLite FTS5 默认 tokenizer（unicode61）不做中文分词，`MATCH`/关键词列对中文整句记忆全部 miss（2026-08-20 实测证实：`memory_recall "用户住在哪里"` 零命中，临时以 bigram 滑窗补丁加固）。Android Room 无 ICU/jieba tokenizer。修订：混合检索主径 = EmbeddingProvider 余弦相似度；降级链 = Embedding 不可用 → bigram 滑窗 LIKE（已实现）→ Tier 2 事实兜底（已实现）。`MemoryScoringEngine.queryRelevance` 由 Embedding 余弦供给。`extractKeywords` 列仅作辅助索引，不再承担中文检索职责** |
| ★ D32 | **AI 回复入库治理：易逝内容 T0 过滤**（v3.2 修订） | **§3.1 源头治理原只覆盖用户消息侧，AI 回复"长度>40 即存 Tier 1"导致天气播报/搜索摘要等易逝内容污染记忆池（实测证实：「AI: 根据气象数据…邵阳市双清区今天天气」入库），月增条数估算（200 条/月）失真约 10 倍。修订：① AI 回复同样过 `classifyTier`（新增易逝性规则：天气/搜索结果/通知播报/纯寒暄 → Tier 0 丢弃）；② AI 侧长期知识只经 Reflection 提炼后入库（ReflectionJob 承接），不做逐轮直存；③ 用户侧直存策略不变。附带记录：P1-1 规则分类器双向误判修复（疑问/否定前置过滤 + 词级匹配）归入 Step 4.1；P2 项（Graph↔Memory 矛盾仲裁线 = Graph 为准 Memory 降权、记忆加密、容量配额）作为 Phase 4/5 跟进项** |
| ★ D33 | **实时语音分阶段**（v3.3，详见 ROADMAP_P5_P8.md） | **第一级 系统STT+现有LLM+系统TTS 级联（零新增成本，快速可用）；第二级 云端流式 Realtime（DashScope，真 Barge-in）。两级并存，ConfigField 切换（D17）** |
| ★ D34 | **屏幕阅读混合路线**（v3.3） | **无障碍节点树文本为主通道（零成本毫秒级）；按需（用户提问/文本不可用）MediaProjection 单帧截图走 VL。不做持续投屏** |
| ★ D35 | **社交平台 Telegram 优先**（v3.3） | **官方 Bot API 零封号风险先行；QQ 走 OneBot 11 + 远端 NapCat/LLBot 方案保留为后续 Adapter；微信个人号本轮不做（无官方 API）** |
| ★ D36 | **文件写入 SAF**（v3.3） | **用户经系统选择器选定「Zero 工作目录」+ persistUriPermission；txt/md 直写、PDF 用 PdfDocument。不申请 MANAGE_EXTERNAL_STORAGE** |
| ★ D37 | **Platform Gateway 统一消息模型**（v3.3，GPT 方案采纳） | **Agent Core 不感知平台：PlatformMessage/OutgoingMessage/MessagePlatform 接口 + MessageRouter；平台 Adapter（Telegram/QQ）与 MCP 同层接入** |
| ★ D38 | **Live Session 会话制权限模型**（v3.3） | **麦克风/屏幕/摄像头仅在用户主动开启 Live Session 时申请，会话结束全部释放。不设计永久后台偷听/偷看，符合 Android 权限模型与隐私预期** |
| ★ D39 | **无障碍配置升级**（v3.3，D34 依赖） | **agent_runtime_service_config 的 canRetrieveWindowContent 由 false 升为 true（屏幕文本主通道）。隐私边界扩大须在权限中心明示 + 包名白名单 + 仅 Live/FC 调用时读取** |
| ★ D40 | **双层调度架构**（v3.4，2026-08-22 实测定时失效修订） | **心跳型任务（反思/图谱/压缩/周期报点）走 AgentRuntime tick（进程内，延迟容忍）；准点型任务（提醒、DAILY/INTERVAL 自动化）走 AlarmManager `setExactAndAllowWhileIdle`（Doze 下也触发）+ `SCHEDULE_EXACT_ALARM` 权限（权限中心直达）+ 执行后重排下一闹钟。实测依据：三星冻结后台进程致进程内 delay 不唤醒（tick/kick 在前台正常、后台/夜间失效）。无障碍服务仍负责保活与心跳，闹钟负责准点** |
| ★ D41 | **REACT 执行模式**（v3.4） | **自动化新增智能执行模式（类 Claude Code agent loop）：不预设静态步骤，LLM 循环"观察结果→选工具→执行→判断"（每轮输出 JSON action），直至 final 或达 maxSteps 轮（复用守护：Token/超时/步数）。静态 PLAN 模式保留供简单任务；创建时可选，默认 REACT。执行器禁用自动化元工具（防递归），工具步骤走统一 ActionPermission** |
| ★ D42 | **自动化结果展示 = 上下两部**（v3.4，用户规范） | **上部「执行过程」：各步骤描述+完整结果（不截断），整区可折叠、默认折叠（过程细节按需展开）；下部「最终结果」：仅最终推送内容全文（如 10 个词汇完整展示），始终可见。修复实测两截断：步骤间依赖上下文 400→2500 字、结果摘要以推送内容为主体** |
| ★ D43 | **System Prompt 去时间化**（v5.7，2026-08-22 实测缓存 30% 修订） | **移除 ContextBuilder 的 timeBlock——秒级时间戳嵌在 system 中段使前缀缓存从该处全部失效（实测命中恒 1152t ≈ 人设+工具表）。时间信息由 MessageFilter 注入每条用户消息前缀（已有机制，行为无损）。system 前缀与全部对话历史完全稳定 → 前缀缓存最大化（目标 ≥90%）。D26/D27 的缓存策略由此才真正生效** |
| ★ D44 | **UserGraph = Canonical User State**（v3.5，原 v3.2 附带项升格） | **"用户是谁/现在怎样"以 UserGraph 为权威真相源（当前有效属性），Memory 是证据与历史（"用户何时说过什么"）。检索/回答冲突时 Graph > Memory（Memory 侧降权），Graph 属性自带 source/evidence/effectiveFrom/To 证据链。原 v3.2 P2 跟进项正式化** |
| ★ D45 | **分发定位：开源个人侧载**（v3.5，用户决策） | **Zero 为开源个人应用，无 Google Play 上架计划——Play 无障碍政策约束不适用（AccessibilityService + 自动化 + Overlay 大胆用）。附定位澄清：AccessibilityService ≠ 心跳服务器——AgentRuntime 是进程内独立单例，服务仅承担「startLoop 触发 + 窗口事件感官」；D40 后准点任务走系统闹钟，心跳型任务延迟容忍。CapabilityGrant 独立权限层不采纳（模块运行时检查 + ActionPermission + D38 会话制已覆盖），记 P8+ 候选** |
| ★ D46 | **Realtime 分层命名 + Provider 抽象**（v3.5，GPT 意见采纳） | **R0 Voice Chat = SpeechRecognizer→LLM→TTS 级联（半双工，D33 第一级）；R1 Live Voice = AudioRecord→VAD→流式云端 Provider→流式音频（真 Barge-in，第二级）。统一接口 `RealtimeVoiceProvider`（start/sendAudio/sendText/sendScreen/events/interrupt/stop），级联与云端为同接口可替换实现——换模型不动 LiveSession** |
| ★ D47 | **ScreenSnapshot 结构化**（v3.5，GPT 意见采纳） | **屏幕上下文不得为裸 String——`ScreenSnapshot(packageName, windowId, timestamp, contentHash, text, nodes, bounds)`。使用前校验时间戳/哈希陈旧度（Android 官方确证节点树可能过时，防"上一帧当当前页"）；无障碍（结构化）与 MediaProjection（视觉）双通道合并为 ScreenContext** |
| ★ D48 | **本地语音引擎：sherpa-onnx 完全替代**（v4.0，2026-08-23 用户决策） | **R0 级联（SpeechRecognizer + 系统 TTS）完全废弃，替换为 sherpa-onnx 全本地引擎。ASR = streaming-zipformer-small-bilingual-zh-en int8（~50MB，真流式逐字出稿，中英双语）；TTS = kokoro-multi-lang-v1.1 int8（~170MB，103 声音，StyleTTS 2 架构）；VAD = Silero（~1MB，Barge-in 检测）。音频与文本零上传（隐私最优先）。R7 OEM 兼容性风险彻底消除** |
| ★ D49 | **模型分发：APK 内置**（v4.0，用户决策） | **ASR + TTS + VAD 全部模型文件打包进 APK（总增量 ~220MB + sherpa-onnx AAR ~20MB ≈ 240MB）。不做运行时下载（避免下载失败导致失去语音能力——个人侧载场景 APK 大小可接受）。首次启动零配置即用** |
| ★ D50 | **LocalVoiceProvider 架构**（v4.0，D46 接口实现） | **`LocalVoiceProvider implements RealtimeVoiceProvider`（D46 统一接口不变）：AudioRecord → OnlineRecognizer（流式 Zipformer）→ partial/final 文本事件 → ChatEngineBridge → LLM 回复 → OfflineTts（Kokoro）→ AudioTrack 分段播放 → Silero VAD 检测用户说话 → interrupt()（真 Barge-in）。删除 CascadeVoiceProvider 与 ChatEngineBridgeImpl 中的系统服务依赖** |
| ★ D51 | **模块配置启动恢复**（v5.10，2026-08-25 平台遗留修复） | **`ModuleLifecycleManager.initializeAll()` 在每模块 onAwake 前先从 `ModuleConfigStore`（Room）恢复存量 config（`config.fromJson`，safeCall 隔离，`config_restored_at_start` 埋点）。此前全库唯一恢复挂载点在 `ModuleDetailViewModel.load()`——用户不打开模块详情页，模块整个进程周期都跑默认值（实测 v5.8 的 speech_tts 明文 Key 迁移因此空转；用户改引擎后重启也回默认）。fromJson 幂等，与 UI 路径重复应用无副作用；module-manager 因此新增 core:data 依赖** |
| ★ D52 | **防自听三重机制**（v5.10，2026-08-25，实测"TTS 语音触发 ASR"P0） | **① 音源 `MIC → VOICE_COMMUNICATION`——启用平台声学回声消除（AEC），麦克风输入自动扣除扬声器输出（TTS 声不再进 ASR/VAD，根治自触发）；② Barge-in 判定阈值 3→6 帧（600ms 持续语音）——AEC 残余回声偶发 2-3 帧不再误判"用户插话"（AI 自己打断自己）；③ 播报结束尾音守卫——`resetStream()` + 250ms 静默窗后才回 LISTENING，扬声器尾音/混响不被当下一句用户输入转写。真 Barge-in 能力保留（AEC 后真人声音远强于残余回声）** |
| ★ D57 | **P8 MCP 生态前置 + 自主能力全案**（v6.21，2026-08-27 用户决策） | **Phase 重排：P8 前置、P7 Telegram 顺延**。四项裁定：① MCP 客户端**自研轻量**（OkHttp Streamable HTTP + org.json，零新依赖栈；官方 Kotlin SDK 的 Ktor 栈弃用）；② 高德**官方托管 MCP 直连**（lbs.amap.com Streamable HTTP 端点 + Key，手机零 Node）；③ 办公文件**纯本地 POI**（拒绝云 API——隐私零上传优先，Aspose 等移出；Spike 先行 + 三阶段 + 5MB/5000 行边界）；④ 浏览器操控改判——"下载+爬取"本质是 HTTP+HTML 解析：**WebFetch 双引擎**（本地 jsoup + Jina Reader 免费层服务端渲染 JS 页，离屏 WebView 与 Playwright/PC 远程均移出）。**文件主权三原则**：LLM 无路径自由度（fileName 仅相对名+净化）、读写编辑下载全收敛 SAF 工作目录（D36 单一真相源）、downloads/ 子目录 + 大小/类型护栏 |
| ★ D56 | **悬浮球手势完备化：双击开启功能菜单**（v6.18，2026-08-27 用户决策，D55 修正） | **单击=打断（D55）/ 双击(≤320ms 内二次点按)=弹出功能菜单（恢复挂断·屏幕共享·文字气泡框三项原菜单）/ 长按=挂断 / 拖动=移位**。单击动作延迟 320ms 派发以消歧双击（双击到达即取消单击打断）——打断响应感知仍即时，误触率为零；菜单恢复后台屏幕共享与气泡框开关入口（D55 移除后仅前台可达的缺口补齐）** |
| ★ D55 | **打断最终裁定：扬声器手动 + 耳机自动全双工**（v6.17，2026-08-27 用户决策，D54 探针废止） | **扬声器场景：自动打断全面放弃，手动打断为唯一路径**——悬浮球【点按=打断当前播报，长按=挂断】（原弹出菜单移除，屏幕共享/气泡开关回归 Live 界面），另有界面打断按钮与通知"静音"；TTS 播报恢复连续（探针 450ms 人工停顿废除）。耳机/蓝牙场景：物理无回声，自动全双工保留（VAD 3 帧秒断 + ASR partial 判停）。依据：无硬件参考的扬声器自动打断业界无成功先例（D54 调研），手动路径 100% 可靠零误触** |
| ★ D54 | **打断策略转向：回声回避（双模式）替代回声消除**（v6.16，2026-08-26 用户决策，v6.11-v6.15 五轮 AEC 实测未达判据） | **业界调研结论：WebRTC(AEC3 5万行 DSP)/Google Assistant(硬件 EC_REF)/Echo(麦克风阵列+AEC 芯片)/LiveKit-Pipecat(浏览器 AEC 已消好)/Wyoming(无硬件 AEC 明确不支持扬声器打断)——无成功项目在"同设备扬声器+麦克风、无硬件参考"下靠纯软件做到可靠全双工**。策略转向：① 耳机模式（AudioDeviceInfo 检测有线/蓝牙输出，2s 缓存）→ 物理无回声 → 全双工打断全开（VAD 3 帧秒断 + ASR 连续 partial 判停）；② 扬声器模式 → 播报期不检测（回声结构性无法误触，自打断根除），MiMo/Melo 每 3.5s **静默探针**（等 playbackHeadPosition 排空缓冲 → 450ms 全静默窗 → 纯 VAD 检测，此刻有声音必是真人）→ 打断延迟上限 ≈ 句长 2-4s，永不自打断；③ 手动打断恒可用；④ 软件 AEC 旁路（代码保留，接硬件参考时启用）。系统 TTS 引擎无探针能力（扬声器模式仅手动打断）** |
| ★ D67 | **自编译最大授权（2026-09-15 用户裁定）**：Termux 环境即授权边界——无沙箱/不限 import/挂载即 AUTO；三道非权限护栏（编译门/试跑探活/全链 trace+CRASHED 治理）。详见模块化重构方案 D67 | 全 M3.x 落地实测：develop_module→挂载→零弹窗直用闭环（模拟器 L3 2026-09-17） |
| ★ D68 | **动态模块配置协议**：manifest configSchema 三型声明 → Zero 配置页动态渲染 → ModuleConfigStore 复用 → env ZERO_MOD_CONFIG 注入（保存即重启生效）；Secret 预留 get_secret 不入此通道 | M2.4 端到端实测（改值→重启→get_config() 回读一致） |
| ★ D69 | **Linux 工作站**：Termux proot Debian 全环境托付 Agent 自主开发——三通道（exec 会话/文件双向/进程与服务管理）+ 工作区契约 /root/work≠/root/zero | M3.0 五链闭环验证（构建/克隆/服务 D51/自编译/决策-操作） |
| ★ D53 | **全双工语音改造：far-end 参考信号 AEC**（v6.14/Phase 6.5，2026-08-26 用户决策，v6.10-v6.13 四轮打断横跳教训） | **音源回 `MIC` 原始流（OEM VOICE_COMMUNICATION 黑盒链废弃——实测同机型既压制说话期用户语音又漏回声），自建纯 Kotlin NLMS 软件 AEC（WebRTC 通话同族算法极简实现）：写入扬声器的每块 PCM 作 far-end 参考同步喂入（MiMo/Melo 写入点挂钩，系统 TTS 无 PCM 访问为文档化限制），麦克风近端经自适应滤波减除回声 + NLP 残余抑制 → VAD/ASR 同源干净信号；打断回归经典判定（AEC 后 VAD 连续 3 帧），v6.13 播报文本回声判别降为二道防线。业界依据：WebRTC AEC far-end reference / LiveKit 可中断播放队列 / Pipecat 分句流式 / Wyoming 硬件层回声处理。后续：6.12 分句流式管线（首响延迟）、6.13 语义判停与蓝牙场景** |

---

## 八、与 Alife PC 的差距分析

| 能力 | Zero V2（完整实现后） | Alife PC | 差距说明 |
|------|---------------------|----------|---------|
| 聊天体验 | ★★★★★ | ★★★★★ | 基本无差距 |
| 思考过程渲染 | ★★★★ | ★★★★★ | 计划实现可折叠思考卡片 + 强度控制，Alife 已有完整实现 |
| 多模型选择 | ★★★★★ | ★★★★ | Capability Selector 更智能 |
| 模块化架构 | ★★★★★ | ★★★★ | V2 优势 |
| Function Calling | ★★★★ | ★★★★★ | 工具生态差距（MCP 后缩小） |
| Agent 自主运行 | ★★★★★ | ★★★★★ | AccessibilityService 对标 Alife 后台 |
| 用户图谱 | ★★★★ | ★★★★ | 属性+关系+来源，能力同级 |
| **用户主权** | **★★★★★** | **★★★** | **V2 优势：所有参数用户可调，Alife 不少是硬编码** |
| 电脑控制 | ★★★★ | ★★★★★ | 2026-09-17 模块化轨后大幅缩小：Termux/Debian Linux 工作站全权（linux_exec/文件双向/多文件构建/git/自建服务）+ LLM 自编译模块热插拔（develop_module 零弹窗闭环实测）+ Android 系统层 Shizuku 操作；余差=真机保活/B13 矩阵（见模块化落地计划 §十遗留清单） |
| Android 原生能力 | ★★★★★ | ★★ | V2 优势 |

---

## 九、总结

### v3.0 → v3.1 核心变更

1. **Agent Runtime 驱动方式**：前台 Service → **AccessibilityService**
2. **报点算法**：固定循环 → **Alife 自适应算法**（打断→重置）
3. **Scheduler 层**：模块轮询 `if(shouldRun())` → **AgentJob 统一调度**
4. **AgentState**：Automation 状态 ≠ Agent 状态 → **IDLE/OBSERVING/EXECUTING/REFLECTING/WAITING**
5. **Memory Tier**：无差别记忆化 → **T0(临时)/T1(事件)/T2(事实)/T3(图谱)**，规则驱动
6. **Memory System**：纯情景记忆 → **情景记忆 + UserGraph**
7. **用户主权**：所有定时/频率/策略 → **用户可在模块详情页修改**
8. **权限模型**：统一 ActionPermission，Phase 0-2 简化

### Agent Core 最终架构

```
                 AccessibilityService（系统级保活）
                         │
                 Agent Runtime（只发 tick + AgentState）
                 自适应报点 + 用户打断重置
                         │
                  systemBus.onTick
                         │
                    Scheduler
                         │
        ┌────────────────┼────────────────┐
        │                │                │
   Module Framework  Automation Engine  Memory System
   (模块+FC+工具)    (Plan-Execute)    ┌─────────────┐
                                       │ Tier 0-2    │ ← 情景记忆（分层）
                                       │ User Graph  │ ← 用户图谱（Tier 3）
                                       └─────────────┘
        ↑
   AgentJob 接口统一
   (ReminderJob, MemoryReflectionJob, GraphExtractionJob, ...)
```

**最终定位**：一个通过 AccessibilityService 在 Android 上持续运行的、用自适应报点自我调节节奏的、拥有情景记忆和结构化用户图谱的、所有自主行为参数都由用户最终控制的——个人 AI Agent。
