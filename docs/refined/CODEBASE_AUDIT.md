# Zero V2 代码库审计报告

> 审计日期：2026-08-11
> 审计范围：D:\ZeroV2 全部 69 个 Kotlin 源文件
> 对照基准：DEVELOPMENT_DESIGN.md v3.0

---

## 一、项目概况

### 1.1 架构统计

| 维度 | 数值 |
|------|------|
| Gradle 模块 | 10 个（core:common, core:bus, core:module-api, core:module-manager, core:trace, core:data, services, framework:chat, framework:memory, app） |
| Kotlin 源文件 | 69 个 |
| 已注册 IZeroModule | 12 个 |
| 已注册 FC 函数 | 25 个 |
| 已实现 Compose Screen | 5 个（Chat, ModuleCenter, ModuleDetail, RunLog, AgentTraceTimeline） |
| ViewModel | 4 个 |
| Room Entity | 2 个（MemoryEntity, ModuleConfigEntity） |
| Room DAO | 2 个（MemoryDao, ModuleConfigDao） |
| 技术栈 | Compose + Material 3 + Hilt + Room + WorkManager + OkHttp + Navigation + DataStore(未用) |
| Kotlin / SDK | 2.1.0 / minSdk 26 / targetSdk 35 |

### 1.2 模块依赖图

```
app
 ├── framework:chat        → ChatBotModule, ContextBuilderModule, MessageFilterModule, TokenBudgetAllocator
 ├── framework:memory      → MemoryModule
 ├── services              → LLM (Client/Config/Router/Adapters), Modules (WebSearch/Vision/Automation/SystemEvent/Notification/UsageStats), ContentExtractor, SecureKeyStore
 ├── core:bus              → ChatBus, SystemBus, MessagePipeline, IMessageFilter
 ├── core:module-manager   → ModuleLifecycleManager, FunctionRegistryModule, ModuleConfigParser
 ├── core:module-api       → IZeroModule, ModuleConfigBase, ConfigField, ITimedModule, ModuleCategory
 ├── core:trace            → AgentTrace, AgentTraceManager
 ├── core:common           → ChatEvents, FunctionDefinition, ModelAdapter, SystemEvents
 └── core:data             → MemoryDao, ZeroDatabase, MemoryEntity, ModuleConfigEntity, ModuleConfigStore
```

---

## 二、逐模块审计

### 2.1 core:module-api（模块接口层）

| 文件 | 状态 | 评价 |
|------|:---:|------|
| `IZeroModule.kt` | ✅ 完整 | 模块生命周期契约完备：onAwake→onStart→[onTick]→onDestroy，文档完整 |
| `ModuleConfigBase.kt` | ✅ 完整 | JSON 序列化抽象基类，配合 ConfigField 注解使用 |
| `ConfigField.kt` | ✅ 完整 | 注解驱动配置 UI 声明，6 种字段类型 |
| `ITimedModule.kt` | ✅ 完整 | 心跳接口，100ms 性能契约已文档化 |
| `ModuleCategory.kt` | ✅ 完整 | CORE/INSTRUMENT/SYSTEM/AUTOMATION 四级分类 |
| `ModuleLifecycle.kt` | ✅ 完整 | UNINITIALIZED→AWAKE→STARTED→DESTROYED→DISABLED |
| `ModuleMetadata.kt` | ✅ 完整 | 模块标识数据类，含 launchOrder 文档 |

**与设计差距**：缺少 `IModuleUI` 接口（模块子页面 UI）、`ModulePermission` 枚举、`requiredPermissions` 仅声明为 `List<String>` 而非类型安全枚举

### 2.2 core:module-manager（模块管理实现层）

| 文件 | 状态 | 评价 |
|------|:---:|------|
| `ModuleLifecycleManager.kt` | ✅ 完整 | 核心编排器。safeCall 崩溃隔离、Core/非Core 差异化处理、ConcurrentHashMap 计数、reEnable 恢复机制，全部实现 |
| `FunctionRegistryModule.kt` | ⚠️ 基本完整 | 注册/执行/批量并行均已实现。SecurityInterceptor 的确认流是 **stub**：SENSITIVE 操作 emit 确认请求后直接放行（`params = emptyMap()`），无等待逻辑 |
| `ModuleConfigParser.kt` | ✅ 完整 | onAwake 时一次性反射解析为 `List<FormFieldState>`，Compose 渲染路径零反射 |

