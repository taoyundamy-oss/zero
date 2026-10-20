# Zero V2 开发前规范化与优化设计（记录）

> 记录日期：2026-08-30 · 审查订正：2026-08-31（§5.2/§7.2/§7.3 maxFcRounds 默认 16/[6,30] 对齐；§六 补 N4.5；§5.2 F1 落点澄清）· **2026-09-03 补章 §九（对话上下文压缩接线，N6）**
> 范围：模块化重构轨开发前的**既有项目**规范化与优化（4 项已同意 + 1 缺陷修复 + 2026-09-03 上下文压缩补章）。
> 与模块化轨关系：前置；其中 LLM 重构与分页修复为模块化轨 M3 自编译的前置依赖。
> 权威：本文件记录决策与缺陷根因；实施 Step 见文末 §六（N6 见 §九 9.1）。

---

## 一、LLM 模块重构（统一 OpenAI 兼容层 + 配置/适配器）

**现状审计**：LLM-API 调用点分散，各一套 OkHttp+端点+鉴权+SSE 解析——`DeepSeekAdapter`(`api.deepseek.com/v1`)、`QwenAdapter`(DashScope compatible-mode)、`MimoTtsClient`(`api.xiaomimimo.com/v1/chat/completions`，端点/模型硬编码)、`RemoteEmbeddingProvider`(DashScope `/compatible-mode/v1/embeddings`)、`VisionModule`(qwen-vl)。抽象层 `ILanguageModel`+`ModelSelector` 仅覆盖 DeepSeek/Qwen chat。

**方案（落地 DEVELOPMENT_DESIGN §十）**：
1. **协议层 `OpenAiCompatibleClient`**（新）：一个 HTTP 客户端，复用 `@Named("llm") OkHttpClient`，统一 chat/completions(流式 SSE+非流)、embeddings、多模态；一套 SSE 解析器。
2. **配置层 `LlmProfile` + `LlmProfileStore`**：`{provider, baseUrl, apiKeyRef, modelId, capabilities, thinking 控制, extraBody}`。内置 profile：deepseek/qwen/mimo-tts/qwen-embedding/qwen-vl（可改，D17）；Key 走 SecureKeyStore。
3. **适配器层 `LlmAdapter`**（差异，保留 DeepSeek/Qwen/MiMo）：DeepSeek(`thinking.type`+`reasoning_effort`)、Qwen(`enable_thinking`+`thinking_budget`)、MimoTts(`audio.format/voice`+解析 `delta.audio`)、Embedding/Vision 端点。
4. **模块收敛**：DeepSeekChatModule+QwenChatModule → `OpenAiChatModule`（按 profile 注册多 ILanguageModel）；MiMo TTS 改用统一 client + mimo-tts profile；Embedding/Vision 同。
5. **迁移（渐进 §6.5）**：并行新建 → DeepSeek → Qwen → MiMo TTS → Embedding/Vision → 删旧 HTTP。

**收益**：新增模型=加 profile（零代码）；统一 SSE/鉴权/可观测（`llm_call: provider=model=`）；MiMo 不再硬编码端点。

---

## 二、模块合并评估（已定）

| 候选 | 建议 | 依据 |
|---|---|---|
| **WorkFiles + OfficeFiles → `FilesModule`** | ✅ 合并 | 都操作 SAF 工作目录、格式重叠、FC 同质（read/create/edit/list/delete）；合并时统一分页契约（§四 4b） |
| 语音三件套(tts/asr/vad) | ❌ 不合并 | v6.20 特意拆分（ConfigField 粒度+生命周期） |
| Vision + ScreenCapture | ❌ 不合并 | 生命周期异（常驻 vs Live 会话 MediaProjection） |
| Location+Calendar+Alarm+Notification | ⛔ 可选低优先 | 各有独立运行时权限；合并收益不大 |
| WebSearch + WebFetch | ❌ 不合并 | 语义/ToS/降级链不同 |

---

## 三、应用流畅度优化

- **截图 VL 串行高延迟**（v6.9 实测 8.4/13.3s）：主推 `read_screen_text` 无障碍主通道（白名单，毫秒级，D34）；VL 仅文本不可用时按需 + contentHash 缓存（D47）。
- **Compose 重组**（Live 动态大圆+转写滚动）：列表 `key`+`contentType`；高频状态 `derivedStateOf`；`LiveUiState` 拆细减少无关重组；稳定参数避免逐帧 lambda alloc。
- **冷启动**：sherpa assets ~220MB 后台异步提取 + splash 进度（`ModelAssetManager.syncAssetDir` 已幂等，加进度 StateFlow）。
- **Trace 热路径**：审计 hot-path 去冗余 record（气泡每帧只记必要事件）；Room persist 异步 fire-and-forget（已是）。
- **大列表**：MemoryManage/DynamicModules observeAll Flow→展示层筛（良好）+ LazyColumn `key`。

