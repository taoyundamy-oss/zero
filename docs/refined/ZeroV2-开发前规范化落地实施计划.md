# Zero V2 开发前规范化 Claude Code 落地实施计划

> 目标：由 Claude Code 在 `D:\ZeroV2` 现有代码库上逐步实现 `ZeroV2-开发前规范化设计.md`（§一–§八：LLM 重构 / 模块合并 / 流畅度 / 细节 / FC 缺陷修复 / 数值定界 / BudgetGuard；**§九 补章：对话上下文压缩接线 N6**）。
> 核心约束：Claude Code 无法编译/运行，验证=L1 代码审查 + L2 用户 Build + L3 APK 实测（沿用 `CLAUDE_CODE_IMPLEMENTATION_PLAN.md §〇`）。
> **文档权威层级**：开发前规范化设计（决策/根因）> 本文档（实施细节）。
> 记录日期：2026-08-31 · **2026-09-03 补章 N6（设计 §九）**

---

## 〇、Claude Code 开发约束与对策

| 约束 | 对策 |
|------|------|
| 无法编译 | 每 Step ≤3 文件或 ≤1 逻辑单元；用户 Build；错误→修复 |
| 无法运行 | `AgentTrace` 埋点；RunLog 可查 |
| 改核心文件（ChatBotModule/McpClientModule/ConfigField.kt） | **先 Read 确认当前签名/行号**再改；改动最小化 |
| LLM 输出不可靠 | `JsonExtractor` 降级链（§7.6）；分页结果结构化返回 |

**协作模式**：完成 Step → 用户 commit → Build → 通过继续 / 失败贴错误修复。

---

## 一、N0：FC 循环缺陷修复（最先，解除已见中断）

### Step N0.1：ChatBotModuleConfig + maxFcRounds（F1）

**创建**：`framework/chat/src/main/java/com/zero/v2/framework/chat/ChatBotModuleConfig.kt`
```kotlin
class ChatBotModuleConfig : ModuleConfigBase() {
    @ConfigField(label = "FC 最大轮次", fieldType = ConfigFieldType.NUMBER, min = 6.0, max = 30.0,
        description = "单次对话最多工具调用轮数（分页读文件每页 1 轮）。默认 16 约可连读 250KB 文档；达上限后 Zero 会基于已读内容作答，不会中断")
    var maxFcRounds: Int = 16
    // toJson/fromJson/defaultConfig 同既有模式（D30：仅 Int/Float/Boolean）
}
```
**修改**：`ChatBotModule.kt`（`override val config = ChatBotModuleConfig()`——现无 @ConfigField 配置；`while (loopCount < 5)` → `while (loopCount < (config as ChatBotModuleConfig).maxFcRounds)`，`ChatBotModule.kt:212`）。D51 启动恢复自动生效（模块已注册）。
**验证**：Build；改配置→trace 显示生效轮数。

### Step N0.2：最终综合轮（F2）+ trace（F4）

**修改**：`ChatBotModule.kt` FC 循环退出点（`while` 结束后、`finally` 前）：
```kotlin
// 因上限退出且末尾为 TOOL（有待综合的工具结果）→ 追加最终综合轮，消除静默中断
val endMsg = _chatHistory.value.messages.lastOrNull()
if (endMsg?.role == MessageRole.TOOL) {
    traceManager.record(traceId, NodeType.SYSTEM_EVENT, metadata.id,
        "fc_loop_capped: round=$loopCount final_synthesis=true")
    _chatHistory.value = _chatHistory.value.copy(messages = _chatHistory.value.messages +
        ChatMessage(id = UUID.randomUUID().toString(), role = MessageRole.ASSISTANT, content = ""))
    val msgsForFinal = buildMsgsForLLM()  // 复用既有"过滤 TOOL + 剥离末尾空 assistant 占位"逻辑（ChatBotModule.kt:224-230 提取为私有方法，循环内两处共用）
    model.chatStream(messages = msgsForFinal,
        systemPrompt = effectivePrompt + "\n\n[系统提示] 已达工具调用轮次上限。请基于以上已获取的工具结果直接回答用户，不要再请求工具。",
        functions = emptyList()).collect { chunk -> emit(chunk) }
    // functions 已空；若模型仍发 tool_calls 仅取 content（忽略），防新循环
}
```
**验证**：实测读 65KB md → **不中断** → 达上限时末尾有"基于已读内容"的最终回答；RunLog 查 `fc_loop_capped`；正常对话（<上限）行为零变化。

---

## 二、N1：read 分页（4b/F3）

### Step N1.1：WorkDirRepository.readText(offset, len)

**修改**：`app/.../data/WorkDirRepository.kt`
```kotlin
data class PagedText(val content: String, val totalLen: Int, val offset: Int, val hasMore: Boolean)
suspend fun readText(fileName: String, offset: Int = 0, length: Int = 8000): PagedText? {
    // SAF 无 seek：全读后 slice(offset, offset+length)；null=文件不存在
}
```
（保留既有 `readText(fileName): String?` 供旧调用方，标注 @Deprecated 引导迁移。）

