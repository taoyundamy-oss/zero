# Zero V2 自主触发与健康守护设计（审计修订版 v2）

> 记录日期：2026-08-31（初稿）· 审计修订：2026-08-31（GPT-5.6 外审 + 自审合并）
> 范围：Agent 自主行为升级——并发冲突修复 / 触发机制多元化（事件驱动） / 健康守护（屏幕使用）。
> 状态：**两轮审计完成**（第一轮 B1–B5/D1–D6 自审 + 第二轮 GPT-5.6 P0×2/P1×5/P2×6 外审），全部修订入本版。定稿待开发。
> 修订要点：**并发原子性（single-flight）**、**Receiver 生命周期（动态注册+进程保活锚）**、**TurnContext 契约（turnId/triggerId 绑定）**、**成本四层分层（TriggerCap→Budget→LLM→NotifyCap）**、**双数据源（屏幕广播/UsageStats）**。

---

## 〇、决策记录（D67–D80）

| # | 决策 | 裁定 |
|---|------|------|
| D67 | 并发冲突修复 | 自主触发逢对话中→**推迟 90s + onChatFinished 回调 5s 宽限即触发**（定时保底双保险）；绝不交错写入历史 |
| D68 | 用户打断自主回合 | **用户优先，直接打断**：GENERATING 且 source==AUTONOMOUS 时用户消息→取消自主回合（作废不重试，等下个事件）；`autonomous_interrupted_by_user` 埋点。**取消传播须贯穿 coroutine 链到 OkHttp call.cancel()（验收 P0-2）** |
| D69 | 触发机制 | **事件驱动为主**五源 + backlog 预留；详见 §三 |
| D70 | 通知触发 | 默认开 + 白名单 + 同 App 5min 去重 |
| D71 | 白名单选择器 | 三层构成（推荐预置/全量可选/基建排除），详见 §三 |
| D72 | 免打扰 | 方案 A 落地后概念整体取消（事件源全天生效） |
| D73 | 时间偏移触发 | 移除（方案 A）：完全事件驱动 + 启动 +2min 补检（**结构化 BacklogSummary**，§三）；tick 基建仅服务心跳型 AgentJob；提醒走 D40 精准闹钟，本轨不触碰 |
| D74 | 健康守护走 LLM | 判定纯规则，决策与文案走 LLM；提醒=通知；经 `autonomous_notify`（AUTO，D75 护栏） |
| D75 | autonomous_notify 护栏 | 注册条件/每日硬顶/内容前置校验——**校验对象从全局 currentSource 改为 TurnContext（D79）** |
| D76 | 事件域自启语义 | 事件源开关=唯一开关；运行判定 `任一源开 ∧（通知源→白名单非空）`；与 ALWAYS_ON 解耦 |
| **D77** | **自主回合原子性（GPT P0-2 修复）** | **Single-flight + 触发队列（合并策略）**：同一时刻**至多一个自主回合**——单消费者协程 + Mutex 双保险；新事件在回合进行中→**合并到 pending（Conflated Channel，新覆旧）**，回合结束后执行最新事件。TOCTOU（两事件并发见 IDLE→双回合）由此根除 |
| **D78** | **Receiver 生命周期（GPT P0-1 修复）** | `SCREEN_ON/OFF` 是 Android **禁止 Manifest 注册**的受保护广播——改为 **`context.registerReceiver` 动态注册**挂 TriggerHub 模块生命周期（进程锚=AgentRuntimeService 无障碍保活 D15）；**进程死亡重启后经 UsageStats 事件回填空窗**（best-effort 近似，非精确）；`BATTERY_CHANGED` 同动态注册（粘性广播首次即得当前态） |
| **D79** | **TurnContext 契约（GPT P1 修复）** | 废弃全局 `currentSource: String`——改为**回合级不可变上下文**：`data class TurnContext(turnId, source: TurnSource, triggerId: String?)`；ChatBotModule 持 `activeTurn: TurnContext?`（AtomicReference），回合入口设置、finally 清除；`autonomous_notify` FC 校验 `activeTurn.source==AUTONOMOUS && activeTurn.triggerId!=null`——**杜绝嵌套/竞态下的上下文污染（A 轮读到 B 的 trigger）** |
| **D80** | **成本四层分层（GPT P1 修复）** | NotifyCap ≠ LLM 消耗上限（LLM 可"决定不提醒"仍耗 Token）。分层：**`TriggerCap`（每日触发次数上限，默认 30 [5,200]）→ `BudgetGuard`（N5 预算）→ LLM → `NotifyCap`（每日提醒上限，默认 4 [1,10]）**。白名单风暴（7 App×高频）由 TriggerCap 兜底 |