---

## 四、细节优化

### 4a. 高德 MCP `amapuri://` 不可点击
- **结果改写**（`McpClientModule.callTool`）：`amapuri://...` → `https://uri.amap.com/...`（高德 https 网关，可点击+浏览器唤起）；服务器级开关，`mcp_amapuri_rewritten` 埋点。
- **应用内拦截**（可选）：`MessageBubble` `AnnotatedString` 链接处理器捕获 `amapuri://` → `startActivity(ACTION_VIEW)` 直唤高德 App。

### 4b. `read_work_file` 无分页（致 §五 缺陷）
- **`read_work_file(file_name, offset=0)`**：返回 `{content(≤pageLength), total_len, offset, page_len, has_more} + "调用 offset=X 读下页"`，与 `read_office_file` 契约统一；`pageLength` ConfigField（默认 8000）。
- **`WorkDirRepository.readText(fileName, offset, len)`** 切片读取（大文件省内存）。
- 统一 `read_work_file`/`read_office_file` 分页契约（LLM 一套翻页逻辑）。

### 4c. `@ConfigField` 面向用户注释修订（sweep）
- 逐模块审 label+description：去 D 编号/技术黑话、写用户可读含义+建议值；SECRET 字段走 API Keys 卡片（不进 AutoForm，v5.8 先例）。
- 重点：DeepSeek temperature/maxTokens/enableThinking/reasoningEffort、MiMo voice、AgentRuntime 报点四参、Memory 8 字段、WebFetch maxDownloadMB、新增 pageLength/maxFcRounds。

---

## 五、缺陷记录：分页读取致对话界面 LLM 中断回复

### 5.1 根因（code-grounded）

**核心：`framework/chat/.../ChatBotModule.kt:212` `while (loopCount < 5)` —— FC 循环硬上限 5 轮，非 ConfigField。**

分页读大文件时每页 = 1 轮（LLM 返回 `read_work_file offset=N` → 执行 → 追加 TOOL 消息 → 下一轮 LLM 见结果继续读）。第 5 轮：

1. **5 轮截断**：第 5 轮（loopCount=5）LLM 仍返回 `read_work_file`（page 5）→ 循环体执行它并追加 TOOL 消息（`ChatBotModule.kt:248-295`）→ `while (loopCount<5)` 变 false → **循环退出，但没再给 LLM 一轮消化第 5 轮结果、产出最终文本**。多页读取被中途截断。
2. **末尾 TOOL 不触发完成事件**：`finally`（`ChatBotModule.kt:311-317`）仅当 `lastMsg.role==ASSISTANT` 才 `emit(ChatFinishedEvent)`；退出时末尾是刚执行的 TOOL 消息 → **不 emit 完成事件** → UI 显示"中断回复"（无最终回答）。
3. **复合放大**：当前 `read_work_file` 无 offset（`take(3000)`），LLM 拿不到 `has_more` → 重复同参读耗轮次 or 盲答 → 更易撞 5 轮上限。

**一句话**：5 轮硬上限截断多页读取 + 退出无"最终综合轮" + 末尾 TOOL 不触发完成事件 = 对话界面"中断回复"。

### 5.2 修复方案

| # | 修复 | 落点 |
|---|---|---|
| F1 | **轮次上限 ConfigField（D17）**：`maxFcRounds` 默认 16、范围 [6,30]（§7.2/§8.2 定论；覆盖分页读+少量动作，F2 兜底防静默中断），有界防死循环 | `ChatBotModule`（**新建 `ChatBotModuleConfig`**——现无 @ConfigField 配置；或并入 AgentBehaviorScreen 成本控制区） |
| F2 | **最终综合轮（关键，消除静默中断）**：循环因上限退出时，若末尾是 TOOL 消息（有待综合），追加一轮 `chatStream(functions=emptyList(), 系统"已达工具调用上限，基于已有信息回答")` → LLM 无工具必出文本 → 末尾 ASSISTANT → 正常 emit ChatFinishedEvent | `ChatBotModule` FC 循环退出点 |
| F3 | **大页 + has_more（4b 联动）**：`read_work_file` 返 pageLength(8000)+has_more+offset → 多数文档 1-3 轮读完 | `WorkFilesModule`/`WorkDirRepository`/`OfficeFilesModule` |
| F4 | **Trace**：`fc_loop_capped: round=N final_synthesis=true` + `read_paged: offset=N has_more=M` | `ChatBotModule`/`WorkFilesModule` |

