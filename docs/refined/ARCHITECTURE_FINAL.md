> 2026-10-17 C5当前：生产闭环/协议修复、根质量及完整编译、848项JVM含2既有跳过、55项应用执行与6项冻结检查通过。新真实通知实验仅作诊断，保存回执/日期/跨键/目标层级门未过，自动注入未接；已清理，手机未操作。工具3/Room37/JSONv5保持；见§7.27–7.29与artifacts/p5-production-agent-20261017/acceptance.md。

> P5实机补验（2026-10-16）：Fold3/API35独立来源与假模型调用链16/16通过；同源码测试包编译复核12秒通过，无代码修订。临时包已清理，主APK摘要和原包/UID列表不变，无日常数据写入或云推理。真实模型、个人盲留出、推理时延/能耗与其他P5父项仍开放。证据artifacts/p5-phone-pipeline-20261016/acceptance.md。

> 当前P5进度（2026-10-16 调用链续片）：已实现显式2500ms证据调用链、完整段落来源及人工确认审计、scope隔离、请求前后模型资格检查；未接管日常检索。质量门及完整编译通过，当前JVM784含2跳过、零失败，本轮新增16项和API35隔离16项全通过。新个人盲留出、真实模型/手机质量、跨场景偏好归纳与S-E仍开放。临时包/自有模拟器已清理，无手机/云/历史写入，缓存与默认保持。见持久系统设计§7.12及artifacts/p5-evidence-pipeline-20261016/acceptance.md。

> 2026-10-16 P5三类用途续片：EvidenceSupport候选契约区分确定/不确定/无支持，客户端核对来源与当前性；尚无生产调用。新8候选45问，“原文判断＋客户端核验”三组15/15，通过本次合成开发门；向模型发送核验标记的对照未过门。不等同个人盲留出/全库/手机达标。修复空正文、未知角色默认及Android JSON API兼容问题；根qualityCheck/assembleDebug 3分54秒，平台补修后services质量/测试与完整编译1分36秒通过，JVM762含2跳过、零失败。冻结输入未变；无安装/云推理/手机操作或历史改写，临时本机模型已关闭。详见持久系统设计§7.10及artifacts/p5-support8-20261016/acceptance.md。

> 2026-10-16 P5独立语义门：新冻结36问三语言组，现行提示9/12、8/12、8/12，固定资格提示均10/12（未达90%，不替换生产）；BGE仅相关性基线。修复空白证据查询误返全部候选边界，工具排序及缓存策略保持。红绿回归、qualityCheck/assembleDebug 2分34秒通过，JVM752含2跳过、零失败。临时本机模型已关闭，无应用安装/云推理/手机操作或自动历史改写；S-D/S-E及P5父门开放。详情artifacts/p5-semantic-gate-20261016/acceptance.md、持久系统收敛设计§7.9。

> 2026-10-16 P5本轮：证据资格/有效期/冲突契约、脱敏分类诊断、本地重复偏好候选及索引维护页落地；候选仍需人工保存确认，无自动历史改写。新合成语义资格中英5/6与3/6未过门，不部署。最终qualityCheck/两类测试APK/assembleDebug 2分1秒通过，JVM750含2跳过、零失败；模拟器25项应用+29项索引通过。三个临时包清理，无手机安装/操作和云推理，缓存/default保持。详见artifacts/p5-evidence-20261016/acceptance.md。

> 2026-10-16 P5 S-D第二候选未过门：冻结合成中英16问，BGE v2-m3原文重排中文4/8、英文2/8（E5 4/8、3/8），正确候选覆盖16/16；只做本地CPU对照，不部署、不改默认。上一轮结构化type存在标签混淆，不能外推来源结构收益；后续独立冻结证据资格/当前性验收。详见artifacts/p5-ranking-bge-20261016/acceptance.md。

> 2026-10-16 P5 S-C JSON闭合：v3备份恢复四种提案状态、确认审计与删除依赖，兼容v1/v2；保留本机同ID记忆/决定/复核来源，损坏审计整批回滚。20项存储与操作、2项生产备份页面通过；qualityCheck/测试APK/assembleDebug 3分6秒通过，JVM740项含2跳过、零失败。两个模拟器临时应用已清理，无手机安装/写入、无云推理。缓存与默认模型保持；P5质量父门仍开放。详见artifacts/p5-review-backup-20261016/acceptance.md。

# Zero v2 — 最终架构设计

> 基于 v1 全链路经验 + Alife v4.1.0 源码分析 + Claude Code / OpenCode 自动化模式研究
> 已纳入全部审阅反馈 + 第二轮架构审查修复
> 修订记录：
>   v2.2 — 第三轮优化：Token动态配额 / 并发FC / 沙箱隔离 / PriorityQueue / Readability / 混合检索 / AutoForm反优化 / 白盒可观测 / 崩溃自愈
>   v2.1 — 第二轮架构审查：循环依赖 / 后台限制 / Plan-Execute容错 / 并发安全 / 写入竞态
>   v2.0 — 初始设计
> 状态：待确认后启动 Phase 1 编码

---

## 一、决策记录（已确认）

| # | 决策点 | 结论 | 理由 |
|---|--------|------|------|
| 1 | KSP 注解 vs 手动 Hilt | **手动 Hilt 绑定**（Phase 1-7），Phase 8+ 评估 KSP | 初期模块可控，KSP 增加编译复杂度 |
| 2 | 配置持久化 | **Room 表**：`module_configs(moduleId, configJson)` | 统一管理、便于备份导出、不产生 N 个 DataStore 文件 |
| 3 | FC 协议 | **OpenAI 原生 FC**（主），非 FC 模型 Prompt 层降级为 JSON/XML | 主流模型对 OpenAI FC 训练最充分，解析成功率最高 |
| 4 | Python 执行 | **暂缓至 Phase 9+** | Chaquopy 增加 APK 体积数十 MB，优先用 Android 原生能力 |
| 5 | 插件热加载 | **QuickJS 引擎预留**（Phase 9+） | 轻量、沙箱安全、无需重新打包 |
| 6 | v1 代码策略 | **架构从零搭建，数据层/LLM 客户端/ONNX 直接移植** | 架构不继承上帝类包袱，成熟工具代码复用 |
| 7 | 模块依赖 | **launchOrder + Hilt 构造函数注入**，无需显式 DAG | Hilt 已隐式解决依赖，launchOrder 控制初始化顺序 |
| 8 | 消息过滤 | **独立责任链 FilterPipeline**，ChatBus 仅负责通知广播 | 避免 SharedFlow 并发修改竞态 |
| 9 | 心跳保活 | **前台 Service(轻量检查) + WorkManager(重量执行) + AlarmManager(唤醒)** | 三层分层：轻量检查不触发网络、WorkManager 自带重试/约束、AlarmManager 兜底唤醒 |
| 10 | Phase 顺序 | **P1 框架 → P2 对话 → P3 FC 系统 → P4 记忆 → P5-P9** | FC 系统提前，Memory 和 SystemEvent 依赖它 |
| 11 | 循环依赖解耦 | **SystemBus.OnScheduleTriggered 事件解耦 SEM→ATM**；FunctionRegistry 延迟查询 | 避免 Hilt 构造循环和逻辑环路 |
| 12 | Plan-Execute 容错 | **maxReplanCount=2 + JSON 分层提取 + 降级规则引擎** | 防止 LLM 无限重规划耗尽 Token；LLM 非纯 JSON 输出容错 |
| 13 | MessagePipeline 安全 | **withTimeout(3s) + Filter 100ms 性能契约** | 防止耗时 Filter 卡住 UI 线程 |
| 14 | ChatBus 缓冲区 | **差异化策略**：onChatReceived DROP_OLDEST(64) / onToolCall SUSPEND(16) / onChatFinished SUSPEND(8) | 高频流式可丢帧；ToolCall 不可丢帧 |
| 15 | Memory 写入锁 | **Mutex + Room @Transaction** | 防止自动化后台与前台对话并发写入触发 SQLite database is locked |
| 16 | Token 动态配额 | **Token Budget Allocator**: System 15% + Tools 15% + RAG 20% + History 50%。超预算时压缩旧对话为摘要 | 没有预算管理的 LLM 应用不可靠，防止超出 Context Window |
| 17 | 并发 FC 执行 | **同批次 ToolCall 默认并行** (coroutineScope+async)，显式依赖通过 `depends_on` 声明 | 3 个独立搜索从 3T→T |
| 18 | 沙箱隔离 | **SAFE/SENSITIVE 两级安全**，SENSITIVE 操作弹窗确认 | 防止 LLM 幻觉误删数据/乱发通知 |
| 19 | 唤醒任务队列 | **PriorityQueue<SystemTickTask>** + 三重唤醒源 (AlarmManager/WorkManager/Service) | O(n)扫描→O(log n) |
| 20 | ContentExtractor 提纯 | **Readability 算法** (文本密度+标签层级剪枝) + 降级管道 | 普通 Jsoup 提取噪声极大 |
| 21 | 混合检索 | **FTS5 全文 + Vector 向量 + RRF 融合** (Phase 5+) | 纯向量检索对关键词/数字敏感度差 |
| 22 | AutoFormUI 反优化 | **onAwake 时解析为 List<FormFieldState>**，UI 只绑定 State，不反射 | 反射在 Compose 渲染路径不可接受 |
| 23 | 白盒可观测性 | **AgentTrace 系统**：每操作带 traceId，UI timeline 可视化 | 调试自动化任务的核心依赖 |
| 24 | 崩溃自愈 | **ModuleLifecycleManager 包裹 try-catch**，Core 模块崩溃不自动禁用，非 Core 标记 DISABLED | 基础设施，越早越好 |

---

## 二、外部自动化工具模式研究

### 2.1 Claude Code 核心模式

Claude Code 是 Anthropic 的命令行 AI 编程代理。关键设计模式：

| 模式 | 描述 | Zero v2 映射 |
|------|------|-------------|
| **Plan-Execute 循环** | 制定计划 → 执行步骤 → 观察结果 → 调整计划 | AutomationModule 的 `Plan → Execute → Synthesize` 管道 |
| **MCP 工具协议** | 通过 Model Context Protocol 动态接入外部工具 | `FunctionRegistry` 的 `register()` 机制 |
| **权限模式** | `bypassPermissions` / `acceptEdits` / `default` | AutomationModule 的 `ExecutionGuard`（成本/时间/步骤上限） |
| **后台子代理** | `task` 工具启动独立代理执行子任务 | `parallelSearch()` — 多搜索词并行执行 |
| **Hook 系统** | Pre/Post 钩子（`before_chat` / `after_edit` 等） | `onTaskStart` / `onStepComplete` / `onTaskFinished` 事件 |
| **Worktree 隔离** | Git worktree 中安全修改代码 | Android 不需要（无文件系统级回滚需求） |
| **Token 预算** | `budget.total` / `budget.spent()` / `budget.remaining()` | `ExecutionGuard.maxTokensPerRun` |
| **stream-json 输出** | 实时流式 JSON 输出（reasoning / tool_call / result） | `PokeEvent` 实时推送步骤进度到对话 |

### 2.2 OpenCode 核心模式

OpenCode 是 Anthropic 的 VS Code 多代理协作框架：

