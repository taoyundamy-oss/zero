# Zero v1 vs v2 功能差距分析与实现计划

> 基于 D:\Zero v1 全量源码探索 + Alife v4.2.0 设计参考
> 2026-08-11

---

## 一、v1 已有但 v2 完全缺失的功能

### 1.1 角色/人设系统 (Character System)

| v1 功能 | v1 文件 | v2 状态 | Alife 对标 |
|---------|---------|:---:|------|
| 角色 CRUD（创建/编辑/删除/切换） | `CharacterScreen.kt`, `CharacterViewModel.kt` | ❌ 缺失 | `Character.cs` + `index.json` |
| 角色系统提示词持久化 | `CharacterEntity.kt`, `CharacterDao.kt` | ❌ 缺失 | `Character.Prompt` |
| 角色生日/描述 | `CharacterEntity.kt` | ❌ 缺失 | `Character.Birthday` / `Character.Description` |
| 角色切换（ChatBot 动态更新 systemPrompt） | `CharacterManager.kt` | ❌ 缺失 | `ChatActivity` 切换角色机制 |
| 多个预设角色 | `CharacterManager.getDefaultCharacters()` | ❌ 缺失 | 角色列表 |

**实现方案**：Phase 13 角色模块
- 新增 `CharacterEntity` + `CharacterDao` (Room)
- 新增 `CharacterModule` (IZeroModule) — 管理角色 CRUD
- 新增 `CharacterScreen` + `CharacterViewModel`
- `ChatBotModule.setSystemPrompt()` 已支持，接入角色切换即可

### 1.2 设置页完整功能 (Comprehensive Settings)

| v1 功能 | v1 文件 | v2 状态 |
|---------|---------|:---:|
| API Key 管理（Qwen/DeepSeek 分别输入） | `SettingsScreen.kt` | ⚠️ 部分（仅 LLM 模块详情页） |
| 模型选择下拉框 | `SettingsScreen.kt` + `PreferencesManager.kt` | ⚠️ 部分（DROPDOWN 有，但仅 LLM config） |
| 自定义模型（添加自定义 endpoint/model） | `SettingsScreen.kt` | ❌ 缺失 |
| Temperature Slider | `SettingsScreen.kt` | ⚠️ 部分（仅在模块详情页） |
| MaxTokens Slider | `SettingsScreen.kt` | ⚠️ 部分 |
| 思考模式开关 + 强度 | `SettingsScreen.kt` + `PreferencesManager.kt` | ❌ 缺失 |
| 显示思考过程开关 | `SettingsScreen.kt` | ❌ 缺失 |
| Web搜索开关 | `SettingsScreen.kt` | ❌ 缺失 |
| 视觉识别开关 | `SettingsScreen.kt` | ❌ 缺失 |
| 深色主题切换 | `SettingsScreen.kt` | ❌ 缺失 |
| 应用信息（版本号） | `SettingsScreen.kt` | ❌ 缺失 |
| 对话导出/分享 | `ConversationScreen.kt` `shareText()` | ❌ 缺失 |

**实现方案**：Phase 14 统一设置
- 扩展 `SettingsScreen` 为全局配置中心（非仅模块管理）
- 融入 v1 的 DataStore `PreferencesManager` 模式
- 每项设置通过 `ModuleConfigBase` 模式持久化

### 1.3 记忆系统完整功能

| v1 功能 | v1 文件 | v2 状态 |
|---------|---------|:---:|
| 多级记忆压缩（L0-L8, 阈值100条） | `MemoryManager.kt` | ❌ 缺失（v2 仅有基础 CRUD） |
| LLM 驱动压缩（AI prompt 压缩） | `HistoryCompressor.kt` | ❌ 缺失 |
| ONNX 向量嵌入（BGE-small-zh） | `OnnxEmbeddingService.kt` | ❌ 缺失 |
| 向量语义搜索（余弦相似度） | `MemoryManager.semanticSearch()` | ❌ 缺失 |
| 时间衰减权重 | `MemoryManager.timeDecay()` | ❌ 缺失 |
| 访问次数加成 | `MemoryManager.accessBonus()` | ❌ 缺失 |
| 重要性系数 | `MemoryManager.importanceBonus()` | ❌ 缺失 |
| 记忆等级筛选 Chip | `MemoryScreen.kt` L0-L8 FlowRow | ❌ 缺失 |
| 记忆详情弹窗 + 相似记忆 | `MemoryScreen.kt` `MemoryDetailDialog` | ❌ 缺失 |
| 关键词提取 + 标签显示 | `MemoryManager.extractKeywords()` | ❌ 缺失 |
| 来源会话追溯 | `MemoryEntity.sourceConversationId` | ❌ 缺失 |
| LRU 淘汰（最少访问） | `MemoryDao.getLeastRecentlyAccessed()` | ❌ 缺失 |

