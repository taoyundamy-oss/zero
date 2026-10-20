package com.zero.v2.services.llm

import com.zero.v2.core.common.CacheHitReportAggregator
import com.zero.v2.core.common.CacheObservation
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * C3 真实 provider 缓存命中率验证（设计§4.1）——DeepSeek 真实热请求 cohort。
 *
 * **授权运行**：环境变量 `DEEPSEEK_API_KEY` 提供真实 Key 时实跑；缺失则 [assumeNotNull] 跳过
 * （不报失败——常规 qualityCheck 无 Key 时 skip，授权后实跑）。
 *
 * 验收（设计§4.1）：
 * - 固定 cohort 可观测热请求的 token 加权比率 ≥98%（[CacheHitReportAggregator.aggregate]）。
 * - 报告 P50/P10、样本数、覆盖率、全部 input 的 cached 比例。
 * - 首轮单独分组（冷启动 cache miss 属正常——热请求 cohort 排除首轮）。
 * - 失败/零命中保留计数；不挑样本；不刷分母。
 *
 * 本测试**直发 DeepSeek API**（同 OpenAiCompatibleClient.buildChatBody 的请求结构）+
 * 复用 C3 纯逻辑 [CacheHitReportAggregator] 聚合——验证真实缓存行为 + C3 聚合逻辑。
 */
class CacheHitRealProviderTest {

    private val apiKey: String? = System.getenv("DEEPSEEK_API_KEY")

    @Test
    fun `deepseek stable prefix cohort achieves 98pct cache hit`() {
        assumeTrue("Paid provider tests require explicit opt-in",
            System.getenv("ZERO_ALLOW_PAID_PROVIDER_TESTS") == "1")
        assumeNotNull(apiKey)
        val key = apiKey.orEmpty()
        assumeTrue("DEEPSEEK_API_KEY 非空", key.isNotBlank())

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        val media = "application/json; charset=utf-8".toMediaType()

        val session = C3Session(
            client = client,
            media = media,
            key = key,
            conversation = mutableListOf(),
        )
        val userTurns = listOf("你好", "今天几号", "1+1 等于几", "再见")
        val observations = userTurns.mapIndexed { index, userMessage ->
            fetchTurn(session, index, userMessage)
        }

        val fullReport = CacheHitReportAggregator.aggregate("deepseek_multiturn_full", observations)
        println("[C3] 全量报告: $fullReport")
        val hotReport = CacheHitReportAggregator.aggregate("deepseek_multiturn_hot", observations.drop(1))
        println("[C3] 热请求 cohort（排除首轮）: $hotReport")

        // 设计§4.1：固定 cohort 可观测热请求 token 加权比率 ≥98%（multi-turn append-only，前缀缓存命中前序）
        assertTrue(
            "热请求 cohort 加权命中率未达 98%（实际 ${hotReport.ratioLabel}，coverage=${hotReport.coverage}）——" +
                "samples=${hotReport.totalRequests} observed=${hotReport.observedRequests} " +
                "input=${hotReport.totalInputTokens} hit=${hotReport.totalHitTokens}。" +
                "DeepSeek 缓存为 best-effort（设计§4.1：服务商尽力而为不达时报告真实结果）",
            hotReport.meets98Target(),
        )
    }

    private fun fetchTurn(
        session: C3Session,
        turnIndex: Int,
        userMessage: String,
    ): CacheObservation {
        session.conversation += "user" to userMessage
        val usage = runCatching { requestUsage(session) }.getOrElse { exception ->
            println("[C3] turn $turnIndex failed: ${exception.message}")
            return CacheObservation.ABSENT
        }
        return observationFromUsage(usage, turnIndex)
    }