| 模式 | 描述 | Zero v2 映射 |
|------|------|-------------|
| **Agent 类型系统** | `general-purpose` / `code-reviewer` / `plan` / `explore` | Module 分类（CORE/INSTRUMENT/SYSTEM/AUTOMATION） |
| **Skill 系统** | 打包的指令文件（`SKILL.md`），按需加载 | 未来 Phase 的 Skill Module（对标 Alife Skill 系统） |
| **Workflow 编排** | `pipeline()` — 无屏障流水线 / `parallel()` — 屏障并行 | AutomationModule 内 `executePipeline()` |
| **Token 预算感知** | `budget.remaining()` 控制代理生成数量 | `ExecutionGuard` |

### 2.3 对 AutomationModule 的核心启示

1. **Plan-Execute 优于固定管道**：当前的 `SEARCH_AND_ANSWER` / `KNOWLEDGE_UPDATE` / `DAILY_BRIEF` 三种固定管道太僵硬。LLM 应能自主规划步骤。

2. **步骤级进度推送**：每完成一个子步骤（搜索 / 抓取 / 合成），通过 Poke 实时推送到对话，用户可见执行进度。

3. **守护机制必不可少**：Claude Code 的 `bypassPermissions` 和成本守卫 ($5 硬上限) 证明自动化必须有护栏：
   - 最大步骤数（默认 10）
   - 最大 Token 消耗（默认 50K）
   - 最大执行时间（默认 5 分钟）
   - 高成本操作需用户确认

4. **结果持久化为对话**：OpenCode 的 Workflow 完成后输出结构化结果。Zero 的自动化结果应自动创建带标题的对话记录。

---

## 三、消息处理管道（替代 SharedFlow 修改）

### 3.1 问题

原设计中 `onChatSend` 暴露为 `MutableSharedFlow<ChatSendEvent>`，允许订阅者直接修改 `event.message`。如果多个模块同时收集（Flow 并发），修改顺序不可控。

### 3.2 改进方案：独立 FilterPipeline（v2.1 增加超时保护）

```kotlin
// framework-module/src/.../MessagePipeline.kt

/**
 * 消息预处理管道（责任链模式）。
 *
 * 替代原设计中通过 MutableSharedFlow 修改消息的方案。
 * 每个 Filter 按 filterOrder 排序依次处理消息。
 * 处理完成后通过 ChatBus 广播 onChatSent（通知性质，不修改）。
 *
 * ⚠️ 安全保护（v2.1）：
 * - 全局超时 3 秒，超时则跳过所有 filter 返回原始消息
 * - 每个 IMessageFilter 必须遵守 100ms 性能契约
 */
@Singleton
class MessagePipeline @Inject constructor(
    @ZeroMessageFilters private val filters: Set<@JvmSuppressWildcards IMessageFilter>
) {
    private val ordered: List<IMessageFilter> by lazy {
        filters.sortedBy { it.filterOrder }
    }

    suspend fun process(event: ChatSendEvent): ChatSendEvent {
        return withTimeoutOrNull(3000L) {
            var current = event
            for (filter in ordered) {
                current = filter.process(current)
            }
            current
        } ?: event  // 超时 → 降级返回原始消息
    }
}

/**
 * 消息过滤器接口。
 *
 * ⚠️ 性能契约（v2.1）：此方法必须在 100ms 内完成。
 * 禁止: 网络请求、DB 写入、文件 I/O、大文本正则回溯。
 * 允许: 字符串拼接、长度检查、简单正则匹配、时间戳格式化。
 */
interface IMessageFilter {
    val filterOrder: Int
    suspend fun process(event: ChatSendEvent): ChatSendEvent
}
```

### 3.3 ChatBus 差异化缓冲策略（v2.1）

不同事件类型使用不同的 `SharedFlow` 配置：

```kotlin
// ChatBusImpl.kt

@Singleton
class ChatBusImpl : ChatBus {
    // 高频 + 可丢帧（UI 流式渲染，丢一帧不影响最终结果）
    override val onChatReceived = MutableSharedFlow<StreamChunk>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // 低频 + 不可丢帧（Tool Call 必须全部精确到达）
    override val onToolCallStart = MutableSharedFlow<ToolCallStartEvent>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.SUSPEND
    )
    override val onToolCallResult = MutableSharedFlow<ToolCallResultEvent>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.SUSPEND
    )

    // 每次对话一次 + 不可丢帧
    override val onChatFinished = MutableSharedFlow<ChatFinishedEvent>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.SUSPEND
    )

    // 通知性质 + 可丢旧消息
    override val onChatSent = MutableSharedFlow<ChatSentEvent>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // 系统消息 + 可丢旧消息
    override val onPokeSend = MutableSharedFlow<PokeEvent>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
}
```

### 3.3 ChatBus 职责调整

```
调整前：
  onChatSend: MutableSharedFlow<ChatSendEvent>  ← 订阅者可修改消息（有竞态风险）

调整后：
  MessagePipeline.process(event)                    ← 责任链依次处理（确定性顺序）
  onChatSent: MutableSharedFlow<ChatSentEvent>      ← 处理完成后广播（只读，通知性质）
  onChatReceived: MutableSharedFlow<StreamChunk>    ← LLM 流式输出
  onChatFinished: MutableSharedFlow<ChatFinishedEvent> ← 对话完成
  onPokeSend: MutableSharedFlow<PokeEvent>           ← 系统消息广播
```

---

## 四、循环依赖解耦（v2.1）

### 4.1 SystemEventModule ↔ AutomationModule 事件解耦

**问题**：`SystemEventModule.onTick()` 直接持有 `AutomationModule` 引用 → 逻辑环路。
**方案**：SystemBus 增加 `onScheduleTriggered` 事件，SystemEventModule 只发布事件，AutomationModule 订阅执行。

```
调整前:  SystemEventModule.onTick()
           → automationModule.executeNow()  // 直接引用, 逻辑环路

调整后:  SystemEventModule.onTick()
           → SystemBus.emit(OnScheduleTriggered(automationId))
         AutomationModule 订阅:
           → SystemBus.onScheduleTriggered.collect { executeNow(it.automationId) }
```

```kotlin
// SystemBus 新增事件
interface SystemBus {
    // ... 原有事件 ...
    val onScheduleTriggered: MutableSharedFlow<ScheduleTriggeredEvent>
}
data class ScheduleTriggeredEvent(
    val automationId: String, val triggeredAt: Long, val source: TriggerSource
)
enum class TriggerSource { HEARTBEAT, ALARM, MANUAL }
```

### 4.2 FunctionRegistry 延迟查询（避免 ChatBot → ContextBuilder → FunctionRegistry → ModuleLifecycleManager 环路）

**关键约定**：`FunctionRegistry.getAllDefinitions()` 在每次 `buildSystemPrompt()` 时延迟调用（而非 DI 构造时固化），`FunctionRegistry` 不持有 `ModuleLifecycleManager` 引用 — 各模块在 `onAwake()` 中通过 `registry.register()` 主动注册，`getAllDefinitions()` 只返回已注册的 Map。

---

## 五、模块间通信逻辑详解

### 5.1 消息发送全流程

```
  用户输入 "今天有什么AI新闻？"
    │
    ▼
  ChatBotModule.sendMessage("今天有什么AI新闻？", source = CHAT_WINDOW)
    │
    ├── 1. 构建 ChatSendEvent(message, source, attachments)
    │
    ├── 2. MessagePipeline.process(event)
    │     │
    │     ├── MessageFilterModule.process()   ← 注入时间戳
    │     │     "当前时间:[2026-08-10 14:35]\n今天有什么AI新闻？"
    │     │
    │     └── [未来其他 Filter，如敏感词过滤]
    │
    ├── 3. ChatBus.emit(onChatSent)           ← 广播（通知各模块）
    │     SystemEventModule 收到 → 重置退避计数器
    │
    ├── 4. ContextBuilderModule 组装 System Prompt
    │     ├── 角色人设（从 CharacterManager 获取）
    │     ├── 本轮上下文扩展（视觉/记忆/事实 — ChatBus 注入）
    │     ├── 时间信息块
    │     └── 可用工具列表（FunctionRegistry.getAllDefinitions()）
    │
    ├── 5. LLMClientModule.chatStream(messages, systemPrompt, tools, config)
    │     │
    │     ├── 发送 HTTP POST to DashScope
    │     └── 收集 SSE 流
    │
    ├── 6. 每收到 StreamChunk:
    │     ├── ChatBus.emit(onChatReceived)    ← UI 增量渲染
    │     ├── 检查 chunk.toolCallDelta:
    │     │   ├── 有 → 解析 ToolCall → 暂停流收集
    │     │   └── 无 → 继续
    │     └── 检查 chunk.isFinal → 流结束
    │
    ├── 7. 如果有 Tool Call:
    │     ├── ChatBus.emit(onToolCallStart)
    │     ├── FunctionRegistryModule.execute(name, arguments)
    │     │   └── ModuleRegistry → WebSearchModule.search("AI 新闻")
    │     │       ├── ContentExtractor.extractBatch(urls)
    │     │       └── 返回 FunctionResult(data, structuredData)
    │     ├── ChatBus.emit(onToolCallResult)
    │     └── 重复步骤 4-6（continueAfterToolCall）
    │
    └── 8. 对话完成:
          ├── ChatBus.emit(onChatFinished)
          │   ├── MemoryModule 收到 → intelligentMemorize()
          │   └── AutomationModule 收到 → 检查是否自动化任务
          └── 持久化 MessageEntity
```

### 5.2 心跳与主动行为流程（v2.1 分层 + 事件解耦）

```
  BackgroundServiceModule (前台 Service 运行中)
    │
    ├── Layer 3: AlarmManager.setExactAndAllowWhileIdle（每 15 分钟）
    │     └── 系统级唤醒 → 检查逾期提醒/任务
    │
    ├── heartbeatLoop() 每次 tick（轻量检查，≤5s）:
    │     │
    │     ├── 遍历所有 ITimedModule.onTick(tickMs)
    │     │
    │     ├── SystemEventModule.onTick(tickMs)
    │     │   ├── 检查 dynamicTick → Poke("动态报点唤醒...")
    │     │   ├── 检查 reminders[] → Poke("⏰ 提醒...")
    │     │   ├── 检查 nextAutoTick → Poke("系统周期报点...")
    │     │   └── 检查自动化调度 → SystemBus.emit(OnScheduleTriggered)  ← v2.1 事件解耦
    │     │
    │     └── UsageStatsModule.onTick(tickMs)
    │
    ├── Layer 2: AutomationModule 订阅 SystemBus.onScheduleTriggered
    │     → 创建 AutomationWorker (WorkManager) 执行重量任务
    │     → WorkManager 自带: 网络约束 / 失败重试(BackoffPolicy) / 自动恢复
    │
    └── 用户消息到达 → SystemEventModule 重置退避
```

### 5.3 自动化执行全流程（v2.1 增强容错）

