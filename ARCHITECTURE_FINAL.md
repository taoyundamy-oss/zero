> P5自然来源真实链路（2026-10-16）：8条真实历史来源/10问冻结，手机证据链→桌面本机生成实际验证。生产2500ms确定3/4、不确定0/2、无支持2/4；10秒诊断仍有语义/协议失败，不部署。来源/范围/只读及16回归通过，根质量/完整编译通过。旧固定全库1411条均UNKNOWN/无消息ID，资格覆盖阻塞；个人九题未消耗。临时包/USB转发/自有进程清理，缓存/配置/观测保持。详见持久系统设计§7.15与artifacts/p5-natural-chain-20261016/acceptance.md。

> P5中文独立对照（2026-10-16）：冻结33资料/18问，BGE/E5/Qwen有答案五组召回均1.0；裸向量带旧证据，当前过滤去除，RRF无额外增益，无支持判断仍待补。Fold3 BGE87次真实推理/桌面数值一致，当前排序子门通过；P95 73ms（含三种排序，不含生成模型），中途Dozing限制保留。手机1项+原16项回归、根质量及完整编译通过，临时包/模型清理，主应用/缓存/配置保持。只完成限定合成召回，个人盲测及S-D/S-E父门开放。详见持久系统设计§7.14及artifacts/p5-chinese-20261016/acceptance.md。

> P5中文优先/真实向量补验（2026-10-16）：Fold3真实BGE 66次成功，冷首条537ms、热查询P95 66ms；冻结质量中文5/6、英文2/6未过。事后中文→中文4/4仅诊断小样本，后续以独立中文质量门为主，跨语言补充。新个人3题已冻结待来源核验，未调参/改写历史。根质量及完整编译通过，JVM784含2跳过；16项协议回归通过，临时包/模型已清理，主应用与缓存保持。详见artifacts/p5-native-recall-20261016/acceptance.md及持久系统设计§7.13。

> P5实机补验（2026-10-16）：Fold3/API35独立来源与假模型调用链16/16通过；同源码测试包编译复核12秒通过，无代码修订。临时包已清理，主APK摘要和原包/UID列表不变，无日常数据写入或云推理。真实模型、个人盲留出、推理时延/能耗与其他P5父项仍开放。证据artifacts/p5-phone-pipeline-20261016/acceptance.md。

> 当前P5进度（2026-10-16 调用链续片）：已实现显式2500ms证据调用链、完整段落来源及人工确认审计、scope隔离、请求前后模型资格检查；未接管日常检索。质量门及完整编译通过，当前JVM784含2跳过、零失败，本轮新增16项和API35隔离16项全通过。新个人盲留出、真实模型/手机质量、跨场景偏好归纳与S-E仍开放。临时包/自有模拟器已清理，无手机/云/历史写入，缓存与默认保持。见持久系统设计§7.12及artifacts/p5-evidence-pipeline-20261016/acceptance.md。

> 2026-10-16 P5三类用途续片：EvidenceSupport候选契约区分确定/不确定/无支持，客户端核对来源与当前性；尚无生产调用。新8候选45问，“原文判断＋客户端核验”三组15/15，通过本次合成开发门；向模型发送核验标记的对照未过门。不等同个人盲留出/全库/手机达标。修复空正文、未知角色默认及Android JSON API兼容问题；根qualityCheck/assembleDebug 3分54秒，平台补修后services质量/测试与完整编译1分36秒通过，JVM762含2跳过、零失败。冻结输入未变；无安装/云推理/手机操作或历史改写，临时本机模型已关闭。详见持久系统设计§7.10及artifacts/p5-support8-20261016/acceptance.md。

> 2026-10-16 P5独立语义门：新冻结36问三语言组，现行提示9/12、8/12、8/12，固定资格提示均10/12（未达90%，不替换生产）；BGE仅相关性基线。修复空白证据查询误返全部候选边界，工具排序及缓存策略保持。红绿回归、qualityCheck/assembleDebug 2分34秒通过，JVM752含2跳过、零失败。临时本机模型已关闭，无应用安装/云推理/手机操作或自动历史改写；S-D/S-E及P5父门开放。详情artifacts/p5-semantic-gate-20261016/acceptance.md、持久系统收敛设计§7.9。

> 2026-10-16 P5本轮：证据资格/有效期/冲突契约、脱敏分类诊断、本地重复偏好候选及索引维护页落地；候选仍需人工保存确认，无自动历史改写。新合成语义资格中英5/6与3/6未过门，不部署。最终qualityCheck/两类测试APK/assembleDebug 2分1秒通过，JVM750含2跳过、零失败；模拟器25项应用+29项索引通过。三个临时包清理，无手机安装/操作和云推理，缓存/default保持。详见artifacts/p5-evidence-20261016/acceptance.md。

> 2026-10-16 P5 S-D第二候选未过门：冻结合成中英16问，BGE v2-m3原文重排中文4/8、英文2/8（E5 4/8、3/8），正确候选覆盖16/16；只做本地CPU对照，不部署、不改默认。上一轮结构化type存在标签混淆，不能外推来源结构收益；后续独立冻结证据资格/当前性验收。详见artifacts/p5-ranking-bge-20261016/acceptance.md。

> 2026-10-16 P5 S-C JSON闭合：v3备份恢复四种提案状态、确认审计与删除依赖，兼容v1/v2；保留本机同ID记忆/决定/复核来源，损坏审计整批回滚。20项存储与操作、2项生产备份页面通过；qualityCheck/测试APK/assembleDebug 3分6秒通过，JVM740项含2跳过、零失败。两个模拟器临时应用已清理，无手机安装/写入、无云推理。缓存与默认模型保持；P5质量父门仍开放。详见artifacts/p5-review-backup-20261016/acceptance.md。

> 2026-10-16 P5 S-C完成本机片：Room35独立复核提案、记忆管理入口及显式确认/拒绝落地；无自动历史改写。修复关键词删除派生版本、范围传递、超长输入与来源状态更新。最终qualityCheck/两类测试APK/assembleDebug4m22s通过，JVM740项含1跳过、零失败；模拟器33项存储/迁移/删除及2项完整UI通过。三个临时应用清理，Fold3未安装或操作，无云推理。JSON提案备份恢复与P5质量父门仍开放；详见artifacts/p5-review-20261016/acceptance.md。

> 2026-10-16 P5来源结构化收尾：完整来源unit协议与类型校验修订，最终qualityCheck/测试APK/assembleDebug2m35s通过，JVM739项含1跳过、零失败。独立中英16问来源重排8/16未达门，不部署；历史1411条仅21唯一来源候选、1歧义、1389未匹配，未写回。明示/推断偏好与历史复核方案见持久系统设计第7节，提案UI/存储仍待实施。无安装/云推理/手机操作，缓存与默认模型不变。证据artifacts/p5-source-ranking-20261015/acceptance.md。

> 2026-10-15 P5新留出更新：冻结个人语料和新中英三题，BGE/Qwen/E5及固定排序均未通过个人Top5门；E5仅合成生产评价门两域12/12通过，默认remote不变。修复缺type默认FACT与英文长期规范校验，2项回归先红后绿，最终qualityCheck/测试APK/assembleDebug2m12s通过。实机旧FACT276/EVENT1135均UNKNOWN，新版安装后暂无提取日志；历史复核/推断偏好闭环仍开放。无安装/云推理/临时应用，详见artifacts/p5-recall-20261015/acceptance.md。

> 2026-10-15 P5最新限定收尾：英文时态过滤、语言分组质量门及旧中文基线兼容已修复；21项发布恢复实机、最终qualityCheck/assembleDebug通过（JVM730含1跳过）。个人1411条本地对照表明多语言候选仍不达标；四分钟离电源观察完成，真实单句ASR/向量并发CER25%边界通过，完整Live/TTS和长期能耗未闭合。旧1260/320条用量、记忆及配置保留，临时测试包/手机候选模型已清理，主APK未替换、默认remote不变。详情artifacts/p5-multilingual-20261015/acceptance.md。

# Zero v2 — 最终架构设计

> 2026-10-15 P7/P5补验：真实分屏8条件与限定完整Agent跨进程技能复用通过；BGE生产召回发布门、显式索引回滚维护API及31项事务/恢复、ASR合成并发通过。回滚复用worker锁与Room事务，不改后端选择。质量门通过，双用户旧1198/320条用量及配置保留，临时测试清理；能耗/个人语料/默认迁移和长期缓存仍开放。见持久系统收敛设计及artifacts/p7-p5-20261015/acceptance.md。

> 2026-10-15 持久系统P7：Room34双用户升级、旧用量保留、隔离恢复与技能核验已通过；后续补齐Gemini重排超时分类诊断、链接/角色说明和合成技能复用。长期缓存、P5召回/索引、P6逐型号门保持开放；具体证据以持久系统收敛设计§6和artifacts/p7-followup-20261015为准。

> 最新验收收尾（2026-10-11 LLM-U7）：代码审核修复4项P1（共享连接契约复验、草稿路由校验、原生状态上下文预算、Responses失败用量记录），新增显式cf-openai-responses-v1及图片输入，共13契约。CF Luna真实聊天、图片SSE、medium思考及两次工具3/3通过，共5次请求、每次1536输出上限；2个加密思考项、3条输出状态恢复回传。LLM页完成分区卡片、参数/能力选项和模型卡片重构；质量530任务、JVM592通过1跳过、实机UI10/10、Agent6/6。原配置/密钥/MCP摘要、实装APK、FGS2003和八模块恢复核对通过。保留Chat Completions，无自动迁移。通用契约不代表所有GPT型号或全部OpenAI API功能已验收。三家直连取消、Ollama/vLLM暂缓不阻塞、Gemini账务未核实、原Qwen400取消及A9停止不变。证据 `artifacts/llm-review-ui-20261011/acceptance.md`。

> 此前验收收尾（2026-10-11 LLM-U6b-12）：GPT-6 Luna经CF REST Responses已实现并实测：聊天/SSE通过，medium思考+两次Agent工具及落盘续接通过，1个真实加密思考项、3条输出状态恢复回传。共8次应用请求（初始low 5次无思考项，补验medium 3次），每次1536输出上限。新增cf-luna-responses-v1，共12契约，保留旧Chat Completions和全部配置，无自动迁移。质量530任务、JVM586通过1跳过、Agent6/6；实装、配置摘要、FGS2003及八模块恢复核对通过。三家直连取消、Ollama/vLLM暂缓不阻塞、Gemini账务未核实、原Qwen400取消及A9停止不变。证据 `artifacts/llm-u6b-luna-responses-20261011/acceptance.md`。

> 此前验收收尾（2026-10-11 LLM-U6b-11）：Anthropic原生手动思考/签名已实现并通过CF真实验收：聊天、SSE、两次Agent工具3/3，5次请求（总输出上限1536、思考预算1024）；1个真实签名块，续接前3条原生内容经metadata落盘恢复回传成功。新增显式cf-anthropic-native-v1契约（共11契约）、整条消息原生状态及存储元数据v3；旧契约/配置不自动迁移。质量530任务、JVM579通过1跳过、Agent6/6，实装APK及八模块恢复通过。用户确认本轮自行修改模型/密钥，其新配置保留，MCP/业务模块配置不变。当前授权功能范围收口：三家直连取消，Ollama/vLLM暂缓部署不阻塞；Gemini账务层级仍未核实，原Qwen400取消、A9停止不变。完整证据 `artifacts/llm-u6b-anthropic-thinking-20261011/acceptance.md`。

> 此前收尾结论（2026-10-11 LLM-U6b-10，覆盖下方历史待办）：CF Haiku真实两次Agent工具回传通过，本片3次请求、每次256输出上限，与U6b-7聊天/SSE合并闭环；Luna三项已于U6b-9通过。用户明确取消OpenAI/Anthropic/Gemini直连验收，不再阻塞收尾。质量530任务、JVM572通过1跳过、实机Agent6/6及原配置/八模块恢复核对通过，无生产代码或VPN修改。当前可用配置已验收收尾；Ollama/vLLM无可用部署仍待验，Anthropic原生思考/签名属于未实现能力（当前明确拒绝），不能写成已支持；Gemini账务层级未独立核实。U6扩展父门仍开放，U4b取消、A9停止不变。总证据 `artifacts/llm-u6b-closeout-20261011/acceptance.md`。

> 此前有效结论（2026-10-11 LLM-U6b-9，覆盖下方历史待验状态）：用户当前手机代理下，Zero生产客户端的GPT-6 Luna聊天/SSE/真实两次工具全部通过，5次应用层请求，上限6、输出上限256、effort=none。默认ProxySelector实际选择HTTP；免费trace先HK、后US/SJC，NO_PROXY对照也为US/SJC，故节点标签不等于固定出口，也不能断言地区封禁。未改VPN、保存配置或生产代码；质量门及实机Agent6/6通过，原配置和八模块恢复核对一致。证据 `artifacts/llm-u6b-phone-luna-20261011/acceptance.md`。Luna手机/SSE/工具条件关闭；其他直连、Haiku真实工具/思考签名、Ollama/vLLM和Gemini账务边界仍保留，U4b取消与A9停止不变。


> LLM最新网络核对（2026-10-11 U6b-8）：无生产代码改动。CF Luna/Nano在原手机出口CN/LAX失败，经PC出口US/SJC同请求成功，Luna Chat/Responses均200；A→B→A→B复核一致。下方U6b-7“统一计费不可用”只描述原路径响应，不是全局停服结论。Zero手机日常网络、Luna SSE/工具仍待验；详情 `artifacts/llm-cf-openai-diagnostic-20261011/acceptance.md`。

> LLM当前实现核对（2026-10-11 U6b-7）：schema3、19连接模板、10显式参数契约；新增CF Anthropic原生SSE转换，文本/工具增量/用量/结束映射到既有StreamChunk，原生思考签名块明确拒绝。Haiku聊天/SSE实机通过，Luna遇CF统一计费503未通过；Agent执行/Room/配置owner不变。质量530任务、JVM572通过1跳过、实机Agent6/6；代码与历史设计差异、交付及未验边界统一见 `artifacts/llm-u6b-cf-final-20261011/acceptance.md`、`code-doc-audit.md`。后文各片记录按日期保留。

> 基于 v1 全链路经验 + Alife v4.1.0 源码分析 + Claude Code / OpenCode 自动化模式研究
> 已纳入全部审阅反馈 + 第二轮架构审查修复
> 修订记录：
>   v2.3 — Phase 13：多模型架构 + 双层持久化 + SecureKeyStore + 6 项工程隐患修正
>   v2.2 — 第三轮优化：Token动态配额 / 并发FC / 沙箱隔离 / PriorityQueue / Readability / 混合检索 / AutoForm反优化 / 白盒可观测 / 崩溃自愈
>   v2.1 — 第二轮架构审查：循环依赖 / 后台限制 / Plan-Execute容错 / 并发安全 / 写入竞态
>   v2.0 — 初始设计
> 状态：待确认后启动 Phase 1 编码

---

## 一、决策记录（已确认）

| # | 决策点 | 结论 | 理由 |
|---|--------|------|------|
| 1 | KSP 注解 vs 手动 Hilt | **手动 Hilt 绑定**（Phase 1-7），Phase 8+ 评估 KSP | 初期模块可控，KSP 增加编译复杂度 |
| 2 | 配置持久化 | **Room 表**：`module_configs(moduleId, configJson)` | 统一管理、便于备份导出、不产生 N 个 DataStore 文件 |
| 3 | FC 协议 | **OpenAI 原生 FC**（主），非 FC 模型 Prompt 层降级为 JSON/XML | 主流模型对 OpenAI FC 训练最充分，解析成功率最高 |
| 4 | Python 执行 | **暂缓至 Phase 9+** | Chaquopy 增加 APK 体积数十 MB，优先用 Android 原生能力 |
| 5 | 插件热加载 | **QuickJS 引擎预留**（Phase 9+） | 轻量、沙箱安全、无需重新打包 |
| 6 | v1 代码策略 | **架构从零搭建，数据层/LLM 客户端/ONNX 直接移植** | 架构不继承上帝类包袱，成熟工具代码复用 |
| 7 | 模块依赖 | **launchOrder + Hilt 构造函数注入**，无需显式 DAG | Hilt 已隐式解决依赖，launchOrder 控制初始化顺序 |
| 8 | 消息过滤 | **独立责任链 FilterPipeline**，ChatBus 仅负责通知广播 | 避免 SharedFlow 并发修改竞态 |
| 9 | 心跳保活 | **前台 Service(轻量检查) + WorkManager(重量执行) + AlarmManager(唤醒)** | 三层分层：轻量检查不触发网络、WorkManager 自带重试/约束、AlarmManager 兜底唤醒 |
| 10 | Phase 顺序 | **P1 框架 → P2 对话 → P3 FC 系统 → P4 记忆 → P5-P9** | FC 系统提前，Memory 和 SystemEvent 依赖它 |
| 11 | 循环依赖解耦 | **SystemBus.OnScheduleTriggered 事件解耦 SEM→ATM**；FunctionRegistry 延迟查询 | 避免 Hilt 构造循环和逻辑环路 |
| 12 | Plan-Execute 容错 | **maxReplanCount=2 + JSON 分层提取 + 降级规则引擎** | 防止 LLM 无限重规划耗尽 Token；LLM 非纯 JSON 输出容错 |
| 13 | MessagePipeline 安全 | **withTimeout(3s) + Filter 100ms 性能契约** | 防止耗时 Filter 卡住 UI 线程 |
| 14 | ChatBus 缓冲区 | **差异化策略**：onChatReceived DROP_OLDEST(64) / onToolCall SUSPEND(16) / onChatFinished SUSPEND(8) | 高频流式可丢帧；ToolCall 不可丢帧 |
| 15 | Memory 写入锁 | **Mutex + Room @Transaction** | 防止自动化后台与前台对话并发写入触发 SQLite database is locked |
| 16 | Token 动态配额 | **Token Budget Allocator**: System 15% + Tools 15% + RAG 20% + History 50%。超预算时压缩旧对话为摘要 | 没有预算管理的 LLM 应用不可靠，防止超出 Context Window |
| 17 | 并发 FC 执行 | **同批次 ToolCall 默认并行** (coroutineScope+async)，显式依赖通过 `depends_on` 声明 | 3 个独立搜索从 3T→T |
| 18 | 沙箱隔离 | **SAFE/SENSITIVE 两级安全**，SENSITIVE 操作弹窗确认 | 防止 LLM 幻觉误删数据/乱发通知 |
| 19 | 唤醒任务队列 | **PriorityQueue<SystemTickTask>** + 三重唤醒源 (AlarmManager/WorkManager/Service) | O(n)扫描→O(log n) |
| 20 | ContentExtractor 提纯 | **Readability 算法** (文本密度+标签层级剪枝) + 降级管道 | 普通 Jsoup 提取噪声极大 |
| 21 | 混合检索 | **FTS5 全文 + Vector 向量 + RRF 融合** (Phase 5+) | 纯向量检索对关键词/数字敏感度差 |
| 22 | AutoFormUI 反优化 | **onAwake 时解析为 List<FormFieldState>**，UI 只绑定 State，不反射 | 反射在 Compose 渲染路径不可接受 |
| 23 | 白盒可观测性 | **AgentTrace 系统**：每操作带 traceId，UI timeline 可视化 | 调试自动化任务的核心依赖 |
| 24 | 崩溃自愈 | **ModuleLifecycleManager 包裹 try-catch**，Core 模块崩溃不自动禁用，非 Core 标记 DISABLED | 基础设施，越早越好 |

---

## 二、外部自动化工具模式研究

### 2.1 Claude Code 核心模式

Claude Code 是 Anthropic 的命令行 AI 编程代理。关键设计模式：

| 模式 | 描述 | Zero v2 映射 |
|------|------|-------------|
| **Plan-Execute 循环** | 制定计划 → 执行步骤 → 观察结果 → 调整计划 | AutomationModule 的 `Plan → Execute → Synthesize` 管道 |
| **MCP 工具协议** | 通过 Model Context Protocol 动态接入外部工具 | `FunctionRegistry` 的 `register()` 机制 |
| **权限模式** | `bypassPermissions` / `acceptEdits` / `default` | AutomationModule 的 `ExecutionGuard`（成本/时间/步骤上限） |
| **后台子代理** | `task` 工具启动独立代理执行子任务 | `parallelSearch()` — 多搜索词并行执行 |
| **Hook 系统** | Pre/Post 钩子（`before_chat` / `after_edit` 等） | `onTaskStart` / `onStepComplete` / `onTaskFinished` 事件 |
| **Worktree 隔离** | Git worktree 中安全修改代码 | Android 不需要（无文件系统级回滚需求） |
| **Token 预算** | `budget.total` / `budget.spent()` / `budget.remaining()` | `ExecutionGuard.maxTokensPerRun` |
| **stream-json 输出** | 实时流式 JSON 输出（reasoning / tool_call / result） | `PokeEvent` 实时推送步骤进度到对话 |

### 2.2 OpenCode 核心模式

OpenCode 是 Anthropic 的 VS Code 多代理协作框架：

| 模式 | 描述 | Zero v2 映射 |
|------|------|-------------|
| **Agent 类型系统** | `general-purpose` / `code-reviewer` / `plan` / `explore` | Module 分类（CORE/INSTRUMENT/SYSTEM/AUTOMATION） |
| **Skill 系统** | 打包的指令文件（`SKILL.md`），按需加载 | 未来 Phase 的 Skill Module（对标 Alife Skill 系统） |
| **Workflow 编排** | `pipeline()` — 无屏障流水线 / `parallel()` — 屏障并行 | AutomationModule 内 `executePipeline()` |
| **Token 预算感知** | `budget.remaining()` 控制代理生成数量 | `ExecutionGuard` |

### 2.3 对 AutomationModule 的核心启示

1. **Plan-Execute 优于固定管道**：当前的 `SEARCH_AND_ANSWER` / `KNOWLEDGE_UPDATE` / `DAILY_BRIEF` 三种固定管道太僵硬。LLM 应能自主规划步骤。

2. **步骤级进度推送**：每完成一个子步骤（搜索 / 抓取 / 合成），通过 Poke 实时推送到对话，用户可见执行进度。

3. **守护机制必不可少**：Claude Code 的 `bypassPermissions` 和成本守卫 ($5 硬上限) 证明自动化必须有护栏：
   - 最大步骤数（默认 10）
   - 最大 Token 消耗（默认 50K）
   - 最大执行时间（默认 5 分钟）
   - 高成本操作需用户确认

4. **结果持久化为对话**：OpenCode 的 Workflow 完成后输出结构化结果。Zero 的自动化结果应自动创建带标题的对话记录。

---

## 三、消息处理管道（替代 SharedFlow 修改）

### 3.1 问题

原设计中 `onChatSend` 暴露为 `MutableSharedFlow<ChatSendEvent>`，允许订阅者直接修改 `event.message`。如果多个模块同时收集（Flow 并发），修改顺序不可控。

### 3.2 改进方案：独立 FilterPipeline（v2.1 增加超时保护）

```kotlin
// framework-module/src/.../MessagePipeline.kt

/**
 * 消息预处理管道（责任链模式）。
 *
 * 替代原设计中通过 MutableSharedFlow 修改消息的方案。
 * 每个 Filter 按 filterOrder 排序依次处理消息。
 * 处理完成后通过 ChatBus 广播 onChatSent（通知性质，不修改）。
 *
 * ⚠️ 安全保护（v2.1）：
 * - 全局超时 3 秒，超时则跳过所有 filter 返回原始消息
 * - 每个 IMessageFilter 必须遵守 100ms 性能契约
 */
@Singleton
class MessagePipeline @Inject constructor(
    @ZeroMessageFilters private val filters: Set<@JvmSuppressWildcards IMessageFilter>
) {
    private val ordered: List<IMessageFilter> by lazy {
        filters.sortedBy { it.filterOrder }
    }

    suspend fun process(event: ChatSendEvent): ChatSendEvent {
        return withTimeoutOrNull(3000L) {
            var current = event
            for (filter in ordered) {
                current = filter.process(current)
            }
            current
        } ?: event  // 超时 → 降级返回原始消息
    }
}

/**
 * 消息过滤器接口。
 *
 * ⚠️ 性能契约（v2.1）：此方法必须在 100ms 内完成。
 * 禁止: 网络请求、DB 写入、文件 I/O、大文本正则回溯。
 * 允许: 字符串拼接、长度检查、简单正则匹配、时间戳格式化。
 */
interface IMessageFilter {
    val filterOrder: Int
    suspend fun process(event: ChatSendEvent): ChatSendEvent
}
```

### 3.3 ChatBus 差异化缓冲策略（v2.1）

不同事件类型使用不同的 `SharedFlow` 配置：

```kotlin
// ChatBusImpl.kt

@Singleton
class ChatBusImpl : ChatBus {
    // 高频 + 可丢帧（UI 流式渲染，丢一帧不影响最终结果）
    override val onChatReceived = MutableSharedFlow<StreamChunk>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // 低频 + 不可丢帧（Tool Call 必须全部精确到达）
    override val onToolCallStart = MutableSharedFlow<ToolCallStartEvent>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.SUSPEND
    )
    override val onToolCallResult = MutableSharedFlow<ToolCallResultEvent>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.SUSPEND
    )

    // 每次对话一次 + 不可丢帧
    override val onChatFinished = MutableSharedFlow<ChatFinishedEvent>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.SUSPEND
    )

    // 通知性质 + 可丢旧消息
    override val onChatSent = MutableSharedFlow<ChatSentEvent>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // 系统消息 + 可丢旧消息
    override val onPokeSend = MutableSharedFlow<PokeEvent>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
}
```

### 3.3 ChatBus 职责调整

```
调整前：
  onChatSend: MutableSharedFlow<ChatSendEvent>  ← 订阅者可修改消息（有竞态风险）

调整后：
  MessagePipeline.process(event)                    ← 责任链依次处理（确定性顺序）
  onChatSent: MutableSharedFlow<ChatSentEvent>      ← 处理完成后广播（只读，通知性质）
  onChatReceived: MutableSharedFlow<StreamChunk>    ← LLM 流式输出
  onChatFinished: MutableSharedFlow<ChatFinishedEvent> ← 对话完成
  onPokeSend: MutableSharedFlow<PokeEvent>           ← 系统消息广播
```

---

## 四、循环依赖解耦（v2.1）

### 4.1 SystemEventModule ↔ AutomationModule 事件解耦

**问题**：`SystemEventModule.onTick()` 直接持有 `AutomationModule` 引用 → 逻辑环路。
**方案**：SystemBus 增加 `onScheduleTriggered` 事件，SystemEventModule 只发布事件，AutomationModule 订阅执行。

```
调整前:  SystemEventModule.onTick()
           → automationModule.executeNow()  // 直接引用, 逻辑环路

调整后:  SystemEventModule.onTick()
           → SystemBus.emit(OnScheduleTriggered(automationId))
         AutomationModule 订阅:
           → SystemBus.onScheduleTriggered.collect { executeNow(it.automationId) }
```

```kotlin
// SystemBus 新增事件
interface SystemBus {
    // ... 原有事件 ...
    val onScheduleTriggered: MutableSharedFlow<ScheduleTriggeredEvent>
}
data class ScheduleTriggeredEvent(
    val automationId: String, val triggeredAt: Long, val source: TriggerSource
)
enum class TriggerSource { HEARTBEAT, ALARM, MANUAL }
```

### 4.2 FunctionRegistry 延迟查询（避免 ChatBot → ContextBuilder → FunctionRegistry → ModuleLifecycleManager 环路）

**关键约定**：`FunctionRegistry.getAllDefinitions()` 在每次 `buildSystemPrompt()` 时延迟调用（而非 DI 构造时固化），`FunctionRegistry` 不持有 `ModuleLifecycleManager` 引用 — 各模块在 `onAwake()` 中通过 `registry.register()` 主动注册，`getAllDefinitions()` 只返回已注册的 Map。

---

## 五、模块间通信逻辑详解

### 5.1 消息发送全流程

```
  用户输入 "今天有什么AI新闻？"
    │
    ▼
  ChatBotModule.sendMessage("今天有什么AI新闻？", source = CHAT_WINDOW)
    │
    ├── 1. 构建 ChatSendEvent(message, source, attachments)
    │
    ├── 2. MessagePipeline.process(event)
    │     │
    │     ├── MessageFilterModule.process()   ← 注入时间戳
    │     │     "当前时间:[2026-08-10 14:35]\n今天有什么AI新闻？"
    │     │
    │     └── [未来其他 Filter，如敏感词过滤]
    │
    ├── 3. ChatBus.emit(onChatSent)           ← 广播（通知各模块）
    │     SystemEventModule 收到 → 重置退避计数器
    │
    ├── 4. ContextBuilderModule 组装 System Prompt
    │     ├── 角色人设（从 CharacterManager 获取）
    │     ├── 本轮上下文扩展（视觉/记忆/事实 — ChatBus 注入）
    │     ├── 时间信息块
    │     └── 可用工具列表（FunctionRegistry.getAllDefinitions()）
    │
    ├── 5. LLMClientModule.chatStream(messages, systemPrompt, tools, config)
    │     │
    │     ├── 发送 HTTP POST to DashScope
    │     └── 收集 SSE 流
    │
    ├── 6. 每收到 StreamChunk:
    │     ├── ChatBus.emit(onChatReceived)    ← UI 增量渲染
    │     ├── 检查 chunk.toolCallDelta:
    │     │   ├── 有 → 解析 ToolCall → 暂停流收集
    │     │   └── 无 → 继续
    │     └── 检查 chunk.isFinal → 流结束
    │
    ├── 7. 如果有 Tool Call:
    │     ├── ChatBus.emit(onToolCallStart)
    │     ├── FunctionRegistryModule.execute(name, arguments)
    │     │   └── ModuleRegistry → WebSearchModule.search("AI 新闻")
    │     │       ├── ContentExtractor.extractBatch(urls)
    │     │       └── 返回 FunctionResult(data, structuredData)
    │     ├── ChatBus.emit(onToolCallResult)
    │     └── 重复步骤 4-6（continueAfterToolCall）
    │
    └── 8. 对话完成:
          ├── ChatBus.emit(onChatFinished)
          │   ├── MemoryModule 收到 → intelligentMemorize()
          │   └── AutomationModule 收到 → 检查是否自动化任务
          └── 持久化 MessageEntity
```

### 5.2 心跳与主动行为流程（v2.1 分层 + 事件解耦）

```
  BackgroundServiceModule (前台 Service 运行中)
    │
    ├── Layer 3: AlarmManager.setExactAndAllowWhileIdle（每 15 分钟）
    │     └── 系统级唤醒 → 检查逾期提醒/任务
    │
    ├── heartbeatLoop() 每次 tick（轻量检查，≤5s）:
    │     │
    │     ├── 遍历所有 ITimedModule.onTick(tickMs)
    │     │
    │     ├── SystemEventModule.onTick(tickMs)
    │     │   ├── 检查 dynamicTick → Poke("动态报点唤醒...")
    │     │   ├── 检查 reminders[] → Poke("⏰ 提醒...")
    │     │   ├── 检查 nextAutoTick → Poke("系统周期报点...")
    │     │   └── 检查自动化调度 → SystemBus.emit(OnScheduleTriggered)  ← v2.1 事件解耦
    │     │
    │     └── UsageStatsModule.onTick(tickMs)
    │
    ├── Layer 2: AutomationModule 订阅 SystemBus.onScheduleTriggered
    │     → 创建 AutomationWorker (WorkManager) 执行重量任务
    │     → WorkManager 自带: 网络约束 / 失败重试(BackoffPolicy) / 自动恢复
    │
    └── 用户消息到达 → SystemEventModule 重置退避
```

### 5.3 自动化执行全流程（v2.1 增强容错）

```
  用户手动触发 or SystemBus.onScheduleTriggered
    │
    ▼
  AutomationModule.executeNow(automationId)
    │
    ├── 1. ExecutionGuard 检查 (v2.1: maxReplanCount=2, maxSteps=10, maxTokens=50K, maxDuration=5min)
    │     ├── 是否已有同名任务在运行？ → 跳过
    │     ├── 本月累计 Token 是否超限？ → 拒绝
    │     └── 是否有可用网络？→ 无网络则推迟到 WorkManager
    │
    ├── 2. 创建 AutomationRunEntity(status = RUNNING)
    │
    ├── 3. LLM 生成 Plan (v2.1: JSON 分层提取 + 降级规则引擎)
    │     │
    │     │  Plan 结构:
    │     │  {
    │     │    title: "每日AI新闻",
    │     │    steps: [
    │     │      {id: "search_1", action: "web_search", mode: "parallel", ...},
    │     │      {id: "search_2", action: "web_search", mode: "parallel", ...},
    │     │      {id: "synthesize", action: "llm_synthesize", ...}
    │     │    ]
    │     │  }
    │     │
    │     │  JSON 提取策略 (v2.1):
    │     │    1. 直接解析 (rawText 以 { 开头)
    │     │    2. 移除 Markdown ```json ``` 包装
    │     │    3. 正则提取最外层 { ... }
    │     │    4. 重试一次 (prompt 追加 "Return ONLY valid JSON")
    │     │    5. 降级 RuleBasedTaskParser
    │     │
    │     └── parsePlanJson() 失败 → RuleBasedTaskParser 生成默认 Plan
    │
    ├── 4. 执行步骤 (Plan-Execute 循环, v2.1: maxReplanCount=2):
    │     │
    │     for (step in plan.steps) {
    │       ├── guard.checkTimeout()      ← 超时检查
    │       ├── guard.checkTokens(used)   ← Token 检查 (留底 5000)
    │       │
    │       ├── Poke("🔧 [${index+1}/${total}] ${step.description}")
    │       │
    │       ├── executeStep(step)
    │       │   ├── "web_search" → FunctionRegistry.execute("web_search", params)
    │       │   ├── "llm_synthesize" → llmClient.chatSync(prompt)
    │       │   ├── "parallel" → coroutineScope { subSteps.map { async { executeStep(it) } }.awaitAll() }
    │       │   └── step.allowFailure=false && failed → throw StepFailedException
    │       │
    │       ├── Poke("✅ 完成: ${step.id}")
    │       │
    │       └── if (result.suggestsMoreSteps
    │              && guard.canReplan()              ← v2.1: 最多重规划 2 次
    │              && guard.remainingTokens() > 5000) ← v2.1: Token 留底
    │           → generatePlan(已有结果) → 追加步骤
    │     }
    │
    ├── 5. LLM 合成最终摘要 (≤500字)
    │
    ├── 6. 持久化 + 通知:
    │     ├── AutomationRunEntity → COMPLETED
    │     ├── ConversationEntity + MessageEntity
    │     └── notifyOnComplete → push_notification
    │
    └── 7. 失败处理:
          ├── 可重试错误 → WorkManager BackoffPolicy 自动重试
          ├── 不可重试错误 → 标记 FAILED + 记录 errorMessage
          └── 一次性任务 → 失败也标记完成（避免无限重试）
```

### 4.4 模块配置加载流程

```
  ModuleLifecycleManager.initializeAll()
    │
    for (module in ordered) {
      ├── 1. 检查是否需要加载配置
      │     └── module 有 config 属性？
      │
      ├── 2. 从配置存储读取
      │     ├── ModuleConfigDao.getByModuleId(module.metadata.id)
      │     ├── 有 → module.config.fromJson(record.configJson)
      │     └── 无 → 使用默认配置
      │
      ├── 3. module.onAwake()
      │     └── 模块在此注册 FC 函数、初始化内部状态
      │
      └── 4. module.onStart()
            └── 模块在此订阅 ChatBus 事件、启动定时器
    }
```

### 4.5 FC 函数注册与执行流程

```
  模块 onAwake():
    │
    module.registerFunctions(registry)
      │
      ├── registry.register(
      │     FunctionDef(name="web_search", params=[...], description="..."),
      │     handler = { args -> module.search(args) }
      │   )
      │
      └── 注册后，FunctionRegistry 维护:
            Map<functionName, FunctionEntry(definition, handler, sourceModule)>
  ────────────────────────────────
  LLM 调用时:
    │
    ChatBotModule 收到 tool_calls:
      │
      ├── 解析 ToolCallDelta → ToolCall(name="web_search", arguments="{\"query\":\"AI\"}")
      │
      ├── ChatBus.emit(onToolCallStart)
      │
      ├── FunctionRegistryModule.execute("web_search", {"query": "AI"})
      │   ├── 查找 handler
      │   ├── 执行 handler(args)
      │   ├── 记录 ModuleCallLogEntity
      │   └── 返回 FunctionResult(data, structuredData)
      │
      ├── ChatBus.emit(onToolCallResult)
      │
      └── continueAfterToolCall() → LLM 生成最终回复
```

---

## 五、自动化模块深度设计

### 5.1 核心问题：自动化能否达到预期？

**可以。** 但必须从当前的"固定管道"模式升级为 **"LLM 驱动的 Plan-Execute 循环"**。

当前 v1 的问题：
```
SEARCH_AND_ANSWER: 搜索 → 提取 → 合成（硬编码 3 步）
KNOWLEDGE_UPDATE:   搜索 → 提取 → 入库（硬编码 3 步）
DAILY_BRIEF:        搜索 → 摘要 → 通知（硬编码 3 步）
```

v2 的目标：
```
用户: "每天早上搜索AI新闻和芯片新闻，对比两家观点，总结5条交叉验证的要点"
  │
  ▼
LLM 生成 Plan:
  steps:
    1. [并行] search("AI 新闻最新进展")    ─┐
    2. [并行] search("芯片行业最新动态")    ─┤ 同时执行
                                           │
    3. extractContent(step1.topUrls)       ─┤ 抓取内容
    4. extractContent(step2.topUrls)       ─┘
    
    5. llm_synthesize("对比AI和芯片领域的交叉趋势，输出5条交叉验证要点")
    6. push_notification("今日简报已生成")
```

### 5.2 AutomationModule 详细设计