### 5.3 验证
- 读 65KB md：offset=0/8000/... → 不中断 → LLM 读全或达上限后给"基于已读内容回答"的最终文本（非静默中断）。
- RunLog 可查 `fc_loop_capped` + `read_paged`。
- 边界：F2 最终轮 `functions=empty` 强制文本，防 LLM 再发 tool_call 陷入新循环。

---

## 六、Step 化实施计划（前置轨 N0–N4 + N4.5，独立于模块化轨 M0–M4）

| Step | 内容 | 依赖 |
|---|---|---|
| N0 | **FC 循环缺陷修复**：F1 `maxFcRounds` ConfigField + F2 最终综合轮 + F4 trace | —（最先，解除用户已见中断） |
| N1 | **read 分页**：4b `read_work_file` offset+has_more + `WorkDirRepository.readText(offset,len)` + 契约统一 read_office_file | N0（F3 依赖 F2 不中断） |
| N2 | **LLM 重构**：`OpenAiCompatibleClient`+`LlmProfile`+`LlmAdapter`（DeepSeek→Qwen→MiMo→Embedding/Vision 渐进迁移，删旧 HTTP） | 独立，可与 N1 并行 |
| N3 | **文件簇合并**：WorkFiles+OfficeFiles → `FilesModule`（统一分页契约，N1 后） | N1 |
| N4 | **细节**：4a amapuri 改写+拦截 / 4c ConfigField 注释 sweep / 流畅度（Compose/VL/冷启动） | 与 N2/N3 并行 |
| N4.5 | **BudgetGuard 强制执行**（§7.4 #4 / §八）：DataStore 读日预算/月费 + ChatBotModule FC 每轮前查余量 + Automation ExecutionGuard 接入 + 超支终止+通知；预算值至此真正生效 | N4（数值定界后） |

> N0 最先（解除已见中断）；N1/N2 并行；N3 依赖 N1；N4 + N4.5 收尾（N4.5 依赖 N4 定界）。全部为模块化轨前置，不与 M0–M4 冲突（不同文件域）。

---

## 七、数值边界审计与安全上限（4c 深化）

### 7.1 根因：`@ConfigField` 默认 max=100 脚枪

`ConfigField.kt:25-26` 注解默认 `min=0.0, max=100.0`。凡数值字段忘写 min/max 即静默套 0-100，导致：
- **真 BUG**：`AutomationModule.maxTokensPerRun` 默认 50000 > max=100 → 默认值越界，滑块无法表示/被钳到 100。
- **域错误**：`reflectionHour` 0-100（应 0-23）、`maxDoublings` 0-100（3^100 报点停摆）。
- **过窄**：`compressionThreshold`/`graphExtractionThreshold`/`archiveAfterDays` 默认近/达 max=100，无法调高。
- **过宽**：`maxSteps` 0-100（100 步烧钱失控）。

### 7.2 maxFcRounds（新字段）定论

- **默认 16**（非 12）：read_work_file pageLength 8000 字，250KB 文档 ≈ 9 轮 + 最终综合，16 覆盖；F2 最终综合轮兜底防静默中断。
- **用户可调（D17）**：暴露 AgentBehaviorScreen，范围 `[6, 30]`（§8.3 #4：下限 6 非 3，3 截断多工具任务）。
- **安全上限 30**：每轮 1 次 LLM 调用（成本+延迟），>30 基本失控；终极兜底=成本守护（§7.4 #4）。

### 7.3 全量数值项定界表