```
  用户手动触发 or SystemBus.onScheduleTriggered
    │
    ▼
  AutomationModule.executeNow(automationId)
    │
    ├── 1. ExecutionGuard 检查 (v2.1: maxReplanCount=2, maxSteps=10, maxTokens=50K, maxDuration=5min)
    │     ├── 是否已有同名任务在运行？ → 跳过
    │     ├── 本月累计 Token 是否超限？ → 拒绝
    │     └── 是否有可用网络？→ 无网络则推迟到 WorkManager
    │
    ├── 2. 创建 AutomationRunEntity(status = RUNNING)
    │
    ├── 3. LLM 生成 Plan (v2.1: JSON 分层提取 + 降级规则引擎)
    │     │
    │     │  Plan 结构:
    │     │  {
    │     │    title: "每日AI新闻",
    │     │    steps: [
    │     │      {id: "search_1", action: "web_search", mode: "parallel", ...},
    │     │      {id: "search_2", action: "web_search", mode: "parallel", ...},
    │     │      {id: "synthesize", action: "llm_synthesize", ...}
    │     │    ]
    │     │  }
    │     │
    │     │  JSON 提取策略 (v2.1):
    │     │    1. 直接解析 (rawText 以 { 开头)
    │     │    2. 移除 Markdown ```json ``` 包装
    │     │    3. 正则提取最外层 { ... }
    │     │    4. 重试一次 (prompt 追加 "Return ONLY valid JSON")
    │     │    5. 降级 RuleBasedTaskParser
    │     │
    │     └── parsePlanJson() 失败 → RuleBasedTaskParser 生成默认 Plan
    │
    ├── 4. 执行步骤 (Plan-Execute 循环, v2.1: maxReplanCount=2):
    │     │
    │     for (step in plan.steps) {
    │       ├── guard.checkTimeout()      ← 超时检查
    │       ├── guard.checkTokens(used)   ← Token 检查 (留底 5000)
    │       │
    │       ├── Poke("🔧 [${index+1}/${total}] ${step.description}")
    │       │
    │       ├── executeStep(step)
    │       │   ├── "web_search" → FunctionRegistry.execute("web_search", params)
    │       │   ├── "llm_synthesize" → llmClient.chatSync(prompt)
    │       │   ├── "parallel" → coroutineScope { subSteps.map { async { executeStep(it) } }.awaitAll() }
    │       │   └── step.allowFailure=false && failed → throw StepFailedException
    │       │
    │       ├── Poke("✅ 完成: ${step.id}")
    │       │
    │       └── if (result.suggestsMoreSteps
    │              && guard.canReplan()              ← v2.1: 最多重规划 2 次
    │              && guard.remainingTokens() > 5000) ← v2.1: Token 留底
    │           → generatePlan(已有结果) → 追加步骤
    │     }
    │
    ├── 5. LLM 合成最终摘要 (≤500字)
    │
    ├── 6. 持久化 + 通知:
    │     ├── AutomationRunEntity → COMPLETED
    │     ├── ConversationEntity + MessageEntity
    │     └── notifyOnComplete → push_notification
    │
    └── 7. 失败处理:
          ├── 可重试错误 → WorkManager BackoffPolicy 自动重试
          ├── 不可重试错误 → 标记 FAILED + 记录 errorMessage
          └── 一次性任务 → 失败也标记完成（避免无限重试）
```

### 4.4 模块配置加载流程

```
  ModuleLifecycleManager.initializeAll()
    │
    for (module in ordered) {
      ├── 1. 检查是否需要加载配置
      │     └── module 有 config 属性？
      │
      ├── 2. 从配置存储读取
      │     ├── ModuleConfigDao.getByModuleId(module.metadata.id)
      │     ├── 有 → module.config.fromJson(record.configJson)
      │     └── 无 → 使用默认配置
      │
      ├── 3. module.onAwake()
      │     └── 模块在此注册 FC 函数、初始化内部状态
      │
      └── 4. module.onStart()
            └── 模块在此订阅 ChatBus 事件、启动定时器
    }
```

### 4.5 FC 函数注册与执行流程

```
  模块 onAwake():
    │
    module.registerFunctions(registry)
      │
      ├── registry.register(
      │     FunctionDef(name="web_search", params=[...], description="..."),
      │     handler = { args -> module.search(args) }
      │   )
      │
      └── 注册后，FunctionRegistry 维护:
            Map<functionName, FunctionEntry(definition, handler, sourceModule)>
  ────────────────────────────────
  LLM 调用时:
    │
    ChatBotModule 收到 tool_calls:
      │
      ├── 解析 ToolCallDelta → ToolCall(name="web_search", arguments="{\"query\":\"AI\"}")
      │
      ├── ChatBus.emit(onToolCallStart)
      │
      ├── FunctionRegistryModule.execute("web_search", {"query": "AI"})
      │   ├── 查找 handler
      │   ├── 执行 handler(args)
      │   ├── 记录 ModuleCallLogEntity
      │   └── 返回 FunctionResult(data, structuredData)
      │
      ├── ChatBus.emit(onToolCallResult)
      │
      └── continueAfterToolCall() → LLM 生成最终回复
```

---

## 五、自动化模块深度设计

### 5.1 核心问题：自动化能否达到预期？

**可以。** 但必须从当前的"固定管道"模式升级为 **"LLM 驱动的 Plan-Execute 循环"**。

当前 v1 的问题：
```
SEARCH_AND_ANSWER: 搜索 → 提取 → 合成（硬编码 3 步）
KNOWLEDGE_UPDATE:   搜索 → 提取 → 入库（硬编码 3 步）
DAILY_BRIEF:        搜索 → 摘要 → 通知（硬编码 3 步）
```

v2 的目标：
```
用户: "每天早上搜索AI新闻和芯片新闻，对比两家观点，总结5条交叉验证的要点"
  │
  ▼
LLM 生成 Plan:
  steps:
    1. [并行] search("AI 新闻最新进展")    ─┐
    2. [并行] search("芯片行业最新动态")    ─┤ 同时执行
                                           │
    3. extractContent(step1.topUrls)       ─┤ 抓取内容
    4. extractContent(step2.topUrls)       ─┘
    
    5. llm_synthesize("对比AI和芯片领域的交叉趋势，输出5条交叉验证要点")
    6. push_notification("今日简报已生成")
```

### 5.2 AutomationModule 详细设计

```kotlin
/**
 * 自动化模块 v2 — LLM 驱动的 Plan-Execute 引擎。
 *
 * 对标 Claude Code 的 Plan-Execute 循环 + OpenCode 的 pipeline/parallel 编排。
 *
 * 执行模式：
 * - SCHEDULED:  定时触发（AlarmManager + 心跳检查）
 * - MANUAL:     用户手动触发
 * - CONDITIONAL: 条件触发（如"充电时"、"连接WiFi时"）— Phase 9+
 */
class AutomationModule : ZeroModule<AutomationConfig>() {

    // ═══════════════════════════════════════════════
    // FC 函数（LLM 可调用的自动化管理接口）
    // ═══════════════════════════════════════════════

    override fun registerFunctions(registry: FunctionRegistry) {
        // 1. 创建任务
        registry.register(FunctionDef(
            name = "create_automation",
            description = "创建自动化任务。用自然语言描述需要定期做什么。",
            params = [
                Param("description", "string", "自然语言描述，如'每天早上9点搜索AI新闻，整理5条要点'"),
                Param("schedule", "string", "执行时间: 自然语言 / cron / ISO-8601"),
                Param("is_one_shot", "boolean", "是否一次性任务")
            ]
        )) { args -> createAutomation(args) }

        // 2. 列出任务
        registry.register(FunctionDef(
            name = "list_automations", description = "列出所有自动化任务。"
        )) { listAutomations() }

        // 3. 暂停/恢复/删除
        registry.register(FunctionDef(
            name = "manage_automation",
            description = "管理自动化任务：暂停、恢复、删除。",
            params = [
                Param("task_id", "string", "任务ID"),
                Param("action", "string", "pause/resume/delete")
            ]
        )) { args -> manageAutomation(args) }

        // 4. 立即执行
        registry.register(FunctionDef(
            name = "run_automation_now",
            description = "立即执行指定的自动化任务。",
            params = [Param("task_id", "string", "任务ID")]
        )) { args -> executeNow(args["task_id"] as? String ?: "") }

        // 5. 查看结果
        registry.register(FunctionDef(
            name = "get_automation_results",
            description = "查询自动化任务的执行历史和结果。",
            params = [
                Param("task_id", "string", "任务ID"),
                Param("limit", "number", "返回条数，默认 5")
            ]
        )) { args -> getResults(args) }
    }

    // ═══════════════════════════════════════════════
    // Plan-Execute 引擎
    // ═══════════════════════════════════════════════

    /**
     * 执行自动化任务。
     *
     * 对标 Claude Code 的 Plan-Execute 循环：
     * 1. 解析任务 → 生成 Plan（LLM 自主分解步骤）
     * 2. 逐个执行步骤（支持并行步骤）
     * 3. 步骤间可依据结果动态调整后续步骤
     * 4. 最终合成 + 持久化
     */
    suspend fun executeNow(automationId: String): ExecutionResult {
        val task = automationDao.getById(automationId) ?: return ExecutionResult.NotFound

        // ── 守护检查 ──
        val guard = ExecutionGuard(
            maxSteps = config.maxSteps,
            maxTokensPerRun = config.maxTokensPerRun,
            maxDurationMs = config.maxDurationMinutes * 60_000L,
            requireNetwork = config.requireNetwork,
            allowedTimeRange = config.allowedTimeRange
        )
        if (!guard.canExecute(task)) return ExecutionResult.GuardBlocked(guard.reason)

        // ── 创建运行记录 ──
        val run = createRunRecord(task)
        var totalTokens = 0L

        try {
            // ── 步骤 1: LLM 生成执行计划 ──
            emitProgress(run, "📋 正在规划执行步骤...")
            val plan = generatePlan(task)
            guard.enforceStepLimit(plan.steps.size)

            // ── 步骤 2: 执行计划 ──
            val results = mutableListOf<StepResult>()
            for ((index, step) in plan.steps.withIndex()) {
                guard.checkTimeout()       // 超时检查
                guard.checkTokens(totalTokens) // Token 检查

                emitProgress(run, "🔧 [${index+1}/${plan.steps.size}] ${step.description}")

                val result = when (step.executionMode) {
                    StepMode.SEQUENTIAL -> executeStep(step)
                    StepMode.PARALLEL   -> executeParallelSteps(step.subSteps)
                }

                results.add(result)
                totalTokens += result.tokensUsed

                if (result.failed && !step.allowFailure) {
                    throw StepFailedException(step.id, result.error)
                }

                // 动态调整：如果结果提示需要额外步骤
                if (result.suggestsMoreSteps && index == plan.steps.lastIndex) {
                    // LLM 基于当前结果重新规划下一步
                }
            }

            // ── 步骤 3: LLM 合成最终结果 ──
            emitProgress(run, "📊 正在合成结果...")
            val summary = synthesizeResults(task, results)
            totalTokens += summary.tokensUsed

            // ── 步骤 4: 持久化 ──
            emitProgress(run, "💾 正在保存...")
            persistResult(task, run, summary, results)

            // ── 步骤 5: 通知 ──
            if (task.notifyOnComplete) {
                sendNotification(task, summary)
            }

            return ExecutionResult.Success(run.id, summary.text, totalTokens)

        } catch (e: Exception) {
            handleFailure(run, e)
            return ExecutionResult.Failure(run.id, e.message ?: "未知错误")
        }
    }

    /**
     * LLM 生成执行计划。
     *
     * 对标 Claude Code 的 Planning 阶段 — LLM 将自然语言描述
     * 分解为具体的可执行步骤。
     */
    private suspend fun generatePlan(task: AutomationEntity): ExecutionPlan {
        val prompt = """
将以下任务分解为可执行的具体步骤。每个步骤应为：
- "web_search": 搜索互联网
- "llm_synthesize": LLM 合成/分析
- "push_notification": 推送通知
- "read_notifications": 读取通知
- "get_screen_time": 获取使用量

任务: ${task.description}
执行时间: ${task.scheduleDescription}

返回 JSON:
{
  "steps": [
    {
      "id": "唯一ID",
      "description": "步骤描述",
      "action": "web_search|llm_synthesize|...",
      "params": {"具体参数"},
      "executionMode": "sequential|parallel",
      "subSteps": [...],    // executionMode=parallel 时
      "allowFailure": false
    }
  ]
}
        """.trimIndent()

        val response = llmClient.chatSync(prompt)
        return parsePlanJson(response)
    }

    /**
     * 执行单个步骤。
     *
     * 步骤类型映射到已注册的 FC 函数：
     *   web_search → FunctionRegistry.execute("web_search", params)
     *   llm_synthesize → LLMClientModule.chatSync(prompt)
     */
    private suspend fun executeStep(step: PlanStep): StepResult {
        return when (step.action) {
            "web_search" -> {
                val result = functionRegistry.execute("web_search", step.params)
                StepResult.fromFunctionResult(step.id, result)
            }
            "llm_synthesize" -> {
                val text = llmClient.chatSync(step.params["prompt"] as? String ?: "")
                StepResult.fromLLM(step.id, text)
            }
            "push_notification" -> {
                val result = functionRegistry.execute("push_notification", step.params)
                StepResult.fromFunctionResult(step.id, result)
            }
            else -> StepResult.failed(step.id, "未知步骤类型: ${step.action}")
        }
    }

    /**
     * 并行执行子步骤（对标 OpenCode pipeline/parallel 模式）。
     *
     * Alife 的 Reasonix 通过 Claude Code CLI 的 task 工具实现并行。
     * 我们通过 Kotlin 协程 async/await 实现。
     */
    private suspend fun executeParallelSteps(subSteps: List<PlanStep>): StepResult {
        val results = subSteps.map { step ->
            coroutineScope { async { executeStep(step) } }
        }.awaitAll()
        return StepResult.merged(results)
    }

    /**
     * LLM 合成所有步骤结果为最终摘要。
     */
    private suspend fun synthesizeResults(
        task: AutomationEntity,
        results: List<StepResult>
    ): SynthesizeResult {
        val prompt = """
基于以下自动化任务的执行结果，生成一份简洁的摘要（≤500字）。

任务: ${task.title}
${task.description}

执行结果:
${results.joinToString("\n---\n") { "${it.stepId}: ${it.summary}" }}

生成摘要，包含：
1. 任务完成情况
2. 关键发现
3. 建议的后续动作
        """.trimIndent()

        val text = llmClient.chatSync(prompt)
        return SynthesizeResult(text, estimateTokens(prompt + text))
    }
}

/**
 * 执行守护 — 对标 Claude Code 的权限/成本限制。
 *
 * v2.1 增强：
 * - maxReplanCount: 最大重规划次数（默认 2），防止 LLM 无限建议新步骤
 * - remainingTokens(): Token 预算剩余查询
 * - checkTokens() 改为每次步骤后检查
 */
class ExecutionGuard(
    val maxSteps: Int = 10,
    val maxReplanCount: Int = 2,            // v2.1: 重规划上限
    val maxTokensPerRun: Long = 50_000,
    val maxDurationMs: Long = 300_000,
    val requireNetwork: Boolean = true,
    val allowedTimeRange: Pair<Int, Int>? = null
) {
    private var stepsExecuted = 0
    private var replanCount = 0
    private var startTime = 0L
    private var tokensUsed = 0L

    fun canExecute(task: AutomationEntity): Boolean { ... }
    fun enforceStepLimit(plannedSteps: Int) { ... }
    fun checkTimeout() { ... }
    fun checkTokens(used: Long) { tokensUsed = used }
    fun remainingTokens(): Long = maxTokensPerRun - tokensUsed

    fun canReplan(): Boolean {
        replanCount++
        return replanCount <= maxReplanCount
    }
}

/**
 * v2.1: JSON 健壮提取器。
 *
 * LLM 返回的 JSON 可能有 Markdown 包装、解释性前缀、尾部注释。
 * 分层提取策略确保容错。
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
```