---

## 一、总架构（定稿 v2）

```
══════════ 事件触发层（TriggerHub，新增 IZeroModule）══════════
🔔 通知（白名单，9.1 SystemBus.onNotificationPosted 基建已有）
🔋 充电满（BATTERY_CHANGED 动态注册，charging→FULL 边沿）
🔋 电量低（level ≤ 阈值，回充 ≥90% 重 armed）
🌙 夜间长亮屏（夜间窗口内 SCREEN_ON 持续 ≥ 阈值）
⏱ 连续使用（SCREEN_ON 段累计 ≥ 阈值；App 统计来自 UsageStatsModule）
（📅日程/🧠记忆/⚠️异常 → backlog）
        │
        ▼ 门控链（有序）：
          ① 源开关(ConfigField) → ② 白名单/去重(通知同App 5min)
          ③ 每日 TriggerCap(D80) → ④ 并发门控(D67, 见执行层)
          ⑤ BudgetGuard(N5，FC 轮内已接)
        │
        │ 直调 onTrigger(event)（不经 kick/Scheduler——B2）
        ▼
══════════ 执行层（AutonomousThinkingJob 改造）══════════
【D77 Single-flight】Conflated Channel(合并队列，容量 1 新覆旧)
  → 单消费者协程循环 → Mutex 双保险
  → 并发门控（bridge.isConversationIdle() 非 true → 推迟 90s / 闲时回调 5s）
  → 建 TurnContext(turnId, source=AUTONOMOUS, triggerId) → chatBot.activeTurn = ctx
  → 事件定向 prompt → LLM 决策
     → 提醒：autonomous_notify（校验 TurnContext，D79）
     → 不提醒：llm_decided_noop 埋点（区分"LLM 不想提"vs"Guard 拦了"）
  → finally：chatBot.activeTurn = null
用户消息 → 打断自主回合（D68：cancel 贯穿到 OkHttp，验收 P0-2）

⏰ 调度分层（D40，本轨不改）：
   · tick → 心跳型（反思/图谱/压缩）
   · 精准闹钟（ExactAlarmReceiver→kick→Scheduler）→ 准点型（提醒/定时自动化）
   · 事件直调（TriggerHub→队列→单消费者）→ 自主思考（本轨新增通道）
   · 启动 +2min 补检（fire(BACKLOG, BacklogSummary)，§三）
```

---

## 二、H1：并发原子性 + 用户打断（P0，最先）

### 现状根因
自主思考经 `ChatEngineBridge.chatAndCollect` 注入历史时未检测对话状态 → FC 轮次未闭合时插入 user 消息 → 序列断裂 → DS 400。**第二轮审计补充根因：即使检测了 IDLE，check-then-act 无原子性——两事件并发均见 IDLE → 双自主回合交错（GPT P0-2）。**

### 设计（三轮修订定稿）

> **依赖方向（B1）**：状态查询经 `ChatEngineBridge` 两方法（接口反转，v6.3 先例）；打断信号经 chatBus 流；TurnContext 定义在 core/common（两侧可达）。