**与设计差距**：缺少 `ToolRegistry`（ToolDefinition Manifest 系统）、SecurityInterceptor 等待 UI 弹窗的逻辑未接通

### 2.3 core:bus（事件总线层）

| 文件 | 状态 | 评价 |
|------|:---:|------|
| `ChatBus.kt` | ✅ 完整 | 7 个事件流，差异化缓冲策略（DROP_OLDEST 64/ SUSPEND 8-16） |
| `ChatBusImpl.kt` | ✅ 完整 | 全部实现，缓冲参数与接口契约一致 |
| `SystemBus.kt` | ✅ 完整 | 8 个事件流，含 SecurityConfirmRequest/Response、ScheduleTriggered |
| `SystemBusImpl.kt` | ✅ 完整 | 状态类流 replay=1 |
| `IMessageFilter.kt` | ✅ 完整 | 过滤器接口，100ms 性能契约 |
| `MessagePipeline.kt` | ✅ 完整 | 责任链 + 全局 3s 超时 + synchronizedCopy 线程安全 |

**与设计差距**：无。总线层实现度 100%，与 v3.0 设计完全匹配

### 2.4 core:common（公共类型层）

| 文件 | 状态 | 评价 |
|------|:---:|------|
| `ChatEvents.kt` | ✅ 完整 | 完整的对话事件链：ChatSendEvent→ChatSent→StreamChunk(thinking/toolCallDelta/tokenUsage)→ChatFinished |
| `FunctionDefinition.kt` | ✅ 完整 | OpenAI 格式 FC 定义，含 securityLevel + dependsOn |
| `ModelAdapter.kt` | ✅ 完整 | 老架构的 Adapter 接口（Phase 1 后废弃） |
| `SystemEvents.kt` | ✅ 完整 | ScheduleTriggeredEvent、SecurityConfirmRequest/Response 等 |

**与设计差距**：`ChatMessage` 缺少 `automationRunId` 字段（用于关联自动化思维链）、缺少 `attachments` 字段、缺少 `id` 字段（现用 timestamp 标识）

### 2.5 core:data（数据层）

| 文件 | 状态 | 评价 |
|------|:---:|------|
| `MemoryEntity.kt` | ⚠️ 基础版 | 仅含 level/importance/keywords/embedding(预留)，缺少 v3.0 设计的 type/status/confidence/reinforcement/conflict 字段 |
| `ModuleConfigEntity.kt` | ✅ 完整 | moduleId + configJson + updatedAt |
| `MemoryDao.kt` | ⚠️ 基础版 | keyword LIKE 搜索 + 基础 CRUD + LRU，**无 FTS5 表**，无向量搜索 |
| `ZeroDatabase.kt` | ✅ 完整 | Room v2，`fallbackToDestructiveMigration()` |
| `ModuleConfigStore.kt` | ✅ 完整 | 300ms debounce 写后 + conflated channel |
| `DatabaseModule.kt` | ✅ 完整 | Hilt provider |

**与设计差距**：缺少 ConversationEntity/DAO、MessageEntity/DAO、CharacterEntity/DAO、AutomationEntity/DAO（Phase 0 全部缺失）；MemoryEntity 字段不足以支持 v3.0 Memory Evolution

### 2.6 core:trace（可观测性层）

| 文件 | 状态 | 评价 |
|------|:---:|------|
| `AgentTrace.kt` | ✅ 完整 | 含 parentTraceId 嵌套支持，12 种 NodeType |
| `AgentTraceManager.kt` | ✅ 完整（内存） | 内存存储 + ring-buffer(100 traces) + startTrace/record/getTrace/getTraceSummary。endTrace 是 no-op |

**与设计差距**：AgentTraceEvent **无 Room 持久化**（当前仅内存），与 v3.0 要求"独立表不入 Message"一致但缺少持久化层；trace 淘汰非真正 LRU（`keys.firstOrNull()` 而非最旧）

### 2.7 services/llm（LLM 客户端层）