### 5.4 与 Claude Code / OpenCode 的对照

| 特性 | Claude Code | OpenCode | Zero v2 AutomationModule |
|------|-----------|----------|-------------------------|
| 任务分解 | LLM 自主 | Workflow 脚本 | `generatePlan()` LLM 分解 |
| 并行执行 | `task` 工具 | `parallel()` | `executeParallelSteps()` coroutine async |
| 进度推送 | `stream-json` | agent 通知 | `PokeEvent` → 对话流 |
| 成本守卫 | `--max-turns` + $5 硬上限 | `budget.remaining()` | `ExecutionGuard` |
| 权限控制 | `--permission-mode` | hook | `allowedTimeRange` + 步骤上限 |
| 结果持久化 | `--output-format stream-json` | agent 返回 | ConversationEntity + MessageEntity |
| 失败恢复 | resume session | resumeFromRunId | 检测 isOneShot 标记 + 重试逻辑 |

---

## 六、修订后的开发阶段

| Phase | 内容 | 新增文件 | 行数 | 依赖 |
|-------|------|---------|------|------|
| **P1** | 项目骨架 + IZeroModule + ChatBus + MessagePipeline | 15 | ~800 | — |
| **P2** | ChatBotModule + MessageFilterModule + LLMClient + ContextBuilder | 6 | ~700 | P1 |
| **P3** | FunctionRegistryModule + FC 注册/分发/调用日志 | 4 | ~500 | P1 |
| **P4** | MemoryModule + MemoryManager（从 v1 移植） | 3 | ~500 | P1, P3 |
| **P5** | WebSearchModule + VisionModule + ContentExtractor | 5 | ~600 | P3 |
| **P6** | SystemEventModule（心跳+提醒+记事本）+ BackgroundService | 6 | ~1300 | P3 |
| **P7** | AutomationModule（Plan-Execute + ExecutionGuard） | 3 | ~500 | P3, P5, P6 |
| **P8** | UI 层（屏幕 + ViewModel + AutoForm + 导航） | 12 | ~1200 | P2-P7 |
| **P9** | NotificationModule + UsageStatsModule + CalendarModule | 5 | ~500 | P8 |
| **P10** | 打磨（电池优化、权限引导、崩溃恢复、QuickJS 预留） | 3 | ~300 | P1-P9 |

**总估**：62 文件，约 6900 行

---

## 七、模块函数清单（完整版）

### Core 模块

| 模块 | 公开函数 | 类型 | 说明 |
|------|---------|------|------|
| **ChatBotModule** | `sendMessage()` | API | 对话主入口 |
| | `regenerate()` | API | 重新生成 |
| | `clearConversation()` | API | 新建对话 |
| | `setSystemPrompt()` | API | 切换角色 |
| **MessageFilterModule** | `process()` | Filter | 时间注入 + 截断 + 提示重注入 |
| **MemoryModule** | `memory_remember` | FC | 记住信息（v2.1: Mutex 保护写入） |
| | `memory_recall` | FC | 搜索记忆 (v2.2: FTS5+Vector+RRF 混合检索) |
| | `memory_forget` | FC | 删除记忆 |
| | `semanticSearch()` | API | 混合检索（内部） |
| | `hybridSearch()` | API | v2.2: FTS5 BM25 + Vector + RRF 融合 |
| | `intelligentMemorize()` | API | 智能记忆化（v2.1: withWriteLock） |
| **FunctionRegistryModule** | `getAllDefinitions()` | API | 生成 LLM tools 数组 |
| | `execute()` | API | 执行 FC 函数 (v2.2: 无依赖并行) |
| | `executeParallel()` | API | v2.2: 并发执行多个独立函数 |
| | `register()` | API | 注册函数（含 securityLevel） |
| **ContextBuilderModule** | `buildSystemPrompt()` | API | 组装完整 System Prompt (v2.2: TokenBudgetAllocator) |
| | `buildContextExtension()` | API | 组装本轮上下文 |
| | `allocateBudget()` | API | v2.2: Token 配额分配 + 超预算自动压缩 |
| **LLMClientModule** | `chatStream()` | API | 流式 LLM 调用 |
| | `chatSync()` | API | 同步 LLM 调用（规划/总结用） |

### Instrument 模块

| 模块 | 公开函数 | 类型 | 说明 |
|------|---------|------|------|
| **WebSearchModule** | `web_search` | FC | 搜索 + 深度抓取 |
| | `search()` | API | 搜索（内部） |
| **VisionModule** | `vision_analyze` | FC | 视觉分析 |
| | `analyzeImagePoke()` | API | Poke 快速路径 |
| **ContentExtractor** | `extract()` | API | URL → Readability 提纯 → Markdown (v2.2) |
| | `extractBatch()` | API | 批量抓取 (Timeout 5s, MaxSize 2MB) |
| | `fallbackSearch()` | API | v2.2: 降级管道 |

### System 模块

| 模块 | 公开函数 | 类型 | 说明 |
|------|---------|------|------|
| **SystemEventModule** | `set_reminder` | FC | 设置提醒（+5m/+1h/+30s） |
| | `list_reminders` | FC | 列出提醒 |
| | `cancel_reminder` | FC | 取消提醒 |
| | `next_tick` | FC | 动态报点（3-300s） |
| | `read_notepad` | FC | 读记事本 |
| | `write_notepad` | FC | 写记事本 |
| | `clear_notepad` | FC | 清空记事本 |
| | `archive_notepad` | FC | 归档记事本 |
| | `onTick()` | ITimedModule | 心跳检查（v2.1: 发布 OnScheduleTriggered 事件，不直接调用 ATM） |
| **NotificationModule** | `read_notifications` | FC | 读取通知 |
| | `push_notification` | FC | 推送通知 |
| **UsageStatsModule** | `get_screen_time` | FC | 使用时长 |
| | `get_app_usage` | FC | 应用使用详情 |
| **BackgroundServiceMod** | `startHeartbeat()` | API | 启动心跳（轻量检查 ≤5s） |
| | `stopHeartbeat()` | API | 停止心跳 |
| | `enqueueAutomation()` | API | v2.1: 将自动化任务入队 WorkManager |
| **AutomationWorker** | `doWork()` | Worker | v2.1: WorkManager CoroutineWorker 执行重量任务 |

### Automation 模块

| 模块 | 公开函数 | 类型 | 说明 |
|------|---------|------|------|
| **AutomationModule** | `create_automation` | FC | 创建任务 |
| | `list_automations` | FC | 列出任务 |
| | `manage_automation` | FC | 管理（暂停/恢复/删除） |
| | `run_automation_now` | FC | 立即执行 |
| | `get_automation_results` | FC | 查询执行结果 |
| | `executeNow()` | API | 执行引擎入口 |
| | `generatePlan()` | API | LLM 分解步骤 |
| | `executeStep()` | API | 执行单步 |
| | `executeParallelSteps()` | API | 并行执行 |
| | `synthesizeResults()` | API | LLM 合成摘要 |

**总计**：14 个模块，32 个 FC 函数，20 个内部 API 函数

---

## 八、v2.1 安全增强详解

### 8.1 Memory 写入互斥锁

防止后台自动化与前台对话并发写入 Room 触发 `SQLite database is locked`：

