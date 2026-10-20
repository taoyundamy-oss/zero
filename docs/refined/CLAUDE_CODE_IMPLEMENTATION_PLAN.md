# Zero V2 Claude Code 开发落地计划

> 目标：由 Claude Code 在 D:\ZeroV2 现有代码库上逐步实现 DEVELOPMENT_DESIGN.md v3.1
> 核心约束：Claude Code 无法编译/运行 Android 项目，所有验证依赖代码审查 + 用户构建反馈
> **文档权威层级（v3.5）**：DEVELOPMENT_DESIGN（架构/决策）> ROADMAP_P5_P8（Phase/Step）> 本文档（实施细节）。P6+ Phase 定义以 ROADMAP 为准，本文档旧章节不得反推。
> 2026-08-11

---

## 〇、Claude Code 开发约束与对策

### 0.1 核心约束

| 约束 | 影响 | 对策 |
|------|------|------|
| 无法编译 | 语法错误、import 缺失、类型不匹配不可见 | 每次改动 ≤3 个文件；用户每次 Phase 完成后构建；错误反馈→修复 |
| 无法运行 | 运行时崩溃、逻辑错误不可见 | 关键路径增加 AgentTrace 埋点；RunLogScreen 可查看 trace |
| 无 Android 设备 | UI 布局、交互无法测试 | UI 代码使用标准 Compose 模式，参考已有可工作的 ChatScreen |
| LLM 输出不可靠 | JSON 解析、Reflection 结果不确定 | JsonExtractor（已有）+ RuleBasedTaskParser（已有）做降级 |
| 跨会话状态丢失 | 上次改到哪了？ | 每步改动记录在本文档的"进度追踪"节；用户可随时要求继续 |

### 0.2 Claude Code 能做好的事

- **写新文件**：遵循已有代码风格，参考标杆模块（WebSearchModule、SystemEventModule、MemoryModule）
- **改现有文件**：精确的字符串替换，不改动无关代码
- **Room Entity/DAO**：模式固定，参考 MemoryEntity/MemoryDao
- **Compose UI**：参考 ChatScreen/ModuleDetailScreen 的模式
- **Hilt DI 注册**：在 AppBindingModule 中加 `@Binds @IntoSet`
- **FC 函数注册**：模式固定，参考 WebSearchModule.registerFunctions()

### 0.3 Claude Code 做不好的事

- **一次性大量重构**：5+ 文件同时改容易出错。拆分为多次小改动
- **调试运行时错误**：看不到 logcat。需要用户反馈 + AgentTrace 日志
- **UI 微调**：间距、颜色、动画。留到后期统调
- **Gradle 依赖调试**：版本冲突不可见。尽量不新增依赖

### 0.4 用户协作模式

```
Claude Code 完成一个 Step → 用户 git commit → 用户 Build → 
  ├── 成功 → 用户反馈"通过" → Claude Code 继续下一步
  └── 失败 → 用户粘贴错误信息 → Claude Code 修复 → 重复
```

每个 Step 的改动量控制在 **可在一个会话中完成且用户能一次性 review** 的范围内。

---

## 一、Phase 0：MVP 产品闭环（预估 8 个 Step）

### Step 0.1：Room 数据表 — Conversation + Message

**创建文件**：
- `core/data/src/main/java/com/zero/v2/core/data/entity/ConversationEntity.kt`
- `core/data/src/main/java/com/zero/v2/core/data/entity/MessageEntity.kt`

**修改文件**：
- `core/data/src/main/java/com/zero/v2/core/data/db/ZeroDatabase.kt` — 加 entity + abstract fun
- `core/data/src/main/java/com/zero/v2/core/data/di/DatabaseModule.kt` — 加 DAO provider

**验证**：用户 Build 通过。Room schema 自动生成。

**参考**：`MemoryEntity.kt` 的模式。

```kotlin
// ConversationEntity.kt
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val characterId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int = 0
)

// MessageEntity.kt  
@Entity(tableName = "messages", foreignKeys = [...])
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,          // "user"|"assistant"|"system"|"tool"
    val content: String?,
    val toolCalls: String?,    // JSON, Phase 2+
    val automationRunId: String?, // Phase 3+
    val tokensUsed: Long?,
    val attachments: String?,  // JSON
    val createdAt: Long
)
```

### Step 0.2：DAO + Repository

**创建文件**：
- `core/data/src/main/java/com/zero/v2/core/data/dao/ConversationDao.kt`
- `core/data/src/main/java/com/zero/v2/core/data/dao/MessageDao.kt`

**创建文件**（app 层）：
- `app/src/main/java/com/zero/v2/app/data/ConversationRepository.kt`

**验证**：用户 Build 通过。

### Step 0.3：ChatViewModel 接入持久化

**修改文件**：
- `app/src/main/java/com/zero/v2/app/ui/ChatViewModel.kt`
  - 注入 `ConversationRepository`
  - `sendMessage()` 中：创建/更新 ConversationEntity + 插入 MessageEntity
  - `init{}` 中：加载最近对话
  - 新增 `loadConversation(conversationId)` 方法

**修改文件**：
- `framework/chat/src/main/java/com/zero/v2/framework/chat/ChatBotModule.kt`
  - `ChatMessage` 数据类增加 `id: String` 字段（已有 `timestamp`，改用 UUID）

**验证点**（AgentTrace 埋点）：
- `sendMessage()` 入口处 traceManager.record("persist_conversation", "conversationId=...")
- 持久化完成后 traceManager.record("persist_done")