| 文件 | 状态 | 评价 |
|------|:---:|------|
| `LLMClientModule.kt` | ⚠️ 需重构 | **单体模块**，依赖 ModelRouter 路由到 Adapter。v3.0 设计要求拆分为独立模块 |
| `ModelRouter.kt` | ⚠️ 需删除 | 集中式路由，含 5xx/429 fallback + token clip。将被 ModelSelector 替代 |
| `ModelConfigModule.kt` | ⚠️ 需重构 | 集中式配置。将被各模块独立 Config 替代 |
| `LLMClientConfig.kt` | ⚠️ 需重构 | 集中式配置类，将被 DeepSeekChatConfig/QwenChatConfig 替代 |
| `DeepSeekAdapter.kt` | ✅ 完整 | 完整 SSE 流式解析：content/thinking/tool_calls/usage(prompt_cache_hit_tokens) |
| `QwenAdapter.kt` | ✅ 完整 | 同上 + thinking extra_body + thinking_budget 计算 |
| `CustomOpenAIAdapter.kt` | ⚠️ 基础 | content-only SSE 解析，**无 FC/thinking 支持**；endpointUrl 未从 Config 注入（默认 localhost Ollama） |
| `ApiKeyProvider.kt` | ❌ Stub | 从环境变量读取 Key（Android 上无意义），注释写明 "Phase 8 DataStore" |

**关键问题**：
- LLM 层是当前架构与设计差距最大的部分——需要从"集中式路由"重构为"每提供商独立模块"
- ApiKeyProvider 是占位实现，实际 Key 通过 ModuleDetailScreen → SecureKeyStore 路径写入
- CustomOpenAIAdapter 的 endpoint 硬编码为 localhost，未与 LLMClientConfig.customEndpointUrl 接通

### 2.8 services/module（服务模块层）

| 文件 | 状态 | 评价 |
|------|:---:|------|
| `WebSearchModule.kt` | ✅ **完整** | **FC 实现标杆**。DashScope multimodal-generation SSE 搜索，extractDeltaText 支持三种 content 格式，搜索结果格式化含来源引用 |
| `VisionModule.kt` | ✅ 完整 | DashScope 图片理解，analyzePoke 快速路径 |
| `SystemEventModule.kt` | ✅ **完整** | **ITimedModule 标杆**。PriorityQueue 提醒(8个FC全实现)、parseTime(相对+ISO)、指数退避自动报点、记事本CRUD、AUTOMATION→SystemBus 事件解耦 |
| `AutomationModule.kt` | ❌ **Stub** | **最大差距**。5 个 FC 全部返回占位字符串。ExecutionGuard/JsonExtractor 已定义但从未调用。无 Plan-Execute 引擎 |
| `NotificationModule.kt` | ❌ Stub | 2 个 FC 返回占位字符串，无实际 NotificationManager 调用 |
| `UsageStatsModule.kt` | ❌ Stub | 2 个 FC 返回占位字符串，onTick 空实现 |
| `ContentExtractor.kt` | ⚠️ 简化版 | 功能性可用但 Readability 算法被简化为正则去噪，MIN_TEXT_DENSITY/MAX_LINK_RATIO 常量已定义但未使用 |

### 2.9 services/security

| 文件 | 状态 | 评价 |
|------|:---:|------|
| `SecureKeyStore.kt` | ⚠️ **安全风险** | 使用**明文 SharedPreferences**（`context.getSharedPreferences`），注释写明"EncryptedSharedPreferences 兼容问题"。实际上只是一个 key-value wrapper |

### 2.10 framework/chat（对话框架层）

| 文件 | 状态 | 评价 |
|------|:---:|------|
| `ChatBotModule.kt` | ✅ **完整** | **核心引擎**。sendMessage(含 MessagePipeline→Mutex→ChatBus→ContextBuilder→LLMStream→onChatFinished 全流程)、applyStreamChunk(增量 content/toolCall/token)、regenerate、toolCall delta 累积、Trace 记录 |
| `ContextBuilderModule.kt` | ✅ 完整 | TokenBudgetAllocator 集成、时间块注入、工具块预算裁切。但 needsCompression(90%触发) 未被调用 |
| `MessageFilterModule.kt` | ✅ 完整 | 时间戳注入 + 长度截断 + 行为提示周期性重注入 |
| `TokenBudgetAllocator.kt` | ✅ 完整 | 15/15/20/50 配额 + 启发式 Token 估算（备注未来用 tiktoken） |

### 2.11 framework/memory（记忆框架层）

| 文件 | 状态 | 评价 |
|------|:---:|------|
| `MemoryModule.kt` | ✅ **完整** | 3个FC(remember/recall/forget)全部实现、Mutex 保护写入、intelligentMemorize 自动记忆化、shouldMemorize 启发式规则、extractKeywords 停用词过滤 |