```kotlin
// core/common/TurnContext.kt（新）
enum class TurnSource { USER, AUTONOMOUS, LIVE }
data class TurnContext(
    val turnId: String,        // UUID.randomUUID()
    val source: TurnSource,
    val triggerId: String?     // AUTONOMOUS 回合的 TriggerEvent.id
)

// services/realtime/ChatEngineBridge.kt（接口扩展——仅查询）
fun isConversationIdle(): Boolean
fun activeTurn(): TurnContext?            // 实现：chatBot.activeTurn

// core/bus/ChatBus（增中断流）
val onUserPriorityInterrupt = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
```

**ChatBotModule**（framework/chat）：
- `val activeTurn = AtomicReference<TurnContext?>(null)`——sendMessage 入口设 USER ctx / Impl chatAndCollect 传 source 建 AUTONOMOUS ctx（含 triggerId）/ Live 设 LIVE ctx；finally 清 null。
- sendMessage 入口直查 `activeTurn.get()?.source==AUTONOMOUS && chatState==GENERATING` → `chatBus.onUserPriorityInterrupt.emit()` → Impl 收到 cancel 自有收集 job（**取消传播验收 P0-2：callbackFlow awaitClose→OkHttp call.cancel，全链 structured concurrency，实测验证**）。

**AutonomousThinkingJob**（services/agent，D77 Single-flight）：
```kotlin
// 触发队列：Conflated——进行中收到新事件→覆盖 pending（新覆旧合并，不堆队）
private val triggerQueue = Channel<TriggerEvent>(capacity = Channel.CONFLATED)
private val turnMutex = Mutex()   // 双保险（单消费者已串行，Mutex 防绕过队列的直调）

// 单消费者循环（onAwake 启动）
scope.launch {
    for (event in triggerQueue) {
        turnMutex.withLock {
            executeTurn(event)   // 并发门控→TurnContext→LLM→notify→清 activeTurn
        }
    }
}

// 入口（TriggerHub 直调）
fun onTrigger(event: TriggerEvent) { triggerQueue.trySend(event) }  // 永不阻塞
```

**executeTurn 内并发门控（D67）**：
1. **先订阅** `chatBus.onChatFinished`（D1 防竞态）。
2. `bridge.isConversationIdle()` 非 true → 推迟 90s（`autonomous_deferred: reason=busy`）+ 订阅回调 5s 宽限即重查。定时保底双保险。
3. 闲时 → 建 `TurnContext(turnId=UUID, AUTONOMOUS, triggerId=event.id)` → 设 `chatBot.activeTurn`（经 Impl helper）→ chatAndCollect → finally 清 activeTurn。

**用户打断（D68）**：作废不重试——cancel 后本回合 TurnContext 随 finally 清除；队列中若有 pending 事件，等下轮单消费者自然取走（与"等下个事件"语义一致）。

---

## 三、H2：TriggerHub + 门控链 + 通知白名单

### TriggerHub（新 `services/agent/TriggerHub.kt`，IZeroModule id=trigger_hub）

> **分发（B2）**：不经 kick/Scheduler——直调 `autonomousThinkingJob.onTrigger(event)`（入队）。kick 保留原职责（精准闹钟落地唤醒）。

- `TriggerSource` 枚举：NOTIFICATION / BATTERY_FULL / BATTERY_LOW / NIGHT_SCREEN / CONTINUOUS_USE / BACKLOG（+ 预留 CALENDAR/MEMORY/ANOMALY）
- `data class TriggerEvent(id: String, source: TriggerSource, payload: String, ts: Long)`
- **门控链（有序，§一）**；`trigger_fired: source=` / `trigger_gated: reason=`（off/whitelist_empty/dedup/trigger_cap）埋点
- **间隔语义**：全局最小间隔仅约束非通知源；通知只受同 App 5min 去重
- **充电满边沿检测**：charging→FULL 转换一次
- **夜间窗口**：`nightStartHour`/`nightEndHour`（默认 23/6，[0,23]）独立参数，**跨午夜环绕判定**：
  ```kotlin
  fun isNightHour(h: Int, start: Int, end: Int) =
      if (start <= end) h in start until end else h >= start || h < end
  ```