```kotlin
/**
 * 自动化模块 v2 — LLM 驱动的 Plan-Execute 引擎。
 *
 * 对标 Claude Code 的 Plan-Execute 循环 + OpenCode 的 pipeline/parallel 编排。
 *
 * 执行模式：
 * - SCHEDULED:  定时触发（AlarmManager + 心跳检查）
 * - MANUAL:     用户手动触发
 * - CONDITIONAL: 条件触发（如"充电时"、"连接WiFi时"）— Phase 9+
 */
class AutomationModule : ZeroModule<AutomationConfig>() {

    // ═══════════════════════════════════════════════
    // FC 函数（LLM 可调用的自动化管理接口）
    // ═══════════════════════════════════════════════

    override fun registerFunctions(registry: FunctionRegistry) {
        // 1. 创建任务
        registry.register(FunctionDef(
            name = "create_automation",
            description = "创建自动化任务。用自然语言描述需要定期做什么。",
            params = [
                Param("description", "string", "自然语言描述，如'每天早上9点搜索AI新闻，整理5条要点'"),
                Param("schedule", "string", "执行时间: 自然语言 / cron / ISO-8601"),
                Param("is_one_shot", "boolean", "是否一次性任务")
            ]
        )) { args -> createAutomation(args) }

        // 2. 列出任务
        registry.register(FunctionDef(
            name = "list_automations", description = "列出所有自动化任务。"
        )) { listAutomations() }

        // 3. 暂停/恢复/删除
        registry.register(FunctionDef(
            name = "manage_automation",
            description = "管理自动化任务：暂停、恢复、删除。",
            params = [
                Param("task_id", "string", "任务ID"),
                Param("action", "string", "pause/resume/delete")
            ]
        )) { args -> manageAutomation(args) }

        // 4. 立即执行
        registry.register(FunctionDef(
            name = "run_automation_now",
            description = "立即执行指定的自动化任务。",
            params = [Param("task_id", "string", "任务ID")]
        )) { args -> executeNow(args["task_id"] as? String ?: "") }

        // 5. 查看结果
        registry.register(FunctionDef(
            name = "get_automation_results",
            description = "查询自动化任务的执行历史和结果。",
            params = [
                Param("task_id", "string", "任务ID"),
                Param("limit", "number", "返回条数，默认 5")
            ]
        )) { args -> getResults(args) }
    }

    // ═══════════════════════════════════════════════
    // Plan-Execute 引擎
    // ═══════════════════════════════════════════════

    /**
     * 执行自动化任务。
     *
     * 对标 Claude Code 的 Plan-Execute 循环：
     * 1. 解析任务 → 生成 Plan（LLM 自主分解步骤）
     * 2. 逐个执行步骤（支持并行步骤）
     * 3. 步骤间可依据结果动态调整后续步骤
     * 4. 最终合成 + 持久化
     */
    suspend fun executeNow(automationId: String): ExecutionResult {
        val task = automationDao.getById(automationId) ?: return ExecutionResult.NotFound

        // ── 守护检查 ──
        val guard = ExecutionGuard(
            maxSteps = config.maxSteps,
            maxTokensPerRun = config.maxTokensPerRun,
            maxDurationMs = config.maxDurationMinutes * 60_000L,
            requireNetwork = config.requireNetwork,
            allowedTimeRange = config.allowedTimeRange
        )
        if (!guard.canExecute(task)) return ExecutionResult.GuardBlocked(guard.reason)

        // ── 创建运行记录 ──
        val run = createRunRecord(task)
        var totalTokens = 0L

        try {
            // ── 步骤 1: LLM 生成执行计划 ──
            emitProgress(run, "📋 正在规划执行步骤...")
            val plan = generatePlan(task)
            guard.enforceStepLimit(plan.steps.size)

            // ── 步骤 2: 执行计划 ──
            val results = mutableListOf<StepResult>()
            for ((index, step) in plan.steps.withIndex()) {
                guard.checkTimeout()       // 超时检查
                guard.checkTokens(totalTokens) // Token 检查

                emitProgress(run, "🔧 [${index+1}/${plan.steps.size}] ${step.description}")

                val result = when (step.executionMode) {
                    StepMode.SEQUENTIAL -> executeStep(step)
                    StepMode.PARALLEL   -> executeParallelSteps(step.subSteps)
                }

                results.add(result)
                totalTokens += result.tokensUsed

                if (result.failed && !step.allowFailure) {
                    throw StepFailedException(step.id, result.error)
                }

                // 动态调整：如果结果提示需要额外步骤
                if (result.suggestsMoreSteps && index == plan.steps.lastIndex) {
                    // LLM 基于当前结果重新规划下一步
                }
            }

            // ── 步骤 3: LLM 合成最终结果 ──
            emitProgress(run, "📊 正在合成结果...")
            val summary = synthesizeResults(task, results)
            totalTokens += summary.tokensUsed

            // ── 步骤 4: 持久化 ──
            emitProgress(run, "💾 正在保存...")
            persistResult(task, run, summary, results)

            // ── 步骤 5: 通知 ──
            if (task.notifyOnComplete) {
                sendNotification(task, summary)
            }

            return ExecutionResult.Success(run.id, summary.text, totalTokens)

        } catch (e: Exception) {
            handleFailure(run, e)
            return ExecutionResult.Failure(run.id, e.message ?: "未知错误")
        }
    }

    /**
     * LLM 生成执行计划。
     *
     * 对标 Claude Code 的 Planning 阶段 — LLM 将自然语言描述
     * 分解为具体的可执行步骤。
     */
    private suspend fun generatePlan(task: AutomationEntity): ExecutionPlan {
        val prompt = """
将以下任务分解为可执行的具体步骤。每个步骤应为：
- "web_search": 搜索互联网
- "llm_synthesize": LLM 合成/分析
- "push_notification": 推送通知
- "read_notifications": 读取通知
- "get_screen_time": 获取使用量

任务: ${task.description}
执行时间: ${task.scheduleDescription}

返回 JSON:
{
  "steps": [
    {
      "id": "唯一ID",
      "description": "步骤描述",
      "action": "web_search|llm_synthesize|...",
      "params": {"具体参数"},
      "executionMode": "sequential|parallel",
      "subSteps": [...],    // executionMode=parallel 时
      "allowFailure": false
    }
  ]
}
        """.trimIndent()

        val response = llmClient.chatSync(prompt)
        return parsePlanJson(response)
    }

    /**
     * 执行单个步骤。
     *
     * 步骤类型映射到已注册的 FC 函数：
     *   web_search → FunctionRegistry.execute("web_search", params)
     *   llm_synthesize → LLMClientModule.chatSync(prompt)
     */
    private suspend fun executeStep(step: PlanStep): StepResult {
        return when (step.action) {
            "web_search" -> {
                val result = functionRegistry.execute("web_search", step.params)
                StepResult.fromFunctionResult(step.id, result)
            }
            "llm_synthesize" -> {
                val text = llmClient.chatSync(step.params["prompt"] as? String ?: "")
                StepResult.fromLLM(step.id, text)
            }
            "push_notification" -> {
                val result = functionRegistry.execute("push_notification", step.params)
                StepResult.fromFunctionResult(step.id, result)
            }
            else -> StepResult.failed(step.id, "未知步骤类型: ${step.action}")
        }
    }

    /**
     * 并行执行子步骤（对标 OpenCode pipeline/parallel 模式）。
     *
     * Alife 的 Reasonix 通过 Claude Code CLI 的 task 工具实现并行。
     * 我们通过 Kotlin 协程 async/await 实现。
     */
    private suspend fun executeParallelSteps(subSteps: List<PlanStep>): StepResult {
        val results = subSteps.map { step ->
            coroutineScope { async { executeStep(step) } }
        }.awaitAll()
        return StepResult.merged(results)
    }

    /**
     * LLM 合成所有步骤结果为最终摘要。
     */
    private suspend fun synthesizeResults(
        task: AutomationEntity,
        results: List<StepResult>
    ): SynthesizeResult {
        val prompt = """
基于以下自动化任务的执行结果，生成一份简洁的摘要（≤500字）。

任务: ${task.title}
${task.description}

执行结果:
${results.joinToString("\n---\n") { "${it.stepId}: ${it.summary}" }}

生成摘要，包含：
1. 任务完成情况
2. 关键发现
3. 建议的后续动作
        """.trimIndent()

        val text = llmClient.chatSync(prompt)
        return SynthesizeResult(text, estimateTokens(prompt + text))
    }
}

/**
 * 执行守护 — 对标 Claude Code 的权限/成本限制。
 *
 * v2.1 增强：
 * - maxReplanCount: 最大重规划次数（默认 2），防止 LLM 无限建议新步骤
 * - remainingTokens(): Token 预算剩余查询
 * - checkTokens() 改为每次步骤后检查
 */
class ExecutionGuard(
    val maxSteps: Int = 10,
    val maxReplanCount: Int = 2,            // v2.1: 重规划上限
    val maxTokensPerRun: Long = 50_000,
    val maxDurationMs: Long = 300_000,
    val requireNetwork: Boolean = true,
    val allowedTimeRange: Pair<Int, Int>? = null
) {
    private var stepsExecuted = 0
    private var replanCount = 0
    private var startTime = 0L
    private var tokensUsed = 0L

    fun canExecute(task: AutomationEntity): Boolean { ... }
    fun enforceStepLimit(plannedSteps: Int) { ... }
    fun checkTimeout() { ... }
    fun checkTokens(used: Long) { tokensUsed = used }
    fun remainingTokens(): Long = maxTokensPerRun - tokensUsed

    fun canReplan(): Boolean {
        replanCount++
        return replanCount <= maxReplanCount
    }
}

/**
 * v2.1: JSON 健壮提取器。
 *
 * LLM 返回的 JSON 可能有 Markdown 包装、解释性前缀、尾部注释。
 * 分层提取策略确保容错。
 */
object JsonExtractor {
    private val codeBlockPattern = Regex("```(?:json)?\\n?([\\s\\S]*?)\\n?```")
    private val bracesPattern = Regex("\\{(?:[^{}]|\\{[^{}]*\\})*\\}")

    fun extract(rawText: String): String? {
        // 1. 直接解析
        if (rawText.trimStart().startsWith("{")) return rawText.trim()

        // 2. 移除 Markdown 代码块
        codeBlockPattern.find(rawText)?.groupValues?.get(1)?.trim()?.let { return it }

        // 3. 正则提取最外层 {}
        bracesPattern.find(rawText)?.value?.let { return it }

        // 4. 无法提取
        return null
    }
}
```

### 5.4 与 Claude Code / OpenCode 的对照

| 特性 | Claude Code | OpenCode | Zero v2 AutomationModule |
|------|-----------|----------|-------------------------|
| 任务分解 | LLM 自主 | Workflow 脚本 | `generatePlan()` LLM 分解 |
| 并行执行 | `task` 工具 | `parallel()` | `executeParallelSteps()` coroutine async |
| 进度推送 | `stream-json` | agent 通知 | `PokeEvent` → 对话流 |
| 成本守卫 | `--max-turns` + $5 硬上限 | `budget.remaining()` | `ExecutionGuard` |
| 权限控制 | `--permission-mode` | hook | `allowedTimeRange` + 步骤上限 |
| 结果持久化 | `--output-format stream-json` | agent 返回 | ConversationEntity + MessageEntity |
| 失败恢复 | resume session | resumeFromRunId | 检测 isOneShot 标记 + 重试逻辑 |

---

## 六、修订后的开发阶段

| Phase | 内容 | 新增文件 | 行数 | 依赖 |
|-------|------|---------|------|------|
| **P1** | 项目骨架 + IZeroModule + ChatBus + MessagePipeline | 15 | ~800 | — |
| **P2** | ChatBotModule + MessageFilterModule + LLMClient + ContextBuilder | 6 | ~700 | P1 |
| **P3** | FunctionRegistryModule + FC 注册/分发/调用日志 | 4 | ~500 | P1 |
| **P4** | MemoryModule + MemoryManager（从 v1 移植） | 3 | ~500 | P1, P3 |
| **P5** | WebSearchModule + VisionModule + ContentExtractor | 5 | ~600 | P3 |
| **P6** | SystemEventModule（心跳+提醒+记事本）+ BackgroundService | 6 | ~1300 | P3 |
| **P7** | AutomationModule（Plan-Execute + ExecutionGuard） | 3 | ~500 | P3, P5, P6 |
| **P8** | UI 层（屏幕 + ViewModel + AutoForm + 导航） | 12 | ~1200 | P2-P7 |
| **P9** | NotificationModule + UsageStatsModule + CalendarModule | 5 | ~500 | P8 |
| **P10** | 打磨（电池优化、权限引导、崩溃恢复、QuickJS 预留） | 3 | ~300 | P1-P9 |

**总估**：62 文件，约 6900 行

---

## 七、模块函数清单（完整版）

### Core 模块

| 模块 | 公开函数 | 类型 | 说明 |
|------|---------|------|------|
| **ChatBotModule** | `sendMessage()` | API | 对话主入口 |
| | `regenerate()` | API | 重新生成 |
| | `clearConversation()` | API | 新建对话 |
| | `setSystemPrompt()` | API | 切换角色 |
| **MessageFilterModule** | `process()` | Filter | 时间注入 + 截断 + 提示重注入 |
| **MemoryModule** | `memory_remember` | FC | 记住信息（v2.1: Mutex 保护写入） |
| | `memory_recall` | FC | 搜索记忆 (v2.2: FTS5+Vector+RRF 混合检索) |
| | `memory_forget` | FC | 删除记忆 |
| | `semanticSearch()` | API | 混合检索（内部） |
| | `hybridSearch()` | API | v2.2: FTS5 BM25 + Vector + RRF 融合 |
| | `intelligentMemorize()` | API | 智能记忆化（v2.1: withWriteLock） |
| **FunctionRegistryModule** | `getAllDefinitions()` | API | 生成 LLM tools 数组 |
| | `execute()` | API | 执行 FC 函数 (v2.2: 无依赖并行) |
| | `executeParallel()` | API | v2.2: 并发执行多个独立函数 |
| | `register()` | API | 注册函数（含 securityLevel） |
| **ContextBuilderModule** | `buildSystemPrompt()` | API | 组装完整 System Prompt (v2.2: TokenBudgetAllocator) |
| | `buildContextExtension()` | API | 组装本轮上下文 |
| | `allocateBudget()` | API | v2.2: Token 配额分配 + 超预算自动压缩 |
| **LLMClientModule** | `chatStream()` | API | 流式 LLM 调用 |
| | `chatSync()` | API | 同步 LLM 调用（规划/总结用） |

### Instrument 模块

| 模块 | 公开函数 | 类型 | 说明 |
|------|---------|------|------|
| **WebSearchModule** | `web_search` | FC | 搜索 + 深度抓取 |
| | `search()` | API | 搜索（内部） |
| **VisionModule** | `vision_analyze` | FC | 视觉分析 |
| | `analyzeImagePoke()` | API | Poke 快速路径 |
| **ContentExtractor** | `extract()` | API | URL → Readability 提纯 → Markdown (v2.2) |
| | `extractBatch()` | API | 批量抓取 (Timeout 5s, MaxSize 2MB) |
| | `fallbackSearch()` | API | v2.2: 降级管道 |

### System 模块

| 模块 | 公开函数 | 类型 | 说明 |
|------|---------|------|------|
| **SystemEventModule** | `set_reminder` | FC | 设置提醒（+5m/+1h/+30s） |
| | `list_reminders` | FC | 列出提醒 |
| | `cancel_reminder` | FC | 取消提醒 |
| | `next_tick` | FC | 动态报点（3-300s） |
| | `read_notepad` | FC | 读记事本 |
| | `write_notepad` | FC | 写记事本 |
| | `clear_notepad` | FC | 清空记事本 |
| | `archive_notepad` | FC | 归档记事本 |
| | `onTick()` | ITimedModule | 心跳检查（v2.1: 发布 OnScheduleTriggered 事件，不直接调用 ATM） |
| **NotificationModule** | `read_notifications` | FC | 读取通知 |
| | `push_notification` | FC | 推送通知 |
| **UsageStatsModule** | `get_screen_time` | FC | 使用时长 |
| | `get_app_usage` | FC | 应用使用详情 |
| **BackgroundServiceMod** | `startHeartbeat()` | API | 启动心跳（轻量检查 ≤5s） |
| | `stopHeartbeat()` | API | 停止心跳 |
| | `enqueueAutomation()` | API | v2.1: 将自动化任务入队 WorkManager |
| **AutomationWorker** | `doWork()` | Worker | v2.1: WorkManager CoroutineWorker 执行重量任务 |

### Automation 模块

| 模块 | 公开函数 | 类型 | 说明 |
|------|---------|------|------|
| **AutomationModule** | `create_automation` | FC | 创建任务 |
| | `list_automations` | FC | 列出任务 |
| | `manage_automation` | FC | 管理（暂停/恢复/删除） |
| | `run_automation_now` | FC | 立即执行 |
| | `get_automation_results` | FC | 查询执行结果 |
| | `executeNow()` | API | 执行引擎入口 |
| | `generatePlan()` | API | LLM 分解步骤 |
| | `executeStep()` | API | 执行单步 |
| | `executeParallelSteps()` | API | 并行执行 |
| | `synthesizeResults()` | API | LLM 合成摘要 |

**总计**：14 个模块，32 个 FC 函数，20 个内部 API 函数

---

## 八、v2.1 安全增强详解

### 8.1 Memory 写入互斥锁

防止后台自动化与前台对话并发写入 Room 触发 `SQLite database is locked`：

```kotlin
class MemoryModule : ZeroModule<MemoryConfig>() {
    private val writeMutex = Mutex()

    private suspend fun <T> withWriteLock(block: suspend () -> T): T {
        return writeMutex.withLock { block() }
    }

    // onChatFinished 触发（来自对话线程）
    private suspend fun intelligentMemorize(userMsg: String, aiMsg: String) {
        withWriteLock {
            if (shouldMemorize(userMsg))
                memoryManager.memorize("用户: $userMsg", ...)
            if (shouldMemorize(aiMsg))
                memoryManager.memorize("AI: ${aiMsg.take(500)}", ...)
        }
    }

    // FC 函数（可能来自自动化 WorkManager 线程）
    private suspend fun remember(args: Map<String, Any?>): FunctionResult {
        return withWriteLock {
            memoryManager.memorize(args["content"] as String, ...)
            FunctionResult.success("已记住")
        }
    }
}

// MemoryDao 事务保护
@Dao
interface MemoryDao {
    @Transaction
    suspend fun insertWithTx(memory: MemoryEntity) {
        insert(memory)
        if (getByLevel(0).size >= L0_THRESHOLD)
            compressLevel(0)
    }
}
```

### 8.2 AutomationWorker — WorkManager 重量执行

```kotlin
@HiltWorker
class AutomationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val automationModule: AutomationModule,
    private val logManager: LogManager
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val automationId = inputData.getString(KEY_AUTOMATION_ID) ?: return Result.failure()
        logManager.info("AutomationWorker", "开始执行: $automationId")

        return try {
            val result = automationModule.executeNow(automationId)
            when (result) {
                is ExecutionResult.Success -> Result.success()
                is ExecutionResult.Failure -> if (result.retryable) Result.retry() else Result.failure()
            }
        } catch (e: Exception) {
            logManager.error("AutomationWorker", "执行异常: $automationId", e)
            Result.retry()  // WorkManager BackoffPolicy 自动控制重试间隔
        }
    }

    companion object {
        const val KEY_AUTOMATION_ID = "automation_id"

        fun enqueue(context: Context, automationId: String, delayMs: Long = 0L) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<AutomationWorker>()
                .setInputData(Data.Builder().putString(KEY_AUTOMATION_ID, automationId).build())
                .setConstraints(constraints)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "automation_$automationId",
                    ExistingWorkPolicy.REPLACE,
                    request
                )
        }
    }
}
```

### 8.3 后台执行三层分层总结

| 层级 | 机制 | 职责 | 执行内容 |
|------|------|------|---------|
| Layer 1 | 前台 Service 心跳 | 轻量检查（≤5s，withTimeout 保护） | 检查提醒、检查调度时间、发布 OnScheduleTriggered 事件 |
| Layer 2 | WorkManager CoroutineWorker | 重量执行 | executeNow()：LLM 调用、网络搜索、内容抓取 |
| Layer 3 | AlarmManager.setExactAndAllowWhileIdle | 系统级唤醒 | 15 分钟兜底唤醒，检查逾期任务并启动 WorkManager |

**安全约束**：
- Layer 1 永不直接调用网络/LLM/DB 写入
- Layer 2 自带网络约束 + 失败重试 + 系统 kill 后自动恢复
- Layer 3 不受 Doze 模式影响，确保长时间间隔任务不丢失

---

## 九、v2.2 生产级增强详解

### 9.1 Token Budget Allocator（P0 — ContextBuilder 集成）

**问题**：System Prompt、角色人设、记忆、事实、多轮对话历史无限制累积，超出 Context Window 或耗尽 API 预算。

**方案**：在 `ContextBuilderModule` 中内建 `TokenBudgetAllocator`。

```kotlin
class TokenBudgetAllocator(
    val contextWindow: Int = 1_048_576,  // qwen3.8-max (1M)
) {
    data class Budget(
        val systemAndPersona: Int,   // 15% = 38,400 tokens
        val toolsDefinition: Int,    // 15% = 38,400 tokens
        val ragContext: Int,         // 20% = 51,200 tokens
        val conversationHistory: Int // 50% = 128,000 tokens
    )

    fun allocate(contextWindow: Int): Budget = Budget(
        systemAndPersona  = (contextWindow * 0.15).toInt(),
        toolsDefinition   = (contextWindow * 0.15).toInt(),
        ragContext        = (contextWindow * 0.20).toInt(),
        conversationHistory = (contextWindow * 0.50).toInt()
    )

    /**
     * 实时 Token 计数（基于 DashScope API 返回的 usage.prompt_tokens 校准）。
     * 当历史对话超过 budget.conversationHistory 的 90% 时，触发压缩。
     */
    fun needsCompression(currentHistoryTokens: Int, budget: Budget): Boolean {
        return currentHistoryTokens > budget.conversationHistory * 0.9
    }
}
```

**ContextBuilder 组装逻辑**：

```kotlin
fun buildSystemPrompt(persona: String, tools: List<FunctionDefinition>,
                      rag: String?, history: List<ChatMessage>): String {
    val budget = allocator.allocate(model.contextWindow)

    // 1. System Prompt + Persona（裁切到预算）
    val systemBlock = (persona).takeApproxTokens(budget.systemAndPersona)

    // 2. Tools 定义（超过预算时只保留核心工具描述）
    val toolsBlock = if (estimateTokens(tools) > budget.toolsDefinition) {
        tools.map { "- ${it.name}: ${it.description.take(80)}" }  // 精简版
    } else {
        tools.map { "- ${it.name}: ${it.description}" }
    }.joinToString("\n")

    // 3. RAG 上下文（截断到预算）
    val ragBlock = rag?.takeApproxTokens(budget.ragContext)

    // 4. 对话历史（超过 90% 预算 → 压缩最旧的 N 轮为摘要）
    val historyTokens = estimateTokens(history)
    val historyBlock = if (allocator.needsCompression(historyTokens, budget)) {
        compressOldestTurns(history, budget.conversationHistory)
    } else {
        history
    }

    return buildString { ... }
}

// 滑动窗口 + 摘要压缩
private fun compressOldestTurns(history: List<ChatMessage>, budget: Int): String {
    // 保留最近 10 轮完整对话
    val recent = history.takeLast(10)
    val old = history.dropLast(10)
    // 对旧对话调用 LLM 压缩为摘要段落
    val summary = historyCompressor.compress(old, maxTokens = budget / 2)
    return "[历史对话摘要]\n$summary\n\n---\n${recent.formatMessages()}"
}
```

**Token 实时校准**：每次 API 返回 `usage.prompt_tokens` 时更新计数器。当 `finish_reason == "length"` 时，标记"上下文超限"，下一轮自动触发历史压缩。

---

### 9.2 并发 Function Calling（P1 — FunctionRegistry 集成）

**问题**：LLM 返回 3 个 `web_search` 调用，串行执行耗时 3T。

**方案**：同批次 ToolCall 默认并行，显式依赖通过 `depends_on` 声明。

```kotlin
// FunctionRegistryModule
suspend fun executeBatch(toolCalls: List<ToolCall>): List<FunctionResult> {
    // 分离独立调用和有依赖的调用
    val (independent, dependent) = toolCalls.partition { it.dependsOn.isEmpty() }

    // 所有独立调用并行执行
    val independentResults = coroutineScope {
        independent.map { tc ->
            async { execute(tc.name, tc.arguments) }
        }.awaitAll()
    }

    // 有依赖的调用在依赖完成后串行执行
    val dependentResults = dependent.map { tc ->
        execute(tc.name, tc.arguments)
    }

    return independentResults + dependentResults
}
```

**依赖声明**：

```kotlin
// FunctionDefinition 扩展
data class FunctionDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, Any?>,
    val dependsOn: List<String> = emptyList(),  // v2.2: 显式依赖声明
    val securityLevel: FunctionSecurity = FunctionSecurity.SAFE
)
```

**AI 使用示例**（LLM 理解依赖关系后自动排序）：
```
# 独立调用3个搜索 → 并行
tool_calls: [
  {name: "web_search", arguments: {query: "AI新闻"}},      // 无依赖
  {name: "web_search", arguments: {query: "芯片进展"}},      // 无依赖
  {name: "web_search", arguments: {query: "机器人突破"}}     // 无依赖
]
→ 3个并行 async → 耗时 max(T1,T2,T3) ≈ T

# 有依赖的调用 → 串行
tool_calls: [
  {name: "web_search", arguments: {query: "PDF工具"}},
  {name: "llm_synthesize", dependsOn: ["web_search"], arguments: {...}}
]
→ 先执行 search → 完成 → 再执行 synthesize
```

---

### 9.3 沙箱安全拦截器（P1 — FunctionRegistry 集成）

**问题**：LLM 幻觉可能触发 `clear_notepad`、`push_notification` 等高危操作。

**方案**：FC 函数分为 SAFE / SENSITIVE，SENSITIVE 操作弹窗确认。

```kotlin
enum class FunctionSecurity {
    SAFE,       // 只读: web_search, memory_recall, read_notepad, get_screen_time, vision_analyze
    SENSITIVE   // 写入/推送: memory_remember, memory_forget, write_notepad, clear_notepad,
                //   archive_notepad, push_notification, set_reminder, cancel_reminder,
                //   cancel_all_reminders, execute_task, create_automation
}

// FunctionRegistry 注册时声明安全级别
registry.register(
    FunctionDef(
        name = "clear_notepad",
        securityLevel = FunctionSecurity.SENSITIVE,
        ...
    ),
    handler = { ... }
)

// 执行 SENSITIVE 时的确认流程
suspend fun executeSecure(name: String, args: Map<String, Any?>): FunctionResult {
    val def = getDefinition(name)

    if (def.securityLevel == FunctionSecurity.SENSITIVE) {
        // 发出确认请求 → UI 弹窗
        val event = SecurityConfirmRequest(
            functionName = name,
            description = "AI 请求执行: ${def.description}",
            params = args
        )
        chatBus.onSecurityConfirmRequest.emit(event)

        // 等待用户响应（超时 30s → 自动拒绝）
        val response = withTimeoutOrNull(30_000L) {
            securityConfirmFlow.first { it.requestId == event.requestId }
        } ?: return FunctionResult.rejected("用户未响应，操作已自动拒绝")

        if (!response.approved) {
            return FunctionResult.rejected("用户拒绝了此操作")
        }
    }

    return execute(name, args)
}
```

**安全分配矩阵**：

| 函数 | 安全级别 | 确认条件 |
|------|---------|---------|
| `web_search` | SAFE | 无 |
| `memory_recall` | SAFE | 无 |
| `read_notepad` | SAFE | 无 |
| `get_screen_time` | SAFE | 无 |
| `vision_analyze` | SAFE | 无 |
| **`memory_remember`** | **SENSITIVE** | 弹窗确认 |
| **`write_notepad`** | **SENSITIVE** | 弹窗确认 |
| **`clear_notepad`** | **SENSITIVE** | 弹窗确认 + 红色警告 |
| **`push_notification`** | **SENSITIVE** | 弹窗确认 |
| **`set_reminder`** | **SENSITIVE** | 弹窗确认 |
| **`create_automation`** | **SENSITIVE** | 弹窗确认 |

---

### 9.4 PriorityQueue 唤醒任务队列（P1 — SystemEventModule）

**问题**：`List<ReminderEntry>` 每次 `onTick()` 遍历全部，O(n)。

**方案**：`PriorityQueue<SystemTickTask>` + 三重唤醒源。

```kotlin
class SystemEventModule : ZeroModule<SystemEventConfig>(), ITimedModule {
    // v2.2: PriorityQueue 替代 List
    private val taskQueue = PriorityQueue<SystemTickTask>(
        compareBy { it.triggerTimeMs }
    )

    override suspend fun onTick(tickMs: Long) {
        // O(log n) 取出 + O(1) 检查队首
        while (taskQueue.isNotEmpty() && taskQueue.peek().triggerTimeMs <= tickMs) {
            val task = taskQueue.poll()
            executeTask(task)  // 执行后可能重新入队（循环任务）
        }
    }

    private fun executeTask(task: SystemTickTask) {
        when (task.type) {
            TaskType.REMINDER -> {
                poke("⏰ 提醒 [${task.id}]: ${task.remark}")
                if (task.isRecurring) {
                    task.triggerTimeMs = now() + task.recurringMs
                    taskQueue.offer(task)  // 循环任务重新入队
                }
            }
            TaskType.DYNAMIC_TICK -> {
                val notepad = readNotepad()
                poke("动态报点唤醒。\n$notepad\n${config.updatePrompt}")
                continuousTimerCount = 0
                nextAutoTick = Long.MAX_VALUE
            }
            TaskType.AUTO_TICK -> {
                poke("系统周期报点。${config.updatePrompt}")
                continuousTimerCount++
                scheduleNextAutoTick()
            }
            TaskType.AUTOMATION -> {
                // 发布事件而非直接调用
                systemBus.emit(OnScheduleTriggered(task.automationId!!, HEARTBEAT))
            }
        }
    }
}
```

**三重唤醒源**：

```kotlin
fun scheduleNextWakeup() {
    val nextTask = taskQueue.peek() ?: return

    // 唤醒源 1: AlarmManager 精确闹钟（毫秒级）
    val alarmManager = context.getSystemService(ALARM_SERVICE) as AlarmManager
    alarmManager.setExactAndAllowWhileIdle(
        AlarmManager.RTC_WAKEUP,
        nextTask.triggerTimeMs,
        wakeupPendingIntent
    )

    // 唤醒源 2: WorkManager PeriodicWork（15分钟兜底）
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "heartbeat_keepalive",
        ExistingPeriodicWorkPolicy.KEEP,
        PeriodicWorkRequestBuilder<HeartbeatWorker>(15, TimeUnit.MINUTES).build()
    )

    // 唤醒源 3: 前台 Service 心跳（前台锁定时的秒级响应）
}
```

**Notepad WAL 持久化**：

```kotlin
// 对标 Alife 的 Markdown 文件存储 → 升级为 Room WAL 表
@Entity(tableName = "task_notepad")
data class NotepadEntity(
    @PrimaryKey val id: Int = 1,  // 单例
    val content: String,
    val updatedAt: Long
)

@Dao
interface NotepadDao {
    @Query("SELECT * FROM task_notepad WHERE id = 1")
    suspend fun get(): NotepadEntity?

    @Transaction  // 原子写入
    @Insert(onConflict = REPLACE)
    suspend fun save(entry: NotepadEntity)
}
```

---

### 9.5 ContentExtractor — Readability 提纯（P1）

**问题**：普通 Jsoup 提取保留大量噪声（导航栏、页脚、广告）。

**方案**：基于文本密度 + 标签层级剪枝的 Readability 算法。

```kotlin
class ContentExtractor {
    companion object {
        const val TIMEOUT_MS = 5_000L
        const val MAX_HTML_SIZE = 2_000_000L  // 2MB
        const val MAX_REDIRECTS = 3
        const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Mobile"
    }

    suspend fun extract(url: String, maxChars: Int = 8000): ExtractedContent {
        // Step 1: 获取 HTML（超时 + 大小限制 + 重定向限制）
        val html = fetchWithGuard(url)

        // Step 2: Jsoup 解析
        val doc = Jsoup.parse(html)

        // Step 3: Readability 提纯 — 文本密度算法
        //   - 移除 script, style, nav, footer, aside, iframe, [role=ad]
        //   - 计算每个 <article>/<main>/<section>/<div>/<p> 的文本密度 (text/chars)
        //   - 保留密度 > 0.25 的节点
        //   - 移除链接-文本比 > 0.5 的节点（导航/链接列表）
        val cleaned = readabilityExtract(doc)

        // Step 4: 转换为 Markdown
        return ExtractedContent(
            url = url,
            title = doc.title(),
            markdown = toMarkdown(cleaned).take(maxChars),
            charCount = cleaned.text().length,
            publishDate = extractDate(doc)
        )
    }

    private fun readabilityExtract(doc: Document): Element {
        // 对标 Mozilla Readability.js
        // 1. 移除噪声标签
        doc.select("script, style, nav, footer, aside, iframe, " +
                   "[role=ad], [aria-hidden=true], .ad, .sidebar, .nav").remove()
        // 2. 找到主内容容器（article > main > [id=content] > body）
        val mainContent = doc.select("article, main, [id*=content], [class*=article]")
            .firstOrNull() ?: doc.body()
        // 3. 文本密度剪枝
        pruneByTextDensity(mainContent, minDensity = 0.25, maxLinkRatio = 0.5)
        return mainContent
    }

    /**
     * 降级管道 (v2.2)
     *   优先: LLM 自带搜索 (DashScope enable_search)
     *   → 降级1: ContentExtractor (Readability)
     *   → 降级2: 仅返回标题+URL
     */
    suspend fun searchWithFallback(query: String): FunctionResult { ... }
}
```

---

### 9.6 MemoryModule — 混合检索 FTS5 + Vector + RRF（P2, Phase 5+）

**问题**：纯向量检索对精确关键词（人名、代码、数字）敏感度差。

**方案**：SQLite FTS5 全文检索 + 向量相似度 + Reciprocal Rank Fusion 融合。

```kotlin
class MemoryModule {
    /**
     * v2.2 混合检索：FTS5 BM25 + Vector Cosine + RRF 融合排序。
     * Android 内置 SQLite 支持 FTS5，零额外依赖。
     */
    suspend fun hybridSearch(query: String, topK: Int = 10): List<MemoryEntity> {
        // 1. FTS5 全文检索（精确关键词匹配）
        val ftsResults = memoryDao.ftsSearch(query, limit = topK * 2)
        val ftsScores = ftsResults.associate { it.id to it.bm25Score }

        // 2. 向量语义检索
        val vectorResults = memoryDao.vectorSearch(
            embedding = generateEmbedding(query)!!,
            limit = topK * 2,
            minSimilarity = 0.3f
        )
        val vectorScores = vectorResults.associate { it.id to it.cosineSimilarity }

        // 3. RRF 融合
        val allIds = (ftsScores.keys + vectorScores.keys).toSet()
        return allIds.map { id ->
            val ftsRank = ftsResults.indexOfFirst { it.id == id } + 1
            val vecRank = vectorResults.indexOfFirst { it.id == id } + 1
            // RRF 公式: score = 1/(k+rank_fts) + 1/(k+rank_vec), k=60
            val rrfScore = 1.0 / (60.0 + ftsRank) + 1.0 / (60.0 + vecRank)
            id to (rrfScore * timeDecay(id) * importanceBonus(id))
        }
            .sortedByDescending { it.second }
            .take(topK)
            .map { memoryDao.getById(it.first)!! }
    }
}

// MemoryDao 新增
@Dao
interface MemoryDao {
    // FTS5 虚拟表查询
    @Query("SELECT *, bm25(memory_fts) as score FROM memory_fts " +
           "WHERE memory_fts MATCH :query ORDER BY score LIMIT :limit")
    suspend fun ftsSearch(query: String, limit: Int): List<FtsResult>
}
```

**ONNX 加速**：`bge-small-zh-v1.5` (~24MB) 通过 NNAPI/GPU Delegate 加速，目标单次 embedding ≤50ms。

---

### 9.7 AutoFormUI — 编译时解析，零反射渲染（P1, Phase 8）

**问题**：Compose 渲染路径中使用 Kotlin 反射读取注解，性能开销大。

**方案**：`onAwake()` 时一次性解析 → `List<FormFieldState>` → UI 只绑定 State。

```kotlin
// 模块 onAwake 时调用一次（非 UI 线程）
class ModuleConfigParser {
    fun parse(config: ModuleConfigBase): List<FormFieldState> {
        return config::class.memberProperties
            .filter { it.hasAnnotation<ConfigField>() }
            .map { prop ->
                val anno = prop.findAnnotation<ConfigField>()!!
                FormFieldState(
                    key = prop.name,
                    label = anno.label,
                    description = anno.description,
                    fieldType = anno.fieldType,
                    isPassword = anno.password,
                    currentValue = (prop.getter.call(config) ?: "").toString(),
                    options = anno.options.toList(),
                    min = anno.min, max = anno.max,
                    onValueChange = { newValue ->
                        // 持久化到 Room 配置表
                        prop.setter.call(config, newValue)
                        saveConfig(config)
                    }
                )
            }
    }
}

data class FormFieldState(
    val key: String,
    val label: String,
    val description: String,
    val fieldType: ConfigFieldType,
    val isPassword: Boolean,
    val currentValue: String,
    val options: List<String>,
    val min: Double, val max: Double,
    val onValueChange: (String) -> Unit
)
```

**Compose UI 只遍历 List，零反射**：
```kotlin
@Composable
fun AutoFormUI(fields: List<FormFieldState>) {
    fields.forEach { field ->
        when (field.fieldType) {
            TOGGLE  -> SwitchRow(field)
            TEXT    -> TextFieldRow(field)
            NUMBER  -> SliderRow(field)
            DROPDOWN -> DropdownRow(field)
            MULTILINE -> MultilineRow(field)
        }
    }
}
```

---

### 9.8 白盒可观测性 — AgentTrace 系统（P1, Phase 8）

**问题**：自动化失败时零调试信息，无法回溯 LLM 思考过程。

**方案**：全局 `traceId` 串联每一步操作。

```kotlin
data class AgentTrace(
    val traceId: String,           // UUID，一次对话/自动化执行 traceId 不变
    val sequence: Int,             // 递增序号
    val timestamp: Long,
    val nodeType: NodeType,        // USER_INPUT / LLM_REASONING / TOOL_CALL / TOOL_RESULT / POKE / ERROR
    val summary: String,           // 一行摘要
    val detail: String?,           // 详细信息（可折叠）
    val tokensUsed: Long?,         // 关联的 Token 消耗
    val durationMs: Long?,         // 耗时
    val parentTraceId: String?     // 嵌套（如 ToolCall 内部再调 LLM）
)

enum class NodeType {
    USER_INPUT, LLM_REASONING, TOOL_CALL_START, TOOL_CALL_RESULT,
    POKE_RECEIVED, POKE_SENT, COMPRESSION, ERROR, GUARD_CHECK
}
```

**UI "Agent 思维链 timeline" 页面**：

```
┌──────────────────────────────────────────────────────┐
│  ← 返回          Agent 思维链                        │
├──────────────────────────────────────────────────────┤
│  Trace: auto_20260810_090000 (每日AI新闻)            │
│                                                      │
│  09:00:00.100  📨 User: 执行自动化: 每日AI新闻        │
│  09:00:00.150  ⏱ MessageFilter: 注入时间戳 (0.2ms)   │
│  09:00:00.500  🧠 LLM Reasoning: "需要先搜索AI相关..."│
│  09:00:00.800  🔧 TOOL: web_search "AI新闻"          │
│  09:00:02.100  ✅ RESULT: 10条, 1.2s, 1234 tokens    │
│  09:00:02.200  🔧 TOOL: web_search "芯片"             │
│  09:00:03.500  ✅ RESULT: 8条, 1.2s, 987 tokens      │
│  09:00:04.000  🧠 LLM Reasoning: "综合两条搜索..."    │
│  09:00:05.200  💬 LLM: 今日AI新闻要点如下...         │
│  09:00:05.500  💾 Memory: 已记忆 (importance=0.3)    │
│  09:00:05.600  📢 Push: "今日简报已生成"              │
│  ──────────────────────────────────────────────────  │
│  📊 总计: 3.5s, 3456 tokens, 💰 ~¥0.02               │
└──────────────────────────────────────────────────────┘
```

