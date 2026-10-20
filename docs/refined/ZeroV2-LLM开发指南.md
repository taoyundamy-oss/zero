# Zero v2 — LLM 编码智能体开发指南

> 本文档面向在 Zero v2 仓库中**开发新模块、添加新功能**的 LLM 编码智能体（如 Claude Code）。
> 目标：确保新代码符合统一开发规范，且**不产生阻塞、不破坏现有功能**。

---

## 〇、文档定位与权威

### 本文档与其他文档的关系

Zero v2 已有**分轨提示词文档**（主轨/规范化轨/云服轨/收束轨/模块化轨各一份 `*开发提示词.md`），它们规定各轨**在该轨范围内**的开发规则。本指南**不替代**它们，而是提供**跨轨通用的、面向"新增模块/功能"这一场景**的开发规范与配方。

**阅读顺序**：先读本指南建立全局认知 → 进入具体轨时读对应轨的提示词 → 写代码前必读 `ARCHITECTURE_FINAL.md` 的相关章节。

### 文档权威层级（冲突时按此序裁决）

1. `ARCHITECTURE_FINAL.md`（根）——架构真相源：类结构、函数定义、安全守卫。
2. `docs/refined/DEVELOPMENT_DESIGN.md`——战略权威：设计原则与决策记录 (D1–D57+)。
3. 对应轨设计文档（如 `ZeroV2-收束开发设计.md`）。
4. 对应轨落地实施计划（如 `ZeroV2-收束落地实施计划.md`）的 SR/C/G 交叉验证表。
5. 本指南 + 根 `CLAUDE.md`。

### 一条铁律

> **当你对实现选择不确定时：先查设计文档 → 再查实施计划 SR/C/G 表 → 仍无答案时，向用户提问，而不是自行决定。**

"找到更好的方案"**不是**修改已封板决策（如收束轨 D115–D126）的理由——它只能作为 backlog 提案。用户裁定的基线不可违背。

---

## 一、开发前必读

### 1.1 必读清单

| 文档 | 必读理由 |
|---|---|
| `ARCHITECTURE_FINAL.md` | 类结构/函数签名/安全守卫——写代码前确认契约 |
| `CLAUDE.md`（根） | 项目级开发规约：架构原则 + 自主开发循环 |
| `docs/refined/CODEBASE_AUDIT.md` | 标杆模块清单 + 历史缺陷 |
| 本指南「四、地雷清单」 | 避免重蹈已知覆辙 |

### 1.2 必须先理解的架构事实

- **所有能力都是 `IZeroModule`**，经 Hilt `@IntoSet` 收集为 `Set<IZeroModule>`，由 `ModuleLifecycleManager` 按 `launchOrder` 排序初始化。模块间**绝不直接持有引用**。
- **三种通信模式**：事件总线 (ChatBus/SystemBus) / Hilt 单例注入 / FunctionRegistry 分发。
- **核心崩溃隔离**：`ModuleLifecycleManager.safeCall()` 包裹所有模块调用——Core 模块崩溃只通知不禁用，非 Core 崩溃自动禁用。
- **Room 数据库** `ZeroDatabase` 当前版本 **14**，开启 `fallbackToDestructiveMigration()`——这意味着**任何遗漏 Migration 的 schema 变更会静默清库**（见地雷 X9）。
- **依赖方向严格单向**：`app → framework → services → core`。`framework:*` 可依赖 `core:data`，**绝不能依赖 `app`**（见地雷 X12）。

---

## 二、核心开发规范（跨轨通用）

以下规则在所有轨的提示词中**共通**，违反任一条都可能破坏现有功能或阻塞构建。

### R1. 每次只做一个 Step，改动文件数受限

- **Android 改动 ≤ 3 个文件 / Step**；超出则拆分为子步骤。
- **云服改动 ≤ 1 个逻辑单元 / Step**。
- **例外**：Room Migration 文件组（Entity + Dao + DB 版本 + MIGRATION）必须**同一提交**（地雷 X9），不可拆分。
- 一个 Step 只有一个目标，**禁止夹带无关改动**。

### R2. 先读后写

- 修改任何现有文件前，**先用 Read 工具确认其当前内容**。
- 改动核心文件（`ChatBotModule` / `McpClientModule` / `TriggerHub` / `ChatViewModel` / `ChatBusImpl` / `ModuleLifecycleManager` 等）前，**先读相关段落**再动笔。

### R3. 参照标杆模块