| 字段 | 当前默认 | 当前有效界 | 推荐默认 | 推荐[min,max] | 状态 |
|---|---|---|---|---|---|
| maxFcRounds（新） | — | — | 16 | [6,30] | 新增 |
| baseIntervalSec | 90 | 0-100⚠️ | 90 | [30,600] | 修界 |
| randomOffsetSec | 30 | 0-100⚠️ | 30 | [5,120] | 修界 |
| intervalMultiplier | 3.0 | 0-100⚠️ | 3.0 | [1.5,5.0] | 修界 |
| maxDoublings | 4 | 0-100⚠️ | 4 | [1,10] | 修界 |
| reflectionHour | 2 | 0-100⚠️域错 | 2 | [0,23] | 修界 |
| graphExtractionThreshold | 50 | 0-100⚠️过窄 | 50 | [10,500] | 修界 |
| archiveAfterDays | 90 | 0-100⚠️过窄 | 90 | [7,365] | 修界 |
| compressionThreshold | 100 | 0-100⚠️=max | 100 | [20,500] | 修界 |
| DeepSeek temperature | 0.7 | 0-2 ✓ | 0.7 | [0,2] | 合理 |
| DeepSeek maxTokens | 4096 | 256-1M ✓ | 4096 | [256,32768] | 收窄(1M 作硬背板) |
| DeepSeek reasoningEffort | high | [high,max]⚠️缺 | high | [none,low,high,max] | 补档(D28) |
| Qwen thinkingBudget | 1024 | 256-4096 ✓ | 1024 | [256,4096] | 合理 |
| Automation maxSteps | 10 | 0-100⚠️过宽 | 10 | [3,30] | 修界 |
| Automation maxTokensPerRun | 50000 | 0-100⚠️BUG | 500000 | [50000,2000000] | 修界+修BUG（REACT 10步≈270k，50000 仅 3-4 步阻塞） |
| Automation maxDurationMin | 5 | 0-100⚠️ | 5 | [1,30] | 修界 |
| WebFetch maxDownloadMB | 500 | 1-2048 ✓ | 500 | [1,2048] | 合理 |
| OfficeFiles maxFileSizeMB | 5 | 1-50 ✓ | 5 | [1,50] | 合理 |
| OfficeFiles pageLength | 10000 | 1k-50k ✓ | 10000 | [1000,50000] | 合理 |
| read_work_file pageLength（新） | — | — | 8000 | [1000,50000] | 新增(对齐) |
| SpeechRec 推理线程数 | 2 | 1-4 ✓ | 2 | [1,4] | 合理 |
| VoiceActivity 阈值 | 0.35 | 0.1-0.9 ✓ | 0.35 | [0.1,0.9] | 合理 |
| Melo 语速% | 100 | 50-200 ✓ | 100 | [50,200] | 合理 |

⚠️=须修（缺界/界错）；✓=合理。

### 7.4 设计方案

1. **注解默认值修正**（`ConfigField.kt`）：`max=100.0` → `max=Double.MAX_VALUE`，消除"忘写=套 100"脚枪（立即修 maxTokens=50000 越界 BUG）。缺界字段暂变无界，由 #2 补。
2. **全量数值字段显式定界**（7.3 表）：每字段写 min/max，`max` 即安全上限（UI 上限+写入钳位合一）；reasoningEffort 补 none/low/high/max 四档（D28）。
3. **写入时强制钳位**（`ModuleDetailViewModel.flushConfigChanges`/`ModuleConfigParser`）：数值写入前 `coerceIn(min,max)` + trace `config_clamped`，防直改 JSON 绕过。
4. **成本守护强制执行**（关键缺口，D17）：AgentBehaviorScreen 每日 Token 预算/月费上限**当前未强制**（STATUS 5.1"待 Kernel 预算层"）——补 `BudgetGuard`：FC 循环/自动化每轮前查余量，超支→终止+通知。此为 maxFcRounds/maxSteps/maxTokensPerRun 的终极兜底。
5. **推荐值≠安全上限分层**：UI 滑块标推荐刻度、`max`=安全上限，超推荐提示；成本守护=绝对上限。

**优先级**：#1+#2+#3 一次性（消除脚枪+定界+钳位，低改动）；#4 独立较高（兜底所有 round/step/token 失控）。并入 N4（4c ConfigField sweep）执行，#4 单列 N4.5。

---

## 八、预算项区间定论（含"最小值不破 UX"判断）

### 8.1 真实用量核算（实测数据订正）

**实测（用户 2026-08-30，DeepSeek-V4 Flash 轻量使用）**：输入 7.8M tokens ≈ ¥0.8（≈$0.11）→ 有效价 ≈ **¥0.1/M 输入**（V4 Flash + 缓存命中，远低于 DSv3 标准价）。
→ **轻量一天 ≈ 8M tokens**（非此前估算的 200-400k——低估约 25 倍）。当前默认 50000/天 ≈ 轻量日的 0.6%，**半小时即触顶中断** → 须大幅上调至百万级。
> 关键重定位：V4 Flash 极廉价（¥0.1/M）→ **token 日预算定位为"失控熔断"（百万级），真实成本控制靠"月费上限"**。原 300k 是"日常限额"思路，完全错。

### 8.2 预算项区间表