### Step N1.2：read_work_file offset + WorkFilesConfig

**修改**：`app/.../data/WorkFilesModule.kt`
- 新 `WorkFilesConfig`（`pageLength: Int = 8000`，`@ConfigField(min=1000.0, max=50000.0)`，对齐 §7.3）。
- FC `read_work_file(file_name, offset=0)`：返回
  `📄 {file} 共{total_len}字（第{offset}-{offset+page}字）\n{content}\n{has_more ? "── 还有后文，调用 offset={offset+page} 读下一页 ──" : "（已到末尾）"}`；`read_paged: offset= has_more=` 埋点。
- 保持 AUTO 权限、SAF-only（D57）。

### Step N1.3：read_office_file 契约对齐

**修改**：`app/.../data/OfficeFilesModule.kt`——返回结构改为与 read_work_file 同构（`{content,total_len,offset,page_len,has_more}` 同字段名同语义），LLM 一套翻页逻辑通用。

**验证**：对话"读 DEVELOPMENT_DESIGN.md 全文"→ 分页读全（≥8 页）不中断；office 与 txt/md 翻页提示一致。

---

## 三、N2：LLM 重构（统一 OpenAI 兼容层，渐进双轨）

> **关键决策（交叉验证落定）**：profile 用**专用存储 + 专用 UI**（复刻 `McpServerStore`/`McpServersScreen` 先例，Step 8.3"专用存储不扭曲扁平 ConfigField"）——**不塞进扁平 @ConfigField**（5 个 profile × 6+ 字段装不下）。Key 沿用 SecureKeyStore 既有 ref（`llm_client:deepseek/qwen`、`tts:mimo` 等），零迁移。

### Step N2.1：OpenAiCompatibleClient + LlmProfile + LlmProfileStore

**创建**：
- `services/.../llm/OpenAiCompatibleClient.kt`（`@Singleton`，注入 `@Named("llm") OkHttpClient`：`chatCompletion(profile, messages, system, functions, stream, adapter)` / `embeddings(profile, inputs)`；**统一 SSE 解析器**（合并 DeepSeekAdapter/MimoTtsClient 各自解析）；统一 trace `llm_call: provider= model= tokens=`；**统一用量计量点**（供 N5 BudgetGuard））
- `services/.../llm/LlmProfile.kt`（`{id, provider, baseUrl, apiKeyRef, modelId, capabilities, temperature, maxTokens, thinking 参数, extraBody}`；内置默认：`deepseek`(api.deepseek.com/v1) / `qwen`(dashscope compatible-mode) / `mimo-tts`(api.xiaomimimo.com/v1) / `qwen-embedding` / `qwen-vl`；**数值定界直接落 profile 字段**：temperature[0,2]、maxTokens[256,32768]、thinkingBudget[256,4096]）
- `services/.../llm/LlmProfileStore.kt`（DataStore/JSON 持久化 + StateFlow，McpServerStore 同模式）

### Step N2.2：DeepSeek 迁移（双轨）
**创建**：`services/.../llm/DeepSeekLlmAdapter.kt`（仅差异：`thinking.type`+`reasoning_effort` 构造 extraBody + 该家流解析，D28）。
**修改**：`DeepSeekChatModule` 委托 `OpenAiCompatibleClient`（替换 `DeepSeekAdapter.chatStream` 调用）；旧 Adapter 保留（双轨可回退）。
**验证**：对话+FC+思考渲染全功能不回归；`llm_call: provider=deepseek` trace。

### Step N2.3：Qwen 迁移（同构）
`QwenLlmAdapter`（`enable_thinking`+`thinking_budget` extra_body，D28）；`QwenChatModule` 切 client。

### Step N2.4：MiMo TTS 迁移
**创建**：`services/.../llm/MimoTtsLlmAdapter.kt`（`audio.format/voice` + 解析 `delta.audio.data`）。
**修改**：`SpeechSynthesisModule` 经 client+`mimo-tts` profile 调用；`MimoTtsClient` 仅留流式管线、删其私有 HTTP/SSE（端点/模型不再硬编码）。
**验证**：Live 语音播报正常、打断/分句管线不回归（v6.12/v6.19c 行为）。

### Step N2.5：Embedding/Vision 迁移
`RemoteEmbeddingProvider`/`VisionModule` 改走 client + `qwen-embedding`/`qwen-vl` profile。**验证**：记忆检索、vision_poke 不回归。

### Step N2.6：收敛 OpenAiChatModule
**创建**：`ProfileLanguageModel(profile, client, adapter) : ILanguageModel`（modelId/displayName/contextWindow/capabilities 取自 profile；chatStream/chatSync 委托 client）。
**修改**：`OpenAiChatModule`（`IZeroModule id=openai_chat`）按启用 profile 注册 N 个 `ProfileLanguageModel` 进 `ModelSelector`（API 不变）；`AppBindingModule` 改绑；**删** `DeepSeekChatModule/QwenChatModule/DeepSeekAdapter/QwenAdapter`（渐进 §6.5：全量验证后删）。
- 新 UI：`LlmProfilesScreen`（profile 列表+编辑 baseUrl/modelId/参数，McpServersScreen 同模式）+ 设置入口。
**验证**：切 profile 对话正常；新增 profile（如 GLM）零代码可用；模型选择/降级链不回归。