```kotlin
class MemoryModule : ZeroModule<MemoryConfig>() {
    private val writeMutex = Mutex()

    private suspend fun <T> withWriteLock(block: suspend () -> T): T {
        return writeMutex.withLock { block() }
    }

    // onChatFinished 触发（来自对话线程）
    private suspend fun intelligentMemorize(userMsg: String, aiMsg: String) {
        withWriteLock {
            if (shouldMemorize(userMsg))
                memoryManager.memorize("用户: $userMsg", ...)
            if (shouldMemorize(aiMsg))
                memoryManager.memorize("AI: ${aiMsg.take(500)}", ...)
        }
    }

    // FC 函数（可能来自自动化 WorkManager 线程）
    private suspend fun remember(args: Map<String, Any?>): FunctionResult {
        return withWriteLock {
            memoryManager.memorize(args["content"] as String, ...)
            FunctionResult.success("已记住")
        }
    }
}

// MemoryDao 事务保护
@Dao
interface MemoryDao {
    @Transaction
    suspend fun insertWithTx(memory: MemoryEntity) {
        insert(memory)
        if (getByLevel(0).size >= L0_THRESHOLD)
            compressLevel(0)
    }
}
```

### 8.2 AutomationWorker — WorkManager 重量执行

```kotlin
@HiltWorker
class AutomationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val automationModule: AutomationModule,
    private val logManager: LogManager
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val automationId = inputData.getString(KEY_AUTOMATION_ID) ?: return Result.failure()
        logManager.info("AutomationWorker", "开始执行: $automationId")

        return try {
            val result = automationModule.executeNow(automationId)
            when (result) {
                is ExecutionResult.Success -> Result.success()
                is ExecutionResult.Failure -> if (result.retryable) Result.retry() else Result.failure()
            }
        } catch (e: Exception) {
            logManager.error("AutomationWorker", "执行异常: $automationId", e)
            Result.retry()  // WorkManager BackoffPolicy 自动控制重试间隔
        }
    }

    companion object {
        const val KEY_AUTOMATION_ID = "automation_id"

        fun enqueue(context: Context, automationId: String, delayMs: Long = 0L) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<AutomationWorker>()
                .setInputData(Data.Builder().putString(KEY_AUTOMATION_ID, automationId).build())
                .setConstraints(constraints)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "automation_$automationId",
                    ExistingWorkPolicy.REPLACE,
                    request
                )
        }
    }
}
```

### 8.3 后台执行三层分层总结

| 层级 | 机制 | 职责 | 执行内容 |
|------|------|------|---------|
| Layer 1 | 前台 Service 心跳 | 轻量检查（≤5s，withTimeout 保护） | 检查提醒、检查调度时间、发布 OnScheduleTriggered 事件 |
| Layer 2 | WorkManager CoroutineWorker | 重量执行 | executeNow()：LLM 调用、网络搜索、内容抓取 |
| Layer 3 | AlarmManager.setExactAndAllowWhileIdle | 系统级唤醒 | 15 分钟兜底唤醒，检查逾期任务并启动 WorkManager |

**安全约束**：
- Layer 1 永不直接调用网络/LLM/DB 写入
- Layer 2 自带网络约束 + 失败重试 + 系统 kill 后自动恢复
- Layer 3 不受 Doze 模式影响，确保长时间间隔任务不丢失

---

## 九、v2.2 生产级增强详解

### 9.1 Token Budget Allocator（P0 — ContextBuilder 集成）

**问题**：System Prompt、角色人设、记忆、事实、多轮对话历史无限制累积，超出 Context Window 或耗尽 API 预算。

**方案**：在 `ContextBuilderModule` 中内建 `TokenBudgetAllocator`。

```kotlin
class TokenBudgetAllocator(
    val contextWindow: Int = 256_000,  // qwen3.8-max
) {
    data class Budget(
        val systemAndPersona: Int,   // 15% = 38,400 tokens
        val toolsDefinition: Int,    // 15% = 38,400 tokens
        val ragContext: Int,         // 20% = 51,200 tokens
        val conversationHistory: Int // 50% = 128,000 tokens
    )

    fun allocate(contextWindow: Int): Budget = Budget(
        systemAndPersona  = (contextWindow * 0.15).toInt(),
        toolsDefinition   = (contextWindow * 0.15).toInt(),
        ragContext        = (contextWindow * 0.20).toInt(),
        conversationHistory = (contextWindow * 0.50).toInt()
    )

    /**
     * 实时 Token 计数（基于 DashScope API 返回的 usage.prompt_tokens 校准）。
     * 当历史对话超过 budget.conversationHistory 的 90% 时，触发压缩。
     */
    fun needsCompression(currentHistoryTokens: Int, budget: Budget): Boolean {
        return currentHistoryTokens > budget.conversationHistory * 0.9
    }
}
```

**ContextBuilder 组装逻辑**：

```kotlin
fun buildSystemPrompt(persona: String, tools: List<FunctionDefinition>,
                      rag: String?, history: List<ChatMessage>): String {
    val budget = allocator.allocate(model.contextWindow)

    // 1. System Prompt + Persona（裁切到预算）
    val systemBlock = (persona).takeApproxTokens(budget.systemAndPersona)

    // 2. Tools 定义（超过预算时只保留核心工具描述）
    val toolsBlock = if (estimateTokens(tools) > budget.toolsDefinition) {
        tools.map { "- ${it.name}: ${it.description.take(80)}" }  // 精简版
    } else {
        tools.map { "- ${it.name}: ${it.description}" }
    }.joinToString("\n")

    // 3. RAG 上下文（截断到预算）
    val ragBlock = rag?.takeApproxTokens(budget.ragContext)

    // 4. 对话历史（超过 90% 预算 → 压缩最旧的 N 轮为摘要）
    val historyTokens = estimateTokens(history)
    val historyBlock = if (allocator.needsCompression(historyTokens, budget)) {
        compressOldestTurns(history, budget.conversationHistory)
    } else {
        history
    }

    return buildString { ... }
}

// 滑动窗口 + 摘要压缩
private fun compressOldestTurns(history: List<ChatMessage>, budget: Int): String {
    // 保留最近 10 轮完整对话
    val recent = history.takeLast(10)
    val old = history.dropLast(10)
    // 对旧对话调用 LLM 压缩为摘要段落
    val summary = historyCompressor.compress(old, maxTokens = budget / 2)
    return "[历史对话摘要]\n$summary\n\n---\n${recent.formatMessages()}"
}
```

**Token 实时校准**：每次 API 返回 `usage.prompt_tokens` 时更新计数器。当 `finish_reason == "length"` 时，标记"上下文超限"，下一轮自动触发历史压缩。

---

### 9.2 并发 Function Calling（P1 — FunctionRegistry 集成）

**问题**：LLM 返回 3 个 `web_search` 调用，串行执行耗时 3T。

**方案**：同批次 ToolCall 默认并行，显式依赖通过 `depends_on` 声明。

```kotlin
// FunctionRegistryModule
suspend fun executeBatch(toolCalls: List<ToolCall>): List<FunctionResult> {
    // 分离独立调用和有依赖的调用
    val (independent, dependent) = toolCalls.partition { it.dependsOn.isEmpty() }

    // 所有独立调用并行执行
    val independentResults = coroutineScope {
        independent.map { tc ->
            async { execute(tc.name, tc.arguments) }
        }.awaitAll()
    }

    // 有依赖的调用在依赖完成后串行执行
    val dependentResults = dependent.map { tc ->
        execute(tc.name, tc.arguments)
    }

    return independentResults + dependentResults
}
```

**依赖声明**：

```kotlin
// FunctionDefinition 扩展
data class FunctionDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, Any?>,
    val dependsOn: List<String> = emptyList(),  // v2.2: 显式依赖声明
    val securityLevel: FunctionSecurity = FunctionSecurity.SAFE
)
```

**AI 使用示例**（LLM 理解依赖关系后自动排序）：
```
# 独立调用3个搜索 → 并行
tool_calls: [
  {name: "web_search", arguments: {query: "AI新闻"}},      // 无依赖
  {name: "web_search", arguments: {query: "芯片进展"}},      // 无依赖
  {name: "web_search", arguments: {query: "机器人突破"}}     // 无依赖
]
→ 3个并行 async → 耗时 max(T1,T2,T3) ≈ T

# 有依赖的调用 → 串行
tool_calls: [
  {name: "web_search", arguments: {query: "PDF工具"}},
  {name: "llm_synthesize", dependsOn: ["web_search"], arguments: {...}}
]
→ 先执行 search → 完成 → 再执行 synthesize
```

---

### 9.3 沙箱安全拦截器（P1 — FunctionRegistry 集成）

**问题**：LLM 幻觉可能触发 `clear_notepad`、`push_notification` 等高危操作。

**方案**：FC 函数分为 SAFE / SENSITIVE，SENSITIVE 操作弹窗确认。

```kotlin
enum class FunctionSecurity {
    SAFE,       // 只读: web_search, memory_recall, read_notepad, get_screen_time, vision_analyze
    SENSITIVE   // 写入/推送: memory_remember, memory_forget, write_notepad, clear_notepad,
                //   archive_notepad, push_notification, set_reminder, cancel_reminder,
                //   cancel_all_reminders, execute_task, create_automation
}

// FunctionRegistry 注册时声明安全级别
registry.register(
    FunctionDef(
        name = "clear_notepad",
        securityLevel = FunctionSecurity.SENSITIVE,
        ...
    ),
    handler = { ... }
)

// 执行 SENSITIVE 时的确认流程
suspend fun executeSecure(name: String, args: Map<String, Any?>): FunctionResult {
    val def = getDefinition(name)

    if (def.securityLevel == FunctionSecurity.SENSITIVE) {
        // 发出确认请求 → UI 弹窗
        val event = SecurityConfirmRequest(
            functionName = name,
            description = "AI 请求执行: ${def.description}",
            params = args
        )
        chatBus.onSecurityConfirmRequest.emit(event)

        // 等待用户响应（超时 30s → 自动拒绝）
        val response = withTimeoutOrNull(30_000L) {
            securityConfirmFlow.first { it.requestId == event.requestId }
        } ?: return FunctionResult.rejected("用户未响应，操作已自动拒绝")

        if (!response.approved) {
            return FunctionResult.rejected("用户拒绝了此操作")
        }
    }

    return execute(name, args)
}
```

**安全分配矩阵**：

| 函数 | 安全级别 | 确认条件 |
|------|---------|---------|
| `web_search` | SAFE | 无 |
| `memory_recall` | SAFE | 无 |
| `read_notepad` | SAFE | 无 |
| `get_screen_time` | SAFE | 无 |
| `vision_analyze` | SAFE | 无 |
| **`memory_remember`** | **SENSITIVE** | 弹窗确认 |
| **`write_notepad`** | **SENSITIVE** | 弹窗确认 |
| **`clear_notepad`** | **SENSITIVE** | 弹窗确认 + 红色警告 |
| **`push_notification`** | **SENSITIVE** | 弹窗确认 |
| **`set_reminder`** | **SENSITIVE** | 弹窗确认 |
| **`create_automation`** | **SENSITIVE** | 弹窗确认 |

---

### 9.4 PriorityQueue 唤醒任务队列（P1 — SystemEventModule）

**问题**：`List<ReminderEntry>` 每次 `onTick()` 遍历全部，O(n)。

**方案**：`PriorityQueue<SystemTickTask>` + 三重唤醒源。