新代码的风格、模式必须与现有代码一致。**写新代码前，先读对应标杆**：

| 你要写的东西 | 标杆模块 / 文件 |
|---|---|
| 带.FC 的服务模块 | `services/.../module/WebSearchModule.kt`（FC 实现标杆） |
| 周期性/timed 模块 | `services/.../module/SystemEventModule.kt`（ITimedModule 标杆） |
| Room 集成 + Migration | `framework/memory/.../MemoryModule.kt` + `core/data/.../MemoryDao.kt` + `MIGRATION_*` |
| Compose 模块详情页 | `app/.../ui/ModuleDetailScreen.kt` |
| DI 绑定 | `app/.../di/AppBindingModule.kt`（`@IntoSet` 中枢） |
| 核心引擎 | `framework/chat/.../ChatBotModule.kt` |
| Provider 抽象/网关 seam | `zero-cloud/gateway/tools/wechat.py`（D97 seam 标杆） |
| 云端 worker 任务 | `zero-cloud/worker/tasks/` 现有模式 |
| 自动化执行 | `services/.../module/AutomationModule.kt`（PLAN/REACT 标杆） |

### R4. 埋 AgentTrace

每个关键集成点加一句 `traceManager.record(...)`。**静默失败路径必须留 trace**（E28 铁律）——不得出现"出错就吞掉、无任何记录"的分支。Trace 调用失败会静默降级为仅内存，不阻塞业务，故可放心埋点。

### R5. 不改 `build.gradle`，除非 Step 明确要求新增依赖

新增依赖必须在 Step 描述中显式声明理由。禁止为图方便引入未评估的库。

### R6. 构建验证强制

- Android 改动：`cmd //c "D:\ZeroV2\_build_apk.bat"`（或 `./gradlew assembleDebug`），验 APK 时间戳更新。
- 云服改动：`docker compose` 重建 + `curl` 验证 + 回归 `bash scripts/test_mcp.sh`（9 用例全过）。
- **构建失败不得标记 `[x]`**，必须读编译输出、自诊断、自修复后重试。

### R7. 真实偏差要记录，不得静默漂移

实现中发现设计与现实不符：先收集证据（错误日志/复现）→ 记录到对应轨的 E-lesson 表 → 同步两份文档 → 再继续。**禁止"我觉得设计不对就按自己想法改"的静默漂移**。

### R8. 每步完成后输出格式

```
### Step X.Y 完成: [Step 名称]

**改动文件**:
- 新增: path/to/NewFile.kt (N 行)
- 修改: path/to/ChangedFile.kt (变化: 简要描述)

**验证要求**:
- [ ] Build 通过
- [ ] 具体功能验证点

**如果 Build 失败**: 请粘贴完整错误信息，我会立即修复。
```

---

## 三、新增模块 / 新增功能 配方

> 配方中的类名/文件名均为仓库实际路径。先读标杆再套配方。

### 3.1 配方 A：新增一个 IZeroModule 服务模块

**场景**：新增一个独立能力模块（如新的系统感知、新的工具集）。

1. **定位模块元数据**
   - `id`：小写 snake_case（如 `screen_capture`、`work_files`、`alarm`）。
   - `category`：`CORE` / `INSTRUMENT` / `SYSTEM` / `AUTOMATION`。
   - `launchOrder`：参照现有——Core 引擎负值大（`ChatBotModule`=-200、`AgentRuntime`=-180、`TriggerHub`=-165、`Scheduler`=-170、`FunctionRegistry`=-50）；服务模块正值（`SystemEventModule`≈+100，后台 +200）。

2. **创建模块类**，实现 `IZeroModule`（周期性任务实现 `ITimedModule`）。在 `core/module-api` 的 `IZeroModule.k` 契约下实现：
   ```
   metadata / lifecycle / isEnabled / config
   onAwake()      // 注册 FC、读配置、初始化状态
   onStart()      // 订阅 ChatBus/SystemBus、启定时器
   onDestroy()    // 取消订阅、释放资源
   registerFunctions()
   checkAvailability()
   ```
   标杆：`WebSearchModule.kt`（FC 模块）、`SystemEventModule.kt`（timed）。