**采集点**：

| 采集位置 | NodeType | 触发时机 |
|---------|---------|---------|
| `ChatBotModule.sendMessage()` | USER_INPUT | 发送前 |
| `LLMClientModule.chatStream()` | LLM_REASONING | 收到 `thinkingDelta` |
| `FunctionRegistryModule.execute()` | TOOL_CALL_START / TOOL_CALL_RESULT | 执行前后 |
| `ChatBotModule` 收到 Poke | POKE_RECEIVED | Poke 到达 |
| `MemoryModule.intelligentMemorize()` | COMPRESSION | 压缩触发 |
| `ExecutionGuard.check*()` | GUARD_CHECK | 守护检查 |
| 任何模块 `catch` 块 | ERROR | 异常捕获 |

---

### 9.9 崩溃自愈（P0 — ModuleLifecycleManager 基础设施, Phase 1）

**问题**：非 Core 模块崩溃可能拖垮整个应用。

**方案**：`ModuleLifecycleManager` 包裹所有模块调用。

```kotlin
class ModuleLifecycleManager @Inject constructor(
    @ZeroModules private val modules: Set<@JvmSuppressWildcards IZeroModule>,
    private val logManager: LogManager,
    private val chatBus: ChatBus
) {
    private val crashCount = ConcurrentHashMap<String, Int>()

    /**
     * 安全的模块调用包装。
     *
     * Core 模块（CBM, MFM, FRM, CBM, LLM）崩溃 → 记录栈 + 通知用户 + 尝试重启。
     * 非 Core 模块崩溃 → 自动标记 DISABLED + 通知用户 + 可手动重新启用。
     */
    suspend fun <T> safeCall(module: IZeroModule, operation: String,
                              block: suspend () -> T): T? {
        return try {
            block()
        } catch (e: Exception) {
            val count = crashCount.merge(module.metadata.id, 1, Int::plus) ?: 1
            logManager.error(module.metadata.id, "$operation 崩溃 (第${count}次)", e)
            agentTrace.record(ERROR, "$operation 崩溃: ${e.message}", module.metadata.id)

            if (module.metadata.category == ModuleCategory.CORE) {
                // Core 模块不自动禁用，但通知用户
                if (count >= 3) {
                    chatBus.onPokeSend.emit(PokeEvent(
                        "🚨 核心模块 ${module.metadata.displayName} 连续崩溃 $count 次。" +
                        "建议重启应用。", source = "system", priority = URGENT
                    ))
                }
            } else {
                // 非 Core 模块自动禁用
                module.markDisabled()
                chatBus.onPokeSend.emit(PokeEvent(
                    "⚠️ ${module.metadata.displayName} 模块异常，已自动禁用。" +
                    "可在设置中重新启用。", source = "system", priority = HIGH
                ))
            }
            null
        }
    }
}

// 每个模块调用示例
suspend fun initializeAll() {
    for (module in ordered) {
        safeCall(module, "onAwake") { module.onAwake() }
        safeCall(module, "onStart") { module.onStart() }
    }
}
```

---

## 十、修订后开发阶段（v2.2）

| Phase | 内容 | 新增/修改 | 行数 | 关键 v2.2 增强 |
|-------|------|---------|------|---------------|
| **P1** | 项目骨架 + IZeroModule + ChatBus + MessagePipeline + **ModuleLifecycleManager(safeCall)** + AgentTrace基础 | 16 | ~900 | 崩溃自愈(P0)、AgentTrace 采集点 |
| **P2** | ChatBot + MessageFilter + LLMClient + ContextBuilder(**TokenBudgetAllocator**) | 7 | ~800 | Token动态配额(P0) |
| **P3** | FunctionRegistry(**并发FC** + **沙箱SecurityInterceptor**) | 5 | ~650 | 并发执行(P1) + 安全拦截(P1) |
| **P4** | MemoryModule + MemoryManager(从v1移植) | 3 | ~550 | FTS5表结构预留(P2) |
| **P5** | WebSearch + Vision + **ContentExtractor(Readability + 降级管道)** | 6 | ~750 | Readability提纯(P1) |
| **P6** | SystemEventModule(**PriorityQueue** + 三重唤醒 + **Notepad WAL**) | 7 | ~1400 | 任务队列(P1) + 原子持久化 |
| **P7** | AutomationModule (Plan-Execute + ExecutionGuard v2.1) | 4 | ~550 | |
| **P8** | UI层(**AutoFormUI反优化** + **AgentTraceTimeline** + ModuleCenter + RunLog) | 14 | ~1500 | 零反射UI(P1) + 可观测性(P1) |
| **P9** | Notification + UsageStats + Calendar | 5 | ~500 | |
| **P10** | 打磨：电池优化、权限引导、混合检索(FTS5+RRF)、崩溃恢复测试 | 4 | ~400 | 混合检索(P2) |

**总估算**：71 文件，约 8000 行

---

## 十一、安全保障清单（合并 v2.1 + v2.2）

| # | 安全措施 | v | 位置 | 机制 |
|---|---------|---|------|------|
| 1 | 循环依赖 SEM→ATM | v2.1 | SystemBus | OnScheduleTriggered 事件 |
| 2 | 循环依赖 FRM→MLM | v2.1 | FunctionRegistry | 延迟查询 |
| 3 | Android 后台限制 | v2.1 | BackgroundService | Service+WorkManager+AlarmManager 三层 |
| 4 | Plan 死循环 | v2.1 | AutomationModule | maxReplanCount=2 |
| 5 | JSON 解析脆弱 | v2.1 | generatePlan() | 4层提取+降级规则引擎 |
| 6 | Pipeline 阻塞 | v2.1 | MessagePipeline | withTimeout(3s) |
| 7 | ChatBus 丢帧 | v2.1 | ChatBusImpl | DROP_OLDEST/SUSPEND 差异化 |
| 8 | Memory 写入竞态 | v2.1 | MemoryModule | Mutex+@Transaction |
| 9 | 任务崩溃 | v2.1 | AutomationWorker | WorkManager BackoffPolicy |
| 10 | **Token 超限** | **v2.2** | **ContextBuilder** | **TokenBudgetAllocator+滑动窗口压缩** |
| 11 | **FC 串行慢** | **v2.2** | **FunctionRegistry** | **coroutineScope+async 并行** |
| 12 | **LLM 幻觉误操作** | **v2.2** | **FunctionRegistry** | **SAFE/SENSITIVE+弹窗确认** |
| 13 | **唤醒扫描 O(n)** | **v2.2** | **SystemEventModule** | **PriorityQueue O(log n)** |
| 14 | **网页噪声大** | **v2.2** | **ContentExtractor** | **Readability+Timeout 5s+MaxSize 2MB** |
| 15 | **纯向量检索弱** | **v2.2** | **MemoryModule** | **FTS5+Vector+RRF (Phase 5+)** |
| 16 | **反射卡 UI** | **v2.2** | **AutoFormUI** | **onAwake 解析→List<FieldState>** |
| 17 | **调试无信息** | **v2.2** | **AgentTrace** | **traceId 串联全链路 timeline** |
| 18 | **模块崩溃拖垮系统** | **v2.2** | **ModuleLifecycleManager** | **safeCall+自动禁用+Core保护** |

---

## 十二、Phase 13 — 多模型架构与配置持久化 (2026-08-10 设计稿 v2.3)

### 设计目标

1. 告别硬编码模型：LLMClientModule 支持 deepseek-v4-flash / qwen3.8-max 动态切换
2. API Key 可配置：UI 层提供安全输入界面，告别环境变量
3. 配置持久化：Room `module_configs` 表 + EncryptedSharedPreferences 双层存储
4. 模块独立性：每模块独立管理自己的 API Key，支持降级复用
5. 零重启热加载：切换模型即时生效，无需重启 App

### 模型规格

| 模型 | 提供方 | 上下文 | FC | Thinking | 端点 | 角色 |
|------|--------|--------|:--:|:--:|------|------|
| deepseek-v4-flash | DeepSeek | **1M** | ✓ | ✓ | api.deepseek.com/v1 | **主对话模型** |
| qwen3.8-max | Qwen | **1M** | ✓ | ✓ | ws-fdhs17...maas.aliyuncs.com/compatible-mode/v1 | 辅对话模型 |
| qwen3.5-flash | Qwen | — | — | — | ws-fdhs17...maas.aliyuncs.com/api/v1 (multimodal-generation) | 搜索模型 |
| qwen3-vl-flash | Qwen | — | — | — | ws-fdhs17...maas.aliyuncs.com/api/v1 (multimodal-generation) | 视觉模型 |

### ModelAdapter 架构

```
LLMClientModule
  │
  ├── LLMClientConfig（@ConfigField 声明式配置）
  │     ├── deepseekApiKey: String (password=true)
  │     ├── qwenApiKey: String (password=true)
  │     ├── activeModelId: String (DROPDOWN: deepseek-v4-flash / qwen3.8-max)
  │     ├── temperature: Float (SLIDER 0.0-2.0)
  │     └── maxTokens: Int (SLIDER 4096-1048576, 三级步长: 4K/128K/256K/512K/1M)
  │
  ├── ModelRegistry
  │     ├── DeepSeekAdapter  ← api.deepseek.com, OpenAI兼容, reasoning_content提取
  │     └── QwenAdapter      ← compatible-mode/v1, extra_body.enable_thinking
  │
  └── SecureKeyStore (EncryptedSharedPreferences)
        ├── getOrFallback(primaryKey, fallbackKey)  ← WebSearch/Vision 降级复用
        ├── put(moduleId:provider, key)
        └── delete(moduleId:provider)
```

```kotlin
/**
 * 模型适配器接口 — 统一 LLM 调用协议。
 *
 * ⚠️ 取消契约：chatStream 实现方必须在 Flow 的 awaitClose 中调用
 * OkHttp Call.cancel()。如果 UI 取消订阅（用户点击停止），底层 HTTP
 * 连接必须立即断开，防止后台继续消耗 Token。
 */
interface ModelAdapter {
    val modelId: String
    val displayName: String
    val contextWindow: Int
    val supportsFC: Boolean
    val supportsThinking: Boolean

    suspend fun chatSync(userMessage: String, systemPrompt: String?,
                         temperature: Float, maxTokens: Int): String

    fun chatStream(messages: List<ChatMessage>, systemPrompt: String?,
                   functions: List<FunctionDefinition>,
                   temperature: Float, maxTokens: Int): Flow<StreamChunk>
}
```

### DeepSeekAdapter 实现要点

```
端点: https://api.deepseek.com/v1/chat/completions
协议: 标准 OpenAI Chat Completions
API Key: SecureKeyStore.getOrFallback("llm_client:deepseek", null)
SSE 格式: "data: {json}" (有空格)
Context Window: 1,048,576 (1M)
特有:
  - delta.reasoning_content → thinkingDelta 提取
  - 无 enable_thinking 参数（DeepSeek 自动启用思考模式）
  - 取消契约: callbackFlow { ... awaitClose { call.cancel() } }
```

### QwenAdapter 实现要点

```
端点: https://ws-example.cn-beijing.maas.aliyuncs.com/compatible-mode/v1
协议: OpenAI 兼容
API Key: SecureKeyStore.getOrFallback("llm_client:qwen", null)
SSE 格式: "data: {json}" (有空格)
Context Window: 1,048,576 (1M)
特有:
  - 思考模式通过 extra_body 传递（非顶层参数）:
    json.put("extra_body", JSONObject().apply {
        put("enable_thinking", true)
        put("thinking_budget", thinkingBudget)
    })
  - delta.reasoning_content → thinkingDelta 提取
  - 取消契约: callbackFlow { ... awaitClose { call.cancel() } }
```

### 配置类定义

```kotlin
class LLMClientConfig : ModuleConfigBase() {
    @ConfigField("DeepSeek API Key", TEXT, password=true)
    var deepseekApiKey: String = ""
    @ConfigField("Qwen API Key", TEXT, password=true)
    var qwenApiKey: String = ""
    @ConfigField("当前模型", DROPDOWN, options=["deepseek-v4-flash","qwen3.8-max"])
    var activeModelId: String = "deepseek-v4-flash"
    @ConfigField("Temperature", SLIDER, min=0.0, max=2.0)
    var temperature: Float = 0.7f
    @ConfigField("最大 Token", SLIDER, min=4096.0, max=1048576.0)
    var maxTokens: Int = 4096
}

class WebSearchConfig : ModuleConfigBase() {
    @ConfigField("搜索 API Key", TEXT, password=true)
    var searchApiKey: String = ""
    @ConfigField("搜索模型", DROPDOWN, options=["qwen3.5-flash"])
    var searchModelId: String = "qwen3.5-flash"
}

class VisionConfig : ModuleConfigBase() {
    @ConfigField("视觉 API Key", TEXT, password=true)
    var visionApiKey: String = ""
    @ConfigField("视觉模型", DROPDOWN, options=["qwen3-vl-flash"])
    var visionModelId: String = "qwen3-vl-flash"
}
```

### 双层持久化

```
Layer 1 — SecureKeyStore (EncryptedSharedPreferences)
  存储: API Key (AES-256-GCM 加密, MasterKey 由 Android Keystore 管理)
  键格式: "{moduleId}:{provider}"
  例: "llm_client:deepseek" → "sk-397b..."
       "llm_client:qwen"     → "sk-ws-H..."

  getOrFallback(primaryKey, fallbackKey):
    → 先查 primaryKey
    → 若空且 fallbackKey 非空 → 查 fallbackKey
    → 若仍空 → 返回 null
  用途: WebSearch/Vision 降级复用 LLM 的 Qwen Key

Layer 2 — ModuleConfigStore (Room)
  表: module_configs(module_id TEXT PK, config_json TEXT, updated_at INTEGER)
  存储: 非敏感配置 (模型选择, temperature, maxTokens 等)
  config_json 不含 API Key
```

### 动态热加载流程

```
用户在 ModuleDetailScreen 选择 "qwen3.8-max"
  │
  ▼
DROPDOWN onValueChange → ModuleDetailViewModel.onFieldChanged()
  │
  ├── 1. pendingChanges["activeModelId"] = "qwen3.8-max"
  ├── 2. debounce(2s) → 自动保存
  │       (若 2s 内再次变更 → cancel Job → 重新计时)
  │
  ├── 3. 用户点击 [保存配置] → 立即同步落盘（最高优先级）
  │       → cancel debounce Job
  │
  ├── 4. ModuleConfigStore.save("llm_client", config.toJson())
  ├── 5. LLMClientModule.onConfigChanged(newConfig)
  │       ├── SecureKeyStore.getOrFallback("llm_client:qwen", null)
  │       ├── ModelRegistry.resolve("qwen3.8-max") → QwenAdapter
  │       ├── 更新 activeAdapter 引用
  │       └── Poke("✅ LLM 已切换到 通义千问 3.8 Max")
  │
  └── 6. onDispose 强制 Flush（页面退出时同步所有 pending 变更）
```

### 密码字段 UI 安全规范

```
渲染规则:
  - 默认显示掩码: "sk-****397b"（前3+后3字符，中间4个*）
  - 👁 点击时临时解密显示明文
  - 失去焦点或 5 秒后自动恢复掩码
  - UI 层永远不持有明文 Key 的 remember 状态

存储规则:
  - 仅通过 SecureKeyStore.put() 写入
  - 仅在"保存"按钮点击或 onFocusChanged(false) 时触发写入
```

### API Key 降级链表

```
WebSearchModule 取 Qwen Key:
  SecureKeyStore.getOrFallback("web_search:qwen", "llm_client:qwen")
  → 先查 WebSearch 自己的 Key → 若无 → 查 LLMClient 的 Qwen Key
  → 都无 → 返回 "[错误: 请在 LLM客户端 或 网页搜索 模块中配置 Qwen API Key]"

VisionModule 取 Qwen Key:
  SecureKeyStore.getOrFallback("vision_analyze:qwen", "llm_client:qwen")
  → 同上
```

模块不需要知道 Key 的来源（是用户单独配的还是复用的），`getOrFallback` 内部透明处理。

### 安全措施补充（v2.3 新增）

| # | 安全措施 | 位置 | 机制 |
|---|---------|------|------|
| 19 | API Key 明文泄漏 | SecureKeyStore | AES-256-GCM + Android Keystore MasterKey |
| 20 | API Key 交叉耦合 | SecureKeyStore.getOrFallback | 模块只声明 provider 类型，不引用其他模块 |
| 21 | SSE 后台泄漏 | ModelAdapter | callbackFlow + awaitClose { call.cancel() } |
| 22 | 密码框内存泄漏 | ModuleDetailScreen | UI 掩码显示 + 仅 onFocusChanged 时写入 |
| 23 | Debounce 竞态丢配置 | ModuleDetailViewModel | 手动保存 > debounce > onDispose Flush 三级保护 |

### 文件变更清单

```
新增 (6):
  core/model/ModelAdapter.kt         ← 模型适配器接口（含取消契约）
  core/model/ModelRegistry.kt        ← modelId → Adapter 映射表
  services/llm/DeepSeekAdapter.kt    ← api.deepseek.com, 1M 上下文
  services/llm/QwenAdapter.kt        ← compatible-mode/v1, extra_body
  core/data/db/ModuleConfigStore.kt  ← Room module_configs 表 + DAO
  services/security/SecureKeyStore.kt ← EncryptedSharedPreferences + getOrFallback

重写 (3):
  services/llm/LLMClientModule.kt    ← LLMClientConfig + ModelRegistry + SecureKeyStore
  services/llm/ApiKeyProvider.kt     ← 委托到 SecureKeyStore（废弃 env var 读取）

修改 (5):
  services/module/WebSearchModule.kt ← WebSearchConfig + getOrFallback 取 key
  services/module/VisionModule.kt    ← VisionConfig + getOrFallback 取 key
  app/ui/module/ModuleDetailScreen.kt← AutoForm 真实渲染 password/DROPDOWN/SLIDER
  app/ui/module/ModuleDetailViewModel.kt ← onManualSave / onFieldChanged / onDispose
  app/build.gradle.kts               ← 添加 security-crypto 依赖

估算: 新增 ~800 行，删除 ~200 行
```

## 2026-10-09 本地开发模型实际接线补充

- 用户授权本地Qwen3.5 9B为开发CHAT主力：显式local-qwen Profile，ADB reverse 8080，context24576/output2048，原云CHAT档暂停启用以避免自动回退；专用多模态/embedding服务保留。操作及验收边界见 docs/refined/【本地模型】Qwen开发接入与上下文验收.md。
- ILanguageModel.outputReserve为默认4096的接口属性；ProfileLanguageModel动态读取Profile.maxTokens。ChatBot FC/最终综合/重新生成使用选中模型输入预算，云端原世代线在其模型预算允许时保持。
- OpenAiCompatibleClient使用LocalLlamaContextGuard（仅local-qwen）读取运行上限／实际模板token计数。LocalLlamaMessageProjection解决Qwen仅首条SYSTEM模板契约，确定性投影后续应用快照，原始历史／Room角色不变。DeepSeek请求体不采用投影或本地参数。
- 本地CHAT/短重排usage保留日token计量，BudgetGuard.record增加默认billable=true参数，本地调用传false，不计云费用估算。常规C3云测试需明确付费开关。最终APK SHA256 26a5b59d1e586b95d8348c4c515310609078cd66ed528262639b21dcd1d2e4f6 已安装，530-task质量门及真实历史/工具续轮实机复合用例通过；不声称DeepSeek当前热率98%或A8完整完成。


### A8c.3a.3b.2b.2b＋3b.3 闹钟持久交接与真实Worker（2026-10-09，限定通过）