### Step 0.4：ConversationHistoryDialog

**创建文件**：
- `app/src/main/java/com/zero/v2/app/ui/chat/ConversationHistoryDialog.kt`

**修改文件**：
- `app/src/main/java/com/zero/v2/app/ui/ChatScreen.kt` — TopAppBar 加历史按钮
- `app/src/main/java/com/zero/v2/app/ui/ChatViewModel.kt` — 加 `conversationList` StateFlow + `loadHistory()` 方法

**验证**：用户 Build 通过。UI 可看到对话列表。

### Step 0.5：SecureKeyStore 切换到加密

**修改文件**：
- `services/src/main/java/com/zero/v2/services/security/SecureKeyStore.kt`
  - 将 `context.getSharedPreferences` 替换为 `EncryptedSharedPreferences`
  - 保留 fallback 到明文（catch 异常时降级 + 记录 log）

**验证**：用户 Build 通过。上次已配置的 Key 可能需要重新输入（存储 key 变更）。

### Step 0.6：CharacterEntity + 基础角色管理

**创建文件**：
- `core/data/src/main/java/com/zero/v2/core/data/entity/CharacterEntity.kt`
- `core/data/src/main/java/com/zero/v2/core/data/dao/CharacterDao.kt`

**修改文件**：
- `core/data/.../ZeroDatabase.kt` — 加 entity + DAO

**创建文件**：
- `app/src/main/java/com/zero/v2/app/ui/settings/CharacterManageScreen.kt`

**修改文件**：
- `app/src/main/java/com/zero/v2/app/ui/navigation/ZeroNavHost.kt` — 设置页增加角色管理路由

### Step 0.7：SettingsScreen 骨架 + 导航重构

**修改文件**：
- `app/src/main/java/com/zero/v2/app/ui/navigation/ZeroNavHost.kt`
  - BottomNavItem: Chat("对话") / Automation("自动化") / Settings("设置")
  - Automation tab 暂用占位 Screen（"自动化功能即将上线"）

**创建文件**：
- `app/src/main/java/com/zero/v2/app/ui/settings/SettingsScreen.kt`

### Step 0.8：Phase 0 集成验证

**修改文件**：
- `app/src/main/java/com/zero/v2/app/ui/ChatViewModel.kt` — FC 接入：`emptyList<FunctionDefinition>()` → `functionRegistry.getAllDefinitions()`（一行修改）

**验证**：
- 用户 Build APK → 安装 → 测试以下流程：
  1. 打开 App → 看到对话/自动化/设置三个 Tab
  2. 聊天 → 消息正常发送和接收
  3. 退出重进 → 历史对话仍存在
  4. 设置页 → 可配置 API Key
  5. 角色管理 → 可创建/切换角色

---

## 二、Phase 1：模块框架（预估 6 个 Step）

### Step 1.1：ILanguageModel + Capability + ModelSelector

**创建文件**：
- `services/src/main/java/com/zero/v2/services/llm/ILanguageModel.kt`
- `services/src/main/java/com/zero/v2/services/llm/ModelCapability.kt`
- `services/src/main/java/com/zero/v2/services/llm/ModelSelector.kt`

**ILanguageModel 接口**：提取 DeepSeekAdapter/QwenAdapter 的公共方法。

### Step 1.2：DeepSeekChatModule（从 Adapter 重构）

**修改文件**：
- `services/.../llm/DeepSeekAdapter.kt` → 保留 HTTP 调用逻辑
- **创建** `services/.../llm/DeepSeekChatModule.kt` — 实现 IZeroModule + ILanguageModel
- **创建** `services/.../llm/DeepSeekChatConfig.kt` — apiKeyRef + modelId + temperature + maxTokens

**验证**：Build 通过。旧 LLMClientModule 暂不删除，双轨并行验证。

### Step 1.3：QwenChatModule（同上）

### Step 1.4：AppBindingModule 注册新模块 + 删除旧路由

**修改文件**：
- `app/.../di/AppBindingModule.kt` — 注册 DeepSeekChatModule + QwenChatModule
- `services/.../llm/LLMClientModule.kt` — 改为组装者，内部委托给 ModelSelector
- 暂不删除旧 Adapter 文件（等验证通过后再删）

### Step 1.5：Secret/Config 分离

**修改文件**：
- DeepSeekChatConfig / QwenChatConfig — apiKeyRef 字段替代原 apiKey 字段
- `ModuleDetailScreen.kt` — Secret 字段渲染：`********` + [修改] 按钮

### Step 1.6：Phase 1 集成验证 + 旧代码清理

**删除文件**：
- ModelRouter.kt, ModelConfigModule.kt, LLMClientModule.kt, LLMClientConfig.kt
- DeepSeekAdapter.kt, QwenAdapter.kt, CustomOpenAIAdapter.kt, ModelAdapter.kt

**修改文件**：
- ChatBotModule.kt — 注入 ILanguageModel（通过 ModelSelector）替代 LLMClientModule

**验证**：用户 Build → 聊天功能正常 → 两个模型可通过模块启用/禁用切换。

---

## 三、Phase 2：FC 上线 + 思考渲染 + 附件入口 + 角色集成 + Memory 分层（预估 9 个 Step）

> **执行顺序**: 2.1(FC Loop) → 2.1b(思考管线, 改 ChatBotModule 流式处理) → 2.1c(附件入口, 复用思考管线路径) → 2.2(Memory Tier) → 2.2b(角色集成) → 2.3(ToolRegistry) → 2.4(Tool Migration) → 2.5(Notification) → 2.6(集成验证)