---

## 四、N3：FilesModule 合并（WorkFiles+OfficeFiles）

### Step N3.1：FilesModule 聚合（FC 名不变！）
**创建**：`app/.../data/FilesModule.kt`（`IZeroModule id=files`）——聚合两模块全部 FC（`save_to_file/read_work_file/list_work_files/delete_work_file/read_office_file/create_document/edit_document`）。**FC 名保持原样**（LLM 已熟悉，Prompt/工具表零扰动；仅模块 id 变）。
### Step N3.2：配置合并
`FilesConfig`（`pageLength[1000,50000]` 默认 8000 + `maxFileSizeMB[1,50]` 默认 5）+ `ConfigMigration`（office_files 存量 config 迁入，D8 链式）。
### Step N3.3：切换+删旧
`AppBindingModule` 改绑 `FilesModule`；**删** `WorkFilesModule/OfficeFilesModule`。
**验证**：全部 7 个 FC 行为/权限（AUTO/CONFIRM，D9）不回归；词汇闭环（v6.7 判据）可复测。

---

## 五、N4：数值定界 + 注释 + amapuri + 流畅度

### Step N4.1：注解默认修正 + 写入钳位（**与 N4.2 同一提交！**）
**修改**：`core/module-api/.../ConfigField.kt`（`max: Double = 100.0` → `Double.MAX_VALUE`，`min` 保持 0.0——消除"忘写=套 100"脚枪，即修 `maxTokensPerRun=50000>100` BUG）；`app/.../ModuleDetailViewModel.kt` `flushConfigChanges`（数值写入前 `coerceIn(min, max)` + `config_clamped` 埋点，防直改 JSON 绕过）。
> ⚠️ **高危约束：N4.1 单独上线=所有无界字段裸奔**——必须与 N4.2 全量定界同一 commit 合并。

### Step N4.2：全量定界 sweep（§7.3 表，非 LLM/文件域——LLM 在 N2.6、文件在 N3.2 已落）
`AgentRuntimeConfig`（baseIntervalSec[30,600]/randomOffsetSec[5,120]/intervalMultiplier[1.5,5.0]/maxDoublings[1,10]）、`MemoryModuleConfig`（reflectionHour[0,23]/graphExtractionThreshold[10,500]/archiveAfterDays[7,365]/compressionThreshold[20,500]）、`AutomationModuleConfig`（maxSteps[3,30]/**maxTokensPerRun 50000→500000 [50000,2000000] 修 BUG**/maxDurationMin[1,30]）、`reasoningEffort` 补 none/low/high/max 四档（若 N2.6 已落则跳过）。
**同时**：`AgentBehaviorViewModel` 默认 `dailyTokenLimit 50_000→20_000_000`、`monthlyCostLimitUsd 5.0f→10f`（§八实测订正）；`AgentBehaviorScreen` 滑杆 `10_000f..500_000f→8_000_000f..100_000_000f`（对数刻度）、`1f..50f→5f..50f`。

### Step N4.3：ConfigField 注释 sweep（4c）
逐模块 label/description 去 D 编号/黑话、写用户含义+建议值（重点：DeepSeek 四参/MiMo voice/AgentRuntime 四参/Memory 8 字段/新增 pageLength/maxFcRounds）；SECRET 不进 AutoForm（v5.8 先例）。

### Step N4.4：amapuri 改写（4a）
**修改**：`McpClientModule.callTool` 结果后处理（高明 server 级开关）：`Regex("amapuri://")` → `https://uri.amap.com/`；`mcp_amapuri_rewritten` 埋点。可选：`MessageBubble` `AnnotatedString` 拦截 `amapuri://` → `startActivity(ACTION_VIEW)`。
**验证**：`schema_navi` 返回链接可点击跳转。

### Step N4.5：流畅度（4 项，§三）
Compose（LazyColumn `key`/`derivedStateOf`/LiveUiState 拆细）；VL（白名单主通道 + contentHash 缓存 D47）；冷启动（assets 后台提取 + 进度 StateFlow）；Trace 热路径去冗余。

---

## 六、N5：BudgetGuard 强制执行（设计文档 §7.4 #4，原标 N4.5——**因 N4 子步占用重编号 N5**）

**依赖**：N2.1（`OpenAiCompatibleClient` = 唯一计量点：chat/TTS/embedding/vision 全 API 用量在此累加）+ N4.2（预算值定界）。

**创建**：`services/.../agent/BudgetGuard.kt`
```kotlin
@Singleton class BudgetGuard @Inject constructor(budgetStore: BudgetStore, trace: AgentTraceManager) {
    // DataStore: dailyUsage_<yyyyMMdd> / monthlyUsage_<yyyyMM>（client 每次调用后累加 tokens+折算费用）
    suspend fun preCheck(): BudgetVerdict   // OK / DAILY_EXCEEDED / MONTHLY_EXCEEDED
    // fail-open：存储读失败→放行 + warn（绝不因守护自身故障砖死对话）
}
```
**修改**：`ChatBotModule` FC 每轮前 + `AutomationModule` ExecutionGuard 接入 `preCheck()`——超支→终止 + 用户通知（含已用量/上限）；`AgentBehaviorScreen` 预算区文案改"已强制执行"。
**验证**：设小额预算实测触顶→对话优雅终止+通知（非静默）；月费覆盖 TTS/embedding/vision 用量可查。