**与设计差距**：仅 keyword LIKE 搜索，无 FTS5、无向量搜索、无 EmbeddingProvider、无 HistoryCompressor、无 MemoryManager、无多级压缩(L0-L8 字段有但逻辑无)

### 2.12 app/ui（UI 层）

| 文件 | 状态 | 评价 |
|------|:---:|------|
| `ChatScreen.kt` | ✅ 完整 | 简洁 Compose 聊天 UI，消息气泡 + AgentTraceTimeline 内联 + 自动滚动 |
| `ChatViewModel.kt` | ⚠️ **FC 被禁用** | `val toolDefs = emptyList<FunctionDefinition>()` 注释 "Phase 13: 逐步启用 FC"。regenerate 却传了 functionRegistry.getAllDefinitions() |
| `ModuleCenterScreen.kt` | ✅ 完整 | 模块列表按 Category 分组，点击进入详情 |
| `ModuleDetailScreen.kt` | ✅ 完整 | **最完整的 UI**：API Key 字段(密码遮罩+已配置状态)+ AutoForm(TOGGLE/TEXT/NUMBER/DROPDOWN/SLIDER/MULTILINE) |
| `ModuleDetailViewModel.kt` | ⚠️ 部分 | config 修改持久化是 **注释占位**（`// ModuleConfigStore.saveNow would be called here`），使用了反射（与零反射设计矛盾） |
| `RunLogScreen.kt` | ✅ 完整 | Trace 时间线查看器，NodeType → emoji 映射 |
| `RunLogViewModel.kt` | ⚠️ 基础 | constructor-only 快照，无实时刷新 |
| `ModuleCenterViewModel.kt` | ⚠️ 基础 | constructor-only 快照，无启用/禁用操作 |
| `AgentTraceTimeline.kt` | ✅ 完整 | 可折叠思维链卡片 + 摘要统计 + 带颜色/emoji 的节点列表 |
| `ZeroNavHost.kt` | ⚠️ **需改** | 当前 3 Tab：对话/模块/日志，需改为 对话/自动化/设置 |
| `PermissionOnboardingScreen.kt` | ❌ **死代码** | 完全实现但未接入导航，Manifest 也缺少对应权限声明 |

### 2.13 app/background（后台层）

| 文件 | 状态 | 评价 |
|------|:---:|------|
| `AutomationWorker.kt` | ❌ **Stub** | `doWork()` 直接返回 `Result.success()`，注释写 "Plan-Execute 引擎在此触发" |
| `BootReceiver.kt` | ❌ Stub | 仅 log，"恢复所有活跃任务的 WorkManager 调度" 未实现 |
| `NotificationListener.kt` | ⚠️ 基本可用 | 读取通知 title/content 并 emit 到 SystemBus，appName 硬编码为空 |

---

## 三、与 DEVELOPMENT_DESIGN.md v3.0 对照

### 3.1 Phase 0（MVP 产品闭环）

| 要求 | 状态 | 差距 |
|------|:---:|------|
| ConversationEntity/DAO | ❌ | 完全缺失，ChatHistory 仅内存 |
| MessageEntity/DAO | ❌ | 完全缺失 |
| CharacterEntity/DAO | ❌ | 完全缺失 |
| ChatViewModel | ⚠️ | 功能有限：无对话持久化、无角色切换、无附件、FC 被禁用 |
| ConversationHistoryDialog | ❌ | 缺失 |
| SettingsScreen | ❌ | 缺失，仅有模块详情页 |
| CharacterManageScreen | ❌ | 缺失 |
| PreferencesManager | ❌ | 缺失，设置通过 Room 分散管理 |
| LLM 直连 | ✅ | 已有（但过于复杂，需要简化入口） |

**Phase 0 完成度：约 15%**

### 3.2 Phase 1（模块框架）

| 要求 | 状态 | 差距 |
|------|:---:|------|
| ILanguageModel + capabilities | ❌ | ModelAdapter 接口存在但无 capabilities |
| ModelSelector | ❌ | ModelRouter 存在但基于 modelId 字符串路由 |
| IModuleUI | ❌ | 缺失 |
| ConfigMigration | ❌ | 缺失 |
| 删除集中式 LLM | ❌ | LLMClientModule + ModelRouter 仍在使用 |
| DeepSeekChatModule | ❌ | DeepSeekAdapter 存在但非独立 IZeroModule |
| QwenChatModule | ❌ | QwenAdapter 存在但非独立 IZeroModule |
| Secret/Config 分离 | ❌ | apiKey 通过 ModuleDetailScreen 直接写入 SecureKeyStore，Config 无 apiKeyRef |
| ModuleCenterScreen 迁入设置 | ❌ | 当前仍是独立 Tab |