### Step 2.1：ChatViewModel FC 接入 + FC Loop

**修改文件**：
- `app/.../ChatViewModel.kt` — 第 65 行：`emptyList` → `functionRegistry.getAllDefinitions()`

**修改文件**：
- `framework/chat/.../ChatBotModule.kt` — `sendMessage()` 中增加 FC 循环：收到 toolCalls → FunctionRegistryModule.executeBatch() → 结果回传 LLM

### Step 2.1b：思考过程流式渲染 + 强度控制（🆕 2026-08-12）

**背景**：DeepSeek API 支持 `thinking.type = "enabled"/"disabled"`（开关）和 `reasoning_effort = "none"/"low"/"high"/"max"`（强度）。Qwen API 支持 `enable_thinking`（开关）和 `thinking_budget`（Token 预算）。当前代码中 ChatBotModule 忽略 `thinkingDelta`，QwenAdapter 硬编码 `enable_thinking = true`，DeepSeekAdapter 未传递任何思考控制参数。

**设计原则**：ChatBotModule 统一处理 `thinkingDelta`（与具体 LLM 无关）；每个模块的 Config 独立暴露该 API 支持的控制参数。

#### 2.1b-a：ChatBotModule 思考累积管线

**修改文件**：
- `framework/chat/.../ChatBotModule.kt` — `applyStreamChunk()` 增加 `thinkingDelta` 处理，新增 `updateLastThinking(delta: String)` 私有方法，增量累积到 `ChatMessage.thinking`

```kotlin
// applyStreamChunk() 新增分支
if (chunk.thinkingDelta != null) {
    val delta = chunk.thinkingDelta!!
    val newThinking = _chatHistory.value.messages.lastOrNull()?.let {
        (it.thinking ?: "") + delta
    } ?: delta
    updateLastThinking(newThinking)
}
```

#### 2.1b-b：DeepSeek 思考控制参数传递

**修改文件**：
- `services/.../llm/DeepSeekChatConfig.kt` — 增加两个 ConfigField
- `services/.../llm/DeepSeekChatModule.kt` — chatStream 读取 config，传递参数给 Adapter
- `services/.../llm/DeepSeekAdapter.kt` — `chatStream()` 增加 `enableThinking: Boolean, reasoningEffort: String` 参数，`buildRequestBody()` 根据参数构建 `thinking` 和 `reasoning_effort`

```kotlin
@ConfigField(label = "启用思考", fieldType = ConfigFieldType.TOGGLE,
    description = "开启后 DeepSeek 先输出思考过程再生成回复")
var enableThinking: Boolean = true

@ConfigField(label = "思考强度", fieldType = ConfigFieldType.DROPDOWN,
    options = ["none", "low", "high", "max"],
    description = "none=不思考(最快) low=轻量 high=深度(默认) max=最大深度")
var reasoningEffort: String = "high"
```

```json
// 构建请求体时
{
  "thinking": {"type": "enabled"},       // enableThinking=true 时
  "thinking": {"type": "disabled"},      // enableThinking=false 时
  "reasoning_effort": "high"             // 顶层参数
}
```

#### 2.1b-c：Qwen 思考控制参数传递

**修改文件**：
- `services/.../llm/QwenChatConfig.kt` — 增加两个 ConfigField
- `services/.../llm/QwenChatModule.kt` — chatStream 读取 config 传递参数
- `services/.../llm/QwenAdapter.kt` — `chatStream()` 增加 `enableThinking: Boolean, thinkingBudget: Int` 参数；移除硬编码 `enable_thinking = true`

```kotlin
@ConfigField(label = "启用思考", fieldType = ConfigFieldType.TOGGLE,
    description = "关闭后 Qwen 跳过思考直接回复，速度更快更省 Token")
var enableThinking: Boolean = false  // 默认关闭（与旧硬编码不同）

@ConfigField(label = "思考预算 (Token)", fieldType = ConfigFieldType.SLIDER,
    min = 256.0, max = 4096.0,
    description = "思考过程的最大 Token 数")
var thinkingBudget: Int = 1024
```

```json
// 请求体
{
  "extra_body": {
    "enable_thinking": true,        // Config 控制
    "thinking_budget": 1024         // Config 控制
  }
}
```

#### 2.1b-d：思考过程 UI 渲染

**修改文件**：
- `app/.../ui/ChatScreen.kt` — `MessageBubble` 中：若 `message.thinking` 非空，在消息气泡上方渲染可折叠思考卡片

```kotlin
@Composable
private fun ThinkingBubble(thinking: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Row(modifier = Modifier.clickable { expanded = !expanded }.padding(4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("🤔 思考过程", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.weight(1f))
            Text(if (expanded) "收起" else "展开", style = MaterialTheme.typography.labelSmall)
        }
        if (expanded) {
            Card(colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Text(thinking, modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
```

**验证**：
- DeepSeek 聊天模块：设置 `reasoning_effort=none` → 发送消息 → 思考卡片不出现，回复速度明显加快
- DeepSeek 聊天模块：设置 `reasoning_effort=high` → 发送消息 → 思考卡片流式出现 → 思考完成后回复卡片渲染
- Qwen 聊天模块：设置 `enableThinking=false` → 发送消息 → 无思考，仅回复
- Qwen 聊天模块：设置 `enableThinking=true, budget=512` → 思考卡片出现但较短