---

## 七、N6：对话上下文压缩接线（ContextCompressor · 设计 §九 · 2026-09-03 补章）

> 背景：用户实测 6000+ 条对话每轮起步 10 万+ token；审计确认 `TokenBudgetAllocator.needsCompression()` **全库零调用**（ContextBuilderModule 注释与实现不符，v2.2 欠账）。三件套：CC1 滑窗止血 / CC2 滚动摘要 / CC3 旧 TOOL 瘦身。硬约束见设计 §9.2（五条不破坏）。

### Step N6.1：ContextWindow 滑窗装配器（止血——无 DB、无 LLM、可独立上线）

**创建**：`framework/chat/src/main/java/com/zero/v2/framework/chat/ContextWindow.kt`（纯函数装配器，零依赖可单测）
```kotlin
object ContextWindow {
    data class Result(val messages: List<ChatMessage>, val windowed: Boolean,
                      val droppedCount: Int, val windowStartIdx: Int, val estTokens: Int, val budget: Int)
    fun assemble(messages: List<ChatMessage>, historyBudgetTokens: Int,
                 toolTrimEnabled: Boolean, toolTrimChars: Int,
                 keepRecentToolRounds: Int = 2): Result
    // 预算内 → 原样返回（windowed=false——短对话零变化，字节级不变）
    // 超预算 → 从最新往回按 estimateMessageTokens 累加至预算截断；
    // 🔴 配对边界（X12）：窗口起点向前推进跳过一切 TOOL 消息（首条必为 USER 或无 toolCalls 的
    //    ASSISTANT）——OpenAI 兼容 API 对"无主 TOOL"直接 400；
    //    窗口内旧 TOOL（超出 keepRecentToolRounds 轮，且 toolTrimEnabled）content.take(toolTrimChars)
    //    + "…[已瘦身，原文在记录中]"（本轮新产生的不动，设计 §9.2-⑤）；
    //    头部插一条占位："[较早对话已省略 N 条]"；
    // windowStartIdx 供 N6.3 Summarizer 定位压缩区间（免重算漂移）
}
```
**修改**：`ChatBotModule`——①`buildMsgsForLLM()`（`ChatBotModule.kt:370-376`，FC 循环 :255 与最终综合轮 :341 已共用）在既有"过滤无主 TOOL + 剥离末尾占位"之后追加 `ContextWindow.assemble`；②`regenerate()`（:421-424 自行组装 msgs.dropLast(1)）**改为复用 buildMsgsForLLM**（消除重复组装点，二次审视 X15——chatStream 全库仅 ChatBotModule 三处 :255/:340/:433，无旁路）；③仅 `windowed=true` 时 trace `ctx_windowed: kept= dropped= est= budget=`。
**另改**：`TokenBudgetAllocator.estimateMessageTokens` 补计 `toolCalls`（name+arguments 序列化长度的估算）与每消息固定开销 ~4 token（X14——FC 密集会话现被低估 → 窗口虚大有超模型限风险）。
**验证**：Build；短对话零变化（无 ctx_windowed）；长对话 estTokens ≤ 预算；UI 聊天记录仍全量显示；FC 循环正常（配对边界不破坏工具链）。

### Step N6.2：摘要存储（DB v12→13 · ⚠️ 地雷步）

**⚠️ 高危约束**：`fallbackToDestructiveMigration()` 开启（`DatabaseModule.kt:180`）——**Migration 漏写/失败 = 全量 6000+ 条对话静默抹除**。Entity/Dao/DB version/`MIGRATION_12_13` 必须同一 commit；老库升级实测为 L3 必测项（不可跳）。

**修改**（单一目标=摘要持久化，文件数超 3 属迁移例外）：
- `ConversationEntity`：+`summary: String?`（@ColumnInfo "summary"）+`summaryUpto: String?`（@ColumnInfo "summary_upto"，**消息 id 水印**——X13：重载后 ChatMessage.timestamp 不保真（转换只拷 id/role/content，ChatViewModel.kt:360-364），Long 时间戳跨重载失效）
- `ConversationDao`：+`getSummary(id): Pair<String?, String?>?`（或投影行）+`updateSummary(id, summary, summaryUpto)`——**framework 侧直接用 Dao**（X12 依赖方向：`ConversationRepository` 在 app 模块，framework/chat 反向依赖不可编译）
- `ZeroDatabase`：version 12→13；`DatabaseModule`：+`MIGRATION_12_13`（ALTER TABLE conversations ADD COLUMN summary TEXT；ADD COLUMN summary_upto TEXT——**逐条执行**，SQLite 不支持同行两列）
- `framework/chat/build.gradle.kts`：+`implementation(project(":core:data"))`（X12——现无此依赖（实测仅 common/module-api/module-manager/bus/trace/services），漏加=ChatBotModule/Summarizer 引 Dao 编译失败；HistoryCompressor 即 framework/memory→core:data 先例）
- `ConversationRepository`（app 侧，可选）：+便捷读取给未来 UI 用（非 N6 必需）
**验证**：Build + **老库升级实测（12→13 数据全保留）** + 全新安装无回归 + 删会话无残留报错。

