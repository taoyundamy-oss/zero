# Zero v2 — 模块通信逻辑详解

> 配套 ARCHITECTURE_FINAL.md，描述每个模块订阅哪些事件、发布哪些事件、依赖哪些模块

---

## 一、事件类型总览

```
┌─────────────────────────────────────────────────────────────┐
│                        ChatBus                              │
│                                                             │
│  onChatSent         用户消息已发送（通知性质，只读）         │
│  onChatReceived     LLM 流式回复到达                        │
│  onChatFinished     一轮对话结束                            │
│  onChatHistoryAdd   消息加入历史                            │
│  onPokeSend         系统消息（Poke）发送                    │
│  onToolCallStart    工具调用开始                            │
│  onToolCallResult   工具调用结果                            │
│                                                             │
├─────────────────────────────────────────────────────────────┤
│                        SystemBus                            │
│                                                             │
│  onTick             心跳帧（ITimedModule 驱动）             │
│  onNotificationPosted 系统通知到达                          │
│  onAppForeground    应用切换到前台                          │
│  onNetworkChanged   网络连接变化                            │
│  onBatteryChanged   电池状态变化                            │
│                                                             │
├─────────────────────────────────────────────────────────────┤
│                      MessagePipeline                        │
│                                                             │
│  IMessageFilter.process()  消息预处理责任链                 │
│  按 filterOrder 顺序依次执行，每个 filter 返回处理后的消息  │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

## 二、每个模块的通信关系

### 2.1 ChatBotModule

```
订阅:
  onPokeSend  ──→ 收到 Poke 消息 → 注入到对话历史（作为系统消息）

发布:
  onChatSent       ← 每次用户消息发送后
  onChatReceived   ← LLM 每个 chunk
  onChatFinished   ← 对话完成
  onChatHistoryAdd ← 消息加入历史
  onToolCallStart  ← LLM 发起工具调用
  onToolCallResult ← 工具调用结果注入

依赖模块:
  ContextBuilderModule   ← 组装 System Prompt
  FunctionRegistryModule ← 获取 tools 数组
  LLMClientModule        ← 调用 API
```

### 2.2 MessageFilterModule

```
实现 IMessageFilter:
  filterOrder = -100  (最先执行)

process(event):
  输入: ChatSendEvent(message="今天有什么新闻？", source=CHAT_WINDOW)
  处理:
    1. config.enableTimestamp → 前缀注入 "当前时间:[2026-08-10 14:35:22]\n"
    2. message.length > maxMessageLength → 截断 + "(文本过长已截断)"
    3. injectionCountdown == 0 → 追加 config.messageAppend + 重置计数器
    4. injectionCountdown > 0 → injectionCountdown--
  输出: ChatSendEvent(message="当前时间:[...]\n今天有什么新闻？", ...)

不发布事件。
不订阅事件（仅被 MessagePipeline 调用）。
```

### 2.3 MemoryModule

```
订阅:
  onChatFinished  ──→ intelligentMemorize(userMsg, aiResponse)
                       │
                       ├── shouldMemorize(userMsg)?
                       │   └── yes → memoryManager.memorize("用户: ...", importance)
                       │
                       └── shouldMemorize(aiResponse)?
                           └── yes → memoryManager.memorize("AI: ...", importance)

发布:
  无

FC 函数:
  memory_remember  → memoryManager.memorize(content, importance)
  memory_recall    → memoryManager.semanticSearch(query, topK)
  memory_forget    → memoryManager.forget(keyword)

依赖模块:
  FunctionRegistryModule  ← 注册 FC 函数（在 onAwake 中调用 registry.register）
```

### 2.4 FunctionRegistryModule

```
订阅:
  无（被动服务，由其他模块调用）

发布:
  无（不主动推送事件）

API（被调用）:
  getAllDefinitions() → 返回 List<FunctionDefinition>
    - 遍历所有模块
    - 收集各模块 registerFunctions() 注册的函数
    - 返回给 ContextBuilderModule 构建 tools 数组

  execute(name, arguments):
    - 查找 handler
    - 执行 handler(args)
    - 记录 ModuleCallLogEntity
    - 返回 FunctionResult

依赖模块:
  无（通过 ModuleLifecycleManager 获取所有模块）
```

### 2.5 ContextBuilderModule

```
订阅:
  无（被动服务）

