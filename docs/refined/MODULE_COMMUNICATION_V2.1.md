# Zero v2.1 — 模块通信逻辑详解

> 修订版：循环依赖解耦 / 事件缓冲策略 / Memory Mutex / 后台三层分层
> 原 MODULE_COMMUNICATION.md 被进程锁定，新版独立文件

---

## v2.1 关键变更摘要

| 变更 | 原设计 | v2.1 |
|------|--------|------|
| SEM→ATM 依赖 | `onTick()` 直接调用 `automationModule.executeNow()` | SystemBus.emit(OnScheduleTriggered) → ATM 订阅 |
| FunctionRegistry 查模块 | 需查 ModuleLifecycleManager | 延迟查询：getAllDefinitions() 只返回已注册 Map，无反向引用 |
| ChatBus 缓冲 | 统一 SharedFlow 配置 | 差异化: onChatReceived DROP_OLDEST(64) / onToolCall SUSPEND(16) / onChatFinished SUSPEND(8) |
| MessagePipeline | 无超时 | withTimeout(3s) + Filter 100ms 性能契约 |
| Memory 写入 | 无锁 | Mutex + Room @Transaction |
| 后台执行 | 前台 Service 直接调 LLM | 三层: Service(轻量≤5s) → WorkManager(重量) → AlarmManager(唤醒) |
| Plan-Execute | 无重规划限制 | maxReplanCount=2 + JSON 分层提取 + 降级规则引擎 |

---

## 事件类型总览

```
┌─────────────────────────────────────────────────────────────┐
│                        ChatBus (v2.1 差异化缓冲)             │
│                                                             │
│  onChatSent         [DROP_OLDEST 16]  用户消息已发送        │
│  onChatReceived     [DROP_OLDEST 64]  LLM 流式 chunk        │
│  onChatFinished     [SUSPEND 8]       对话完成              │
│  onChatHistoryAdd   [SUSPEND 8]       消息入历史            │
│  onPokeSend         [DROP_OLDEST 32]  系统消息              │
│  onToolCallStart    [SUSPEND 16]      工具调用开始          │
│  onToolCallResult   [SUSPEND 16]      工具调用结果          │
│                                                             │
├─────────────────────────────────────────────────────────────┤
│                     SystemBus (v2.1 新增)                    │
│                                                             │
│  onTick             心跳帧                                  │
│  onScheduleTriggered 自动化调度触发 (解耦 SEM→ATM)          │
│  onNotificationPosted / onAppForeground / onNetworkChanged   │
│                                                             │
├─────────────────────────────────────────────────────────────┤
│                   MessagePipeline (v2.1)                     │
│                                                             │
│  process(event): withTimeout(3s) { 责任链 } ?: event        │
│  IMessageFilter.process()  100ms 性能契约                   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

## SystemEventModule 与 AutomationModule 解耦

**原设计**：`SystemEventModule.onTick()` 直接持有 `AutomationModule` 引用

**v2.1**：通过 SystemBus 事件解耦

```
[SystemEventModule.onTick()]
  → 检查自动化调度 → 到期任务
  → SystemBus.emit(OnScheduleTriggered(automationId, HEARTBEAT))

[AutomationModule 订阅]
  → SystemBus.onScheduleTriggered.collect { event ->
      AutomationWorker.enqueue(context, event.automationId)
    }

[AutomationWorker (WorkManager)]
  → doWork() → automationModule.executeNow(automationId)
  → 网络约束 + 失败重试 + 自动恢复
```

**依赖矩阵变更**：
```
原: SEM ──(直接引用)──→ ATM
新: SEM ──(emit)──→ SystemBus ──(subscribe)──→ ATM
    ATM ──(Hilt注入)──→ FunctionRegistry, LLMClient  (不再被 SEM 反向引用)
```

---

## MemoryModule 写入互斥锁

```kotlin
class MemoryModule : ZeroModule<MemoryConfig>() {
    private val writeMutex = Mutex()

    private suspend fun <T> withWriteLock(block: suspend () -> T): T {
        return writeMutex.withLock { block() }
    }