### Step N6.3：ConversationSummarizer + needsCompression 接线 + 摘要注入

**创建**：`framework/chat/.../ConversationSummarizer.kt`（HistoryCompressor 同模式先例）
```kotlin
@Singleton class ConversationSummarizer @Inject constructor(
    conversationDao: ConversationDao,          // core:data 直用（X12 依赖方向）
    modelSelector, tokenBudgetAllocator, traceManager) {
    suspend fun compressIfNeeded(conversationId: String, fullMessages: List<ChatMessage>,
                                 windowStartIdx: Int,          // 来自 ContextWindow.Result，免重算漂移
                                 budget: TokenBudgetAllocator.Budget, summaryMaxChars: Int)
    // needsCompression(estTokens, budget) 未触发 → return（终于接线！）
    // 增量：定位 summary_upto（消息 id）在列表中的 index → [该 index, windowStartIdx) 区间消息
    //      + 旧摘要 → chatSync 滚动合并 ≤summaryMaxChars → dao.updateSummary（新水印=窗口首条消息 id）
    // 降级（§7.6）：LLM 失败 → trace ctx_summarize_failed，仅窗口运行，不重试不阻塞
}
```
**修改**：`ChatBotModule`——①发送完成后 `scope.launch { summarizer.compressIfNeeded(_chatHistory.value.id, 全量, windowStartIdx, …) }`（异步后台，拿本轮 assemble 的 Result）；②`sendMessage` 的 `buildContextExtension(null, null, null)` 调用点（`ChatBotModule.kt:191`，现传三 null）改为首参注入 `[早期对话摘要]\n${dao.getSummary(id)}`（存在才注入；摘要更新低频=前缀缓存影响有界）。
`ContextBuilderConfig` 新增 3 字段（4c 注释规范 + D51 fromJson 钳位，零新 UI——AutoForm 自动暴露）：
```kotlin
@ConfigField(label = "会话摘要长度上限（字）", fieldType = ConfigFieldType.NUMBER, min = 200.0, max = 2000.0,
    description = "早期对话压缩成摘要后的最长字数。默认 600 兼顾信息量与每轮成本；调大=早期细节保留更多但每轮固定消耗略增（受总上下文预算约束）")
var summaryMaxChars: Int = 600

@ConfigField(label = "旧工具结果瘦身", fieldType = ConfigFieldType.TOGGLE,  // 注：枚举实为 TOGGLE（实现期修正）
    description = "开启后，较早轮次的工具返回（网页全文/搜索结果等大文本）在发送给模型时自动截短，聊天记录原文不受影响。默认开启")
var toolTrimEnabled: Boolean = true

@ConfigField(label = "工具结果保留字数", fieldType = ConfigFieldType.NUMBER, min = 200.0, max = 4000.0,
    description = "配合上一项：较早工具结果保留前多少字（默认 500）。窗口总 token 受上下文预算封顶，调大不会突破总预算")
var toolTrimChars: Int = 500

// fromJson 逐一 coerceIn（D51 兜底，contextWindow 既有同款）：
// summaryMaxChars = obj.optInt("summaryMaxChars", 600).coerceIn(200, 2000)
// toolTrimChars = obj.optInt("toolTrimChars", 500).coerceIn(200, 4000)
// toolTrimEnabled = obj.optBoolean("toolTrimEnabled", true)
```
**验证**：Build；长对话触发后 RunLog `ctx_summarized: chars= upto=`；下一轮 system 含摘要；摘要有/无对话均正常（降级路径）；跨重启重载后摘要仍注入（Dao 持久化生效）；**模块详情页 3 个新字段可见可调且写入被钳位（直改 JSON 越界值 → 重启后回落界内）**。

### Step N6.4：全链路实测（L3，6000 条真实库）

- 老库升级不丢数据；起步 token 10万+ → ≤115k 封顶（256k 默认预算下）
- 连续性：问早期事实（"我们之前讨论过 X 吗"）——摘要可答
- ConfigField 生效：调 contextWindow / 关 toolTrimChars；RunLog 埋点齐全（ctx_windowed / ctx_summarized）

---

## 八、保证机制（沿用既有）