发布:
  无

API:
  buildSystemPrompt(basePersona, contextExtension, toolDefinitions):
    返回: """
      ${basePersona}

      ---
      [系统信息]
      当前时间: ${now}
      时区: ${timezone}

      ---
      [可用工具]
      ${toolDefinitions.joinToString("\n") { "- ${it.name}: ${it.description}" }}

      ---
      ${contextExtension ?: ""}
    """

  buildContextExtension(visionContext?, memoryContext?, factContext?):
    返回: """
      [本轮上下文]
      ${visionContext}
      ${memoryContext}
      ${factContext}
    """

依赖模块:
  FunctionRegistryModule  ← 获取 tools 列表
```

### 2.6 LLMClientModule

```
订阅:
  无（被动服务）

发布:
  无（chatStream 返回 Flow，由 ChatBotModule 收集后发布 onChatReceived）

API:
  chatStream(messages, systemPrompt, functions, config):
    - 构建 HTTP 请求
    - 发送到 DashScope
    - 返回 Flow<StreamChunk>（contentDelta / toolCallDelta / usage）

  chatSync(userMessage, systemPrompt?):
    - 非流式 HTTP 请求
    - 返回 String

依赖模块:
  无（纯 HTTP 客户端）
```

### 2.7 SystemEventModule

```
实现 ITimedModule:
  onTick(tickMs):
    1. 检查 dynamicTick → 触发 → Poke("动态报点唤醒...")
    2. 检查 reminders[] → 触发 → Poke("⏰ 提醒 [id]: ...")
    3. 检查 nextAutoTick → 触发 → Poke("系统周期报点...")
    4. 检查自动化调度 → 到期任务 → automationModule.executeNow()

订阅:
  onChatSent       ──→ 检测非 Poke 消息 → 重置 continuousTimerCount → 重置退避
  onChatFinished   ──→ 检测到长对话结束 → 评估是否需要设置 NextTick

发布:
  onPokeSend       ← 报点消息、提醒通知、记事本状态

FC 函数:
  set_reminder / list_reminders / cancel_reminder
  next_tick
  read_notepad / write_notepad / clear_notepad / archive_notepad

依赖模块:
  FunctionRegistryModule  ← 注册 8 个 FC 函数
  AutomationModule        ← executeNow() 触发自动化
```

### 2.8 BackgroundServiceModule

```
发布:
  SystemBus.onTick     ← 每个心跳帧（驱动所有 ITimedModule）

订阅:
  SystemBus.onBatteryChanged  ← 电池状态变化 → 调整心跳频率
  SystemBus.onNetworkChanged  ← 网络变化 → 暂停/恢复需要网络的操作

内部逻辑:
  heartbeatLoop():
    while (isActive) {
      val tickMs = System.currentTimeMillis()
      timedModules.forEach { module ->
        try { module.onTick(tickMs) }
        catch (e) { logManager.error(module.metadata.id, "onTick", e) }
      }
      delay(heartbeatInterval)
    }

  AlarmManager 兜底:
    - 每 15 分钟 setExactAndAllowWhileIdle
    - 唤醒后检查 timedModules 中未触发的提醒
    - 确保 Doze 模式下不丢失提醒

依赖模块:
  所有 ITimedModule 实现
```

### 2.9 WebSearchModule

```
订阅:
  无

发布:
  无（被 FC 调用）

FC 函数:
  web_search(query, maxResults, deepExtract):
    1. performApiSearch(query, maxResults)  → 获取 URL 列表
    2. if deepExtract:
         ContentExtractor.extractBatch(urls.take(5))
    3. formatSearchOutput(enrichedResults)  → 带引用标注

API（内部）:
  search(query, maxResults, deepExtract) → FunctionResult

依赖模块:
  ContentExtractor       ← 网页抓取
  FunctionRegistryModule ← 注册 FC
```

### 2.10 ContentExtractor

```
订阅:
  无

发布:
  无

API:
  extract(url, maxChars) → ExtractedContent
  extractBatch(urls, maxChars) → List<ExtractedContent>

内部:
  OkHttp GET(url)
  Jsoup.parse(html)
  正文提取算法（移除 nav/ad/script/style → 保留 article/main/p 标签 → Markdown）
```

### 2.11 VisionModule

```
订阅:
  无