**Phase 1 完成度：约 10%**

### 3.3 Phase 2（Function Calling）

| 要求 | 状态 | 差距 |
|------|:---:|------|
| ToolRegistry + ToolDefinition | ❌ | FunctionRegistry 用 Map 管理，无 Manifest |
| SecurityInterceptor | ⚠️ | 框架已有但 SENSITIVE 确认自动放行 |
| WebSearchModule | ✅ | 完整实现 |
| VisionModule | ✅ | 完整实现 |
| ContentExtractor | ⚠️ | 功能性可用，Readability 算法简化 |
| ChatBotModule FC 循环 | ⚠️ | FC 基础设施完备但 ChatViewModel 禁用了 FC |
| ToolCall 并行执行 | ✅ | executeBatch 已实现 |

**Phase 2 完成度：约 65%**

### 3.4 Phase 3（自动化 + Agent Runtime）

| 要求 | 状态 | 差距 |
|------|:---:|------|
| AutomationModule | ❌ | 5 个 FC 全部 stub |
| AutomationStateMachine | ❌ | 缺失 |
| AgentRuntimeService | ❌ | 缺失 |
| AgentRuntime(O→R→D→A) | ❌ | 缺失 |
| AgentWakeupManager | ❌ | 缺失 |
| AutomationScreen | ❌ | 缺失 |
| CreateAutomationScreen | ❌ | 缺失 |
| AutomationDetailScreen | ❌ | 缺失 |
| TaskParserLLM/RuleBasedTaskParser | ❌ | 缺失 |

**Phase 3 完成度：约 5%**（仅有 ExecutionGuard/JsonExtractor 工具类 + AutomationWorker 骨架）

### 3.5 Phase 4（Memory Evolution）

| 要求 | 状态 | 差距 |
|------|:---:|------|
| MemoryType/MemoryStatus | ❌ | Entity 无这些字段 |
| MemoryScoringEngine | ❌ | 仅有 importance 字段，无 scoring |
| MemoryConflictDetector | ❌ | 缺失 |
| MemoryReflectionJob | ❌ | 缺失 |
| EmbeddingProvider | ❌ | 缺失 |
| HistoryCompressor | ❌ | 缺失 |
| FTS5 表 | ❌ | MemoryDao 仅 keyword LIKE |
| MemoryManageScreen | ❌ | 缺失 |

**Phase 4 完成度：约 5%**（仅有基础 MemoryModule + keyword 搜索）

### 3.6 Phase 5（设置完善 & 打磨）

| 要求 | 状态 | 差距 |
|------|:---:|------|
| GeneralSettingsScreen | ❌ | 缺失 |
| 暗色主题 | ❌ | 仅 light theme |
| ConfigMigration | ❌ | 缺失 |
| ModulePermission | ❌ | requiredPermissions 仅为 List<String> |
| 数据导出 | ❌ | 缺失 |

**Phase 5 完成度：约 0%**

---

## 四、关键发现

### 4.1 安全风险

| 风险 | 严重度 | 详情 |
|------|:---:|------|
| **SecureKeyStore 明文存储** | 🔴 高 | 使用 `context.getSharedPreferences` 明文存储 API Key，任何有 root 权限或能访问 `/data/data/com.zero.v2/shared_prefs/zero_v2_keys.xml` 的应用可读取 |
| API Key 在 logcat 泄漏 | 🟡 中 | SecureKeyStore 在 `put()`/`getString()` 中 log 了 Key 的后 4 位和长度 |
| config 无加密 | 🟡 中 | Room 数据库无加密，ModuleConfigEntity 的 configJson 明文存储 |

### 4.2 架构债务