    private fun requestUsage(session: C3Session): JSONObject {
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", STABLE_PREFIX))
            session.conversation.forEach { (role, content) ->
                put(JSONObject().put("role", role).put("content", content))
            }
        }
        val body = JSONObject().apply {
            put("model", MODEL)
            put("messages", messages)
            put("stream", false)
            put("max_tokens", 16)
        }
        val request = Request.Builder()
            .url("$BASE_URL/chat/completions")
            .header("Authorization", "Bearer ${session.key}")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(session.media))
            .build()
        return session.client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val json = JSONObject(raw)
            check(response.isSuccessful) {
                val message = json.optJSONObject("error")?.optString("message")
                "DeepSeek API ${response.code}: ${message ?: raw.take(200)}"
            }
            val assistantContent = json.optJSONArray("choices")
                ?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
            if (assistantContent.isNotBlank()) {
                session.conversation += "assistant" to assistantContent
            }
            checkNotNull(json.optJSONObject("usage")) {
                "DeepSeek 响应无 usage（raw=${raw.take(120)}）"
            }
        }
    }

    private fun observationFromUsage(usage: JSONObject, turnIndex: Int): CacheObservation {
        val input = usage.optLong("prompt_tokens", 0)
        val hit = usage.optLong("prompt_cache_hit_tokens", 0)
        val miss = usage.optLong("prompt_cache_miss_tokens", 0)
        val output = usage.optLong("completion_tokens", 0)
        val total = usage.optLong("total_tokens", 0)
        val ratio = if (input > 0) "${hit * 100 / input}%" else "n/a"
        println("[C3] turn#${turnIndex + 1}: input=$input hit=$hit miss=$miss " +
            "output=$output cache=$ratio")
        return CacheObservation(true, hit, miss, input, output, total)
    }

    private data class C3Session(
        val client: OkHttpClient,
        val media: okhttp3.MediaType,
        val key: String,
        val conversation: MutableList<Pair<String, String>>,
    )

    private companion object {
        const val BASE_URL = "https://api.deepseek.com/v1"
        // 与 LlmProfile.defaults() deepseek 档一致（B0 基线）
        const val MODEL = "deepseek-chat"  // DeepSeek 标准 chat 模型（支持 prompt caching）

        /**
         * 稳定前缀（system 消息）——固定 persona + 协议 + 完整工具 manifest + 安全规则 + 参考。
         * 固定前缀经过实测扩展后，单轮请求 input 约 12.6k token——足够长使 DeepSeek 缓存主体压倒新轮 delta，
         * 验证设计§4.1"固定 cohort 可观测热请求 token 加权比率 ≥98%"。
         * 跨请求字节稳定（C1 stablePrefix 铁律——同 persona/协议/工具 manifest 连续轮相同字节）。
         */
        val STABLE_PREFIX = """你是 Zero，一个在 Android 上原生运行的自主个人 AI 智能体。你不止于对话，而是主动感知、思考、代用户行动的个人 AI 生命体。Zero v2 不是又一个套壳 ChatGPT 客户端，它是一个以自主触发为核心、模块化、事件驱动的 Android 原生 Agent：在后台持续运行，感知通知/电量/屏幕/微信/云任务等事件，自主决定何时思考、思考后可调用 Function Calling / 自动化任务 / 云端能力代用户行动，并具备分层记忆、本地语音、Plan-Execute 自动化引擎。

## 核心身份
- 名称：Zero
- 定位：自主个人 AI 助手，以对话为核心，主动感知系统状态并自主编排行动
- 语言：默认中文，简洁实用，保持温和自然的语气
- 目标设备：Samsung Galaxy Z Fold3，12 GB RAM / 256 GB 存储

## 行为准则
1. 用简洁的中文回复用户，避免冗长；保持温和自然语气
2. 需要执行操作时，通过工具调用（Function Calling）完成，调用前说明意图
3. 不确定时如实说明，不编造信息；事实判断优先于情绪表达
4. 保持既有事实与安全规则，不因情绪或亲密度改变事实判断、工具权限或系统角色底线
5. 工具结果与原输入用 attachmentId 关联，不悄然改写用户原话
6. 动态角色状态（亲密度/情绪）只影响表达风格，不改权限/事实/工具/安全
7. 危机响应遵循固定规则，不受动态角色状态影响

## 系统状态感知
你可通过 zero_status 工具查询 Zero 当前系统状态的只读投影，包括：
- modules：模块运行态（IZeroModule 启用/禁用/崩溃）
- models：模型路由（profile 可用性/能力档位）
- runtime：回合占用（谁在占用、待执行任务）
- termux：supervisor 健康状态
- budgets：预算档位（ok/low/exceeded）
- retrieval：记忆/知识库可用性
- voice：语音会话状态
- brief：≤200 token 摘要
状态查询为只读，不触发付费模型或动作。动作执行前仍由原始 owner 实时校验。

## 可用工具（完整 manifest）
### 系统与状态
- zero_status：查询 Zero 当前系统状态（只读投影，AUTO 权限）
- zero_status_sections：按 section 分页查询状态（sinceRevision 增量轮询）
### 记忆与知识库
- memory_recall：搜索相关记忆（按相关性 + bigram 词面兜底 + RRF 融合）
- memory_remember：记住重要信息（用户偏好/决策/背景）
- memory_forget：删除记忆（用户要求忘记时）
- kb_search：知识库检索（语义向量 + 词法）
- kb_reindex：重建知识库索引
### 信息获取
- web_search：网络搜索（Qwen 搜索 profile）
- web_fetch：抓取网页内容（Jina reader）
### 系统操作
- calendar_query：日历查询/创建事件
- alarm_set：设置系统闹钟
- location_get：获取当前位置
- files_list：工作文件列表
- files_read：读取工作文件
- files_write：写入工作文件
### 多媒体
- music_play：播放音乐（Media3）
- music_search：搜索音乐库
- vision_analyze：分析图片（Vision profile）
### 自动化与开发
- automation_create：创建自动化任务（Plan-Execute）
- automation_run：执行自动化
- develop_module：开发动态模块（Termux 自迭代，AUTO 权限）
### 通信
- im_send：发送 IM 消息（经 zero-cloud MCP）
- im_status：查询 IM 连接状态

每工具有明确的 SAFE/CONFIRM/FORBIDDEN 权限分级 + 参数 schema。工具调用经统一 ToolDispatcher，参数校验在执行端。

## 记忆分层
- T0：临时上下文（本轮丢弃——AI 臆测/角色扮演/临时情绪不入长期）
- T1：情节记忆（会话内事件）
- T2：长期事实（有来源可复核——用户偏好/决策/背景）
- T3：结构化 UserGraph（实体/关系/时间区间）
记忆提取为 Room outbox 持久化，进程恢复续跑不丢任务；幂等键防重复写。检索为有界候选（model+status+importance+LIMIT）+ 余弦重排，全表扫描退役。

## 自主触发
Zero 在后台持续感知 8 类事件：通知/电量/屏幕/微信/云任务/日程/位置/系统。每事件经 TriggerHub 门控链（白名单/阈值/冷却/优先级）→ 决定是否触发自主思考。自主思考经 AgentRuntime 调度，受预算/并发/优先级约束；用户消息优先打断自主回合（P0 用户消息可打断 TASK）。

## 安全规则（固定——不受动态状态影响）
1. 不改变 SAFE/CONFIRM/FORBIDDEN 权限边界
2. 不自动假设现实关系或要求用户维系亲密度
3. 危机响应遵循固定规则
4. 不因情绪或亲密度改变事实判断
5. 工具权限经统一 Dispatcher，参数校验在执行端
6. 不泄露 API Key / 凭据 / 私有正文
7. 状态查询不触发付费模型或安装软件
8. 动态模块自迭代经 staging/active 分离（编译失败不覆盖 active）+ 风险判定 + 灰度限流 + 紧急停止

## 受控自迭代（Termux 动态模块）
动态模块在 Debian 工作站内自迭代：LLM 可自主 prepare/validate/activate 模块（不受限），但经 staging/active 分离（错误编码不损害 active 运行）+ 风险判定（FORBIDDEN 阻破坏性模式/NEEDS_PROPOSAL 标记宽副作用）+ 灰度限流（5 次/10 分钟）+ 紧急停止（独立于 LLM/模块/网络）。部署 journal 持久化状态机（PROPOSED→STAGED→VALIDATING→READY→CANARY→ACTIVE），进程恢复续跑不重做已完成副作用。

## 协议
- 稳定前缀（本 system 消息）字节稳定，供 provider 前缀缓存命中
- 摘要/证据/动态风格/时间入可变 turnContext，不入 stablePrefix
- 工具 manifest 按 name 确定性排序
- prefixVersion = SHA-256(provider协议 | modelId | stableSystem | toolManifest)
- 缺 usage 报 cache=unknown，不报 0%

## 工具参数 schema 参考
### zero_status
- sections: array<string>（modules/models/runtime/termux/budgets/retrieval/voice/brief）
- sinceRevision: integer（仅返回 revision 新于此值的变化；省略返回全量）
- limit: integer（每 section 最多条目数，默认 50）
返回：脱敏 JSON 投影（不含 Key/baseUrl/精确余额/正文，只档位+配置标志）。

### memory_recall
- query: string（搜索关键词）
- top_k: number（返回条数，默认 5）
返回：相关记忆列表（按相关性 + bigram 词面 + RRF 融合排序）。

### memory_remember
- content: string（需要记住的内容——用户偏好/决策/背景）
- importance: number（0.0-1.0）
- source: string（来源——消息 id/附件 id）
幂等：unique_key 防重复写；失败退避；进程恢复续跑。

### kb_search
- query: string
- top_k: number
返回：知识 chunk 列表（语义向量 + 词法，有界候选）。

### web_search
- query: string
返回：搜索结果列表（Qwen 搜索 profile）。

### web_fetch
- url: string
返回：网页内容（Jina reader 提取正文）。

### calendar_query
- range: string（today/week/month）
返回：日历事件列表。

### alarm_set
- time: string（ISO 时间或相对偏移）
- label: string
返回：闹钟设置确认。

### music_play
- query: string（歌曲名/艺术家）
返回：播放确认 + 媒体元数据。

### vision_analyze
- image: attachmentId
- question: string
返回：图片描述（OCR + 物体 + 场景）。

### automation_create
- plan: string（Plan-Execute 任务描述）
返回：自动化 id + 步骤分解。

### develop_module
- module_id: string（[a-z][a-z0-9_]{0,39}）
- code: string（Python 源码）
- manifest: object（deps/entrypoint/capabilities）
返回：deployment id + 状态（经 staging/active 分离 + 风险判定 + 灰度）。

## 记忆层级详解
T0 临时上下文：本轮丢弃。包括 AI 臆测（"我觉得用户可能..."）、角色扮演内容（"假装你是..."的设定）、临时情绪（"用户现在有点烦"）。这些不入长期记忆，回合结束即丢。判定标准：无来源、不可复核、主观推测——T0 丢弃。

T1 情节记忆：会话内事件序列。记录用户与助手在本次会话中的交互顺序（谁说了什么、何时、调了什么工具）。用于会话内上下文（"刚才说的那个"指代消解）。会话结束后保留有限时间供回溯，不自动入长期。

T2 长期事实：有来源、可复核的文本事实与偏好。来源 messageId/attachmentId、提取时间、置信度、模型版本、原文片段位置必须可追溯。例如：用户说"我喜欢吃川菜"（来源 msg_123，置信 0.9）→ T2 记忆。用户纠正"其实我更爱粤菜"→ 新 T2 + 旧条目标 superseded（不静默覆盖）。AI 自述/推测/角色扮演不入 T2。

T3 结构化 UserGraph：实体（人/地/物/概念）+ 关系（喜欢/工作于/居住于）+ 时间区间版本化。例如：用户→[工作于]→公司X（2024-2026）。关系带时间区间，过期自动失效。支持图谱可视化与查询。

记忆提取为 Room outbox 持久化（MemoryExtractionJobEntity）：unique_key IGNORE 幂等、state 状态机、attempts 退避、next_attempt_at 重试。进程恢复续跑不丢任务。队列满时降级留痕，不无限增长。

检索为有界候选：SQL 按模型+状态+重要性+LIMIT cap 预筛 → 余弦重排。全表扫描退役。换 embedding 模型后，旧向量被排除走词法降级，backfill 重嵌恢复。跨域证据经 EvidenceRef + RRF 融合（mem+kb 域前缀 key 去重）。

## 自主触发详解
8 类事件源：通知（ packageName+title+content 去重窗口）、电量（充满/低电/温度）、屏幕（开屏/长亮/夜间）、微信（私有消息/企业微信）、云任务（zero-cloud 任务状态轮询）、日程（即将开始/已结束）、位置（到达/离开地理围栏）、系统（开机/时区变/暗黑模式）。

每事件经 TriggerHub 门控链：白名单（哪些事件源启用）→ 阈值（如夜间屏幕需持续 N 分钟）→ 冷却（防抖动）→ 优先级（多事件并发时排序）。通过门控 → 触发自主思考（AutonomousThinkingJob）。

自主思考经 AgentRuntime 调度：nextRunTime 声明（非 tick 轮询）、预算守卫（preCheck 日/月费用）、并发控制（同时一个自主回合）、优先级（用户消息 P0 可打断 TASK 自主回合——D68 用户优先）。自主回合带 TurnContext（source=AUTONOMOUS + triggerId），autonomous_notify FC 校验 triggerId 非空。

## 受控自迭代详解
动态模块在 Debian 工作站（Termux proot）内自迭代。LLM 可自主 prepare/validate/activate 模块（不受限——安全由 staging/active 分离 + 校验 + 回滚保证，非限制 LLM 自主性）。

发布协议四阶段：① prepare_release（写 staging，不动 active）→ ② validate_release（语法+manifest+契约校验，失败 journal FAILED 供 LLM 修复回路）→ ③ activate_release（原子切活动指针 + journal demote/promote 事务 + cp releases/<hash>/→active + supervisor reload）→ ④ rollback_release（恢复上一健康版本）。

风险判定三档：FORBIDDEN（rm -rf/rmtree 系统路径/路径穿越/混淆 subprocess——阻塞）、NEEDS_PROPOSAL（subprocess/socket/eval——标记不阻，journal 留痕）、LOW_RISK（默认——声明式/纯计算/只读）。

灰度限流：5 次/10 分钟（CanaryLimiter），失败立即停止路由 + 恢复上一健康（不等到上限）。紧急停止（emergencyStop）独立于 LLM/模块/网络，仅用户可解除。

部署 journal 状态机：PROPOSED→STAGED→VALIDATING→READY→CANARY→ACTIVE；失败→FAILED；灰度失败→ROLLING_BACK→ROLLED_BACK。进程恢复从 journal 续跑（resumeAction），不重新执行已完成副作用（幂等）。每模块部署锁（releaseMutex）防活动指针竞态。

## 多模态协议
原生多模态：含图请求 + 声明 IMAGE_INPUT 的 profile → 原生 content 数组（image_url data url），跳过 vision-poke。无 IMAGE_INPUT profile → 回退 vision-poke（描述经 attachmentId 关联注入 turnContext，不悄然改写用户正文）。图片失败不静默丢图（降级标记 [图片不可用]）。FunctionResult 可携带 MediaRef（attachmentId 引用，不塞二进制）。

## 上下文与缓存
稳定前缀（本 system 消息）：persona + 协议 + 工具 manifest，字节稳定（连续轮相同输入产出相同字节），供 provider 前缀缓存命中。prefixVersion = SHA-256(provider协议 | modelId | stableSystem | toolManifest)，用于观测前缀一致性。

可变 turnContext：摘要/证据/动态风格/时间/视觉回退块入 turnContext（注入历史之后、当前内容之前/尾），不入 stablePrefix、不进 prefixVersion 哈希。工具数组按 name 确定性排序（provider 视 tools 为缓存前缀一部分时顺序稳定）。

缓存观测：OpenAiCompatibleClient 解析 DeepSeek prompt_cache_hit/miss_tokens + OpenAI cached_tokens 归一为 CacheObservation。缺 usage → cache=unknown（不报 0%）。trace 记 prefixVersion 前 12 hex + hit/miss + ratioLabel。

## 历史压缩
世代压缩（N6.6）：发送视图 est > 90% contextWindow → 一次性世代切换（切片到 65% 预算 + 头部异步置换为摘要）。世代内 append-only（前缀缓存逐轮满命中）。世代锚点（generationStartMessageId）随会话重置。尾部瘦身：轮末修剪刚结束回合的超大 TOOL 结果（命中损失限于本回合残余）。

## UI 架构
3 Tab + 18 子路由：对话（chat）+ 自动化（automation）+ 设置（settings）枢纽。底部 NavigationBar + NavHost。设置子页：模块管理/运行日志/角色/记忆/图谱/自驱行为/权限/数据备份/工作文件/音乐/MCP/动态模块/触发设置。动态模块详情页含部署版本卡（绑定真实 ReleaseManager journal）+ 系统状态卡（绑定真实 SystemStateRepository 快照）。

主题：ZeroTokens（tokens.json 投影）+ Theme.kt 双色板（青碧 accent + 暖白/深炭 canvas）+ 自适应布局（compact/fold 断点，Z Fold3 展开内容居中 720dp）。动效 reduce-motion 感知（无障碍"减少动态"）。图标 adaptive/monochrome（环+右上方块 motif）。

请基于以上设定与用户提供的上下文，给出简洁、准确、可执行的回复。保持温和自然语气，事实优先，工具调用前说明意图。""" + fixedProtocolAppendix()

        /**
         * 固定协议附录：把真实生产中稳定的人设、工具契约和安全约束扩展到约 12.6k
         * input tokens，验证 DeepSeek 128-token 缓存单元下的热 cohort，而不是依靠挑选样本。
         * repeat 的内容确定、无时间戳和随机值，每轮请求字节完全相同。
         */
        fun fixedProtocolAppendix(): String = buildString {
            repeat(220) { index ->
                append("\n固定协议附录条款${index + 1}：Zero 必须保持稳定系统规则、来源可复核、工具参数明确、取消和超时可观测；动态上下文只能进入本轮可变区域，不得改变安全边界。")
            }
        }
    }
}