### Step 2.1c：附件入口 + 图片选择（🆕 2026-08-12）

**前置条件**：Step 2.1 FC Loop + Step 2.1b 思考管线完成。附件入口可复用 ChatBotModule 的流式处理路径。

**修改文件**：
- `core/common/.../ChatEvents.kt` — `ChatMessage` 增加 `attachments: List<Attachment>` 字段
- `app/.../ChatScreen.kt` — 输入栏左侧增加 📎 `IconButton`，点击触发系统图片选择器
- `app/.../ChatViewModel.kt` — 新增 `pickImage()` 方法，处理 `ActivityResult` 回调

**新增数据模型**：
```kotlin
data class Attachment(
    val uri: String,        // content:// URI
    val mimeType: String,   // "image/jpeg"
    val type: AttachmentType // IMAGE / FILE
)
enum class AttachmentType { IMAGE, FILE }
```

**验证**：Build 通过。点击 📎 → 系统文件选择器弹出 → 选择图片 → 附件预览显示在输入框上方。

### Step 2.2：Memory Tier 分类器

**修改文件**：
- `core/data/.../entity/MemoryEntity.kt` — 增加 `tier: Int` 字段（0=临时, 1=事件, 2=事实, 3=图谱）
- `core/data/.../dao/MemoryDao.kt` — 增加 `getByTier(tier)` 查询

**修改文件**：
- `framework/memory/.../MemoryModule.kt` — `intelligentMemorize()` 写入前调用 `classifyTier()`：
  - Tier 0 → 不写入 Memory Store
  - Tier 1-2 → 正常写入
  - 新增 `classifyTier(content, context): Int` 方法（规则驱动）

### Step 2.2b：角色系统集成 + Prompt Cache 优化（🆕 2026-08-12）

**设计原则**：角色在设置中固定选择（非对话内切换），systemPrompt 全局一致 → DeepSeek/Qwen 自动缓存前缀 → 缓存命中率最大化。对标 Alife 95%+ 缓存命中率。

**修改文件**：
- `core/data/.../entity/CharacterEntity.kt` — 增加 `isActive: Boolean = false` 字段
- `core/data/.../dao/CharacterDao.kt` — 增加 `getActive(): CharacterEntity?` + `deactivateAll()` + `setActive(id)`
- `app/.../settings/CharacterManageScreen.kt` — 列表每行加 RadioButton "设为当前"；激活角色高亮

**修改文件**：
- `app/.../ChatViewModel.kt` — `init{}` 中注入 `CharacterDao`，读取活跃角色 → `chatBot.initialize(character.systemPrompt)`
- `app/.../ChatScreen.kt` — `TopAppBar` 标题显示当前角色名（如 "Zero v2 · 编程专家"）

**设计约束**：
- 角色选择在设置中完成，对话界面不提供切换入口
- systemPrompt 一旦设定，整个会话期间不变（保障缓存前缀一致性）
- 切换角色 = 新建对话（旧对话保留原角色关联）

**验证**：
- 设置 → 角色管理 → 选择「编程专家」为当前角色
- 返回对话 → TopAppBar 标题显示 "Zero v2 · 编程专家"
- 发送消息 → LLM 以编程专家人格回复
- AgentTrace / TokenUsage 中 `cachedTokens > 0`（确认缓存命中）

### Step 2.3：ToolRegistry 重构 + 统一权限

**修改文件**：
- `core/module-manager/.../FunctionRegistryModule.kt`
  - 内部 `FunctionEntry` 增加 `permission: ActionPermission` 字段
  - `register()` 方法签名加上 permission 参数
  - `executeWithSecurity()`：Phase 0-2 仅检查 AUTO/CONFIRM 两级，FORBIDDEN 保留枚举但暂不启用

### Step 2.4：Existing Tool Migration — WebSearch / Vision / ContentExtractor（🆕 2026-08-12）

**背景**：WebSearchModule、VisionModule、ContentExtractor 代码已完整存在，但注册方式仍为旧 `FunctionRegistry.register()` 模式，需适配 Step 2.3 的新 `ToolDefinition` + `ActionPermission`。**本 Step 不重新开发工具业务逻辑，仅执行接口迁移**。

**修改文件**：
- `services/.../module/WebSearchModule.kt` — `registerFunctions()` 增加 `permission = ActionPermission.AUTO`；适配新 `ToolDefinition` 格式
- `services/.../module/VisionModule.kt` — 同上，`permission = ActionPermission.AUTO`
- `services/.../module/ContentExtractor.kt` — 同上

**不修改**：HTTP 调用、SSE 解析、降级管道——这些逻辑保持不动。

**验证**：模块管理中 WebSearch/Vision 的 ToolDefinition 正确显示 AUTO 权限标签。

### Step 2.4b：ConfigField 统一规范重构（方案B kotlin-reflect）+ 日志复制（🆕 2026-08-15）

**背景**：ConfigField 注解解析曾经历 4 轮修复（FIELD target / 三层回退 / 剥离合成后缀），均因 Java 反射与 Kotlin 注解存储模型（合成成员 `xxx$annotations`）不匹配而失效。本轮确立统一规范 D30，用 kotlin-reflect 重构，一劳永逸。