| 项 | 当前(默认/UI界) | 推荐默认 | 推荐[min,max] | 最小值为何不破 UX |
|---|---|---|---|---|
| 每日 Token 预算 `dailyTokenLimit` | 50000 / 10k-500k ⚠️过小(实测轻量日 8M) | **20,000,000** | [8,000,000, 100,000,000] | 8M=轻量日实测值，下限不切轻用户；20M=2.5x 轻量日余量；100M=失控熔断(≈$1.4/天) |
| 月费上限 `monthlyCostLimitUsd` | $5 / $1-50 | **$10** | [$5, $50] | $5≈357M tokens/月(V4 Flash 价)≈12M/天 >轻量月$3.3，不切轻用户；$10≈700M/月覆盖中度 |
| 上下文预算 `TokenBudgetAllocator.contextWindow` | 256000 硬编码/未暴露 | **256000** | [128000, 512000] | 128k 下限：低于此历史压缩过激→LLM 丢上下文→忘记中段任务(破 UX) |
| 单次执行 Token `maxTokensPerRun` | 50000 / 0-100 ⚠️BUG | **500000** | [50000, 2000000] | REACT transcript 累积：10 步≈270k、30 步≈2M；50000 仅 3-4 步即终止→阻塞多步自动化；500000 覆 10 步+余量；与 maxSteps 协同(调到 30 须同步 2M) |
| FC 轮次 `maxFcRounds`（新） | — | 16 | [6, 30] | 6 下限：保证 search→read→answer 或 2-3 页分页+综合完成；<6 截断多工具任务(破 UX) |

### 8.3 关键修正

1. **每日预算默认 50000→20,000,000**（实测轻量日 8M，300k 仍半小时触顶——V4 Flash ¥0.1/M 使百万级才合理）；UI 10k-500k → 8M-100M（对数刻度）。**token 预算定位为"失控熔断"非"日常限额"**（V4 Flash 极廉价，日常靠月费控）。
2. **月费默认 $5→$10**（实测轻量月≈$3.3，$10 留 3x 余量，覆盖全 API chat+TTS+embedding+vision）；UI 下限 $1→$5（$5≈轻量月$3.3 不切轻用户）。**月费是真实成本控制**（V4 Flash 价下 token 预算已非成本约束）。
3. **上下文预算暴露 ConfigField**（当前硬编码 256k 不可调；长对话压缩过激→丢记忆→破 UX）；[128k, 512k]。
4. **maxFcRounds 下限 6**（非 3；3 截断多工具/分页任务）。
5. **maxTokensPerRun 修 BUG + 上调**（@ConfigField max=100 越界）→ 默认 500000，[50000, 2000000]（REACT 10 步≈270k，原 50000 仅 3-4 步阻塞任务）。

### 8.4 协同与前提

- **双上限不互掐**（实测价订正）：daily=失控熔断（百万级），monthly=真实成本控制；V4 Flash ¥0.1/M 下 daily 20M×30=600M≈$8.4/月，在 $10 月费内；daily 拉到 100M 时月费兜底（月费主导）。
- **月费覆盖全 API**（chat+MiMo TTS+DashScope embedding+qwen-vl vision），非仅 chat——防 TTS/视觉绕过 chat 预算。
- **前提**：以上区间仅在 `BudgetGuard` 强制执行后生效（当前未强制，§7.4 #4 缺口）；floor 值（日 8M/$5 月/128k 上下文/6 轮/50k 每自动化）=强制执行后不破 UX 的安全下限。并入 N4.5（`BudgetGuard`）。

### 8.5 对话侧 token 不被 maxTokensPerRun 阻塞（澄清）

- `maxTokensPerRun` 作用于 **Automation REACT 单次执行**，**不作用于对话**。
- 对话 per-turn 100k+ token（system+tools+历史+输出，DeepSeek 1M 上下文+缓存）走：`contextWindow`（256k，历史超则压缩，`TokenBudgetAllocator`）+ **日预算 20M / 月费 $10**（BudgetGuard 强制后）。对话**无 per-turn 成本上限**，日预算是对话的失控熔断。
- 可选：per-conversation 二级熔断（如 2M/会话）——但 V4 Flash 极廉价，**建议日预算-only**（per-conversation 反易误切长对话）。
- `DeepSeek.maxTokens`（4096）= per-call **输出**上限（回复长度），非成本预算；长自动化回复可酌提 8192，非阻塞项。

---

## 九、对话上下文压缩接线（ContextCompressor · 2026-09-03 补章 · N6）

> 触发：用户实测 **6000+ 条对话每轮起步 10 万+ token**，线性增长终将撞模型上限。审计结论：压缩体制「有零件、没接线」。

### 9.0 现状审计（code-grounded）