3. **声明配置**：继承 `ModuleConfigBase`，字段用 `@ConfigField` 注解。
   - 数值字段用 `Int` / `Float`（**必须显式给 `min`/`max`**，见地雷——默认 max=100 是历史 footgun）；开关用 `Boolean`；文本/选择类按需用 `TEXT` / `DROPDOWN` / `MULTILINE`（String 承载——`ConfigFieldType` 枚举、AutoForm 渲染与 kotlin-reflect 写回均支持，S4.1 语音引擎切换即 String `DROPDOWN` 先例）。*(2026-09-11 用户裁定修订：原"类型仅 Int/Float/Boolean（D30 约束）"与代码现实不符——D30 实为 kotlin-reflect 反射规范，非类型限制。)*
   - **常数/约定不做成配置字段**——仅真正需用户调节的才进 Config（如目录名常量会使既有数据键失效，见音乐轨 MD15 `MusicPaths.SUBDIR` 先例）。
   - `SECRET` 字段（API Key）**不进 AutoForm**，走 `SecureKeyStore`，key 格式 `"{moduleId}:{provider}"`，可用 `getOrFallback` 复用。
   - 标杆：`MemoryModuleConfig`、`ChatBotModuleConfig`。

4. **在 `app/.../di/AppBindingModule.kt` 用 `@IntoSet` 绑定**：
   ```kotlin
   @Provides @IntoSet fun provideYourModule(m: YourModule): IZeroModule = m
   ```
   （`AgentJob` / `ITimedModule` 若实现也要分别 `@IntoSet` 绑定到对应集合。）

5. **注册 FC（若需要）**：在 `onAwake()` / `registerFunctions()` 调
   ```kotlin
   functionRegistry.register(
     FunctionDefinition(name, description, parametersJsonSchema, securityLevel, dependsOn),
     sourceModuleId = metadata.id,
     permission = ActionPermission.AUTO,  // 或 CONFIRM / FORBIDDEN
     handler = { args -> FunctionResult(...) }
   )
   ```
   FC 名一经发布**保持稳定**（LLM 已熟悉），重构只改模块 id 不改 FC 名。

6. **订阅总线（若需要）**：在 `onStart()` 订阅 `chatBus.onChatFinished` / `systemBus.onTick` 等，`onDestroy()` 反订阅。

7. **埋 AgentTrace**：关键路径加 `traceManager.record(...)`。

8. **构建验证**：R6。Android 端无障碍服务/权限在 `AndroidManifest.xml` 声明（参照现有 `<uses-permission>`）。

### 3.2 配方 B：新增一个 FC（Function Calling）功能

**场景**：给已有模块加一个 LLM 可调用的工具函数。

1. 在所属模块的 `registerFunctions()` / `onAwake()` 内 `functionRegistry.register(...)`。
2. `FunctionDefinition` 字段：
   - `name`：稳定、语义化（如 `read_notifications`、`set_reminder`）。
   - `description`：给 LLM 看的清晰描述。
   - `parameters`：JSON Schema（Map）。
   - `securityLevel`：`SAFE` / `SENSITIVE`（旧），优先用统一 `permission = AUTO/CONFIRM/FORBIDDEN`。
   - `dependsOn`：声明与其他 FC 的依赖（`executeBatch` 据此并行/串行）。
3. `ActionPermission` 选择：
   - `AUTO`：无副作用或用户已授权代理（读类、用户主权委托类）。
   - `CONFIRM`：有副作用需弹窗确认（写通知、删日历、设闹钟）。
   - `FORBIDDEN`：禁止 LLM 直接调用。
4. 处理器 `suspend (args) -> FunctionResult`；**不得在处理器里做 100ms+ 阻塞**（那是 `IMessageFilter` 的禁忌，FC 处理器可异步但要有超时意识）。
5. 标杆：`WebSearchModule.web_search`、`SystemEventModule.set_reminder`。

### 3.3 配方 C：新增一个自主触发源

**场景**：扩展 `TriggerHub`，让 Agent 能感知新的事件类型并自主思考。

1. 在 `TriggerHub` 加触发源枚举（参照现有 8 源：`NOTIFICATION`/`BATTERY_FULL`/`BATTERY_LOW`/`NIGHT_SCREEN`/`CONTINUOUS_USE`/`WECHAT_PRIVATE`/`CLOUD_TASK`/`BACKLOG`）。
2. 加 `@ConfigField` 开关 + 阈值（用户主权，D17）。
3. 实现检测逻辑：订阅 `SystemBus`（如 `onNotificationPosted`/`onBatteryChanged`）、注册动态 `BroadcastReceiver`、或轮询 job。
4. 经门控链：**开关 → 白名单 → 去重(5min/包) → `dailyTriggerCap` → `fireTraced()`**。
5. `fireTraced()` 已统一：① 发 `SystemEventDisplay` 气泡到 `chatBus.onSystemEventInjected`（**仅 UI 投影，F8**）② 调 `autonomousJob.onTrigger(TriggerEvent)`。
6. 在 `AutonomousThinkingJob.buildPrompt()` 加对应来源路由（构造专属 Prompt；外部不可信内容用 `<untrusted_external_content source="...">` 包裹防注入，D107）。
7. 标杆：`TriggerHub.kt` 的 WECHAT_PRIVATE / CLOUD_TASK 实现。