```kotlin
class SystemEventModule : ZeroModule<SystemEventConfig>(), ITimedModule {
    // v2.2: PriorityQueue 替代 List
    private val taskQueue = PriorityQueue<SystemTickTask>(
        compareBy { it.triggerTimeMs }
    )

    override suspend fun onTick(tickMs: Long) {
        // O(log n) 取出 + O(1) 检查队首
        while (taskQueue.isNotEmpty() && taskQueue.peek().triggerTimeMs <= tickMs) {
            val task = taskQueue.poll()
            executeTask(task)  // 执行后可能重新入队（循环任务）
        }
    }

    private fun executeTask(task: SystemTickTask) {
        when (task.type) {
            TaskType.REMINDER -> {
                poke("⏰ 提醒 [${task.id}]: ${task.remark}")
                if (task.isRecurring) {
                    task.triggerTimeMs = now() + task.recurringMs
                    taskQueue.offer(task)  // 循环任务重新入队
                }
            }
            TaskType.DYNAMIC_TICK -> {
                val notepad = readNotepad()
                poke("动态报点唤醒。\n$notepad\n${config.updatePrompt}")
                continuousTimerCount = 0
                nextAutoTick = Long.MAX_VALUE
            }
            TaskType.AUTO_TICK -> {
                poke("系统周期报点。${config.updatePrompt}")
                continuousTimerCount++
                scheduleNextAutoTick()
            }
            TaskType.AUTOMATION -> {
                // 发布事件而非直接调用
                systemBus.emit(OnScheduleTriggered(task.automationId!!, HEARTBEAT))
            }
        }
    }
}
```

**三重唤醒源**：

```kotlin
fun scheduleNextWakeup() {
    val nextTask = taskQueue.peek() ?: return

    // 唤醒源 1: AlarmManager 精确闹钟（毫秒级）
    val alarmManager = context.getSystemService(ALARM_SERVICE) as AlarmManager
    alarmManager.setExactAndAllowWhileIdle(
        AlarmManager.RTC_WAKEUP,
        nextTask.triggerTimeMs,
        wakeupPendingIntent
    )

    // 唤醒源 2: WorkManager PeriodicWork（15分钟兜底）
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "heartbeat_keepalive",
        ExistingPeriodicWorkPolicy.KEEP,
        PeriodicWorkRequestBuilder<HeartbeatWorker>(15, TimeUnit.MINUTES).build()
    )

    // 唤醒源 3: 前台 Service 心跳（前台锁定时的秒级响应）
}
```

**Notepad WAL 持久化**：

```kotlin
// 对标 Alife 的 Markdown 文件存储 → 升级为 Room WAL 表
@Entity(tableName = "task_notepad")
data class NotepadEntity(
    @PrimaryKey val id: Int = 1,  // 单例
    val content: String,
    val updatedAt: Long
)

@Dao
interface NotepadDao {
    @Query("SELECT * FROM task_notepad WHERE id = 1")
    suspend fun get(): NotepadEntity?

    @Transaction  // 原子写入
    @Insert(onConflict = REPLACE)
    suspend fun save(entry: NotepadEntity)
}
```

---

### 9.5 ContentExtractor — Readability 提纯（P1）

**问题**：普通 Jsoup 提取保留大量噪声（导航栏、页脚、广告）。

**方案**：基于文本密度 + 标签层级剪枝的 Readability 算法。

```kotlin
class ContentExtractor {
    companion object {
        const val TIMEOUT_MS = 5_000L
        const val MAX_HTML_SIZE = 2_000_000L  // 2MB
        const val MAX_REDIRECTS = 3
        const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Mobile"
    }

    suspend fun extract(url: String, maxChars: Int = 8000): ExtractedContent {
        // Step 1: 获取 HTML（超时 + 大小限制 + 重定向限制）
        val html = fetchWithGuard(url)

        // Step 2: Jsoup 解析
        val doc = Jsoup.parse(html)

        // Step 3: Readability 提纯 — 文本密度算法
        //   - 移除 script, style, nav, footer, aside, iframe, [role=ad]
        //   - 计算每个 <article>/<main>/<section>/<div>/<p> 的文本密度 (text/chars)
        //   - 保留密度 > 0.25 的节点
        //   - 移除链接-文本比 > 0.5 的节点（导航/链接列表）
        val cleaned = readabilityExtract(doc)

        // Step 4: 转换为 Markdown
        return ExtractedContent(
            url = url,
            title = doc.title(),
            markdown = toMarkdown(cleaned).take(maxChars),
            charCount = cleaned.text().length,
            publishDate = extractDate(doc)
        )
    }

    private fun readabilityExtract(doc: Document): Element {
        // 对标 Mozilla Readability.js
        // 1. 移除噪声标签
        doc.select("script, style, nav, footer, aside, iframe, " +
                   "[role=ad], [aria-hidden=true], .ad, .sidebar, .nav").remove()
        // 2. 找到主内容容器（article > main > [id=content] > body）
        val mainContent = doc.select("article, main, [id*=content], [class*=article]")
            .firstOrNull() ?: doc.body()
        // 3. 文本密度剪枝
        pruneByTextDensity(mainContent, minDensity = 0.25, maxLinkRatio = 0.5)
        return mainContent
    }

    /**
     * 降级管道 (v2.2)
     *   优先: LLM 自带搜索 (DashScope enable_search)
     *   → 降级1: ContentExtractor (Readability)
     *   → 降级2: 仅返回标题+URL
     */
    suspend fun searchWithFallback(query: String): FunctionResult { ... }
}
```

---

### 9.6 MemoryModule — 混合检索 FTS5 + Vector + RRF（P2, Phase 5+）

**问题**：纯向量检索对精确关键词（人名、代码、数字）敏感度差。

**方案**：SQLite FTS5 全文检索 + 向量相似度 + Reciprocal Rank Fusion 融合。

```kotlin
class MemoryModule {
    /**
     * v2.2 混合检索：FTS5 BM25 + Vector Cosine + RRF 融合排序。
     * Android 内置 SQLite 支持 FTS5，零额外依赖。
     */
    suspend fun hybridSearch(query: String, topK: Int = 10): List<MemoryEntity> {
        // 1. FTS5 全文检索（精确关键词匹配）
        val ftsResults = memoryDao.ftsSearch(query, limit = topK * 2)
        val ftsScores = ftsResults.associate { it.id to it.bm25Score }

        // 2. 向量语义检索
        val vectorResults = memoryDao.vectorSearch(
            embedding = generateEmbedding(query)!!,
            limit = topK * 2,
            minSimilarity = 0.3f
        )
        val vectorScores = vectorResults.associate { it.id to it.cosineSimilarity }

        // 3. RRF 融合
        val allIds = (ftsScores.keys + vectorScores.keys).toSet()
        return allIds.map { id ->
            val ftsRank = ftsResults.indexOfFirst { it.id == id } + 1
            val vecRank = vectorResults.indexOfFirst { it.id == id } + 1
            // RRF 公式: score = 1/(k+rank_fts) + 1/(k+rank_vec), k=60
            val rrfScore = 1.0 / (60.0 + ftsRank) + 1.0 / (60.0 + vecRank)
            id to (rrfScore * timeDecay(id) * importanceBonus(id))
        }
            .sortedByDescending { it.second }
            .take(topK)
            .map { memoryDao.getById(it.first)!! }
    }
}

// MemoryDao 新增
@Dao
interface MemoryDao {
    // FTS5 虚拟表查询
    @Query("SELECT *, bm25(memory_fts) as score FROM memory_fts " +
           "WHERE memory_fts MATCH :query ORDER BY score LIMIT :limit")
    suspend fun ftsSearch(query: String, limit: Int): List<FtsResult>
}
```

**ONNX 加速**：`bge-small-zh-v1.5` (~24MB) 通过 NNAPI/GPU Delegate 加速，目标单次 embedding ≤50ms。

---

### 9.7 AutoFormUI — 编译时解析，零反射渲染（P1, Phase 8）

**问题**：Compose 渲染路径中使用 Kotlin 反射读取注解，性能开销大。

**方案**：`onAwake()` 时一次性解析 → `List<FormFieldState>` → UI 只绑定 State。

```kotlin
// 模块 onAwake 时调用一次（非 UI 线程）
class ModuleConfigParser {
    fun parse(config: ModuleConfigBase): List<FormFieldState> {
        return config::class.memberProperties
            .filter { it.hasAnnotation<ConfigField>() }
            .map { prop ->
                val anno = prop.findAnnotation<ConfigField>()!!
                FormFieldState(
                    key = prop.name,
                    label = anno.label,
                    description = anno.description,
                    fieldType = anno.fieldType,
                    isPassword = anno.password,
                    currentValue = (prop.getter.call(config) ?: "").toString(),
                    options = anno.options.toList(),
                    min = anno.min, max = anno.max,
                    onValueChange = { newValue ->
                        // 持久化到 Room 配置表
                        prop.setter.call(config, newValue)
                        saveConfig(config)
                    }
                )
            }
    }
}

data class FormFieldState(
    val key: String,
    val label: String,
    val description: String,
    val fieldType: ConfigFieldType,
    val isPassword: Boolean,
    val currentValue: String,
    val options: List<String>,
    val min: Double, val max: Double,
    val onValueChange: (String) -> Unit
)
```

**Compose UI 只遍历 List，零反射**：
```kotlin
@Composable
fun AutoFormUI(fields: List<FormFieldState>) {
    fields.forEach { field ->
        when (field.fieldType) {
            TOGGLE  -> SwitchRow(field)
            TEXT    -> TextFieldRow(field)
            NUMBER  -> SliderRow(field)
            DROPDOWN -> DropdownRow(field)
            MULTILINE -> MultilineRow(field)
        }
    }
}
```

---

### 9.8 白盒可观测性 — AgentTrace 系统（P1, Phase 8）

**问题**：自动化失败时零调试信息，无法回溯 LLM 思考过程。

**方案**：全局 `traceId` 串联每一步操作。

```kotlin
data class AgentTrace(
    val traceId: String,           // UUID，一次对话/自动化执行 traceId 不变
    val sequence: Int,             // 递增序号
    val timestamp: Long,
    val nodeType: NodeType,        // USER_INPUT / LLM_REASONING / TOOL_CALL / TOOL_RESULT / POKE / ERROR
    val summary: String,           // 一行摘要
    val detail: String?,           // 详细信息（可折叠）
    val tokensUsed: Long?,         // 关联的 Token 消耗
    val durationMs: Long?,         // 耗时
    val parentTraceId: String?     // 嵌套（如 ToolCall 内部再调 LLM）
)

enum class NodeType {
    USER_INPUT, LLM_REASONING, TOOL_CALL_START, TOOL_CALL_RESULT,
    POKE_RECEIVED, POKE_SENT, COMPRESSION, ERROR, GUARD_CHECK
}
```

**UI "Agent 思维链 timeline" 页面**：

```
┌──────────────────────────────────────────────────────┐
│  ← 返回          Agent 思维链                        │
├──────────────────────────────────────────────────────┤
│  Trace: auto_20260810_090000 (每日AI新闻)            │
│                                                      │
│  09:00:00.100  📨 User: 执行自动化: 每日AI新闻        │
│  09:00:00.150  ⏱ MessageFilter: 注入时间戳 (0.2ms)   │
│  09:00:00.500  🧠 LLM Reasoning: "需要先搜索AI相关..."│
│  09:00:00.800  🔧 TOOL: web_search "AI新闻"          │
│  09:00:02.100  ✅ RESULT: 10条, 1.2s, 1234 tokens    │
│  09:00:02.200  🔧 TOOL: web_search "芯片"             │
│  09:00:03.500  ✅ RESULT: 8条, 1.2s, 987 tokens      │
│  09:00:04.000  🧠 LLM Reasoning: "综合两条搜索..."    │
│  09:00:05.200  💬 LLM: 今日AI新闻要点如下...         │
│  09:00:05.500  💾 Memory: 已记忆 (importance=0.3)    │
│  09:00:05.600  📢 Push: "今日简报已生成"              │
│  ──────────────────────────────────────────────────  │
│  📊 总计: 3.5s, 3456 tokens, 💰 ~¥0.02               │
└──────────────────────────────────────────────────────┘
```