1. **`TokenBudgetAllocator.needsCompression()`**（`TokenBudgetAllocator.kt:42`，历史超 50% 预算的 90% 触发）——**全库零调用**，检查从未接线。
2. **`HistoryCompressor`**（framework/memory）压缩的是 **Memory 条目**（L0-L4 分级摘要，ReflectionJob 周期触发）——与对话历史无关，名字撞车易误判。
3. **注释与实现不符**：`ContextBuilderModule.kt:17-18` 宣称"超预算 90% 自动压缩历史（保留最近 10 轮 + 旧对话压缩为摘要）"——v2.2 计划未做，实现只有 persona 截断 + 工具表精简。
4. **全量链路**：`ChatViewModel.openConversation → getMessages()`（`MessageDao.getByConversation` **无 LIMIT**，`MessageDao.kt:16-17`）→ `loadHistory()` 全量载入（`ChatBotModule.kt:416-418`）→ 每轮 `chatStream(messages=全量)`（`ChatBotModule.kt:433-434`）。
5. **DB 地雷**：ZeroDatabase v12 且 **`fallbackToDestructiveMigration()` 开启**（`DatabaseModule.kt:180`）——任何 schema 变更漏写 Migration = **全量对话静默抹除**。
6. **重载历史已丢配对信息**（二次审视 2026-09-03 补）：`loadConversationInternal` 的 entity→ChatMessage 转换**只拷 id/role/content**（`ChatViewModel.kt:360-364`），toolCalls/toolCallId/thinking/timestamp 全部丢弃——重载后历史里的 TOOL 消息被 `buildMsgsForLLM`（`ChatBotModule.kt:370-376`，"过滤无 toolCallId 的 TOOL"）全部滤除，助手消息的 tool_calls 也消失。即：**跨会话重载的发送视图无 TOOL 配对风险**（工具结果本就不可见——现状行为，N6 不改变）；**会话进行中**的 TOOL 消息带 toolCallId 且确实发送（配对约束真实存在，见 9.2-⑥）。
7. **发送点清单**（二次审视核实）：`chatStream` 全库调用点 = ChatBotModule 三处（FC 循环 :255、最终综合轮 :340、regenerate :433）——**无旁路**；滑窗接 `buildMsgsForLLM`（前两处已共用）+ regenerate 改为复用即全覆盖。

### 9.1 方案（三件套，零件全复用）

| # | 层 | 机制 | 复用零件 |
|---|---|---|---|
| **CC1** | 发送侧滑窗（止血） | LLM 发送点统一经 `ContextWindow` 装配：**预算内字节级原样**（短对话零变化）；超预算→从最新往回装至 conversationHistory 预算（contextWindow×50%），窗口外丢弃 + 头部注入省略标记 | `TokenBudgetAllocator.estimateMessageTokens`（已有）、`buildMsgsForLLM`（N0.2 已提取） |
| **CC2** | 会话滚动摘要（连续性） | `needsCompression()` 真正接线：触发**后台增量**压缩（`summary_upto` 水印=**消息 id**（重载不保真 timestamp，见 9.0-⑥）起的消息 → LLM 摘要，与旧摘要滚动合并 ≤600 字）→ 存 `conversations.summary`（经 **ConversationDao**，core:data）→ 作 contextExtension 首段注入 | HistoryCompressor 同模式（chatSync+降级+trace）、ConversationEntity + MIGRATION_12_13 |
| **CC3** | 旧工具结果瘦身（仅会话内） | 滑窗窗口内、非最近 2 轮的 TOOL 消息 content 截至默认 500 字（`toolTrimEnabled` Boolean 默认开 + `toolTrimChars` [200,4000]）——会话内搜索/抓取大结果不再霸占窗口。重载历史无 TOOL 消息（9.0-⑥，不适用） | — |

### 9.2 不破坏约束（硬性，全程有效）

1. **UI/DB/导出全量不动**——只改发送侧组装；`loadHistory`/聊天记录显示/DataExporter 不受影响
2. **预算内路径行为零变化**（短对话走原路径，trace 不出现 windowed 事件）
3. **Migration 12→13 与 Entity 变更同一 commit**（fallbackToDestructiveMigration 地雷，漏写=6000 条对话抹除）+ 老库升级实测为必测项
4. **摘要异步后台、失败降级=仅窗口不阻塞**（§7.6 同则；LLM 失败 trace 后跳过，不重试不报错给用户）
5. **本轮 FC 循环内刚产生的 TOOL 结果不瘦身**（LLM 干活需要完整结果；瘦身只作用于历史轮消息的发送视图，DB 原文不动）
6. **配对边界（二次审视新增，🔴 阻塞级）**：窗口起点绝不可落在 assistant(toolCalls) 与其 TOOL 结果之间——OpenAI 兼容 API 对"无主 TOOL"直接 400（FC 循环自身注明 tool_call_id 1:1 对应，`ChatBotModule.kt:270`）。**边界规则：窗口起点向前推进，跳过一切 TOOL 消息**，直至首条为 USER 或无 toolCalls 的 ASSISTANT。
7. **模块依赖方向（二次审视新增，🔴 阻塞级）**：摘要读写必须走 **core:data 的 `ConversationDao`**（framework/chat→core:data，HistoryCompressor 先例）——`ConversationRepository` 在 app 模块，framework 反向依赖 app 不可编译；`framework/chat/build.gradle.kts` 现无 core:data 依赖（实测仅 common/module-api/module-manager/bus/trace/services），**须与 N6.2 同 commit 添加**。