### 3.4 配方 D：新增一个 UI 屏幕

**场景**：新增设置/管理类页面。

1. 在 `app/.../ui/` 下建 Screen + ViewModel，标杆 `ModuleDetailScreen.kt` / `ModuleDetailViewModel.kt`。
2. ViewModel 用 Hilt `@HiltViewModel` + `StateFlow`；只读流优先（`remindersState`、`modulesState`）。
3. 在 `ui/navigation/ZeroNavHost.kt` 加路由（注意：**三 Tab 固定——对话/自动化/设置**，新页放设置子路由，不得改主 Tab 结构）。
4. 配置 UI 用 AutoForm（`ModuleConfigParser` 已用 kotlin-reflect 生成 `FormFieldState`，**UI 渲染零运行时反射**）。
5. 标杆：`ModuleDetailScreen`、`TriggerSettingsScreen`。

### 3.5 配方 E：新增一个 LLM 模型 / Profile

**场景**：接入新模型或新 provider。

1. **零代码方式**（推荐）：在 `LlmProfile.defaults()` 或运行时 `LlmProfileStore` 加一条 `LlmProfile`（baseUrl/apiKeyRef/modelId/kind=CHAT）。`OpenAiChatModule.onAwake` 会自动为每个启用 profile 注册 `ProfileLanguageModel` 到 `ModelSelector`。新模型 = 加 profile。
2. **新 provider 协议适配**：实现 `LlmAdapter`（`buildExtraBody`/`parseStreamExtra`），在 `OpenAiCompatibleClient.chatStream` 已统一 SSE 解析。
3. API Key 一律走 `SecureKeyStore`，**禁止 `System.getenv()`**（Android 恒空，历史缺陷）。
4. 标杆：`DeepSeekLlmAdapter`、`QwenLlmAdapter`、`LlmProfile`。

### 3.6 配方 F：新增一个 MCP 服务

**场景**：接入外部/自建 MCP server。

1. 在 `McpServerStore` 加 `McpServerConfig`（id/name/url/apiKeyRef/enabled/autoPermission）。
2. `McpClientModule` 自动：发现启用的 server → `initialize()` → `toolsList()` → 每个工具注册为 FC `mcp_<serverId>_<tool>`（JSON Schema 透传）。
3. server 级 `autoPermission` 决定 AUTO/CONFIRM。
4. 协议约束（E3）：所有 MCP 响应必须返回可解析 JSON，**禁 204 / 空 body**。
5. 标杆：`zero-cloud` gateway、`McpClientModule`。

### 3.7 配法 G：新增 Room 持久化（实体/表）

**场景**：新增持久化数据。

1. 在 `core/data/` 加 `XxxEntity` + `XxxDao`。标杆 `MemoryEntity` / `MemoryDao`。
2. `ZeroDatabase` 加实体、**版本号 +1**、写 `MIGRATION_X_Y`。
3. ⚠️ **Entity + Dao + DB 版本 + Migration 必须同一提交**（地雷 X9）。旧库升级是 L3 必测项。
4. `DatabaseModule` provide 新 DAO。
5. **写操作用 `Mutex` + `@Transaction`**（安全守卫 #8）。
6. 配置持久化：非敏感走 `ModuleConfigStore`（`module_configs` 表，300ms 防抖）；敏感走 `SecureKeyStore`。**`config_json` 不得含 API Key**。

---

## 四、地雷清单（务必回避）