- **8.1 三层验证**：L1 静态自查 → L2 用户 Build → L3 实测。任一失败→修复→重验。
- **8.2 埋点**：`fc_loop_capped`/`read_paged`/`llm_call`/`config_clamped`/`mcp_amapuri_rewritten`/`budget_*`/`ctx_windowed`/`ctx_summarized`。
- **8.3 变更边界=Git Commit**：每 Step commit；N2/N3 双轨期可 `git reset` 回退。
- **8.4 标杆对照**：FC/SAF→`WebFetchModule`；MCP→`McpClientModule`；Room→`MemoryModule`；专用存储+UI→`McpServerStore/McpServersScreen`（N2 profile 同模式）；ConfigField→`DeepSeekChatConfig`；LLM 摘要→`HistoryCompressor`（N6.3 同模式）。
- **8.5 渐进迁移**：N2/N3 先新建双轨→验证→切换→删旧（§6.5）；N6.1 止血先行（无 DB/LLM 依赖，独立上线即收益）。
- **8.6 降级**：分页/LLM/守护故障→结构化失败文案回 LLM/用户，内核不崩（B11 同则）；N6.3 摘要失败=仅窗口运行。

---

## 九、交叉验证与阻塞/高危排查记录（2026-08-31）

> 对设计文档 §六 原序（N0→N1/N2→N3→N4+N4.5）交叉验证后的 **8 项订正**（已全部落入上文 Steps）：

| # | 发现 | 订正 |
|---|---|---|
| X1 | 设计 §7.4 把全部定界并入 N4——但 LLM 字段（temperature/maxTokens）将被 N2 重构为 LlmProfile、文件字段将被 N3 合并为 FilesConfig，**后置定界=二次返工** | **定界随域拆分**：LLM 字段落 N2.1 profile、文件字段落 N3.2 FilesConfig、其余落 N4.2 |
| X2 | N4.5（BudgetGuard）与 N4 子步编号冲突（N4 细节已占 N4.1–N4.5） | BudgetGuard **重编号 N5**（映射设计 §7.4 #4） |
| X3 | BudgetGuard 依赖未声明——**计量点**在 N2.1 的 OpenAiCompatibleClient（chat/TTS/embedding/vision 唯一咽喉），N5 先于 N2 落地则无处计量 | N5 依赖改为 **N2.1 + N4.2** |
| X4 | N2 的 profile 配置若走扁平 @ConfigField 装不下（5 profile × 6+ 字段）且违背既有先例 | profile 用**专用存储+专用 UI**（McpServerStore/McpServersScreen 先例，Step 8.3） |
| X5 | N4.1（注解默认 max→MAX_VALUE）单独上线=全部无界字段裸奔 | **N4.1 与 N4.2 强制同一 commit**（高危约束） |
| X6 | N3 合并若改 FC 名会扰动 LLM 工具表/Prompt | **FC 名全部保持原样**，仅模块 id 变 |
| X7 | N0 需 ChatBotModule 有 config——现无任何 @ConfigField | N0.1 **新建 ChatBotModuleConfig**（D51 恢复自动生效） |
| X8 | BudgetGuard 自身故障可砖死对话 | **fail-open**：存储读失败→放行+warn |

**阻塞项：无**（8 项均已消解；N2 为最高风险轨——渐进双轨+每子步验证+git 可回退兜底）。

### 2026-09-03 补充（N6 勘察 · 设计 §九）

| # | 发现 | 订正 |
|---|---|---|
| X9 | `fallbackToDestructiveMigration()` 开启——N6.2 schema 变更漏写 Migration 即全量对话抹除 | N6.2 列 ⚠️ 地雷步：Entity/Dao/version/MIGRATION_12_13 **同 commit**，老库升级实测为 L3 必测 |
| X10 | `ContextBuilderModule` 注释宣称 v2.2 已自动压缩历史，实现缺失——防后来者再次误信 | 注释已在 N6.3 落地时同步修正为真实行为（滑窗+摘要），消除注释-实现偏差 |
| X11 | CC2 若每次全量重压摘要，长会话压缩成本随长度线性涨 | **增量水印**（summary_upto）：只压新增区间，与旧摘要滚动合并（HistoryCompressor 批次模式移植） |
| X12 🔴 | **模块依赖方向**：`ConversationRepository` 在 app 模块——framework/chat 反向依赖 app 不可编译；且 framework/chat 的 build.gradle **现无 core:data 依赖**（实测核验） | Summarizer/ChatBotModule 直用 **core:data 的 ConversationDao**（HistoryCompressor 同例）；`framework/chat/build.gradle.kts` +core:data 与 N6.2 同 commit |
| X13 🟠 | **时间戳不保真**：`loadConversationInternal` 转换只拷 id/role/content（ChatViewModel.kt:360-364），ChatMessage.timestamp 重载后=加载时刻——Long 水印跨重启失效 | `summary_upto` 改 **消息 id**（TEXT）水印；按 id 在列表中定位 index 划压缩区间 |
| X14 🟠 | **估算漏计 toolCalls**：`estimateMessageTokens` 只算 content+thinking——FC 密集会话预算低估 → 窗口虚大 → 仍可能超模型上限；另 `toolTrimChars=0 可关` 与 min=200 矛盾 | N6.1 补计 toolCalls 序列化 + 每消息 ~4 token 开销；瘦身开关拆独立 `toolTrimEnabled`（Boolean，默认开） |
| X15 🔴 | **TOOL 配对边界**：窗口起点落在 assistant(toolCalls) 与 TOOL 结果之间 → OpenAI 兼容 API 400（FC 循环自身注明 1:1 对应）；另 regenerate() 自行组装消息=第二发送点易漏窗 | ContextWindow 边界规则：起点跳过一切 TOOL 消息；regenerate 改复用 buildMsgsForLLM（chatStream 全库仅 ChatBotModule 三处，核验无旁路） |
| X16 🔴（N6.5 · 实测触发） | **前缀缓存断裂**：N6 首版滑窗每轮重滑（首条消息变化=system 后即失配）+ 摘要每轮更新（system 全局失效）+ 轮次推进改 TOOL 瘦身状态——实测命中跌至 57%，10万×43% 未命中 > 修复前 19万×~85% 未命中，**绝对成本反升** | N6.5 量子化滑窗为过渡方案，**N6.6 世代压缩根治**（见 X17） |
| X17 🔴（N6.6 · 用户裁定重定向） | **目标错位**：压缩本意=防 1M 上限而非省 token（缓存价 ~1/10）；常驻滑窗（含量子化）必然周期性改写头部=周期性牺牲命中——结构上不可能回到 97% 稳态 | **废除常驻滑窗**：世代压缩（平时 append-only 零干预 / est>90% 预算一次性切换到 65% / 轮末尾部瘦身 TOOL）；contextWindow 升级为切换线（默认 512k，上限 960k<1M）；slideQuantumTokens 移除；预期平均命中 96-97% |