**重构内容**：
- `core/module-manager/build.gradle.kts` — 新增 `implementation("org.jetbrains.kotlin:kotlin-reflect:2.1.0")`
- `core/module-manager/.../ModuleConfigParser.kt` — 改用 `KClass.declaredMemberProperties` + `KProperty.findAnnotation<ConfigField>()` + `getter.call()` 读取。删除三层 Java 反射回退（方案1/2/3 全部移除）
- `app/.../ModuleDetailViewModel.kt` — `flushConfigChanges()` 写入改用 `KProperty.setter.call()`（类型转换按 `returnType` 判断）。删除 Java 反射写入 + `$annotations` 剥离兜底
- `app/.../ui/log/RunLogScreen.kt` — 日志记录可复制：每条 Trace 加「复制」按钮 + 顶部「复制全部」

**规范（写入 D30）**：
- 所有 ConfigField 解析/写入：kotlin-reflect KProperty
- 禁止 Java 反射访问 Kotlin property
- @ConfigField 注解保持 PROPERTY 目标（不再需要 @field: 前缀）

**验证**：
- UI 修改 → 保存 → 退出重进 → 值保持（回归 D30 前 4 轮 bug）
- 运行日志每条可单独复制、可全部复制

### Step 2.5：NotificationModule + UsageStatsModule 真实实现

**修改文件**：
- `services/.../module/NotificationModule.kt` — `push_notification` FC 调用 NotificationManager
- `services/.../module/UsageStatsModule.kt` — `get_screen_time` FC 调用 UsageStatsManager

### Step 2.6：Phase 2 集成验证

**验证**：
- FC 全链路：LLM 对话中请求"帮我搜索XX" → 触发 web_search FC → 搜索结果返回 → LLM 基于结果回答
- 附件 + Vision：发图片 + "分析这张图" → 触发 vision_analyze FC → 分析结果返回
- Web/Vision 模块稳定性：经 Step 2.1c 图片入口 + FC Loop 完成端到端闭环后，首次进行强度测试
- 角色集成：设置「编程专家」为当前角色 → 聊天以专家人格回复 → 切换角色后新建对话验证
- **缓存命中率**：检查 AgentTrace / TokenUsage 中 `cachedTokens` 字段，确认连续对话中 `cachedTokens > 0` 且占比 > 70%（对标 Alife 95%）

---

## 四、Phase 3：自动化 + Agent Runtime（预估 8 个 Step）

### Step 3.1：AgentRuntime IZeroModule + Config

**创建文件**：
- `services/src/main/java/com/zero/v2/services/agent/AgentRuntime.kt`
- `services/src/main/java/com/zero/v2/services/agent/AgentRuntimeConfig.kt`
- `services/src/main/java/com/zero/v2/services/agent/AgentState.kt`

### Step 3.2：AccessibilityService 骨架

**创建文件**：
- `app/src/main/java/com/zero/v2/app/background/AgentRuntimeService.kt`

**修改文件**：
- `app/src/main/AndroidManifest.xml` — 注册 AccessibilityService + 权限声明

### Step 3.3：Scheduler + AgentJob

**创建文件**：
- `services/src/main/java/com/zero/v2/services/agent/Scheduler.kt`
- `services/src/main/java/com/zero/v2/services/agent/AgentJob.kt`（接口）

### Step 3.4：SystemEventModule 去内驱 → ReminderJob

**修改文件**：
- `services/.../module/SystemEventModule.kt`
  - 移除 `scope.launch { while(isActive) { delay(10_000) } }`
  - 改为实现 `AgentJob` 接口：`nextRunTime` 返回队列中最早的提醒时间
  - `execute()` 处理到期提醒

### Step 3.5：AutomationModule Plan-Execute 引擎

**修改文件**：
- `services/.../module/AutomationModule.kt` — 5 个 FC 从 stub 改为真实实现
  - `create_automation`: LLM 生成 ExecutionPlan → 持久化
  - `executeNow()`: Plan-Execute 循环 + ExecutionGuard

### Step 3.6：AutomationScreen + CreateAutomationScreen

**创建文件**：
- `app/.../ui/automation/AutomationScreen.kt`
- `app/.../ui/automation/AutomationViewModel.kt`
- `app/.../ui/automation/CreateAutomationScreen.kt`

### Step 3.7：AgentTraceEvent Room 持久化

**创建文件**：
- `core/data/.../entity/AgentTraceEvent.kt`
- `core/data/.../dao/AgentTraceEventDao.kt`

**修改文件**：
- `core/trace/.../AgentTraceManager.kt` — record() 方法增加 Room 写入

### Step 3.8：Phase 3 集成验证

**验证**：
- 用户开启辅助功能 → Agent 后台运行 → 测试定时提醒
- 创建自动化任务 → 验证 Plan-Execute 循环
- 查看 AgentTraceTimeline → 验证 trace 持久化

---

## 五、Phase 4：Memory Evolution + UserGraph（预估 12 个 Step）

> **设计依据**: §3.1-3.7。MemoryModule 自驱动改为通过 AgentJob 实现（统一 Scheduler 模型），不再直接订阅 systemBus.onTick。
>
> **v3.2 修订（D31/D32，2026-08-20）**: ① 检索主径改为 Embedding 余弦 + bigram 降级（FTS5 对中文无效，实测证实），**Step 4.6-pre 为 4.6 的前置条件**；② AI 回复入库治理（易逝内容 T0 过滤）并入 4.1/4.8。

### Step 4.1：MemoryType / MemoryStatus 枚举 + Entity 增强