| 编号 | 地雷 | 正确做法 |
|---|---|---|
| **X9** | `ZeroDatabase` 开 `fallbackToDestructiveMigration()`——遗漏 Migration 的 schema 变更**静默清库** | Entity+Dao+版本+Migration **同一提交**；旧库升级必测 |
| **§7.1** | `@ConfigField` 默认 `max` 历史曾是 `100`（忘写界的数值字段被静默夹到 0–100，如 `maxTokensPerRun=50000` 被夹成 100）。N4.1 已把默认改为 `Double.MAX_VALUE`——但"无上界"对领域字段本身是风险（如 `reflectionHour` 应 0–23 而非 0–MAX） | 每个数值字段仍**显式 min/max**；`fromJson` 写时 `coerceIn`；trace `config_clamped` |
| **§5.1** | FC 循环硬编码 5 轮截断 + 无末尾综合轮 + 无分页 → "中断回复" | `maxFcRounds` 为 ConfigField（默认 16，[6,30]）；末轮强制综合（`functions=emptyList()`）；长结果分页带 `has_more` |
| **X12** | `framework:*` 误依赖 `app` → 不编译（`ConversationRepository` 在 app 层） | framework 用 `core:data` 的 DAO 直接访问；如需跨层，在 `framework/.../build.gradle.kts` 同提交加 `core:data` 依赖 |
| **X15** | 上下文窗口起点落在 `assistant(toolCalls)` 与其 TOOL 结果之间 → OpenAI API 400 "orphan TOOL" | 任何窗口切分**跳过 TOOL 消息**作起点 |
| **X17** | 持续滑窗结构性破坏前缀缓存（97%→低） | 用**生成式压缩**（N6.6）：append-only + 罕见生成切换；压缩目标是防 1M 上限报错，不是省 token（缓存价约 1/10） |
| **E28** | 静默失败路径无任何记录 → 排障无据 | 每个静默失败分支留 `traceManager.record(...)` |
| **B11** | Termux/Shizuku 不可用时动态 FC 崩溃内核 | 不可用时 `dyn_/action` FC 返回引导文本，**内核（chat/memory/voice/已有 FC）不受影响** |
| **F16** | 把"聚合后批量触发"当业务实现 → 违背实时语义 | 消息接收保持**逐事件实时**；聚合仅限 UI 显示，不是 Zero 处理的输入 |
| **F17** | `set/modify_reminder` 无过去时刻守卫 + 周期推进相位丢失 → 多条挤同刻 | 设/改时拒绝过去时刻；周期推进保持原相位 |
| **F18** | 微信批量推送破坏体验与页面纯度 | 游标持久化 (`zero_wechat_cursor` 跨重启) + 仅取最新一条 |
| **C1/SR11** | 新建 RemindersModule 与 SystemEventModule FC 名冲突 | **原地增强** `SystemEventModule`，不并行新模块 |

---

## 五、安全红线与不可破坏区

下列是**已封板决策 / 用户裁定基线**，修改它们需用户显式授权，"实现更简单"不是理由。

### 5.1 不可破坏的基线（收束轨 D115–D126 + 用户裁定）

- **三 Tab 固定**：对话 / 自动化 / 设置——不新增主 Tab。
- **设置页不大改**：移除全局"模型管理"页，模型管理下沉到各模块自治（D115）。
- **执行轨迹单折叠卡**：思考 + 工具调用合并为单一可折叠叙事卡（D117）。
- **系统气泡右侧渲染**：`SystemEventDisplay` 仅会话级投影显示。
- **工作目录 AUTO 权限**：用户主权委托。
- **原地增强 `SystemEventModule`**：提醒能力加在现有模块上，不新建并行模块。
- **软件名 = Zero，版本 1.0**（versionCode=2，单调递增防升级拒绝）。

### 5.2 破坏禁区（触碰即可能伤及核心）

- **`chatHistory` 纯净**：系统事件**只进投影层**（`onSystemEventInjected` → UI），**绝不进 `chatHistory`**——否则污染 N6.6 生成式压缩、持久化、LLM 发送视图。
- **N6.6 生成式压缩**：不得回退到持续滑窗（破坏缓存命中）。
- **D19 单一 tick 源**：`AgentRuntime.onTick` 是唯一心跳信号，不得另立 tick 源。
- **FunctionRegistry 唯一通信**：FC 注册/分发只经 `FunctionRegistryModule`，不得另立并行注册表。
- **D57 SAF 边界**：文件操作经 Storage Access Framework 自然护栏。
- **BudgetGuard 存在性**：预算熔断器不得移除；守卫自身故障 **fail-open**（放行+告警），绝不砖化对话。
- **`safeCall` 包裹**：所有模块调用经 `ModuleLifecycleManager.safeCall()`。

### 5.3 通用安全契约