**实现方案**：Phase 15 记忆增强
- 移植 v1 `MemoryManager.kt`（367行已验证代码）
- 移植 v1 `HistoryCompressor.kt`
- 移植 v1 `OnnxEmbeddingService.kt` + ONNX 依赖
- v2 `MemoryModule` 已有 Room 实体 → 直接接入 MemoryManager

### 1.4 自动化系统完整功能

| v1 功能 | v1 文件 | v2 状态 |
|---------|---------|:---:|
| 自然语言 → Cron 解析 | `TaskParserLLM.kt` | ❌ 缺失 |
| 规则降级解析器 | `RuleBasedTaskParser.kt` | ❌ 缺失 |
| Claude Code 风格 Plan-Review-Execute | `CreateAutomationScreen.kt` | ❌ 缺失 |
| 多方案备选 + 替代方案对比 | `CreateAutomationViewModel.kt` | ❌ 缺失 |
| 管道类型（SEARCH_AND_ANSWER / KNOWLEDGE_UPDATE / DAILY_BRIEF） | `AutomationPipeline.kt` | ❌ 缺失 |
| 知识库入库 + 去重 | `KnowledgeDao.kt`, `KnowledgeCompressor.kt` | ❌ 缺失 |
| 三级知识整合（L0-L2） | `ThreeLayerIntegrator.kt` | ❌ 缺失 |
| 搜索结果来源引用 | `AutomationRunEntity.sourcesCount` | ❌ 缺失 |
| 运行历史列表 + 状态图标 | `AutomationScreen.kt` `RunHistoryRow` | ❌ 缺失 |
| 内联执行流程面板（ExecutionFlowPanel） | `AutomationScreen.kt` | ❌ 缺失 |
| 任务暂停/恢复/立即执行 | `AutomationViewModel.kt` | ❌ 缺失 |
| Cron 表达式人类可读描述 | `CronParser.describe()` | ❌ 缺失 |
| 定时调度 + WorkManager 恢复 | `AutomationScheduler.kt` | ⚠️ 部分（Worker 骨架已创建） |
| 失败次数统计 | `AutomationViewModel.getFailedCount()` | ❌ 缺失 |
| 累计 Token 消耗统计 | `AutomationEntity.totalTokensUsed` | ❌ 缺失 |

**实现方案**：Phase 16 自动化重铸
- 移植 v1 `AutomationPipeline.kt` + `TaskParserLLM.kt` + `RuleBasedTaskParser.kt`
- 移植 v1 知识库系统 (`KnowledgeDao` + `KnowledgeCompressor` + `ThreeLayerIntegrator`)
- 新建 `CreateAutomationScreen` (Compose 重写 v1 的 Plan-Review-Execute 流程)
- 接入 v2 `AutomationWorker` (WorkManager)

### 1.5 对话增强功能