### 9.3 数值定界（并入 §7.3 体系）

| 字段 | 推荐默认 | [min,max] | 说明 |
|---|---|---|---|
| 历史预算（派生，非新字段） | contextWindow×50% | [64k, 256k]（随 contextWindow [128k,512k]） | N4 已用户化，CC1 直接消费 |
| `summaryMaxChars`（新） | 600 | [200, 2000] | 摘要长度上限（ContextBuilderConfig，上下文域） |
| `toolTrimEnabled`（新） | true | Boolean | 旧 TOOL 瘦身开关（二次审视：原"toolTrimChars=0 可关"与 min=200 矛盾，拆独立开关） |
| `toolTrimChars`（新） | 500 | [200, 4000] | 旧 TOOL 消息瘦身截断（开关开启时生效） |
| 压缩触发阈值 | 90% | 既有常量 | `needsCompression()` 原样接线 |
| Token 估算补全（二次审视） | — | — | `estimateMessageTokens` 现漏计 `toolCalls`（name+arguments）与 attachments——FC 密集会话预算低估→窗口虚大；N6.1 一并补计（+每消息固定开销 ~4 token） |

**规范符合性定稿（2026-09-03 三问审计）**：
- **UI 暴露**：全部新参数**零新 UI**——挂 `ContextBuilderConfig`（context_builder 模块），经既有模块详情页 AutoForm 自动暴露（Int→滑块、Boolean→Switch；Step 8.3/v5.8 先例）；`contextWindow` 本就在此暴露（N4.5 已落）。
- **D51 钳位兜底**：新 3 字段 `fromJson` 逐一 `coerceIn`（contextWindow 既有同款先例）——防启动恢复直读 JSON 绕过 UI 界。
- **4c 注释**：label/description 用户可读无黑话（文案见落地计划 N6.3）。
- **推荐值≠安全上限分层（§7.4 #5）**：`toolTrimChars` 推荐 500 / 上限 4000——上限安全性由**历史预算硬顶二次约束**（瘦身后的 TOOL 仍计入预算，窗口总 token 封顶不因调大而突破）；`summaryMaxChars` 上限 2000 字 ≈ 1.3k token/轮固定注入成本，受 systemAndPersona+RAG 预算段约束；两参数调大只影响窗口内配比，不突破总预算——符合"max=安全上限、超推荐仅提示"分层。
- **内部常量不暴露**：压缩触发 90%（`needsCompression` 既有常量）与 `keepRecentToolRounds=2` 属机制内部参数，不设 @ConfigField——暴露徒增误调风险，无用户语义。

### 9.4 成本模型（§八实测价续算）

6000 条场景起步 10 万+ token/轮 → CC1 后 ≈ 历史预算封顶（256k×50%×90% ≈ **115k 硬顶**，且窗口稳定段前缀缓存命中折价）；CC2 压缩成本 ≈ 每触发一次 1 次 chatSync（HistoryCompressor 同级，~1500 token，远低于被压缩内容）。

**落地实测（2026-09-03 用户，2506 条真实会话）**：起步 token 19万→**10万（-47%）**且封顶不再线性涨；ctx_summarized 8 段增量水印链（from=前水印, to=新水印，0→2506 无缝衔接）零失败；摘要长度 600 钳制生效（378→584→600 封顶收敛）；对话全程零阻塞。剩余 10 万构成 = system + 43 工具 Schema（API tools 参数 ~15-25k，MCP 生态固有，非 N6 范围）+ 滑窗历史 + 摘要——工具 Schema 瘦身列 backlog（按需加载/闲置服务器工具不注册）。

### 9.6 缓存友好修订（N6.5 · X16 · 2026-09-03 实测触发）