- 各源开关/阈值 @ConfigField（D17 + 4c 注释）

### 通知白名单（D70/D71 三层）

**存储**：`TriggerWhitelistStore`（SharedPreferences StringSet + StateFlow）。**backlog**：数据模型后续升 `TriggerAppConfig(packageName, enabled, category, priority)`（本轮 packageName 即可）。

**选择器 UI**（新路由 `trigger_settings` 独立屏，复用 6.5a 模式）：
1. **推荐预置区**（置顶预勾选）：通讯（微信/QQ/短信）、支付（支付宝/银行）、日程（日历）、邮件、健康——按标签关键词匹配。
2. **全量可选区**：全部可启动 App（`<queries>` 已声明），已选置顶。
3. **基建排除**（默认隐藏）：黑名单关键词（Auto/Android Auto/投屏/一键多拍/AI 拍照/助手/输入法类）+ `FLAG_PERSISTENT` + 无 LAUNCHER；排除=隐藏非禁选。

### 时间触发移除 + 启动补检（D73 + D76）

- AutonomousThinkingJob 删 tick 订阅边 + **删 mode==ALWAYS_ON 门控**（乙方案；`autonomous_mode_gate_removed` 埋点）。
- **启动补检（+2min）**：`fire(BACKLOG, BacklogSummary)`——**结构化（GPT P2 修复），不塞原始 buffer**：
  ```kotlin
  data class BacklogSummary(
      val notificationCount: Int,           // 30min 内通知总数
      val apps: List<String>,               // 去重 App 名
      val latestNotifications: List<String>,// ≤5 条摘要（app: title）
      val screenInteractive: Boolean,       // 当前亮屏态
      val gapEstimateMin: Int               // 进程死亡空窗估算（UsageStats 回填，best-effort）
  )
  ```
- ALWAYS_ON 模式字段去向：仅存续于 tick 域（D40 原义），不被自主思考读取。

---

## 四、H3：设备状态采集 + 健康守护 LLM 化

### 采集（D78 修订：动态注册 + 双数据源）

**Receiver**：
- `SCREEN_ON`/`SCREEN_OFF`/`ACTION_BATTERY_CHANGED` 经 **`context.registerReceiver` 动态注册**（`SCREEN_ON/OFF` 是 Android 禁止 Manifest 注册的受保护广播——D78 根因）。
- 注册挂 **TriggerHub.onStart()**，注销 onDestroy()——模块生命周期=进程生命周期；进程存活锚=**AgentRuntimeService 无障碍保活（D15）**。
- `BATTERY_CHANGED` 粘性广播，注册即得当前电量（无需额外查询）。

**双数据源分工（GPT P1 修复）**：
| 数据 | 源 | 用途 |
|---|---|---|
| 屏幕连续亮屏时长 | SCREEN_ON/OFF 广播段累计（in-memory 段 + 当日总量持久） | 连续使用/夜间亮屏判定 |
| App 使用统计（Top: 微信） | **UsageStatsModule（Step 2.5 已有）**——`get_app_usage` 查询，PACKAGE_USAGE_STATS 授权已在权限中心 | payload 上下文（哪个 App 用最多） |

**进程死亡回填（D78）**：
- TriggerHub onAwake 后 +2min 的 BACKLOG 补检中，**queryUsageStats events 重建空窗期 ACTIVITY_RESUMED 序列**→近似判断"进程死亡期间用户是否持续使用"。
- **best-effort 近似**（非精确）：UsageStats 事件粒度为 Activity 切换，非逐秒亮屏。文档化限制，不承诺精确连续时长。

### 判定 → LLM 决策

- payload 例：`"连续使用 52 分钟（今日累计 3.2h，Top: 微信）"` / `"凌晨 1:40 亮屏已 18 分钟"` / `"电量 18%"`。
- 自主 prompt 按 source 定制；**明示"提醒用 autonomous_notify，不要用 push_notification（会弹确认）"**（D6）。