**采集点**：

| 采集位置 | NodeType | 触发时机 |
|---------|---------|---------|
| `ChatBotModule.sendMessage()` | USER_INPUT | 发送前 |
| `LLMClientModule.chatStream()` | LLM_REASONING | 收到 `thinkingDelta` |
| `FunctionRegistryModule.execute()` | TOOL_CALL_START / TOOL_CALL_RESULT | 执行前后 |
| `ChatBotModule` 收到 Poke | POKE_RECEIVED | Poke 到达 |
| `MemoryModule.intelligentMemorize()` | COMPRESSION | 压缩触发 |
| `ExecutionGuard.check*()` | GUARD_CHECK | 守护检查 |
| 任何模块 `catch` 块 | ERROR | 异常捕获 |

---

### 9.9 崩溃自愈（P0 — ModuleLifecycleManager 基础设施, Phase 1）

**问题**：非 Core 模块崩溃可能拖垮整个应用。

**方案**：`ModuleLifecycleManager` 包裹所有模块调用。

```kotlin
class ModuleLifecycleManager @Inject constructor(
    @ZeroModules private val modules: Set<@JvmSuppressWildcards IZeroModule>,
    private val logManager: LogManager,
    private val chatBus: ChatBus
) {
    private val crashCount = ConcurrentHashMap<String, Int>()

    /**
     * 安全的模块调用包装。
     *
     * Core 模块（CBM, MFM, FRM, CBM, LLM）崩溃 → 记录栈 + 通知用户 + 尝试重启。
     * 非 Core 模块崩溃 → 自动标记 DISABLED + 通知用户 + 可手动重新启用。
     */
    suspend fun <T> safeCall(module: IZeroModule, operation: String,
                              block: suspend () -> T): T? {
        return try {
            block()
        } catch (e: Exception) {
            val count = crashCount.merge(module.metadata.id, 1, Int::plus) ?: 1
            logManager.error(module.metadata.id, "$operation 崩溃 (第${count}次)", e)
            agentTrace.record(ERROR, "$operation 崩溃: ${e.message}", module.metadata.id)

            if (module.metadata.category == ModuleCategory.CORE) {
                // Core 模块不自动禁用，但通知用户
                if (count >= 3) {
                    chatBus.onPokeSend.emit(PokeEvent(
                        "🚨 核心模块 ${module.metadata.displayName} 连续崩溃 $count 次。" +
                        "建议重启应用。", source = "system", priority = URGENT
                    ))
                }
            } else {
                // 非 Core 模块自动禁用
                module.markDisabled()
                chatBus.onPokeSend.emit(PokeEvent(
                    "⚠️ ${module.metadata.displayName} 模块异常，已自动禁用。" +
                    "可在设置中重新启用。", source = "system", priority = HIGH
                ))
            }
            null
        }
    }
}

// 每个模块调用示例
suspend fun initializeAll() {
    for (module in ordered) {
        safeCall(module, "onAwake") { module.onAwake() }
        safeCall(module, "onStart") { module.onStart() }
    }
}
```

---

## 十、修订后开发阶段（v2.2）

| Phase | 内容 | 新增/修改 | 行数 | 关键 v2.2 增强 |
|-------|------|---------|------|---------------|
| **P1** | 项目骨架 + IZeroModule + ChatBus + MessagePipeline + **ModuleLifecycleManager(safeCall)** + AgentTrace基础 | 16 | ~900 | 崩溃自愈(P0)、AgentTrace 采集点 |
| **P2** | ChatBot + MessageFilter + LLMClient + ContextBuilder(**TokenBudgetAllocator**) | 7 | ~800 | Token动态配额(P0) |
| **P3** | FunctionRegistry(**并发FC** + **沙箱SecurityInterceptor**) | 5 | ~650 | 并发执行(P1) + 安全拦截(P1) |
| **P4** | MemoryModule + MemoryManager(从v1移植) | 3 | ~550 | FTS5表结构预留(P2) |
| **P5** | WebSearch + Vision + **ContentExtractor(Readability + 降级管道)** | 6 | ~750 | Readability提纯(P1) |
| **P6** | SystemEventModule(**PriorityQueue** + 三重唤醒 + **Notepad WAL**) | 7 | ~1400 | 任务队列(P1) + 原子持久化 |
| **P7** | AutomationModule (Plan-Execute + ExecutionGuard v2.1) | 4 | ~550 | |
| **P8** | UI层(**AutoFormUI反优化** + **AgentTraceTimeline** + ModuleCenter + RunLog) | 14 | ~1500 | 零反射UI(P1) + 可观测性(P1) |
| **P9** | Notification + UsageStats + Calendar | 5 | ~500 | |
| **P10** | 打磨：电池优化、权限引导、混合检索(FTS5+RRF)、崩溃恢复测试 | 4 | ~400 | 混合检索(P2) |

**总估算**：71 文件，约 8000 行

---

## 十一、安全保障清单（合并 v2.1 + v2.2）

| # | 安全措施 | v | 位置 | 机制 |
|---|---------|---|------|------|
| 1 | 循环依赖 SEM→ATM | v2.1 | SystemBus | OnScheduleTriggered 事件 |
| 2 | 循环依赖 FRM→MLM | v2.1 | FunctionRegistry | 延迟查询 |
| 3 | Android 后台限制 | v2.1 | BackgroundService | Service+WorkManager+AlarmManager 三层 |
| 4 | Plan 死循环 | v2.1 | AutomationModule | maxReplanCount=2 |
| 5 | JSON 解析脆弱 | v2.1 | generatePlan() | 4层提取+降级规则引擎 |
| 6 | Pipeline 阻塞 | v2.1 | MessagePipeline | withTimeout(3s) |
| 7 | ChatBus 丢帧 | v2.1 | ChatBusImpl | DROP_OLDEST/SUSPEND 差异化 |
| 8 | Memory 写入竞态 | v2.1 | MemoryModule | Mutex+@Transaction |
| 9 | 任务崩溃 | v2.1 | AutomationWorker | WorkManager BackoffPolicy |
| 10 | **Token 超限** | **v2.2** | **ContextBuilder** | **TokenBudgetAllocator+滑动窗口压缩** |
| 11 | **FC 串行慢** | **v2.2** | **FunctionRegistry** | **coroutineScope+async 并行** |
| 12 | **LLM 幻觉误操作** | **v2.2** | **FunctionRegistry** | **SAFE/SENSITIVE+弹窗确认** |
| 13 | **唤醒扫描 O(n)** | **v2.2** | **SystemEventModule** | **PriorityQueue O(log n)** |
| 14 | **网页噪声大** | **v2.2** | **ContentExtractor** | **Readability+Timeout 5s+MaxSize 2MB** |
| 15 | **纯向量检索弱** | **v2.2** | **MemoryModule** | **FTS5+Vector+RRF (Phase 5+)** |
| 16 | **反射卡 UI** | **v2.2** | **AutoFormUI** | **onAwake 解析→List<FieldState>** |
| 17 | **调试无信息** | **v2.2** | **AgentTrace** | **traceId 串联全链路 timeline** |
| 18 | **模块崩溃拖垮系统** | **v2.2** | **ModuleLifecycleManager** | **safeCall+自动禁用+Core保护** |


### 2026-10-13 基建 B1–B5 契约修订

- `SupervisorAuthorization` 约束 loopback/配置端口并附控制凭据；凭据由受权限保护的 RUN_COMMAND 在 Termux 私有目录生成，Android仅内存持有。supervisor HTTP与有界Socket帧入口分派前鉴权，HTTP不跟随重定向，副作用请求不自动重放。Termux同UID模块仍属信任域。
- `OutputCapture`/`collect_exec` 连续读取双流；单流64KiB内存尾、2MiB原文前缀，32个近期完成文件和最多4个活动命令。返回保存量/总量/读取路径；超时使用身份校验进程树清理并反馈结果，不无限等待继承管道。
- `BudgetAccounting` 为预算累计owner，十进制与串行confirmed commit；旧分值迁移但不补造历史损失。`ModelTokenPrices` 单独持久于模型profile，USD/百万tokens，用户来源；不进入请求参数或缓存scope。预算区分已定价、未知、套餐与本地。保护判据含未知部分旧混合估算，非服务商真实账单；无价格或usage不足不按免费处理。
- `AutomationClaims` 在调度及手动共同入口按任务ID认领，重读持久状态和调度时间，finally释放；`ExecutionPlan.validationError`/解析拒绝非法DAG，运行时无就绪节点即失败。仅进程内互斥，不承诺崩溃后外部副作用exactly-once。
- `CredentialStorageGate` 禁止加密失败降级明文，错误可见且可重试。显式旧纯明文迁移先写独立加密副本、核对、再切换；不删除旧原件、不自动重建损坏密钥。现有加密存储保留原文件与引用。

详细验收及平台边界见《【收敛开发设计】缓存可靠性·基建修订·ChatGPT接入》第9节、artifacts/infrastructure-20261013/acceptance.md。不改变缓存换代90%/65%、提示词与工具清单、原文读取和原生模型状态；Room31不变。

### 2026-10-13 B1–B5复审补充
自动化计划显式编号及依赖字段严格解析，禁止类型错误被静默降级。BudgetAccounting独立noBackup缺口标记让预算写入失败在重建后仍可见，不影响故障放行。TermuxBridge Socket兜底在令牌发送前核对Termux peer UID，4秒读等待、1MiB完整帧及200状态约束；HTTP主通道不变。测试/编译和实机边界见收敛设计§9.7；本轮未安装、未云测。

### 2026-10-13 ChatGPT S1认证契约
S0已验证个人本地/Pro候选路径、官方协议及Fold3系统浏览器loopback。S1在services/chatgpt增加ChatGptOAuth（PKCE/回调/JWT）、ChatGptVault（Keystore+noBackup AtomicFile）、ChatGptAuth（账号与并发owner）和固定官方端点客户端；app模型管理挂认证卡。未完成OAuth不激活新账号；退出/切号使迟到结果失效；不改LlmProfileStore、ModelSelector、请求缓存和旧凭据存储。具体切片及证据维护于收敛设计§10。

### 2026-10-13 ChatGPT S2套餐模型
ChatGptPlanLanguageModel按显式profile接入ModelSelector，冻结账号会话与模型；按需刷新Bearer，退出/切号取消旧请求。独立ChatGptPlanProtocol固定官方Responses端点、developer输入和zero函数namespace，stream=true/store=false；原生reasoning、usage及工具续轮复用。RequestContextPreparation仅套餐按注册身份保持缓存校准，旧API仍按凭据隔离；换代策略不变。套餐同步聚合SSE、不发送max_output_tokens、不做输出预算重试；本地预留不是服务端上限。显式启用的套餐模型在授权失败时报错，不因缺凭据选中付费候选。目录仅添加停用模型，未知能力保守、用户可声明；套餐计量独立。S2本地质量门通过，真实接入S3未验。证据及边界统一见收敛设计§11。