    // onChatFinished 触发（对话线程）
    private suspend fun intelligentMemorize(userMsg: String, aiMsg: String) {
        withWriteLock {
            if (shouldMemorize(userMsg))
                memoryManager.memorize("用户: $userMsg", ...)
            if (shouldMemorize(aiMsg))
                memoryManager.memorize("AI: ${aiMsg.take(500)}", ...)
        }
    }

    // FC 函数（可能来自 WorkManager 线程）
    private suspend fun remember(content: String, importance: Float): FunctionResult {
        return withWriteLock {
            memoryManager.memorize(content, importance)
            FunctionResult.success("已记住")
        }
    }
}
```

---

## 后台执行三层分层

| 层级 | 机制 | 职责 | 超时 | 执行内容 |
|------|------|------|------|---------|
| Layer 1 | 前台 Service heartbeatLoop | 轻量检查 | ≤5s | 检查提醒、检查调度、发布事件 |
| Layer 2 | WorkManager CoroutineWorker | 重量执行 | ≤5min | executeNow(): LLM/搜索/抓取 |
| Layer 3 | AlarmManager.setExactAndAllowWhileIdle | 系统唤醒 | — | 15min 兜底，检查逾期任务 |

---

## 修订后依赖矩阵（0 循环依赖）

```
                    依赖方
被依赖方         CBM  MFM  MEM  FRM  CBM  LLM  SEM  BGS  WSM  VIM  CEX  NTM  USM  ATM
ChatBotModule      -    -    -    -    -    -    -    -    -    -    -    -    -    -
MessageFilterMod   -    -    -    -    -    -    -    -    -    -    -    -    -    -
MemoryModule       -    -    -    -    -    -    -    -    -    -    -    -    -    -
FunctionRegistry   Y    -    Y    -    Y    -    Y    -    Y    Y    -    Y    Y    Y
ContextBuilder     Y    -    -    Y    -    -    -    -    -    -    -    -    -    -
LLMClientModule    Y    -    -    -    -    -    -    -    -    Y    -    -    -    Y
SystemEventModule  -    -    -    Y    -    -    -    -    -    -    -    -    -    -  ← 不再依赖 ATM
BackgroundService  -    -    -    -    -    -    Y    -    -    -    -    -    Y    Y
WebSearchModule    -    -    -    Y    -    -    -    -    -    -    Y    -    -    -
VisionModule       -    -    -    Y    -    -    -    -    -    -    -    -    -    -
ContentExtractor   -    -    -    -    -    -    -    -    Y    -    -    -    -    -
NotificationMod    -    -    -    Y    -    -    -    -    -    -    -    -    -    -
UsageStatsModule   -    -    -    Y    -    -    -    -    -    -    -    -    -    -
AutomationModule   -    -    -    Y    -    Y    -    -    -    -    -    -    -    -
```

**0 循环依赖**。SEM 行不再有 ATM 列的 Y。FRM 通过延迟查询不依赖任何模块。

---

## v2.1 安全保障清单

| # | 安全措施 | 位置 | 机制 |
|---|---------|------|------|
| 1 | 循环依赖解耦 | SEM→ATM | SystemBus.OnScheduleTriggered 事件 |
| 2 | 循环依赖解耦 | FRM→ModuleLifecycleManager | 延迟查询 + 主动注册 |
| 3 | 后台限制 | BackgroundService | 三层: Service(≤5s) + WorkManager + AlarmManager |
| 4 | Plan 死循环 | AutomationModule | maxReplanCount=2 + Token 留底 5000 |
| 5 | JSON 脆弱 | generatePlan() | 4 层: 直接→Markdown移除→正则→重试→降级规则引擎 |
| 6 | Pipeline 阻塞 | MessagePipeline | withTimeout(3s) + 100ms Filter 契约 |
| 7 | ChatBus 丢帧 | ChatBusImpl | DROP_OLDEST(stream) / SUSPEND(critical) |
| 8 | Memory 竞态 | MemoryModule | Mutex + Room @Transaction |
| 9 | 任务崩溃 | AutomationWorker | WorkManager BackoffPolicy + 自动恢复 |