**N6 阻塞项：无**（🔴 X12/X15 均已在方案内消解——依赖方向改 Dao+同 commit 加 gradle 依赖、边界规则进 ContextWindow 契约；N6.1 无前置可先行；N6.3 依赖前两步零件）。

---

## 十、进度追踪表

> ✅ 完成 | ⏳ 待开始 | 🔧 进行中 | ⚠️ 阻塞

| Step | 内容 | 状态 | 验证 |
|---|:---:|---|---|
| N0.1 | ChatBotModuleConfig + maxFcRounds | ✅ | Build ✅ APK 08-31 10:36 |
| N0.2 | 最终综合轮 + trace | ✅ | Build ✅ APK 08-31 10:38（65KB 读不中断待实测） |
| N1.1 | readText(offset,len) | ✅ | Build ✅ APK 08-31 10:39 |
| N1.2 | read_work_file 分页 + WorkFilesConfig | ✅ | Build ✅ APK 08-31 10:41（分页读全待实测） |
| N1.3 | read_office_file 契约对齐 | ✅ | Build ✅ APK 08-31 10:44（翻页一致待实测） |
| N2.1 | OpenAiCompatibleClient + Profile + Store | ✅ | Build ✅ APK 08-31 11:11（基建双轨，无行为变化） |
| N2.2 | DeepSeek 迁移（双轨） | ✅ | Build ✅ APK 08-31 11:14（对话/FC/思考不回归待实测） |
| N2.3 | Qwen 迁移 | ✅ | Build ✅ APK 08-31 11:22（切 Qwen 实测待用户） |
| N2.4 | MiMo TTS 迁移 | ✅ | Build ✅ APK 08-31 11:41（Live 播报/打断待实测；中途修复第二调用点漏迁） |
| N2.5 | Embedding/Vision 迁移 | ✅ | Build ✅ APK 08-31 12:52。**偏差：Vision 用 DashScope 原生多模态端点（非 OpenAI 兼容），切换属行为变更，保留不迁**（profile qwen-vl 留作切换位）；Embedding 已迁 + 补 index 归位 |
| N2.6 | OpenAiChatModule 收敛 + LlmProfilesScreen + 删旧 | ✅ | a 12:57 / b 切换删旧 12:59 / **c LlmProfilesScreen ✅ Build APK 08-31 13:09**（ModelSelector+unregister、refreshProfiles 即时切换、孤儿 Config 删；中途修 @Composable 作用域错）——**N2 全轨收官** |
| N3.1 | FilesModule 聚合 | ✅ | 单步切换完成（双轨不可行：FC 同名撞 FunctionRegistry）Build ✅ APK 08-31 13:21 |
| N3.2 | FilesConfig + 迁移 | ✅ | 并入单步：统一 pageLength 8000 + coerceIn；**偏差：跨模块 config 不迁移**（旧自定义值作废重设一次） |
| N3.3 | 切换 + 删旧 | ✅ | bindFiles 替换两旧绑；两旧文件删；FC 名/权限原样；中途修 _build_apk.bat（Gradle 按 cwd 解析项目目录，脚本补自 cd） |
| N4.1+N4.2 | 注解修正+钳位+全量定界（**同一 commit**） | ✅ | Build ✅ APK 08-31 13:29（7 文件：max=MAX_VALUE 脚枪根除/写入 coerceIn+config_clamped/AgentRuntime+Memory+Automation 定界+fromJson 钳位/maxTokensPerRun 500000 BUG 修/预算 20M-$10 落地；中途修 fromJson 重复定义） |
| N4.3 | ConfigField 注释 sweep | ✅ | Build ✅ APK 08-31 13:34（WebSearch SECRET 撤 AutoForm 修 v5.8 同款隐患 + 描述纠错；TTS 引擎引导补全；其余字段已在 N2-N4 顺带完成，Vision 检查无问题） |
| N4.4 | amapuri 改写 | ✅ | Build ✅ APK 08-31 13:38（callTool 后处理 amapuri://→https://uri.amap.com/，含 mcp_amapuri_rewritten 埋点） |
| N4.5 | 流畅度 4 项 | ⏳ | —（另插一步：**全量可调值落地** ✅ APK 08-31 13:44——LlmProfiles 编辑对话框开放温度/最大Token/思考开关/强度/预算五参 + ContextBuilderConfig 上下文预算 [128k,512k] 用户化，设计可调值清单全绿） |
| N5 | BudgetGuard | ✅ | Build ✅ APK 08-31 13:54（全 API 计量跨重启 + FC 每轮/自动化运行前拦截 + fail-open + budget_blocked 埋点）——**主轨 N0-N5 收官**，仅剩 N4.5 流畅度打磨 |
| N6.1 | ContextWindow 滑窗装配器 | ✅ | Build ✅ APK 09-03 21:20（短对话零变化/长对话封顶/ctx_windowed 埋点**待实测**；顺带修 regenerate 发送无主 TOOL 存量隐患；实现偏差：不做头部占位消息——UI 全量+摘要走 system，省 token） |
| N6.2 | 摘要存储 + MIGRATION_12_13 | ✅ | Build ✅ APK 09-03 21:31（Entity/Dao/DB v13/Migration/gradle core:data 同批；**老库升级实测待用户——L3 必测**） |
| N6.3 | Summarizer + needsCompression 接线 | ✅ | Build ✅ APK 09-03 21:37（ConfigField 3 字段落 ContextBuilderConfig(TOGGLE 修正)+D51 钳位；摘要注入 :191 首段；finally 异步触发；**实测待用户**） |
| N6.4 | 全链路实测 | ✅ | **实测过（用户 09-03）**：起步 token 19万→10万（-47%）且封顶不再涨；ctx_summarized 8 段增量水印链无缝（0→2506）零失败；chars 600 钳制生效；对话零阻塞。**抽查项不阻塞**：早期事实连续性问答 / 配置改动生效 / 断网降级 |
| N6.5 | 缓存友好修订（X16） | ✅→被取代 | Build ✅ APK 09-03 22:22——量子化滑窗为过渡方案，**N6.6 世代压缩根治取代**（常驻滑窗废除） |
| N6.6 | 世代压缩（X17 · 用户裁定） | ✅ | **实测收官（09-03）**：缓存命中回 **97%**；每轮 130k 稳态 append-only；ctx_gen_switch/ctx_tail_trim 埋点齐 |
| N6.7 | 死代码清账（六项调用证据核验后执行） | ✅ | Build ✅ APK 09-03 23:31（needsCompression/ragContext/conversationHistory 移除——全库零调用证据；allocate 保留（persona/tools 截断活消费）；注释-实现对齐 X10 兑现） |