### autonomous_notify FC（D75 + D79 修订）

- 注册条件：任一事件源开关开启；AUTO 权限。
- **校验（D79 终版）**：handler 经 `bridge.activeTurn()` → `ctx?.source==TurnSource.AUTONOMOUS && ctx?.triggerId!=null` → 通过；否则 `notify_rejected: not_guard_context`。**TurnContext 不可变+回合级生命周期 → 无嵌套/竞态污染**。
- 每日 NotifyCap（SharedPreferences 日键）。
- 固定渠道 `zero_reminder`（IMPORTANCE_HIGH）。

### 埋点区分（GPT P2：触发≠提醒）

```
trigger_fired / trigger_gated(reason)   — 事件层
trigger_rejected_cap                     — TriggerCap 拦截
llm_started / llm_decided_noop           — LLM 层（noop=LLM 判断不用提）
notify_allowed / notify_rejected_cap     — 通知层
```
用户"没收到提醒"可诊断：LLM 不想提（noop）vs Guard 拦了（rejected）。

### ConfigField 清单（TriggerHubConfig，全显式定界 + 4c 注释）

| 字段 | 默认 | 界 | 描述示例 |
|---|---|---|---|
| 通知触发开关 | 开 | — | "白名单内应用来通知时让 Zero 自主判断是否提醒/代回" |
| 充电满触发 | 关 | — | "充电完成时告知" |
| 电量低触发 | 关 | — | "低于阈值时提醒" |
| 电量低阈值 % | 20 | [5,30] | "建议 20；回充 90% 后重 armed" |
| 夜间亮屏触发 | 关 | — | "深夜持续亮屏关怀提醒" |
| 夜间窗口 起/止(时) | 23/6 | [0,23] | "仅夜间亮屏源；跨午夜自动环绕" |
| 夜间亮屏阈值 min | 15 | [5,30] | "窗口内持续亮屏超过该时长触发" |
| 连续使用触发 | 关 | — | "久坐/护眼提醒" |
| 连续使用阈值 min | 45 | [15,120] | "建议 45；熄屏 5min 重新计时" |
| 日累计提醒档 | 4/6/8h | 固定 | "当日总用时每过一档触发" |
| **每日触发上限** | **30** | **[5,200]** | "TriggerCap：事件触发 LLM 的每日硬顶（区别于通知上限——LLM 可'决定不提醒'仍消耗 Token，此帽兜底防白名单风暴）" |
| 每日提醒上限 | 4 | [1,10] | "NotifyCap：autonomous_notify 每日硬顶" |
| 事件最小间隔 min | 5 | [1,30] | "非通知源最小间隔（通知仅同 App 5min 去重）" |

**聚合状态行（D76）**：trigger_settings 顶部三态——运行中/待配置/未启用。

---

## 五、实施计划（v2 重排：Single-flight 前置 A1）