| v1 功能 | v1 文件 | v2 状态 |
|---------|---------|:---:|
| 图片附件上传 + 预览 | `ConversationScreen.kt` `AttachmentPreviewRow` | ❌ 缺失 |
| 文件附件上传 | `ConversationScreen.kt` `filePicker` | ❌ 缺失 |
| 思考过程展开/折叠 | `ConversationScreen.kt` `isThinkingExpanded` | ❌ 缺失 |
| 消息长按复制 | `ConversationScreen.kt` `combinedClickable` | ❌ 缺失 |
| 对话历史列表 + 切换 | `ConversationScreen.kt` `ConversationHistoryDialog` | ❌ 缺失 |
| 对话分享（导出文本） | `ConversationScreen.kt` `shareText()` | ❌ 缺失 |
| Token 用量实时显示 | `ConversationTopBar` `usageSummary` | ❌ 缺失 |
| 缓存命中率显示 | `TokenUsage.cacheHitRate` | ❌ 缺失 |
| 停止生成按钮 | `ConversationScreen.kt` `stopGeneration` | ❌ 缺失 |
| 重新生成按钮 | `ConversationScreen.kt` `onRegenerate` | ⚠️ 部分（有 regenerate 但无 UI 按钮） |
| Typing 指示器动画 | `ConversationScreen.kt` `TypingIndicator` | ❌ 缺失 |
| 事实图谱注入对话 | `ChatViewModel.searchRelevantFacts()` | ❌ 缺失 |
| Vision Poke 图片预处理 | `ChatViewModel.buildVisionContext()` | ❌ 缺失 |
| 记忆上下文注入对话 | `ChatViewModel.searchRelevantMemories()` | ❌ 缺失 |

**实现方案**：Phase 17 对话增强
- 扩展 `ChatScreen.kt` — 附件上传、思考折叠、历史列表、长按复制、分享
- 扩展 `ChatViewModel.kt` — Vision Poke、记忆注入、事实图谱注入
- `ChatBotModule` 已支持 applyStreamChunk → 接入 thinking 展开

### 1.6 LLM 能力

| v1 功能 | v1 文件 | v2 状态 |
|---------|---------|:---:|
| DeepSeek 客户端（含 reasoning） | `DeepSeekClient.kt` | ⚠️ 部分（重构中） |
| Qwen3Max 客户端（含 thinking） | `Qwen3MaxClient.kt` | ⚠️ 部分（重构中） |
| DeepSeek 搜索客户端 | `DeepSeekSearchClient.kt` | ❌ 缺失 |
| Qwen 搜索客户端 | `QwenSearchClient.kt` | ❌ 缺失 |
| 模型管理器（全局模型同步） | `ModelManager.kt` | ❌ 缺失 |
| 模型路由器 | `ModelRouter.kt` | ❌ 缺失（v2 曾实现，待按新方案重构） |
| Prefix Cache 追踪 | `Qwen3MaxClient` cachedTokens | ❌ 缺失 |
| Token 用量累计 | `ChatViewModel.totalUsage` | ❌ 缺失 |

**实现方案**：Phase 13 模块重构（已设计）
- 每模型独立模块：`DeepSeekChatModule`, `QwenChatModule`
- 每模块独立 Config：apiKey + endpoint + modelId + temperature + maxTokens
- 用户通过启用/禁用模块选择模型

### 1.7 数据层

| v1 功能 | v1 文件 | v2 状态 |
|---------|---------|:---:|
| 对话持久化 (ConversationEntity/DAO) | `ConversationEntity.kt` | ❌ 缺失 |
| 消息持久化 (MessageEntity/DAO) | `MessageEntity.kt` | ❌ 缺失 |
| 角色持久化 (CharacterEntity/DAO) | `CharacterEntity.kt` | ❌ 缺失 |
| 自动化持久化 (AutomationEntity/DAO) | `AutomationEntity.kt` | ❌ 缺失 |
| 运行记录 (AutomationRunEntity/DAO) | `AutomationRunEntity.kt` | ❌ 缺失 |
| 事实图谱 (FactEntity/DAO) | `FactEntity.kt` | ❌ 缺失 |
| 知识库 (KnowledgeEntity/DAO) | `KnowledgeEntity.kt` | ❌ 缺失 |
| 任务 (TaskEntity/DAO) | `TaskEntity.kt` | ❌ 缺失 |
| **已有** 记忆 (MemoryEntity/DAO) | `MemoryEntity.kt` | ✅ 已实现 |
| **已有** 模块配置 (ModuleConfigEntity) | `ModuleConfigEntity.kt` | ✅ 已实现 |
| DataStore 偏好持久化 | `PreferencesManager.kt` | ❌ 缺失 |
| StorageManager 文本存储 | `StorageManager.kt` | ❌ 缺失 |

### 1.8 平台集成