- 接续L1和2b.2a。生产Receiver旧goAsync承载整个模块扫描，AutomationWorker固定成功，Application未提供Hilt工厂。先在旧实装包上跑2项实机，2项失败：不存在的旧automation_id被报成功、Application没有Configuration.Provider。红APK/源码/日志保留；完成修订后真实业务及配置验证均通过。
- Receiver只接受三种已知action，提交一次性持久任务并等待提交确认后finish；业务在Worker执行。core AlarmWorkQueue接口隔离services与app/WorkManager；Hilt队列按action串接APPEND_OR_REPLACE，后续唤醒不取消正在执行的扫描，无网络前提、无新周期心跳。提交future最多等待5秒；不将其描述为所有冷启动/系统调度情况下的时限保证。
- Application与前台宿主/Worker共用Singleton ModuleInitializationGate。Worker仅初始化等待有60秒期限、最多2次重试；业务经生产AlarmDueDispatcher与现有模块守卫/safeCall。取消及工具局部超时传播，已进入业务的普通异常失败且不自动重放。成功仅表示到期扫描/Bus分发返回，不能等同每个任务交付或音乐播放成功。保留Worker类名，移除无调用方的旧enqueue存根；只有旧automation_id且缺少alarm_action的遗留请求明确失败，不删除个人任务。
- Application提供HiltWorkerFactory配置，只移除WorkManagerInitializer元数据，保留其他AndroidX Startup初始化器。实机直接验证该配置能创建实际AutomationWorker；Hilt工厂无单例约定，初次assertSame误判已改为真实创建/执行验证，没有改变生产作用域迎合测试。
- 联合23项实机10.221s通过：2项工厂/旧输入、6项Worker/初始化/取消/真实广播交接、4项模块守卫、6项启动、2项SMART/ALWAYS_ON宿主及3项Scheduler取消。真实同UID有序广播验证Receiver.finish发生在提交后，两个唤醒均保留且业务仅执行一次；业务Room和WorkManager用隔离夹具，无个人任务或云请求。另两进程阶段用物理WorkManager数据库提交无action的隔离任务，显式force-stop后正常Main重启，验证阶段读取原ID为FAILED、执行次数1且没有重试；只证明持久恢复/真实工厂，不冒充LMK、有效个人任务、Boot或Doze交付。
- qualityCheck＋assembleDebugAndroidTest通过（最终26秒，530 tasks，含assembleDebug；quality-build-final-3.log）。新增Gate取消等待单测通过。保留编译修正日志（Hilt EntryPoint导入、WorkInfo可空契约）和WorkManager执行次数断言纠正日志及安装未完成提前启动旧包的无效结果premature-install-native.log，后者不计绿态。未抑制质量规则。
- 最终APK artifacts/stability-20261009-a8durable-alarm/ZeroV2-a8durable-alarm-debug.apk SHA256 bef486dfc11d0f08b9ddc0b38bd2e02df15dfe5c060a3b27b878da57c6c5a482 已保留数据ADB安装、手机摘要一致；正常Main恢复及前台宿主另留foreground-host.txt。诊断quick_check=ok，用户module_configs全表摘要与本轮基线完全一致。本轮未改LLM/上下文/缓存请求逻辑，保留local-qwen与ADB reverse，不发DeepSeek验收请求、不重置既有预算。全部已有工作树改动保留。
- A8保持开放。下一片3b.4须审计BootReceiver初始化前直接恢复并补真实回收/Doze证据；提醒一次性过期跳过、周期下期重排的现有规则保持。独立Job源门禁、手动/定时竞争、跨进程副作用幂等、首次宿主恢复异常、A8c.4–8及A9/A10仍开放。本片无UI视觉变化，不复用截图宣称全页新验收。旧临时数据库副本被自动审批拒绝清理仍开放，不绕过。
- 官方依据：[自定义WorkManager配置](https://developer.android.com/develop/background-work/background-tasks/persistent/configuration/custom-configuration)、[Hilt Worker](https://developer.android.com/training/dependency-injection/hilt-jetpack#workmanager)、[唯一任务串接策略](https://developer.android.com/reference/androidx/work/ExistingWorkPolicy)。证据见本片acceptance-index.json、red-native.log、final-device.log、persistence-enqueue.log、persistence-verify.log、quality-build-final-3.log、installed-sha.txt及source-final。



### A8c.3a.3b.4a 开机恢复契约与Doze持久任务探针（2026-10-09，限定通过）

- 接续3b.3，保留本轮开始的391项工作树改动。旧生产BootReceiver直接调用模块异步恢复，绕过共享初始化和源门禁；恢复入口没有启用态检查，广义捕获隐藏取消。先安装测试接缝包，在真实SystemEventModule、隔离Room及拒绝调度真实闹钟的Context上实跑1项，1项失败：关闭模块后过期合成提醒仍从active改为missed。红源码/APK/控制器及red-native.log保留，不读取个人提醒，不发送模型请求。
- BootReceiver只在BOOT_COMPLETED提交已知BOOT_RESTORE action，等待现有一次性持久队列提交后finish；复用真实AutomationWorker/共享初始化Gate、模块守卫和safeCall，不新增周期唤醒。恢复业务采用suspend restoreAfterBoot，等待落库和队列更新后返回，取消/错误交给Worker所有者处理；旧权限变化回调保留模块作用域异步rescheduleAllOnBoot适配，取消单独传播，不破坏非suspend调用方。
- 恢复入口检查关闭/销毁状态；restoreMutex串行化初始化、开机和权限回调的提醒恢复，未把该锁声称为所有提醒增删/执行的全局互斥。恢复后的StateFlow改用更新后的Room数据，修正旧快照仍显示active或旧周期时间的问题。一次性过期仍missed且不补发，周期保持原相位推进到未来，不修改历史规则或数据库版本。未改变全局onAwake初始化策略；关闭态拒绝结论限定为额外恢复入口及Worker守卫。
- 新4项实机通过：关闭模块不改记录；恢复完成时Room和只读流一致、周期相位/重复恢复幂等；已取消调用不改记录；真实AlarmWorkExecution等待Gate后经真实Dispatcher/ModuleLifecycleManager/SystemEventModule恢复，关闭源返回Failure。原23项联合保留，共27项11.418s通过。Context仅在最后一项允许空模块启动时一次只读精确闹钟权限查询，后续闹钟服务访问拒绝；没有替换个人AlarmManager排程或通知。控制器不运行正常Application.onCreate，真实系统开机广播未伪造。
- qualityCheck＋assembleDebugAndroidTest通过（1m 17s，530 tasks，含assembleDebug）。初次detekt拒绝新泛型捕获，改为让Worker拥有错误处理；随后编译指出旧权限回调是非suspend，保留明确适配入口修复。失败日志留存，没有屏蔽规则。本片普通业务错误不自动重放可能产生的副作用。
- 实机Doze探针：手机约31℃，既有用户白名单包含Zero，未改白名单/保活设置。先向物理WorkManager数据库提交缺少action的隔离任务，再临时模拟拔电/熄屏和force-idle；5次间隔10秒采样均IDLE，退出后原任务终态FAILED、执行次数1且没有重试。探针无有效业务action、无个人任务/模型调用；不能由此宣称有效提醒准点送达、无白名单Doze行为、真实系统闹钟或LMK恢复已通过。临时force状态、电池模拟已解除，最终状态见doze-observation.json和restored-system-state.txt。该探针按[Android官方Doze测试流程](https://developer.android.com/training/monitoring-device-state/doze-standby)执行，验收边界保持。
- 修复APK artifacts/stability-20261009-a8boot-recovery/ZeroV2-a8boot-recovery-debug.apk SHA256 093492168842403b1b92e45bb3474a0697d6adbca7b88eb156b3fbcbdfc62ab9 已保留数据ADB安装，主包/控制器摘要核对。正常Main恢复，前台宿主另留foreground-host.txt；quick_check=ok，用户module_configs全表摘要与本轮基线一致。未改LLM预算/上下文/缓存源码，local-qwen及USB转发保留，无DeepSeek测试请求、不重置既有额度账本。本片无页面布局变化，不替代全页UI矩阵。
- A8及3b.4整体仍开放；下一项3b.4b需真实设备重启后BOOT_COMPLETED、有效提醒/自动化在Doze中的交付及进程回收证据。本片仅收口恢复方法/Worker接线和无业务持久探针。独立Job门禁、手动/定时竞争、跨进程副作用幂等、A8c.4–8、A9/A10仍按原序推进。此前被自动审批拒绝清理的旧临时数据库副本仍开放，不重试绕过。
- 证据 artifacts/stability-20261009-a8boot-recovery/{acceptance-index.json,red-native.log,green-native.log,quality-build-final.log,doze-observation.json,doze-worker.log,doze-verify.log,final-device-check.json,installed-sha.txt,source-final}。


### A8c.3a.3b.4b.1 真实开机恢复／前台宿主缺口（2026-10-09，限定通过）

- 接续4a，从本轮开始392项工作树改动继续，未reset/clean/提交。真实重启手机，首次解锁前ADB不可见，由用户完成首次解锁恢复连接；未伪造BOOT_COMPLETED或修改锁屏设置。首次新boot_id下真实BOOT_RESTORE完成且SMART循环启动，但主用户AutonomousKeepAliveService不存在。verify-boot.py after-device.json red实跑断言失败并保存red-real-boot.log、red-boot-check.json和原BootReceiver；不是用组件测试代替真实开机。
- 三项诊断按实际证据收敛：MainActivity是唯一宿主start生产调用方；模式OFF/配置变动由SMART和摘要一致排除；系统拒绝不是首轮根因，因为旧BootReceiver根本未请求宿主。新增Hilt AutonomousBootHost，在开机接收器的持久交接协程中按确认配置请求现有specialUse前台服务，OFF/当前关闭的Runtime不请求，服务本身继续等待共享初始化及观察运行态。宿主请求被平台拒绝时独立留boot_host_start_failed，不阻断提醒恢复入队。无第二心跳、周期重试或后台恢复器，不改缓存/模型路由。
- 1项新增复合实机用例用隔离Room测试SMART/ALWAYS_ON/OFF、关闭Runtime和配置不被改写；不是6个独立测试，也不冒充三模式真实重启。原27项保留，共28项12.551s全通过。qualityCheck＋assembleDebugAndroidTest通过（1m 32s，530 tasks，含assembleDebug）。
- 安装修复包后第二次真实重启，用户首次解锁并未打开Zero主页面。新boot_id与首轮不同；主用户已出现foregroundId2003、isForeground=true、specialUse服务，BOOT_RESTORE成功记录位于本次估算boot epoch之后。初期读取曾见旧记录，等待实际完成后用严格epoch校验收口，未把前轮成功冒充本轮。第二次verify-boot.py green-device-final.json green通过，证明当前Android15/SDK35、SMART及既有电池豁免条件下真实开机接线成立。
- 手机同时存在用户0和用户95的同名进程，旧pidof“唯一进程”诊断主动拒绝读取。仅修订本片诊断脚本，以run-as主用户UID10025匹配ps的准确主进程，并以dumpsys --user 0核验宿主；没有冻结、读取或修改用户95私有数据。原诊断模板及历史证据未覆盖写，副用户不计本片验收。异常输出留痕；不把两用户服务混算成同一Runtime重复owner。
- 主用户quick_check=ok、module_configs全表摘要与重启前一致，五条ACTIVE自动化数量与分组保持。两次重启后dynamic_modules全记录state/crashCount一致：qzone_post/searx_search/vision_pro为ACTIVE，其他保持原DISABLED；未擅自启用关闭模块，DB状态不等同每项远端功能均已调用验收。Termux恢复走原生产引导与mount链，原dyn_restore_failed记录早于本轮，未报告为新失败。
- APK artifacts/stability-20261009-a8real-boot/ZeroV2-a8real-boot-debug.apk SHA256 09149cb405373efe5cef982e04ff29361eb9f13be0ad217cf1edbbc868ac1e1f 已保留数据ADB安装，手机主包/控制器摘要核对。两个重启后ADB reverse8080已恢复，本地节点health正常；本轮不新增DeepSeek付费测试、不操作旧预算额度账本。真实常规运行的专用能力网络行为不宣称已全部本地化。
- 3b.4b仅真实开机子项收口，A8仍开放。下一3b.4b.2补有效提醒/自动化在真实Doze中的AlarmManager→Receiver→Worker→业务交付及进程回收；4a无action探针不代替有效任务。真实OFF/ALWAYS_ON开机、无电池豁免和其他用户空间另需证据，不修改个人模式来冒充已验收。A8c.4–8、独立Job源门禁/手动竞争和A9/A10按原计划继续。本片无UI布局变化；旧被自动审批拒绝清理的临时副本仍开放。
- 官方边界：[后台启动前台服务的例外与限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)、[Android15开机服务类型限制](https://developer.android.com/about/versions/15/changes/foreground-service-types)。本机处于既有电池豁免，不能由结果外推所有后台限制条件。证据本片acceptance-index.json、red/green-boot-check.json、red/green-real-boot.log、green-host.txt、green-device-final.json、green-native.log、quality-build.log及source-final。


### A8c.3a.3b.4b.2a 有效PLAN真实Doze广播交付（2026-10-09，限定通过）

- 按3b.4b.1后续推进有效任务，未更改生产业务源码。新增显式validDozeProbe=true才运行的验收控制器；主用户0、独立requestCode900820，目标为生产ExactAlarmReceiver，action为真实AUTOMATION_DUE。该身份与生产4002不同，不替换个人闹钟；本次用ELAPSED_REALTIME_WAKEUP＋setExactAndAllowWhileIdle安排45秒后唤醒，生产RTC身份/时钟及权限降级仍另验。无新增debug导出组件、生产周期心跳或模型调用。
- 实机走真实AlarmManager广播→Hilt生产Receiver→生产DurableAlarmWorkQueue接口→实际AutomationWorker/AlarmWorkExecution→模块守卫/ModuleLifecycleManager→真实AutomationModule PLAN。业务工具为本地夹具回调，只读当时系统idle状态；隔离Room保存合成计划，WorkManagerTestInitHelper提供内存数据库及组装实际Worker的工厂，不扫描个人自动化、不发送通知或模型请求。此接缝验证交付和业务执行，不能冒充生产WorkManager物理落盘、真实Hilt工厂冷恢复或进程死亡后的执行。
- 1项复合实机用例45.544s通过，不拆成多个测试计数：9次5秒间隔采样均IDLE，工具真正执行时idleAtTool=IDLE；工具calls=1、Room runCount=1、workCount=1、Worker成功，下次执行时间已推进到未来。计划与实际elapsed相差131ms，仅为单次观察，不是长期准点/性能门，不放宽原验收门槛。该用例不是4a“缺少action→Failure”的无业务探针，已进入实际PLAN和记账路径。
- 测试结束finally取消900820对应的独立PendingIntent并取消隔离WorkManager的本测试队列；未cancelAll/prune生产任务。驱动脚本解除force-idle和电池unplug模拟，deep恢复ACTIVE、mForceIdle=false、实际供电状态恢复（AC=true、USB=false）；既有Zero电池白名单保留，未改锁屏、超时、字号或其他Android用户配置。源门禁、初始化、取消及两种宿主模式的28项上一片证据仍对应同一主APK，本轮不冒充重跑28项。
- qualityCheck＋assembleDebugAndroidTest通过（38s，530 tasks，含assembleDebug）。主APK SHA256 09149cb405373efe5cef982e04ff29361eb9f13be0ad217cf1edbbc868ac1e1f与上一片实装一致，仅ADB更新测试控制器；没有发现需通过改业务逻辑修复的新失败。安装主包事实仍以本轮installed-sha.txt核对；不将新增验收控制器包装成新修复主包。
- 正常Main/前台宿主恢复：主用户foregroundId2003、specialUse、isForeground=true，quick_check=ok；module_configs全表摘要、个人自动化分组及dynamic_modules state/crashCount与本轮基线一致。原关闭模块未启用，现有工作树改动全部保留，未提交交错改动。local-qwen与USB转发保留，未操作旧费用额度账本；“无模型请求”限定本测试夹具，不外推为常规后台所有专用能力均无网络。
- 3b.4b.2及A8保持开放，下一2b验收有效业务在物理持久队列和进程回收后的恢复，必须避免活instrumentation/内存夹具替代冷恢复；同时补生产RTC排程、真实提醒通知、权限降级和必要竞态证据。A8c.4–8、独立Job门禁/手动竞争及A9/A10继续原顺序。本片无UI视觉修改，不能替代全页UI矩阵；此前被自动审批拒绝清理的旧临时副本仍开放，不重试绕过。
- 证据 artifacts/stability-20261009-a8valid-doze/{acceptance-index.json,native-doze.log,driver.log,business-receipt.json,doze-observation.json,quality-build.log,installed-sha.txt,before-device.json,after-device.json,foreground-host.txt,restored-system-state.txt}；控制器源码ValidDozeAlarmAcceptanceTest.kt及本片final源码快照。


### A8c.3a.3b.4b.2b.1 持久业务夹具跨进程恢复（2026-10-09，限定通过）

- 接续2a，仅扩展androidTest：原守卫夹具默认仍内存Room；显式参数只允许独立a8_disk_recovery_fixture.db，恢复阶段不重新插入任务。新增diskRecoveryPhase显式双阶段控制器，隔离配置/运行时/工具，无个人任务扫描、模型请求或生产闹钟变更。
- seed阶段实际Room提交合成PLAN并保存就绪标记。驱动核对主用户UID10025、进程名及PID后，仅对该测试进程执行SIGKILL，确认原PID退出；不是force-stop、真实系统LMK或后台自恢复。随后独立instrumentation新进程读取同一物理数据库，未重建任务，经实际AutomationWorker→AlarmWorkExecution→模块守卫→AutomationModule执行本地工具。
- seed PID12140、恢复PID12209；恢复前runCount=0，工具calls=1、记账runCount=1，下次时间推进未来；同一恢复进程再次Worker扫描没有重执行。1项恢复用例通过；原夹具/Worker接线10项相关实机回归通过（2.445s）。强制终止的seed不是通过测试，未把崩溃日志算成功。此项不证明副作用后记账前崩溃的恰好一次语义。
- qualityCheck＋assembleDebugAndroidTest通过（41s、530 tasks，含assembleDebug）；生产源码/主APK未变，主APK SHA256 09149cb405373efe5cef982e04ff29361eb9f13be0ad217cf1edbbc868ac1e1f与手机安装摘要一致，仅更新控制器。原28项不冒充本轮重跑。
- 验收后关闭并删除本测试独立数据库及就绪标记，驱动确认不存在；保留私有脱敏回执和仓库证据。正常Main及user0前台宿主恢复，foregroundId2003/isForeground=true；个人module_configs摘要、自动化分组、动态模块state/crashCount与基线一致，quick_check=ok。本地8080转发保留，不重置费用账本、不触碰副用户95或既有工作树改动。
- 2b及A8仍开放：下一2b.2必须补生产WorkManager物理队列及真实Hilt自动冷恢复。本片Worker由测试工厂构建，进程由instrumentation重新启动，不能替代队列/系统自动唤醒验收。RTC生产排程、提醒通知及权限降级、必要竞态、A8c.4–8和A9/A10继续原序。无UI布局变化，不代替全页UI验收。先前被自动审批拒绝的旧临时副本保持原状态，不重试绕过。
- 证据 artifacts/stability-20261009-a8disk-recovery/{acceptance-index.json,termination.json,seed-native.log,recovery-native.log,regression-native.log,business-receipt.json,quality-build.log,before-device.json,after-device.json,installed-sha.txt,host.txt,reverse.txt}及控制器源码快照。


### A8c.3a.3b.4b.2b.2a 真实持久WorkManager／Hilt新进程执行（2026-10-09，限定通过）

- 从393项工作树改动接续，保留全部既有改动，未reset/clean/提交。新增仅debug编译的ColdPlanAcceptanceWorker及显式coldWorkPhase控制器；无生产源码/Manifest/缓存前缀/模型路由变更。隔离Worker固定自己的物理Room、标记文件及Work UUID，缺标记/身份不符拒绝执行，不接个人工具、提醒或数据。
- seed使用正常Application的Configuration.Provider与真实HiltWorkerFactory、真实WorkManager数据库（no_backup/androidx.work.workdb），提交60秒延迟独立Work并确认ENQUEUED后保存就绪标记。不使用WorkManagerTestInitHelper、内存队列、TestListenableWorkerBuilder或手工Worker工厂；生产DurableAlarmWorkQueue的请求类仍为AutomationWorker，本片独立请求类为debug桥接Worker，不能混称原生产入队端到端冷验收。
- 主用户UID10025准备PID30026，核对身份后SIGKILL并确认消失；等待期间仅run-as读取回执和ps观察，没有instrumentation重启、Main打开、广播、强制Job或force-stop。新PID30125中由正常Hilt创建debug桥接Worker，内部以独立Gate/模块/Room调用实际AutomationWorker→AlarmWorkExecution→守卫→AutomationModule PLAN。相同UUID 418225fb-f5fd-4526-a24a-425b77d1cb1d自动完成，本地工具calls=1、runCount=1、nextRunAt推进未来。
- 重要归因边界：采样先观察到新进程，约50秒后才收到任务回执，不能声称该进程仅由到期JobScheduler首次拉起；现有宿主/后台恢复可能先启动它。验证的是准备进程消失后持久任务在自动恢复进程中由Hilt自动执行，不是假装从未运行的冷启动性能测试。未模拟系统LMK，不证明副作用后记账前崩溃的恰好一次语义。
- 回执到达后才启动verify控制器，读取同一UUID物理WorkSpec为SUCCEEDED且独立Work只一条；1项验证通过（0.178s）。另12项原Hilt配置、模块守卫、Worker/广播接线实机回归通过（2.540s）；终止的seed日志不是成功用例。关闭并删除本片独立业务数据库/就绪文件，保留完成WorkSpec及脱敏回执，不cancelAll/prune个人队列。
- 首轮控制器两处可空WorkInfo处理编译失败已修正并保留失败日志；最终qualityCheck＋assembleDebugAndroidTest通过（51s、530 tasks，含assembleDebug）。主APK因新增debug验收桥而变化，SHA256 ad9d723555a410461a9190d5b92fd3c493a361aad08bad0a83af23c169786f43，已保留数据ADB安装并核对手机摘要，不称生产调度修复。归档 artifacts/stability-20261009-a8cold-work/ZeroV2-a8cold-work-debug.apk；benchmark/release不包含此debug Worker。
- Main和主用户specialUse宿主恢复，foregroundId2003/isForeground=true；quick_check=ok，module_configs全表摘要、个人自动化分组、动态模块state/crashCount与基线一致。本地8080转发保留；夹具拒绝模型，但正常Application后台行为仍走原配置，不能外推所有专用能力无网络。未操作旧费用账本，不读取/终止副用户私有进程、不触碰先前被自动审批拒绝清理的临时副本。
- 持久WorkManager和正常Hilt新进程创建接缝取得真实证据，2b/A8仍开放：原生产AutomationWorker请求类＋共享初始化Gate＋Receiver在同一冷恢复链的独立业务隔离证据仍需补齐；生产RTC排程/权限降级、提醒通知、必要竞态及A8c.4–8/A9/A10继续原序。无页面视觉变化，不借用旧截图替代UI验收。
- 证据本片acceptance-index.json、termination.json、observations.json、business-receipt.json、seed/verify/regression-native.log、quality-build[-final].log、installed-sha.txt、before/after-device.json、主用户host.txt及限定Job摘要、源码快照。


### A8c.3a.3b.4b.2b.2b 原生产RTC／Receiver／共享Gate联合提醒交付（2026-10-09，限定通过）

- 接续2b.2a，仅新增显式productionReminderPhase控制器；主APK不变。本片不再使用debug桥、测试WorkManager、替换Hilt工厂/Gate或手工构造Worker。使用真实生产库中唯一标注的临时提醒a8-production-cold-reminder，非独立Room库；执行前拒绝任何180秒内个人active提醒及未完成提醒Work。为纯本机系统通知测试，不安装模块、不使用外部发信工具。
- 控制器经实际DAO持久保存60秒后的测试提醒，再调用原ExactAlarmScheduler.scheduleReminder：RTC_WAKEUP、标准REMINDER_DUE/原4001身份，真实alarm dump确认触发时间。暂时替代最近一次唤醒是本片自身到期任务的正常生产排程，未cancelAll或修改个人提醒。准备PID11934核对user0 UID10025后SIGKILL并确认退出；不force-stop、不冒充系统LMK。
- 驱动进入真实Doze，在到期前后持续IDLE，仅观察进程和系统状态；无Main打开、instrumentation重启、广播注入或强制Job触发。自动恢复进程运行正常Application初始化，原Receiver→DurableAlarmWorkQueue→物理WorkManager→原Hilt AutomationWorker→生产AlarmWorkExecution/共享Gate/模块守卫完成。本轮新增Work UUID f69bf411-3836-4946-9681-a7fc9200d92e，物理worker_class_name为原AutomationWorker，state=2/SUCCEEDED；不是上一片debug桥请求。
- 交付归因如实收敛：Runtime的job_execute先开始处理到期提醒，系统通知在计划时刻后42ms登记；78ms后出现alarm_fired: REMINDER_DUE，再6ms出现Worker scan_finished。当前实例只出现一条通知、一条对应notified轨迹，原一次性测试行删除。证明现有双入口联合链交付和Worker后续正常扫描，不能声称此次副作用由Worker独立完成或已经证明任意并发恰好一次；生产代码原本明确Worker成功只代表扫描返回。
- 到期及后台观察完成后才启动verify，1项复合实机验证0.220s通过；被SIGKILL的seed不是通过用例。校验其余全部提醒完整实体摘要保持不变，随后仅取消本测试通知、删除本测试行/就绪文件，保留原个人提醒和完成WorkSpec。门禁/工厂12项上一片回归仍对应同一主APK，不冒充本轮重跑。
- qualityCheck＋assembleDebugAndroidTest通过（41s、530 tasks，含assembleDebug）；仅ADB更新控制器。主APK ad9d723555a410461a9190d5b92fd3c493a361aad08bad0a83af23c169786f43与当前手机实装摘要一致，没有新生产修复包。finally解除force-idle及电池模拟，deep恢复ACTIVE；未修改白名单、系统锁屏/字号/超时。正常Main和主用户specialUse宿主恢复，foregroundId2003/isForeground=true；module_configs摘要、个人自动化分组、dynamic_modules state/crashCount一致，quick_check=ok，本地8080转发保留。未重置旧费用账本；正常后台按现有路由运行，不声称所有专用能力无网络。
- 2b.2b按生产联合交付范围收口；A8及3b.4b整体仍开放。下一项补提醒Runtime/Worker/恢复方法并发队列和周期写入保护、独立Worker交付及精确权限降级必要证据，随后按原序推进剩余Job门禁/手动竞争、A8c.4–8/A9/A10。onAwake对过期一次性仍执行既有missed兼容规则，本次自动进程在到期前已恢复，不能外推到到期后首次恢复。无UI布局修改，不借旧截图代替UI验收；旧被自动审批拒绝的临时副本保持原状态。
- 证据本片acceptance-index.json、termination.json、rtc-before.txt、observations.json、business-receipt.json、seed/verify-native.log、quality-build.log、before/after-device.json、installed-sha.txt及主用户host.txt、控制器源码快照。


### A8c.3a.3b.4b.2c.1 周期提醒保留元数据／删除不复活（2026-10-09，修复限定通过）

- 按2c先处理可复现的周期写入缺口，使用diagnosing-bugs流程。旧实现由moduleScope异步saveReminder(REPLACE)，重建对象只带id/remark/trigger/isRecurring/recurringMs；真实模块/隔离Room两项实机红测均失败：title空、note=null、createdBy从llm变user、repeatRule丢失；通知权限检查接缝中删除行后，旧异步保存又插入该行。只用本地合成数据，强制拒绝系统通知/个人闹钟/模型，不把源码推测写成实机失败。
- 新DAO接口advanceRecurringIfActive(id,expectedTriggerAt,nextTriggerAt):Int，只对status=active且trigger_at仍匹配的现有行更新trigger_at。Room @Transaction＋周期推进Mutex保护，执行路径等待提交成功再发布下一期队列/唤醒；不重建整条ReminderEntity，不把错误吞进后台协程。保留全部其他字段，删除/非active/已改触发时间更新返回0，不重建或覆盖该行；数据库结构未改，无新增迁移。
- 2项原失败用例转绿，另1项复合DAO用例验证旧触发时间不能覆盖新排程、非active行不能被推进。相关门禁/Hilt/Worker/广播/开机恢复16项同轮回归，共19项实机通过（6.498s）。不是19项新增，也不是真实个人通知/LMK测试；真正系统通知出口沿用上片证据。本片仅保留已验证写入范围，未声称所有PriorityQueue读写已串行。
- 最终qualityCheck＋assembleDebugAndroidTest通过（1m29s、530 tasks，含assembleDebug）；前一轮2m15s构建也通过，补全Mutex/事务后以最终产物重新测试。修复APK f35d3592e7054f61c9a8353a04fd795ef88068067be557d57ff0d31cf7e17e1b已保留数据ADB安装，手机实装摘要一致；归档artifacts/stability-20261009-a8reminder-write/ZeroV2-a8reminder-write-debug.apk。
- 正常Main及主用户specialUse宿主已恢复，foregroundId2003/isForeground=true；quick_check=ok，module_configs摘要、个人自动化分组、dynamic_modules state/crashCount与开工基线一致，本地8080转发保留。不改LLM/缓存前缀/模型配置/额度账本；测试夹具不请求模型，不外推常规后台所有能力无网络。全部既有工作树改动保留，生产差异相对本片开工源码独立存档，未reset/clean/提交交错改动。
- 架构收敛：周期排程推进使用具备状态和触发时间条件的深DAO接口，将“可否发布下一期”与Room提交结果关联；调用方不再拼装易遗漏字段的全实体。仍需下一2c.2统一提醒Runtime/Worker/恢复/FC/UI队列访问，并审计一次性删除与创建异步写入；同触发时刻下并发修改重复规则仍未由本次条件独立覆盖。Worker独立交付、精确权限降级、必要竞态及A8c.4–8/A9/A10保持原序，A8不整体验收。无UI视觉修改；先前被自动审批拒绝清理的旧临时副本保持原状态。
- 证据本片acceptance-index.json、red/green-native.log、red-build.log、quality-build[-final].log、installed-sha.txt、before/after-device.json、host.txt、源码before/final及slice.diff。红测命令：am instrument --user 0 -w -r -e class com.zero.v2.app.acceptance.RecurringReminderWriteAcceptanceTest com.zero.v2.test/com.zero.v2.app.acceptance.ProviderAcceptanceRunner；红结果Tests run:2, Failures:2，绿同类3项及相关回归共19项通过。


### A8c.3a.3b.4b.2c.2 提醒统一队列边界／一次性及创建取消提交（2026-10-09，限定通过）

- 接续2c.1，按diagnosing-bugs实机红绿流程：新增3项隔离实际模块/Room红测均失败。一次性提醒在通知接缝中被改到未来，旧异步delete(id)仍删除新排程；恢复和cancel_reminder在正在处理的到期任务未释放时已完成，证明原restore锁和后台写入没有统一入口。原失败日志保存，不把审查推测当成设备复现。
- 统一taskMutex：Runtime/Worker的execute、恢复、set/cancel/modify_reminder、next_tick及UI重排的队列/数据库变更共享同一锁，周期推进复用此边界，取消传播保留。公共UI入口仍异步，由模块生命周期scope持有，但内部等待真实写入，错误留痕；FC创建/取消不再把“后台写入已启动”当作成功。创建先提交Room再发布内存队列和唤醒，取消先提交删除再移除队列并刷新。
- 一次性送达改为等待deleteDeliveredIfActive(id,expectedTriggerAt):Int，Room @Transaction条件删除只匹配active原排程，不误删同ID已重排记录。save/delete交易标注补齐，数据库结构不变。读nextRunTime使用PriorityBlockingQueue弱一致、线程安全遍历，所有模块内队列变更受共同Mutex约束；不承诺跨线程只读查询是线性一致快照或跨进程副作用恰好一次。
- 3项原红测转绿，另1项真实FC创建/取消回执立即对应已提交Room数据的用例通过；同轮周期保护/Hilt/Worker/广播/开机恢复19项回归，共23项实机通过（9.366s），其中4项新增。拒绝个人闹钟/通知与模型，使用隔离Room，未以这些用例替代真实生产Worker独立通知交付。此前生产联合通知证据另存，不冒充本轮复测。
- 初轮质量门发现行长和魔法数2项，修正后通过；随后保留UI异步入口错误处理及取消传播，以最终产物为准。最终qualityCheck＋assembleDebugAndroidTest通过（1m22s，530 tasks；含assembleDebug），日志quality-build-release.log名称仅表示最终门，不是release构建。修复debug APK SHA256 4b01e9980871c9abc35e984399e7f70d6a48f63b112f0def6618d7bdbf0ab3fa已保留数据ADB安装并核对手机摘要，归档artifacts/stability-20261009-a8reminder-queue/ZeroV2-a8reminder-queue-debug.apk。
- Main及主用户specialUse前台宿主恢复，foregroundId2003/isForeground=true，quick_check=ok；module_configs全表摘要、个人自动化分组及dynamic_modules state/crashCount与本片基线一致，本地8080转发保留。全部既有工作树改动保留，按开工源码另存本片差异，未reset/clean/提交交错内容；无缓存前缀、LLM路由、额度账本或锁屏设置变更。夹具无模型请求，不外推常规后台全部专用能力无网络。
- 防复发收敛：提醒生命周期操作共享同一串行入口，成功回执以已提交数据为准；删除与周期推进采用原排程条件，避免迟到写入破坏较新的状态。2c.2按上述接口/同进程范围收口，A8仍开放。下一2c.3继续独立生产Worker交付、精确闹钟权限降级及必要取消/进程恢复边界；A8c.4–8/A9/A10继续原序。通知副作用后的进程死亡、未知外部DAO写入和取消瞬间的事务/队列一致性尚不能由本轮保证。无UI视觉修改，不替代截图矩阵，旧被自动审批拒绝清理的临时副本保持原状态。
- 证据本片acceptance-index.json、red/green-native.log、quality-build[-final/-release].log、installed-sha.txt、before/after-device.json、host.txt、源码before/final及slice.diff。红命令为ProviderAcceptanceRunner中ReminderQueueAcceptanceTest，Tests run:3/Failures:3；最终该类4项加19项回归全部通过。


### A8c.3a.3b.4b.2c.3a 原Worker独立通知及取消恢复（2026-10-09，限定通过）

- 接续2c.2，新增WorkerReminderDeliveryAcceptanceTest。实际AutomationWorker.doWork→AlarmWorkExecution→AlarmDueDispatcher→实际ModuleLifecycleManager.safeCall→SystemEventModule；使用独立内存Room和测试WorkerFactory，ModuleInitializationGate由夹具完成，Runtime从未启动、无模型调用，个人系统闹钟服务被夹具拒绝。本片为验收补证，未发现新生产失败，未修改生产代码，因此不伪造红绿修复。
- 三项新实机用例通过：Worker独立发出一条真实系统通知并提交一次性删除，第二次扫描未增加通知；FC取消后恢复再扫描无复活/无通知；模块禁用后Worker返回failure且保留待处理行、无通知。按随机测试内容识别并清理本测试通知，未cancelAll或删除个人提醒。23项队列/周期/守卫/Worker/持久交接/Boot相关回归同轮通过，合计26项（12.03s）。
- 归因范围：证明原Worker的独立业务与通知出口，不证明生产WorkSpec物理队列在Runtime被抑制时的冷恢复。测试Worker由TestListenableWorkerBuilder及测试工厂创建，不声称正常Hilt/系统调度本轮端到端通过；先前生产联合冷恢复报告仍保留其Runtime提前送达78ms边界。
- qualityCheck＋assembleDebugAndroidTest通过（19s，530 tasks，包含assembleDebug）。仅更新测试控制器并ADB安装成功；主APK未变，手机摘要核对为4b01e9980871c9abc35e984399e7f70d6a48f63b112f0def6618d7bdbf0ab3fa，继续使用上片修复APK，不把测试控制器更新包装成新修复版本。
- 测试后正常Main恢复，user0宿主foregroundId2003/isForeground=true；quick_check=ok，module_configs摘要、个人自动化分组及dynamic_modules state/crashCount与本片基线一致。发现ADB本地8080反向转发缺失，已恢复，节点/health HTTP200；未发送模型测试请求、未改LLM/缓存前缀/额度账本。未修改精确闹钟或锁屏权限、不操作副用户95，全部既有工作树改动保留。
- 2c.3及A8仍开放：继续生产物理队列独立通知、精确权限降级及取消瞬间/副作用后进程死亡边界；A8c.4–8/A9/A10原序不变。源码现有canScheduleExactAlarms→setExactAndAllowWhileIdle间的权限竞态及外层静默catch属于待验证风险，不以源码推测称已复现。未进行全页UI视觉验收，旧被自动审批拒绝清理的临时副本保持原状态。
- 证据artifacts/stability-20261009-a8worker-reminder/{acceptance-index.json,native.log,quality-build.log,before-device.json,after-device.json,installed-sha.txt,host.txt,reverse.txt,WorkerReminderDeliveryAcceptanceTest.kt}。实机命令为ProviderAcceptanceRunner中该类＋ReminderQueue/RecurringReminderWrite/AlarmWorker/AlarmModuleGuard/DurableAlarmExecution/BootRecovery七类，OK(26 tests)。


### A8c.3a.3b.4b.2c.3b.1 精确闹钟权限竞态降级（2026-10-09，限定通过）

- 接续2c.3a，按diagnosing-bugs提取生产调度决策为AlarmSchedulingPolicy，第一版保留原canExact/精确或窗口提交/失败吞掉语义，再运行确定性故障测试。7项中4项失败：精确提交时SecurityException未降级、权限查询SecurityException未降级、降级次数为0、取消异常被吞。红XML及日志独立保存；属于生产共同策略的故障注入复现，不冒充手机实际撤销权限复现。
- 修订为权限查询或精确提交SecurityException时仅尝试一次窗口提交，窗口自身拒绝不二次提交；其他运行异常返回FAILED，取消及非运行异常继续传播。ExactAlarmScheduler实际调用该策略，沿用原RTC_WAKEUP、同一PendingIntent、4001/4002/4003身份和60秒请求窗口；不加重试循环/心跳、不把窗口降级承诺为准点。调度降级或失败使用ZeroAlarm系统日志输出action/mode/异常类，不包含提醒内容或凭据；准备PendingIntent/系统服务阶段失败同样留痕。未新增Room字段、Hilt循环依赖或模型请求。
- 修复后8项单测全绿，新增窗口自身拒绝只能提交一次的边界。debug-only访问桥只暴露共同策略给控制器；3项实机使用独立900823身份、独立action及一小时后触发时间，真正调用AlarmManager并读取系统注册window：精确分支window=0，拒绝/注入提交时撤销分支window非0；每次finally仅取消本测试闹钟。既有26项提醒/Worker/守卫/恢复同轮回归，共29项实机通过（12.331s）。手机实际精确权限及个人4001–4003身份未撤销/替换；未验证窗口闹钟实际到期送达或Doze时延。
- 中间质量门发现泛异常捕获及测试写法问题，修正后另遇一次Lint KaModule内部异常，保留失败日志；最终完整qualityCheck＋assembleDebugAndroidTest成功（2m55s，530 tasks，含assembleDebug）。不修改detekt基线/关闭Lint。最终修复APK SHA256 1491ddc178c32cc85f7ae6fe5db3663d013f35acfe0aed6d9bd7fdaa5d641c8d已保留数据ADB安装并核对手机摘要，归档artifacts/stability-20261009-a8alarm-fallback/ZeroV2-a8alarm-fallback-debug.apk，测试控制器安装成功。
- 正常Main/user0前台宿主恢复，foregroundId2003/isForeground=true，quick_check=ok；module_configs摘要、自动化status/scheduleType分组数量、动态模块state/crashCount与基线一致。DAILY到期数由0变1（诊断按观察时间计算），不能把该动态字段称为完全相等或清除个人到期任务。本地8080反向转发恢复；不改缓存前缀/模型路由/额度账本/锁屏或副用户95，全部既有工作树改动保留。旧被自动审批拒绝清理的临时副本保持原状态。
- 2c.3及A8仍开放：下一项仍需正常生产物理WorkManager队列的独立冷通知、手机真实精确权限撤销/恢复闭环和必要取消/进程死亡边界。共享策略＋实际系统注册只按上述范围收口，不替代这些端到端门。A8c.4–8/A9/A10继续原序；本片没有UI视觉修改。
- 证据本片acceptance-index.json、red-test.log/red-tests.xml、green-tests.xml、native.log、quality-build-final-device.log、安装及摘要、before/after-device.json、host/reverse、源码快照与相对开工源码slice.diff。红命令: :services:testDebugUnitTest --tests '*AlarmSchedulingPolicyTest'；红7/4、绿8/0，实机ProviderAcceptanceRunner八类OK(29 tests)。


### A8c.3a.3b.4b.2c.3c.1 冷进程原Worker独立交付／OFF残留报点修复（2026-10-09，限定通过）

- 从395项既有工作树改动接续，未reset/clean/提交。新增显式opt-in独立冷恢复控制器：仅user0，保留原agent_behavior完整JSON及updated_at在手机私有标记内，事务＋Mutex临时切OFF，75秒测试提醒，独立900824未知action闹钟20秒自动拉起正常Application。原提醒仍用RTC4001→原Receiver→DurableAlarmWorkQueue→正常Hilt创建原AutomationWorker→共享初始化Gate→实际safeCall/SystemEventModule→真实系统通知。没有测试WorkerFactory、内存WorkManager或debug桥接Worker；未改系统锁屏/精确权限/副用户95。
- 首轮明确SIGKILL后自动恢复，Runtime有loop_blocked_by_mode且无loop_started；通知1条、测试行已删除，但物理队列新增2条成功Work，原“必须1条”断言失败。第二条在约5秒后扫描旧AUTO_TICK，无第二条提醒通知。按diagnosing-bugs补隔离实际模块/Room红测：OFF且自动报点deadline已过时，提醒送达后nextRunTime仍出现新自动任务，1项失败；不把测试工厂与真实生产冷链证据混称。
- 根因是SystemEventModule的旧自动报点兜底未读已确认自主模式。修订通过AgentScheduleGateway读取配置owner；execute统一taskMutex内，OFF先清AUTO_TICK并将nextAutoTick置MAX，再正常处理显式提醒。SMART/ALWAYS_ON及既有保活策略不变，next_tick/提醒工具入口不删、不扩大为全局任务禁用。首轮新增构造依赖触发detekt参数数量门，收回到调度网关，未修改质量基线。旧SMART自动报点分支仍存在，其完整单owner/唤醒频率按后续A10另验，不能由本片宣称全部收敛。
- 最终qualityCheck＋assembleDebugAndroidTest通过（1m43s，530 tasks，含assembleDebug）。原红测转绿并与既有提醒/Worker/守卫/权限策略/Boot同轮30项实机回归通过（12.538s）。随后同一冷恢复流程及原断言重新验证1项通过：物理新Work仅1条、class=AutomationWorker/state=SUCCEEDED、通知1条、测试行不在，个人提醒完整实体摘要与当次seed基线一致，通知在Worker alarm_fired之后23ms，较计划时刻延迟173ms。
- 一次复验准备保护检查发现个人提醒临近，测试在改模式/写测试行前停止；保留guard失败日志，恢复正常Main后再执行，不调整个人提醒。首轮红链/终止/回执完整保留red-cold目录，seed被明确终止的日志不计成功测试。verify/finally和驱动独立cleanup恢复原JSON/updated_at，仅删除自己的测试行/通知/预唤醒PendingIntent；若有并发用户配置修改则拒绝覆盖。私有就绪/配置备份标记已清除，保留脱敏回执。
- 新修复APK SHA256 6fa01def1f185bc9be38063deb951088f095692ce3c54a65ab7f3a48354b6945已保留数据ADB安装并核对实装摘要；归档artifacts/stability-20261009-a8independent-cold/ZeroV2-a8independent-cold-debug.apk。Main和user0宿主foregroundId2003/isForeground=true恢复；SMART、module_configs摘要、自动化status/scheduleType分组数量、dynamic_modules state/crashCount与开工基线一致，quick_check=ok，本地8080转发恢复。动态due按时间变化不称逐值不变。无缓存前缀/模型路由/额度账本改动，夹具无模型请求；正常Application后台仍按原配置运行，不外推全部网络关闭。旧被自动审批拒绝清理的临时副本保持原状态。
- 归因边界：验证明确终止后的自动冷进程恢复及原生产闹钟/持久交接/Hilt Worker独立通知。冷进程由独立预唤醒在提醒前启动，提醒WorkSpec在恢复后由原Receiver入队；不证明WorkSpec已在终止前入队、JobScheduler唯一拉起进程、系统LMK或副作用后崩溃的恰好一次。2c.3及A8仍开放，真实权限撤销/恢复及这些剩余边界继续原序；A8c.4–8/A9/A10不跳过。本片无UI视觉修改。
- 证据本片acceptance-index.json、red-cold/*、red-queue-native.log、green-native.log、business-receipt.json、termination.json、observations.json、verify/cleanup-native.log、quality-build-gate.log、before/after-device.json、installed-sha/host/reverse、源码快照及slice.diff。命令run-independent.py驱动independentReminderPhase seed/verify/cleanup；冷verify1项、回归30项，cleanup1项为清理确认另列。


### A8c.3a.3b.4b.2c.3b.2 真实精确权限撤销／系统授权重排（2026-10-09，限定通过）

- 接续3c.1，新增显式opt-in ExactPermissionAcceptanceTest。实机原有user电池豁免与user0 SCHEDULE_EXACT_ALARM AppOp=default；电池豁免按应用ID共享，单改AppOp不能可靠制造不可用。驱动保存原状态，暂时移除共享豁免并将user0 AppOp设deny；未读写副用户私有资料、未操作其AppOp。临时OFF完整JSON/updated_at只保存在手机私有备份，Room事务＋Mutex，保护检查拒绝临近个人提醒。退出finally先恢复共享豁免，再恢复AppOp=default，并独立运行清理控制器。
- 第一次实际canScheduleExactAlarms=false、原ExactAlarmScheduler登记窗口成功；授权40秒等待失败，原状态已恢复。按diagnosing-bugs检查三候选：系统广播未到、监听未注册、重排未完成。实际记录有alarm_permission_changed: exact=true及重排，最近个人提醒nextRun=1791554400000被正确选中；失败是控制器错误要求共用4001身份继续指向更晚测试行。保留first-attempt证据，不称生产缺陷或修改生产代码。将本测试设为2分钟后，且仍要求个人活跃提醒均在3分钟保护范围之外，保证测试行确为最近项；质量门重跑通过。
- 修正后真实闭环1项通过：canExact由false变true，系统登记window由+1m28s663ms变0；grant通过主用户AppOps allow改变真实系统授权，控制器没有手工发送授权广播，生产已注册监听收到系统广播并自动重排。原4001/RTC身份沿用，未使用模拟AlarmManager/测试工厂或注入权限结果。prepare及cleanup各1项另列，不凑为业务验收数量；30项既有提醒/Worker/模式门/注册/Boot实机回归同轮通过。
- 一次重跑准备因个人提醒临近而提前拒绝，权限及配置未改变，恢复正常Main后等待正常处理再运行。每次清理只删本测试行，个人提醒完整实体摘要与当次prepare基线一致，私有配置备份/就绪标记已清除。共享电池豁免与主用户AppOp恢复原值，SMART模式及配置updated_at恢复。真实窗口到期时延/Doze仍未由本片测量，不以窗口注册冒充准点送达。
- 最终qualityCheck＋assembleDebugAndroidTest通过（36s，530 tasks，含assembleDebug）；前一轮43s也通过。只新增/修正测试控制器并ADB安装；生产源码及主APK未变，实装SHA256 6fa01def1f185bc9be38063deb951088f095692ce3c54a65ab7f3a48354b6945与上片修复一致。Main及user0宿主foregroundId2003/isForeground=true恢复，quick_check=ok，module_configs摘要、自动化status/scheduleType分组数量、动态模块state/crashCount与本片基线一致，本地8080转发恢复。全部既有工作树改动保留；无模型请求由测试发起、缓存前缀/路由/额度账本不变，不外推正常后台全网络关闭。旧被自动审批拒绝清理的临时副本保持原状态。
- 3b的真实撤销/授权重排闭环按上述设备条件收口；2c.3及A8仍开放。下一项继续已入队原WorkSpec跨终止与必要取消/副作用后恢复边界，随后回A8c.4–8/A9/A10；不复用本片为全页面UI验收。同步纠正计划首页A8c“待开始”及文档顶端过期摘要，保留历史证据。
- 官方规则依据 https://developer.android.com/about/versions/14/changes/schedule-exact-alarms ：电池豁免可影响有效精确闹钟能力。证据本片acceptance-index.json、business-receipt.json、platform-before/after.json、prepare/observe/cleanup-native.log、regression-native.log、first-attempt/*、personal-guard-native.log、quality-build-final.log、before/after-device.json、installed-sha/host/reverse及最终控制器源码。驱动run-permission.py，闭环1项＋回归30项，准备/清理2项另列。


### A8c.3a.3b.4b.2c.3c.2 已入队原WorkSpec跨明确终止恢复（2026-10-09，限定通过）

- 保留开工395项工作树改动，新增显式opt-in QueuedAlarmColdAcceptanceTest及run-queued.py，仅user0。个人活跃提醒若在3分钟保护窗口内或有未完成提醒Work则拒绝准备；原agent_behavior完整JSON/updated_at保存在手机私有标记，事务＋Mutex临时切OFF，不创建/改排个人提醒、不修改系统权限、锁屏或副用户95。
- 控制器使用原DurableAlarmWorkQueue实现及实际WorkManager实例入队原REMINDER_DUE（构造Provider提供真实manager，并非替换WorkerFactory）。原Application Configuration.Provider/Hilt创建原AutomationWorker，ProviderAcceptanceRunner跳过Application.onCreate，使共享初始化Gate待就绪。终止前必须确认RUNNING，并从真实androidx.work.workdb读出worker_class_name及state=1才发布就绪；没有内存队列、TestListenableWorkerBuilder、伪Worker或测试版业务扫描。
- UUID=6044e6c0-47ab-4bd6-9291-c38d3da1d9a2；驱动核对uid10025及准确PID后SIGKILL并确认原PID消失。另登记独立900825未知action预唤醒作为恢复兜底；实际原Worker扫描早于预唤醒计划时间约9.6秒，不能归因由该预唤醒启动，也不据此宣称JobScheduler唯一拉起进程。55秒观察期间未启动Main/instrumentation、未手工广播/强制Job或重新入队。正常Application在OFF下初始化原模块并完成共享Gate，物理队列恢复相同UUID，原class/state最终SUCCEEDED(2)。终止前attempt=1，最终attempt=2，保留真实次数，不声称只有一次Worker启动。
- verify1项通过：终止前后PID不同、原UUID不变、新增提醒Work仅此1条、真实模块守卫扫描成功，Runtime无loop_started且有loop_blocked_by_mode，个人提醒完整实体摘要不变。本项没有到期提醒，测试通知数0；证明已提交且等待初始化任务的持久恢复/扫描，不把空扫描算为通知交付，也不拼接上片75秒提醒为同一实验。seed故意终止的Process crashed不计成功测试；cleanup1项独立列出，只清理本标记/预唤醒、取消仍未完成的本UUID并恢复原配置，不prune其他WorkSpec。
- 最终qualityCheck＋:app:assembleDebugAndroidTest通过（43s，530 tasks，含assembleDebug），前轮全项目AndroidTest构建1m3s/751 tasks另列；最终清理逻辑已重编。30项既有权限策略/提醒/周期实体/Worker/守卫/物理交接/Boot实机回归通过。只新增测试代码并安装控制器，生产源码与主APK未变，实装SHA256=6fa01def1f185bc9be38063deb951088f095692ce3c54a65ab7f3a48354b6945沿用修复版。Main及user0宿主foregroundId2003恢复，SMART/配置摘要、自动化分组数量、动态模块state/crashCount与基线一致，quick_check=ok，本地8080转发恢复。测试未发模型请求，不改路由、稳定前缀、额度账本，不外推后台原应用完全无网络。
- 2c.3及A8仍开放。下一项验证取消及通知副作用后故障窗口：先独立复现当前notify→条件删除间恢复语义，再决定必要收敛，不凭WorkSpec成功推导任意副作用恰好一次。系统LMK、纯JobScheduler冷启动、真实Doze窗口送达未由本片测量；后续A8c.4–8/A9/A10按原序，UI矩阵未复用。旧被自动审批拒绝清理的临时副本保持原状态。
- 证据artifacts/stability-20261009-a8queued-cold：acceptance-index.json、business-receipt.json、termination.json、observations.json、seed/verify/cleanup-native.log、regression-native.log、quality-build-final.log、before/after-device.json、installed-sha/host/reverse、run-queued.py及最终源码快照。1业务验收＋30回归，清理1项单列。


### A8收敛S1a 同轮通知重放（2026-10-09，限定通过）

- 按《【A8收敛计划】剩余验收与交付.md》执行。新增真实Android通知出口重放测试，生产SystemEventModule＋隔离Room；反射只调用原私有通知出口，未修改个人权限、提醒或渠道，不称为真实SIGKILL/原Worker端到端中断。红3项中1项失败：同轮两次提交出现2条，另两项不同ID/周期保持独立。候选为时间ID、渠道或采样延迟，间隔100ms提交/真实列表确认时间ID是根因。
- 原出口改用zero_reminder:提醒ID:原触发时刻作为tag及固定id0，并setOnlyAlertOnce(true)；调用者传入任务原轮次。不同提醒/周期不合并，同轮在通知栏中更新。无Room结构/模型/缓存变更、不新增重试或心跳。旧三控制器清理同步用tag＋id。Android官方身份规则：https://developer.android.com/reference/android/app/NotificationManager 。用户清除后再提交仍可能新建通知，不宣称任意副作用恰好一次；声音/振动未实测，仅检查onlyAlertOnce标志。
- 最终qualityCheck＋:app:assembleDebugAndroidTest通过（4s/530 tasks；前轮2m42s另列，含assembleDebug）。实机33项通过：3通知重放＋30既有提醒/权限策略/Worker/守卫/Boot回归（15.937s）。修复APK已保留数据ADB安装，SHA256=29d3e513aaef2cd1f1e2203ef41dbe890cc3108eaebfa4d6a8afc3e794e1dcec，归档本片ZeroV2-a8notification-replay-debug.apk。user0正常Main/foregroundId2003、SMART和本地8080转发恢复；配置摘要、自动化分组数量、动态模块state/crashCount与基线一致，quick_check=ok。
- 测试未发送模型请求，不改路由/缓存稳定前缀/预算账本；不外推日常后台全部网络关闭，保留全部既有工作树与历史证据。S1a通过，S1b通知失败/条件提交失败、S1c真实故障窗口及S2–S6继续；A8未整体验收。实施进度、审计报告、验收计划和UI清单同步引用本片acceptance-index.json、红绿日志、构建/安装/摘要、before/after-device.json及slice.diff。


### A8收敛S1b.1 通知失败不伪送达（2026-10-09，限定通过）

- S1a收口后继续。真实原Worker＋模块守卫＋隔离Room，仅在隔离模块Context注入POST_NOTIFICATIONS拒绝：红2项全失败，原Worker仍返回success，原通知出口Unit吞失败后继续一次删除/周期推进。不是手机真实撤销权限测试，不改变个人权限/通知渠道。
- 通知出口现在返回提交结果；权限拒绝、系统通知关闭/渠道禁用、系统提交异常返回false，CancellationException继续传播。execute共同taskMutex内，失败条件提交markUndeliveredIfActive(id,原触发时刻)，标既有missed状态并同步刷新只读列表，然后error传播到原Worker failure。提醒实体保留、周期原规则/触发时刻保留，复用现有已错过/重新排程入口，不自动紧密重试；数据库写拒绝则原记录仍在且Worker失败。无Room字段/版本变更，无新增Hilt依赖或心跳。成功路径仍用S1a稳定轮次身份和原条件删除/推进。
- 首轮联合38项失败7项，原始日志保留first-regression-native.log：1项暴露通知拒绝异常被当作非核心模块崩溃禁用，另6项旧队列/周期夹具把PERMISSION_DENIED当成功交付。新增module-api的ModuleOperationRejectedException，执行守卫在safeCall内转明确FunctionResult.failure并记录，不增加崩溃计数/禁用；取消和未知异常仍遵循原守卫。只有可预期通知拒绝使用该类型，数据库拒绝仍真实失败。强化新测试：拒绝后模块仍operational且恢复空扫描可正常完成。旧6项夹具改为真实授权提交并按精确tag/id清理，原并发/实体断言不变；拒绝语义由新增测试单列。
- 新DAO事务仅匹配active原轮次，已重新排程或取消不覆写/复活。绿5项：一次拒绝与恢复不重复、周期拒绝原字段保持、系统服务提交异常、SQLite触发器拒绝missed提交、迟到失败不覆盖重排/删除。同轮S1a三通知与原30回归，共38项实机通过。渠道禁用分支实现但本片未改手机实际渠道；没有测notify后真实SIGKILL、提交中取消或恰好一次声音输出，不把隔离失败称为完整跨进程事务。
- 最终qualityCheck＋:app:assembleDebugAndroidTest通过，任务数/耗时以quality-build-gate.log为准；中间1m50s/530 tasks质量门不算最终守卫修订通过。初轮detekt ReturnCount/UseCheckOrError失败留存，修正实现、不改基线。APK已保留数据ADB安装并核对摘要26ac172591edd649824f0c9e6159bbf0f03f2fdb9cdab74ecc58811519b94d40，归档本片ZeroV2-a8notification-failure-debug.apk。正常Main/user0宿主foregroundId2003、SMART及本地8080转发恢复；配置摘要、自动化分组数量、动态模块state/crashCount与S1b开工基线一致，quick_check=ok。
- S1b开工前searx_search已DISABLED，不能把本片基线一致称为全部动态模块正常；具体恢复失败列入S3，证据before-device.json。全部工作树改动保留，模型路由/缓存前缀/预算账本未变，夹具不发模型请求。下一S1b.2/S1c补通知后条件提交失败、取消/真实故障恢复及必要Doze差额，再进入S2–S6；A8仍未整体验收。
- 证据artifacts/stability-20261009-a8notification-failure：acceptance-index.json、red/green-native.log、红构建及最终质量门、安装及实装摘要、before/after-device.json、host/reverse、slice.diff。5失败处理业务验收＋33回归分列。


### A8收敛S1b.2 通知后提交失败与新owner恢复（2026-10-09，限定通过）

- 按收敛索引接续，保留397项既有工作树改动。原Worker/实际模块守卫＋隔离内存Room，用SQLite触发器拒绝一次性DELETE或周期trigger_at更新；Android通知真实提交。失败后原记录完整保留、可见通知1条、Worker failure。移除本隔离触发器后新建生产SystemEventModule实例恢复：一次性原轮次按既有兼容规则转missed不补发，active周期按原相位推进到未来、全部其他字段保持。新owner空到期扫描不增加通知。首次Worker由TestListenableWorkerBuilder/夹具工厂创建，不称真实生产物理队列或Hilt冷Worker；新owner不是实际进程重启，未在个人库创建触发器。
- 红3项中1项失败：未授权通知导致的missed周期记录被新owner改回active，推进触发时刻。按diagnosing-bugs核对恢复忽略状态/失败未落库/旧实例三个候选，持久化missed已确认且新建owner，根因为restore只跳过done。改为仅恢复active；已有missed/done不重新入队，须用户显式重排。active周期相位及过期一次性转missed原规则不变；同步修正文内“周期永不missed”的过时注释。无Room结构/版本、工具协议、稳定前缀、模型路由、心跳或自主门槛变更。
- 三项新恢复实验＋此前38项回归，设备运行41项全部通过；完整qualityCheck＋:app:assembleDebugAndroidTest最终通过（含assembleDebug，耗时/530任务详见quality-build-final.log），前轮质量门另保留。未知SQLite故障仍走既有模块隔离，不将其改作预期通知拒绝；失败保留与新owner恢复的范围分列，不宣称任意通知/声音副作用恰好一次。
- 修复APK已保留数据ADB安装并核对手机SHA256 68d27c1f5fc6b0910b2878c0659cab2ba42ad5c2df9c09b619a71aa540ea4a29，归档本片ZeroV2-a8commit-recovery-debug.apk。正常Main/user0宿主foregroundId2003、SMART和本地8080转发恢复；配置摘要、自动化分组数量、动态模块state/crashCount与本片基线一致，quick_check=ok。searx_search在两端均DISABLED，S3开工前恢复差额仍开放，不称全部模块正常。测试不发模型请求，不改额度账本，保留个人数据/权限及副用户空间。
- S1b.2按隔离通知后事务故障/新owner范围收口。S1c仍须必要真实中断/Doze差额及父项证据归并，随后S2事件权限首次引导→S3能力模型/搜索恢复→S4外部连接→S5备份恢复→S6全页残留。A8未整体验收，不借用此片证明UI矩阵或实际SIGKILL。证据artifacts/stability-20261009-a8commit-recovery/acceptance-index.json、红绿设备日志、构建/安装/摘要、before/after-device.json、host/reverse、最终源码及slice.diff。


### A8收敛S2a 权限状态不误报与Termux:Boot入口（2026-10-09，限定通过）

- S1c通知后真实恢复1业务＋41回归收口；Doze窗口因DeviceIdleController屏幕前置条件未满足保留原门，按收敛索引推进独立S2。用真实PermissionCenterViewModel、Context权限查询、隔离内存Room trace/动态模块DAO；ProviderAcceptanceRunner不初始化MCP，真实MCP无activeClients，读取立即失败、不连接服务或保存配置。未修改个人授权/凭据。4项红中3项失败：SecurityException误报GRANTED、CancellationException吞掉、实机仅Termux安装却显示Boot安装。实际平台权限刷新项正常。按diagnosing-bugs核对异常分支、状态覆盖、包名混用候选，根因为catch返回true及Boot安装/入口两处查com.termux。
- 精确闹钟查询普通失败返回DENIED，取消继续传播；未增加重试或后台源。Termux工作站电池/OEM仍查com.termux，Boot单独查com.termux.boot，已装跳Boot详情、未装跳原安装指引；Manifest仅新增Boot包可见性声明。实机ADB确认Boot包未安装，新测试验证状态false与指引URL；已安装分支本片无实机证据。清理无障碍描述，把自主运行后台宿主与屏幕/手势权限区分；精确闹钟说明明确未授权窗口及休眠延期，不再承诺准点保障。未改Room结构、模型配置、稳定前缀、预算或两个保活模式。
- 原4项回归绿＋41项提醒/调度回归，实机45项通过；qualityCheck＋:app:assembleDebugAndroidTest最终通过（含assembleDebug，任务/耗时见日志）。修复APK保留数据ADB安装并核对摘要a3504c2bae0f59d6aa44e971481e2bf9aa62e32825da2ee09046a5a778c9e167，产物artifacts/stability-20261009-a8permissions-state/ZeroV2-a8permissions-state-debug.apk。Main、主用户宿主2003、SMART及8080连接恢复，配置/自动化分组/动态模块state与基线一致，quick_check=ok；searx_search既有DISABLED差额仍由S3处理。无测试模型请求，不动先前审批拒绝临时副本。
- S2a仅权限状态与入口Intent范围通过，不把ViewModel测试当生产页面授权往返或浅深色/200%矩阵。S2b接续权限中心共用ZeroPageHeader/ZeroCard、纵向大字号与错误可见；S2c云通道读取/保存/取消/草稿、实际首次入口核对。当前PermissionOnboardingScreen无导航调用，不以孤立组件声称首次引导已落地；沿实际系统权限唯一入口审计，最终S6再清理确认为过时的残留。随后S3模型能力/搜索恢复→S4外部连接→S5备份→S6父项对账。Doze窗口原门、S2其余及A8仍开放。
- 证据本片acceptance-index.json、red/green-native、quality-build-final、源码/Manifest快照、slice.diff、安装摘要、before/after-device及host/reverse。原红日志保留，不改测试断言或质量基线。

### 2026-10-10 权限页面目标启动契约（A8收敛S2b）

PermissionCenterScreen沿唯一permission_center入口使用ZeroPageHeader/ZeroCard。新增内部openPermissionDestination(Context, Intent, Intent?): Boolean：主目标成功即结束，RuntimeException失败最多尝试一个已有兜底；CancellationException传播，全部失败由页面Snackbar反馈。系统返回仍由ON_RESUME调用原refresh。PermissionCenterViewModel本片仅清理权限描述；Room、模块生命周期、模型/缓存及保活所有权不变。实际786dp/390dp分屏、正常/200%资源字号、浅深色/动态色共16条件及最后声明可达；11设备＋1矩阵与530任务质量门通过。证据artifacts/stability-20261010-a8permissions-ui/acceptance-index.json。云通道加载/保存/取消/草稿仍是S2c差额，独立PermissionOnboardingScreen没有实际导航调用；Doze与A8父项未整体通过。

### 2026-10-10 云通道配置与草稿owner（A8收敛S2c云区）

PermissionCenterViewModel通过Hilt PermissionCloudCall进入原McpClientModule/zero-cloud，ModuleLifecycleManager.safeCall守卫，沿用原MCP超时。内部PermissionCloudController独占配置、状态/登录Job、读代次和编辑版本；失败为未知/明确错误，严格解码并匹配写回执。SavedStateHandle保存原始白名单/上限/开关/dirty，迟到读/保存不能丢失新编辑，取消传播且释放忙态；登录显式触发、单Job/两分钟等待，无额外心跳。PermissionCenterScreen受控输入、可见反馈与返回草稿确认。远端字段写入并非事务，取消不承诺回滚。19＋2＋1设备、16真实资源/窗口条件及530任务质量门通过；证据artifacts/stability-20261010-a8cloud-config/acceptance-index.json。实际首次入口旧组件处置与A8父项未闭合；无Room结构、模型/缓存/保活owner变更。

### 2026-10-10 权限引导归并

无调用PermissionOnboardingScreen已移除；主导航chat起点、设置permission_center唯一owner与按需授权保留，无新增首启标记/配置。隔离新权限读状态的生产设置/权限页导航、拒绝显示与返回20项联合验收和530任务通过；不是新装OS授权弹窗验收。证据artifacts/stability-20261010-a8permission-entry/acceptance-index.json。




### A8收敛S3 端点交接并发修订（2026-10-10，隔离重叠挂载范围通过）

- 保留既有工作树，按S3只读差额推进。真实隔离Debian release通过原发布/校验/启动链，SDK仅对Android数字id的initialize延迟4秒并写本夹具标记；标记出现后触发同模块第二次挂载。原主APK实际红1项，两次均false（53.853秒），不是模拟网络或模型响应。初次测试包尚未安装完成产生ClassNotFoundException，单列controller-not-yet-installed.log，不算业务红；旧红日志保留。排序核对重复start替换进程/端点、SDK握手、HTTP/socket回退三个候选。Supervisor start每次stop旧进程，Android两条挂载链无互斥，可互相失效后触发回退与stop；串行后原用例通过，证明该并发缺陷存在，但不能把历史searx_search事件直接归因于此。
- McpClientModule为同模块mount/unmount完整交接增加Mutex，覆盖start、initialize、tools/list、注册和Room状态写入；不同模块仍独立。保留原restart与runtime端点发现语义、safeCall和发布安全规则。锁在实例内保留，避免有等待者时移除锁导致同id形成两把锁；不宣称发布/回滚/工具调用/外部supervisor操作/看门狗全部已串行。没有改稳定前缀、工具schema、模型默认、Room结构或预算账本。第一次拆私有长函数触发detekt，改为原函数内部持锁后原质量门通过；未改baseline或抑制配置。
- 首次复验HTTP通过、socket等待初始化标记超时；socket实际init_failed为Permission denied，与并发端点失效独立，原3项中1失败留在socket-denied-native.log。新增McpSupervisorSocketTransport经Termux supervisor连接其自身受管socket，模块业务仍在独立进程执行。代理只接受当前ACTIVE模块的准确socket端点、限定MCP方法/正整数id/1–3600000ms截止/1MiB请求，紧急停止拒绝；调用后检查运行代次，迟到结果不冒充成功；不持全局锁等待工具。初版300秒上限经配置对账发现会拒绝既有最长3600秒配置，已对齐原范围并补截止传递测试；原118.66秒/3项绿及初版APK留档，不混作最终产物。Android每次请求取消会关闭HTTP调用，服务器按自身截止停止等待，不宣称取消已执行的工具副作用。代理不开放任意socket地址；原直连类保留供已有调用，不在模块挂载中继续依赖跨UID直连。
- 实机HTTP/socket重叠挂载2项＋已有动态媒体发现/导出/回传/连续工具1项全部通过（120.454秒，无skip），两次实际初始化均完成、最终echo与ACTIVE验证；媒体保持稳定前缀和追加历史。代理安全4项＋release既有16项本地回归通过。完整qualityCheck＋assembleDebugAndroidTest通过1m 47s/530任务，含assembleDebug；新增常量检查失败修正，未修改检查门。新版supervisor沿既有bundle prepare/install/accept部署，升级会重启其受管进程，完成后核对实际恢复，不称升级完全不中断。原失败与最终报告分列。夹具使用唯一a8_mount_/a8_acceptance_身份，结束卸载并删自身动态模块行；停止的release历史、部署镜像和标记保留用于审计，不删除个人模块或资料。
- 修复APK已保留数据ADB安装并核对摘要bf7ac59ebaad913b59628bdde8103e91ba632a5cde7a3f17203e2cf725013f85，artifacts/stability-20261010-a8search-restore/ZeroV2-a8mount-handoff-debug.apk。主用户Main、foregroundId2003和8080恢复；行为/触发门/配置摘要/动态模块与基线一致，自动化分组一致，quick_check=ok。共享包代码安装同时作用于其他用户身份，测试只查询主用户，不称副用户业务已验证。模型测试请求0；个人searx_search仍DISABLED，不强制启用来造一致。
- S3父项未验收：历史socket失败仍未区分start失败和initialize失败，须对当前搜索源码/运行契约做隔离复验及错误分阶段取证，再核对模型profile和图片/视频/语音入口出口及模型页。固定后续为S3完整能力/模型→S4外部连接→S5备份事务→S6全页残留/证据映射；真实Doze只在mScreenOn=false/IDLE有效前置下补测。A8整体、A9连续Live与A10长期自主运行仍开放，不以本片后台测试代替UI矩阵或父门。
- 证据：本片acceptance-index.json、red/green-native.log、mount-http/socket.json、quality-build-final.log、slice.diff、before/after-device、installed-apk.log、after-host/reverse及只读历史报告。继续沿固定父项顺序，禁止重复扩展已通过子项或放宽原门。


### A8收敛S3 搜索实际恢复与本地开发连接（2026-10-10，运行切片通过）

- 按用户要求启动现有Qwen脚本，服务启动约82秒，原60秒等待先报超时，随后实时health=ok/model=Qwen3.5-9B、运行槽上下文24576确认；不把脚本超时当模型启动失败，不重复重启。ADB reverse8080连接已恢复，生产LocalModelAcceptanceTest显式configureLocal=true通过1项/3.213秒，覆盖文本/原生工具/结果续轮/超长输入拒绝与无云CHAT回退。本地CHAT为开发主力，专用embedding/媒体服务不声称零云费用，未改额度账本或稳定前缀。
- 新SearchRestoreAcceptanceTest使用原searx_search源码与配置，独立a8_search_ legacy身份，只启动/initialize/tools-list不搜索。红1项4.105秒：HTTP成功、socket失败；实际trace明确dyn_mount_start_failed/transport_mismatch，不是socket握手失败。排序核对默认入口、启动传输不一致和握手三个候选，SDK serve()固定HTTP、不消费runtime ZERO_MOD_SOCKET为当前根因；默认serve()现按显式环境选择serve_socket，无选择仍保留HTTP与自定义host/port。旧跨UID socket不受系统约束说明已修正。未改个人搜索源码、配置或凭据；已冻结release仍使用自身冻结SDK，不悄悄改其代码或解除SDK校验。
- SDK两项回归、qualityCheck530任务/2分50秒含assembleDebug通过，最终controller另编译32秒/240任务；原源码HTTP/socket与显式实际恢复2项12.857秒全绿。真实searx_search已ACTIVE并工具发现成功；刻意恢复该状态，其他模块/config摘要/SMART与触发门保持，quick_check=ok，宿主2003和本地8080恢复。夹具模块行、临时配置及自身源码清理，停止的legacy审计记录保留；未执行搜索查询，不称搜索服务外部质量验收。历史首个HTTP连接失败的触发不因本轮默认入口复现而确证。
- 修复APK保留数据实装、手机摘要一致：f7b1991f36bae61a90e8872bc5016620edb4d6812389a9d27ba4cce02c04d86e；产物artifacts/stability-20261010-a8s3-capabilities/ZeroV2-a8search-restore-debug.apk。本片证据search-first/green-native、search-traces、sdk-transport-tests、search-quality、local-native/connection、search-after-device/host、search-acceptance-index.json。继续S3模型profile草稿/错误/返回与统一页、模型能力/媒体入口出口对账，再S4外部连接→S5备份→S6全页证据；真实Doze及A8父门仍开放，不用本片工具发现代替模型页矩阵。


### A8收敛S3 模型草稿验证与返回保护（2026-10-10，组件与媒体契约范围通过）

- 真实ProfileEditorPage隔离回调先红1项2.765秒：回复上限输入invalid仍触发保存旧值。根因为toIntOrNull失败静默回退，并先执行密钥回调。新增ProfileEditorDraft：完整草稿先验证名称/模型/HTTP地址、有限温度、整数回复上限及小于上下文容量、启用思考时强度/预算；不偷偷钳位或保存旧数字。保留未启用思考参数、能力、优先级、上下文与keyRef。2项JVM覆盖非法/边界/上下文、未启用思考参数保留。
- 非密钥字段使用rememberSaveable(profile.id)，选中模型保存id；密钥只放易失remember，不写SavedState。失败显示通用错误、保留草稿，不展示异常中的凭据。密钥既有单独保存入口保留；全部保存先验证，若密钥成功而模型失败，明确部分保存。取消/硬件返回/编辑页返回共用草稿确认；核对实际模块详情顶栏发现可绕过，新增编辑页注册/卸载返回处理，顶栏也复用确认。统一ZeroCard/16dp内距、纵向全宽保存/取消与主题前景，去过时示例/自动钳位说明及标题emoji。未改变profile持久owner、稳定前缀、工具schema、Room或额度；SharedPreferences.apply不能确认磁盘故障，本次隔离回调失败不冒充真实持久失败或原子双存储。
- 第一轮质量门530任务/1分14秒，3实机流程9.119秒绿；追加8组实际资源字号1/2×浅深×动态/固定主题，首尾16截图并保存回调可达，合并自动媒体回退/动态模块音视频附件发现导出读取回传共6项201.055秒绿。实际独立release/MCP、四工具连续轮次稳定前缀和追加历史检查，使用本地Planner不是Qwen语义评测；云请求0。顶栏返回追加修订最终qualityCheck＋assembleDebugAndroidTest通过（任务与耗时见profile-parent-quality.log），顶栏测试两次红，第二次回调计数确认修改后立即返回读取上一帧dirty快照；改为返回时读取当前草稿。最终编辑器5项通过（含返回处理更新与卸载），最终耗时见profile-final-native.log，不把旧APK测试当新导航测试。
- 最新修复APK保留数据实装且SHA一致：b035ed5b3247c86e89d16aebb1250fa89a8e0bbb6ba53385b51e781e23bf8a6f；产物artifacts/stability-20261010-a8s3-capabilities/ZeroV2-a8s3-model-editor-debug.apk。实际窗口矩阵尺寸见a8-profile-editor/matrix.json，实际373dp窄窗口，已目检200%浅/深截图，思考说明outline对比不足修为onSurfaceVariant；不声称已验展开/分屏。Main/SMART/触发门/配置摘要/动态模块保持搜索恢复后基线，quick_check=ok，foregroundId2003与本地8080恢复，Qwen health=ok。搜索仍ACTIVE。工作树保留，未测试个人语义媒体服务、不改预算账本。
- S3父门保持开放：待补展开屏/分屏长字段、实际配置重建/进程恢复、持久写失败范围、模块中心/详情加载错误与统一视觉、完整能力媒体入口出口和既有证据逐项映射。下一步固定先S3剩余页面/能力对账，再S4外部连接→S5备份事务→S6全页清理及父门归并；真实Doze仍须有效IDLE，不重复已通过后台子片。A8/A9/A10未整体完成。证据profile-red/green/final-native、profile-quality/parent-quality、profile-media-final-native、profile-acceptance-index.json、matrix/截图、恢复/实装摘要。


### A8收敛S3 模块加载终态与窗口证据（2026-10-10，限定范围通过）

- 真实ModuleDetailViewModel、只请求唯一不存在的a8_missing_身份，原红1项0.931秒：moduleId仍空、production页面module=null只显示永久进度。根因getById=null直接return，没有状态终态。新增isLoading/loadError，开始记录请求身份，不存在结束加载并提供错误/重试；新加载取消旧Job，配置读取/迁移保存的CancellationException继续传播，销毁取消加载和runtime订阅。没有改个人模块配置写入策略或数据库结构。
- ModuleCenter/Detail接入ZeroPageHeader/ZeroCard，描述用onSurfaceVariant、去状态emoji和旧API Keys标题；模型编辑返回仍经过草稿守卫。2项最终实机4.605秒通过：不存在身份两次请求均结束，并以生产错误组件在浅/深200%资源字号触发真实重试回调。此前7项联合中6项通过，错误组件字号失败为夹具只设置第一轮、第二轮实测1.3；改为每轮设置/断言2.0，不改系统字号、不把该夹具问题称生产bug。保留module-detail-font-fixture-native.log。
- 模型编辑器补正确Surface，原孤立夹具外层未铺深色背景使标题继承错误默认前景；实际生产Scaffold不据此归因。修正后窄屏373dp八组16截图保存在profiles-narrow-final，联合回归5编辑器项均通过（含预置虚拟密钥+非法数字不触发密钥/模型回调）。用户展开设备后真实786dp八组25.743秒通过，补16截图，字号1/2×浅深×动态/固定主题不模拟宽度。只读RealMediaProviderAcceptanceTest不传runRealMedia，1项0.594秒，仅模型/能力/密钥存在布尔值盘点，未发provider请求；不是实际媒体服务质量验收。
- 媒体对账：ChatScreen的AUTO/NATIVE/TOOLS→Attachment.route；ChatBot图片显式IMAGE_INPUT+必要工具能力，无候选NATIVE明确失败、AUTO回退和不可用标注；MediaAnalysisService视频是三个采样帧交图片能力，不称原生完整视频或含声音；音频经已配置ASR转WAV；用户/LLM均可用media_analyze或attachment_export→发现动态工具，结果attachment_publish呈现，四连续工具前缀/追加历史见本轮已绿动态媒体报告。本地Qwen仍只声明CHAT/FUNCTION_CALLING，当前未加载视觉投影，云CHAT停用保持，不偷偷提升能力或开云回退。专用ASR/视觉等凭据存在不证明有效性。
- qualityCheck＋assembleDebugAndroidTest530任务/1分21秒通过；最后夹具controller另编译见module-detail-font-controller-build.log。APK保留数据已实装并核对摘要518def8c920afb59c3c038a8b762ae2b3dcfc8a962eb8fd9d0c0c3fb9a5128b4，artifacts/stability-20261010-a8s3-capabilities/ZeroV2-a8s3-capabilities-debug.apk；正常Main/SMART/触发门/配置/动态模块与profile切片后基线一致，quick_check=ok，foregroundId2003及本地8080恢复，Qwen health=ok，searx_search ACTIVE。源码工作树保留，预算账本/稳定前缀/个人权限不改。
- S3继续按有限差额推进，不标父门通过：实际分屏、模型草稿重建/返回完整路由、模块已加载页完整矩阵与凭据交叉入口，通用参数写失败/迟到保护仍需隔离证明。静态复核发现ModuleDetail flush失败可能清pending、运行配置先于持久确认变更、onConfigChanged未走safeCall，作为下一项候选，用隔离SQL拒绝复现后修订，不称已验证故障。随后固定S4外部连接→S5备份→S6全页证据；真实Doze仅有效IDLE前置补验。A8/A9/A10未整体通过，不把当前组件证据当所有页面验收。
- 证据module-detail-red/final-native、module-detail-quality、module-acceptance-index.json、窄/宽matrix与截图、media-inventory-native和a8-real-media-report.json、module-after-device/host及实装摘要。

### A8收敛S3 参数确认写入与跨模块草稿保护（2026-10-10，限定范围通过）

- 隔离内存Room＋真实ModuleDetailViewModel/模块管理器复现：SQL拒绝后运行count由3变7；编辑第一模块后切换，第二模块被写成7。修正夹具等待实际加载终态后两项仍红（2.789秒）。最早固定500ms准备断言失败不算SQL证据，随后两次误用runner的启动失败也单列保留；有效红为config-awaited-red-native-final.log。
- 草稿与防抖按moduleId持有，提交Mutex串行；后来编辑只取消待启动防抖、不取消已经提交的兄弟Job。从独立defaultConfig克隆运行配置，有限数字/整数/布尔验证后用saveConfirmed确认落库，再发布运行对象并safeCall onConfigChanged；拒绝不排入后台重试，失败保留草稿并显示重试保存。只移除仍与本批一致的草稿，旧完成不清新输入。公开Store原有带Scope构造仅供隔离夹具管理其生命周期，生产注入/后台写契约不变。
- 最终本片2项实机通过（SQL拒绝/解除后不自动写/手动重试、跨模块草稿隔离），完整qualityCheck及assembleDebugAndroidTest通过，日志分别config-green-native.log与config-green-quality.log。联合4项中本片2项及加载终态通过，错误页重试在锁屏焦点未找到按钮而失败，保留config-combined-locked-native.log，不冒充界面复验通过；上一片已有错误页通过证据仍独立。失败候选未应用/未回调；手动重试后运行/数据库7且回调一次；第二模块始终3。异常输入不输出原值或密钥。数据库已确认但模块应用失败明确标为部分成功，不能声称数据库与任意模块副作用全局原子，也不宣称覆盖其他配置owner或进程死亡草稿恢复。
- APK保留数据实装并核对SHA256=1ecf02e01c5731776f75e264f3aad81fc6356536cc3371ad7b81ecb872daaba0，ZeroV2-a8s3-config-confirmed-debug.apk；正常Main、SMART/触发门/配置摘要/动态模块与上一片一致，quick_check=ok，前台宿主2003/8080反向代理恢复，Qwen health=ok。未改稳定前缀、工具schema、模型选择、Room版本或预算账本，不发云端模型请求；共享APK代码作用于其他用户，业务验证仅user0。
- 下一步固定补S3模型草稿重建和真实分屏、已加载模块页/凭据入口对账；随后S4外部连接、S5备份和S6全页证据归并。真实Doze仍缺有效IDLE门。S3/A8父项仍开放，局部绿不代替剩余验收。


### A8收敛S3 模型草稿真实重建（2026-10-10，限定范围通过）

- debug非导出VisualAcceptanceActivity加入生产ProfileEditorPage，仅隔离回调，不连接个人模型/密钥存储。实际填回复上限1024和未保存虚拟密钥后Activity.recreate，等待新实例：1024恢复、未保存密钥为空，提交保存回调收到1024且不触发密钥写入。证明真实Activity重建/Compose SavedState范围，未假称OS杀进程、全NavHost路由恢复或完整媒体provider质量。
- 本片重建1项＋解锁后生产错误/重试组件200%资源字号1项通过，7.153秒，profile-restoration-native.log；上一片锁屏导致按钮不可达报告保留，不覆盖。首次debug夹具遗漏rememberScrollState import编译失败已修正；最终qualityCheck含assembleDebug及测试包530任务/51秒通过，profile-restoration-quality.log，未改检查门。没有改生产模型选择、缓存前缀或计数，也不发云端请求。
- APK保留数据已实装并核对SHA256=a8cd1f5c17eaea04702650b33c56cf5377d1f7ea4e1e52fe0186865cd895c879，ZeroV2-a8s3-draft-restored-debug.apk；SMART、触发门、配置摘要、动态模块与参数修复基线一致，quick_check=ok，正常Main、前台宿主2003、本地8080代理和Qwen health恢复。参数两项绿仍见config-green-native.log；完整质量门已经包含该生产修复。
- S3有限剩余固定为真实分屏模型页、已加载模块中心/详情完整视觉与长字段/凭据入口对账。已提出分屏窗口协作请求，未收到真实窗口证据前不标通过；后续S4外部连接、S5备份、S6全页归并和真实Doze依序，A8/A9/A10父项未通过。只读S4预审看到refresh缺finally与取消状态、增改弹窗保存后立即退出等候选，尚未复现/修订，不称为已确认运行故障。


### A8收敛S3 父项收口与最终实装（2026-10-10，按既定范围通过）

- 依据S3原范围归并已有证据：搜索原源码/SDK socket契约恢复与实际ACTIVE；HTTP/socket交接并发及动态工具媒体四轮见原报告；模型非法输入、失败保留、父/系统返回保护、真实Activity重建；参数SQL拒绝/手动重试/跨模块草稿保护；能力声明和图片/视频采样帧/音频ASR/动态工具输入出口对账。凭据owner与兼容回退静态对账见前节，不因无调用的旧llm_client分支删除仍有效的存储ref。
- 已加载生产ModuleCenterScreen/ModuleDetailScreen以唯一隔离模块/内存Room验证，不点击个人开关。最终786dp展开八条件24截图＋390dp真实分屏八条件24截图；模型页真实分屏八条件16截图，资源字号1/2×浅深×固定/动态主题。整页长名称/长描述可读、参数区可达。超长模型显示名/模型ID/端点，另含TTS长音色，在200%浅/深实际控件保存后完整保留且凭据引用不变；都是隔离回调，不把自由音色字符串称为provider支持。
- 新发现超长标题在固定TopAppBar高度下裁切，共享ZeroPageHeader改为单行省略，完整标题仍在正文/语义中；参数控件按moduleId+field.key绑定，防跨owner复用remember草稿。这两项生产改动之后重跑最终宽/分屏整页矩阵，不把旧截图作为新APK证据。首轮真实分屏模型保存通过、随后定位取消失败；截图显示控件可见，保存后重新确认Zero窗口焦点，原八条件全部通过，未改生产页尾布局。原失败及模块同轮通过报告module-profile-split-native.log保留，不掩盖。
- 最终宽屏3项29.338秒（整页矩阵、CHAT/TTS长字段、只读CHAT/ASR盘点），最终分屏2项118.458秒（模型/模块各八条件）均无skip通过。CHAT/ASR盘点与基线一致，专用provider未实请求；完整qualityCheck＋assembleDebugAndroidTest530任务/54秒通过，包含顶栏/参数生产修复、单测与assembleDebug，s3-matrix-final-quality.log；顶栏修订质量门41秒独立保留。
- 最终APKZeroV2-a8s3-final-debug.apk保留数据已安装，SHA256=1d8f4988886bf6750beb89022a7414215d497dc3aa5b48a0803085b2580008ef与手机base.apk一致；正常Main恢复全屏，SMART、触发门、配置摘要/动态模块与基线一致，quick_check=ok，前台宿主2003/8080反向代理及Qwen health正常。最终UI运行前后zero_llm_profiles摘要一致，本地CHAT主力和禁用云CHAT不变，不新增云端测试调用，不改稳定前缀、工具schema、Room版本或预算账本。共享APK影响其他用户代码，功能核验仍仅user0；不宣称整个数据库字节完全不变或云缓存命中比例已重测。
- S3按照能力/模型与页面范围收口；全NavHost/进程死亡连续恢复、Live动效及长周期自驱沿原A9/A10，不外推本片通过。异步偏好存储的断电耐久性、任意模块副作用全局原子与真实provider语义/成本质量不由UI夹具证明。下一片固定S4外部连接，随后S5备份、S6入口/全页归并；真实Doze仍须有效IDLE单列，不因S3完成关闭。A8父项仍进行中。
- 总证据索引s3-acceptance-index.json，最终截图loaded-wide-final/loaded-split-final/profile-split-final，以及原红/绿、质量门、恢复/实装摘要。资料和现有工作树保留。


### S3真实进程恢复补验与动态恢复差额（2026-10-10，父门仍开放）

- 补齐前序明确要求的配置/草稿进程恢复门，纠正前节提前收口。首轮instrumentation到达onStop与磁盘检查点，但SIGKILL同时导致系统清理instrumentation任务；profile-process-instrumentation-first-host.log、profile-process-seed-native.log保留，属于夹具边界，不计应用恢复失败/通过。
- 普通Activity首轮已有任务保留，但最初显式启动参数不同于launcher，在旧任务上增加主页面；改为相同MAIN/CATEGORY_LAUNCHER参数从干净任务复验。ADB首次编辑还发生输入焦点偏移，准备断言及时阻止进入SIGKILL；宿主增加逐字段焦点确认和全选替换，不调整生产表单。原日志profile-process-launcher-first-host.log、profile-process-input-first-host.log保留，不把输入准备失败计为业务恢复失败。
- 改用仅debug存在、android.permission.DUMP保护的独立恢复Activity，实际生产ProfileEditorPage及LlmProfileStore；nonce限定，偏好文件独立，不写个人模型或密钥。宿主核对UID10025/PID后SIGKILL普通应用进程，再通过已有launcher任务恢复页面。回复草稿1024、模型isolated实际恢复；终止前虚拟未保存密钥已非空，恢复后为空。另一新进程原生测试逐字段核对配置等值并删除本轮独立偏好文件，OK(1 test)；报告profile-process-recovery-report.json。不是Activity.recreate替代，不声称未落盘apply、断电或全NavHost连续恢复通过。
- 最终完整qualityCheck＋assembleDebugAndroidTest通过（profile-process-shell-quality.log）；本片新增是debug/androidTest夹具与宿主证据，不再改生产UI或缓存前缀。此前最终宽屏/真实分屏UI矩阵的生产实现不变；测试入口不会打包到release，debug入口不能由普通第三方应用调用。
- 最终恢复对账曾失败：qzone_post/vision_pro在supervisor ACTIVE但Room DISABLED，profile-process-record.log原失败保留。直接分支分别是start返回空、health_check_failed，根因尚未确认，不擅自归因模型/SDK/资源。经原safeCall路径明确恢复两模块，原生1项11.171秒通过；随后正常应用单次SIGKILL冷恢复PID20215→20665，动态状态和配置摘要与基线相同，baseline-cold-recovery-report.json。追加完整质量门530任务2m7秒通过。单次通过不能推翻此前间歇失败，高频中断/启动健康检查与控制面失联仍是S3下一差额，不将父门改绿。
- 测试包/force-stop生命周期后，原已选的Zero无障碍服务不再选中；按原基线恢复该可选服务并保持其他服务选择，使用合并manifest实际完整组件名。仍有独立前台宿主运行证据；不改锁屏、显示、系统字号或超时。accessibility-baseline-restored.json及最终状态单列。
- APK ZeroV2-a8s3-recovery-debug.apk保留数据安装，手机base.apk核对SHA256=484e90e3e6f04319daa43b4d4578480288305f5e25778b2637cf8835e1c81335。正常Main全屏、本地Qwen health/8080反向代理与前台宿主2003恢复；SMART、触发门、配置摘要和动态模块与上轮基线一致，quick_check=ok，个人zero_llm_profiles摘要不变。
- S3页面/配置与真实进程恢复范围通过，间歇动态恢复差额仍待收敛；总索引s3-acceptance-index.json更新，S3父门保持开放。接续固定先复现并收敛启动健康检查/控制面返回空，再S4外部连接→S5备份恢复→S6全页证据归并，真实Doze仍须有效IDLE，A8父项、A9与A10保持开放。不以本次恢复替代云缓存命中比例、当前全部provider网络语义、全局模块副作用原子或长期自驱验收。


### S3动态模块间歇启动收敛（2026-10-10，既定范围通过）

- 根因机制已复现：ReleaseStore.start持全局RLock执行健康探测，后到请求排队超过Android普通20秒read timeout后仍可能执行，导致调用方失败、运行端后来ACTIVE的状态错位。有效红start-queue-red-final.log为“Queued start outlives client timeout and mutates runtime later”；初次假后端deployment_id准备错误单列，不作业务证据。历史qzone_post空响应/vision_pro健康失败分支与此机制相容，但没有原始请求全链证据，不称两者仅有该原因。
- start改为非阻塞取得控制面锁，忙碌立即control_plane_busy且不入后续变更队列，紧停优先。Android有界等待45秒/500ms，null或health_check_failed仅重试一次/1秒，永久契约错误不重试；start独立read130秒/call140秒覆盖服务端最高120秒探测契约，普通控制仍20秒。OkHttp异步请求绑定协程取消，取消传播，不声称能回滚服务端已接受的启动或保证任意模块健康成功。Busy耗尽仍明确失败，不无限唤醒。
- Python发布协议17项全绿；Android重试/紧停/永久错误/取消3项全绿。最终qualityCheck＋assembleDebugAndroidTest530任务1m53秒通过；此前13项及2项detekt规范失败修正，失败日志保留，不调整门槛/抑制。生产修改限定zero_release.start及MCP启动请求/重试，发布、灰度、紧停、Room版本、稳定前缀、工具schema与模型选择保持。
- user0真实原生4项无skip通过，432.032秒：并发直接start明确busy，safeCall mount等待后Room ACTIVE且echo返回7；HTTP/socket初始化重叠均完成；媒体发现/导出/动态读取/发布与连续四轮工具历史通过。实际加载与已安装SDK四文件摘要以及Debian SDK身份均与APK一致；前段耗时含SDK安全部署/既有进程恢复，不把总时长当单次交互时延。测试仅独立a8_*模块，结束停止并删除本夹具Room条目，发布审计历史保留。
- 三轮普通应用UID10025核对后SIGKILL冷恢复全部通过，其中第二轮启动2秒后再中断一次；原四个ACTIVE模块无需手工重新启用，逐轮四个dyn_mounted成功时间均晚于该轮终止开始，原DISABLED与crashCount保持，SMART/触发门/配置摘要及个人模型偏好摘要一致，quick_check=ok，观察窗口无新增dyn_restore_failed。首次三轮仅对账持久状态，不能证明新进程挂载完成，cold-state-only-first-report.json单列；补强挂载成功时点后完整三轮复验，cold-recovery-report.json才是最终恢复门。三轮不外推长周期/所有进程故障。辅助诊断曾误用SQLite列及默认编码，日志保留后修正，不计应用故障，也未递归清理旧临时目录。
- APK ZeroV2-a8s3-start-reliability-debug.apk保留数据已实装，手机base.apk摘要一致SHA256=90c0b2c7096fc311798fb15b61ab8a4c573c78169159f17a4cc61f3b63431f32；正常Main、前台宿主2003、8080反向代理和本地Qwen health正常。未发云端模型验收请求，不声称已重新实测DeepSeek缓存命中比例；共享APK作用于其他用户代码，业务验收仅user0。工作树改动保留。
- S3前序页面/模型能力/参数与真实进程恢复证据沿原索引，间歇启动差额按本轮有界机制和实机回归收敛；S3既定范围通过。接续S4按凭据owner→配置确认/刷新取消→统一页面及真实窗口顺序，详见本目录next-s4-plan.md；再S5备份、S6全页归并。有效IDLE的Doze、A8/A9/A10父门继续开放，不删除历史失败或提前收口说明。证据artifacts/stability-20261010-a8s3-start-reliability/acceptance-index.json及原生/冷恢复/质量日志。


### S4外部连接凭据owner（2026-10-10，限定切片通过，S4父门进行中）

- UI添加/编辑与LLM mcp_add_server原先均按mcp_server:显示名建引用，两个同名连接会覆写同一凭据。先提取原规则并接入实际两个入口，credential-red.log两项一红，具体同名引用相等，非编译准备失败。
- 共用McpCredentialReference：新连接采用随机ID、密钥引用按不变ID；改名/留空不动既有引用；明确填新密钥时，独占旧ref沿用，共享旧ref仅为本连接分叉，不删除/覆写其他连接的旧凭据。还检查候选ref已被其他owner引用的情况。仅本次写入决策，不自动迁移全部历史密钥；旧共享记录的原始独立密钥若此前已被覆盖，无法据此恢复。
- 4项单测全绿：同名隔离、改名/留空/独占更新、共享旧ref的新旧值独立、其他owner占用候选引用。实际UI与LLM入口调用同一规则；没有向个人MCP服务发送测试请求或修改个人密钥。连接增改trace只记ID/指纹存在状态，移除URL与密钥ref，避免旧原始token引用或查询参数进入这些日志；不宣称完成全部网络日志脱敏审计。
- 首次完整质量门1m27秒/530任务通过；为最终日志与说明改动再次运行完整qualityCheck＋assembleDebugAndroidTest，credential-final-source-quality.log通过。此前一处MaxLineLength修正，失败日志保留，不调整门槛。S3启动生产修复与SDK资源保持，稳定前缀、工具schema、Room版本、CHAT主力及云CHAT禁用不改。
- 最终APK ZeroV2-a8s4-credentials-debug.apk保留数据实装，手机base.apk摘要一致SHA256=2e16b140a61f87a859608ae3861ff6f74a14a4fd043b6bc096fc2fc068547b33。安装后四原ACTIVE模块均有晚于安装开始的新dyn_mounted，SMART/触发门/配置/原动态状态及crashCount一致，quick_check=ok；个人连接偏好、加密密钥文件、模型偏好三份SHA256前后一致。首轮安装后手机锁屏休眠，Main onStart等待初始化后已非STARTED，前台宿主2003不存在，record-s4-host-first.log与host-first-observed.log保留。核对真实PIN输入页后使用此前用户授权解锁，正常Main重新进入STARTED后2003恢复；不称安装锁屏下自动宿主恢复已通过，package替换广播恢复作为新增A8保活边界跟踪。最终8080反向代理、本地Qwen health与原无障碍选择保持。未改锁屏/系统超时/显示设置，业务验收仍仅user0，现有工作树保留。
- S4仅凭据owner纵切片通过。下一片按既定next-s4-plan.md处理配置确认/保存失败保留草稿、连接发现失败/重试/取消及迟到保护，再统一生产页面并补真实窗口矩阵；McpClientModule.refreshAllTools当前捕获Exception、UI刷新缺finally/确认保存及表单立即关闭仍是未收敛候选，不称S4完成。S5备份、S6全页归并、有效IDLE的Doze及A8/A9/A10父门继续开放。
- 证据artifacts/stability-20261010-a8s4-credentials/acceptance-index.json、credential-red.log、credential-tests.xml、最终质量/安装/新挂载记录与个人配置摘要对账。S3已收口证据独立见stability-20261010-a8s3-start-reliability/acceptance-index.json，不用当前单测替代真实连接或UI验收。


### 锁屏升级自动保活修复（2026-10-10，实际包替换范围通过）

- 使用前一已交付APK、实际user0锁屏和保留数据安装复现；安装后12次/5秒采样均无foregroundId2003，全程不打开Main、不解锁，red-real-update.log明确失败。不是模拟广播或Activity启动代替包替换。旧BootReceiver只接BOOT_COMPLETED；Main的onStart在初始化完成时要求STARTED，因此锁屏更新后没有独立宿主请求。
- Manifest及原BootReceiver加入MY_PACKAGE_REPLACED，复用AutonomousBootHost确认模式/启用态守卫、已有specialUse前台宿主和原BOOT_RESTORE持久队列；新增host_restore_requested记录真实来源和是否请求，宿主失败仍不阻断提醒恢复。未新增心跳、定时重试、无障碍要求或第二调度器；未添加LOCKED_BOOT_COMPLETED绕过首次解锁。规则依据[Android前台服务后台启动限制官方文档](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)，实际平台结果单独验证。
- 隔离Room模式守卫实机1项0.369秒通过：SMART与ALWAYS_ON允许、OFF及关闭runtime不请求，原配置不改。完整qualityCheck＋assembleDebugAndroidTest530任务49秒通过。修复APK实际锁屏替换后宿主自动出现，全程没有Main启动/解锁；随后7轮约100秒以上锁屏观察宿主持续在前台、MY_PACKAGE_REPLACED请求时间与原持久恢复完成均晚于安装开始，原四个ACTIVE模块均有本轮新dyn_mounted，配置/触发门/动态状态/crashCount不变、quick_check=ok。不是仅看旧Room ACTIVE；green-report.json、stable-report.json与stable-0..6.json留证。
- 个人连接、加密密钥与模型偏好三份SHA256前后一致；原无障碍选择保持，不靠改变用户设置造通过。APK ZeroV2-locked-upgrade-debug.apk已保留数据安装、手机摘要一致SHA256=24050bca4e1b2fc1d98629712ad33530b3ec8171cdc566369bf60568cfa2def7。本轮未打开主页面以证明广播独立恢复，开发本地模型与缓存前缀未改，也未发云端模型验收请求。当前源码和407项已有工作树改动保留。
- 本片关闭前轮锁屏安装后的自动宿主恢复缺口；实际SMART平台门通过，ALWAYS_ON/OFF为隔离生产决策门，不外推首次解锁前、用户force-stop、任意OEM/长期驻留或真实Doze准点交付。接续S4配置确认、刷新失败/取消/迟到保护及现代化生产页面/窗口验收；S4/S5/S6及A8/A9/A10父门仍开放。证据artifacts/stability-20261010-a8locked-upgrade/acceptance-index.json。

### S4配置确认持久化（2026-10-10，存储切片通过，父门开放）

- 实机隔离生产McpServerStore复现拒绝写入仍发布新配置：store-red-native.log为1项1失败，明确Rejected preference write must report failure。使用独立内存偏好后端模拟commit先改内存、磁盘拒绝，不修改个人连接或密钥。
- upsert/remove/toggle/togglePermission串行计算候选，commit确认后才发布StateFlow；失败恢复偏好内存旧值并明确报错，不静默后台重试。恢复commit同样可能失败，不据此声称跨文件/断电原子性或磁盘故障修复。
- 同一实机原生测试修复后1项0.096秒通过：拒绝新增/更新、删除和开关保持旧状态，重建Store不读到未确认候选，允许写入后手动重试成功。qualityCheck＋assembleDebugAndroidTest530任务1m32秒通过；首轮detekt要求error()替代直接抛异常，已修正且原日志保留。实际修复APK保留数据安装成功。
- UI/LLM入口尚需承接写入失败和凭据补偿，刷新取消/迟到、草稿及生产页面窗口仍待验收；本片只确认存储边界，不关闭S4，也不将孤立存储测试算作完整页面通过。证据artifacts/stability-20261010-a8s4-confirmed/store-red-native.log、store-green-native.log、quality-store.log。接续先接入统一确认保存及错误状态，再刷新与页面验收。


### S4确认保存与失败反馈（2026-10-10，保存入口切片通过，S4父门开放）

- 接续存储红/绿实机证据，将配置候选与凭据更新共用McpServerStore.saveWithCredential；沿用已验收的连接ID owner和共享旧ref分叉规则，写入串行，配置拒绝时补偿本连接旧密钥或移除本轮新增密钥，不改其他owner。补偿失败明确报错，未实现或宣称多文件/断电全局原子事务。
- 审计实际SecureKeyStore发现put只记录commit布尔值、delete用apply：现改为同一writePreferenceConfirmed，拒绝落盘先恢复进程缓存并抛失败，revision在finally结束，删除同样确认；去掉put引用/长度日志。原加密方式、兼容回退与已有凭据引用保留。JVM固定后端模拟commit先改内存再磁盘失败，拒绝put/remove/新key与手动重试均通过；不把该后端称为真实Keystore故障实测。
- 生产McpServersViewModel拆为独立owner，批量校验名称/HTTP(S)/可选64hex指纹后在IO确认保存；失败表单不关闭，明确保留草稿和重试，确认成功才回调关闭。保存和发现成功分别处理，发现失败不撤销已保存配置。刷新进入safeCall、单Job及finally释放忙态；McpConnectionActions仅隔离发现边界，生产仍进入原模块。API Key遮罩且该字段仅remember，不存SavedState；非秘密字段rememberSaveable及可滚动长表单。当前不是完整现代化页面或真实窗口验收。
- LLM添加接入相同确认owner，在IO写入且存储/凭据拒绝返回FunctionResult.failure；删除确认保存后才注销该连接工具，拒绝删除不会提前清空。工具名称、参数schema、稳定前缀、模型主力、Room版本和调度预算未改。未宣称实际个人MCP网络服务或provider语义通过。
- 原生实机3项0.282秒通过：生产Store拒绝写入不发布/重载候选、旧/新增凭据补偿/其他owner不变、生产ViewModel非法输入/拒绝保存无成功回调/手动重试确认，以及隔离发现失败后已保存配置保持。发现边界使用固定隔离actions，不代替真实HTTP失败/取消/迟到门。完整qualityCheck＋assembleDebugAndroidTest最终530任务1m48秒通过；之前风格门失败留日志并修正，未改基线或抑制新增问题。证据final-native.log、confirmed-preference-tests.xml、quality-delivery.log。
- 最终APK ZeroV2-a8s4-confirmed-debug.apk保留数据安装，user0手机base.apk摘要一致SHA256=901924fee299550c987c5409d9fdb29fab22f4277610c2ad9c3a0486cf3b3d36。最后实装保持锁屏，无Main启动/解锁，MY_PACKAGE_REPLACED宿主请求和四个新dyn_mounted均晚于安装开始，前台宿主2003自动恢复；SMART/配置/触发门/动态状态及crashCount与前轮基线一致，个人连接偏好、加密密钥和模型偏好三份SHA256一致，原无障碍选择不变。本地8080反向代理与Qwen health=ok。证据acceptance-index.json与delivery-state-*；既有工作树改动全部保留。
- S4保存入口切片通过；剩余真实网络失败/无工具/部分成功/取消/迟到保护、高德既有查询参数、未保存返回/删除确认、统一生产页面和真实窄宽/200%字号/浅深窗口矩阵继续开放。S4、S5、S6、有效IDLE的Doze及A8/A9/A10父门不提前关闭。下一步固定先真实网络可复现门，再模块刷新报告与取消保护，再页面矩阵；顺序见next-s4-plan.md。

### S4真实网络基础红绿回归（2026-10-10，基础切片通过，扩展门接续）

- 手机原生TCP/HTTP隔离夹具直接调用生产McpClientModule与其mcp_refresh_tools声明handler：原代码3项3失败，分别HTTP500仍报告成功、删除连接后迟到清单重新注册、取消先清除已确认工具；red-native.log保留具体断言，非编译准备失败。
- 网络发现暂存候选，refreshMutex串行，确认连接Store generation后在同一写入锁中发布；每连接version包含同值重写/凭据变更，旧handler执行前也校验owner。取消传播至原OkHttp call，不清未变更连接的已确认工具；finally剔除已删除/变更owner。动态工具挂载独立保留，不刷新时清空dynamic activeClients。报告区分失败与无工具，FC失败可见，UI共用报告、取消按钮owner及refresh generation避免旧finally覆写新忙态。
- 完整qualityCheck＋assembleDebugAndroidTest530任务1m50秒通过；生产网络3项加原保存3项实机6项0.845秒通过。结构/风格门失败已修正，原日志留存；删除无调用togglePermission旧方法（全Kotlin检索仅自身声明），权限字段仍由upsert确认持久化。凭据owner及工具名称/schema未改。
- 此处只关闭三个原始网络缺口的基础回归；当前正在补验部分成功/空清单/tools-list失败、同值凭据版本变更、排队刷新与生产UI网络取消状态，随后统一页面与实际窗口。个人MCP服务未发测试请求，未用固定actions代替本片真实HTTP。父S4仍开放，不声称任意注册器副作用全局原子或取消可撤回已发布操作。证据artifacts/stability-20261010-a8s4-network/red-native.log、green-native.log、quality.log。

### S4真实网络失败、取消与迟到保护（2026-10-10，网络切片通过）

- 同一实机原生TCP/HTTP夹具扩展7项1.417秒全绿：initialize HTTP500返回失败；tools/list HTTP500与有效空清单分开；部分失败保留成功连接工具并报告失败；删除后迟到清单不注册；URL/名称/权限变更的旧结果丢弃，排队新刷新仅发布新owner；同配置/ref更新凭据也改变version、旧handler不向删除owner发tools/call；取消保留未变更已确认工具；生产ManagedMcpConnectionActions＋safeCall＋ViewModel真实网络失败可见，旧取消finally不能清除新刷新忙态。
- 初始red-native.log三项三红→green-native.log原网络3项和保存3项六绿→expanded-native.log扩展七绿；不删旧失败。生产源码完整qualityCheck530任务1m50秒通过，扩展控制器assembleDebugAndroidTest240任务16秒通过。fixture不onAwake、不启动自驱或个人动态模块操作，服务/连接/凭据/注册表独立；独立TrustConfig只存在隔离instrumentation进程，测试完关闭。不使用个人MCP服务、云LLM或改变用户密钥。
- 版本检查与发布同Store写入锁，网络不占用该锁；所有刷新共用原模块Mutex。取消可阻止未发布候选，不承诺撤回已发布注册或服务端已执行操作；未知注册器异常/模块副作用不称全局原子。FC名称/schema、缓存稳定前缀不改，动态挂载计数仍合计，外部刷新不清dyn客户端。新增声明只为内部报告，不新增LLM工具。
- S4网络切片既定范围通过，S4父门仍开放，接续统一生产页面、未保存返回/删除确认、安全端点参数与实际窄宽/分屏/资源字号/主题矩阵。最终个人摘要与保留数据交付对账在页面后归并。证据artifacts/stability-20261010-a8s4-network/expanded-native.log及quality.log。


### S4外部连接完整范围收口（2026-10-10，真实网络＋统一页面＋实机窗口）

- S4既定范围通过：承接锁屏升级保活、凭据owner与确认保存证据，本轮从实机原生HTTP三项三红修复发现失败误报、取消清空已确认工具、删除后迟到清单复活。最终生产网络8＋Store3＋ViewModel1共12项2.067秒通过；覆盖initialize/list HTTP500、部分失败、有效空清单、取消/新刷新竞争、排队刷新、URL/名称/权限及同值凭据版本变更、失效handler、管理探测/保存拒绝、删除后的旧编辑草稿不能重新插入或写密钥。网络服务/注册表/配置/凭据为隔离测试数据，不请求个人MCP服务或云模型。
- 同一Store确认generation/version并在写入锁中校验发布，刷新Mutex串行、候选发布前可取消，失败保留未变更已确认工具；已删除/变更owner清理。生产操作沿ManagedMcpConnectionActions与safeCall，不新增第二注册/配置owner。管理添加/删除确认保存后才报告成功/注销；保存成功与发现失败分开，探测不把tools/list失败称为有效空清单。工具名/schema、缓存稳定前缀和本地开发模型保持原约束。
- 生产McpServersScreen现由McpConnectionScreen.kt实现，导航入口不变；统一ZeroPageHeader/ZeroCard、成对主题色、可换行长名称/地址、刷新报告/错误与取消、保存忙态、删除确认。编辑页单一标题，滚动表单与固定保存/返回按钮；系统返回和页面返回共用未保存确认。API Key仅内存remember且遮罩，非秘密草稿可恢复，含查询/userinfo/fragment的URL草稿不进SavedState。四项生产组件实机测试通过：拒绝保存留草稿并手动重试、两种返回确认、Activity重建保留普通草稿但清未保存Key、敏感URL草稿不恢复。Activity重建不外推为进程死亡恢复。
- 端点处理集中于McpEndpoint：新高德密钥只在传输时编码添加，不覆盖其他查询参数，明确更新Key时从配置URL分离该参数；旧配置留空Key保持兼容，任意同名服务不按高德改写。列表不显示userinfo/query/fragment。Endpoint JVM3项、凭据引用4项与确认偏好写入1项通过；未全面迁移旧URL中的秘密或宣称个人高德服务连通。高德端点规则依据官方 https://lbs.amap.com/api/mcp-server/gettingstarted 。
- 最终真实Fold3展开窗口[786]dp与真实Samsung分屏[390]dp，分别完成100%/200%资源字号×浅深色×固定/动态主题8组，合计16组64张原生截图（列表、编辑首段、实际滚到末端、删除确认）。所有组保存与取消删除可达，末段说明在固定保存按钮上方，成对正文/辅助文字与Surface最小对比度5.54:1。第一轮截图发现重复标题已清理；初版仅部分说明可见的断言已强化并重跑。wide-native.log四项、split-native.log一项各自通过；旧截图/中断日志保留，不作为当前最终证据。不把分屏390dp声称外屏373dp，不把主题配色断言称全像素测量或全页完成。
- 最终qualityCheck＋assembleDebugAndroidTest530任务47s通过；未新增风格抑制/降低门槛。ZeroV2-a8s4-final-debug.apk保留数据安装，user0安装base.apk摘要一致：bb818f58745bd8e6675480d7f0073fecb9b8e3131d19e283df8051fe4fe2d6b3。最终安装保持锁屏，无Main启动或解锁，MY_PACKAGE_REPLACED前台宿主2003自动恢复，当前八个ACTIVE模块均有晚于本轮安装的新挂载；behavior/triggerGates/configHashes与历史基线一致，动态状态/crashCount与恢复后的当前基线一致、数据库quick_check=ok，个人连接/加密密钥/模型偏好三份SHA256一致，原无障碍选择不变。8080反向连接与本地Qwen health=ok；测试不切个人模型或付费测缓存命中。
- 证据统一：artifacts/stability-20261010-a8s4-ui/acceptance-index.json、final-native.log、wide-native.log、split-native.log、quality.log、wide/split矩阵与截图、delivery-state-*、delivery-install.log、delivery-personal-prefs.log、三份JVM XML；原红绿网络证据保留于artifacts/stability-20261010-a8s4-network。既有工作树改动全部保留，不混合提交。取消不保证撤回服务端已执行请求/已发布注册，跨文件断电与任意注册副作用不称全局原子。
- 历史模块基线不能直接复用：15:24生产ui_toggle明确启用bili_downloader/image_gen/mimo_tts/web_fetch，四项选择保留；16:06 qzone_post自动恢复收到resp=null并落DISABLED，supervisor却仍ACTIVE。第一次最终交付核验因此失败，first-delivery-*及delivery-audit/toggle-audit留证。qzone_post沿生产动态页重新启用，ui_toggle stopping=false ok=true、Room ACTIVE及新dyn_mounted确认；再冻结before-final-delivery并实装核对八模块，不覆盖用户新设置。这个新空回包/运行与挂载状态不一致缺口只完成恢复，根因未确认，不归因本轮刷新修订，也不声称S3启动可靠性已覆盖全部新条件。
- S4按既定外部连接范围验收收口。优先接续S3新增启动空回包差额：隔离真实异常响应及取消复现、沿原启动/注册owner有界对账，保护明确停用状态，再验证当前八模块冷恢复。随后S5备份恢复：审计唯一备份owner与真实进度/取消/错误，使用隔离库和输出文件验证数据保护，再统一页面与实际窗口；沿用A3已有事务证据，缺证部分补验。随后S6全入口/残留/父项证据对账；有效IDLE的Doze、A8/A9/A10/A11仍开放，不以S4关闭替代全应用验收。


### S3新增启动空回包差额收口；S5接续计划（2026-10-10）

- 实机隔离真实supervisor接受启动后，代理丢失成功正文：旧代码回归红（运行ACTIVE但挂载失败），不是由健康页推测根因。空正文/无效JSON/HTTP失败原先压为null，盲目重启并将Android状态置DISABLED，形成运行与挂载差异。历史qzone_post那次null的具体网络原因无法追溯，本轮确认并修订的是这个确定的处理缺口。
- Android启动沿原safeCall/模块锁增加请求ID，遇不确定回包查询GET start-status只读回执。supervisor仅接受本次请求且仍为同一pgid/endpoint、ACTIVE、enabled、活进程的回执，拒绝旧请求/明确停用/急停；回执仅内存保留，不保证supervisor重启后恢复。无法确认时返回可诊断的不确定失败，保留此前意图，不盲目重复启动、不采用health旧端点、不把未确认挂载声称成功。成功后仍须initialize与工具清单核验才注册。
- JVM回执4＋既有重试3共7通过；SDK33项中Windows原生进程7项跳过，另有真实Debian/Android成功空回包实机1项58.569s通过，工具echo可调用且仅一次start。第一次绿色实机尝试因supervisor不可用在发布前置失败，保留green-native.log；补齐ensureSupervisor前置后green-native-final.log通过，未放宽故障断言。
- qualityCheck＋assembleDebugAndroidTest 751任务49s通过（含各子项目测试APK构建，不等于751项测试）；补充JVM测试最初6项风格问题已修复，无新增抑制。锁屏保留数据实装APK e034e29af2794a168d03a6e68ad249a37c95f1ffa0fa07b89559af21afd33015，user0安装摘要相同；八个当前ACTIVE模块均新挂载且supervisor同为ACTIVE，配置/行为/触发门及个人连接/密钥/模型偏好摘要一致，SDK运行/安装资产四项摘要一致，Debian工具SDK一致，本地模型health=ok。
- 证据artifacts/stability-20261010-start-reconcile/acceptance-index.json、red-native/green-native-final、quality-final、receipt-unit、sdk-tests、delivery-*；工作树原改动保留。S3新增空回包差额按上述请求回执范围关闭，不外推任意网络隔离/全局副作用原子或云缓存比例。
- 下一片S5计划已落artifacts/stability-20261010-a8s5-backup/next-s5-plan.md：唯一备份owner、阶段/取消/错误与恢复确认→隔离库/文件保护回归→统一页面及真实宽/分屏矩阵→编译实装与记录。现有备份五域，附件实体/知识库/密钥/模块配置/图谱不包含；SAF提供方原子覆盖不保证。S5/S6、有效IDLE的Doze及A8/A9/A10/A11继续开放。


### S5备份恢复既定范围收口及S3补充修订（2026-10-10）

- 用户确认规则：合并恢复保留本机当前角色；本机无当前角色时采用备份中的当前角色，其他角色完整导入。同ID沿原合并规则更新内容，唯一当前角色的选择与五域恢复在同一Room事务中。早期冻结控制器缺新方法导致零项执行（role-red-no-case），未计入通过证据；换用含用例控制器在旧APK运行，role-red-native复现两个活动角色；最终回归验证本机优先、空库采用备份及后续写失败整体回滚，未恢复个人库。
- 唯一data_backup路由和DataExportViewModel操作owner沿原Hilt注入。真实阶段进度、重复操作保护、读取/准备取消、写入/事务阶段禁止UI取消、finally收尾和operationId迟到保护已实现。选文件后确认恢复，忙碌返回保护、错误重试及统计沿统一Zero页面/卡片主题。
- 修复已有数据遗漏：会话summary/summaryUpto、自动化execMode导出恢复，合并消息后的messageCount按实际行数重算；fields-red-native/count-red-native保留原生红证据。非法记录跳过统计红证据red-native保留。独立真实Room五域往返验证摘要位置、消息来源/附件引用元数据、记忆来源/状态/删除保护、角色及暂停任务REACT模式。取消/未来版本/写前取消保护原文件、同ID合并/本机独有保留均通过。
- S3补充：ok=true缺有效endpoint或ok类型错误也必须按不确定回包走请求ID回执，不当确定成功；回执4＋重试3项JVM全绿。动态模块ACTIVE徽章改“已启用”，明确Room意图不是当前运行/挂载证明。真实成功正文丢失仍只start一次并注册可调用工具；历史qzone_post那次空回包确切传输原因无法追溯，不声称任意控制面故障均修复。
- 最终原生门final-data-start-native-role.log共12项通过：备份数据库8、操作3、真实启动丢回包1，61.162s，采用最终角色修订APK。quality-role-final.log质量检查及测试APK构建530任务通过；加强角色事务回滚夹具后role-rollback-controller-build.log再次构建240任务通过，任务数不是测试数。临时QQ启用态恢复另有显式参数保护的生产owner实机1项通过（qq-resume-native，2.513s），恢复辅助测试不是默认回归；追加测试控制器构建recovery-controller-build通过。
- 真实页面门：wide-native-final.log 2项（真实SAF两种选择器取消＋展开屏矩阵）；split-native-final.log为8次分组实机调用，每次1项。实际786dp/390dp窗口各8组浅深色×固定/动态色×100%/200%资源字号；每组空闲/确认/读取/提交/错误/完成/尾页7张，共112截图。确认、取消、提交保护、忙碌返回和尾部可达均通过，配对正文/卡片对比度≥4.5。字号由测试宿主覆盖，未改系统字号/锁屏；分屏是Samsung真实窗口，不是外屏或模拟窄宽。
- 页面验收APK7c02ef2813556996e0c19f8dac9e0fecdd838df8435efe7ed3fc84a04ff6596e另存ZeroV2-a8s5-ui-tested-debug.apk；之后角色导入/模块状态标签修订未改变DataExportScreen源，数据门在最终APK另验，不混淆产物。热状态4暂停UI，后续短组等待机身≤40.5℃且热状态≤2；既有授权下临时停止Termux并暂停user0测试宿主，随后恢复；未动user95宿主，最终前台1337及八模块已核对。本轮无性能测量，不改变资源门，另有四个QQ残留原生进程临时挂起并按PID/启动时间恢复；恢复后确认旧代仍占用CPU，依既有临时停止授权按同一身份结束旧代，仅保留已恢复的当前模块进程。模块停止后残留高CPU作为S6候选，保留降温及失败日志。
- 最终ZeroV2-a8s5-final-debug.apk SHA256 `5db26ca500bb8f897d577f2a999d9f76cd683804b83e880652fc8d15ef56f11a`，锁屏adb install -r成功，user0实际base.apk摘要一致。八个原ACTIVE模块运行ACTIVE且本次安装后均新挂载、宿主请求新鲜；个人三偏好、配置/行为/触发门/模块意图摘要保持一致；运行/安装SDK四资产及Debian工具摘要相同，本地Qwen health=ok且ADB8080反向连接保留。证据artifacts/stability-20261010-a8s5-backup/acceptance-index.json及delivery-install.log；首次锁屏比对发现临时QQ暂停期间Android意图被健康检查置DISABLED，不能通过；保留delivery-first-*，沿生产mount owner恢复ACTIVE并重做安装核验，未直接改个人数据库、未清数据或重置工作树。
- 边界：五域不含附件实体/知识库/密钥/模块配置/图谱/完整提取作业链。32MiB导出检查发生在完整快照分配后，不是全流程内存上限；SAF失败可能留下部分文件，不保证任意提供方原子覆盖。旧备份缺失字段不能重建；向量不在备份内，导入后立即自动重建仍缺证。真实SAF取消和生产确认分别验收，不声称完成选文件到个人库恢复；未测云缓存比例，未改稳定提示前缀或用户模型配置。
- S5在上述既定范围关闭。S6只读预审列出22入口（18显式路由＋4底部根页），备份路由/owner唯一；接续冻结S0–S5索引→父门/残留对账→仅补缺证。前次联测发布测试模块无响应发生于故障注入前，final-data-start-first.log保留；同产物重测通过，确切原因未知，单列S6控制面差额，不归因温度。最终安装已通过挂载/配置/摘要后一次health查询连接提前关闭（delivery-health-first），保留失败；以本次安装的host_restore_requested时间作更严格新鲜度界重新只读核验，不重复安装，SDK身份及八模块运行最终通过。有效IDLE Doze、S6及A8/A9/A10/A11开放，见next-s6-plan.md和s6-route-owner-preaudit.json。


### S6a QQ进程归属与高CPU诱发修订（2026-10-10，限定通过）

- 实机闲置QQ约3% CPU；真实stop返回ok后，两项事先证明归属qqbot的旧子进程仍活、各约99% CPU（qq-stop-red）。PRoot内外会话不同，原Runtime._kill直接KILL外层组，遗漏内层tracee；不是据进程名猜测归属。独立会话最小原生夹具4项先红3项（停用残留、死根恢复、崩溃先回调），身份变化/无关进程保护项通过。
- ProcessRuntime统一按内核PID启动身份和UID保存后代归属：启动确认/停止重扫＋单一共享0.5s跟踪，退出进程记录被裁剪，仅归属变化才写journal。先TERM/KILL工作进程，确认退出再停PRoot与外层；崩溃先清旧代再回调，恢复可清理已登记的死根后代，逐次验证身份，拒绝无关或PID复用。Supervisor退出沿同一runtime owner，保留enabled意图；超时/服务受控结束也采用归属树清理。尚未观察/登记且已脱离根的进程不声称可识别；同UID不是完整沙箱。
- ownership-native-green四项Linux原生通过，qq-stop-green当前真实QQ停止后旧代存活数0，原SDK路径恢复成功。quality-qq 530任务/1m9s通过，27项SDK脚本Windows4项原生跳过，另有四项设备原生补验；跳过不算通过。修订APK b0711f4d6c7ced3626466d7a822510aed91f1259e077351d0d3787caf0efea0f实装，SDK运行资产与源码一致，行为/触发门/个人模块配置/模块意图与开工快照相同；未改提示前缀或云模型配置。
- 证据 artifacts/stability-20261010-a8s6-qq-control/，未整体关闭S6/A8；继续S6b控制面异常空断差额。真实HTTP处理器夹具3项红：两项RemoteDisconnected、一项HTTP/Socket错误契约不一致；历史瞬断的具体触发未确定。QQ消息端到端、长期资源/Doze仍非本片证据。


### S6b 控制面异常回包及S6证据冻结（2026-10-10，限定通过）

- 生产HTTP Handler提取夹具先红：内部RuntimeError使GET/POST直接RemoteDisconnected；Socket返回原始异常文本且契约不同。新增control_route/control_error，未开始响应时返回脱敏JSON 500及error_type/operation；POST结果统一uncertain=true，不伪装成已确定未执行。已开始响应/对端断开时关闭连接，不追加第二组响应；不自动重试副作用。HTTP与Socket一致，日志不含原异常正文/请求正文/查询值。此处证明异常处理机制，不证明历史每次瞬断均由同一异常触发。
- 最新Linux原生7项通过（进程归属4＋控制面3，8.360s），Windows共30项、4项仅Linux跳过，实际26项通过。qualityCheck＋assembleDebugAndroidTest 530任务/41s通过，包含assembleDebug；最终APK上真实Room/操作取消/生产启动成功正文丢失共12项通过（36.422s），测试隔离库，不恢复个人资料。
- 最终QQ停止后旧代存活0；新模块沿生产SDK启动恢复。原红证据qq-stop-red.log保留2项归属明确的残留各99.47/98.97% CPU；最初同名qq-stop-red.json被绿色复验覆盖，不再用该JSON作红证据，后续脚本以label分别输出。最终活动QQ短时采样38.5/43.9/32.6% CPU，不能称长期低CPU或消息端到端验收；本轮修复残留诱发的多核高占用，活动资源继续跟踪。
- 实装后首次health仍提前关闭，保留control-live-first-after-install.log及control-first-state；发生在恢复窗口，不能凭时序断言全部根因。之后50/50健康请求监督PID不变，2/2真实非法读取参数/非法proc PID请求返回预期脱敏500，随后health正常；非法PID未执行kill。有限连续请求不是长期网络稳定性或无升级空窗证明。
- ZeroV2-s6-final-debug.apk SHA256 `de52201001f12c53f72bc65945d1af0bd74cc8f92f062ae6fd13973667594a1d` 已adb install -r成功，user0 base.apk一致；运行/安装SDK四资产与当前源码及Debian工具一致。八个原ACTIVE模块运行ACTIVE且安装恢复后新挂载，配置/行为/触发门/模块意图、三项个人偏好文件摘要一致，quick_check=ok，本地Qwen health=ok与ADB8080连接保留。未改稳定提示前缀、未测云缓存比例、未调用付费模型、未清个人库/工作树或改变副用户配置。
- S6证据冻结：s6-route-parent-audit.json列18显式路由＋4根页，无重复路由；备份入口/owner唯一，自动化创建沿列表父owner，无旧PermissionOnboardingScreen文件。90份既有A8验收索引按原范围和摘要冻结，逐项记录源码仍匹配或已变化；历史索引不是本轮重新运行。更新UI台账已取得的权限页/云区与MCP页状态，未把所有页改绿。
- 有限后续：S6c按22入口逐页映射正文/弹窗/首尾/恢复证据，仅补当前确切缺证或源码变化；S1有效IDLE Doze送达独立保留；A9连续Live/A10长周期/A11最终全回归保持原门。S6a/b限定通过，S6/A8父门仍开放。证据 artifacts/stability-20261010-a8s6-qq-control/acceptance-index.json、s6-route-parent-audit.json、红绿日志及delivery-result.json。


### S6c.1 活动QQ重复冷启动、升级恢复就绪与逐页证据对账（2026-10-10）

- 根因及范围：真实QQ启动曲线在15秒出现232.8%多核占用，45–75秒约37–45%；热点为QQ utility进程MSFNG线程，Python父进程占用低。更久闲置可自然下降，不能据此认定永久忙循环。另确认ReleaseStore恢复已启动模块，Android自动挂载又无条件stop/start，使QQ重复冷启动；未改手机QQ shim/core或个人配置。
- 自动恢复修订：仅自动挂载显式reuse_active=true。复用必须同时通过完整release配置、实际PID启动身份、入口源码摘要、release检查、RPC工具健康及同一活动generation校验；显式重启仍重启，不匹配则正常启动。三次真实自动挂载QQ均保持PID8301及一致请求回执，所有活动模块PID不变；最终12项instrumentation前后活动PID亦保持。入口摘要不覆盖所有外部import依赖，不能外推成全依赖热更新校验。
- 升级恢复修订：控制面先监听再恢复，health明确starting/recovering/ready/failed；未就绪非health调用返回503 supervisor_not_ready、uncertain=false，不执行操作。Android区分alive/ready；恢复期部分状态不作为权威CRASHED，不误停用。旧owner以真实UID、完整脚本路径、PID启动身份等待实际退出，不能以health失联代替退出；使用已校验staging helper兼容旧SDK升级。最终升级有23个恢复中采样，拒绝调用保护实测503，仍捕获1次旧新监听停启边界EOF，零空窗及全部历史瞬断原因不作通过结论。
- 最终验证：Windows发现38项、9项Linux条件跳过；Debian隔离SDK15项通过；就绪/状态JVM6项通过；最终APK真实Room备份/取消/迟到及启动丢回包12项通过（41.149s）。qualityCheck、assembleDebug及测试APK构建530任务通过（2m，任务数不是测试数）。红证据startup-red、runtime-readiness-red、reattach-red，绿证据native-final、sdk-windows、quality-final、final-data-start-native均独立归档。
- 交付保护：最终APK SHA256 421e2bc75d44ab33cfb68e44992baa0c32ccfa38d211da3b6ed9e5d143a98c13，已通过ADB覆盖安装并核对user0实际base.apk摘要；测试后再次核对SDK四文件、原八模块ACTIVE及新挂载、个人配置和偏好摘要一致、前台保活2003、本地模型健康。最终QQ在线无错误，闲置三采样2.8/2.4/4.2%，未发QQ消息；短采样不是长期资源门。未调用付费模型，未改稳定提示前缀或缓存机制。
- S6c逐页父项：22入口已对账，17页仅匹配当前Screen源码与限定历史证据，不能据此宣称共享主题、弹窗、首尾、窗口及恢复全通过。live_session、library、automation、modules、trigger_settings五入口仍需当前源绑定/差额验收；modules已有S3真实窗口证据，缺绑定不等于从未测试。报点设置页确认独立旧页头/卡片、搜索草稿未保存、配置状态误写“运行中”和横向大字号拥挤，本片只登记，尚未修订。
- 后续固定顺序：S6c.2先修报点页统一组件、真实状态语义、草稿与大字号布局，完成编译/回归/所需真实窗口；S6c.3归并根页、模块、Live的当前源及产物证据，仅补确切缺证，不全量重复17页旧矩阵；之后逐页核对正文/弹窗/首尾/恢复父门。有效IDLE Doze及A9连续Live、A10长周期自主、A11最终全回归独立保留。S6/A8父门未验收。
- 本片证据与最终APK：artifacts/stability-20261010-a8s6c-active-recovery/acceptance-index.json、s6c-page-coverage.json、upgrade-summary.json、reattach-live.json、delivery-result.json、ZeroV2-s6c-final-debug.apk。


### S6c.2 报点设置页修订验收与S6c.3五页证据差额归并（2026-10-10）

- 本片对象为既有trigger_settings「事件感知」路由，仍使用TriggerSettingsViewModel、TriggerWhitelistStore及SystemEventModule提醒owner；未增加设置入口或新写入链。页头采用ZeroPageHeader，配置卡和提醒卡采用ZeroCard及主题配对色；配置状态纵向分行，提醒操作独立一行，应用行单一Checkbox语义和至少56dp高度，长标签允许换行，移除重复推荐点击控件。旧说明中的“模块管理”改为现有“能力与模型”入口。
- 文案修复：配置、白名单不能证明服务健康，原“运行中”改“已配置 · N个应用”；另明确“待配置 · 尚未选择应用”和“未启用 · 通知感知已关闭”。说明实际接入取决于系统通知权限及后台状态，不伪造运行结论；通知源开关进入/恢复页面时从原配置owner刷新，并订阅StateFlow重组。旧逻辑两项JVM先红，修订后两项通过。
- 草稿与操作：搜索改rememberSaveable；抽取实际生产TriggerSettingsContent用于隔离验收，真实Activity重建后搜索内容恢复，筛选目标可见，整行单次选择只回调一次，提醒删除/重新安排走既有owner。首轮脚本误匹配搜索框的相同文字导致toggle断言失败，修正为唯一包名目标；trigger-native-first.log是脚本定位失败，不是生产功能红证据。
- 实机证据：trigger-native-final.log在最终应用APK上一个测试方法内完成786dp展开屏八组（浅/深×固定/动态×100%/200%资源字号），每组配置态、草稿恢复、停用态三图，共24图；23.477s通过。trigger-native-split.log补真实390dp multiWindow、200%固定浅色一组，操作及Activity重建均通过，48.213s含等待用户恢复分屏，共3图。截图确认字色和背景可读、配置说明换行及提醒操作可达；分屏首图右侧仍是系统配对应用选择器，不宣称全程计算器已就绪。未改系统字号/锁屏设置；此处不替代进程死亡或全部提醒生命周期/权限丢失矩阵。
- S6c.3五页对账：trigger_settings已绑定本片源和产物；library/automation由root-navigation-native.log补当前真实MainActivity启动、资料与工作区（包含系统状态）、外观返回和偏好不变，1项7.877s通过、4张截图；modules由module-loaded-native.log补当前已加载中心/详情八组786dp主题/字号，1项13.271s通过、24图，隔离长字段且未动个人开关。历史A7矩阵与S3真实分屏/错误/配置证据单独引用，不以新宽屏结果替代。live_session仍缺当前运行绑定，保留A9连续动效/媒体/退后台/恢复门。本轮由17/22当前页面源码绑定推进到21/22，绑定不等于21页父门全通过。
- 质量及交付：qualityCheck、assembleDebug及测试APK构建通过，530任务、59s；增加窄屏测试参数后的控制器编译240任务、5s通过，应用APK未变。最终APK SHA256 83c305b86759e19223039164bdff0e68bd3c24df036fdd3a460915ca4c8d89c8，ADB覆盖安装成功，user0实际base.apk摘要一致。测试后SDK四文件与源一致，原八模块ACTIVE且挂载刷新；个人配置/密钥/profile偏好摘要与上片冻结基线一致，前台保活2003及本地模型health正常。隔离页面不调用provider，未改提示前缀/缓存链；真实主页面后台流量未做成本计量，不声称绝对零后台token。
- 有限后续：S6c.2按本片UI/配置文案/Activity草稿及必要宽窄窗口范围通过；S6c.3继续逐页核对共享依赖、弹窗/首尾及恢复的确切差额，Live归A9独立门，不能靠源码绑定计数关闭S6/A8。有效IDLE Doze、QQ长期资源/停启空窗、A10长周期自主及A11最终全回归保持既有边界。
- 证据目录：artifacts/stability-20261010-a8s6c2-trigger-ui/acceptance-index.json、five-page-parent-audit.json、device-fixture、device-followup、delivery-result.json及ZeroV2-s6c2-trigger-debug.apk；红/失败尝试完整保留，未清理既有工作树。


## 2026-10-10 S6c.3父项差额对账与A9 Live首轮验收

- S6c.3逐页冻结22入口正文、弹窗/Sheet、首尾、窗口、恢复及已记录共享依赖差额，见 `artifacts/stability-20261010-s6c3-a9-live/s6c3-parent-gap-ledger.md` / JSON。初始绑定21/22；本轮新增Live限定证据后当前Screen源码绑定22/22。源码绑定只证明索引对应当前页面，不能代替每页完整父项验收。
- A9.1：Live使用统一页头、轻量转写卡、主题渐变状态球和自适应控制栏；状态球按可见活动/减少动效/弹窗覆盖决定循环，补THINKING。白名单搜索/勾选/弹窗开关可随Activity重建恢复，确认后重开使用已确认集合。保留原ViewModel、语音服务、权限、投影及白名单owner；返回和挂断语义仍分离。状态颜色仍即时切换，平滑过渡后续补验。
- A9.2：动效策略3项JVM红绿验证；最终生产修改qualityCheck成功（530 tasks），app/test APK编译成功。真实786dp展开屏八组100%/200%×浅深×固定/动态色，六状态、六控制回调、草稿重建及确认重开通过；两个原生方法59.628s。真实窄分屏 390dp、200%固定浅色，六状态/控制回调/白名单Activity草稿恢复及确认重开通过。
- 实际活动窗口237个FrameMetrics样本，总时长P95=14.714326ms；生产参数减少动效覆盖注入后状态球像素静止；实际moveTaskToBack后清空尾帧再观察700ms，新增帧0。这是隔离生产渲染器诊断，不是正式性能门、全局系统减少动画设置或真实语音服务停止验收。12秒录屏及截图已归档，不把录屏当无掉帧结论。
- 窄窗口首轮在Samsung对侧应用选择器尚未退出时提前开始，未找到屏幕阅读状态；第二轮取焦点点击关闭恢复中的弹窗，未找到搜索框。测试脚本增加交互窗口可见性、等待计算器就绪、状态取焦点和恢复后焦点判定；全部失败尝试保留，不用多次重测抹除失败。中间缺交互窗口flag的控制器尝试已主动终止，不计为通过。
- 主APK `artifacts/stability-20261010-s6c3-a9-live/ZeroV2-a9-live-debug.apk` 已安装，SHA256 `2465276dfe7dddeb548f0d0ba868fb15c74c5b89b10339e39ffa2e5eb12e9b95`。最后SDK/八原活动模块/新鲜挂载/宿主foregroundId2003/本地Qwen健康及配置摘要核验见delivery-result.json；配置与模块基线来自上片S6c2冻结after-device，密钥/profile/MCP摘要来自本轮真实安装前采样。不是全库内容一致性证明。没有付费provider请求，个人资料及现有工作树改动保留。
- 隔离控制器结束后正常宿主尚未恢复，delivery-after-narrow.log的foregroundId断言失败；主动拉起正常MainActivity后delivery-restored.log核验通过。测试应用初始化刻意跳过正常宿主，不能将此隔离夹具状态当生产自动恢复验收，也不删除该失败记录。
- A9/S6/A8父门继续开放：真实麦克风和投影权限拒绝/撤销、实际语音/服务媒体生命周期及挂断释放，折叠/IME/长转写与进程死亡连续性、状态颜色平滑过渡及正式资源门；其余逐页父项差额按台账补证。有效IDLE Doze、QQ长期资源/停启空窗、A10/A11仍独立。下一步按next-a9-plan.md先完成A9.2窗口/连续恢复差额，再A9.3真实服务资源，禁止用UI回调替代实际媒体验收。
- 完整范围、源码/产物摘要和失败路径见 `artifacts/stability-20261010-s6c3-a9-live/acceptance-index.json`。本轮S6c.3台账已交付，父项缺证补验持续进行。


## 2026-10-10 A9真实媒体权限/资源与IME、物理折叠连续恢复

- 真实拒权红证据：Android 15在RECORD_AUDIO拒绝时，LiveSessionService.onCreate仍启动microphone FGS，触发SecurityException。第一次仅服务内部拒绝又复现ForegroundServiceDidNotStartInTimeException，两个失败均保留。最终在Live启动入口前置检查授权，服务内部检查授权/捕获前台权限SecurityException，并以startupRejected在onStartCommand停止正确startId。拒权不挂起前台服务，给出重授权提示。
- 生命周期修订：销毁时先取消初始化/收集，再同步停止provider、投影和overlay；初始化结束后ensureActive与serviceDestroyed守卫阻止已挂断会话晚启动，provider未活动或启动异常则结束服务。投影FGS升级进入异常清理范围；系统授权Activity重建不重复拉起授权窗口。快速启动/挂断竞态压力与前台资格竞态尚待独立验证。
- A9.3a实机：真实权限拒绝后跨12秒前台启动超时观察，无服务/AudioRecord/投影残留，media-denied-final.log 13.654s通过。实际Hilt LocalVoiceProvider录音开启，真正系统投影取消保留会话、授权并选择Zero后VirtualDisplay/ImageReader创建，实际MediaProjection.stop触发系统停止回调清理，二次授权创建成功；退后台会话保持，挂断服务和AudioRecord/投影资源释放，500ms后无晚重开。media-release-ready.log 8.389s通过。
- 录音中撤权：真实AudioRecord录音后记录user0 UID/PID/session，外部pm revoke撤销RECORD_AUDIO；系统终止原录音进程，Live服务不再存在，AudioService当前活动录音配置（排除历史日志）user0 UID10025从1变0，音频服务诊断归档。此为预期OS进程终止，instrumentation的Process crashed不是常规JUnit通过，也不与此前服务自身崩溃混淆。原麦克风已授权状态在finally恢复；真实运行时麦克风授权弹窗和生产长会话语音质量仍待验。
- A9.2b：真实IME显示并输入白名单搜索/勾选，收起IME后同一Activity携带草稿经历物理内屏786dp→外屏373dp→内屏786dp，设备状态OPENED(3)→CLOSED(0)→OPENED(3)，搜索/勾选保持并最后一次确认。截图及continuity-report.json归档。此为生产renderer在真实configuration变化下的连续性，不等于真实NavHost/进程死亡恢复。50条合成转写仅作为背景，未声称长转写阅读锚点已验收。
- Samsung折叠后回到桌面，外屏解锁后通过am task focus将既有测试任务带回前台，未重建入口或修改折叠延续设置；因此证明任务恢复后的草稿保持，不声称折叠后自动留在前台。展开回程在同一测试继续完成，continuity-first.log 201.296s通过，含人工姿态等待。
- 最终生产及测试APK编译通过；qualityCheck成功（500 tasks）。修复包已ADB安装，SHA256 `92272eaf8107980abca28fcc8e6c102ef9558819c477cf62c681ddc8fcd6ebe3`。最终SDK/八原活动模块/挂载新鲜度/宿主2003/本地Qwen及配置、密钥/profile/MCP摘要核验见delivery-result.json，本轮真实安装前基线before-device.json与before-personal-prefs.log。正常MainActivity在隔离控制器后恢复；未改提示前缀、缓存链或模型路由，不将本轮资源测试当缓存命中率证明。
- 全部失败和控制器定位修订保留：旧APK缺debug entrypoint不计生产红；Android 15单应用共享选择器未完成不计实现失败；投影对象指针先于VirtualDisplay就绪，测试改等待既有sharing StateFlow的完成状态。隔离runner跳过正常应用启动，不替代实际生产语音profile/识别播报质量验收。
- A9/S6/A8整体继续开放。下一片固定：启动/挂断初始化及前台资格竞态→实际NavHost/进程死亡与长转写/IME阅读位置→平滑状态颜色及正式资源门；S6逐页父项、有效IDLE Doze、QQ长期资源/停启空窗、A10/A11仍独立。证据 `artifacts/stability-20261010-a9-media-continuity/acceptance-index.json`。


## 2026-10-10 A9.2c 真实IME确认按钮遮挡修订与最终交付

- 草稿连续性通过后，截图复核另发现按钮遮挡，未以先前通过覆盖该缺陷。新原生窗口坐标断言先红：保存按钮bottom=1379px，真实IME top=1344px，覆盖35px；ime-button-red.log 3.202s保留。
- Live白名单AlertDialog增加imePadding，保留原选择、搜索、保存和取消owner。复验保存bottom=1290px，键盘top=1344px，完整留出54px，实际目标高度49.77778dp；ime-button-green.log 3.222s通过。只声明本组真实786dp窗口IME几何通过，200%/全部IME类型等继续沿原矩阵补验。
- 最终编译及qualityCheck通过（500 tasks）。最新修复APK `artifacts/stability-20261010-a9-ime-controls/ZeroV2-a9-ime-debug.apk` 已ADB安装，SHA256 `5a198794aa20cf7af82512fae5fa09b704772f8dd6b4b67b66980211b7d3a482`；delivery-final.log核对实际base.apk、SDK四文件、八原活动模块、新鲜挂载、宿主2003、本地Qwen和安装前配置/密钥/profile/MCP摘要通过。麦克风授权及flags恢复、user95未变另见前片permission-restore-result.json。
- 真实媒体权限/释放、录音中撤权、IME/物理折叠草稿证据仍冻结在a9-media-continuity目录的原APK（SHA92272eaf…），本片仅增加弹窗IME留白；两个索引与原失败日志均保留，未将旧实机方法改写成最新APK上的重跑结果。
- A9.3a拒权/真实AudioRecord/投影取消授权/系统停止/二次授权/退后台与挂断，以及录音中撤权的OS终止证据限定通过。A9.2b物理内外屏同任务草稿及A9.2c本组按钮几何限定通过。A9/S6/A8整体保持进行中：真实运行时麦克风弹窗、快速初始化挂断与前台资格竞态、实际NavHost/进程死亡/长转写阅读锚点、平滑状态颜色及正式资源门仍待验。
- 当前统一后续顺序：启动/挂断和权限资格竞态→真实导航与进程死亡/长转写及IME阅读位置→其余动效/资源门。实施进度、审计报告、总架构/当前设计、路线图及验收计划/清单同步；没有改动提示前缀、缓存机制或模型路由，未进行缓存命中率实测。


## 2026-10-10 A9.3b/2d 启停竞态、真实导航与阅读锚点

- 快速重启原生红证据：实机实际Hilt provider stop/start复现AudioRecord.read的releaseBuffer断言与SIGABRT（race-red.log/race-crash.log），Kotlin crash shield不能拦截。根因是旧监听协程未结束且共享active/audioRecord，新旧循环可同时读取同一原生缓冲。生命周期Mutex串行start/stop，独立会话SupervisorJob覆盖录音/回复/播报，结束取消并等待旧会话退出；短锁保护非阻塞AudioRecord读取/释放，累计为原100ms帧，不改变VAD/ASR帧节奏。停止完成后发布IDLE，避免旧帧晚改状态。
- 实机8次实际录音快速重启后activeProviderJobs=1，最终结束无资源/旧循环及700ms迟到重开；5次真实服务创建后立即结束，初始化不会迟到录音，后续新服务可正常录音。本片限定启动/结束竞态通过，不冒称所有前台资格时序和长会话语音质量通过。
- 长转写红：生产renderer保留50条、向前阅读后裁头3条，同一项top从1892px跳到954px（native-first.log）。转写增加会话内稳定UUID，列表以ID为key，末项ID变化触发跟随，不再依赖固定50条的size。复验裁头保留同一内容与位置、同任务Activity重建保留阅读偏移，以及满50条末尾新增可跟随通过。合成条目属于隔离生产renderer；不是实际语音文本持久化或OS死亡后的转写恢复。转写继续瞬态50条上限，未新增个人数据库记录。
- 真实MainActivity/NavHost红：Live返回对话保持会话、再次进入后白名单草稿正常；后台保存任务后run-as SIGKILL原应用PID，实际OS进程终止恢复了路由/草稿，却自动重启Live服务（real-navigation-red-report.json/navigation-red.log）。入口用rememberSaveable区分首次显式进入和路由恢复，恢复不隐式启动麦克风。绿验同一路径，搜索草稿恢复、跨12秒观察无Live服务，用户点击开始后实际LISTENING、结束返回对话且服务停止。此是真实主应用，不以renderer或Activity.recreate替代OS进程死亡；测试前force-stop仅隔离入口，正式死亡段为SIGKILL。
- qualityCheck及主/测试APK编译通过，quality-final.log；最初detekt发现两处异常类型判断，已改为独立CancellationException catch并保留失败日志，没有压制规则。最新APK `artifacts/stability-20261010-a9-start-navigation/ZeroV2-a9-start-debug.apk` 已ADB安装，SHA256 `b886bcff42099f12841dfa42f1387905197a8d24006f97151f3797a9b931cbb6`。本轮真实安装前before-device/config与密钥/profile/MCP摘要对比一致；原八活动模块、新鲜挂载、SDK四文件、正常宿主2003及本地Qwen健康见delivery-result.json。未改提示前缀、缓存链或模型路由；不以未改动替代缓存命中率实测。
- 本片原生四项最终回归和真实导航报告独立记录，native-first.log两项录音通过、一项阅读失败不改写为整组通过；原APK与失败保留。导航首次绿尝试出现空root，旧脚本可能读取残留XML，故仅保留为中间尝试；最终脚本每次先删除临时快照、要求新dump成功，再完整复验，不以旧窗口内容评分。正常宿主已恢复，临时测试未修改个人白名单、系统锁屏/显示或麦克风授权。主产物/源码摘要见acceptance-index.json。
- A9/S6/A8整体仍开放：前台资格失效/运行时授权弹窗、真实长会话迟到回复/播报压力、被裁掉锚点降级及真实折叠/IME长转写阅读连续性、平滑状态颜色和正式资源门待补。下一步先补资格与长会话边界，再收敛阅读连续矩阵，随后动效/资源；其他页父项、有效IDLE Doze、QQ长期资源、A10/A11独立。


## 2026-10-10 A9长会话/阅读/动效/资源续验（中间检查点，尚未整体验收）

- 测前规则冻结于 `artifacts/stability-20261010-a9-long-motion/plan.md`。保留现有工作树、个人配置和本地模型路由，不修改提示前缀或缓存机制；本轮没有云端缓存命中率证明。
- 真实Hilt录音/Live服务接不配合取消的2200ms响应夹具，红验结束耗时2212ms、迟到文本事件2次（slow-red.log）。取消等待整棵会话导致阻塞，旧响应回调又缺少会话身份约束。修订为取消整棵会话但仅等待原生录音owner退出，回复/分句/最终结果与播报启动校验捕获会话身份；旧会话不得清理新会话状态。build-slow-green-color-red.log编译成功；slow-color-reading-first.log本项实机通过，夹具原bridge在finally恢复。不是实际LLM回复质量或24小时稳定证明。
- 同日志200%应用资源字号、实际IME弹窗开关及Activity重建保留阅读条目通过；系统字号不改，不冒称相同字符像素位置或物理折叠阅读已验。
- 当前阅读条目被裁掉的实机红验：保留50条、删除前20条后首屏实际跳到30而非20；固定旧索引跳过保留内容。增加捕获旧layout key的裁头降级，当前被删除才回到最早保留条目；未删除锚点保持与跟随末尾需独立绿验。该修订暂不标通过。
- 颜色测试首轮夹具无效：品牌主题primary与tertiary相同，不能据此判定颜色过渡；改测LISTENING→SPEAKING两个不同端点，再取得有效红验后修订过渡。
- 正式资源新增仅benchmark变体profileable manifest，以及真实Main/NavHost/录音服务Macrobenchmark控制器。冻结基线APK，要求同APK三组A/A校准后再比较候选。初次环境语音改变空转写场景及控制器idle等待导致无效；并行shell uiautomator产生的是测试进程注册冲突，不计应用崩溃。第二次缺少打断节点，仍为失败；已补交互窗口检索/失败时刻树证据，不能以测试结束后的桌面推断生产导航故障。正式JSON/Perfetto及完整A/A尚未齐全，资源门继续开放。
- 下一序列：裁头绿验及有效颜色红绿→5分钟真实录音打断/后台返回→正式资源校准和候选对照→最终安装配置/八模块/SDK/宿主/本地Qwen核验。A9/S6/A8整体保持进行中，其他独立门不代替验收。


## 2026-10-10 A9.3c/2e 长会话隔离、裁剪阅读、颜色及媒体收尾（限定验收）

- 最终生产APK SHA256 `a77ad70781a3225228daf52984c552eb7cc60b0c0b00a520e508922ed47d1337`，真实base.apk摘要匹配；native-release-final.log九项42.968s通过。真实慢响应结束耗时86ms（原红2212ms），迟到文本事件0、新会话LISTENING。会话身份隔离覆盖状态、分句、最终结果及播报启动；播报尾音等待后再次校验，避免旧会话越权恢复状态。质量门拆出会话校验/播报收尾/错误恢复，未压制detekt规则。
- 实机裁剪红为阅读条目10被删后跳到30；旧layout key若被删才回到最早保留20，未删条目的同内容/同位置、Activity重建及满50条末项跟随同组绿验。转写仍瞬态50条、不持久化到个人数据库。200%应用资源字号和实际IME弹窗往返保留可见条目通过；不冒称相同字符像素位置、物理折叠阅读或OS死亡后的转写恢复。
- 颜色有效红（reading-color-red.log）：LISTENING→SPEAKING端点不同，但采样全为终态；统一180ms状态过渡后实际中间像素及减少动效即时切换通过。浅色品牌primary=tertiary的首个夹具失败保留，不以夹具失败冒称产品红。color-frames为真实截图裁取状态球，已二进制exec-out重新传输并视觉复核，避免shell换行污染PNG；减少动效静止及实际退后台额外帧数0通过。motion-report.json仅隔离生产renderer诊断，不是正式性能门；系统字号/动画设置未改。
- 五分钟真实Hilt录音/服务保持：301411ms、30次打断、后台任务往返、同一AudioRecord session、结束释放和迟到观察通过（soak.log 303.152s）。仅响应边界使用离线夹具，并在finally恢复；不调用收费模型，不证明真实识别/回答质量或24小时稳定。该报告冻结在ZeroV2-a9-soak-debug.apk（SHA `591fb78db1a2b15872aefa21e737d2ca9ff92f04f24a105940d4833dfe4f08fc`）；后续最终APK仅调整投影释放先后，录音provider相同。最终APK单独九项回归，不把前一产物的长测改写为最新产物重跑。
- 首轮最终九项有一项真实媒体收尾红（native-final.log）：麦克风/服务已停止时投影尚未清空。销毁路径先释放投影，再停止provider并发布IDLE；真实系统取消、授权、系统stop回调、二次授权、退后台与挂断全资源清理九项复验通过。没有把首轮8/9标成整组通过。
- quality-delivery.log 945 tasks通过；投影次序修订后Lint内部FIR崩溃保留quality-release-order.log，采用独立Gradle进程/2 workers，不停用检查，quality-release-order-retry.log 895 tasks通过；帧归档控制器build-color-frames.log编译通过。全量检查另外暴露知识库fake worker取消后仍计数的夹具竞态，给fake build补ensureActive，与真实suspend worker取消语义一致；生产知识库未改，KnowledgeIndexTest 10项全绿。
- 正式资源基线仍无完整合格结果：旧控制器idle等待/环境转写改变场景、节点查找和实际锁屏阻断分别留痕。新增测前实际解锁检查、交互窗口检索、入口/控制缺失时刻窗口树，不能用测试结束后的桌面推断应用故障。热状态最高3时停止性能评分；按既有授权暂时停止Termux并熄屏冷却，正式AA/候选对照尚未通过，测试后必须恢复正常宿主和八模块并比对配置。
- [x] 本轮慢响应跨会话隔离及5分钟录音限定验收。
- [x] 被裁掉锚点有界降级、保留锚点/末尾跟随、200%资源字号/实际IME阅读条目限定验收。
- [x] 状态颜色中间像素/减少动态/后台停止绘帧及最终真实媒体释放限定验收。
- [ ] 正式非debuggable资源AA/候选对照；前台资格/真实运行时授权、实际语音长会话质量、物理折叠长转写阅读等父项差额保持独立。

A9/S6/A8整体继续开放；实施与审计证据 `artifacts/stability-20261010-a9-long-motion/native-index.json`。未修改提示前缀、缓存链或模型路由，不以此代替缓存命中率测试。


## 2026-10-11 A9正式资源测量边界与最终恢复交付

- 本轮长会话/连续阅读/动效功能依据前节九项实机与独立五分钟录音限定验收，不将不同APK或fixture范围混写。生产修订已经qualityCheck（895 tasks）及assembleDebug通过；之后仅正式资源控制器改动，build-resource-headroom.log子项目编译58 tasks通过。
- [x] 通过暖态限定资源门。有完整资源报告的组：baseline1, baseline2, baseline3, candidate1, candidate2, candidate3。frameDurationCpuMs: 基线13.290、候选13.183、差额-0.106、冻结容差1.166、通过=True；frameOverrunMs: 基线12.243、候选15.569、差额3.327、冻结容差4.367、通过=True；pssKiB: 基线357371.000、候选377531.000、差额20160.000、冻结容差23893.000、通过=True
- 测前冻结plan.md、formal-scope-v2.md和formal-thermal-window.md：非debuggable/profileable基线与候选，相同真实Main/NavHost/录音/可访问性控制，WARM/Ignore，物理展开无override，QQ按已有授权临时暂停，空转写固定场景。每组3次正式测量；框架预热iteration=null单列保留，正式0/1/2与三份trace对齐。setup两次热状态0且SKIN≤34.0℃，每次测量begin、三次controls、end共五点必须0；任何异常整组不评分。仅资源控制器允许localhost/127.0.0.1明文，不改目标网络策略。帧P95容差max(1ms,三组AA极差)，PSS容差max(基线中位数2%,三组AA极差)，AA完成冻结后才测候选，不能把容差叫提速。
- 失败证据保留：旧冷启动等待/环境语音/锁屏/坐标注入与loopback策略问题；首份完整暖态功能报告无窗口温度，不计分；首份窗口采样无明确预热轮次，存formal-unscored/baseline1-without-iteration；baseline1-measured-thermal-end-failure.log在end热状态1中止，不追认。后台QQ暂停后不得据此声称全模块负载或冷启动资源通过，外部报告处理温度单列诊断。
- 候选第三组首次在setup的240秒内未达到两次冷却条件，candidate3-warm-cooling-failure.log保留、不评分；失败后快照热状态3、SKIN42.8℃。runtime中QQ无活动句柄，系统多进程短时繁忙，不能凭单个top快照归因为QQ或Zero；间隔CPU采样后已显著回落（candidate3-hot-cpu-series.log）。熄屏充分冷却后仅补测第三组，同一冻结目标/控制器/误差，未停止其他个人应用、未修改正式门槛。
- 最终通过ADB恢复并安装ZeroV2-a9-long-debug.apk，SHA256 `a77ad70781a3225228daf52984c552eb7cc60b0c0b00a520e508922ed47d1337`，真实base.apk相同。正常宿主FGS2003、原八活动模块新鲜挂载、SDK资产/安装/Debian摘要、本地Qwen健康与8080连接全部复核；行为设置/触发开关/模块配置/密钥/profile/MCP个人偏好摘要与开工一致。暂停QQ已恢复，未清个人资料，不改系统字号/动画/锁屏设置。
- 恢复核验首次捕获QQ的Room状态因临时后端stop而同步为DISABLED；升级恢复正确保留停用意图，只自动挂载另外七项，不能把这当成升级丢模块。通过既有生产“工作区→动态模块→启停qqbot”恢复原配置，module-restore-ui.log 3.31s通过，最终dynamicModules与开工完全一致。恢复控制器单独编译/归档，不替换正式资源使用的冻结controller；首次窗口尚未就绪及选中页标题不可点击的控制器失败均留痕，最终按真实可点击组合contentDescription定位。未写SQL修状态、未用空配置启动QQ。
- 最终verify_delivery.py还要求MY_PACKAGE_REPLACED宿主请求以及全部八项挂载时间不早于手机侧本次安装起点，避免旧恢复记录误计新交付。build-module-restore-controller-final.log 58 tasks通过；这是交付控制器编译，不把它混写为再次执行全量qualityCheck。
- 证据索引artifacts/stability-20261010-a9-long-motion/acceptance-delivery-index.json及delivery-result.json。缓存前缀/模型路由未修改；本轮不推断缓存命中率。
- 下一步仍独立核对真实运行时麦克风授权/前台资格、真实LLM语音长会话质量、物理折叠及真实窄分屏长转写阅读、冷启动与全后台模块资源，接续A9后续父项与A10/A11；A9/S6/A8整体验收保持开放。


### 2026-10-11 LLM-U 统一模型适配（目标设计，未整体验收）

用户要求优先推进模型适配；完整契约/范围/顺序见 docs/refined/【验收推进计划】框架收敛·数据稳定·统一体验.md 的 LLM-U 节，实施见同目录【实施进度】稳定基建与自迭代升级.md。保持现有Agent执行owner，连接/模型身份/协议契约分离；首片U1a配置身份，后续连接迁移、参数编译、UI、CF/本地、工具回传和实测矩阵。A9已停止设备资源测试及原父项差额保持。


### 2026-10-11 LLM-U1a实现更新

模型注册身份现为profileId，wire modelId可重复；刷新原子发布完整owner快照，精确注销不删除其他连接。23项定向回归与qualityCheck（500 tasks，含assembleDebug）通过，记录见验收计划同日U1a节及artifacts/llm-adaptation-20261011/acceptance-index.json。U1b连接迁移/参数契约/UI/工具回传仍待实施，未设备安装或真实provider验收。


### 2026-10-11 LLM-U1b 连接配置与存储迁移通过

- 单一 LlmProfileStore 保存 schemaVersion=1 的 connections+profiles 快照；CHAT 只持久化 connectionId，旧消费者读取投影。每个旧配置独立连接，保留 ID/模型/参数/能力/优先级/启停/主备用凭据引用；非 CHAT 保持原协议。
- profiles_json 原样留存；迁移不运行旧预置模型升级。不识别版本/损坏数据冻结写入，绝不用默认覆盖；commit 成功后才发布 StateFlow，失败保留已发布状态并提示重启。共享连接冲突拒绝，修改连接与主力切换原子提交。
- NONE/Bearer/Header 已接入编辑页及同步/流式/短补全/健康状态；NONE 不读取或发送旧密钥、不伪造 Key。ASR/TTS/embedding/vision 路径不迁移。
- 6 项迁移/认证 JVM 回归通过；qualityCheck+services测试APK编译 528 tasks 通过，含 assembleDebug。首轮静态检查7项问题已修复、日志保留，不改检查基线。
- SM-F926N 隔离 services.test 实机 SharedPreferences commit/迁移/重开/旧文档保留/未知版本保护 1 项通过。测试前后个人 profile 文件 SHA256 相同。主 Zero APK未替换，未做真实 provider 调用或完整Agent验收。
- 证据 artifacts/llm-u1b-20261011/acceptance-index.json、quality-final.log、device-migration.log；继续 U2 参数契约，U3–U6与原父门仍开放。


### 2026-10-11 LLM-U2 显式模型参数契约通过限定验收

- CHAT 运行时按 contractId 调用 ModelContract 参数编译器，删除按 profileId 选 Qwen/DeepSeek Adapter 的分支。八种显式方言覆盖通用、DashScope预算/强度、DeepSeek新/旧同步语义、reasoning_effort+max_completion_tokens、本地模板及Ollama强度；协议与CF认证独立。
- ParameterSpec 声明实际JSON路径、类型、枚举及范围。parameters 中未出现的值不发送；false明确发送，非法0/枚举/未知键/互斥参数在写入与发送前拒绝。输出上限同时受 maxOutputTokens 和 contextWindow 约束；不使用温度的契约隐藏控件并说明保留旧值。
- DashScope直连HTTP参数改为顶层，避免SDK extra_body包装误用。DeepSeek reasoning_content通过契约回传（包括显式选择该契约的网关）。ChatRequestBody统一消息编码，保持工具顺序、ID、参数、结果及既有媒体表示；工具执行、safeCall、Agent owner和Room未改。
- snapshot schemaVersion=2，兼容旧数组及v1快照；缺契约的旧配置只在迁移时结合旧槽位及已知官方host映射，陌生网关/端点回到显式generic契约，绝不猜测模型版本。旧scalar参数原样保留；新参数独立保存。旧DeepSeek同步行为保留在deepseek-legacy-v1，可显式切换新契约。
- 模块内既有编辑页已支持契约选择、按契约参数字段、清除不支持字段、上下文/输出限制；切换保留草稿。新增/复制模型和完整连接管理仍归U3。已知local-qwen上下文保护继续执行，显式参数不再被旧思考字段覆盖。
- 补强U1b：Android JSON共享连接比较改为逐字段，不依赖JSONObject.similar；未知能力枚举禁止静默丢弃；初次提交失败保留准确写入错误。错误写入不发布，旧profiles_json不删除。
- 最终 qualityCheck + services:assembleDebugAndroidTest + app:compileDebugAndroidTestKotlin 537 tasks通过，含assembleDebug；JVM 553项（552通过、1原有跳过、0失败/错误）。定向证据：契约8、消息/工具2、存储7、编辑草稿4，另保留CF/模型身份/回传回归XML。
- 实机SM-F926N独立services.test两项通过：旧数组迁移/重开/未知版本保护、共享连接/实际Android JSON/顶层参数/NONE认证。测试前后个人profile摘要相同，主Zero APK未替换；不是实机UI验收或真实模型调用。
- 最终APK SHA256：381423a0d5b3b7518be80806b997a069953266048ff0bbd5a7c6184cd7d11122。证据 artifacts/llm-u2-20261011/acceptance-index.json、quality-release.log、jvm-summary.json、device-migration-contract.log、slice.diff及final源码。首轮静态问题、未知能力保护补测红结果保留，不降低规则/修改基线。
- 限定结论：U1b/U2基础实现与回归通过，U3–U6继续开放。Qwen3.8 27B具体失败尚无本片真实HTTP证据；Gemini签名/providerState、工具回合冻结与完整Agent矩阵待U5/U6，不宣称所有提供方已适配通过。下一片交接见artifacts/llm-u2-20261011/next-u3.md。


### 2026-10-11 LLM-U3：模型与连接管理已验证

- 在现有模型页增加添加、复制、停用后删除、连接选择/独立连接、共享连接显式更新、能力及优先级；新建/复制默认停用，不自动增加工具能力或改变主力。连接仍由单一 LlmProfileStore 原子快照管理，删除不清除凭据。
- 共享连接差异必须显式勾选更新；失败不发布新快照、保留草稿。未保存密钥不进入 Activity 恢复状态。
- `artifacts/llm-u3-20261011/acceptance-index.json`：quality 500 tasks；JVM 557（失败 0，跳过 1）；Fold3 独立包生产编辑器 2/2，覆盖 420/1200 宽度 fixture、共享选择及 Activity 重建。宽度 fixture 不等于真实分屏；非完整 Agent 实机验收。
- 日常 Zero APK 未覆盖；模型/密钥/MCP 三份配置前后 SHA-256 一致。首次独立构建配置时机错误及安装完成前 instrumentation 失败均保留日志，重试通过。
- 下一片 LLM-U4：提供方与 CF 模板、路径和认证；原 Qwen 错误仍须独立采证。U5/U6 未完成。


### 2026-10-11 LLM-U4a：提供方/CF 适配工程与隔离验收通过，U4b 原错误复现开放

- 现有模型编辑页新增 19 个连接模板：OpenAI、DeepSeek、百炼、硅基流动、智谱、Kimi、MiniMax、火山方舟、Gemini、OpenRouter、CF 三种新模式/旧 compat，以及 Ollama/vLLM/llama.cpp/LM Studio 和自定义。模板仅创建独立连接与新凭据引用，清除旧 fallback/未保存密钥，不自动改模型、能力或参数契约；地址占位符须补全，地域/Coding Plan 不能混用。
- `GatewayMode` 明确 DIRECT / CF_WORKERS / CF_BYOK / CF_GATEWAY；Workers REST 使用完整 @cf/...，可选 Gateway header；第三方 REST 使用 CF Token + Gateway header；旧 compat 使用 cf-aig-authorization。旧布尔 BYOK 配置（包括曾用于 Workers 网关的配置）保持请求语义，不自动重命名模型。新模式的错误路径/鉴权/模型格式在保存和发送前拒绝。
- REST 默认 BYOK 密钥缺失可能转统一计费，客户端不能强制免费或保证 BYOK；界面与指南已说明服务端策略边界。连接模板不是所有模型能力、免费额度或真实工具调用已通过的证明。
- 新 `cf-qwen38-v1` 契约按官方模型页限制 effort=low/medium/xhigh，发送 max_completion_tokens；温度保留。bounded 在无法关闭思考时用 low，避免省略后触发服务端最高默认强度。工具 ID、参数、结果与消息顺序回归保持一致。
- snapshot schemaVersion=3：gatewayMode 归连接，localLlamaPreflight 归模型；兼容数组/v1/v2并保留旧 profiles_json，提交失败不发布、未知版本不覆盖。llama.cpp 预检改为显式标志，复制模型保留，并传递所选认证；CF 与本地预检冲突先拒绝。generic 本地预检保留旧思考字段语义，需模型专属设置时显式选择契约。
- U3 收尾：已启用模型也可设为唯一主力；自定义模型隐藏无效恢复默认。主力选择仍由现有单次快照提交。Agent owner、工具执行、safeCall、Bus、Room schema 未改。
- 最终 qualityCheck（含 assembleDebug）500 tasks 成功；JVM 564（563 通过、1 既有跳过、0 失败/错误）。SM-F926N：独立包生产编辑器 3/3；services.test 的 Android JSON/迁移/路由 3/3。UI 仅验证 420/1200 宽度 fixture、连接/模板/草稿重建，不等同真实分屏或全 Agent。列表两项收尾由最终工程门验证，未宣称单独实机验收。
- 个人模型/密钥/MCP 三份配置前后 SHA-256 一致；日常 APK 未替换，未导出密钥或发送个人历史；临时 UI 两包已卸载。首次静态检查、旧测试前提、测试可空值编译、锁屏阻挡的失败日志保留；锁屏那次中止不计通过。仅 debug 的 profile fixture 允许锁屏显示合成页面，不改变系统锁屏或权限设置。
- 原 Qwen 只读历史采证得到两条 HTTP400 通用消息，原始上游响应/参数缺失，当前 qwen 槽位已改 GLM；**U4b 唯一根因与真实复现未关闭**，不把参数契约差异冒充已复现根因。后续代码片 U5；U4b 采样与 U6 真实提供方/Agent 矩阵继续开放，A9 仍停止。
- 证据：`artifacts/llm-u4-20261011/acceptance-index.json`、`quality-closeout.log`、`jvm/`、`device-ui-awake.log`、`device-migration.log`、`qwen-errors-redacted.json`、`provider-sources.md`、`slice.diff`、`final/`、`next-u5.md`。正式 APK SHA256：b3c32bff7f0eb815631323c32ab25ad7fd740b447d7204ec0dff9bd9d417b9f9。


### 2026-10-11 LLM-U5：回合快照与工具状态限定验收通过

- ILanguageModel.snapshotForTurn 在普通发送/重生成选定模型后冻结配置；ProfileLanguageModel 深复制参数/能力并捕获凭据，后续工具请求、输出预算、短补全均使用同一份内存快照。缺凭据不在续轮重新解析，下一用户回合才读取新配置；密钥不进入持久化或诊断。
- ToolProviderState 保留 OpenAI 工具扩展 extra_content.google.thought_signature，按连接/模型/契约/凭据引用绑定作用域；跨作用域不转发。签名不参与工具参数或执行，toString 脱敏。仅覆盖此明确扩展，不宣称原生 Gemini/Interactions/任意厂商扩展均支持。
- ToolCallAssembly 按 SSE index 合并乱序并行与晚到签名；冲突 ID/签名、越界索引失败，缺 ID/名称及同批重复 ID 不执行。既有工具 dispatcher、safeCall、TurnGate 和 Room 事务 owner 保持。
- 现有 tool_calls 文本列升级 metadata version=2，保留 provider_state version=1 和 stream_index，兼容旧数组/v1，不改 Room schema。重载完整工具消息及签名；进程恢复读取历史，不自动重放未完成工具。JVM 多步回传与配置轮换为确定性证据；实机通过数据库重载，不等于跨进程杀死恢复已测。
- quality-final.log：qualityCheck+测试APK，530 tasks，含 assembleDebug；JVM 567（566通过、1原有跳过、0失败）。SM-F926N 在真实宿主 UID、跳过正常 Application 启动的 Runner 下 6/6 Agent 回归通过：实际引擎/dispatcher、隔离Room、两次工具续轮、重载、取消与重生成/失败保留；模型为确定性 fixture，不计真实提供方通过。
- 已备份旧主APK并安装本片新正式 applicationId=com.zero.v2 APK；不同于前片“未安装”。测试不修改个人模型或密钥；U6结束后统一核对配置迁移和正常宿主恢复。首轮编译误改embedding局部变量、静态格式检查失败均已修正，原日志保留，未降规则。
- 证据 artifacts/llm-u5-20261011/acceptance-index.json；后续 U6 真实提供方/Agent矩阵与 U4b 原Qwen400复现继续开放，A9资源测量仍停止。


### 2026-10-11 LLM-U6a：CF真实模型与Zero Agent限定验收通过；U4b按用户裁定停止历史追溯

- Fold3 SM-F926N 使用真实个人UID内的SecureKeyStore凭据，未导出密钥、未发送个人对话、未修改主力模型。新增 opt-in LlmProviderAcceptanceTest，仅发送合成输入、执行无副作用测试工具；请求计数硬上限18，实际17次，未扩大额度重试。
- 当前CF REST账户入口：GLM @cf/zai-org/glm-4.7-flash 与 Qwen @cf/qwen/qwen3.8-27b，普通同步/SSE/两次顺序工具执行与结果综合共6/6通过。Qwen另通过生产 discover_tools → invoke_tool → invoke_tool → 最终综合路径，3条工具结果、两次实际probe执行，无重复执行。使用真实ChatBot/selector/dispatcher/客户端，正常Application启动被验收Runner跳过、只注册无副作用工具；不是全模块所有工具及聊天UI交互已认证。
- Qwen三种请求对照（旧槽位extra_body、仅移除extra_body、新cf-qwen38-v1）全部HTTP200并返回OK，保留请求形状/cf-ray。不能据此将extra_body或旧max_tokens认定为历史400根因。CF日志接口/网关列表均HTTP200，原2026-10-11 03:25–03:40 UTC窗口未取到对应失败日志。
- **用户最新裁定：“无需追求复现，当前功能通过即可”。U4b历史复现不再是阻塞门，停止继续追溯；结论为当前Qwen功能通过，历史唯一根因未确认，绝不写成已定位或已复现修复。**
- U5补验：独立持久Room测试库在PID8171写入、PID8449重开，四条消息、完成/待完成调用及签名完全保留，历史加载不自动重执行。seed/restore两次instrumentation均通过；个人聊天库未用于测试消息持久化。
- 最终 quality-closeout.log：qualityCheck + app:assembleDebugAndroidTest，530 tasks通过，含assembleDebug；JVM 567（566通过、1原有跳过、0失败），未修改基线。新主APK e843875bd91acb884e70b6cd73f44601885819e4576befb5bc4504cf087d7267 已实装，设备base.apk与构建哈希一致。
- 正常MainActivity/宿主FGS已恢复，原8个活动动态模块全部新鲜挂载；SDK资产/安装/DEBIAN哈希一致，行为设置/触发开关/模块配置/启停状态不变，密钥和MCP文件哈希不变。个人profile首次升级到schema3：旧profiles_json原文保留，原有全部字段逐一比对无变化；没有把qwen槽位从GLM切到Qwen。旧APK保存在U5证据目录。
- 本地Qwen仍按原配置停用，PC 8080健康端点当前不可达、adb reverse未配置，本轮未启动/更改本地推理服务；不计本地部署实测通过。首次按手机端口forward检查得到RemoteDisconnected，后核对原架构为PC reverse连接，纠正结果保留于delivery-result.json，不冒充本地模型恢复通过。
- U6b扩展矩阵继续明确未验证：Gemini免费层BYOK/真实签名、其他国内外直连、本地Ollama/vLLM/llama.cpp/LM Studio。连接模板/协议单测不等于这些账户或模型已经真实通过；未向未确认BYOK配置的第三方模型发起可能回落统一计费的探测。
- 证据 artifacts/llm-u6-20261011/acceptance-index.json、matrix.json、discovery.json、diagnostic.json、cf-logs-search.json、restart.json、delivery-result.json。错误的MainActivity包名启动失败日志保留，随后按实际namespace纠正并验证正常宿主。A9仍停止，不扩展本片资源/语音/全UI父门。


### 2026-10-11 LLM-U6b-1：Gemini BYOK 实机入口与错误数组修复

- Fold3 同 UID/Keystore 只读清单通过：default 网关已有 google-ai-studio、alias=default；保留 default_config=false，不改远端配置、不导出密钥。
- 临时 google-ai-studio/gemini-3.8-flash 经 compat/BYOK 的首个真实请求收到上游 503 UNAVAILABLE（high demand）。发现同步聊天先强制解析 JSONObject 导致错误数组遮蔽原错误；现先检查 HTTP 状态、支持根数组错误、响应关闭与密钥脱敏。
- qualityCheck + assembleDebugAndroidTest 530 tasks 成功；JVM 568 项：567 通过、1 原有跳过、0 失败。新增 503 数组与脱敏回归通过。未更改 Agent 工具调度/快照/配置 owner。
- 修复 APK 实装后，Gemini 3.8 Flash 经 CF compat + 已存 BYOK 的真实聊天、SSE（final=true）、Zero Agent 两次顺序工具及最终答复全部通过；2 次调用均取得真实 Google 工具签名，生产客户端继续回传后完成回合。累计 6 次请求（首次 503 + 成功矩阵 5 次），未改个人主力 GLM，也未回退其他模型/统一计费入口。
- 实机 Agent 重生成/取消/工具安全 6/6 通过。签名不是 fixture；不导出原始签名或凭据。其他直连/本地矩阵仍未验。官方模型有 API 免费层，用户现有 BYOK 用于本轮；Google 项目的免费/付费账务状态未独立读取，不能把 CF 估算成本当实际账单。
- 证据：artifacts/llm-u6b-gemini-20261011/acceptance.md、gemini-retry1.json、agent-regression.log；首次 503 原证据 gemini.json 保留。

- U6b-1交付核对：修复APK已安装且哈希一致；正常新进程前台宿主2003与原八模块新挂载；模型/密钥/MCP/行为/模块设置不变，原dirty路径无清除。见 delivery-result.json；当前Gemini仅临时验收配置。

### 2026-10-11 U6b-2至U6b-6：现有可用连接续验收口

- DeepSeek/deepseek-v4-flash、DashScope/qwen3.8-flash、MiMo/mimo-v2.5、llama.cpp/Qwen3.5-9B、LM Studio/Qwen3-8B，五条连接各聊天/SSE/真实Agent两次顺序工具3/3通过，共26次推理。MiMo首次固定标记提示拒答单独保留，普通问题与只读记录查询复验通过，未放宽工具执行/结果断言。
- 本地真实30020输入+2048输出超过24576上下文，生成前阻断1/1通过。LM Studio NONE认证通过，未误用llama.cpp预检。已有云端凭据和本机模型复用；无下载、无原模型/连接启停改写。
- 新增显式mimo-v1（现共10契约）：thinking.type、同步/流式同义、reasoning_content回传、使用服务端温度；不支持未确认effort。仅生产契约目录增量，旧档不迁移，工具执行/快照/Room owner不变。沿用既有动态UI控件；本轮未新增UI视觉验收声明。
- quality-closeout.log 530 tasks成功，JVM568通过/1原有跳过；Agent重生成/取消/工具安全实机6/6。新APK 97eccfd94e3b8700c1967da70b54b307a0b1c62e185a4100e2945b595f741a2a已实装并校验；前台宿主和八模块新挂载，模型/密钥/MCP/行为/模块配置保持一致，原dirty路径全部保留。测试本地服务/模型/转发均撤销，LM Studio配置恢复。
- 剩余OpenAI/Anthropic等直连缺对应CHAT配置、Ollama/vLLM未发现可用部署，不能用模板或其他网关模型替代实测。Gemini项目账务状态仍未独立核对。A9停止、历史Qwen400复现取消的裁定不变。
- 证据及后续条件：artifacts/llm-u6b-remaining-20261011/acceptance.md、acceptance-index.json、delivery-result.json、next.md。

### 2026-10-11 U6b：MiMo 契约增量

现有凭据的真实模型目录确认 DashScope/MiMo 聊天模型可用。新增 `mimo-v1`，仅声明 `thinking.type` 开关、同步/流式一致、思考内容回传和省略温度（使用服务端默认）；不猜测 effort 支持。契约由用户显式选择，不迁移旧模型，不修改 TTS/ASR/embedding 配置，也不改变 Agent 工具执行与存储 owner。实测与最终工程门以 `artifacts/llm-u6b-remaining-20261011/acceptance.md` 为准。


### 2026-10-13 基建 B1–B5 契约修订

- `SupervisorAuthorization` 约束 loopback/配置端口并附控制凭据；凭据由受权限保护的 RUN_COMMAND 在 Termux 私有目录生成，Android仅内存持有。supervisor HTTP与有界Socket帧入口分派前鉴权，HTTP不跟随重定向，副作用请求不自动重放。Termux同UID模块仍属信任域。
- `OutputCapture`/`collect_exec` 连续读取双流；单流64KiB内存尾、2MiB原文前缀，32个近期完成文件和最多4个活动命令。返回保存量/总量/读取路径；超时使用身份校验进程树清理并反馈结果，不无限等待继承管道。
- `BudgetAccounting` 为预算累计owner，十进制与串行confirmed commit；旧分值迁移但不补造历史损失。`ModelTokenPrices` 单独持久于模型profile，USD/百万tokens，用户来源；不进入请求参数或缓存scope。预算区分已定价、未知、套餐与本地。保护判据含未知部分旧混合估算，非服务商真实账单；无价格或usage不足不按免费处理。
- `AutomationClaims` 在调度及手动共同入口按任务ID认领，重读持久状态和调度时间，finally释放；`ExecutionPlan.validationError`/解析拒绝非法DAG，运行时无就绪节点即失败。仅进程内互斥，不承诺崩溃后外部副作用exactly-once。
- `CredentialStorageGate` 禁止加密失败降级明文，错误可见且可重试。显式旧纯明文迁移先写独立加密副本、核对、再切换；不删除旧原件、不自动重建损坏密钥。现有加密存储保留原文件与引用。

详细验收及平台边界见《【收敛开发设计】缓存可靠性·基建修订·ChatGPT接入》第9节、artifacts/infrastructure-20261013/acceptance.md。不改变缓存换代90%/65%、提示词与工具清单、原文读取和原生模型状态；Room31不变。

### 2026-10-13 B1–B5复审补充
自动化计划显式编号及依赖字段严格解析，禁止类型错误被静默降级。BudgetAccounting独立noBackup缺口标记让预算写入失败在重建后仍可见，不影响故障放行。TermuxBridge Socket兜底在令牌发送前核对Termux peer UID，4秒读等待、1MiB完整帧及200状态约束；HTTP主通道不变。测试/编译和实机边界见收敛设计§9.7；本轮未安装、未云测。

### 2026-10-13 ChatGPT S1认证契约
S0已验证个人本地/Pro候选路径、官方协议及Fold3系统浏览器loopback。S1在services/chatgpt增加ChatGptOAuth（PKCE/回调/JWT）、ChatGptVault（Keystore+noBackup AtomicFile）、ChatGptAuth（账号与并发owner）和固定官方端点客户端；app模型管理挂认证卡。未完成OAuth不激活新账号；退出/切号使迟到结果失效；不改LlmProfileStore、ModelSelector、请求缓存和旧凭据存储。具体切片及证据维护于收敛设计§10。

### 2026-10-13 ChatGPT S2套餐模型
ChatGptPlanLanguageModel按显式profile接入ModelSelector，冻结账号会话与模型；按需刷新Bearer，退出/切号取消旧请求。独立ChatGptPlanProtocol固定官方Responses端点、developer输入和zero函数namespace，stream=true/store=false；原生reasoning、usage及工具续轮复用。RequestContextPreparation仅套餐按注册身份保持缓存校准，旧API仍按凭据隔离；换代策略不变。套餐同步聚合SSE、不发送max_output_tokens、不做输出预算重试；本地预留不是服务端上限。显式启用的套餐模型在授权失败时报错，不因缺凭据选中付费候选。目录仅添加停用模型，未知能力保守、用户可声明；套餐计量独立。S2本地质量门通过，真实接入S3未验。证据及边界统一见收敛设计§11。

2026-10-13 S3实机补充：官方套餐实际completed.output可为空，ChatGPT路线从output_item.done收集原生项，完整终态后释放；解析异常保留usage。实机gpt-6.1-sol文本/图片/工具续轮与账号进程恢复通过，其他型号/真实到期刷新未外推。最终补修APKf253bb…（云用例通过版本573630…）已实装，106条原观测/配置保留、临时测试包清理。详细证据artifacts/chatgpt-s3-20261013/acceptance.md；S3父门待补自然到期刷新。


### 2026-10-16 P5互斥证据输出候选（未启用）
ExclusiveEvidenceOutput提供1–8槽完整JSON Schema和严格字面wire解码，槽位只能certain/uncertain/none之一；缺项、重复、未知、注释/NUL、尾随内容拒绝，不等于成功无支持。无生产调用者，不改MemoryEvidencePrompt、EvidenceSupport、2500ms链路、缓存/默认模型或Room。三臂自然来源本机生成未达语义门；元问题和命题确定性须先统一标注，再新独立集验证。详见持久系统设计§7.16及artifacts/p5-exclusive-20261016/acceptance.md，个人盲测/全库与P5父门保持开放。


### 2026-10-16 P5目标属性实验边界
离线实验明确subject/attribute/time/kind/proposition及来源asserted/denied/tentative/planned/absent；元确定性回答不提升原事实确定性。片段真实性只作来源约束，不证明属性蕴含。新自然两臂语义门未过，不接入MemoryEvidencePipeline、不改生产解析/预算/缓存/默认模型/Room；候选仅artifacts/p5-attribute-20261016。结果和下一独立门见持久系统设计§7.17；个人/全库/P5继续开放。


### 2026-10-16 P5完整原文类型视图实验
离线候选由客户端将不可变完整原文绑定到kind/state视图，模型不能改写证据value；模型状态不等于客户端认证，来源provenance与事件主体/日期不可混同。公开自然独立首轮与修订后同集诊断均未达质量门；未接入生产MemoryEvidencePipeline/Room/缓存/默认模型，原2500ms和历史保护保持。详见持久系统设计§7.18；个人/全库父门不关闭。


### 2026-10-16 P5来源元数据隔离实验边界
离线候选仅正文入模，客户端将provenance独立附加；发布日期/标题不可隐式投影为事件属性。完成状态必填、日期正文原样候选及mode/completion闭集只保证协议，不能认证模型语义。单事件/单mode未覆盖多谓词及条件计划组合；首轮与同集修订诊断均未达门。未改MemoryEvidencePipeline/Room/默认模型/2500ms预算/缓存，不自动改写历史。后续新独立质量门与个人/全库顺序见持久系统设计§7.19。


### 2026-10-16 P5多轴事件候选边界
离线多事件对象/动作引用、实际进度、计划/预测、条件unit分别表示；条件可附着计划，不强迫互斥。客户端绑定完整原文与元数据，原文空白复写只在唯一跨度可恢复时绑定回源文本，歧义/改字拒绝；这不是语义认证或实体消歧。新自然集未过门，不接入生产MemoryEvidencePipeline/Room，不改变2500ms/缓存/默认模型，不回写历史。继续验证动作极性及条件范围，个人/全库顺序与证据见持久系统设计§7.20。


### 2026-10-16 P5全unit证据验证候选边界
固定七字段多轴，实验复核覆盖所有机械unit，来源正文/hash/provenance均一致才合并；空草案不能跳过单元，空结果不等于无支持证明。同文异源保护与focus范围修订通过协议回归，两个中文模型及同集诊断仍未过语义门，不接管生产MemoryEvidencePipeline/Room，不改2500ms/default/cache。Mini原生模板/补全适配仅实验代码，保留原聊天接口HTTP400；不能外推Zero厂商回归。后续独立质量、手机与个人/全库顺序见持久设计§7.21。


### 2026-10-16 P5无草案候选与评分边界
保留七字段域结构和focus引用；空proposals重建仍覆盖全部unit，拒绝来源不作为空集成功。主体/受事和谓词拆分属于表示口径，须预先定义等价表达，报告诊断不得改变冻结门或认证事实。新自然集Qwen/Mini/受限思考未过质量门，不接入生产MemoryEvidencePipeline/Room/默认模型/缓存，不放宽2500ms，不自动改历史。来源/失败响应留存与自有进程保护保持；后续顺序见持久设计§7.22。


### 2026-10-17 本地编年分层边界
新增只读本地图投影：原消息/确认审计作为来源，既有memories为派生层，当前候选视图可重算，检索语义与上下文消费另行判定。记录时间不替代事件时间，RECORDED_NEXT不是因果，DERIVED_FROM与SUPERSEDES只映射已存关系。C1/C2不改Room35/JSONv3/历史/缓存/默认模型，不接管生产注入；Jev与云旁路不纳入。新存储/来源留存和消费接管须通过各自独立迁移与质量门，详见持久设计§7.23。

### 7.24 C3 独立编年来源、JSON与删除传播（2026-10-17，实施中）
Room36新增chronicle_sources及chronicle_deletions，35→36迁移仅建空表/索引/隐私触发器，不复制历史消息、不改旧记忆或usage。新用户消息在显式新输入事务中采集；历史载入和JSON消息恢复不触发补采。明确确认的复核结果保留独立审计来源。来源正文、来源元数据和记录时间生成稳定内容身份，采集时间独立；重复采集不覆盖首次副本。哈希仅校验完整性，不证明语义或真实用户身份。
JSON升级v4，旧v1–v3兼容；v4显式恢复来源和内容无关删除标记，删除优先于数据、原有同身份来源不被覆盖。恢复与普通消息写入分离，不通过导入推断历史来源。来源存储不接管检索/缓存/上下文；C4/C5另行消费和复核。
删除消息/会话/复核审计的SQL级触发器清除对应独立副本；记忆删除墓碑清除原消息来源副本与关联确认副本。忘记某条事实会屏蔽整个对应消息来源以防再提取，原对话文本按既有用户对话管理语义保留，其余历史记忆不被自动改写，但不得继续拿已屏蔽来源作证。备份按锚点拦截旧版本/改名派生恢复；旧版本删除墓碑若缺少来源指针，在恢复时用备份里的显式绑定补齐删除范围，不认证内容。源删除不被视为事实否定或事件从未发生。
生产数据库创建与升级均安装ChroniclePrivacy；隔离Room构建器须使用同一callback。验收覆盖升级逐表保留、空来源起点、事务/重启、整会话和记忆删除、备份往返/幂等/损坏回滚/删除防恢复。不得将独立存储验收外推中文语义、手机推理或P5父门通过。

C3补充边界（已纳入下述最终验收）：用户原始输入在模型选择/媒体处理之前经ChronicleInputStore的Mutex与Room事务留存，后续落库只补采尚无来源的输入，避免重计/改时；独立存储失败记录chronicle_input_failed，不触发云回退。自动注入不作为用户输入采集。新确认仅在显式decide成功后采集，JSON恢复旧审计不自动补采。
删除标记细分：message清除消息来源副本并撤销证据资格；transcript表示用户删除了聊天原文，恢复也应删除/拦截原消息；conversation覆盖整会话；confirmation覆盖确认审计来源。只忘记记忆时保留聊天历史及其恢复资格；实际聊天删除则优先于旧备份。关键词遗忘也处理尚未派生记忆的来源。消费端的当前视图、迟到召回、按ID和长期兜底均需屏蔽已删除来源，历史管理记录保留。
JSON v4同事务恢复所有域，先删除标记及依赖，再现有数据，最后显式来源；原32MiB备份上限保持。来源哈希并不认证用户身份或语义，C4/C5必须继续验证来源版本、资格和问题支持。C3验收不关闭P5中文语义/个人盲测/全库/手机预算父门。

### 2026-10-17 C3 最终验收完成
Room36/JSONv4落实独立来源与删除标记；不补录历史、不认证UNKNOWN。新输入模型前落库、显式确认留存、备份恢复幂等/损坏回滚，以及来源副本与聊天原文删除分离已实现。来源删除会屏蔽当前/自动证据/模型排序/向量重建/图谱队列/迟到提交，历史管理记录保持；原关系继续复用memory_sources/supersedes，精确来源版本绑定与新派生重建由C4实施，不能将最新来源当作原始版本。
最终qualityCheck、assembleDebug及两个测试APK构建4分20秒通过（586任务113执行473复用）。模拟器数据层39项、应用备份恢复25项通过；向量/图谱套件28项通过，1项跨进程条件跳过后已单独在seed/complete/verify三个不同PID完成（每阶段1项通过）。原35库非空消息/记忆/向量字节/配置/usage逐表值保留，最早v5→36链路通过；来源表初始为空。
复审修复图谱outbox既有未限定列名误绑定，原查询在独立SQLite复现0条匹配，限定作业列后1条；误删已完成/失败任务及重复入队的2项实测失败修复后通过，失败/修复日志均保留。来源删除保护也覆盖后台重建和模型排序之前的候选，不只过滤最终回答。
模拟器新装的com.zero.v2、com.zero.v2.test、com.zero.v2.core.data.test均已卸载，确认无残留；本轮自启模拟器已关闭。无云模型调用、无Jev、无手机安装/配置/usage/历史读写。未触及缓存世代/前缀/默认模型/预算策略，不声称新增缓存命中率实测。C3完成不关闭P5语义质量或C4–C6/个人/全库/手机门。证据artifacts/p5-chronicle-storage-20261017/acceptance.md。

### 7.25 C4 来源版本绑定、主对话分类与本地派生（2026-10-17，本地与隔离验收完成）
用户修订：分类由主对话LLM自主调用memory_sources/memory_remember完成，客户端不新增旁路分类器，后台不再调用UserFactExtractor。不改写旧记忆，不自动绑定历史，不认证UNKNOWN。旧分类排队任务退役为SUPERSEDED，保留审计；旧提取器仅保留实验/回归实现，不作为生产入口。规则不再替主模型选择type或tier；存储层级由明确type映射，不靠语句猜测。
来源绑定使用chronicle_sources不可变内容ID，提交必须含原文quote、type（FACT/EVENT/PREFERENCE/DIRECTIVE）、basis（EXPLICIT/INFERRED）、scope与claim_key。主模型负责命名和复用属性键、保持日期/否定/完整限定；客户端只校验格式、唯一原文定位、来源存活及版本、显式时间区间。DIRECTIVE仅接受声明EXPLICIT，但声明和字面引用均不等于语义认证。原文过大、定位歧义或来源已删除均拒绝，不能改用最新版补救。
Room37空表增量：memory_derivations为不可变提交内容及持久队列，memory_derivation_views为可丢弃的重建投影。确定性ID确保同源同载荷重试不重复；新意图只追加记录，相同载荷重试复用首次记录。后台prepare/commit分离，提交时重读来源、作业、删除墓碑和同ID结果，迟到或冲突结果不能覆写。新记忆origin=MODEL_CLASSIFIED，不进入现有已认证重要记忆前缀；显式召回保留候选标记，C5再决定自动证据认证/上下文消费。
当前视图按scope+claim_key重建：同值多来源可作同一候选，不同内容/分类/时间限定或显式待解冲突隔离；不采用最后写入覆盖。冲突判定是相同属性键的结构比较，不宣称完成跨键语义冲突检测。有效期左闭右开，发生时间不从记录时间补造；来源/记忆被删或改动后，投影不得继续返回旧候选。
来源FK删除级联移除派生提交（含待处理载荷）并清空对应缓存视图；已有历史管理记录保持，但无有效绑定的MODEL_CLASSIFIED不得再供模型/向量/图谱消费。记忆遗忘反向清理其绑定来源，防换ID/改内容再提取；关键词遗忘覆盖未物化提交。JSON v5备份显式派生绑定及状态，不备份缓存视图；旧v1–v4不补造绑定，恢复后重建，墓碑优先且同ID本机状态优先。
C4a：实体/协议/迁移及幂等基础测试、模块编译；C4b：本地派生/重建、删除失效与备份闭环、模块测试编译；C4c：工具分类入口与后台接线、退役副分类器、全质量/完整编译；C4d：模拟器隔离迁移/恢复/冲突/重启/取消和迟到提交验收，安装前告知并清理临时包。缓存世代/预算/手机配置/观测保持，Jev不纳入；主LLM分类真实质量及个人/规模/手机门仍由C5/C6独立验收。

C4兼容与幂等审查补充：工具持久清单使用显式contractVersion=2，旧不兼容memory_remember参数仅在协议升级时重建一次；同版本/同世代仍复用原清单，不跟随每轮catalog或最近使用时间变化。固定核心＋附加工具同时受24项和既有字节上限约束，未挂载工具仍可发现调用。新声明会自然改变一次工具前缀指纹，沿用既有scope_changed机制，不改缓存TTL、换代阈值、默认模型或账本口径，不声称98%命中已实测。
投影版本只反映有效候选、来源/结果版本、存活与隔离状态；为已经冲突的槽位补写同一冲突审计，不应额外引发投影版本变化。单一候选新增待解冲突仍立即隔离。EVENT同文本但不同来源不自动合并成同一经历；新意图追加提交，相同载荷重试复用既有ID和首次时间。
C4的memory_recall可返回标注为主对话分类候选的有效记录，不把字面引用等同语义支持，也不进入现有已认证重要记忆前缀。C5继续负责来源资格、问题支持、跨键语义冲突及自动证据/上下文消费门；旧显式启用的压缩/反思功能不扩大，新MODEL_CLASSIFIED排除旧自动过期/压缩/副冲突模型流程。

重建与索引衔接：启动先重建首批视图再启动既有向量Builder；后台以64项有界分页扫完整轮次，再比较仅含slot/version的元数据指纹。首次或有效集合变化时唤醒既有索引校验，未变化不重复唤醒；即使工具读取先重建了视图也不会丢失变更通知。指纹不进入模型前缀、不充当证据资格，原向量复用、发布质量门与远程/本地选择保持。C4以假向量运行时验调用顺序/次数，不执行实际模型推理。

### 2026-10-17 C4 本地实现与隔离验收完成
当前源码Room37/JSONv5：来源精确版本绑定、主对话工具自主分类、持久派生队列与可重建当前视图已落实。客户端只做结构、原文定位、版本、删除、时态和冲突保护，不调用副分类LLM；旧USER_FACT自动分类退役留审计，不重写历史、不补认证UNKNOWN。MODEL_CLASSIFIED保留候选标记，不进入原已认证重要前缀。当前视图同scope/claim_key结构冲突隔离，不以最新写入替代；C5再验语义与自动消费。
已修复工具旧快照协议升级/24项边界、首次冲突审计引发投影版本多跳、旧视图下迟到召回对冲突/归档的重读，以及恢复后先重建视图再回填索引/变更唤醒遗漏。工具协议显式升为contractVersion=2，仅不兼容升级重建一次；日常冻结策略、换代阈值、默认模型、预算/账本口径保持。声明升级自然改变一次前缀指纹，不声称98%缓存已实测。
最终qualityCheck/assembleDebug及应用/数据层测试APK2分54秒通过（586任务89执行）；JVM835项含2项既有跳过，零失败。模拟器核心70项调度（69通过、1项跨进程条件跳过后另3阶段通过），应用39项全部通过；原记忆/工具前缀跨真实进程恢复2阶段通过。36→37旧表逐值保留及v5→37链路、JSON状态/幂等/损坏回滚/删除防恢复、并发/取消/磁盘重开/迟到提交、归档/冲突消费守卫、65项元数据分页和假索引唤醒均通过。
所有安装/验收仅独立emulator-5580 / ZeroP27Api35；三个临时包已卸载、包清单为空，模拟器已关闭。Fold3未安装/配置修改/数据写入，未使用个人题、未云推理、无Jev。真实主对话分类/自然中文支持、跨键冲突、个人/全库/手机推理与长期门留C5/C6，P5父门不关闭。证据artifacts/p5-derivation-20261017/acceptance.md；最终APK仅留本机，未安装到手机。

### 2026-10-17 C5 来源读取与消费安全边界（实施中）
主对话负责分类和问题语义支持；本地ClassifiedMemoryReader只校验独立来源、规范化绑定、当前范围/冲突和显式有效期。MemoryDerivationStore.readCandidate在同一Mutex/Room事务内读取最多129项槽位（超过128拒绝），纯重算投影，不写当前视图或审计/访问计数，不依赖后台缓存时效。消费前重新读取，来源/结果变更或删除即失效。
新来源快照将automationRunId加入provenance身份。已有四字段来源与旧备份保持原ID及可管理状态，不补造缺失字段、不自动认证；Room37与JSONv5不变。确认来源仍验证原确认审计/父版本及同scope，不能只凭哈希认证。发生日期不从recordedAt填充。
显式memory_recall对新分类当前候选返回完整来源及候选声明/时间元数据，超出整条预算只输出按ID工具入口。来源已核验不等于声明真实，也不等于支持当前问题。自动旧入口不能绕过MODEL_CLASSIFIED质量门；旧UNKNOWN/无来源摘要不再自动作为证据（显式历史管理保持）。原USER_QUOTE/USER_CONFIRMED须通过已有来源适配器后输出完整资料，超预算引用，不切200字丢限定；注入前重读。新派生qualifiedEvidenceCandidates与重核接口暂为待验衔接，不启用生产自动调用。
资料仅入turnContext；memory_recall增加显式scope，工具contractVersion3仅升级时重建一次，之后冻结。缓存世代阈值/预算/账本/手机设置保持。跨键语义冲突不由局部槽位投影冒充解决；旧语义门与C6继续开放。


### 2026-10-17 C5 安全子片与隔离验收完成，语义父门开放
独立来源资格/使用前重核、纯当前投影、完整限定召回、scope显式隔离及隐式重试不认证旧来源已落地；旧UNKNOWN仍可显式读原值，不自动作为证据。来源/哈希/类型仅说明结构与资格，不等于问题支持或日期真实。新派生自动消费接口已准备，生产入口继续关闭，避免旧自动路径绕门；不自动改历史，不启用旁路分类或Jev。
完整编译/测试APK、最终qualityCheck通过；848项JVM含2既有跳过零失败，隔离核心71项调度（70通过、1条件跳过后跨进程3阶段补齐）、最终应用49项含10新项全通过，前缀真实重启2阶段通过。三包初轮/两包复测均清理，自启模拟器与本机模型已停；手机无安装或配置/观测写入。Room37/JSONv5保持；memory_recall新增scope，工具协议3仅升级一次，之后原冻结/缓存阈值/预算保持。
本机8条新撰开发资料41次主机协议夹具复跑未过语义门，存在记录时间误填、引用反复失败及夹具/评分边界偏差；不能冒充真实自然来源/生产Agent/个人或手机通过。实际参数经生产Android工具离线重放：16次接受含重试、6次歧义引用拒绝，不认证语义。下一片先校验生产工具闭环，再预声明逐主体/否定/无支持/日期口径、冻结新真实中文来源与可靠主模型候选；通过后才自动消费。C5/C6/P5仍开放。详见artifacts/p5-main-dialogue-20261017/acceptance.md、model-review.md及持久设计§7.26。

### 7.27 C5生产主对话闭环与冻结口径（2026-10-17，契约已验收/语义诊断未过）
先完成生产ChatBotModule→工具发现/注册/FunctionRegistryModule→ModuleLifecycleManager→MemoryModule→Room→原样工具结果→同一主对话的隔离装配与离线契约验收，再冻结新真实自然中文资料。测试不得忽略原文instr查询、限制生产允许的scope、伪造召回首候选、放宽引文唯一性或绕过生产失败重试保护。真实模型使用生产ProfileLanguageModel/OpenAiCompatibleClient及生产FC循环，不能以主机仿写工具/离线参数重放冒充Agent。
装配仅在androidTest隔离Context/内存Room/独立模型选择与注册器；保留生产模块、调度/生命周期保护、工具清单/结果访问、预算与流式处理。不运行正常Application.onCreate、全模块/手机任务或用户配置，测试应用安装前告知并清理。旧技能验收夹具新增ChronicleInputStore的构造兼容遗漏须修复，不能继续只通过编译却运行时反射失败。
评分先区分主体与所问属性、时间锚、确定肯定/确定否定、不确定、无支持；多主体可各有分类，不要求整来源同类型。存储分类/日期/完整限定、检索覆盖、最终问题支持和矛盾识别分开报告；引用与元数据资格不当作语义正确。跨键不同说法不机械归并/覆盖，主对话必须阅读双方来源再报告冲突；记录时间不能填事件时间。未出现事件日期可省略结构日期但必须保留原文日期/范围，禁止伪造精确时间。输入与金标/评分/候选/参数在推理前冻结，保留所有失败和夹具不一致，不调个人题。
通过真实来源及主对话门后才启用新派生自动turnContext；此前接口待验、旧自动入口排除保持。Room37/JSONv5/工具协议3/缓存阈值/已认证前缀和手机数据保持，不新增Jev或旁路判定LLM。

### 7.28 C5工具轮封顶综合协议修复边界（2026-10-17，红绿验收完成）
真实本机Qwen主对话在工具轮次封顶后触发模板500：最终综合将SYSTEM消息追加到历史末尾，违反只允许首部system的模板。最小/apply-template复现500，单改尾部为USER即200。修复使用临时USER续答提示，仍与systemPrompt分离；不写历史来源/稳定头，不改变工具上限、原综合无工具策略、预算保护、注册协议或缓存阈值。通过实际ChatBot封顶路径红绿测试及冻结来源原实验复验确认，语义失败仍保留独立口径，不因为协议修复改金标或提示。

### 7.29 C5冻结评分的命题层级门（2026-10-17）
评估资料必须在推理前向模型与金标同时显式给出target_proposition、asked_attribute、time_scope和judge_layer（WORLD_CLAIM或SOURCE_STATEMENT），不能仅凭“是否证明/原文怎样表述”问句暗藏评分目标。来源对“可能发生”的记述可确定，而事件本身仍不确定；二者不混为一个支持结论。scripts/freeze_memory_eval.py验证输入/金标目标逐值一致、唯一分母、候选及参数声明后才生成不可覆盖的SHA清单；不调用语义副模型，不自动生成真值。机器结构通过后仍须独立人工核对目标含义、日期类别、完整限定与来源。当前4通知的旧评分缺显式层级，保留为生产路径诊断/回归，不能宣称独立语义质量通过；下一新独立自然来源须走此门。生产Room/工具schema/缓存与自动注入门均不因此改动。


### 2026-10-17 C5生产闭环续片收尾（§7.27–7.29，语义父门仍开放）
实际生产ChatBot/发现/注册/生命周期/记忆工具/Room/完整原文闭环契约已通过；MODEL_CLASSIFIED召回不再交另一LLM重排；旧AutonomousSkillAcceptanceTest补ChronicleInputStore构造（云技能测试未执行）。真实工具轮封顶触发Qwen中途SYSTEM模板500，最小模板红/USER对照绿及实际ChatBot红绿均留证；仅将临时综合提示改为USER，不改stable systemPrompt、工具上限、预算或协议版本。旧schema验收35→37也有实际红绿证据，未改生产数据库。
根qualityCheck/assembleDebug/测试APK3m55s通过，848项JVM含2既有跳过零失败（含有效Gradle缓存）；最终应用51+schema/技能存储2+跨进程恢复两阶段=55项执行绿。恢复PID4567→4595，stablePrefix/tailCorrection/withdrawn均true。新增scripts/freeze_memory_eval.py有6项检查，最后assembleDebug15s通过。真实模型instrumentation执行OK不算语义通过。
新4份真实公开中文通知/12题、金标/提示/模型全SHA在推理前冻结；原33请求受综合500阻塞，修复同集32请求六阶段正常，system前缀各1种且append-only，辅助LLM0。保留所有失败，不用个人题调参。三组分类仅查来源却文本声称保存；后续问答保存不倒算成功。博物馆4候选将网页日期/无依据时分填为validFrom（2025-06-28 05:20），假期两候选实际同键冲突，没有跨键场景覆盖；博物馆最终JSON有字面量反斜杠n。源绑定/结构接受不能认证语义，不能据类型提升稳定记忆权限。
复盘校验评分：原文表述/是否证明可能与目标事实层级混淆，不将所有标签差异硬归模型。旧题缺显式target_proposition/asked_attribute/time_scope/judge_layer，被新增冻结目标检查拒绝；不改原金标追认通过，旧12题降为诊断/修复回归，不提供有效盲集通过率。新冻结工具强制输入/金标逐值同目标及判断WORLD_CLAIM/SOURCE_STATEMENT，唯一分母、候选参数和不可覆盖SHA清单；仍须推理前人工核对语义，不能以结构代判。
下一步先新独立真实自然来源和显式目标层级，经冻结门及人工审阅；再验可靠主对话实际工具回执、日期依据/类别/时间计算、无支持/明确否定/完整限定/当前性。跨键改为预置历史遗留不同键候选，独立于模型应复用同属性键的常规行为；不让模型制造违反键复用规则的测试。以上通过才接新派生自动turnContext，再到C6个人/全库/手机实际推理。35B因既有20GiB物理/23GiB提交门阻塞未运行，不降资源门、不替换用户进程。
65请求token/缓存均未知，覆盖0，不当0token/0%命中，不宣称98%实测。Room37/JSONv5/工具3、缓存世代阈值/账本/手机配置和观测保持；无云请求/手机安装或写入、无Jev、无自动历史改写。两临时包已卸载、包列表为空；模拟器和自有模型35600/34092已停，18086不监听，最终ADB仅手机。证据artifacts/p5-production-agent-20261017/acceptance.md、manual-review.md、cleanup-facts.json；C5/C6/P5不关闭。



### 7.30 后续测试门分层与本日停止（2026-10-17）
经用户确认，后续将工程正确性、模型适配、实际主模型语义质量、设备性能分别验收。本地9B用于开发诊断及其自身能力评估，不要求它通过全部语义题后才能推进云端主模型功能；本地候选失败不得泛化为Zero架构失败或云端主模型失败。反之，不能未经实测就假定更强云模型通过。
工程门用确定性测试验来源绑定、删除/迟到失效、迁移/备份、工具闭环及稳定前缀；适配门按具体provider/profile/协议分别验证，如本轮Qwen模板500只说明该路径兼容回归。语义门先冻结明确目标命题/属性/时间/判断层级和独立来源，再使用日常实际云端主对话模型做有界真实工具闭环；模型/参数/上下文/工具轮次/超时单独记录。不得将本地算力导致的慢、超时、轮次封顶混算为语义失败，不要求不同模型采用失真的统一时延门槛。
自动注入的批准范围绑定实际通过验收的主模型/适配配置；云端主模型通过即可推进其对应路径，不再被本地9B语义成绩卡住。未通过/未测试的候选仍明确标记，不能自动继承其他模型结论或静默切换。评分歧义必须独立修订，不通过换大模型或放宽金标掩盖；旧12题与两轮原始结果继续保留为诊断。个人来源绑定、全库规模、真实手机推理/资源与长期缓存观测仍各自验收，云端通过不替代设备或长期证据。缓存仍按实际服务商有效usage/覆盖/未知与冷/失败样本统计，不由本机前缀结构测试推断98%已达成。
下次开发顺序：独立来源与明确目标冻结/人工审阅→本地工程回归→提前告知后使用实际云端主模型有界验语义与工具执行→通过后衔接对应自动turnContext→C6设备/个人/全库等后续门。保留现有缓存设计、手机配置与观测数据，不自动改写历史，不引入Jev/旁路分类，不静默付费回退。
用户已要求今天休息：本轮仅同步文档，不再启动开发、构建、测试、安装、云请求或后台观测；本轮临时应用/模拟器/自有模型清理证据沿用已核实状态。等待用户下次明确继续后恢复工作，安装或云测试前重新告知具体范围。C5/C6/P5父门保持开放，不因停止而标完成。


2026-10-18 主Agent六类记忆维护/尾部撤回/增量注入的本轮规范与进度唯一入口：docs/refined/【收敛开发设计】持久上下文·分层记忆·工具与模型治理.md §8。复用MemoryDerivationStore事务/Mutex、EvidenceProvider/safeCall、MemoryContextProjection，不建第二所有权；覆盖此前普通撤回立即重建与本地9B前置门的旧约定。其他章节为历史。

2026-10-18 历史来源手动迁移、六类/时间修订、完整召回、历史链诊断与持久回执的唯一规范及验收见同一持久系统设计§9；Room38/JSONv6，沿用原复核审计/事务所有权和缓存前缀，设备交付单独确认。

> 2026-10-18 历史维护实机交付：Room38/JSONv6成品已安装Fold3，副本与手机迁移/手机31项及数据保留通过；当前唯一验收边界见持久系统收敛设计§9.4，非预期日常18请求单列，不视为受控验收。