---

## 十一、每个 Step 的标准输出格式

```
### Step Nx.y 完成
**改动文件**：新增/修改（path，变化）
**验证要求**：[ ] Build [ ] 实测项
**如 Build 失败**：贴完整错误，我立即修复
```

---

## 附：核心决策沿用清单（既往统一）

| 决策 | 本计划落点 |
|---|---|
| D9/D24/D63 ActionPermission | N1.2（read AUTO/save/delete CONFIRM 不变）、N3.1（7 FC 权限原样） |
| D17 用户主权 | N0.1/N1.2/N3.2/N4.2（全部新参数 @ConfigField）、N5 |
| D28 思考分离 | N2.2/N2.3（adapter 保留 thinking 控制） |
| D30 kotlin-reflect（仅 Int/Float/Boolean） | 全部新 ConfigField |
| D51 启动恢复 | N0.1（ChatBotModuleConfig 自动恢复） |
| D57 文件主权 | N1（SAF-only 分页） |
| D8 ConfigMigration | N3.2（office_files→files 链式迁移） |
| §6.5 渐进迁移 | N2/N3（双轨→切换→删旧） |
| §7.6 降级 | N0/N1/N5（失败=结构化文案，不崩内核） |
| Step 8.3 专用存储先例 | N2.1（LlmProfileStore 不进扁平 ConfigField） |
| §八实测预算（V4 Flash ¥0.1/M） | N4.2（daily 20M/monthly $10/maxTokensPerRun 500k） |
| **§9.2 不破坏五约束（N6 补章）** | N6 全步（UI/DB 不动·预算内零变化·Migration 同 commit·摘要降级·本轮 TOOL 不瘦身） |
| HistoryCompressor 模式先例 | N6.3（chatSync 摘要 + 降级 + trace 同构） |
| §7.6 降级 | N6.3（摘要失败=仅窗口运行，不阻塞对话） |
| D17 用户主权 | N6.1/N6.3（summaryMaxChars/toolTrimChars 均 @ConfigField + 钳位） |
| N0.2 buildMsgsForLLM 提取 | N6.1（滑窗接线点——单点覆盖 FC 循环+最终综合轮+regenerate） |