发布:
  无

FC 函数:
  vision_analyze(image_data, question):
    - 构建 DashScope multimodal-generation 请求
    - 返回分析文本

API:
  analyzeImagePoke(imageData, question): 非流式快速路径（用于聊天 Poke 预运行）

依赖:
  FunctionRegistryModule ← 注册 FC
  LLMClientModule        ← 内部 HTTP 调用（或独立 OkHttp）
```

### 2.12 NotificationModule

```
订阅:
  无（通过 NotificationListenerService 直接获取系统通知）

发布:
  onPokeSend  ← 重要通知到达时注入对话上下文

FC 函数:
  read_notifications(count, filterApp) → FunctionResult
  push_notification(title, content, priority) → FunctionResult

Android 权限:
  BIND_NOTIFICATION_LISTENER_SERVICE
  POST_NOTIFICATIONS
```

### 2.13 UsageStatsModule

```
实现 ITimedModule:
  onTick(tickMs):
    每 15 分钟收集一次使用量数据，存入内存缓存

FC 函数:
  get_screen_time(period):
    UsageStatsManager.queryUsageStats(period, ...)
    → 格式化返回

  get_app_usage(packageName):
    UsageStatsManager.queryUsageStats(period, packageName)
    → 返回该应用使用时长

Android 权限:
  PACKAGE_USAGE_STATS
```

### 2.14 AutomationModule

```
订阅:
  SystemBus.onTick  ← 检查是否有到期任务（由 SystemEventModule 触发）
  onChatFinished    ← 检查是否是一次性自动化任务 → 评估结果质量 → 可能重试

发布:
  onPokeSend        ← 执行进度推送
  onToolCallStart   ← 每个步骤开始
  onToolCallResult  ← 每个步骤结束

FC 函数:
  create_automation / list_automations / manage_automation
  run_automation_now / get_automation_results

内部:
  executeNow() → generatePlan() → [executeStep()]* → synthesizeResults() → persistResult()

依赖模块:
  FunctionRegistryModule ← 步骤执行时调用其他 FC
  LLMClientModule        ← 生成 Plan + 合成结果
  ModuleLifecycleManager ← 获取所有模块（动态步骤调度）
```

---

## 三、典型场景数据流

### 场景 1：用户发图片

```
1. 用户选择图片 + 输入 "这是什么？"
2. ChatBotModule.sendMessage("这是什么？", attachments=[image])
3. MessagePipeline.process(event)
   → MessageFilterModule: 注入时间戳
4. ContextBuilderModule.buildContextExtension()
   → Poke VisionModule.analyzeImagePoke(image, "这是什么？")
   → "一只橘猫坐在窗台上，背景是蓝天白云"
5. ContextBuilderModule.buildSystemPrompt(persona, context, tools)
6. LLMClientModule.chatStream(messages, systemPrompt, tools)
7. LLM 返回: "图片中是一只橘猫，它正坐在窗台上..."
8. ChatBus.emit(onChatReceived) → UI 增量渲染
9. ChatBus.emit(onChatFinished)
   → MemoryModule.intelligentMemorize("这是什么？", "图片中是...")
```

### 场景 2：LLM 主动搜索

```
1. 用户: "帮我查一下今天的AI新闻"
2. ChatBotModule.sendMessage(...)
3. MessagePipeline → 时间注入
4. LLM 返回 tool_calls: [{name: "web_search", arguments: {query: "AI新闻 2026-08-10"}}]
5. ChatBus.emit(onToolCallStart)
6. FunctionRegistryModule.execute("web_search", {query: "AI新闻 2026-08-10"})
   → WebSearchModule.search("AI新闻 2026-08-10", deepExtract=true)
     → DashScope API 搜索 → 10 条 URL
     → ContentExtractor.extractBatch(top5) → Markdown
7. ChatBus.emit(onToolCallResult)
8. continueAfterToolCall() → LLM 基于搜索结果生成回复
9. ChatBus.emit(onChatFinished) → MemoryModule 记忆化
```

### 场景 3：自动化每日新闻摘要

```
1. SystemEventModule.onTick(tickMs=2026-08-10 09:00:00)
2. 检查自动化调度 → "每日AI新闻" 到期
3. AutomationModule.executeNow("automation_001")
4. ExecutionGuard.check → OK
5. generatePlan() → LLM 分解:
     step1: search("AI新闻")
     step2: search("AI技术突破")
     step3: llm_synthesize(step1+step2 → 5条要点)