| v1 功能 | v1 文件 | v2 状态 |
|---------|---------|:---:|
| Boot 开机恢复 | `BootReceiver.kt` | ⚠️ 部分（骨架已创建） |
| FCM 推送 | `ZeroNotificationService.kt` | ❌ 缺失 |
| TaskWorker 后台任务 | `TaskWorker.kt` | ❌ 缺失 |
| 内容搜索引擎 | `ContentSearchEngine.kt` | ❌ 缺失 |
| 文件索引器 | `FileIndexer.kt` | ❌ 缺失 |
| Plugin 系统 | `PluginManager.kt` | ❌ 缺失 |
| Plugin 市场 | `PluginManifest.kt` + `PluginRegistry.kt` | ❌ 缺失 |
| ONNX 嵌入服务 | `OnnxEmbeddingService.kt` | ❌ 缺失 |
| LLM 压缩器（事实提取） | `LLMCompressor.kt` | ❌ 缺失 |
| BERT Tokenizer | `BertTokenizer.kt` | ❌ 缺失 |
| 向量存储 | `VectorStore.kt` | ❌ 缺失 |

### 1.9 模块系统（v1 的 IModule 体系）

| v1 功能 | v1 文件 | v2 状态 |
|---------|---------|:---:|
| VisionModule (视觉识别) | `services/module/VisionModule.kt` | ⚠️ 已实现但需按新方案重构 |
| WebSearchModule (网络搜索) | `services/module/WebSearchModule.kt` | ⚠️ 已实现但需按新方案重构 |
| MemoryModule (记忆) | `services/module/MemoryModule.kt` | ⚠️ 已实现但需增强 |
| ModuleRegistry (模块注册) | `services/module/ModuleRegistry.kt` | ✅ v2 升级为 ModuleLifecycleManager |
| ModuleBindingModule (DI绑定) | `services/di/ModuleBindingModule.kt` | ✅ v2 升级为 AppBindingModule |

### 1.10 UI/UX 功能

| v1 功能 | v2 状态 |
|---------|:---:|
| 5 Tab 底部导航（对话/自动化/角色/记忆/设置） | ⚠️ 3 Tab（对话/模块/日志） |
| 完整 Material 3 主题 | ⚠️ 基础主题 |
| 暗色主题支持 | ❌ 缺失 |
| 对话历史对话框 | ❌ 缺失 |
| 附件预览区 | ❌ 缺失 |
| 图片选择器 | ❌ 缺失 |
| 文件选择器 | ❌ 缺失 |
| 消息气泡差异化（User/AI） | ✅ 已实现 |
| 对话导出/分享 | ❌ 缺失 |
| 自动化 FAB 创建按钮 | ❌ 缺失 |
| 自动化卡片展开/折叠 | ❌ 缺失 |

---

## 二、与 Alife 设计对比的差距

| Alife 能力 | v2 对标 | 差距 |
|-----------|---------|------|
| `InteractiveModule<T>` 完整生命周期 | `IZeroModule` | ✅ 已实现 |
| `ITimeIterative.OnUpdate` | `ITimedModule.onTick` | ✅ 已实现 |
| `IConfigurable<T>` 配置模式 | `ModuleConfigBase` + `@ConfigField` | ✅ 已实现 |
| `[Module]` 属性声明 | `ModuleMetadata` 数据类 | ✅ 已实现 |
| `ChatBot` 事件总线 | `ChatBus` | ✅ 已实现 |
| 每模块独立 Config 类 + 独立 apiKey | ❌ | 待 Phase 13 重构实现 |
| 角色/人设系统 (Character, Prompt, Birthday) | ❌ | 待实现 |
| 多模型并存（每提供商独立模块） | ❌ | 待 Phase 13 实现 |
| XML Function Calling | ❌ | v2 选用 OpenAI FC |
| 热编译插件 (Roslyn) | ❌ | Android 不可行，改为 QuickJS 预留 |
| `SystemEventPlus` 提醒+记事本+NextTick | `SystemEventModule` | ✅ 已实现 |
| `MessageFilter` 时间注入 | `MessageFilterModule` | ✅ 已实现 |
| `Reasonix` Claude Code 编排 | `AutomationModule` Plan-Execute | ⚠️ 骨架已创建 |
| `Memory` 多级压缩 + 向量检索 | ❌ | 待增强 |
| `Browser` 真实浏览器网页抓取 | ❌ | Android 不可行，用 WebView 替代 |
| `Python` 脚本执行 | ❌ | Phase 9+ Chaquopy |
| `Skill` 渐进式文档 | ❌ | 未来 Phase |
| `MCP` 协议客户端 | ❌ | 未来 Phase |
| Live2D 桌宠 | ❌ | 不适用（纯聊天 App） |
| 语音对话 (Speech + Auditory) | ❌ | 未来 Phase |
| QQ/通讯平台 (QChat) | ❌ | 未来 Phase |
| 插件市场 (PluginMarket) | ❌ | 未来 Phase |