- Room 写：`Mutex` + `@Transaction`。
- `MessagePipeline`：3s 超时；`IMessageFilter.process()` 100ms 契约（禁网络/DB/IO/大文本回溯）。
- ChatBus buffer：流式 `DROP_OLDEST` / 关键 `SUSPEND` 分流，不得擅自改既有流的策略。
- API Key：`SecureKeyStore` (AES-256-GCM + Keystore)，`getOrFallback` 复用；不得明文进 Room / logcat / `config_json`。
- LLM 外部内容：用 `<untrusted_external_content source="...">` 包裹，作为数据非指令（D107 防注入）。
- SSE/HTTP：`Flow.awaitClose { call.cancel() }` 取消契约，防后台空耗 token。

---

## 六、命名规范

| 对象 | 规范 | 示例 |
|---|---|---|
| 模块 `id` | 小写 snake_case | `web_fetch`、`mcp_client`、`speech_tts` |
| `ModuleCategory` | `CORE` / `INSTRUMENT` / `SYSTEM` / `AUTOMATION` | — |
| FC 名 | 稳定、语义化，重构不改名 | `read_work_file`、`set_reminder` |
| MCP 注册 FC | `mcp_<serverId>_<tool>` | `mcp_zero-cloud_wechat_send` |
| 动态模块 FC | `dyn_<id>_<tool>` | — |
| `ActionPermission` | `AUTO` / `CONFIRM` / `FORBIDDEN` | — |
| `ConfigField` | `label`/`description` 用户可读（无 D 编号/术语）；数值 Int/Float 显式 min/max、开关 Boolean、文本/选择类 TEXT/DROPDOWN/MULTILINE（String）按需；常数不进 Config；SECRET 不进 AutoForm | — |
| `launchOrder` | Core 引擎负值大、服务正值 | `ChatBot`=-200、`TriggerHub`=-165 |

---

## 七、何时不自行决定（提问触发器）

出现以下情况，**停下并向用户提问**，不得自决：

1. 需要修改 §五 中任一基线 / 禁区 / 已封板决策（D115–D126）。
2. 需要改 `build.gradle` 引入新依赖（除非 Step 已声明）。
3. 需要改模块边界 / 通信机制 / 数据层结构（属"未授权架构重构"）。
4. 设计文档与实施计划 SR/C/G 表对同一问题给出冲突答案，无法自行裁决。
5. 需要在"已决策方案"上做替代设计（"我觉得这样更好"）——只能作为 backlog 提案，须经用户同意才动。
6. 涉及真机权限/设备实测项（标"待用户实测"的，不阻塞编码但需用户验）。

**决策层级**：设计文档 → SR/C/G 表 → 仍无答案 → **提问**。

---

## 八、相关文档索引

| 轨 | 提示词 | 设计 | 实施计划 |
|---|---|---|---|
| 主轨 | `docs/refined/CLAUDE_CODE_PROMPT.md` | `docs/refined/DEVELOPMENT_DESIGN.md` | `docs/refined/CLAUDE_CODE_IMPLEMENTATION_PLAN.md` |
| 规范化(N) | `docs/refined/ZeroV2-开发前规范化开发提示词.md` | `…规范化设计.md` | `…规范化落地实施计划.md` |
| 模块化(M) | `docs/refined/ZeroV2-模块化开发提示词.md` | `…模块化架构设计.md` | `…模块化落地实施计划.md` |
| 收束(S) | `docs/refined/CLAUDE_CODE_PROMPT_SHOUSHU.md` | `…收束开发设计.md` | `…收束落地实施计划.md` |
| 音乐(MU) | `docs/refined/ZeroV2-音乐模块开发提示词.md` | `…音乐模块开发设计.md` | `…音乐模块落地实施计划.md` |
| 知识库(KB) | `docs/refined/ZeroV2-知识库开发提示词.md` | `…知识库设计.md` | `…知识库落地实施计划.md` |
| Assistant(AS) | （已并入 KB 轨 · 提示词用 KB 档） | `…知识库设计.md` §十八（探索存档：`ZeroV2-Assistant化与语音唤醒探索.md`） | `…知识库落地实施计划.md` Phase KB3 |
| 云服(C) | `docs/refined/ZeroV2-云服开发提示词.md` | `…云服模块化架构设计.md` | `…云服落地实施计划.md` |
| 自主触发(H) | — | `docs/refined/ZeroV2-自主触发与健康守护设计.md` | (内含) |

进入任一轨前，先读该轨提示词的「跨会话防漂移锚点」自检，再开工。

---

*本指南随项目演进持续更新。若发现与代码现实不符，按 R7 记录并同步，不得静默漂移。*