| 债务 | 严重度 | 详情 |
|------|:---:|------|
| **LLM 单体架构** | 🔴 高 | LLMClientModule→ModelRouter→Adapters 三层耦合，需按 Phase 1 拆分为独立模块 |
| **FC 未接入对话** | 🔴 高 | ChatViewModel.sendMessage() 传 `emptyList<FunctionDefinition>()`，整个 FC 系统在对话中未激活 |
| **AutomationModule 全 Stub** | 🔴 高 | 5 个 FC 返回占位符，整个自动化系统是空壳 |
| **无对话持久化** | 🔴 高 | ChatHistory 仅内存，App 重启丢失所有对话 |
| **导航结构需改** | 🟡 中 | 3 Tab 为对话/模块/日志，需改为对话/自动化/设置 |
| ApiKeyProvider 无效 | 🟡 中 | 从 System.getenv() 读 Key，Android 上永远返回空 |

### 4.3 已完整实现的标杆模块

这些模块可以作为后续开发的参考模板：

| 模块 | 作为什么的标杆 |
|------|-------------|
| **WebSearchModule** | FC 函数实现标杆：完整 API 调用 + SSE 解析 + 错误处理 + 结果格式化 |
| **SystemEventModule** | ITimedModule 标杆：PriorityQueue + 心跳 + 8 个 FC 全实现 + 事件解耦 |
| **MemoryModule** | Room 集成标杆：Mutex 写锁 + FC + intelligentMemorize 自动触发 + 启发式规则 |
| **ChatBotModule** | 核心引擎标杆：Mutex 生成锁 + MessagePipeline + ContextBuilder + LLM Stream + ChatBus 事件全流程 |
| **ModuleDetailScreen** | Compose UI 标杆：AutoForm + API Key 管理 + 配置持久化 |

### 4.4 死代码

| 文件 | 详情 |
|------|------|
| `PermissionOnboardingScreen.kt` | 完全实现但未接入任何导航路由，Manifest 也缺少对应权限声明 |
| `ApiKeyProvider.kt` | 占位实现，实际 Key 通过其他路径注入 |

---

## 五、可行性评估

### 5.1 核心问题：能否在当前基础上开发？

**答案：可以，且基础非常扎实。**

理由：

1. **模块系统已生产级**：ModuleLifecycleManager 的 safeCall 崩溃隔离、Hilt @IntoSet 多绑定、有序启动/逆序销毁，均已完整实现
2. **事件总线已成熟**：ChatBus + SystemBus 的差异化缓冲策略、MessagePipeline 责任链、SystemEvent→Automation 事件解耦，全部就位
3. **FC 管道已打通**：FunctionRegistry 的注册/分发/并行执行/SecurityInterceptor 框架、WebSearch/Vision 的成功案例，证明 FC 基础设施是工作的
4. **Room 已集成**：数据库、DAO、Entity、debounce 写后，数据层基础设施完备
5. **参考实现丰富**：WebSearchModule（FC 标杆）、SystemEventModule（ITimedModule 标杆）、MemoryModule（Room 标杆）、ChatBotModule（引擎标杆）

### 5.2 需要立即修复的阻塞项（P0，开发前）

| # | 阻塞项 | 影响 |
|---|--------|------|
| 1 | **启用 FC 到对话** | ChatViewModel 中 `emptyList<FunctionDefinition>()` → `functionRegistry.getAllDefinitions()` |
| 2 | **SecureKeyStore 加密** | 替换为 EncryptedSharedPreferences 或 Android Keystore |
| 3 | **导航改为 对话/自动化/设置** | 3 Tab 结构必须先调整，后续 UI 才能接入 |

### 5.3 按 Phase 的开发优先级

```
Phase 0 (最高优): 产品闭环 — 让 App 可日常使用
  1. ConversationEntity/DAO + MessageEntity/DAO + ChatViewModel 持久化
  2. CharacterEntity/DAO + CharacterManageScreen
  3. SettingsScreen（API Key 配置 + 主题 + 关于）
  4. ConversationHistoryDialog
  5. PreferencesManager (DataStore)

Phase 1 (高优): LLM 模块拆分 — 架构债务清理
  1. 删除 ModelRouter / LLMClientModule(改为组装者) / Adapter(改为 IZeroModule)
  2. 新建 DeepSeekChatModule / QwenChatModule（继承现有 Adapter 逻辑）
  3. ILanguageModel + capabilities + ModelSelector
  4. Secret/Config 分离（apiKeyRef）
  5. 导航结构切换（对话/自动化/设置）

Phase 2 (高优): FC 上线
  1. 启用 ChatViewModel FC
  2. ChatBotModule FC 循环完善
  3. SecurityInterceptor UI 弹窗接通
  4. ToolDefinition Manifest

Phase 3 (中优): 自动化
  1. AutomationModule 实现（复用 ExecutionGuard + JsonExtractor）
  2. AutomationScreen + CreateAutomationScreen + AutomationDetailScreen
  3. 完整状态机
  4. AgentTraceEvent Room 持久化独立表
  5. AgentRuntime（可在 Phase 3 后期或独立 Phase）

Phase 4 (中优): Memory Evolution
Phase 5 (低优): Settings 完善 & 打磨
```