6. executeStep(step1) → Poke("🔧 搜索 AI新闻...")
   → WebSearchModule.search → 10条结果
7. executeStep(step2) → Poke("🔧 搜索 AI技术突破...")
   → WebSearchModule.search → 8条结果
8. executeStep(step3) → Poke("📊 正在合成...")
   → LLM 生成 5 条要点摘要
9. persistResult() → ConversationEntity("🤖 每日AI新闻", messages=[...])
10. push_notification("✅ 今日简报已生成")
11. ChatBus.emit(onChatFinished)
```

### 场景 4：AI 主动提醒用户休息

```
1. UsageStatsModule.onTick() 每 15 分钟收集数据
2. 缓存: {com.tencent.mm: 120min, total: 280min}
3. SystemEventModule.onTick() 动态报点触发
4. Poke("动态报点唤醒。当前任务进度:\n(记事本为空)\n\n系统信息: 今日已使用屏幕 4h40m")
5. LLM 收到 Poke + 上下文:
   "你已经连续使用微信 2 小时了，屏幕总时长 4h40m，比昨天多 30%。
    要不要休息一下？我可以设置 15 分钟后的提醒。"
6. 用户: "好的，15分钟后提醒我"
7. LLM 调用 set_reminder(time="+15m", remark="休息时间到")
8. SystemEventModule 创建 ReminderEntry(time=now+15m)
9. 15 分钟后 → onTick 触发 → Poke("⏰ 提醒 [a1b2]: 休息时间到")
10. LLM → push_notification("休息一下", "起来走走，看看远处")
```

---

## 四、模块间依赖矩阵

```
                    依赖方
被依赖方         CBM  MFM  MEM  FRM  CBM  LLM  SEM  BGS  WSM  VIM  CEX  NTM  USM  ATM
ChatBotModule      -    -    -    -    -    -    -    -    -    -    -    -    -    -
MessageFilterMod   -    -    -    -    -    -    -    -    -    -    -    -    -    -
MemoryModule       -    -    -    -    -    -    -    -    -    -    -    -    -    -
FunctionRegistry   Y    -    Y    -    Y    -    Y    -    Y    Y    -    Y    Y    Y
ContextBuilder     Y    -    -    Y    -    -    -    -    -    -    -    -    -    -
LLMClientModule    Y    -    -    -    -    -    -    -    -    Y    -    -    -    Y
SystemEventModule  -    -    -    Y    -    -    -    -    -    -    -    -    -    Y
BackgroundService  -    -    -    -    -    -    Y    -    -    -    -    -    Y    Y
WebSearchModule    -    -    -    Y    -    -    -    -    -    -    Y    -    -    -
VisionModule       -    -    -    Y    -    -    -    -    -    -    -    -    -    -
ContentExtractor   -    -    -    -    -    -    -    -    Y    -    -    -    -    -
NotificationMod    -    -    -    Y    -    -    -    -    -    -    -    -    -    -
UsageStatsModule   -    -    -    Y    -    -    -    -    -    -    -    -    -    -
AutomationModule   -    -    -    Y    -    Y    -    -    -    -    -    -    -    -

Y = 依赖（通过 Hilt 构造注入或 ModuleLifecycleManager 查找）
- = 无依赖

模块缩写:
CBM=ChatBotModule, MFM=MessageFilterModule, MEM=MemoryModule,
FRM=FunctionRegistryModule, CBM=ContextBuilderModule, LLM=LLMClientModule,
SEM=SystemEventModule, BGS=BackgroundServiceModule, WSM=WebSearchModule,
VIM=VisionModule, CEX=ContentExtractor, NTM=NotificationModule,
USM=UsageStatsModule, ATM=AutomationModule
```

关键观察：
- **FunctionRegistryModule** 是被依赖最多的模块（9 个模块需要通过它注册 FC 函数）
- **ChatBotModule** 和 **LLMClientModule** 是被 Automation 和 ContextBuilder 依赖的核心
- **SystemEventModule** 依赖 AutomationModule（心跳中触发自动化），而 Automation 的 FC 函数也通过 SystemEvent 的调度触发
- 无循环依赖