| Step | 内容 | 文件 | 验证 |
|---|---|---|---|
| **A1a** | TurnContext（core/common）+ Bridge 两查询方法（isConversationIdle/activeTurn）+ ChatBus.onUserPriorityInterrupt + ChatBotModule activeTurn(AtomicReference) 标记/清除/发送 | TurnContext(新) + ChatEngineBridge + ChatEngineBridgeImpl + ChatBotModule + ChatBus（1 逻辑单元） | Build；activeTurn 回合入口设/finally 清；USER/AUTONOMOUS/LIVE 三源正确设置 |
| **A1b** | **Single-flight + 合并队列（D77）**：Conflated Channel + 单消费者 + Mutex；闲时推迟/回调（先订阅后推迟）；用户打断接线（cancel 传播） | AutonomousThinkingJob | **P0 验收矩阵 §六 1/2/3/7 全过**；cancel 贯穿 OkHttp（trace 确认 call cancelled） |
| **A2a** | TriggerHub 核心：TriggerEvent/门控链（含 **TriggerCap D80**）+ 通知源 + mode 门控删除 + 启动补检（**结构化 BacklogSummary + UsageStats 空窗回填**） | TriggerHub(新) + AutonomousThinkingJob + TriggerWhitelistStore(新) | 白名单通知→入队→单消费者执行；非白名单 gated；TriggerCap 到顶 `trigger_rejected_cap`；BACKLOG payload 为 BacklogSummary JSON |
| **A2b** | 白名单选择器 UI + 新路由 trigger_settings + 聚合状态行 | TriggerSettingsScreen(新) + ZeroNavHost + AgentBehaviorScreen(入口) | 三层选择正确；基建 App 隐藏；状态行三态 |
| **A3a** | **动态注册 Receiver（D78）**：registerReceiver 于 onStart/onDestroy + 屏幕段累计 + **UsageStatsModule 集成（Top App 查询）** + 四源判定（跨午夜环绕/充电满边沿/低电 armed） | TriggerHub 扩 + DeviceStateReceiver(新，仅代码类**无 Manifest 声明**——动态注册) | 连续 45min→fired；**P0-4/5 验收**（进程杀→重启→UsageStats 回填 best-effort）；夜间跨午夜 23:50→00:10 判定为窗口内 |
| **A3b** | autonomous_notify（TurnContext 校验/NotifyCap/prompt 引导/动态注册）+ TriggerHubConfig 全量 + 埋点六事件落码 | AutonomousThinkingJob + TriggerHub(Config) | **P0-6 验收**（AUTONOMOUS 通/USER 拒/LIVE 拒）；NotifyCap 到顶 rejected；noop 与 rejected 区分可查 |

依赖：A1a→A1b（P0）；A2a→A2b；A3a→A3b。全过 = D67–D80 落地闭环。

---

## 六、P0 验收矩阵（GPT-5.6 提出七项，全文纳入）

| # | 场景 | 验收标准 | 对应 Step |
|---|---|---|---|
| **P0-1 并发** | Trigger A + Trigger B 同时到达 | **只产生一个自主回合**（single-flight）；另一事件入 pending 合并 | A1b |
| **P0-2 用户抢占** | AUTONOMOUS generating → USER message | LLM 请求真实取消（trace OkHttp call cancelled）；自主回合 abandoned；USER 回合立即开始 | A1b |
| **P0-3 历史** | AUTONOMOUS generating → USER message → cancel | 历史**永不包含**交错的未闭合 turn（无 orphan tool_calls/tool 消息） | A1b |
| **P0-4 进程死亡** | SCREEN_ON → process killed → process restart | 重启后 UsageStats 回填 best-effort 判定连续使用（**非精确，文档化限制**）；BACKLOG 补检触发 | A3a |
| **P0-5 Receiver** | 设备状态事件 → 进程活/死 | 进程活→事件收到；进程死→**定义明确恢复行为**（无障碍保活重启→动态重注册→粘性广播补当前态） | A3a |
| **P0-6 Notify Context** | AUTONOMOUS A→notify/USER→notify/LIVE→notify | A：接受；USER：拒；LIVE：拒（TurnContext 校验，无全局污染） | A3b |
| **P0-7 Trigger Storm** | 100 条通知（白名单风暴） | LLM 并发 ≤ 1（single-flight）；TriggerCap 生效；NotifyCap 生效；BudgetGuard 生效 | A1b+A2a |

---

## 七、审计记录

### 第一轮（自审，2026-08-31 上午，11 项已修）

| 级别 | 项 | 修复 |
|---|---|---|
| B1 | services 无法注入 framework/chat | ChatEngineBridge 两查询方法（接口反转 v6.3） |
| B2 | kick→Scheduler→MAX 永不执行 | TriggerHub 直调 onTrigger |
| B3 | 免打扰取消后夜间定义悬空 | nightStartHour/nightEndHour 独立参数 |
| B4 | 打断取消信号传播未设计 | chatBus.onUserPriorityInterrupt 流 |
| B5 | notify 源校验依赖方向 | bridge.activeTurn() |
| D1-D6 | 竞态/间隔冲突/边沿/Manifest→**后续被 D78 覆盖**/UI 路由/注释 | 均入正文 |