**修改文件**：
- `core/data/.../entity/MemoryEntity.kt` — 增加字段：`type: MemoryType`, `status: MemoryStatus`, `confidence: Float`, `reinforcementCount: Int`
- `core/common/.../` — 新建 `MemoryType.kt`、`MemoryStatus.kt` 枚举
- `framework/memory/.../MemoryModule.kt` — **classifyTier 修复（D31/D32 配套）**：疑问/否定前置过滤（"还记得/吗/？"不入 T2）、词级匹配（"住在"替代"我住在"）、**AI 回复侧易逝性规则（天气/搜索结果/通知播报 → Tier 0，D32）**

### Step 4.2：MemoryDao 增强（类型/状态查询）

**修改文件**：
- `core/data/.../dao/MemoryDao.kt` — 增加 `getByType(type)`, `getByStatus(status)`, `findSimilar(id, minSimilarity)`, `getExpired(before)`

### Step 4.3：MemoryScoringEngine

**创建文件**：
- `framework/memory/.../MemoryScoringEngine.kt` — typeWeight + timeDecay + reinforcement + confidence 加权。实现 DESIGN §3.7 的评分公式。

### Step 4.4：EmbeddingProvider 接口 + Remote 实现

> **v3.2 调整（D31）**: 接口与 Remote 实现的核心部分已提前至 Step 4.6-pre（检索地基）。本 Step 保留：Local 预留实现、批量接口优化、成本与缓存策略。

**创建文件**：
- `services/.../embedding/LocalEmbeddingProvider.kt` — 本地降级预留（哈希/量化向量，离线可用）
- `services/.../embedding/EmbeddingProvider.kt`、`RemoteEmbeddingProvider.kt` — 若 4.6-pre 已建则本 Step 仅增强

### Step 4.5：MemoryConflictDetector

**创建文件**：
- `framework/memory/.../MemoryConflictDetector.kt` — 语义相似度检测 + LLM 矛盾判断。实现 DESIGN §3.5-3.6 的冲突检测流程。

### Step 4.6-pre：检索地基 — Embedding 主径基建（🆕 D31 前置，2026-08-20）

**背景**：FTS5 默认 tokenizer 不分词，中文记忆检索全 miss（实测证实）。D31 修订：Embedding 余弦为主径，bigram 为降级。本 Step 打检索地基，**未完成不得开工 4.6**。

**创建文件**：
- `services/.../embedding/EmbeddingProvider.kt` — 接口 `suspend fun embed(text: String): FloatArray` + 批量版本 + 可用性探测（原 4.4 提前）
- `services/.../embedding/RemoteEmbeddingProvider.kt` — DashScope embedding API 实现（复用 SecureKeyStore Key 链）
- `core/data/.../entity/MemoryEntity.kt` — 增加 `embedding: String?`（Base64 float 数组，懒计算）

**修改文件**：
- `core/data/.../dao/MemoryDao.kt` — `getWithoutEmbedding(limit)`（存量回填游标）
- `framework/memory/.../MemoryModule.kt` — 写路径懒算 embedding；后台回填协程（每批 20 条，失败跳过）；`semanticSearch` 改 Embedding 余弦（不可用 → 现有 bigram 降级链）

**验证**：中文问句首轮命中率（"用户住在哪里" 类）≥ bigram 基线；RunLog 记录 `embedding_backfill: done=X skip=Y`。

### Step 4.6：MemoryManager 增强（Embedding 检索 + LLM 提取）

**前置条件**: Step 4.6-pre 完成（D31）。

**修改文件**：
- `framework/memory/.../MemoryModule.kt` — `intelligentMemorize()` 增加 LLM 提取（注入 ModelSelector）；检索排序改为 **Embedding 余弦主径 + bigram 降级 + RRF 融合两路**（D31 修订，无 FTS5）+ type 加权（MemoryScoringEngine）

### Step 4.7：HistoryCompressor

**创建文件**：
- `framework/memory/.../HistoryCompressor.kt` — LLM 驱动的对话历史压缩。当 Tier 消息积累超过阈值时触发压缩。

### Step 4.8：MemoryReflectionJob（AgentJob 实现）

**创建文件**：
- `services/.../agent/MemoryReflectionJob.kt` — 实现 `AgentJob` 接口。频率由 MemoryModule ConfigField 控制（默认每天 02:00）。执行冲突检测 + 过期归档 + 重复合并 + **AI 侧记忆提炼（D32：AI 回复长期知识只经 Reflection 提炼入库，逐轮直存已在 4.1 关闭）**。

### Step 4.9：GraphExtractionJob（AgentJob 实现）

**创建文件**：
- `services/.../agent/GraphExtractionJob.kt` — 实现 `AgentJob` 接口。新记忆累积 ≥N 条时触发。LLM 提取实体/属性/关系 → UserGraph。

### Step 4.10：UserGraph Entity + DAO + Attribute

**创建文件**：
- `core/data/.../entity/UserGraphEntity.kt` — `EntityType` 枚举 + 实体
- `core/data/.../entity/UserGraphAttribute.kt` — 属性（带时间区间 + 可信度 + 来源）
- `core/data/.../dao/UserGraphDao.kt` — 实体/属性/关系 CRUD

### Step 4.11：Graph 可信度更新 + MemoryArchive

**修改文件**：
- `framework/memory/.../MemoryModule.kt` — 增加 `updateGraphConfidence()`（每月未确认衰减 0.05）、`archiveExpired()`（90 天未访问归档）

### Step 4.12：MemoryManageScreen + 图谱可视化