### 5.4 估量

| Phase | 新增文件 | 修改文件 | 删除文件 | 约时间 |
|-------|---------|---------|---------|--------|
| Phase 0 | ~12 | ~5 | 0 | 1 周 |
| Phase 1 | ~8 | ~6 | ~5 | 1.5 周 |
| Phase 2 | ~3 | ~4 | 0 | 1 周 |
| Phase 3 | ~12 | ~3 | 0 | 2.5 周 |
| Phase 4 | ~8 | ~2 | 0 | 2 周 |
| Phase 5 | ~6 | ~4 | 0 | 1 周 |
| **总计** | **~49** | **~24** | **~5** | **9 周** |

---

## 六、架构质量评分

| 维度 | 评分 | 说明 |
|------|:---:|------|
| 模块化设计 | 9/10 | IZeroModule 契约完备，Hilt 多绑定优雅 |
| 事件驱动解耦 | 9/10 | ChatBus/SystemBus 缓冲策略差异化、责任链预处理 |
| 崩溃隔离 | 9/10 | safeCall + Core/非Core 差异化策略 + 自动禁用 + reEnable |
| FC 基础设施 | 8/10 | 注册/并行/安全分级完整，SecurityInterceptor 未接通 UI |
| LLM 集成 | 7/10 | SSE 流解析完整、fallback/降级到位，但架构需拆分 |
| 数据层 | 5/10 | 仅 2 个 Entity，缺少核心业务表 |
| 可观测性 | 7/10 | AgentTrace 设计好、全链路埋点，但仅内存存储 |
| UI 完整度 | 5/10 | 模块管理完整，对话基础，但缺自动化/设置/角色/记忆 UI |
| 安全性 | 4/10 | API Key 明文存储是硬伤 |
| 代码质量 | 8/10 | 注释完整(中文)、模式一致、命名规范 |

**综合评分：7.1/10**

---

## 七、总结

### 当前状态

Zero V2 已经是一个**架构基础非常扎实**的 Android AI Agent 框架。12 个模块、25 个 FC 函数、完整的事件总线和崩溃隔离机制都已投入运行。WebSearch、Vision、Memory、SystemEvent 四个模块完全可用，是最好的参考实现。

### 核心差距（按紧迫度）

1. **无对话持久化** — Conversation/Message Entity 缺失，ChatHistory 仅内存
2. **FC 未接入对话** — 基础设施完备但 ChatViewModel 中 FC 被禁用
3. **LLM 集中式架构** — 需拆分为每提供商独立模块
4. **AutomationModule 全 Stub** — 整个自动化系统是空壳
5. **API Key 明文存储** — 安全风险需立即修复
6. **导航结构错误** — 需从 对话/模块/日志 改为 对话/自动化/设置

### 建议的启动路径

```
第 1 步 (P0 阻塞修复，0.5天):
  - 启用 FC: ChatViewModel 中传 functionRegistry.getAllDefinitions()
  - SecureKeyStore 切换到 EncryptedSharedPreferences
  - 导航改为 对话/自动化/设置

第 2 步 (Phase 0 MVP，1周):
  - ConversationEntity/DAO + MessageEntity/DAO
  - ChatViewModel 持久化
  - CharacterEntity/DAO + CharacterManageScreen
  - SettingsScreen
  - PreferencesManager

第 3 步 (Phase 1 模块拆分，1.5周):
  - 拆分 LLM 为独立模块
  - ILanguageModel + ModelSelector
  - Secret/Config 分离

此后按 Phase 2→3→4→5 推进
```

**结论**：当前代码库完全可以在上面继续开发。架构设计的正确性和已有代码的质量都为后续开发提供了坚实基础。最需要优先解决的不是架构问题，而是"用户闭环"——让 App 先能正常聊天、存历史、切换角色。