### 第二轮（GPT-5.6 外审，2026-08-31，全部裁定采纳）

| 级别 | 项 | 修复落点 |
|---|---|---|
| 🔴 P0-1 | **SCREEN_ON/OFF 禁止 Manifest 注册**；连续使用依赖进程存活（process-liveness ≠ device-state） | **D78**：动态注册+无障碍保活锚+UsageStats 回填；A3a 重写；P0-4/5 验收 |
| 🔴 P0-2 | **TOCTOU 竞态**：check-then-act 无原子性，双事件→双回合 | **D77**：Single-flight（Conflated Channel+单消费者+Mutex）+ 合并队列；A1b 前置；P0-1/7 验收 |
| 🟠 P1 | currentSource 全局可变→嵌套/竞态污染 | **D79**：TurnContext(turnId/source/triggerId) 不可变+回合级；A1a 契约冻结 |
| 🟠 P1 | cancel 可能不贯穿 LLM 请求 | D68 补强制验收 P0-2（callbackFlow awaitClose→call.cancel 结构化取消，**实测验证**） |
| 🟠 P1 | NotifyCap≠LLM 消耗上限（LLM noop 仍耗 Token） | **D80**：四层 TriggerCap→Budget→LLM→NotifyCap；ConfigField 增"每日触发上限"30/[5,200] |
| 🟠 P1 | Top App 需 UsageStats（SCREEN 广播不含） | A3a 改**双数据源**：屏幕广播管连续性 + **UsageStatsModule（Step 2.5 已有）** 管 App 统计 |
| 🟠 P1 | 夜间跨午夜 | isNightHour 环绕判定 + A3a 测试矩阵 |
| 🟡 P2 | 4/6/8 档 vs 日顶 4 语义 | 埋点六事件区分（trigger_fired/llm_started/llm_decided_noop/notify_allowed/notify_rejected_cap） |
| 🟡 P2 | BACKLOG 塞原始 buffer 会爆 prompt | BacklogSummary 结构化（count/apps/≤5 latest/screen/gap） |
| 🟡 P2 | 白名单 packageName 无分类/优先级 | backlog：TriggerAppConfig(packageName,enabled,category,priority) |
| 🟡 P2 | AutonomousThinkingJob 已非 Job（队列/并发/上下文/通知） | 类名本轮不改；Controller 化记 backlog |
| 🟡 P2 | 缺开工前验收矩阵 | §六 七项全文纳入 |

### 保留不改的裁定（GPT 也评 🟢）

D67–D76 方向（并发思路/用户优先/事件驱动/通知机制/白名单 UX/调度解耦/自启语义）、TriggerHub 直调、乙方案。

---

## 八、与既有体系的关系

- **N5 BudgetGuard**：事件触发过 FC 轮 preCheck；**TriggerCap（D80）在其前方兜白名单风暴**——四层分层。
- **D40 双层调度**：kick 通道原职责（精准闹钟唤醒）不变；三通道并存（tick/闹钟/事件直调）。
- **9.1 通知基建**：SystemBus.onNotificationPosted 直接订阅。
- **Step 2.5 UsageStatsModule**：A3a 复用（App 统计数据源 + 进程死亡回填），PACKAGE_USAGE_STATS 授权已在权限中心。
- **D15 无障碍保活**：TriggerHub 动态注册的进程存活锚。
- **D17 用户主权**：全参数 @ConfigField 显式定界 + 4c 注释；事件域自启=源开关即唯一开关（D76）。
- **backlog**：📅日程触发 / 🧠记忆触发 / ⚠️异常监测 / TriggerAppConfig 分类白名单 / AutonomousThinkingController 更名。