**创建文件**：
- `app/.../ui/settings/MemoryManageScreen.kt` — 记忆浏览 + 搜索 + FilterChip(type) + 详情
- `app/.../ui/settings/MemoryGraphScreen.kt` — 用户图谱可视化（力导向图或层级列表）

---

## 六、Phase 5：打磨 + 用户主权落地（预估 7 个 Step）

> **设计依据**: §1.3 用户主权原则 — 所有定时/频率/策略参数必须暴露为 ConfigField。DESIGN v3.1 新增: GeneralSettingsScreen、所有模块 ConfigField 用户化、ModulePermission。

### Step 5.1：Agent 自主行为设置页

**创建文件**：
- `app/.../ui/settings/AgentBehaviorScreen.kt` — Agent 模式选择 (SMART/FAST/CONSERVATIVE)、报点参数 (baseInterval/multiplier/maxDoublings)、成本控制 (每日 Token 预算/月费上限)、Reflection 开关。

### Step 5.2：所有模块 ConfigField 全面用户化

**修改文件**：
- 逐一审查所有模块的 `ModuleConfigBase` 子类，确保 DESIGN §1.3 表中的每个参数都有 `@ConfigField`：
  - AgentRuntimeConfig: baseIntervalSec/randomOffsetSec/intervalMultiplier/maxDoublings
  - MemoryModuleConfig: graphExtractionThreshold/graphExtractionModel/conflictDetectionFrequency/archiveAfterDays/compressionThreshold/enableReflection/reflectionModel
  - DeepSeekChatConfig: enableThinking/reasoningEffort (已有)
  - QwenChatConfig: enableThinking/thinkingBudget (已有)

### Step 5.3：GeneralSettingsScreen + ModulePermission（v3.3 扩容：权限中心）

**创建文件**：
- `app/.../ui/settings/GeneralSettingsScreen.kt` — 主题/模型/参数全局设置
- `app/.../ui/settings/PermissionCenterScreen.kt` — **权限中心**：全量权限矩阵分组列表（状态徽标 + 用途说明 + Intent 一键直跳各系统授权页——含 `ACTION_USAGE_ACCESS_SETTINGS`，修复三星韩版入口不可达）+ 授权状态实时自检

### Step 5.4：暗色主题 + M3 Expressive UI 翻新（v3.3 扩容）

**修改文件**：
- `app/src/main/res/values/themes.xml` — Material 3 Dark Color Scheme
- `app/.../ui/navigation/ZeroNavHost.kt` — 主题切换逻辑
- **M3E 翻新**（ROADMAP §二，布局不动）：`enableEdgeToEdge()` 强制 + WindowInsets 全量适配；Dynamic Color（12+ 回退品牌色板）；列表页去 Card 化（tonal surface/ListItem）；`ZeroTheme` 统一形状/动效。逐屏推进（Chat → 模块中心 → 自动化 → 设置族）

### Step 5.5：ConfigMigration 机制（各模块）

**创建文件**：
- `core/module-api/.../ConfigMigration.kt` — 接口：`fromVersion/toVersion/migrate()`
- 各模块实现迁移链（旧 JSON → 新 JSON 转换）

### Step 5.6：数据导出/导入

**创建文件**：
- `app/.../ui/settings/DataExportScreen.kt`
- `app/.../data/DataExporter.kt` — Conversation + Message + Memory JSON 导出
- `app/.../data/DataImporter.kt` — JSON 导入 + 校验

### Step 5.7：P0-P5 全量集成验证 + 文档交付

**验证**：
- 所有 Settings 子页面可访问、所有 ConfigField 可调
- 暗色主题切换正常
- 数据导出 → 卸载重装 → 导入 → 对话/角色/记忆恢复
- ConfigMigration 升级路径验证
- README + DEVELOPMENT_STATUS + 两份设计文档最终同步

---

## 七、Phase 6-9 — 后续路线（v3.3 重定义，详见 ROADMAP_P5_P8.md）

> 2026-08-21 阶段性评审后重定义。完整设计（决策依据/权限矩阵/Step 明细/验证标准）见 `docs/refined/ROADMAP_P5_P8.md`。
> Phase 5 完成后按序执行。

- **Phase 5（现有，扩容）**：打磨 + 用户主权 + **权限中心强化（5.3，Intent 直跳，修复三星韩版 usage-stats 不可达）** + **M3 Expressive UI 翻新（5.4 扩容：Edge-to-Edge/Dynamic Color/反 Card 化，布局不动）**
- **Phase 6：Realtime Agent**（6.1-6.10）——SAF 文件写入 / 无障碍屏幕文本（canRetrieveWindowContent=true，D39）/ 第一级级联语音（D33）/ LiveSessionService+UI / 按需截图 VL（D34）/ 文献场景词汇闭环 / 第二级云端 Realtime（升级位）
- **Phase 7：Platform Gateway + Telegram**（7.1-7.9，D35/D37）——统一消息模型 / MessageRouter / TelegramAdapter（官方 Bot API）/ 附件映射 / 白名单触发 / 后台 Job 保活；QQ OneBot 11 方案附录保留
- **Phase 8：MCP 生态**（8.1-8.6）——原 Phase 6 内容迁移，统一 ActionPermission 映射（D9 延续）
- **Phase 9：多 Agent**——远期保留，不纳入本轮

---

## 八、保证机制

### 7.1 每次改动可验证

**文件规则**：
- Phase 0/1: 改动 ≤ 3 个文件 / Step
- Phase 2+: 改动 ≤ 3 个文件或 ≤ 1 个逻辑目标单元 / Step（如"思考管线"同时改 Config/Module/Adapter/UI，可超过 3 文件但必须单一目标）