---

## 三、分阶段实现计划

### Phase 13: 模块重构（已设计）— 最高优先
- LLM 每提供商独立模块 (DeepSeekChat / QwenChat)
- 每模块独立 apiKey/endpoint/modelId Config
- WebSearch/Vision 独立模块 + 独立 Key
- 删除集中式 LLMClientModule / ModelRouter

### Phase 14: 统一设置 & 角色系统
- 全局 SettingsScreen（API Key 总览、模型选择、参数、主题）
- CharacterSystem（角色 CRUD + 切换 + systemPrompt 持久化）
- PreferencesManager (DataStore) 迁移

### Phase 15: 记忆增强
- 移植 v1 MemoryManager（多级压缩 L0-L8）
- 移植 v1 HistoryCompressor（LLM 驱动压缩）
- 移植 ONNX Embedding + 向量语义搜索
- FTS5 + Vector + RRF 混合检索
- MemoryScreen UI 增强（等级Chip、详情弹窗、相似记忆）

### Phase 16: 自动化重铸
- 移植 v1 AutomationPipeline + TaskParserLLM + RuleBasedTaskParser
- 移植 v1 知识库系统（KnowledgeDao + KnowledgeCompressor + ThreeLayerIntegrator）
- CreateAutomationScreen（Plan-Review-Execute 流程）
- 自动化详情页（运行历史 + 执行流程面板）

### Phase 17: 对话增强
- 附件上传（图片/文件）+ Vision Poke
- 思考过程展开/折叠
- 对话历史列表 + 切换 + 分享
- 记忆/事实图谱上下文注入
- Token 用量 + 缓存命中率显示

### Phase 18: 数据层补全
- ConversationEntity/DAO + MessageEntity/DAO
- AutomationEntity/DAO + RunEntity/DAO
- FactEntity/DAO + KnowledgeEntity/DAO
- PreferencesManager (DataStore)

### Phase 19: 平台集成
- FCM 推送
- 内容搜索引擎
- 文件索引器
- Plugin 系统 (QuickJS)
- LLMCompressor 事实提取

### Phase 20: 打磨
- 暗色主题
- 权限引导优化
- 电池优化白名单
- 崩溃恢复完善
- 端到端集成测试

---

## 四、v2 已实现且 v1 没有的功能（v2 独特优势）

| v2 功能 | 说明 |
|---------|------|
| `ModuleLifecycleManager.safeCall()` | 崩溃自愈隔离 |
| `ChatBus` + `SystemBus` 事件总线 | 完全解耦的模块通信 |
| `FunctionRegistryModule` 并发 FC | `coroutineScope + async` 并行执行 |
| `SecurityInterceptor` SAFE/SENSITIVE | FC 函数安全分级 |
| `TokenBudgetAllocator` | 15/15/20/50 配额管理 |
| `AgentTraceManager` + Timeline UI | 白盒可观测性 |
| `ModuleConfigParser` → `FormFieldState` | 零反射 AutoFormUI |
| `SystemEventModule` PriorityQueue | O(log n) 提醒调度 |
| `ContentExtractor` Readability 算法 | 网页提纯 |
| `SecureKeyStore` | 加密 Key 存储 |
| `ModelConfigStore` debounce 写入 | Room 配置持久化 |

---

## 总结

- **v1 功能总计**: ~60 项
- **v2 已实现**: ~25 项（42%）
- **v2 部分实现**: ~12 项（20%）
- **v2 完全缺失**: ~23 项（38%）
- **v2 独有优势**: ~11 项

**建议优先级**: Phase 13（模块重构）→ Phase 14（设置+角色）→ Phase 15（记忆）→ Phase 16（自动化）→ Phase 17（对话增强）→ Phase 18-20