**缺陷（N6.1/N6.3 首版）**：9.4 假设"窗口稳定段前缀缓存命中"，但实现无稳定段保证——
① 滑窗每轮从头重滑 → 发送列表首条每轮变化 → 前缀缓存 system 后即失配（**主凶**）；
② needsCompression 条件（est>90% 预算）长会话恒成立 → 摘要每轮更新 → system 全局失效（**帮凶**）；
③ "最近 2 轮"瘦身边界随轮次推进 → 旧 TOOL 内容中途变化 = 窗口中段二次断裂。
**实测**：缓存命中跌至 57%；成本数学——修复前 19万×~85% 命中 ≈ 2.9 万未命中/轮 vs 首版 10万×43% ≈ 4.3 万/轮，**绝对成本反而更高**。

**修订（三件套）**：
| 修复 | 机制 | 效果 |
|---|---|---|
| 量子化滑窗 | 锚点保持：est ≤ budget + `slideQuantumTokens`（新 ConfigField，默认 8192 [2048,32768]）时**起点不动**（容忍软超预算），逾量子才重滑一次 | 窗口段 append-only 跨轮命中；一次全量重读被 ~N 轮摊薄 |
| 压缩节流 | 区间 <16 条消息 **且** <2048 token → `ctx_summarize_deferred` 跳过 | 摘要多轮稳定；区间自然在重滑时达限 → 两类失效合并为一次 |
| 瘦身定位化 | TOOL 瘦身改**窗口前半段**位置判定（替代轮次判定） | 同一起点下某 TOOL 的瘦身状态不随追加变化 |

**观测**：`ctx_windowed` 埋点增 `startMoved=` 字段（false=锚点保持轮——缓存友好生效的直接证据）；摘要触发频率应从"每轮"降至"约每量子一次"。

### 9.7 世代压缩（N6.6 · 用户裁定重定向 · 取代 N6.1/N6.5 常驻滑窗）

**目标重定向（用户 2026-09-03）**：压缩唯一使命=**防 1M 上限报错**；token 省钱不是目标（缓存价 ~1/10，19 万高命中 < 10 万低命中的真实成本）；**高缓存命中=高优先级**。常驻滑窗（无论是否量子化）必然周期性改写头部=周期性牺牲命中——方向性错误，废除。

**机制（append-only 铁律 + 罕见世代切换 + 尾部瘦身）**：
1. **平时零干预**：发送视图=世代锚点起至最新，append-only——前缀逐轮满命中（**97% 稳态回归**，与压缩前同构）
2. **世代切换**（唯一压缩事件）：视图 est > 90% × contextWindow → 一次性切片到 **65%** 预算（90−65=25% 增长空间=切换天然稀疏，512k 档约每 128k 新内容一次≈数百轮）→ 被置换头部异步压缩为摘要（水印协议不变）→ 新基线继续 append-only
3. **尾部瘦身**：轮末修剪"刚结束回合"的超大 TOOL 结果（内存态，先于持久化 emit——落库即修剪版）。**这是唯一可安全压缩的位置**：内容可重新获取 + 自包含 + 改写位置贴尾（命中损失限于本回合残余几 k）
4. 摘要仍在 system，但只在世代切换时变（稀疏）；配对边界/瘦身定位化规则沿用

**参数变更**：contextWindow 语义=世代切换线，默认 256k→**512k**、上限 512k→**960k**（[128k,960k]，1M 防线内；旧持久值 256k 落界内自动沿用）；slideQuantumTokens 移除（常驻滑窗废除外溢）。生命周期：世代锚点随会话切换重置（首发送如超线切换一次≈冷启动全量重读，可接受）。

**预期命中**：稳态轮 ~97%；切换轮仅 system 段命中（一次全量重读）；切换稀疏 → **平均 96-97%**。1M 安全：触发线 ≤864k < 1M。

**埋点**：`ctx_gen_switch: viewEst= budget= kept= dropped= anchor=`（切换）、`ctx_tail_trim: trimmed= maxChars=`（轮末瘦身）、`ctx_summarized/failed/skipped`（摘要，沿用）。

### 9.5 Step 化（N6，实施细节见落地计划文档 §七）

| Step | 内容 | 依赖 |
|---|---|---|
| N6.1 | `ContextWindow` 滑窗装配器 + 发送点接线 + `ctx_windowed` trace | N0.2（buildMsgsForLLM） |
| N6.2 | 摘要存储：ConversationEntity +2 列 + **MIGRATION_12_13** + Repository 读写 | —（可与 N6.1 并行） |
| N6.3 | `ConversationSummarizer`（增量水印+降级）+ `needsCompression` 接线 + 摘要注入 | N6.1 + N6.2 |
| N6.4 | 全链路实测：老库升级 / 起步 token 封顶 / 早期事实连续性 / 降级 | N6.3 |