```
改动 (≤3 文件 或 ≤1 逻辑单元)
↓
L1: Static Review (import/API/DI/interface 自查)
↓
L2: 用户 Build (compile/lint/Room/Hilt)
↓
L3: Behavior (APK 安装后按验证要求人工测试)
↓
三层全部通过 → 进入下一步
任一失败 → 修复 → 重新验证
```

### 7.2 关键路径埋点

每处模块集成点增加 AgentTrace 记录：

```kotlin
// 示例：MemoryModule 写入时埋点
traceManager.record(
    traceId = "mem_${entity.id}",
    nodeType = NodeType.SYSTEM_EVENT,
    sourceModule = "memory",
    summary = "memory_written: tier=${entity.tier}, len=${entity.content.length}"
)
```

用户可通过 `RunLogScreen` 查看 trace，定位运行时问题。

### 7.3 变更边界 = Git Commit

每个 Step 完成后进行 commit。如果后续 Step 出错：

```
git diff HEAD~1  → 看改了什么
git reset --hard HEAD~1  → 回滚到上一个可工作状态
```

### 7.4 标杆模块对照

遇到不确定的实现方式时，参考以下已完整工作的模块：

| 要实现 | 参考 |
|--------|------|
| FC 函数注册 | WebSearchModule.registerFunctions() |
| ITimedModule | SystemEventModule.onTick() |
| Room 集成 | MemoryModule（Mutex + DAO） |
| Compose 表单 | ModuleDetailScreen（AutoForm） |
| 模块注册 | AppBindingModule（@Binds @IntoSet） |

### 7.5 渐进式迁移

删除旧代码的节奏：**先新建，验证通过，再删除**。

```
Step N:   新建 DeepSeekChatModule（独立模块）
Step N+1: 修改 ChatBotModule 注入 ModelSelector → DeepSeekChatModule
Step N+2: 用户验证聊天功能正常
Step N+3: 删除 LLMClientModule + ModelRouter + Adapters
```

每个中间状态都可编译运行。

### 7.6 降级策略（应对 LLM 输出不可靠）

任何涉及 LLM 生成结构化输出的地方，都有规则降级：

```
LLM JSON → JsonExtractor 4 层提取 → 失败 → RuleBasedTaskParser 默认方案
```

不依赖 LLM 输出的唯一性来保证系统正确性。

---

## 八、进度追踪

> **状态图例**: ✅ 已完成 | ⏳ 待开始 | 🔧 进行中 | ⚠️ 有阻塞

| Phase | Step | 内容 | 状态 | Git Commit | 验证结果 |
|-------|------|------|:---:|------|------|
| P0 | 0.1-0.8 | MVP 产品闭环 | ✅ | `0cbe3f4` | 全流程 ✅ |
| P1 | 1.1-1.6 | 模块框架（ModelSelector + 多模型） | ✅ | `66a50f0`~`22eceec` | 双模型切换 ✅ |
| P2 | 2.1-2.6 | FC Loop + 思考渲染 + 附件 + Memory Tier + 角色集成 + 权限统一 + Notification | ✅ | 多次 commit | 实测通过 ✅ |
| P3 | 3.1-3.8 | Agent Runtime + Scheduler + 自动化 Plan-Execute + Trace 持久化 | ✅ | `88e3e1b` | 后台运行/提醒/自动化 ✅ |
| P4 | 4.1-4.12 | Memory Evolution + UserGraph（Embedding 检索 + 冲突检测 + 反思 + 图谱） | ✅ | `88e3e1b` | 混合检索/图谱/反思 ✅ |
| P5 | 5.1-5.6 | 打磨 + 用户主权（AgentBehavior + ConfigField 用户化 + 权限中心 + M3E UI + ConfigMigration + 数据备份） | ✅ | 待 commit | Build ✅ |
| P5 | 5.7 | 全量集成验证 + v3.4/v4.0 设计修订 | 🔧 | — | D40-D43/D48-D50 入档，实测修复 10+ 项 |
| P6 | 6.1-6.5a | Realtime Agent 基础（SAF + 读屏 + R0 语音 + Service + UI） | ✅ | 待 commit | Build ✅，语音待 v4.0 重写 |
| P6 | 6.3-v4 | **LocalVoiceProvider 重写**（sherpa-onnx 全本地引擎） | ⏳ | — | 详见 ROADMAP（权威） |
| P6 | 6.6-6.10 | ScreenCapture + 词汇闭环 + 集成验证 | ⏳ | — | 详见 ROADMAP（权威） |
| P7 | 7.1-7.9 | Platform Gateway + Telegram | ⏳ | — | 详见 ROADMAP（权威） |
| P8 | 8.1-8.6 | MCP 生态 | ⏳ | — | 详见 ROADMAP（权威） |
| P9 | — | 多 Agent | 🔒 | — | 远期保留 |

---

## 九、每个 Step 的标准输出格式

Claude Code 完成一个 Step 后，输出：

```
### Step X.Y 完成

**改动文件**:
- 新增: path/to/NewFile.kt (约 N 行)
- 修改: path/to/ChangedFile.kt (变化: ...)

**验证要求**:
- [ ] Build 通过
- [ ] 打开 X 页面 → 确认 Y 功能正常

**如果 Build 失败**:
- 粘贴完整错误信息
- 我会立即修复
```

用户反馈"Build 通过"或粘贴错误信息后，继续下一步。