2026-10-13 S3实机补充：官方套餐实际completed.output可为空，ChatGPT路线从output_item.done收集原生项，完整终态后释放；解析异常保留usage。实机gpt-6.1-sol文本/图片/工具续轮与账号进程恢复通过，其他型号/真实到期刷新未外推。最终补修APKf253bb…（云用例通过版本573630…）已实装，106条原观测/配置保留、临时测试包清理。详细证据artifacts/chatgpt-s3-20261013/acceptance.md；S3父门待补自然到期刷新。

### 2026-10-17 C4 本地实现与隔离验收完成
当前源码Room37/JSONv5：来源精确版本绑定、主对话工具自主分类、持久派生队列与可重建当前视图已落实。客户端只做结构、原文定位、版本、删除、时态和冲突保护，不调用副分类LLM；旧USER_FACT自动分类退役留审计，不重写历史、不补认证UNKNOWN。MODEL_CLASSIFIED保留候选标记，不进入原已认证重要前缀。当前视图同scope/claim_key结构冲突隔离，不以最新写入替代；C5再验语义与自动消费。
已修复工具旧快照协议升级/24项边界、首次冲突审计引发投影版本多跳、旧视图下迟到召回对冲突/归档的重读，以及恢复后先重建视图再回填索引/变更唤醒遗漏。工具协议显式升为contractVersion=2，仅不兼容升级重建一次；日常冻结策略、换代阈值、默认模型、预算/账本口径保持。声明升级自然改变一次前缀指纹，不声称98%缓存已实测。
最终qualityCheck/assembleDebug及应用/数据层测试APK2分54秒通过（586任务89执行）；JVM835项含2项既有跳过，零失败。模拟器核心70项调度（69通过、1项跨进程条件跳过后另3阶段通过），应用39项全部通过；原记忆/工具前缀跨真实进程恢复2阶段通过。36→37旧表逐值保留及v5→37链路、JSON状态/幂等/损坏回滚/删除防恢复、并发/取消/磁盘重开/迟到提交、归档/冲突消费守卫、65项元数据分页和假索引唤醒均通过。
所有安装/验收仅独立emulator-5580 / ZeroP27Api35；三个临时包已卸载、包清单为空，模拟器已关闭。Fold3未安装/配置修改/数据写入，未使用个人题、未云推理、无Jev。真实主对话分类/自然中文支持、跨键冲突、个人/全库/手机推理与长期门留C5/C6，P5父门不关闭。证据artifacts/p5-derivation-20261017/acceptance.md；最终APK仅留本机，未安装到手机。


### 2026-10-17 C5 安全子片与隔离验收完成，语义父门开放
独立来源资格/使用前重核、纯当前投影、完整限定召回、scope显式隔离及隐式重试不认证旧来源已落地；旧UNKNOWN仍可显式读原值，不自动作为证据。来源/哈希/类型仅说明结构与资格，不等于问题支持或日期真实。新派生自动消费接口已准备，生产入口继续关闭，避免旧自动路径绕门；不自动改历史，不启用旁路分类或Jev。
完整编译/测试APK、最终qualityCheck通过；848项JVM含2既有跳过零失败，隔离核心71项调度（70通过、1条件跳过后跨进程3阶段补齐）、最终应用49项含10新项全通过，前缀真实重启2阶段通过。三包初轮/两包复测均清理，自启模拟器与本机模型已停；手机无安装或配置/观测写入。Room37/JSONv5保持；memory_recall新增scope，工具协议3仅升级一次，之后原冻结/缓存阈值/预算保持。
本机8条新撰开发资料41次主机协议夹具复跑未过语义门，存在记录时间误填、引用反复失败及夹具/评分边界偏差；不能冒充真实自然来源/生产Agent/个人或手机通过。实际参数经生产Android工具离线重放：16次接受含重试、6次歧义引用拒绝，不认证语义。下一片先校验生产工具闭环，再预声明逐主体/否定/无支持/日期口径、冻结新真实中文来源与可靠主模型候选；通过后才自动消费。C5/C6/P5仍开放。详见artifacts/p5-main-dialogue-20261017/acceptance.md、model-review.md及持久设计§7.26。


### 7.27 C5 生产工具闭环与真实来源冻结（2026-10-17）
隔离装配使用实际ChatBotModule、DefaultToolContextSelector、FunctionRegistryModule、ModuleLifecycleManager、MemoryModule和独立Room；离线契约1/1通过，验证真实instr无命中空、保存/新范围召回、scope拒绝、完整来源、模块禁用guard及同轮stablePrefix/消息append-only，无辅助LLM。修复旧AutonomousSkillAcceptanceTest遗漏ChronicleInputStore的构造回归；不把编译当作其云技能语义验收。新MODEL_CLASSIFIED召回保持本地排序并让主对话读原文，不交给另一LLM重排；旧非派生路线不变。
推理前冻结四份真实公开行政通知（旧/调整2019劳动节、2025博物馆暑期服务、2025电力防汛）及三组12问题，金标留主机、不进入APK；日期/完整限定/明确否定/无支持/风险不确定性/跨键调整关系分别计分。用生产ProfileLanguageModel/OpenAiCompatibleClient和实际FC循环，分类与新会话回答分开，直接查来源不能冒充记忆召回复用。source_id/quote/结构资格不认证语义，多个主体可分类不同。
证据artifacts/p5-production-agent-20261017；contract-build.log完整编译4m14s，natural-build初次缺Gson后改用现有JSON，修复编译43s通过。真实模型诊断及最终质量/编译已完成，语义门未过（见本轮收尾）；35B因既有资源下限阻塞，未执行、不降资源门。本机9B开发候选不代表日常云主模型。所有模拟器测试只用隔离库，无云请求/手机安装或配置/观测写入，无个人题/Jev，历史不改写。Room37/JSONv5/工具协议3、缓存世代和稳定前缀规则保持；新派生自动上下文仍关闭，C5/C6/P5父门不关闭。



### 2026-10-17 C5生产闭环续片收尾（§7.27–7.29，语义父门仍开放）
实际生产ChatBot/发现/注册/生命周期/记忆工具/Room/完整原文闭环契约已通过；MODEL_CLASSIFIED召回不再交另一LLM重排；旧AutonomousSkillAcceptanceTest补ChronicleInputStore构造（云技能测试未执行）。真实工具轮封顶触发Qwen中途SYSTEM模板500，最小模板红/USER对照绿及实际ChatBot红绿均留证；仅将临时综合提示改为USER，不改stable systemPrompt、工具上限、预算或协议版本。旧schema验收35→37也有实际红绿证据，未改生产数据库。
根qualityCheck/assembleDebug/测试APK3m55s通过，848项JVM含2既有跳过零失败（含有效Gradle缓存）；最终应用51+schema/技能存储2+跨进程恢复两阶段=55项执行绿。恢复PID4567→4595，stablePrefix/tailCorrection/withdrawn均true。新增scripts/freeze_memory_eval.py有6项检查，最后assembleDebug15s通过。真实模型instrumentation执行OK不算语义通过。
新4份真实公开中文通知/12题、金标/提示/模型全SHA在推理前冻结；原33请求受综合500阻塞，修复同集32请求六阶段正常，system前缀各1种且append-only，辅助LLM0。保留所有失败，不用个人题调参。三组分类仅查来源却文本声称保存；后续问答保存不倒算成功。博物馆4候选将网页日期/无依据时分填为validFrom（2025-06-28 05:20），假期两候选实际同键冲突，没有跨键场景覆盖；博物馆最终JSON有字面量反斜杠n。源绑定/结构接受不能认证语义，不能据类型提升稳定记忆权限。
复盘校验评分：原文表述/是否证明可能与目标事实层级混淆，不将所有标签差异硬归模型。旧题缺显式target_proposition/asked_attribute/time_scope/judge_layer，被新增冻结目标检查拒绝；不改原金标追认通过，旧12题降为诊断/修复回归，不提供有效盲集通过率。新冻结工具强制输入/金标逐值同目标及判断WORLD_CLAIM/SOURCE_STATEMENT，唯一分母、候选参数和不可覆盖SHA清单；仍须推理前人工核对语义，不能以结构代判。
下一步先新独立真实自然来源和显式目标层级，经冻结门及人工审阅；再验可靠主对话实际工具回执、日期依据/类别/时间计算、无支持/明确否定/完整限定/当前性。跨键改为预置历史遗留不同键候选，独立于模型应复用同属性键的常规行为；不让模型制造违反键复用规则的测试。以上通过才接新派生自动turnContext，再到C6个人/全库/手机实际推理。35B因既有20GiB物理/23GiB提交门阻塞未运行，不降资源门、不替换用户进程。
65请求token/缓存均未知，覆盖0，不当0token/0%命中，不宣称98%实测。Room37/JSONv5/工具3、缓存世代阈值/账本/手机配置和观测保持；无云请求/手机安装或写入、无Jev、无自动历史改写。两临时包已卸载、包列表为空；模拟器和自有模型35600/34092已停，18086不监听，最终ADB仅手机。证据artifacts/p5-production-agent-20261017/acceptance.md、manual-review.md、cleanup-facts.json；C5/C6/P5不关闭。



### 7.30 后续测试门分层与本日停止（2026-10-17）
经用户确认，后续将工程正确性、模型适配、实际主模型语义质量、设备性能分别验收。本地9B用于开发诊断及其自身能力评估，不要求它通过全部语义题后才能推进云端主模型功能；本地候选失败不得泛化为Zero架构失败或云端主模型失败。反之，不能未经实测就假定更强云模型通过。
工程门用确定性测试验来源绑定、删除/迟到失效、迁移/备份、工具闭环及稳定前缀；适配门按具体provider/profile/协议分别验证，如本轮Qwen模板500只说明该路径兼容回归。语义门先冻结明确目标命题/属性/时间/判断层级和独立来源，再使用日常实际云端主对话模型做有界真实工具闭环；模型/参数/上下文/工具轮次/超时单独记录。不得将本地算力导致的慢、超时、轮次封顶混算为语义失败，不要求不同模型采用失真的统一时延门槛。
自动注入的批准范围绑定实际通过验收的主模型/适配配置；云端主模型通过即可推进其对应路径，不再被本地9B语义成绩卡住。未通过/未测试的候选仍明确标记，不能自动继承其他模型结论或静默切换。评分歧义必须独立修订，不通过换大模型或放宽金标掩盖；旧12题与两轮原始结果继续保留为诊断。个人来源绑定、全库规模、真实手机推理/资源与长期缓存观测仍各自验收，云端通过不替代设备或长期证据。缓存仍按实际服务商有效usage/覆盖/未知与冷/失败样本统计，不由本机前缀结构测试推断98%已达成。
下次开发顺序：独立来源与明确目标冻结/人工审阅→本地工程回归→提前告知后使用实际云端主模型有界验语义与工具执行→通过后衔接对应自动turnContext→C6设备/个人/全库等后续门。保留现有缓存设计、手机配置与观测数据，不自动改写历史，不引入Jev/旁路分类，不静默付费回退。
用户已要求今天休息：本轮仅同步文档，不再启动开发、构建、测试、安装、云请求或后台观测；本轮临时应用/模拟器/自有模型清理证据沿用已核实状态。等待用户下次明确继续后恢复工作，安装或云测试前重新告知具体范围。C5/C6/P5父门保持开放，不因停止而标完成。

