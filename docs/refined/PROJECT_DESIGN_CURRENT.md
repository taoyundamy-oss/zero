> P5无草案/中文候选（2026-10-16）：固定多轴及focus，新4页面6摘录9事件比较；Qwen带/无草案均完整3/6，Mini0/6，Qwen受限思考2/6，未达门。评分中主体/受事差异单列，真实预测/越界/漏事件仍存在。12+17+25测试、根质量/完整编译通过；自有进程清理，无个人题/生产接管/手机操作/安装/云推理/历史写入。设计§7.22与artifacts/p5-no-draft-20261016/acceptance.md；P5父门开放。

> P5固定多轴/逐事件验证（2026-10-16）：新6页面6摘录11事件，Qwen/Mini直接完整均1/6，未达门。修复同文异源合并遗漏及实验Mini原生结构化接口；focus提示范围修订后同集协议6/6但完整仍1/6。17+25测试及根qualityCheck/assembleDebug通过；无生产接管/个人题/手机操作/安装/云推理/历史改写，自有进程清理。详见持久系统设计§7.21及artifacts/p5-event-verification-20261016/acceptance.md；P5父门保持开放。

> P5事件多轴续片（2026-10-16）：对象/动作、实际进度、计划/预测与条件引用独立表示；新5页面8摘录13事件两臂首轮完整均2/8未过。唯一原文空白跨度绑定修复后，离线同集诊断协议8/8、完整2/8与3/8；仍有将来升级完成与条件错绑，非独立通过。25项新测试及19项旧回归、两步编译通过；无生产接管/个人题/手机写入/安装/云测试，自有模型已清理。详见持久系统设计§7.20与artifacts/p5-event-axes-20261016/acceptance.md。

> P5来源元数据分离（2026-10-16）：正文单独入模，必填完成状态；新3页面10摘录两提示首轮完整均2/10，未过。日期闭集/状态组合修订后同集诊断协议10/10、完整7/10，仍将未来升级完成，不能作独立通过。19项新协议与14项旧回归通过，两步编译通过；无生产接管、个人题/安装/云测试/手机写入，自有进程清理。详见持久系统设计§7.19与artifacts/p5-provenance-20261016/acceptance.md。

> P5来源属性/自然因果续片（2026-10-16）：公开自然6来源14问补齐2个因果正例。独立直接判断7/7、0/2、3/5未过；分解臂引用协议失败阻止匹配。客户端完整原文绑定修订后接口6/6，但同集诊断3/7、2/2、5/5且因果1/2、来源金标0/6，不能作新独立通过。14项实验测试与根质量/完整编译通过；手机仅稳定只读检查，无安装/云模型/数据写入，自有进程清理。个人盲题未消耗，缓存/配置/观测保持。详见持久系统设计§7.18及artifacts/p5-source-facts-20261016/acceptance.md。

> P5属性状态对照（2026-10-16）：已分离目标事实与元确定性，冻结8新自然来源/15问。直接判断完整匹配0/7、0/3、5/5；片段＋状态抽取6/7、2/3、2/5，均未达门，引用校验拒绝1次错槽，个人盲题未消耗。8项Python测试及qualityCheck/完整编译通过，现有JVM788含2跳过零失败。无生产默认变更/安装/云调用/手机写入，自有模型已停止。详见持久系统设计§7.17及artifacts/p5-attribute-20261016/acceptance.md。

> P5互斥输出对照（2026-10-16）：新8条自然来源/12问，原提示、仅互斥、互斥＋语义规则三臂36次本机生成均未达门；无支持1/5、2/5、3/5，不确定性0/2且存在元问题标注歧义。最终qualityCheck/完整编译3分31秒通过，JVM788含2跳过、零失败；新增隔离候选协议，未接管生产；保留原评分和失败证据，不消耗个人盲题。无安装/云调用/手机写入，自有模型已清理，缓存/配置/观测与历史保持。详见持久系统设计§7.16及artifacts/p5-exclusive-20261016/acceptance.md。

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

> 2026-10-15 最新P7/P5：分屏八条件、完整Agent跨进程技能复用通过；BGE两域生产发布、31项索引事务/回滚恢复、ASR合成并发与Qwen桌面对照完成。补显式回滚维护API，未更换主APK；旧1198/320条usage及配置保留，测试包/手机模型已清理。P5多语言记忆、个人语料、能耗/默认迁移和长期缓存继续开放。详细证据：artifacts/p7-p5-20261015/acceptance.md。

# Zero V2 当前项目完整设计（代码校准版）


> 2026-10-15 P7更新：双用户Room31→34与旧用量保留、隔离恢复/技能UI/图表/BGE实机通过；质量门通过，临时测试已清理。后台Gemini主失败已定位为既有2.5s重排超时并补分类诊断，P5质量/P6云端/长期缓存门未闭合。详见持久系统收敛设计及 artifacts/p7-20261015/acceptance.md。
> 2026-10-15 持久系统收敛：P3b冲突/摘要来源/记忆快照（Room34）、P4候选技能、P5本地向量候选、P6新增契约已实施；最终本地质量门通过（723项JVM含1跳过），技能核验UI和手机迁移已于P7验收；能力/长期缓存与逐型号云验收仍开放。唯一详细台账见 `【收敛开发设计】持久上下文·分层记忆·工具与模型治理.md`。

> 最新验收收尾（2026-10-11 LLM-U7）：代码审核修复4项P1（共享连接契约复验、草稿路由校验、原生状态上下文预算、Responses失败用量记录），新增显式cf-openai-responses-v1及图片输入，共13契约。CF Luna真实聊天、图片SSE、medium思考及两次工具3/3通过，共5次请求、每次1536输出上限；2个加密思考项、3条输出状态恢复回传。LLM页完成分区卡片、参数/能力选项和模型卡片重构；质量530任务、JVM592通过1跳过、实机UI10/10、Agent6/6。原配置/密钥/MCP摘要、实装APK、FGS2003和八模块恢复核对通过。保留Chat Completions，无自动迁移。通用契约不代表所有GPT型号或全部OpenAI API功能已验收。三家直连取消、Ollama/vLLM暂缓不阻塞、Gemini账务未核实、原Qwen400取消及A9停止不变。证据 `artifacts/llm-review-ui-20261011/acceptance.md`。

> 此前验收收尾（2026-10-11 LLM-U6b-12）：GPT-6 Luna经CF REST Responses已实现并实测：聊天/SSE通过，medium思考+两次Agent工具及落盘续接通过，1个真实加密思考项、3条输出状态恢复回传。共8次应用请求（初始low 5次无思考项，补验medium 3次），每次1536输出上限。新增cf-luna-responses-v1，共12契约，保留旧Chat Completions和全部配置，无自动迁移。质量530任务、JVM586通过1跳过、Agent6/6；实装、配置摘要、FGS2003及八模块恢复核对通过。三家直连取消、Ollama/vLLM暂缓不阻塞、Gemini账务未核实、原Qwen400取消及A9停止不变。证据 `artifacts/llm-u6b-luna-responses-20261011/acceptance.md`。

> 此前验收收尾（2026-10-11 LLM-U6b-11）：Anthropic原生手动思考/签名已实现并通过CF真实验收：聊天、SSE、两次Agent工具3/3，5次请求（总输出上限1536、思考预算1024）；1个真实签名块，续接前3条原生内容经metadata落盘恢复回传成功。新增显式cf-anthropic-native-v1契约（共11契约）、整条消息原生状态及存储元数据v3；旧契约/配置不自动迁移。质量530任务、JVM579通过1跳过、Agent6/6，实装APK及八模块恢复通过。用户确认本轮自行修改模型/密钥，其新配置保留，MCP/业务模块配置不变。当前授权功能范围收口：三家直连取消，Ollama/vLLM暂缓部署不阻塞；Gemini账务层级仍未核实，原Qwen400取消、A9停止不变。完整证据 `artifacts/llm-u6b-anthropic-thinking-20261011/acceptance.md`。

> 此前收尾结论（2026-10-11 LLM-U6b-10，覆盖下方历史待办）：CF Haiku真实两次Agent工具回传通过，本片3次请求、每次256输出上限，与U6b-7聊天/SSE合并闭环；Luna三项已于U6b-9通过。用户明确取消OpenAI/Anthropic/Gemini直连验收，不再阻塞收尾。质量530任务、JVM572通过1跳过、实机Agent6/6及原配置/八模块恢复核对通过，无生产代码或VPN修改。当前可用配置已验收收尾；Ollama/vLLM无可用部署仍待验，Anthropic原生思考/签名属于未实现能力（当前明确拒绝），不能写成已支持；Gemini账务层级未独立核实。U6扩展父门仍开放，U4b取消、A9停止不变。总证据 `artifacts/llm-u6b-closeout-20261011/acceptance.md`。

> 此前有效结论（2026-10-11 LLM-U6b-9，覆盖下方历史待验状态）：用户当前手机代理下，Zero生产客户端的GPT-6 Luna聊天/SSE/真实两次工具全部通过，5次应用层请求，上限6、输出上限256、effort=none。默认ProxySelector实际选择HTTP；免费trace先HK、后US/SJC，NO_PROXY对照也为US/SJC，故节点标签不等于固定出口，也不能断言地区封禁。未改VPN、保存配置或生产代码；质量门及实机Agent6/6通过，原配置和八模块恢复核对一致。证据 `artifacts/llm-u6b-phone-luna-20261011/acceptance.md`。Luna手机/SSE/工具条件关闭；其他直连、Haiku真实工具/思考签名、Ollama/vLLM和Gemini账务边界仍保留，U4b取消与A9停止不变。


> 最新补充（2026-10-11 U6b-8）：CF OpenAI失败已收窄为网络出口相关：手机CN/LAX稳定503，PC US/SJC的Luna Chat/Responses、Nano Chat均200，交替复核一致；同账户凭据与参数不变。不能再把U6b-7原路径503写成全局计费不可用，也不能宣称已修好手机出口或Luna SSE/Agent。零生产变更，九次限定POST及恢复证据见 `artifacts/llm-cf-openai-diagnostic-20261011/acceptance.md`。

> LLM最新事实（2026-10-11 U6b-7）：19模板/10契约/schema3。CF Anthropic SSE原生事件兼容修复后，Haiku聊天/SSE通过；GPT-6 Luna因CF统一计费暂不可用503未通过，不归因于参数、不重试。五次短请求；JVM572通过1跳过、质量530任务、实机Agent6/6；APK已交付，原配置及八模块恢复核对一致。当前收口边界及代码映射见 `artifacts/llm-u6b-cf-final-20261011/acceptance.md` 和 `code-doc-audit.md`；历史章节中的“待验”以最新逐片证据为准。

> 2026-10-05实现更新：Room30，A3按用户确认的旧数据保留/未核验规则已验收；最新工程、实机与交付证据见本文末节A3验收裁决。以下早期基线仅供追溯。

> 2026-09-30 新迭代入口：先读《【迭代总纲】稳定基建·系统感知·受控自迭代·UI重构.md》，再读配套设计/落地计划/视觉规范。本文件记录当前实现事实；目标行为按用户最新决策执行。新增“低风险自动灰度、高风险提案”只约束Termux动态模块自迭代发布，不能自动解释为恢复全体工具的旧AUTO/CONFIRM/FORBIDDEN分级。新设计尚未落地，不据此改变下文现状的完成状态。

> 状态：当前开发唯一入口文档（Current Design / Agent Bootstrap）  
> 基线：Git `4ecd86e`，审查日期 2026-09-29  
> 适用范围：Android 工程、Termux 动态模块、`zero-cloud`；历史决策仍保留在原专项文档中  
> 核心原则：代码事实优先于完成声明；构建通过不等于行为验收通过
> 2026-09-29 裁决：FunctionRegistry 工具调用默认放行符合当前产品使用方式，取消 AUTO/CONFIRM/FORBIDDEN 安全分级设计；现存分级字段仅作待清理的兼容元数据，不作为执行安全边界。

---

## 0. 如何使用本文档

新 Agent 开始任何开发前，按以下顺序读取：

1. 根目录 `AGENTS.md`：执行纪律与安全规则。
2. 本文档：当前架构、接口、不变量、已知偏移和下一步优先级。
3. `DEVELOPMENT_STATUS.md`：历史实施记录，只用于追溯，不作为当前真相源。
4. 当前任务对应的专项设计及落地计划：只读取相关轨道。
5. 实际代码、Gradle、Manifest、Room schema：最终裁决依据。

文档冲突时按以下顺序裁决：

`代码/Schema/Manifest/构建结果` > `本文档` > `专项最终设计` > `专项落地计划` > `DEVELOPMENT_STATUS` > `README` > `ARCHITECTURE_FINAL` > `ROADMAP`

说明：根目录 `ARCHITECTURE_FINAL.md` 是 v2.2/v2.3 时代的历史总设计，根目录 `ROADMAP.md` 的 Phase 1–12 虽全部勾选，但不能证明后续 v3.x、S/N/M/KB/MU/E 轨均已完成，更不能代替测试证据。

### 状态词

| 状态 | 含义 |
|---|---|
| 已实现 | 当前源码中存在完整主路径，且能编译 |
| 已构建 | `assembleDebug` 成功；不推导运行时正确 |
| 已实测 | 文档中有明确设备/模拟器证据；仍应按任务风险复测 |
| 待验证 | 有实现但本轮无自动化或设备证据 |
| 偏移 | 设计声明与源码行为不一致 |
| 未实现 | 接口、事件或注释存在，但主行为缺失 |

---

## 1. 审查结论与当前基线

### 1.1 代码规模与构建

- Android 多模块工程：10 个 Gradle module（`app`、`services`、6 个 `core:*`、2 个 `framework:*`）。
- Kotlin 主源码：196 个文件，约 37,361 行。
- Android：`minSdk 26`、`compileSdk/targetSdk 35`，包名 `com.zero.v2`。
- 实际版本：`versionName 1.0.0`、`versionCode 2`。
- Room：schema version 22，19 个 Entity，显式迁移从 5→22。
- 本轮构建：设置正确 `JAVA_HOME` 与 `GRADLE_USER_HOME` 后，`assembleDebug` 成功，319 个任务均为 up-to-date。
- 自动化测试：仓库中没有 `src/test` 或 `src/androidTest` 测试文件；当前质量证据主要来自编译、历史手工冒烟和真机记录。

### 1.2 可继续开发的判断

工程可以继续增量开发，主框架、Hilt 装配、Room schema、Compose UI、Agent/FC/Memory/MCP/Termux/Cloud 主体均已存在。但不能把当前状态描述为“生产安全已完成”，至少有以下阻塞级事实：

1. FunctionRegistry 历史权限分级没有闭环；现已裁决取消分级并保留默认放行，历史结构待后续清理。
2. Room 静默 destructive fallback 已移除并导出 schema；v5/v18/v19/v20 历史 schema 已从对应 Git 提交恢复，5→21 与最近三版升级矩阵已在模拟器通过。
3. 自动化测试地基已覆盖 core:data 迁移和 core:bus 消息管道；更广泛的模块 contract 测试仍按 Gate 4 继续补齐。
4. `MessagePipeline` 已强制单个 `IMessageFilter` 100ms 超时，并对超时/异常执行隔离降级。
5. README、根架构文档、Roadmap 与当前代码版本、DB 版本、能力状态存在明显漂移。

因此当前阶段应定义为：**功能面较完整的可编译产品原型，进入安全闭环、测试地基和文档收敛阶段**。

---

## 2. 产品目标、边界与设计原则

### 2.1 产品目标

Zero 是 Android 原生的自主个人 Agent。它应同时具备：

- 可控的多模型对话、思考流和 Function Calling；
- 事件驱动的自主触发、计划执行与后台恢复；
- 用户可审计、可配置、可禁止的行动权限；
- 分层记忆、知识库、用户图谱与长上下文治理；
- 本地语音、屏幕理解、系统能力和媒体能力；
- MCP、Termux 动态模块和自建云端三种扩展路径；
- 对用户数据、凭据、成本、后台行为的最终用户主权。

### 2.2 非目标与边界

- Android 端不是任意代码的安全沙箱；高风险代码执行只能进入明确的 Termux/云端隔离路径。
- 无障碍、通知监听、精确闹钟、悬浮窗、语音助手角色等能力均受 Android/ROM/用户授权约束，不得承诺绝对常驻。
- “模块已注册”不等于“模块可用”；权限、Key、外部进程、网络、模型资产和系统设置必须分别探测。
- 云服务和 Termux 是可降级的 Adapter，不得成为核心聊天、配置读取或本地数据浏览的硬依赖。

### 2.3 必须长期保持的原则

1. **单一入口**：本文档只描述当前状态；专项文档保留设计细节和历史裁定。
2. **深模块**：调用方只学习小而稳定的 Interface，复杂度留在 Implementation 内。
3. **缝隙真实**：只有存在两个 Adapter 或明确可替换实现时才新增 Seam。
4. **依赖单向**：`app → framework → services → core` 是概念方向；禁止低层反向依赖 UI/app。
5. **Hilt 装配**：正式 `IZeroModule` 通过 `@IntoSet` 汇集；禁止手工全局单例和隐式 Service Locator。
6. **Bus 解耦**：跨域通知走 `ChatBus/SystemBus`；需要同步返回值的能力调用才允许显式注入。
7. **用户主权**：自主行为、成本、权限、频率、通知与数据导出必须可见、可调、可停。
8. **失败显式**：危险动作 fail-closed；可选增强可降级；不得返回成功而不落盘。
9. **可观测不等于正确**：Trace 是诊断证据，不代替断言测试。
10. **数据库不丢数据**：Entity、DAO、version、Migration 必须同一步提交并做迁移测试。

---

## 3. 系统上下文与运行形态

Zero 由三套运行环境协作：

```text
Android App
  ├─ UI / ViewModel / Android Services
  ├─ Chat / Memory / Agent / Tools
  ├─ Room / SAF / EncryptedSharedPreferences
  ├─ MCP client ───────────────► zero-cloud
  └─ TermuxBridge ─────────────► Termux supervisor / dynamic modules

zero-cloud
  ├─ nginx + gateway + Redis
  ├─ optional worker/media/search/document stack
  └─ optional OpenClaw IM stack
```

三者的权威边界：

| 环境 | 权威数据/职责 | 不应承担 |
|---|---|---|
| Android | 用户配置、对话、记忆、知识库索引、模块状态、权限与 UI | 任意不受控代码执行 |
| Termux | 本机动态模块、Linux 工作站命令、编译与进程管理 | Android 主数据库与用户授权裁决 |
| zero-cloud | 重型抓取、异步任务、媒体处理、IM 接入、跨设备文件中转 | Android 本地配置真相源 |

---

## 4. Gradle 模块设计与依赖

| Module | 职责 | 关键 Interface / Implementation |
|---|---|---|
| `core:common` | 跨模块纯类型 | Chat/System events、FunctionDefinition、ActionPermission、ModelAdapter |
| `core:bus` | 异步事件 Seam | ChatBus、SystemBus、MessagePipeline、IMessageFilter |
| `core:module-api` | 模块 Interface | IZeroModule、ITimedModule、ModuleMetadata、ConfigField |
| `core:data` | Room schema 与 DAO | ZeroDatabase v21、19 Entity、DAO、ModuleConfigStore |
| `core:trace` | 白盒追踪 | AgentTraceManager、NodeType、Room 异步持久化 |
| `core:module-manager` | 模块与工具编排 | ModuleLifecycleManager、FunctionRegistryModule、ModuleConfigParser |
| `services` | 平台/网络/Agent/能力 Adapter | LLM、Agent、MCP、系统工具、语音、Shizuku、Termux |
| `framework:chat` | 对话深模块 | ChatBotModule、ContextBuilder、ContextWindow、TokenBudgetAllocator |
| `framework:memory` | 记忆深模块 | MemoryModule、Reflection/Graph Jobs、HistoryCompressor |
| `app` | Composition Root 与 UI | Hilt bindings、Compose、Android Service、app 级 Files/Music/Knowledge/Assistant |

当前依赖存在一处需要承认的现实：`services` 直接依赖 `core:module-manager`，多个工具模块直接向具体 `FunctionRegistryModule` 注册。这使注册中心成为共享基础设施，而不是纯 app 编排层。短期保留，禁止继续让 services 依赖 app/framework；中期可抽取窄 Interface（见 §13）。

---

## 5. 模块系统设计

### 5.1 IZeroModule Interface

每个正式模块必须提供：

- 稳定、唯一的 `metadata.id`；
- `lifecycle: StateFlow<ModuleLifecycle>`；
- 可变 `isEnabled`；
- `onAwake/onStart/onDestroy`；
- `config` 与 `onConfigChanged`；
- `registerFunctions`；
- `checkAvailability` 与 `requiredPermissions`；
- `markDisabled`。

标准生命周期：

```text
config restore
  → onAwake（注册 FC、建立内部状态）
  → onStart（订阅 Bus、启动循环）
  → running
  → onDestroy（取消订阅、关闭资源）
```

所有生命周期调用必须从 `ModuleLifecycleManager.safeCall()` 进入。当前代码只对生命周期集中调用满足该要求；普通 FC handler 的异常由注册中心另行捕获，并不经过 lifecycle safeCall。

### 5.2 Composition Root

`AppBindingModule` 是正式模块装配的唯一入口。当前已绑定的主要能力族包括：

- Core/chat：OpenAiChat、ChatBot、ContextBuilder、MessageFilter、FunctionRegistry；
- Agent：AgentRuntime、Scheduler、TriggerHub、AutonomousThinkingJob；
- Memory/knowledge：Memory、Reflection、GraphExtraction、Knowledge；
- System/tool：WebSearch、Vision、WebFetch、Files、Notification、UsageStats、Location、Calendar、Alarm、Automation；
- Voice/screen：ASR、TTS、VAD、ScreenCapture；
- Extension：MCP、Shizuku、Termux、CodeDev；
- Product：Music、Assistant。

新增模块必须同时检查：Hilt binding、launchOrder、ID 冲突、配置恢复、FC 名冲突、requiredPermissions、设置页可达性、禁用后的资源释放。

### 5.3 模块深度审查

当前较深的 Module：

- `ChatBotModule`：隐藏 FC 循环、流式、上下文、持久化与最终综合；
- `MemoryModule`：隐藏分层、检索、写锁、冲突与嵌入降级；
- `WorkDirRepository`：隐藏 SAF、文件名净化、原子写和流式上限；
- `TermuxBridge`：隐藏 RUN_COMMAND、supervisor、传输和 bootstrap；
- `McpClientModule`：隐藏 server store、协议、动态 FC 映射和 transport 选择。

仍偏浅或 Seam 放置不理想的部分：

- 调用方直接依赖具体 `FunctionRegistryModule`，注册、执行、安全策略耦合在一类中；
- 多个 app ViewModel 直接按 FC 字符串名调用注册中心，编译器无法保证工具存在或参数正确；
- `IZeroModule` 同时承载生命周期、配置、工具注册、权限和可用性，Interface 较宽；
- `isEnabled` 与 `ModuleLifecycle` 仍是两个底层字段，但统一通过 `ModuleRuntimeState.operational` 解释；非 Core 崩溃会同步关闭开关并进入 `DISABLED`，避免调用方各自解释。

---

## 6. 事件、对话与工具调用

### 6.1 Bus 语义

`ChatBus` 用于对话、流式片段、Poke、工具轨迹和用户优先打断；`SystemBus` 用于 tick、调度、通知、前后台/网络/电量及安全确认。

缓冲不变量：

- UI 流式帧可 `DROP_OLDEST`；最终结果和工具结果不可因拥塞静默丢失；
- 业务关键事件若采用 SharedFlow，必须明确“无订阅者时是否允许丢失”；
- Bus 事件是通知，不承载需要事务确认的命令；安全审批尤其不能只依赖 fire-and-forget 事件。

### 6.2 MessagePipeline

当前 Implementation：按 `filterOrder` 串行处理，整体 `withTimeoutOrNull(3000)`；每个 filter 使用独立 100ms 超时。

当前契约：

- 单个 filter 超时或抛出非取消异常时，记录 `MessagePipelineTraceRecorder` 事件并跳过当前 filter，继续后续 filter。
- 过滤器列表注册、注销与快照统一使用同一锁，快照保证一次处理回合内稳定。
- 整体 3 秒超时时返回原始事件并记录 pipeline 级超时事件；协程取消异常继续向上传播。

### 6.3 Chat 主流程

```text
ChatViewModel
  → ChatBotModule.sendMessage
  → MessagePipeline
  → ContextBuilder + ContextWindow + summary/memory
  → ModelSelector / OpenAiCompatibleClient
  → streaming assistant content / reasoning
  → tool_calls
  → FunctionRegistry
  → TOOL result 回灌
  → 继续 LLM，达到上限时最终综合
  → ConversationRepository / Room
  → ChatBus + AgentTrace
```

关键不变量：

- assistant(toolCalls) 与对应 TOOL 消息不可被上下文裁剪拆开；
- toolCallId 必须 1:1 对应；
- FC 达上限后必须再给模型一次不带 tools 的综合机会；
- UI 投影和 LLM 上下文分离，系统展示事件不得无意污染 prompt cache；
- 用户新消息应可打断自主回合，但不能造成半落盘状态。

### 6.4 FunctionRegistry 当前事实

注册中心提供定义查询、动态注册/注销、批量并发和 handler 分发。当前产品裁决为工具调用默认放行，不再建立 AUTO/CONFIRM/FORBIDDEN 执行安全分级，也不建设审批闭环。

现有 `ActionPermission`、安全确认事件和 UI 标签属于历史兼容结构；在后续窄接口/调用入口收敛时删除，不阻塞当前 Gate。系统权限、文件路径、URL、Intent、shell 参数和凭据保护仍是独立安全边界，不受本裁决影响。

仍需修复的执行可靠性问题：

- `FunctionRegistry` 已统一调用身份：优先复用模型 `ToolCall.id`，空 id 生成 UUID；start/result/成功或异常 Trace 复用同一 `callId`，`traceId` 由其派生。
- `dependsOn` 已在 FunctionRegistry 批量执行中构建并校验 DAG：同波次并行、跨波次按依赖顺序执行，缺依赖/歧义/环会拒绝整批；依赖结果注入协议仍待后续专项定义。

---

## 7. Agent Runtime、自主触发与自动化

### 7.1 驱动结构

- `AgentRuntimeService`：AccessibilityService 形式的 Android 入口。
- `AgentRuntime`：自适应 tick、前后台/用户活动感知。
- `Scheduler`：将 tick 分派给 `AgentJob`。
- `TriggerHub`：通知、设备、云/IM/动态模块等事件的门控、去重、限额与入队。
- `AutonomousThinkingJob`：单飞执行、自主 prompt、用户优先打断与任务续跑。
- `ExactAlarmReceiver/BootReceiver/WorkManager`：精确触发、重启恢复和后台兜底。

### 7.2 调度不变量

- 同一自动回合只能有一个 owner；并发触发必须合并、排队或显式丢弃并留痕。
- P0 用户输入永远高于自主事件；P2 tick 不得打断 TASK。
- 所有重排必须有上限、退避和唯一键，避免重启风暴。
- 一次性任务到期后不可重复；周期任务推进必须原子认领。
- Android exact alarm 不可用时必须显式降级并告知精度，不可伪装为精准成功。
- 自主行为必须同时经过 mode、source、daily cap、budget 和权限策略。

### 7.3 Automation

`AutomationModule` 同时支持 PLAN 和 REACT 路径，配有步数、重规划、Token 和时长 guard。工具调用按当前产品裁决默认放行；Automation 仍必须遵守步骤、Token、时长、参数域和系统权限等独立 guard。

---

## 8. 数据、记忆与知识库

### 8.1 Room v21 数据域

当前主要数据域：

- 会话与消息；
- Character；
- Memory 与 UserGraph；
- Automation 与 AgentTrace；
- Notepad/Reminder；
- Music track/playlist/schedule；
- Knowledge docs/chunks；
- Dynamic modules；
- Module config。

### 8.2 写入与事务契约

- `MemoryModule` 使用 `Mutex` 保护主要写路径；Knowledge/Music/WorkDir 也有各自写锁。
- 多表或“先查后写”的一致性仍必须由 DAO `@Transaction` 或 database transaction 保证；进程内 Mutex 不能替代 SQLite 事务，也不能覆盖多进程。
- `KbDao` 有 `@Transaction`，其他复杂 DAO 路径需要逐项审计。
- Room migration 5→22 已显式注册且 destructive fallback 已移除；迁移缺失会中止打开并保留原库。
- 已启用 schema 导出并纳入版本控制；v5/v18/v19/v20 历史 schema 从对应 Git 提交重新编译恢复，`MigrationTestHelper` 已覆盖 5→21 数据保留及 18/19/20→21 schema 校验。

### 8.3 Memory / Knowledge 边界

- Memory：用户事实、偏好、事件证据和 UserGraph；回答“用户是谁/当前状态”时 Graph 是当前真相，Memory 是证据历史。
- Knowledge：用户工作目录内的文档知识；按文档/chunk 建索引，支持词面与 embedding 检索。
- Conversation summary：只服务长对话上下文，不自动成为长期记忆。
- Files：数据访问能力，不承担知识选择与记忆决策。

禁止把聊天摘要、知识库 chunk、Memory 和 UserGraph 合并成一个“万能表”。它们有不同生命周期、真相语义与删除策略。

---

## 9. LLM、配置与密钥

### 9.1 LLM Seam

`ILanguageModel`/`ModelSelector` 是聊天模型选择 Seam；`OpenAiCompatibleClient` 封装兼容协议；profile/store 管理 provider/baseUrl/modelId/能力与思考参数。

约束：

- 模型输出永远是不可信输入，工具参数必须 schema 校验和业务二次校验。
- API Key 只保存引用或加密值，不得进入普通 Config JSON、Trace、异常 body 或导出文件。
- 网络错误、限流、超时和协议错误必须区分，不可统一显示“模型不可用”。
- capability 选择必须有确定性 tie-break 与失败原因。

### 9.2 当前配置系统

配置通过 `ModuleConfigBase + @ConfigField + ModuleConfigStore + ModuleConfigParser` 驱动模块详情页。当前使用 Kotlin reflection，并非根架构文档所写的“零反射编译期 parser”；这是已经接受的实现偏移，除非有性能证据，不应再次并行造一套 parser。

`ApiKeyProvider` 仍留有 Phase 8/DataStore TODO，部分能力已绕过它直接使用 `SecureKeyStore`。应收敛为一个凭据 Interface，删除失效 stub 和多条取 Key 路径。

---

## 10. 文件、语音、系统与扩展能力

### 10.1 Files / SAF

`WorkDirRepository` 是文件访问深模块，负责 SAF 根目录、路径净化、写锁、流式大小限制、二进制和分页文本。所有 LLM 文件能力必须限定在用户选择的工作目录，禁止 handler 自由拼接绝对路径。

### 10.2 Realtime voice

ASR/TTS/VAD 分成独立 Module；Live 会话由 `LiveSessionService` 组织。扬声器采用手动打断，耳机/蓝牙可自动 barge-in。模型资产、音频焦点、麦克风释放、前台服务类型、来电/断网恢复都属于真机验收项，编译无法证明。

### 10.3 Android system adapters

Notification、UsageStats、Location、Calendar、Alarm、ScreenCapture/Reader、Shizuku 都必须：

- 在执行前重新检查权限与系统开关；
- 返回可诊断的 unavailable 原因；
- 不把 Manifest 声明误当作用户已授权；
- 对敏感写操作使用统一权限策略。

### 10.4 MCP

支持 Streamable HTTP、JSON-RPC、SSE 响应和本地 socket Adapter；服务器工具动态映射为 FC。工具执行默认放行；外部 MCP 描述、schema 和返回内容仍是不可信数据，必须继续做协议、参数和域校验。

### 10.5 Termux dynamic modules

Termux supervisor 管进程、依赖、端点和模块状态；Android 端维护 DynamicModuleStore 并映射 `dyn_*` FC。必须保持：

- 模块名/路径白名单和穿越防护；
- 进程组级停止与有界重启；
- manifest/schema 校验；
- 调用超时与异步长任务分离；
- 自动开发/修复轮次、模块数、依赖安装均有硬上限；
- 动态模块永远不能绕过 Android 的行动权限与用户禁用状态。

### 10.6 zero-cloud

当前仓库含 gateway/nginx/Redis、可选 search/worker/media/sandbox、OpenClaw IM，以及已退役但仍留定义的旧容器。`docker-compose.yml` 同时保留历史 profile 和现役 profile，部署前必须以迁移维护手册及服务器实况为准。

安全边界：Bearer token、TLS/pin、SSRF、文件大小/TTL、并发数、容器网络和 socket proxy 白名单必须全部存在；云端返回内容仍需 Android 端按不可信输入处理。

---

## 11. UI 与可观测性

主要导航域：Chat、Module Center、Run Logs、Automation、Settings，以及 Live/Music/Files/Memory/Knowledge/Dynamic Module/MCP 等子页。

UI 设计约束：

- 设置项只有在真实读写同一配置源且运行时消费时才能显示；
- 敏感 Key 只显示配置状态，不回显值；
- 系统事件、工具轨迹和普通聊天在投影层区分，不能破坏 LLM 消息序列；
- 长列表使用稳定 key，流式高频状态避免扩大 Compose 重组范围；
- 错误信息给用户可行动建议，Trace 保留技术细节。

AgentTrace 记录执行节点并异步落库。FunctionRegistry 已统一 `traceId/callId` 的生成和贯穿规则；其他模块新增调用入口仍必须复用同一调用身份。

---

## 12. 安全、可靠性和性能预算

### 12.1 强制安全不变量

1. 工具调用默认放行，不设置 AUTO/CONFIRM/FORBIDDEN 安全等级或审批闭环。
2. LLM/MCP/IM/网页内容均按外部不可信内容隔离，不能覆盖 system policy。
3. 文件路径、URL、Intent、shell 参数、calendar/alarm ID 均执行域校验。
4. Android 系统权限与用户授权状态必须在执行前重新检查。
5. Key/token 不写入 Trace、Room 普通配置、聊天历史、Git 或导出包。
6. 数据升级失败必须中止并保留原库，禁止静默 destructive fallback。

### 12.2 时间与资源预算

- MessagePipeline：整体 3s，单 filter 强制 100ms；超时/异常隔离并记录轻量 trace。
- LLM/长工具可有较长 timeout，但 UI 必须可取消并有进度。
- MCP/Termux/Cloud 长任务使用 start/poll，不占用无限 FC 调用。
- 文件读取/下载双重检查 Content-Length 与流中累计值。
- Agent/Automation 必须同时限制轮次、步骤、Token、时长和重试。
- 本地模型资产与 APK 体积是发布预算，依赖引入必须记录体积增量。

---

## 13. 代码审查发现与优化建议

### P0：开发前先修

| # | 发现 | 影响 | 正确落地 |
|---|---|---|---|
| P0-2 | Room migration 安全 | 已完成 | 已移除 fallback、启用 schema 导出；5→21 注册链单测及 5/18/19/20→21 `MigrationTestHelper` 模拟器测试通过 |
| P0-3 | 测试地基 | 已完成基础纵切片 | core:data JVM/Room migration tests、core:bus MessagePipeline contract tests 已建立；其余模块按 Gate 4 扩展 |
| P0-4 | 100ms filter 契约未强制 | 已完成 | 每 filter `withTimeout(100)`，超时/异常单独记录 trace 并跳过当前 filter |
| P0-5 | `callId` 不一致 | 已完成 FunctionRegistry 纵切片 | caller 的 `ToolCall.id` 优先，贯穿 start/result/成功异常 Trace；其他调用入口继续收敛 |

### P1：结构与可靠性

| # | 建议 | 价值 |
|---|---|---|
| P1-1 | 抽取窄 `FunctionCatalog`、`ToolDispatcher` Interface | 拆开注册、发现、执行；审批闭环按用户裁决不建设 |
| P1-2 | 用 typed command 代替 UI 直接按 FC 名调用 | 消除字符串漂移和运行时参数错误；用户触发与 LLM 工具调用仍共享底层 use case |
| P1-3 | 合并 `isEnabled` 与 lifecycle 的状态真相 | 已完成运行态判定、崩溃禁用同步、可靠 `reEnable`、统一门禁、`runtimeStates` 状态流、详情页订阅及专项测试 |
| P1-4 | 为 `dependsOn` 构建并验证 DAG | 已完成结构纵切片；拓扑、环、缺失依赖和并发波次已覆盖，结果引用协议待专项定义 |
| P1-5 | 配置/密钥读取统一到一个 CredentialStore Interface | 删除 `ApiKeyProvider` stub 和分散回退链 |
| P1-6 | 为 Module ID、FC name、trace source 建常量/注册校验 | 启动即发现重复 ID/函数覆盖，而不是最后注册者静默获胜 |
| P1-7 | 统一协程 scope ownership | 每个常驻 Module 有明确 SupervisorJob，destroy 时取消，禁止泄漏 collector |
| P1-8 | 清理过大类 | Files 1189 行、Music 1608 行、TriggerHub 941 行、TermuxBridge 990 行等按内部 Seam 拆 Implementation，不扩大外部 Interface |

### P2：可维护性与性能

- 建立 Detekt/Ktlint 和 CI：clean assemble、unit test、lint、schema 校验。
- 用版本目录统一依赖，明确 sherpa jar 的来源、校验和与许可证。
- README 自动从 Gradle/Room 生成版本片段，避免当前“README 2.0.0/DB v14”与代码“1.0.0/DB v21”的漂移。
- AgentTrace 增加结构化字段，减少把业务信息拼进 summary 后再解析。
- 对 LLM tools 做按需装载/压缩，减少大量 schema 常驻上下文。
- 为 Compose 高频页面做基准与 Macrobenchmark，而非凭重组猜测优化。
- 对 zero-cloud 建 compose config 校验、Python tests 和 profile 级 smoke test；区分现役与 retired 目录。

---

## 14. 设计—实现偏移清单

| 设计/文档声明 | 当前代码事实 | 裁决 |
|---|---|---|
| Roadmap Phase 1–12 全完成 | 后续 v3.x/S/N/M/KB/MU/E 轨已大幅重写能力 | 根 Roadmap 仅为历史里程碑，不作当前进度 |
| FunctionRegistry 安全分级 | 主要调用路径默认放行，分级结构未形成审批闭环 | 取消执行安全分级；历史字段后续随接口收敛清理 |
| IMessageFilter 100ms 合同 | 只有注释，运行时仅有 pipeline 总 3s | P0 偏移 |
| AutoForm 零反射/编译期 parser | 当前 ModuleConfigParser 使用 kotlin-reflect | 以当前反射方案为准，除非性能数据支持重构 |
| README 版本 2.0.0 | Gradle 为 1.0.0 / code 2 | README 错误 |
| README core:data v14 / 12 实体 | 当前 Room v21 / 19 实体 | README 错误 |
| 模块间“绝不持有直接引用” | ChatBot、Automation 等直接注入具体模块 | 改写为“异步通知优先 Bus，同步请求允许显式依赖”；禁止虚假绝对化 |
| 所有 Room 写均 Mutex + @Transaction | Memory 有 Mutex；全库只有少量 @Transaction，多个域自行加锁 | 原声明过宽，应按聚合事务逐项补齐 |
| 构建完成即 Phase 完成 | 无自动化测试，大量真机矩阵仍未勾选 | 完成必须拆分 L1/L2/L3 |
| zero-cloud compose 即服务器终态 | compose 同时含 full/retired/历史服务，服务器实况可能不同 | 部署必须先 inventory，不可直接重建 |
| `ARCHITECTURE_FINAL.md` 是所有类/函数唯一真相 | 文件停留 v2.x，当前代码已演进到 v21 与多专项轨 | 本文档+代码成为当前入口；旧文档为历史依据 |

---

## 15. 验证策略与完成定义

### 15.1 分层验证

| 层级 | 内容 | 允许声明 |
|---|---|---|
| L0 | 静态审查、依赖/Schema/Manifest 对照 | 设计一致/发现偏移 |
| L1 | JVM unit/contract tests | 纯逻辑与 Interface 行为通过 |
| L2 | clean assemble + lint + instrumentation | 编译、资源、Room/Android 集成通过 |
| L3 | 模拟器/真机场景 | 平台权限、后台、音频、闹钟、通知、升级通过 |
| L4 | 长稳/故障注入/云端演练 | 可发布或生产就绪 |

### 15.2 每个任务的 Definition of Done

一个任务只有同时满足以下条件才可标记完成：

1. 需求和非目标明确；只完成一个可验证纵切片。
2. Interface、不变量、失败模式和权限语义已实现。
3. 新行为有测试；修 bug 先有失败复现。
4. `assembleDebug` 通过；涉及 Android 行为则完成对应 L3。
5. Room 变更有 schema、migration、升级测试和回滚说明。
6. 配置项在 UI、持久化、运行时消费三点闭环。
7. Trace/日志不泄密且能关联同一 traceId/callId。
8. 更新本文档的当前状态或专项进度，不复制出另一份“最终总设计”。

---

## 16. 后续 Agent 启动协议

### 16.1 环境准备

Windows 当前环境需显式设置：

```powershell
$env:JAVA_HOME='C:\Program Files\Zulu\zulu-21'
$env:GRADLE_USER_HOME='C:\Users\USER\.gradle'
.\gradlew.bat assembleDebug
```

未设置时，`gradlew.bat` 会尝试 `%JAVA_HOME%\bin\java.exe` 并失败；Gradle 用户目录错误解析到 `C:\.gradle` 时会因无写权限失败。Agent 必须先区分环境失败与源码失败。

### 16.2 每次启动步骤

1. `git status --short`，保护用户未提交改动。
2. 读取 `AGENTS.md` 与本文档。
3. 确认任务所属域，只加载对应专项设计。
4. 在代码中验证文档所述类、函数、schema 和调用点仍存在。
5. 先运行相关测试；若无测试，先补最小复现测试。
6. 一次只改一个纵切片，优先改 Interface 背后的 Implementation。
7. 构建失败就修复，不更新完成状态。
8. 涉及权限、后台、音频、SAF、Room 升级、Termux、云端时明确列出 L3/L4 验收。

### 16.3 当前推荐开发顺序

```text
Gate 0  文档入口收敛（本文档）
  ↓
Gate 1  FunctionRegistry 默认放行裁决（取消安全分级，不建设审批闭环）
  ↓
Gate 2  P0-2 Room migration 安全 + schema 导出
  ↓
Gate 3  P0-3 测试地基 + P0-4 filter contract
  ↓
Gate 4  callId/traceId、DAG、状态真相收敛
  ↓
Feature  在数据安全与测试地基上继续专项功能
```

Gate 1 已由产品裁决完成；不再新增依赖 AUTO/CONFIRM/FORBIDDEN 语义的设计。现存分级字段在后续接口收敛时清理。在 Gate 2 完成前，不应继续提升 Room version；在 Gate 3 完成前，不应做大规模模块拆分。

---

## 17. 本轮审查证据与未覆盖范围

本轮已核对：Gradle module/dependency、196 个 Kotlin 文件清单、Hilt module bindings、IZeroModule/lifecycle、ChatBus/MessagePipeline、FunctionRegistry 调用点与安全事件、Room v21/migrations、Manifest 组件与权限、测试目录、zero-cloud 文件/compose、README/ROADMAP/核心设计及多份专项落地文档，并完成 `assembleDebug`。

初始审查未执行：clean build、lint、单元/仪器测试、APK 安装、模拟器/真机操作、语音模型运行、后台长稳、旧数据库实际升级、Termux supervisor、zero-cloud 部署与服务器实况核验。后续 Gate 2 已补 Room 迁移模拟器测试，Gate 3 已补 core:data/core:bus 单测；其余能力仍不因本轮构建而升级为“已验证”。

---

## 18. 维护规则

- 本文档只保留当前态，不记录逐步施工日志；历史写入 `DEVELOPMENT_STATUS` 或专项落地计划。
- 每次架构变更只修改一个当前入口，不再创建新的“最终架构最终版”。
- 偏移修复后从 §14 移除，并在 commit/状态文档保留历史。
- 每次 Room 升级、权限模型变化、模块新增、运行环境变化都必须同步本文档。
- README 面向用户；本文档面向开发 Agent。README 不得承担精确接口和完成状态的真相源职责。

### 2026-10-02 媒体链路与对话视觉现状补记
- DeepSeek已验证官方图片模型补齐IMAGE_INPUT/VISION；USER图片走原生内容块，助手/工具结果保留引用。附件路线AUTO/NATIVE/TOOLS持久化，用户与LLM均可选择media_analyze或工作站动态工具。
- 图片/音频/视频均可导入和展示；音频文件独立解码后经ASR，MiMo API模型ID修正为mimo-v2.5-asr；视频为三个时点采样视觉分析，不含音轨。单附件5MB；大文件由工作站工具分段处理。TTS和attachment_publish返回媒体引用，MCP标准二进制媒体结果亦转本地引用。
- 所有助手正文统一新样式；自驱事件卡保留来源并主题化；附件路线FlowRow支持换行。MCP HTTP增量读取SSE、通知202、超时与取消契约修复；工具发现支持多关键词排序。
- 本节只记录当前实现。质量、APK版本与实机分项证据以同日实施进度/审计报告为准；全总纲、缓存96–98%、UI完整无障碍等仍未全部验收。

## 2026-10-02 审计修订补记：UI、知识索引与状态事实
- UI入口以AppDestinations为准：资料、工作区、设置按领域唯一归属；自主行为页统一承接agent_runtime节奏参数与memory整理参数，模块页使用上下文跳转。AppearanceStore提供应用明暗/动态色偏好，默认跟随系统，系统栏图标同步主题。
- 知识源仍为用户SAF工作目录。KnowledgeSource是读取边界；WorkDirRepository.TreeListing给出枚举完整性。KbDao.replaceDocument事务发布单篇元数据与片段，保留文档ID；rebuildIndex强制重读而不预清空。Room仍v26。完整影子索引和embeddingSpaceId尚未完成，不能将逐篇替换描述为全库原子发布。
- 模型状态分离凭据配置与连通性：后台实际解析凭据链，仅投影Boolean?和观测时间；未探测连通性为UNKNOWN，不能因apiKeyRef非空称LIVE。真实provider健康观测、索引/部署版本事实仍有验收差额。
- 本轮版本/门禁/实机边界见实施进度与审计报告末尾：UI包已实机验收；合并UI/知识索引/状态修订候选包D250EFBC…已构建但未安装，数据库事务实机测试按用户要求后补。
### 2026-10-02 最新合并修复版实机安装与补验
- 用户重新要求实机安装测试后，已对 DEVICE_SERIAL 执行 adb install -r，返回 Success；未清除应用数据。MainActivity 启动成功，手机实际 base.apk SHA256 与 ZeroV2-ui-kb-status-candidate-debug.apk 一致：D250EFBCD820E935F7A2B4F688FF597D957E479243C7CA8A9F43634FDA538091。此记录取代上节“候选未安装”的当前状态。
- 独立数据库测试包本次安装 Success，保持手机安装校验；KnowledgeTransactionTest 实际执行 OK (1 test)，0.111秒。隔离的内存 Room/SQLite 数据库验证片段唯一键冲突后标题、元数据及旧片段回滚，随后正常替换成功，未操作用户数据库。证据：artifacts/stability-20261002/kb-transaction-device-retry.log。原“稍后补验”项现已完成。
- ui_acceptance_d250.py 在本次 D250 包实机运行退出0：设置→能力与模型→Agent运行时→自主行为设置→返回原详情→列表→设置；深色切换及恢复跟随系统；工作区→自动化任务→返回工作区全部走通。
- 已目视复核 d250-ui-appearance-dark-final.png 与 d250-ui-workspace-final.png：深色系统栏图标可读，浅色状态卡文字可读；模型显示“6 已启用 · 6 已配置凭据 / 连通性待验证”，Termux 显示已连接。该模型显示只证明凭据配置事实，不代表六模型请求均成功。
- 本轮仅安装和实机补验，无生产代码修改，沿用 model-health-final-quality.log 已通过的构建和353项JVM结果；未重复运行付费provider、多模态、完整缓存cohort或长期自驱验收。完整影子索引/embeddingSpaceId、S1剩余状态事实、E恢复及其余UI场景仍待后续推进，不标全计划完成。

### 2026-10-02 S1积压投影与本轮验收汇总
- S1积压补齐：Knowledge和Memory分别按当前空间计数NULL/旧空间向量，再合并记忆提取作业；观测途中空间变化不发布新计数，保留原时间供TTL识别陈旧。只读SQL，无模型探测、无工具schema或稳定前缀改动。
- kb-backlog-quality.log完整qualityCheck及数据库测试APK构建成功：522任务（119执行），BUILD SUCCESSFUL；本轮最终JVM XML合计360项＝359通过/1既有跳过/0失败/0错误。
- kb-backlog-device.log：KnowledgeTransactionTest OK (3 tests)，0.238秒；逐篇回滚、整批跨文档回滚/成功删除、Memory及KB旧空间积压真实SQL均通过。使用隔离内存Room库，未修改用户数据库。
- 新制品artifacts/stability-20261002/ZeroV2-kb-space-debug.apk，SHA256 B5A6BD49FC9889E460B021BD68470381F0961E21C98DA0F85713822D0A1620AD。本轮不改变schema（Room26），保留旧向量及原文；旧空间后台重嵌使用既有配置，provider不可用时词法保持可用。
- 验收边界：本轮未重跑真实provider、完整缓存cohort、多模态、全UI或长期自驱频率；不得把既有实机结果自动迁移成新包全量验收。R2持久化影子向量/中断恢复/召回发布门、100/1k/10k设备基准，S1模型健康及部署事实，E故障恢复和剩余UI验收仍未全部完成。
- 后续接续：优先补R2持久化候选和召回发布门（当前强制重建仍是有界内存候选+Room整批事务，超400万字符拒绝发布而保持旧索引）；随后完成S1真实观测和E/UI/长期行为与生产缓存全样本门。工作树保留，不提交混合前序修改，不标全计划完成。
- 最新安装确认：adb install -r 返回Success，手机base.apk SHA256与B5A6BD49FC9889E460B021BD68470381F0961E21C98DA0F85713822D0A1620AD完全一致；am start -W 返回Status: ok，保留应用数据。本次启动检查不等于全部运行场景验收。

### 2026-10-02 A1.2/A1.3阶段验收与接续
- A1.2：ToolExecutionGuard由ModuleLifecycleManager实现，注册器经Provider延迟调用；源owner未知/禁用/销毁时不进入handler，意外异常走safeCall，取消透传，核心异常/正常业务失败不误禁用。静态与MCP后注册工具均走该入口，动态子模块原有启停/急停校验保留。
- A1.3：声明刷新先生成新快照，移除本owner退役声明工具，保留动态注册项；重复刷新幂等、注销后不可执行、跨owner名字冲突不覆盖。目录读写用不可变快照和写锁，避免迭代可变Map。acceptance-a13-red.log明确旧名字残留先红；修后通过。
- UI清理：移除模块详情AUTO/CONFIRM/FORBIDDEN标签、MCP无效权限按钮和权限文字。旧autoPermission仍由McpServerStore读取/写回，以兼容存量配置，不再表示运行审批；ActionPermission/FunctionSecurity作为存量声明API元数据保留，不能据此恢复旧全局确认。
- 完整qualityCheck：acceptance-a13-quality.log，496任务（95执行）BUILD SUCCESSFUL。追加核心/销毁/未知owner边界后acceptance-a12-owner-final.log模块全测及assembleDebug成功235任务。最终JVM XML368项＝367通过/1既有跳过，0失败/错误；ToolOwnerExecutionTest六项覆盖真实注册器与生命周期管理器。
- APK：artifacts/stability-20261002/ZeroV2-framework-guard-debug.apk，SHA256 2B4E0781F51AEA1EB42ACE077D31BC954C6F35D3FB1E70670253D0C04C566947。adb覆盖安装Success，手机base.apk哈希完全一致，am start -W Status: ok。未清数据，未发送provider测试消息。
- ui_guard_acceptance.py实际检查知识库详情的可用工具区、MCP设置及返回；acceptance-a1-device.log最终PASS，截图a1-guard-module-detail/mcp-settings。最初知识库条目在屏幕外导致定位失败，修正为实时滚动后通过；不计为应用失败。此结果不代表全页面样式/无障碍验收。
- A1.2/A1.3关闭，下一片A2a持久化候选/中断恢复；不得将现有400万字符内存候选当持久化完成。Termux真实发布恢复仍归A6，缓存、多模态与长期自驱不因本轮测试外推通过。


### 2026-10-02 A2b.1当前实现补记

Room当前源码v27，新增向量版本/候选/active指针/验证报告四张派生表。VectorIndexDao已经过14项隔离实机事务/迁移测试；原数据及旧向量保留。该存储层尚未被MemoryModule、KnowledgeModule、HistoryCompressor生产回填/查询接入，运行时仍是旧列读写；因此不是完整影子向量发布已落地。A2b.2/A2b.3及证据边界以验收推进计划末节为准。手机主应用目前仍为A2a（Room26），本轮仅更新测试包。


### 2026-10-02 A2b.2当前实现补记（接续上一条）

VectorIndexRuntime/VectorIndexWorker/VectorCandidateBuilder已接入Memory、Knowledge及历史压缩的生产写入与查询路径，取代逐条回填旧embedding列。构建只到VALIDATING；已有合格报告才可晋升，生产真实召回评估器仍待A2b.3，因此不是完整影子向量发布验收。首个已发布版本前只读兼容旧列，之后固定版本分页查询。知识库状态和系统积压改读版本owner；Memory暂时失败由既有Scheduler低频重试。Room仍27；最终qualityCheck和24项隔离实机回归通过，主应用本轮未安装候选包。详细证据和限制见验收推进计划末节。

### 2026-10-02 A2b.3a实现补充
VectorIndexWorker新增evaluateAndPublish，使用显式标注VectorRecallDataset计算逐样本Recall@K、类别Recall和禁止来源命中；全量合格后approve→publishValidated。生产build尚不自动触发评估，真实固定语料与provider质量验收未完成。15项隔离实机流程回归与qualityCheck通过，不能据此外推A2b完成。详见验收计划同日A2b.3a记录。

### 2026-10-02 A2b.3b独立基准决策与实现
用户确认固定独立基准＋候选完整性，个人资料抽样另验。生产VectorIndexWorker自动调用VectorBenchmarkGate（12文本/6查询），合格才审批发布；拒绝报告持久化避免同候选重复请求。VectorEvaluationJournal与evaluationReport/kb_status提供基准摘要和当前active核对；个人标签支持精确内容摘要重关联。qualityCheck与22项隔离流程测试通过，真实provider成绩与主应用升级尚未验收。详见独立召回基准文档和验收计划A2b.3b/c。

### A2b.3c真实验收接续
已增加debug-only主应用作用域验收入口，独立内存Room验证真实EmbeddingProvider。401在provider内进程级熔断，配置变化后下次构建恢复；worker不对鉴权失败安排30分钟自动重试，HTTP响应均关闭。qualityCheck及23项隔离实机测试通过。凭据更新后Knowledge基准6/6通过、Memory4/6未通过，A2b.3仍未完成；实际主APK已覆盖安装并核对SHA256，详见进度/审计报告最新条目。

### 2026-10-03 A3.1来源修订
自动用户事实改由处理前用户原文及消息/会话来源驱动，停止助手回复单独提炼；UserFactExtractor摘录原句、来源校验后经outbox事务提交。模型手工记忆工具保存EVENT笔记，不自动认证用户FACT。旧助手队列退役，报告的两种无来源低置信度模板事实可恢复归档；图谱新输入仅使用有来源的新用户事实。自驱提示去掉预设用户偏好与强迫活跃示例。完整门禁及8项JVM/3项隔离SQLite回归通过，历史变体、图谱存量语义与长期队列恢复仍待验收。A2b.3c Memory召回4/6保持未通过；详细证据见实施进度末节。

A3.1补验：首轮真实模型将模板投诉摘出1条事实；增加上下文独立性与反馈语义筛选后，同例templateFactCount=0且显式偏好保留。最终9项记忆JVM、3项隔离SQLite、1项两请求真实语义测试通过；qualityCheck 530任务成功。最终APK AB9BBBE4…4112CDE5已保留数据安装并核对设备摘要。该结果不证明全部历史污染清理或长期提取准确率，A3与A2b.3c仍待后续验收。

### 2026-10-03 A2b.3c实现补充：有界语义重排

当前实现增加BoundedSemanticReranker：记忆工具在RRF/Scoring后重排，知识库工具在融合和过滤后重排。最多8条短候选/单次模型请求，稳定只读排序提示、匿名slot、严格排列校验、HTTP 2.5秒/调用3秒截止、128输出tokens、5分钟64项内存缓存；失败保留原排序并记录状态。不在自动证据注入/相似度去重中发起重排。Memory返回前在写锁＋Room事务内重检来源并计访问；Knowledge在只读事务中重检文档和片段。Room版本仍27。

DashScope官方支持域名使用原生query/document契约，其他兼容服务保留原选端点。响应按显式索引归位并拒绝错位/非有限/零向量。对应空间身份版本化。真实v3的原生与兼容向量完全一致，v4同样在培训更正样本失败；因此purpose修订与换模型不是语义召回已修复的证据。

固定独立基准门禁同时记录原始向量、最终检索、排序策略及各样本状态，重排失败回退不批准最终管线；模型策略改变显示历史报告不再匹配。当前active索引不会仅因聊天模型改变自动重嵌或重新认证。个人标签评估器仍为原始向量评估，报告明确标记personal_labelled_vector；个人资料最终重排抽样和生产完整融合路径的质量仍需独立验证。

本地qualityCheck及assembleDebug通过（semantic-device-ready-quality.log，558任务），JVM405项/0失败错误/1既有跳过；services隔离实机29项通过。真实provider最终重排基准正在验证，不据此宣布A2b.3c整体完成。

### 2026-10-03 A2b.3c.1收口：有界语义重排固定基准通过

- 最终真实证据：semantic-v2-real-device.log，OK (1 test)，12.52秒；semantic-v2-real-report.json及semantic-v2-summary.json。当前已配置embedding与CHAT模型通过主应用Hilt取得，基准仅写独立内存库；未将合成数据写入个人资料。两个域固定datasetDigest与首轮完全一致。
- 结果：原始向量Memory5/6（room错误）、Knowledge6/6；最终重排Memory6/6、Knowledge6/6，SOURCE/NEGATION/CORRECTION各组均1.0，禁止命中0，两个候选均CURRENT/published=true（仅隔离库）。原始向量缺陷仍在报告中，未通过改样本/标签/门槛隐去；不能把最终检索通过描述为向量模型本身已修好。
- 最终v2响应的脱敏槽位计数包含1/2项子集，说明真实模型确会用短子集返回。程序严格验证子集并稳定补齐剩余候选，不丢失/编造资料；JSON代码围栏可解析。首轮没有存原响应，仍不声称已还原全部6个invalid_response的原始内容。
- 预算实测：12次额外CHAT请求，input=4399、output=56、cached=0 tokens；延迟min345ms/中位569ms/P95最近秩840ms/max840ms。仅本组短调用，不是长期P95/SLA或全应用性能基线。新增请求会影响整体加权缓存比率，后续A10必须与主对话cohort合并报告；本轮没有宣称96–98%通过，没有填充prefix。
- 本地最终：semantic-v2-final-quality.log，qualityCheck/assembleDebug及测试包构建BUILD SUCCESSFUL，558任务（65执行）；semantic-v2-jvm-summary.json记录406项＝405通过/1既有跳过，0失败错误。新增6项重排和4项HTTP预算单测通过。前次29项隔离SQLite回归通过；最终协议版本再验semantic-v2-isolated-device.log，OK (10 tests)，1.775秒，覆盖语义门禁与来源失效。未重跑无改动的整组29项，长停顿原因仍未证实。
- 最终APK：artifacts/stability-20261003/ZeroV2-bounded-retrieval-debug.apk；SHA256 A20DB4D04F0F535BEAB23E9831706480D7CE18CC544EB9BF688001DF4F1F336D。semantic-v2-install.log覆盖安装Success；semantic-v2-installed-sha256.txt的手机base.apk摘要完全相同；semantic-v2-start.log为MainActivity Status: ok（WARM，661ms，仅启动检查）。未卸载/清数据，Room仍27；手机恢复正常主界面。
- 本片A2b.3c.1固定基准阻塞修订已完成。A2b.3c整体保持未勾选：完整生产RRF/评分/过滤路径与个人资料最终重排质量、真实配置切换/断网恢复还需独立验证；个人标签评估器目前明确为原始向量模式。聊天模型切换后旧报告显示策略不匹配，不自动重嵌/重新认证active。超预算长资料、无模型/超时/并发忙保留原排序，不能保证这些回退场景有同样语义质量。
- 后续按序：补A2b.3c生产路径与真实恢复证据，再推进A3剩余来源/纠正删除/长期作业和备份恢复；A4大规模召回、A7–A9 UI统一/动画、A10缓存与自驱均未由本片外推通过。保留全部既有工作树改动，不reset/clean、不混合提交。

### 2026-10-04 A2b.3c.2收口：知识库纯本地模式契约修订

- 根因：kb_search的向量路径受hybridEmbedding控制，但最终rerankCurrent未受同一开关控制，关闭状态仍可能发起云端请求并改变顺序。真实工具入口回归先红后绿：local-search-red.log / local-search-green-build.log。
- 实现：关闭时跳过BoundedSemanticReranker，记录local_only，保留KbDao.currentRecall来源有效性检查；开启时保持现有有界重排。复用既有配置key/入口，说明同步包含向量与重排，不改变保存值。已发出的请求不因事后关闭被追溯撤回。
- 验证：KnowledgeSearchContractTest一项多阶段测试，真实ToolContribution.handler＋内存DAO/模型替身，关闭→开启→再次关闭并更换查询，对应累计重排调用0→1→1、返回本地→重排→本地顺序。该用例验证生产开关接线，不是SQLite实机测试或个人资料召回质量证明。
- 最终local-search-quality.log：qualityCheck BUILD SUCCESSFUL，500任务（29执行），含assembleDebug；local-search-jvm-summary.json为407项＝406通过/1既有跳过，0失败错误。本轮未新增付费provider探测或复跑既有固定语义基准；主对话96–98%缓存门仍未关闭。
- APK：artifacts/stability-20261004/ZeroV2-local-retrieval-debug.apk；SHA256 C180AF9D1C7C2866A56BCE1A12FDAF99966C05D3D3178CCFB08A22C44A5AD1AA。手机DEVICE_SERIAL的ADB install -r返回Success，base.apk摘要完全一致，MainActivity启动Status: ok；证据local-search-install.log、local-search-installed-sha256.txt、local-search-start.log。未清数据或改用户配置，Room仍27。此为覆盖安装/启动检查，未宣称页面交互或实机网络隔离已经验收。
- 本片完成，A2b.3c整体保持未勾选。接续完整生产融合路径与个人资料抽样、真实配置切换/断网恢复证据，再按计划推进A3–A11；固定基准成绩、个人质量、设备性能、缓存指标分别记录。保留开工318项既有工作树变更，未reset/clean或混合提交。

### 2026-10-04 A2b.3c.3收口：范围检索过滤前移

完成词法、旧向量和已发布索引三条路径的范围过滤前移。kb_search保持原路径前缀/标签语义，过滤发生在候选上限和Top-K之前，再进入融合与有界重排；范围外高排名内容不再挤掉范围内命中。ScopedDocumentQuery以500文档ID分批，累计保留最多limit条，单次合并最多两倍limit条，不一次装载全部向量。VectorIndexRuntime搜索新增可选知识库文档集合，Memory及无范围查询沿原路径；范围集合快照后使用，空范围不新增embedding请求。

证据：scoped-search-red.log真实工具入口先失败；scoped-search-quality.log完整门禁成功（528任务），408项JVM无失败错误/1既有跳过。scoped-search-device.log手机隔离Room九项通过，包括450片段、503个文档ID条件（501不存在＋2有效）、跨页/分批合并、空范围请求计数和已更正来源失效。新夹具最终构建见scoped-search-device-build.log；本片未调用付费模型，未改变固定基准和门槛。

最终APK：artifacts/stability-20261004/ZeroV2-scoped-retrieval-debug.apk；SHA256 67F03724169796EF7AE4B527A374FA9BF4696DB5869E6A0D17B1FE2EA516ADF0。手机DEVICE_SERIAL保留数据覆盖安装Success，base.apk哈希相同，MainActivity启动Status: ok（LaunchState UNKNOWN (0)，只记启动结果，不作性能结论）；证据scoped-search-install.log、scoped-search-installed-sha256.txt、scoped-search-start.log。未清数据或改变配置，Room27不变。

本片已完成，A2b.3c整体继续未勾选。后续仍须生产完整融合/重排质量、个人资料抽样及真实配置切换/断网恢复；大规模性能、整体缓存96–98%、UI与自驱验收保留各自任务。当前设备测试证明过滤和索引机制，不等于真实provider在个人语料上的质量通过。开工318项既有变更全部保留，未reset/clean或混合提交。

### 2026-10-04 A2b.3c.4：READY候选在重排策略切换后的恢复

- 审计发现：候选在approve后、publish前中断，重启期间切换重排模型，旧验收被发布守卫拒绝，但preflight位于候选异常恢复范围之外，每次恢复均抛vector_ranking_policy_changed，READY候选无法退出。不是个人数据丢失，也不是放宽验收可解决的问题。
- 修订：VectorCandidateBuilder将preflight纳入现有候选失效处理；失效候选在write Mutex及Room事务下废弃，worker沿用最多一次即时重建。旧活动版本保留；新候选仍需当前策略完整基准验收，暂不可用返回RETRY并沿用30分钟调度退避。取消继续传播，不绕过摘要、源完整性或质量门槛。代价是废弃候选中无法从活动版本复用的新增向量需重新计算；未引入无限重试。
- 先红后绿：ready-recovery-red-device.log在真实手机隔离Room复现vector_ranking_policy_changed；ready-recovery-green-device.log最终OK (10 tests)，2.737秒。新增用例串联已有活动索引、新增资料、READY中断、换策略、重排不可用、服务恢复，断言旧索引保留、旧READY不发布、重新验收的新版本晋升且报告策略一致。其余为原门禁及范围检索回归，均使用合成资料与确定性provider，无个人库写入或付费请求。
- ready-recovery-quality.log：qualityCheck＋services测试包构建BUILD SUCCESSFUL，528任务（82执行）。ready-recovery-jvm-summary.json：408项JVM，407通过/1既有跳过，0失败错误。Room仍27，未更改工具schema、稳定前缀或配置入口。
- 最终APK：artifacts/stability-20261004/ZeroV2-ready-recovery-debug.apk；SHA256 7B0CEE3ABCAFAF8BAD50AED5395326237B5993CB1715BB0745771FFDEDACE293。DEVICE_SERIAL保留数据覆盖安装Success，base.apk摘要完全一致，MainActivity启动Status ok（WARM）；ready-recovery-install.log、ready-recovery-installed-sha256.txt、ready-recovery-start.log为证据。启动耗时不作为性能验收。
- 本片完成，A2b.3c整体仍未关闭。下一步仍按生产完整融合/重排质量→个人资料抽样→主应用真实配置/断网恢复推进；本片的模拟策略切换/服务不可用不等同于真实网络或主应用进程恢复验收。A3历史污染、A7–A9统一UI、A10整体缓存/自驱等余项保持原状态。保留所有既有工作树变更，未提交混合修改。
### 2026-10-04→05 A2b.3c.5–7：生产工具、真实网络恢复与个人抽样

本轮继续保留现有318项工作树变更，补齐验收证据。新增入口均为debug/androidTest；不新增正式设置或工具，不变更数据库schema及固定基准标签。A2b.3c.5验证实际memory_recall/kb_search handler内的候选查询、RRF、Memory评分、过滤、重排与来源复核，绕过LLM自动选工具及正常Application后台启动，因此不是全应用交互或调度器验收。个人资料与固定合成基准分别报告。

1. A2b.3c.5：首轮production-recall-device.log通过，12题双域各6/6、禁止来源0；工具耗时560–1174ms，单次小样本不作SLA。首轮Memory夹具importance默认0，随后明确设为0.5使评分参与排序；以最终复跑报告为最终证据。通过debug-only RecallAcceptanceFixture复用现有基准，未复制/改写标签；资料ID随机化，模型仅收到正文和正常知识标题，无答案标签泄漏。
2. A2b.3c.6：专用持久Room在不同instrumentation进程中执行seed→offline→recover→switch，真实已配置provider与真实备用v3/v4模型；四阶段均OK (1 test)。真实断网时ConnectivityManager.activeNetwork为null，双域RETRY、旧active与原来源仍可读取、重试时间有效。恢复网络后双域CURRENT且新版本发布；切换实际备用模型后两个space均变化，新候选验收通过。手机保存的profile/密钥不修改；换档为验收provider实例切换，不是用户设置UI操作。网络原Wi-Fi/移动数据均为1，finally后均恢复1。专用测试库已cleanup通过，无个人库清空或合成资料污染。
3. 证据位于artifacts/stability-20261004/：recovery-{seed,offline,recover,switch}-report.json及对应device.log（恢复日志名recovery-online-device.log），recovery-wifi-restored.txt、recovery-data-restored.txt、recovery-cleanup-device.log。seed10.630秒、offline0.476秒、recover11.652秒、switch12.509秒；固定基准评估仍保持原门槛。断网发生在新增条目嵌入前，不外推“任意已完成批次断电”；部分批次持久恢复仍引用既有隔离回归。
4. A2b.3c.7：用户给出四个个人抽样问题：我的喜好、我最近在做什么、我们最近做了什么、我们的约定。已增加真实个人库观察入口，恢复保存的模块配置，以top_k=3读取两域；只执行检索，Memory沿正常路径更新访问计数，核对正文/ID不变。问题尚无独立正确答案，qualityVerdict必须保持pending_user_labels，不允许把模型生成总结或未标注结果当Recall合格。结果待实机观察后补记。
5. 本地a2b-final-quality.log完整门禁和测试包构建成功，530任务（73执行）；a2b-final-jvm-summary.json为408项、407通过/1既有跳过、0失败错误。新增实机网络请求仅由显式instrumentation opt-in触发，不进入qualityCheck。仍不声称A10整体缓存96–98%已通过，短重排调用须计入后续加权指标。

本节是验收推进记录，A2b总体暂不勾选；个人抽样证据及最终复跑/安装摘要将在后文收口，不能用已通过机制测试替代未标注的个人质量结论。
### 2026-10-05 A2b验收收口（限定范围通过）

A2b的版本化向量构建/完整性/独立召回发布门、生产工具检索、真实网络恢复与模型换档，以及本轮用户确认的个人最低覆盖检查已完成。ROADMAP与计划总表同步勾选A2b.3/A2b.3c；这是A2b规定范围的验收，不是全部个人事实、全应用交互或整体验收通过。A3历史来源污染与完整纠正/删除，A4规模，A7–A9 UI，A10整体缓存/自驱仍保留原门槛。

#### A2b.3c.7：真实个人抽样触发的读取侧修订

- 在1373条ACTIVE记忆、83篇知识文档上，按用户给定四个问题调用实际memory_recall/kb_search，恢复保存的模块配置。主Application正常后台初始化被验收runner抑制，直接调用生产handler；Memory访问计数按正常检索更新，ID/正文前后完全一致。工具返回结果观察不自动等于语义质量通过。
- 首次发现同文不同ID重复占位、无会话来源的旧提问回声和附带旧运行提示的提问。MemoryRecallContractTest真实Room先红（重复次数expected1/actual2）后绿。MemoryRecallCandidates仅在读取侧按正文末尾标点去重；对少量明确“我/我们最近做什么”等纯提问形式过滤，历史问题查询仍可检索；有会话来源的记录、带实际陈述/回答的文本不按该规则删除。候选筛选先于词法整句短路及融合上限；RRF按同文合并，每条路先去重，不因重复记录叠加权重。原资料未合并、删除或归档。
- 不把这一修订描述成历史污染已清理：无来源且高置信的旧“微信偏好”仍需A3核验，其他问句变体也不能声称全部覆盖。证据personal-recall-observation.json、personal-recall-after.json含个人资料，仅作为本地验收观察，不复制正文到公开报告。

#### A2b.3c.8：近期概览与超长邻项阻断

- 用户确认候选答案“基本正确但不全面”，据此冻结personal-minimum-20261005-v1的四条最低要求。原始两域合并top3覆盖2/4，见personal-minimum-red.json；不把不全面的最低答案当完整个人知识标签集。
- 对明确“我/我们最近/近期做什么”概览，RecentRecallWindow将本地融合候选上限保留到100，在原8条重排窗口中最多为4条带显式ISO日期的资料留位，并保留语义候选和全部尾部。Memory读正文日期、Knowledge读标题链日期；不使用访问/索引时间替代事件时间，不把未来或非法日期当近期证据。普通专题检索保持原窗口，模型失败回到原融合/评分顺序；没有新增模型请求。
- 首次近期修订仍未通过：recent-diagnostics.json证明知识库两次rerank=input_limit；已确认目标片段自身仅102–367字级别，超长邻项使整次排序回退。oversize-neighbour-red.log新增回归明确失败，未通过改题或降低门槛掩盖。
- bounded-semantic-v3：在最多100个候选中选择不超过600字符的至多8条，仍只发一次、HTTP2.5秒/外层3秒、128输出token。超长内容不截断、不发给模型，未入选条目以原相对次序保留在已重排集合后；预算内不足2条时回退原序。所有ID仍恰好保留一次，匿名slot与响应严格校验、缓存/策略校验不变。策略版本升级使旧排序缓存/拒绝报告不被当成v3结果；旧active向量不因单纯排序策略变更自动重嵌，历史验收报告策略不匹配仍明确显示。

#### 最终证据与结果

- a2b-v3-quality.log：qualityCheck＋app/services测试包构建BUILD SUCCESSFUL，558任务（87执行），包含assembleDebug；a2b-v3-jvm-summary.json为413项＝412通过/1既有跳过，0失败错误。此前静态检查2项已实际修正，未新增豁免。
- a2b-v3-device.log：最终手机三项测试OK (3 tests)，38.190秒，覆盖重复/问句回归、真实生产固定基准、真实个人观察；观察用例只断言工具成功和资料未改动，最低覆盖由独立脚本另验。
- production-v3-report.json：固定数据/标签/分类门槛未变；生产Memory、Knowledge各6/6，SOURCE/NEGATION/CORRECTION均全命中，禁止来源0。主应用已配置provider，合成资料仅在独立Room；完整handler路径有实际融合/评分/来源复核，不等于LLM自动选工具或UI对话端到端。12次工具耗时598–1152ms，单轮小样本不作SLA。
- personal-v3-after.json：个人八次工具查询均重排applied，正文/ID不变；check-personal-minimum.ps1对同一最低标签集复核，personal-minimum-v3.json为4/4。口径是Memory与Knowledge各top3的合并最低覆盖；两条近期问题可以共享同一相关来源，不能称为四份独立事实，也不等于Memory单域4/4、Precision100%、完整答案或长期召回质量。用户已说明答案不全面，后续更完整个人标签归A3/A4继续补充，不覆盖独立发布基线。
- 真实网络/换档结果沿用本轮A2b.3c.6：seed→真实离线→新进程recover→实际备用模型switch，四阶段全部通过，原网络状态已恢复，专用持久库已清理。此测试在相同主应用UID和provider、独立资料库完成，不外推用户设置页面交互、正常后台调度或任意断电时点。
- production-v3-ranking-usage.json：固定基准构建门禁12次＋实际工具12次，共24次短重排，input8790/cached4480/output116 tokens。该表不含随后个人抽样或全应用对话，不能作为整体96–98%缓存结论；A10须合并所有调用成本，未填充稳定前缀。

最终APK：artifacts/stability-20261004/ZeroV2-a2b-v3-debug.apk。
SHA256：036D526763DE1FE47A0897235BF23315B12F9A2128C8140CDF9ED331223AE177。
DEVICE_SERIAL保留数据覆盖安装Success；a2b-v3-installed-sha256.txt与本地完全一致，a2b-v3-start.log为MainActivity Status ok/WARM。Room仍27，未清用户数据、改已保存profile或新建设置入口。原工作树全部保留，没有reset/clean或混合提交。下一片按计划进入A3剩余来源污染、纠正/删除联动及长期作业/备份恢复。
## 2026-10-05 A3.2：图谱属性更新的事务回滚

- 实机先红：旧路径先关闭旧属性再插入新值；SQLite trigger 注入插入失败后，当前属性为空（expected old / actual []）。证据：artifacts/stability-20261005/graph-atomic-red-device.log。
- 修订：UserGraphDao.replaceCurrentAttribute 使用 Room @Transaction 包含关闭旧区间及写入新值，GraphExtractionJob 改用此入口并以任务级 Mutex 串行执行。网络请求不在数据库事务内；Room版本仍27，无资料迁移或清理。
- 实机后绿：GraphAttributeTransactionTest 同时验证失败回滚、恢复后的成功更新与历史区间；连同 MemorySourceTransactionTest 共4项通过（隔离内存Room，不修改个人资料）。证据 graph-atomic-green-device.log，0.47秒。
- qualityCheck＋core:data:assembleDebugAndroidTest BUILD SUCCESSFUL，526 tasks（124执行）；包含主APK编译。graph-jvm-summary.json：413项，412通过、1既有跳过、0失败错误。全部证据位于 artifacts/stability-20261005/。
- 验收边界：本片只保证单个属性版本替换原子性及同一提取任务串行化；不是整批图谱写入、跨进程恢复或来源可信性完成。旧工作树改动保留。
- 后续顺序：A3.3绑定原记忆证据并复核来源/纠正/删除联动；A3.4持久化提取进度与完整批次恢复；A3.5历史污染抽样和备份恢复。当前仍存在属性evidence未填、模型自报source、内存时间游标重启跳过存量及50条上限推进可能遗漏的问题。不得把A3.2通过视为A3整体通过。

交付确认：ZeroV2-a3-atomic-debug.apk 已通过 adb install -r 保留数据覆盖安装，graph-app-install.log 为 Success；本地与手机 base.apk SHA256 均为 372522ACD1158861460F0037645D7FA382937C8FC571F7A58B434F143702A96A。graph-start.log：MainActivity Status ok，启动仅作冒烟，不作性能验收。APK：artifacts/stability-20261005/ZeroV2-a3-atomic-debug.apk。

## 2026-10-05 A3.3a：图谱属性来源绑定与读取失效

- 先红证据：graph-source-red-device.log（1项失败）复现来源记忆删除后getCurrentAttributes仍返回图谱属性。独立内存Room，不操作个人资料。对应总纲§6“删除后不可召回/待处理结果不可复活”。
- 实现：GraphAttributeGrounding要求有效的输入memoryIndex，只接收带会话来源的ACTIVE user-fact FACT；宿主保存完整原句（最多400字，不截断入库），以保留否定、更正与条件。模型负责分类，不能通过value/source/confidence改写原句或自证USER_STATED；新属性统一EXTRACTED且置信度不高于0.4及源记忆置信度。分类仍是模型推断，不宣称分类语义完全正确。
- 来源使用现有evidence字段中的memory:v1引用，包含编码的记忆ID及正文/会话/创建时间指纹，不新增Room表或迁移。replaceGroundedAttribute在Room事务内复核当前来源后才关闭旧属性并写新属性；模型请求期间来源被改动、删除、归档则拒绝提交，原属性不受影响。
- getCurrentAttributes在读取事务中复核版本化来源，来源消失、非ACTIVE、正文/会话/创建时间变化时不返回；普通访问计数/时间变化不触发失效。旧无来源属性保持原状，保留历史行供后续审计；这不是物理删除，也不代表旧污染已清理。
- 单测新增3项：否定完整保留及禁止模型自提置信度、无效来源序号、无来源笔记/超长来源拒绝。graph-source-jvm-summary.json：416项＝415通过＋1既有跳过、0失败错误。
- graph-source-quality.log：qualityCheck＋core:data:assembleDebugAndroidTest BUILD SUCCESSFUL，526任务（124执行），含主APK编译。graph-source-green-device.log：7项通过，0.412秒，包括删除后不返回、来源更正/归档失效、访问计数不误失效、延迟提交不覆盖旧值、原子回滚与原记忆来源回归。
- 证据均在artifacts/stability-20261005/。本片未调用真实provider验证新的图谱提取prompt，亦未验证正常后台调度和完整用户对话；实机结论限于生产DAO与真实SQLite，模型解析规则由JVM验证。未增加模型请求次数或填充缓存前缀。
- A3.3剩余：关系边/实体的来源绑定、历史无来源图谱抽样、纠正冲突选择和物理删除联动；A3.4持久化游标/整批原子提交及重试（当前无效模型属性可能被跳过并推进游标，尚未解决）；A3.5备份恢复。getAttributes历史读取及存储计数仍不是当前有效来源投影，不能外推本片为所有图谱接口闭环。A3整体保持未完成。

交付确认：artifacts/stability-20261005/ZeroV2-a3-source-debug.apk 已adb install -r保留数据覆盖安装（Success），本地与手机base.apk SHA256一致：8CDA209F1C5318D109E765C38C67162736CDA6880B2501D679B2CC0EF4B5DA7D。graph-source-start.log为MainActivity Status ok，启动仅作冒烟验证。工作树既有改动保留；Room仍27。

## 2026-10-05 A3.3b：关系边来源绑定与迁移验收

- 先红：graph-edge-red-device.log在隔离内存Room复现删除来源记忆后关系仍返回（1项失败）。此前关系没有来源字段，无法执行属性同等的来源复核。
- Room 28新增user_graph_relationships.evidence可空TEXT字段；27→28仅ALTER ADD COLUMN，保留全部旧关系及历史区间，旧evidence为null，不伪造来源。28.json已导出；graph-edge-schema-diff.json确认仅关系表发生变化。README受控构建事实同步28。
- GraphRelationshipGrounding要求有效memoryIndex，宿主从ACTIVE、有会话来源的user-fact FACT生成引用；不接受模型自报evidence。生产GraphExtractionJob关系入口改为replaceGroundedRelationship；沿用任务Mutex，在Room事务中复核来源正文/会话/创建时间指纹，再关闭相同from/to/type旧区间并写新边，失败回滚。
- getCurrentRelationships复核版本化来源；删除、归档、更改正文后的边不作为当前关系返回，访问计数变化不误失效。历史null来源关系保留待审计；这不代表旧关系可信、实体已绑定来源、物理删除已联动或纠正语义已闭环。关系类型仍为模型推断，引用存在不能证明分类语义正确。
- 首轮qualityCheck因迁移链单测仍固定断言27失败（expected27/actual28）；更新为实际28并保留完整连续链断言。graph-edge-quality-final.log：BUILD SUCCESSFUL，526任务（92执行），含assembleDebug；graph-edge-jvm-summary.json：418项＝417通过/1既有跳过，0失败错误。
- graph-edge-green-device.log：17项通过，1.125秒。关系新例覆盖来源删除、变更/归档/不存在时拒绝延迟提交、SQLite插入失败回滚、正常替换及历史保留；并回归属性来源和原记忆来源。7项迁移覆盖5/18/19/20/26/27到28，其中26保留知识正文及旧向量、27保留关系当前及历史区间并保持evidence空值。
- 证据目录artifacts/stability-20261005/。实机测试使用独立Room/迁移测试库，无个人资料失败注入；新关系prompt没有真实provider语义评测，未增加模型请求或作缓存收益承诺。主应用启动冒烟也不能替代正常后台提取验收。
- 后续：A3.3c实体/历史无来源图谱审计、纠正冲突及物理删除联动；A3.4持久化游标/整批提交和有界重试；A3.5备份恢复。A3整体仍未完成。旧工作树改动保留，无reset/clean或混合提交。

交付确认：artifacts/stability-20261005/ZeroV2-a3-relations-debug.apk已adb install -r保留数据安装Success，本地与手机base.apk SHA256均为D8DA2AB79A52DB671CE3906D2C6803BF0EF6566A4188907BD5FA8AD1FF1F2D69。graph-edge-start.log为MainActivity Status ok（仅启动冒烟）；未卸载或清理主应用数据。Room构建版本28，个人数据库逐行数据核对不在本轮隔离迁移证据范围内。

## 2026-10-05 A3.3c.1：工具遗忘入口的图谱物理清理

- 先红：graph-forget-red-device.log复现forgetMatching删除记忆后，属性原句及关系仍留在存储（1项失败）。此前当前读取会隐藏失效来源，但并非物理清理。
- 修订MemoryExtractionJobDao.forgetMatching：在既有Room事务中，先使匹配待处理作业失效，按128个来源ID分页清理匹配记忆派生的属性和关系，再删除记忆。GraphMemoryEvidence.sourcePrefix包含完整编码ID及分隔符，覆盖同ID各历史来源指纹，不误匹配相似ID，也不按图谱文本猜测无来源记录。空关键词在DAO层拒绝。生产工具仍由MemoryModule既有写Mutex保护。
- 原子边界：任何一步失败都回滚记忆、派生图谱及作业状态；延迟作业不能提交已SUPERSEDED的旧快照。删除数量仍返回记忆条数，不混入图谱行数。Room仍28，无新迁移。
- graph-forget-green-device.log：13项通过，1.232秒。新增3项验证旧来源修订仍可清理、129条跨页清理/相似ID及旧无来源属性保留/重复遗忘幂等/旧作业拒绝、SQLite记忆删除失败后全部回滚；并回归属性、关系来源和之前原子事务。全部使用隔离Room，不对个人资料执行遗忘或失败注入。
- graph-forget-quality.log：qualityCheck＋core:data:assembleDebugAndroidTest BUILD SUCCESSFUL，526任务（118执行），含assembleDebug。graph-forget-jvm-summary.json：418项＝417通过、1既有跳过，0失败错误。证据位于artifacts/stability-20261005/。
- 边界及下一片：本片只关闭memory_forget工具入口的有来源图谱清理。MemoryManageViewModel.delete仍直接调用memoryDao.delete，必须在A3.3c.2收敛，不能宣称所有删除入口完成；实体、无来源历史图谱、此前已孤立记录、派生向量物理清理和备份中的删除传播仍待审计。读取失效不等于所有副本擦除；129条是分页功能测试，不是A4规模性能验收。A3.4作业恢复与A3.5备份恢复保持未完成。

交付确认：artifacts/stability-20261005/ZeroV2-a3-forget-debug.apk已adb install -r保留数据覆盖安装Success；本地与手机base.apk SHA256一致：C9A2E67CBE5B28BC91C09121BB78D02FA740949F4F5C869381C491F0015A6859。graph-forget-start.log为MainActivity Status ok，仅作启动冒烟。未清用户资料，既有工作树改动保留。

## 2026-10-05 A3.3c.2：设置页精确ID删除入口

- 先红：graph-single-red-device.log复现旧设置页相同MemoryDao.delete路径留下派生图谱数据（1项失败）。测试为两条同文不同ID的隔离资料，目标是只删除选定ID。
- 新增Hilt单例MemoryDeletionRepository，以Mutex串行设置页删除；DAO forgetById在同一Room事务清理精确来源ID的所有属性/关系版本并删除该记忆，不借用关键词删除，不误删同文其他记录。Room仍28。
- 对符合宿主生成格式user-fact:<sourceMessageId>:<16位摘要>且保留会话来源的记录，事务内使同会话/消息的PENDING或RUNNING作业失效，拒绝旧快照完成。作业以整条来源消息为单位，取消会停止该消息作业其他尚未提取的事实，但不会删除已经存储的其他记忆；未知旧ID不猜测消息来源。未来重新入队/永久删除墓碑仍非本片完整保证。
- MemoryManageViewModel改用该仓库；删除成功后才记录完成，失败写入状态并由MemoryManageScreen Snackbar显示“删除未完成，数据已保留，请重试”；CancellationException继续传播。未增加设置入口。源码搜索app/framework/services已无memoryDao.delete直接调用，但不据此宣称所有备份/清库路径完成。
- graph-single-green-device.log：15项通过，1.032秒。新增精确ID/同文保留、仓库真实SQLite失败回滚、仅对应会话消息作业失效、旧作业拒绝及重复删除幂等；同时回归关键词遗忘与图谱来源。独立Room，不删除个人记忆。设置页错误提示已编译验证，未实机点击/截图验证其显示效果，不能将仓库测试等同完整UI端到端。
- graph-single-quality.log：qualityCheck＋core:data:assembleDebugAndroidTest BUILD SUCCESSFUL，526任务（124执行），含主APK编译；graph-single-jvm-summary.json：418项＝417通过、1既有跳过，0失败错误。证据目录artifacts/stability-20261005/。
- 后续仍为A3.3c实体/旧孤立图谱与其他副本审计、纠正冲突，A3.4持久作业恢复、A3.5备份恢复；向量/备份物理清理未由本片覆盖，A3整体保持未完成。工作树原有改动保留。

交付确认：artifacts/stability-20261005/ZeroV2-a3-single-delete-debug.apk已adb install -r保留数据安装Success；本地与手机base.apk SHA256均为5F4CA058381B942516892A6DF94A83EA5E0C28463E52091943E20DDCBAE1D80D。graph-single-start.log：MainActivity Status ok，仅启动冒烟；未清用户资料。

## 2026-10-05 A3.4及后续验收：持久作业、来源更正与恢复

- 图谱改用现有memory_extraction_jobs的GRAPH作业记录：按来源ID/正文/会话/时间快照幂等入队，50条一批，完整解析后整批事务提交实体、属性、关系和完成状态。进程内时间仅控制轮询，不再作为进度游标；完成记录保留防重复，来源变更清理旧快照。取消不标完成；失败指数退避5–60分钟、最多5次，FAILED不自动无限重试。未增加独立唤醒闹钟，未填充缓存前缀。
- 乱序实机先红（a3-order-red-device.log）：旧请求晚到覆盖新更正。修订按来源时间/ID阻止旧来源覆盖新来源；新用户事实继承来源作业时间。关系active=false记录撤回，旧批次不复活已撤回关系。旧历史记录时间不作猜测修复；模型实体ID/属性键一致性仍影响跨表述纠正，不能宣称任意语义冲突全部解决。
- a3-outbox-device.log报告6 tests，其中crossProcessStage无参数为Assumption跳过，其余5项通过：51条分页、原子失败回滚、删除后延迟提交拒绝、5次有界失败、乱序更正及撤回。随后a3-process-seed/complete/verify.log各1项通过，进程PID依次12154/12203/12237，真实跨进程恢复51条且无重复提交。均为独立测试库。
- a3-lifecycle-device.log 4项通过（0.378秒）：生产GraphExtractionJob关闭/取消/恢复/不重复请求及3项真实备份导入导出回归。a3-switch-history-device.log 4项通过（0.391秒）：Memory生命周期、Knowledge禁用再启用不删正文、设置页删除失败StateFlow可见且数据库保留、个人数据库只读聚合审计。错误状态测试不等于Snackbar实机截图或点击端到端。
- 历史审计当时计数：1373条记忆，246条无会话来源FACT，342条旧来源属性，161条旧来源关系，77条孤立实体，96条包含“随时/打断”或“提醒/微信”的FACT候选。包含历史/归档记录，关键词只识别候选，不证明污染；未导出正文、未批量删改个人资料。图谱界面新增来源可追溯/来源未核验标签，替换可能误读为真实概率的置信度数字。来源可追溯也不等于语义经人工确认。
- A3.3c.3及A3.5a证据见上节；Room29新增删除ID/时间记录，v1/v2备份兼容、原子恢复与本机删除优先。既有JSON备份范围不含知识外部SAF原件、附件文件、密钥或图谱；这不是全设备灾备。
- a3-provider-quality.log：qualityCheck＋app:assembleDebugAndroidTest BUILD SUCCESSFUL，530任务/34执行，含assembleDebug；a3-jvm-summary.json：421项，420通过/1既有跳过，0失败错误。工作树既有修改保留。
- A3整体继续保持进行中：历史无来源事实/图谱的语义核对、孤立实体来源所有权与清理尚无充分证据，不能把本轮工程测试等同个人资料全部准确。后续从A3.6历史来源核对和实体删除所有权开始，完成后再关闭A3；不把残留问题移到A4后伪称A3已验收。

### 2026-10-05 A3.6a：新实体所有权与历史来源降级

Room30在user_graph_entities新增memoryDerived（默认false），29→30仅增列，旧实体不伪造来源。生产整批提交仅为新创建实体赋予true，模型不覆盖已有实体名称/所有权。遗忘事务清理有来源属性/关系后，只删除没有任何属性或关系引用的memoryDerived实体；共享实体及旧实体保留，任一步异常沿用整批回滚。a3-entity-device.log 21项报告（其中1项跨进程方法无参数Assumption跳过，20项通过，3.864秒）：9项迁移含29→30旧实体名称/所有权保留，实体孤立清理/共享与旧实体保留及删除/图谱事务回归。a3-entity-quality.log BUILD SUCCESSFUL（556任务/128执行）。

历史召回审计补修：memory_recall兜底不再称为“用户长期事实”；工具返回及自动EvidenceRef均标注用户原文提取或历史来源未核验。会话关联和高置信度不再把旧总结自动认证为用户原话。此标签基于宿主来源元数据，不是对任意手工导入数据的真实性认证；内容保留，未自动归档96条关键词候选。图谱旧实体继续可读，孤立历史实体77条需要来源补证后才可清理。

真实生产GraphExtractionJob的独立否定样本已通过：a3-graph-provider-device.log 1项/8.277秒，使用当前已配置provider，原句“我不喜欢冷饮，喜欢示例食物A”由宿主完整保留并绑定来源；只说明这一例prompt/生产提取可用，不代表所有关系类型、任意改写纠正或缓存率98%通过。无个人资料发送给本次测试模型。

A3后续剩余：历史246条无会话来源FACT及旧图谱的语义/来源补证，不能通过模型自评或关键词批量判错完成验收。工程恢复、删除、开关和备份门已有证据，个人历史语义门继续保留未通过。下一步使用可追溯历史用户消息逐批比对，再将无可追溯来源的待确认记录汇总，禁止把无来源等同错误。

## 2026-10-05 A3验收裁决（覆盖此前“整体未完成”的阶段状态）

用户明确确认：无法补证的旧记录保留并标为未核验，由用户更正后再确认，A3按此兼容规则验收。因此不再以旧资料缺少原始来源阻塞A3，也不把它们认证为全部正确。旧数据不自动批量删除；新增提取走来源约束，纠正通过新用户原话形成新来源与有序图谱更新。来源标记已覆盖memory_recall、自动证据、记忆列表/详情、图谱属性/关系及旧实体卡片。当前构建Room30。

最终工程门：a3-accepted-quality.log qualityCheck BUILD SUCCESSFUL（500任务/27执行），包含assembleDebug；a3-jvm-summary.json 422项＝421通过＋1既有跳过，0失败错误。a3-final-app-device.log报告8项（7通过＋1未授权真实provider方法的Assumption跳过，7.753秒），验证生产召回/自动证据历史标签、双域开关、取消恢复、设置删除失败状态、3项备份及只读历史来源。真实provider已另在a3-graph-provider-device.log单独授权执行并通过1项。a3-entity-device.log 21项报告（20通过/1跨进程参数方法跳过）；跨进程方法已另由3个不同PID分阶段执行并全部通过。

历史无来源246条FACT中，2条在历史普通用户消息中找到完整原句匹配，244条本次没有这种匹配；匹配也不证明会话时间归属或当前仍有效，不自动补写来源/提升置信度。96条关键词候选不等同96条错误；历史无来源图谱与77条旧孤立实体按确认规则保留未核验。JSON恢复范围仍不含外部知识原文件、附件与密钥，视觉点击/动画质量继续A8验收；不外推为全量个人语义正确或缓存98%验收。

A3：按上述兼容边界已验收；下一未通过阶段A4（规模与检索质量门）。本轮保留既有工作树修改，未reset/clean或混合提交。最终APK安装/摘要证据以下一条交付确认记录为准。


交付确认：artifacts/stability-20261005/ZeroV2-a3-recovery-debug.apk最终保留数据adb install -r为Success；本地与手机base.apk SHA256一致：6D12809CBB94E02D60ED22594A743A236AEBA237CD595103678D76559F74263F。a3-final-start.log为MainActivity Status ok，仅启动冒烟；a3-final-delivery.json记录完整安装/摘要结论。该包包含Room30、历史来源标记与本轮全部修订，不沿用中间包摘要。

### 2026-10-05 A4.2b发布策略一致性及10k真实规模结果

10k真实候选试验已结束：a4-raw-10000-device.log 989.733秒、1失败（raw_candidate_incomplete）；a4-raw-10000.json completed=false，Memory WAITING_VALIDATION且16题候选扫描完成，Knowledge RETRY、没有完整候选。最终持久预算embeddingCharged=498376、rankRequests=60，停止本轮追加云端测试，不清零或另建账本。失败报告不冒充两域10k通过；RETRY本身不能区分预算拒绝与所有provider异常，直接可确认的是候选不完整及最终账本。

发现运行检索与独立发布门的策略偏移：运行使用CurrentRecallPolicy，发布门仍按未过滤候选排序。新增确定性VectorBenchmarkPolicyTest先红（a4-gate-policy-red.log）后绿（a4-gate-policy-green.log）：原始向量5/6保持，应用当前记录规则后6/6；不改固定题目、正确标签或最低门槛。共享规则版本绑定rankingPolicy，旧策略凭据不可复用；报告保留原始vectorRecall并明确最终vector_current_filter管线。无来源旧记录不因该规则删除。

最近付费规模报告使用修订前发布策略，不能当作本修订真实provider验收。当前仅窄范围回归已通过，最终全量工程门及代表性实机基线以下续记录为准。A4整体保持进行中，完整1k/10k混合重排、融合证据和可比较资源门尚未通过。

A4.2b工程门完成：a4-gate-policy-quality-verified.log qualityCheck＋app:assembleDebugAndroidTest BUILD SUCCESSFUL（530任务/44执行，含assembleDebug）；a4-jvm-summary.json 427项＝426通过/1既有跳过、0失败错误。VectorBenchmarkPolicyTest同时验证当前规则后6/6、原始向量5/6仍可见，以及旧vector-only-v1凭据拒绝复用。中间MaxLineLength与JUnit非void返回错误已修复，失败日志保留。本片不宣称修订后真实provider发布通过。

### 2026-10-05 A4.1b.1代表性重复采样与交付确认

最新APK和测试包保留数据安装成功（a4-app-install.log、a4-test-install.log）。a4-final-contract-device.log 3项通过/1.8秒，当前/历史召回、来源标签、预算不足提前阻断与持久预留回归通过。a4-representative-{100,1000,10000}-device.log分别OK 1 test、11.359/18.053/64.971秒；对应JSON completed=true，每档320行（双域16题×2个K×5次），文档数26/62/422，符合默认准入上限。全部为离线生产工具、独立Room、无云端请求；最终账本仍498376/60。

三档双域Recall@3均15/16＝93.75%、禁止旧记录问题率0，知识路径/正文引用一致性100%；Memory工具无来源ID，不声称同样引用准确率。10k块@3 Memory P50/P95=75.05/100.34ms，Knowledge=87.96/115.27ms；进程采样PSS峰值100/1k/10k分别359.26/360.79/291.98MiB（含建库、夹具、评分、进程背景，非严格峰值）。报告及a4-representative-summary.json保留逐类别失败。无同表示旧版本重复基线，不据此宣称性能无退化或资源门已通过。

A4.1b.1代表性工具重复采样完成；A4.1b.2全自动融合证据与可比较资源门仍待验，A4.3完整1k/10k混合重排未通过。下次从这些未通过门继续；本轮不追加云端额度，不把Memory原始候选16/16替代生产最终召回/发布验收。现有工作树修改全部保留，未混合提交。

交付artifacts/stability-20261005/ZeroV2-a4-current-recall-debug.apk；已安装到DEVICE_SERIAL，本地与手机base.apk SHA256一致：421D2D966AEC3EA979E0C3D6D3BBD4E5B7A7E2467F7AD67A84D52A29D526F588（a4-final-delivery.json）。a4-final-start.log MainActivity Status ok，仅启动冒烟；不能外推为个人资料全部正确、完整功能回归或缓存98%。Room仍30。

### 2026-10-05 A4.1b.2自动融合基线与A4.2c重复正文占位修订（进行中）

直接生产EvidenceProviderImpl、隔离Room合成资料、同grouped-v2摘要，100/1k/10k两轮独立进程，每题K1/K3各5次；a4-fusion-{first,repeat}-{100,1000,10000}.json均completed=true、160行，设备电量/热状态另留档。a4-fusion-summary.json：三档@3均90.625%，禁止旧正文0、来源ID/路径/摘录一致性100%，每档80次@3共75个重复正文槽位；跨文档均75%。10k首轮/复轮@3 P95=702.59/672.61ms，采样PSS=277.33/275.27MiB。引用正确不等于相关性正确；正文重复会挤掉第二条必要来源，已复现。

最小EvidencePolicyTest的两域重复正文挤占下一来源：a4-fusion-red.log及完整摘要契约下a4-fusion-identity-red.log均1失败。修订只在读侧最终选型按完整正文SHA256去重，候选先保持跨域RRF身份；Memory/Knowledge生产者在截断/来源标签前生成bodyDigest，选择最高排序的真实原引用，缺少摘要的旧引用沿用原域键；同200字前缀不同全文仍独立。数据、schema、写入域、事实置信与历史未核验标签不变；不进行相似语义合并、不增自动云端调用。

目前修订后全量工程检查执行中，实机复测待检查通过。资源两轮重复基线可观察波动，不能代替受控Macrobenchmark或自动声明性能无退化；10k约0.7秒P95成为后续定位证据。云端账本仍498376/60，不追加本轮额度。整体A4维持进行中。

A4.2c工程门：a4-fusion-after-quality.log qualityCheck＋app:assembleDebugAndroidTest BUILD SUCCESSFUL（530任务/154执行，含assembleDebug）；a4-fusion-jvm-summary.json 429项＝428通过/1既有跳过、0失败错误。EvidencePolicyTest 12项全绿，包括跨域同全文避免挤占第二来源、同摘录不同全文不得合并、无摘要旧引用保持兼容及K=0。SHA256只绑定原始正文，不猜测删除用户文本中的标签；选中原引用保留来源/未核验标记，不提高置信度。修订后实机两轮复测正在执行，验收结果以下续记录为准。

#### A4.2c补位候选被退化门拒绝，最终采用保守压缩

a4-fusion-after-*是试验性补位候选，不是最终验收版。三档两轮均@3 93.75%、跨文档100%、重复槽位0，但无关证据问题率68.75%→75%，平均每题无关正文0.6875→1.0625；a4-fusion-refill-rejection.log及after-quality-comparison.json明确拒绝该候选，未用高召回掩盖注入退化。修订后的最终规则仅压缩原RRF top-K中完整正文相同的引用，不从更低排名补位；不动基准答案。新增逐行质量比较脚本保留原始960条测量，检查召回不降、禁止/无关正文不增、引用一致、重复槽位0与800字预算。来源召回、相同正文合并与模糊语义过滤分别记录。

对应最小契约同时测试：top3原本含第二来源时必须保留；top2只含同全文双域重复时压缩为1条，不引入排名更低的第三来源。此前“跨文档已恢复”的描述仅适用于被拒绝的补位候选，最终有界版本跨文档及口语缺口需继续如实标未通过。保守版工程门与实机复测以下续结果为准。

### 2026-10-05 A4.1b.2a融合基线与A4.2c最终保守去重闭环

最终规则覆盖试验性自动补位：先按原跨域RRF取top-K，再压缩相同完整正文，不补位；保留最高排序的真实引用、原始来源与未核验标签，无摘要旧引用保持域键兼容，原数据及Room30不变。a4-fusion-bounded-quality.log qualityCheck＋app:assembleDebugAndroidTest BUILD SUCCESSFUL（530任务/78执行，含assembleDebug），429项JVM＝428通过/1既有跳过、0失败错误（a4-fusion-jvm-summary.json）。

SM-F926N/API35的100/1k/10k块两轮独立进程生产EvidenceProviderImpl实测：a4-fusion-bounded-{first,repeat}-{100,1000,10000}.json全部completed=true，共960行；对应device.log六次各OK 1 test。相同grouped-v2摘要与原基线逐样本比较，a4-fusion-bounded-quality-comparison.json accepted=true、degradedRows=0，重复正文槽位75→0/每档每轮，Recall@1仍75%、@3仍90.625%，禁止旧记录0、无关正文问题率仍68.75%、每题无关正文仍0.6875，来源ID/路径/正文截断一致性100%。保守版没有解决所有语义召回：跨文档仍75%、中文口语仍50%，不得套用被拒绝补位候选的93.75%/100%成绩。误注入需同时看forbidden及unrelated，禁止命中0不代表无关证据0。

10k保守版@3首轮/复轮P50=142.33/146.92ms、P95=180.51/170.84ms，进程250ms采样PSS=301.60/295.42MiB；1k采样PSS=384.10/376.74MiB，高于原融合基线267.30/270.01MiB。测试包含建库、夹具、JSON与GC，设备温度/电量和主机构建状态有记录但未固定，既不把延迟下降归因于去重，也不忽略采样内存上升；资源预算与可归因的无退化门尚未通过。接下来A4.1b.2b需分离建库/稳态查询内存、固定执行条件与分阶段定位，然后再裁决。不能将质量比较accepted当作资源门accepted。

最终实机3项召回/预算契约回归通过（a4-fusion-final-contract-device.log），MainActivity启动Status ok（a4-fusion-final-start.log，仅冒烟）。候选修复APK：artifacts/stability-20261005/ZeroV2-a4-evidence-bounded-debug.apk；已保留数据安装，本地及手机base.apk SHA256一致：2A2E7195B20FD4D5EE051BBCACB3C2BFEA5268FE1139C76F0423A75ACD635FE8（a4-fusion-final-delivery.json）。试验性补位包已被本保守版覆盖，不作为最终交付。

本轮隔离测试未追加云端调用，账本仍embeddingCharged=498376/rankRequests=60（a4-fusion-budget-final.json）。A4.1b.2a自动融合基线及A4.2c有界正文去重修订完成；A4.1b.2b资源门、口语/跨文档与无关注入质量、完整1k/10k真实混合重排仍未通过，A4整体保持进行中。现有工作树改动全部保留，无reset/clean或混合提交。下一步从资源分段测量继续，不重置或越过云端预算。

### 2026-10-05 A4.1b.2b分段资源测量与A4.2d摘要分配修订（进行中）

新增隔离EvidenceDigestResourceTest复现生产摘要的分配回归，a4-digest-before-device.log 1失败；a4-digest-before.json：同SHA256/UTF8正文、1000次，等价紧凑编码917504字节/54.58ms，原生产逐字节Formatter编码22282240字节/403.04ms。只替换十六进制编码，不改完整正文摘要字节、RRF选择、去重/来源语义；JVM新增标准abc摘要与长正文尾部区别回归。该证据仅证明摘要额外分配，不外推全部PSS上升由摘要造成。

原实现两轮100/1k/10k分段报告a4-resource-before-{first,repeat}-{size}.json completed=true，区分seed/warmup/query/report与逐查询ART进程分配量，不强制GC。1k首轮/复轮query采样PSS约264.35/265.64MiB，本轮未稳定复现此前376–384MiB采样；旧整进程采样不可被当作已归因的生产泄漏。phase采样250ms，短阶段可能没有样本，不伪造精确峰值。

a4-digest-optimized-quality-final.log qualityCheck＋app:assembleDebugAndroidTest BUILD SUCCESSFUL（530任务/78执行，含assembleDebug）。中间位掩码MagicNumber已提取命名常量并复验，不抑制质量门；优化版实机分段复测正在执行，最终资源/质量裁决以下续记录为准。

完整A4真实规模门需追加额度确认：已向用户提出累计上限150万embedding tokens＋256次短重排，包含已用498376/60、同账本不清零；尚未收到答复前不执行额外付费请求。原50万/64上限继续有效，完成源码/离线及实机资源测量不依赖新额度。

### 2026-10-05 A4.3追加授权、实跑复用与引用门准备

用户明确授权本次A4累计上限提高至150万embedding输入tokens＋256次短重排；已有498376/60计入同一acceptance-a4-authorized-budget.json，不清零或新建账本。测试预算同步新上限并记录embeddingLimit/rankLimit；未知/失败预留仍不退款、超限仍在实际调用前拒绝。隔离fake边界测试同步1499999/256，不触碰真实账本。

仅AndroidTest的AcceptanceResponseCache按backend identity/model/purpose/完整请求摘要复用真实provider返回；JSON原响应/真实向量原子持久到noBackup独立验收目录，不存个人资料，不进入生产检索缓存或系统备份。查询/文档用途、模型变更、请求/系统提示词区别分别入键；损坏停止、不隐式付费重试。缓存绕过实际API时不重复收费，报告单列命中/新增调用；复用不是新鲜provider请求，不冒充Chat前缀缓存命中率。

Memory工具新增[ memory/id ]形式的准确来源（实际输出无空格）用于核对召回引用，不改正文/类型/Tier及来源未核验标签；兜底历史记录也附实际ID。a4-memory-citation-red-device.log旧APK 2项/1失败，明确缺少memory/current引用；最终生产修订已实现，实机green待前置契约。知识工具路径引用保持原样。真实规模报告同时导出独立gate报告和候选实际条数，使用grouped-v2的26/62/422篇，不再把10k独立文档压力数据当正常索引准入。

a4-complete-candidate-quality.log qualityCheck＋app:assembleDebugAndroidTest BUILD SUCCESSFUL（530任务/53执行、含assembleDebug）；a4-complete-jvm-summary.json 430项＝429通过/1既有跳过，0失败错误。当前串行执行新版资源同协议两轮，然后6项前置契约与100/1k/10k真实生产工具验收；所有结果以下续实测记录为准，A4整体尚未验收。

A4.2d摘要最小回归已绿：a4-digest-candidate-device.log 1项通过；同1000次实际摘要分配933888字节（旧22282240），标准输出一致。a4-real-final-preflight-device.log 6项通过/0.547秒：Memory来源ID、当前/历史与重复问句，预算/真实响应缓存两项，以及摘要分配。预算升级已获明确授权，不再为同一额度重复确认。

资源同协议phased-v2旧/新三档两轮均 completed=true、各160行；a4-resource-v2-comparison.json qualityAndAllocationAccepted=true：所有逐题召回/禁止/无关计数保持、平均查询分配均下降约10万字节。10k候选query采样PSS274.16/276.72MiB（旧281.94/281.94），请求GC后的Java活堆6.60/6.48MiB、Native14.50/14.49MiB；旧活堆5.66、Native14.50/14.51。请求GC后整进程PSS仍上升约22MiB，不能把仅分配门通过外推为全部资源门通过。已准备MemoryInfo memoryStats、已提交堆与ART计数的旧/新1k配对观测，等待付费10k结束后执行，禁止中途安装中断已付费运行。

真实正常布局100/1k已完成：a4-real-final-{100,1000}.json两域CURRENT、候选100/1000条完整；各域16题Recall@3均16/16，禁止失效来源0，@3各48条引用ID/路径/摘录一致性48/48。独立发布gate使用currentSourcePolicy版本并保留vectorRecall；Memory独立原始向量5/6仍如实报告，最终当前过滤＋有界语义6/6后CURRENT，不改基准标签/阈值。

100/1k累计embeddingCharged=504176/551876，rankRequests=109/130；计量包括旧498376/60。1k命中342项/新增1821项是逐条真实向量和精确完成响应条目统计，不是API请求数或聊天prefix缓存率。真实工具top3中平均1.875条不在严格相关标签内（16题共18个正确来源）；工具候选不等于最终答案准确率，不能把引用准确或禁止命中0称为无关返回0。10k仍执行，最终以completed/gate/计数/预算和固定问题评分为准。

### 2026-10-05 A4.3真实三档规模验收完成

用户追加累计预算150万embedding tokens＋256次短重排已落实到原账本，包含此前498376/60，不清零。a4-real-final-{100,1000,10000}-device.log均OK (1 test)，10k耗时1398.998秒；对应JSON completed=true，双域索引均CURRENT，各档候选完整100/1000/10000条、知识文档26/62/422篇。a4-real-final-summary.json由独立脚本检查64行/档、实际发布与候选计数，双域每档固定16题Recall@3均16/16、禁止旧版本命中0，各48条引用ID/路径/摘录全部一致。发布gate保留原始向量与最终有界重排成绩及策略/数据摘要，不改标签或阈值。

最终同账本embeddingCharged=1035198、rankRequests=151（上限1500000/256），余额464802 tokens与105次短重排；精确真实响应缓存按用途/模型/请求摘要复用既有返回，缓存条目不等同API次数或聊天缓存命中。10k工具Memory/Knowledge P50=2130.83/2268.98ms、P95=3481.13/3134.58ms，包含网络和真实历史响应复用，每题一次，非受控延迟无退化结论。top3严格无关候选平均1.875条/题，引用一致性不等于答案完整/相关性100%。自动融合中文口语50%、跨文档75%与无关注入缺口未被该工具成绩覆盖。

A4.3真实生产工具规模/预算/候选完整性和引用门通过；A4整体保持进行中，资源分项配对观测正在执行，自动融合语义门仍需修订。最新a4-final-quality.log qualityCheck＋app:assembleDebugAndroidTest BUILD SUCCESSFUL（530任务/16执行，含assembleDebug）。修正资源报告说明为请求GC在查询计时之外，250ms采样仍非精确峰值；保留全部既有工作树修改及失败证据。
### 2026-10-05 A4.2f用户决策：有界自动语义证据（实现/待实机验收）

用户明确选择：允许回合入口有界语义，查询向量复用/双域共享、最多一次短重排、超时回退并保持稳定前缀。替代此前自动证据只能零网络的设计约束，主动memory_recall/kb_search仍可自由按需调用。生产VectorIndexWorker查询缓存按backend identity与完整query隔离，64条、5分钟、最大16384维，坏向量/配置切换/不可用密钥不复用；不缓存索引结果，每次域查询继续验证发布空间、维度及live source，缓存复用不跳过来源删除/更正检查。

EvidenceProviderImpl先保留并行本地词法回退，再有界读取双域已发布向量，按来源所有权投影真实ID/路径/来源标签和完整正文摘要；去掉相同全文的候选后最多8条送单次筛选。自动筛选允许[]，只保留明确选中slot；不把未选候选补回。主动工具重排原保留候选规则不变；两种模式缓存互不混用，自动缓存同时绑定全文摘要，数据更新不可沿用旧选择。总语义阶段3000ms，失败/忙/模型切换/无有效响应回本地词法，取消继续传播；输出仍最多3条/800字符，经既有turnContext动态槽进入，不改stablePrefix或Room30。

新增QueryEmbeddingCacheTest双域并发单请求、模型切换、失效凭据与坏向量不复用；BoundedSemanticRerankerTest自动弃权与主动工具缓存隔离。a4-semantic-quality.log全量qualityCheck＋assembleDebugAndroidTest成功（530任务/65执行）；最终摘要绑定补丁再次检查中，实机自动融合三档待验，不据工程检查提前宣布质量门通过。仍使用同A4预算账本，1035198/151已计入，余额不重置。

资源分项a4-pss-components-{reference,candidate}.json/log两项完成：旧/新retained PSS245796/265822KiB，native-heap分类50780/68728KiB占主要差额，native allocated11863104/12009120字节；Java活堆6349800/7674968、committed31433704/32775256字节。采样差额未等同活对象泄漏；并未完成native驻留页进一步归因，资源无退化门维持未通过，不改阈值掩盖。
### 2026-10-05 A4.2f实机失败保留与A4.2g native分配修订

自动语义首版100/1k真实融合 completed=true、各32行，Recall@3均93.75%、无关及禁止注入0，口语/跨文档各100%；唯一未召回为固定转写题14，模型弃权。不是整片验收；未改答案，新增通用同音转写/口语容错与冲突弃权说明。首版10k双域索引CURRENT但samples=0、completed=false，外层3000ms取消（a4-semantic-fusion-initial-10000-incomplete.json/device.log），不能套用主动工具16/16。双域改并行读取并共享同一查询向量；整组前后核对backend身份，切换时不混用。生产语义阶段仍3000ms，真实测试外层5000ms仅用于观察回退，回退必须按实际返回评分而非强行计为语义成功。候选数固定8，不随返回K变化导致重复模型请求；剩余累计额度继续同账本，不重置。

LexicalPatternResourceTest先以Java分配门运行（a4-pattern-initial-*，通过），但观测到重复正则编译native allocated升高；加native峰值门后a4-pattern-red-device.log明确1失败。a4-pattern-red.json：2000次同输出，复用正则native allocated采样峰值11880032字节，原生产20742128字节；Java分配4278944/4407296字节，不能仅用Java分配推断native无退化。只将固定bigram与两域分隔正则提为复用实例，不变匹配规则/分词/样本/排名，JVM原查询测试保持。a4-semantic-pattern-quality.log全量成功（530任务/91执行）；native绿色复验与同协议PSS仍待实测，不因微观修订提前关闭资源门。

自动证据模块读调用补接ModuleLifecycleManager.safeCall，Hilt Lazy延迟取manager避免Set<模块>与ChatBot证据依赖循环，异常遵循原模块状态/日志，取消透传。隔离无Application合成夹具仍自行组装独立模块；不把该夹具替代正常App启动和个人数据库验证。最终质量与实机结果以下续记录为准。
### 2026-10-06 A4自动证据格式与组合阶段时限修订

384预算前置边界测试发现账本初始化仍残留256检查，已修正为384；旧失败保留a4-budget-384-preflight-red-device.log，修正后a4-semantic-score-preflight-device.log 8项通过。a4-budget-384-final-quality.log全量质量/测试包编译成功。a4-score-jvm-summary.json 436项、0失败错误、1既有跳过。

EncodedCosineScorer实机10000条×1024维逐条与原计算一致：旧分配42303584字节/7027.68ms，新1032800字节/719.82ms（a4-vector-score-green.json）。仅隔离计算，不冒充数据库/云端延迟。

a4-semantic-fusion-score三档完成：100首次与热Recall@3均16/16、无关0；1k首次与热均16/16但无关1；10k首次与热均15/16且仍有语义超时回退，质量门未通过。固定各规模digest与上一版同规模一致，不要求不同规模语料hash相同；汇总脚本已纠正该检查，未改样本。累计1035198 embedding tokens/295短重排，限1500000/384。

1k订单题实际返回[{slot:0,text:正确资料}]，原仅数字数组解析拒绝后回退词法带入无关记录。自动证据模式新增只读取并严格校验对象slot，忽略模型返回正文，最终引用仍使用原候选；重复/越界/非整数/字符串编号/缺slot继续拒绝，主动工具原格式契约不变。新增JVM兼容和拒绝边界回归，策略版本bounded-semantic-v4，清除旧语义策略缓存影响。

自动证据组合阶段从3000ms调整至5000ms，覆盖共享查询向量、双域完整精确扫描与最多一次筛选；模型请求仍3000ms、MessagePipeline仍3000ms，生产超时继续本地回退。依据10k实际组合阶段回退约3.3–3.7秒作明确运行预算修订，不修改Recall/禁止/无关/引用质量门。真实测试外层7000ms仅观察实际5秒回退；离线资源计时协议不变。新增最多约2秒的等待风险需与首次/热延迟一起记录，不能宣称延迟无退化。修订后完整三档仍待复验。

### 2026-10-06 A4.2f自动语义质量门通过，资源门继续开放

a4-semantic-fusion-slot-{100,1000,10000}-device.log均OK (1 test)，10k完成347.065秒；各档完整32行。a4-semantic-fusion-slot-summary.json校验同规模digest不变，固定16题首次调用与热Recall@3均16/16；禁止和无关命中0，重复槽0，@3引用各18/18，格式最大106字符。Recall@1三档均93.75%（跨资料题的单槽上限），不冒充@1全部来源齐全。热@3 P50/P95分别55.66/71.02、264.17/309.18、2270.31/2784.26ms；包含精确真实响应复用，不代表新鲜网络或受控延迟无退化。真实模型响应[]、对象数组及超时首版证据均保留。

同一手机累计embeddingCharged=1035198、rankRequests=299，上限1500000/384，剩余464802 tokens/85次。追加授权、预算重读及失败预留规则有效，无账本重置。A4.2f自动语义质量/引用/格式门收口；A4整体保持进行中。

phased-v3五秒闲置配对两轮各160行完成，但资源门仍失败：reference闲置PSS231406/234148KiB，candidate263826/256682KiB；native allocated旧11832704/11188416、新11868384/11208032字节，native-heap PSS旧35360/41228、新64664/61896KiB。延长闲置未消除差额，不能以活分配接近推断全部驻留内存无退化，也不能据PSS推断泄漏。最新测试新增nativeHeapSize/free与电池温度采样，a4-idle-observation-quality.log BUILD SUCCESSFUL（530任务/16执行），补采与native调用栈归因继续。个人资料库未写入、未卸载/清空，工作树全部保留。

### 2026-10-06 A4正式资源产物规则与A4.2i摘要修订

用户明确选择：使用仓库已有非debuggable benchmark APK做正式资源门，debug冷态PSS差额继续单列跟踪，不冒充已消除。先前a4-resource-aot-*名称仅表示speed请求；a4-aot-reference-package.log实际status=verify，不能称为Full AOT对照。对应三档两轮原始报告保留，不用于正式性能通过结论。两轮native采样均含fixture PID buffer_overran，完整归因失败；TestRunner日志已对齐11780/13238进程。截断栈中存在ART JIT编译/调试ELF分配，仅作线索，不作全部驻留差额的证明。

正式产物沿用app已有benchmark buildType（非debuggable、debug签名、profileable），保留Room30与用户资料。保存a4-benchmark-reference.apk（当前A4.2i修订前新建立的benchmark基线，不是A4开工前的历史整包基准），a4-benchmark-reference-build.log BUILD SUCCESSFUL（384任务/161执行）。正式脚本核对真实target dexopt status=speed才运行；隔离报告通过测试runner显式输出，仅包含合成夹具统计，不导出个人文件，不依赖run-as或修改安装验证。没有历史同构benchmark包，因此不能据此宣称整个迭代相对历史版本全面资源无退化；原debug差额仍保留。

PolicyDigestResourceTest在参考benchmark实机先红：1000次空ModelSelector（providerCalls=0），输出相同beabf579...12ba5缓存键；等价参考442464字节/4.51ms，旧生产21577728字节/74.96ms。语义缓存/策略摘要统一复用EvidencePolicy紧凑SHA256，不改输出、版本、缓存键、稳定前缀或任何候选语义。新增固定历史缓存键JVM回归。正式资源三档同协议前后测试待完成。

合并qualityCheck与assembleBenchmark时，lint分析Debug测试源码与KSP Benchmark生成目录发生NoSuchFileException（a4-policy-digest-final-quality.log），不是源码错误；改为串行assembleBenchmark、qualityCheck＋assembleDebugAndroidTest，不抑制检查。手机Thermal Status=2，正式脚本等待≤1才测；未通过温度门的数据不代替正式验收，无付费调用。账本仍以1035198 embedding/299短重排为已观察累计，上限1500000/384不变。

### 2026-10-06 A4.2i绿色验证及修订APK交付

正式资源产物按用户确认采用非debuggable benchmark；debug冷态PSS差额继续跟踪。A4.2i摘要分配修订已通过：a4-policy-digest-candidate-device.log为OK (1 test)，1000次policyKey计算providerCalls=0，相同历史键beabf579836db63885a5451d4d8f5939d88acc780234982c7433ee6292f12ba5；生产分配1753088字节、参考458752字节，符合既定4倍＋65536字节上限。原参考包生产21577728字节的红例保留。21.19/5.33ms仅微观观测，不作为整条检索延迟或正式资源通过结论。

串行构建修正后a4-policy-digest-benchmark-quality.log BUILD SUCCESSFUL（384任务/13执行）；a4-policy-digest-debug-quality.log qualityCheck＋assembleDebugAndroidTest BUILD SUCCESSFUL（530任务/19执行），未抑制lint。四模块JVM XML汇总438项、0失败、0错误、1既有跳过，见a4-policy-digest-final-unit-summary.json。A4.2i实现/缓存键兼容/微观分配验收收口，A4整体与正式资源门仍未通过。

最新ZeroV2-a4-semantic-repaired-debug.apk已install-r保留数据覆盖安装；本地与手机base.apk SHA256均7EA114467EA8EC48D5B7EA5D93B686B5356A2A4D66AD54BD95BAAA8A087FAA3B，MainActivity启动Status: ok。证据a4-semantic-repaired-delivery.json、a4-semantic-repaired-final-install.log、a4-semantic-repaired-final-start.log。仅安装/启动冒烟，不认证全部交互或个人资料语义。

正式资源脚本参考包真实dexopt speed已核对，但降温等待近5分钟仍Thermal Status=2，未执行三档正式资源用例；安装后复核仍2，SKIN41.1℃。待手机降温后依次运行run_a4_formal_resources.ps1 -Phase reference和-Phase candidate；两轮三档报告齐全并核对同协议后再判门。参考benchmark为当前A4.2i前新基线，不能替代迭代前历史整包基线。测试后须恢复上述最新debug APK并再次核验摘要与启动。没有新增付费调用，已观察累计仍1035198 embedding tokens/299短重排，上限1500000/384。

### 2026-10-06 A4正式benchmark两轮三档测试完成，资源门仍未通过

用户要求检查温度并继续测试。初始热状态2，停止Zero后SKIN38.9→40.3℃。当前top独立确认Termux UID内多组QQ zygote约56.6–83.3%单核占用，总800%cpu中仅177%idle（约78%忙碌）。用户明确授权本轮临时停止Termux、测试后恢复；暂停后729%idle（约9%忙碌）。该干扰来源已实测，尚未定位QQ多进程高负载代码根因，不将它当作已修复。候选安装后后台再次恢复、高负载/热状态3复现，再暂停后温度回落。持续高负载和安装后后台恢复需列入后续模块稳定审计。

参考包等待上限到达后温度随后降至1，新增ResumeValidatedInstall续测入口：先核对手机主APK及测试APK与对应快照SHA256，再重新核对target dexopt status=speed，不重复安装/预编译。最初pm path经过Select-Object管道导致错误退出，修为完整捕获返回后检查退出码，实际续测成功。新增SuspendTermux显式授权开关，用于安装后/各夹具前维持隔离条件；默认不暂停其他用户服务。温度缺失拒绝、测前≤1、测后≤1与force-stop Zero继续有效；PowerShell解析通过。此次仅验收工具/记录变化，无生产源码改动，沿用上一轮qualityCheck和438项JVM证据，不声称新源码编译。

正式参考/候选均真实speed编译；每包100/1k/10k两轮、每份160行，共12份/1920行完整报告，设备SM-F926N API35、grouped-v2、phased-v3-requested-gc-idle-5s。对应a4-resource-formal-{reference,candidate}-{first,repeat}-{100,1000,10000}.json/device.log及测前/测后温度日志。summarize_a4_formal_resources.py和a4-formal-resource-comparison.json逐规模核对语料digest、全部配对来源/recall/禁止/无关/引用字段一致，全部温度≤1。离线词法夹具用于资源配对，不冒充真实云端语义质量；云端质量继续引用已有slot三档报告。

| 档位/轮次 | 参考→候选 @3 P95 ms | 参考→候选闲置PSS KiB | 参考→候选查询采样PSS KiB |
|---|---|---|---|
|100 first|24.61→24.59|61959→69209|76785→87105|
|1k first|83.40→73.54|70816→70604|94892→104829|
|10k first|558.67→558.54|57553→57198|127284→120696|
|100 repeat|25.59→25.55|68773→68431|81150→83636|
|1k repeat|69.91→74.46|73046→73783|94441→95721|
|10k repeat|558.57→636.47|63098→56228|122142→121316|

结论：资源测试执行完成，配对质量不变，但资源无退化门未通过；10k重复轮P95上升约13.95%，100首次闲置PSS增加7250KiB、100/1k首次查询采样PSS增加10320/9937KiB。没有把波动自动视为可接受，也没有删样本或另设容差。当前数据仅为两轮观察，不能证明全部差异由源码造成或已归因；后续需针对重复轮延迟及小规模驻留差额诊断。250ms采样不等于严格峰值。参考包为A4.2i修订前新benchmark基线，不支持相对A4开工前历史整包无退化。debug冷态PSS差额继续跟踪，A4/A4.1b.2b保持未通过，不进入A5生产修改。

候选摘要微观绿例复测：1000次参考458752字节/4.41ms，生产1786048字节/18.73ms，providerCalls=0，相同历史policyKey；符合既定分配门。正式包真实speed状态与配对报告独立记录，不据微观绿例宣布整体资源通过。

测试后ZeroV2-a4-semantic-repaired-debug.apk已install-r恢复，手机/本地SHA256均7EA114467EA8EC48D5B7EA5D93B686B5356A2A4D66AD54BD95BAAA8A087FAA3B，MainActivity Status ok。Termux已重新启动；a4-formal-restored-module-health.json显示supervisor alive/rootfs=true，image_gen、bili_downloader、web_fetch、searx_search、qqbot、vision_pro、qzone_post七个模块ACTIVE，仅runtime恢复观测，不认证所有Android工具挂载或业务调用。a4-formal-final-delivery.json/install.log/start.log保存恢复证据，未卸载/清空资料。账本实读仍embeddingCharged1035198、rankRequests299，限1500000/384，本轮无付费调用（a4-formal-outcome.json）。

### 2026-10-06 A4.2j资源差异定位：CPU/GC与双域读取观测

保留原12份正式报告及资源未通过结论，按diagnosing-bugs流程先核对后台负载、GC/调度、实际路径计算三项假设，未凭PSS或单轮延迟修改生产检索。原100首次闲置差7250KiB中code PSS贡献6848KiB；native allocated反而少28848字节、Java活堆少23192字节，主要差额位于代码驻留分类，尚非泄漏或源码回归证明。本轮开始热状态3，沿用用户授权暂时停止Zero/Termux，结束恢复。

ScaleRecallAcceptanceTest新增显式resourceDiagnostics：只在离线分阶段用例启用，逐请求记录进程CPU时间、GC计数/累计时长增量；读取在查询计时边界外，新增探针会影响整体进程统计，因此与原正式协议独立保存。计数为进程范围，CPU包含其他线程工作，GC是近似运行时统计，不等价于该方法纯CPU或GC暂停时间。方法定义核对Android官方Debug.getRuntimeStat和Process.getElapsedCpuTime文档。resourceDomainProbe另在采样器停止、全部资源快照保存后顺序读取两域，明确不当作融合延迟。生产路径/Room30/稳定前缀/来源策略均未改动。测试脚本新增规模、轮次与报告标签选择；ResumeValidatedInstall始终校验手机主/测试APK摘要和真实speed状态，独立文件名不覆盖原报告。

编译a4-resource-diagnostics-build.log BUILD SUCCESSFUL（240任务/6执行）；a4-resource-diagnostics-quality.log qualityCheck成功（500任务/10执行）；a4-domain-probe-quality.log assembleDebugAndroidTest＋qualityCheck成功（530任务/16执行）。PowerShell解析及汇总完整性检查通过。既有438项JVM/1既有跳过沿用质量任务检查证据，不宣称新增单元测试数量。

候选→参考顺序对100和10k各复验一次，共4份/640行，同一诊断测试包；a4-resource-diagnostic-v1-{candidate,reference}-repeat-{100,10000}.json及日志均完整，测前/测后温度≤1，逐题来源/recall/禁止/无关/引用字段一致。a4-resource-diagnostics-summary.json：

|规模|候选/参考 @3 P95 ms|候选/参考CPU均值 ms|候选/参考闲置PSS KiB|候选/参考发生GC的@3请求|
|---|---|---|---|---|
|100|26.87/28.91|28.89/27.19|71130/69544|0/0|
|10k|735.93/723.00|718.20/741.50|75550/74143|1/1|

10k候选最慢八项均未发生GC；100两包全部@3测量无GC。原10k重复轮约13.95% P95差额未在本诊断同条件观察中稳定复现（约1.79%），候选平均进程CPU更低；不能由此宣布原差额已消除、GC为唯一原因或资源门通过。新探针协议的绝对耗时/驻留量也不能与旧协议直接当作性能退化比较。100两包code PSS22880/22924KiB，原首次6848KiB差额没有固定复现，但驻留归因仍需更细映射证据。

再使用已speed编译参考包核验摘要续测一次10k（不重复主APK预编译），a4-resource-domain-v1-reference-repeat-10000.json完整160行融合观察＋32项双域探针，测后热状态0。采样器停止后的16题顺序读取：Memory平均wall256.60ms/进程CPU250.94ms；Knowledge平均wall307.80ms/CPU302.75ms（a4-domain-probe-summary.json）。两域均有实质计算开销，不支持只修单一域或只归因GC；此为warm顺序访问观察，不等价于并行融合或真实云端性能。后续应拆分整词/bigram多次SQL和候选投影成本，构建等价性与资源回归后再改生产代码，避免凭现有结果盲目缓存个人资料或减少候选刷门。

A4.2j诊断观测片实现/编译/实机验证完成；A4.1b.2b以及A4整体仍未验收，继续按原质量/资源门收敛，未进入A5。当前5份诊断用例共800行融合观察，额外32项域观测，不增加付费调用。

测试后最新ZeroV2-a4-semantic-repaired-debug.apk已install-r恢复，手机/本地SHA256均7EA114467EA8EC48D5B7EA5D93B686B5356A2A4D66AD54BD95BAAA8A087FAA3B，MainActivity Status ok；a4-diagnostics-final-delivery.json及install/start日志留证。Termux已恢复，a4-diagnostics-restored-health.json为supervisor alive/rootfs true，七个模块ACTIVE，仅runtime恢复状态而非全部业务调用验收。账本实读仍1035198 embedding tokens/299短重排、限1500000/384，见a4-diagnostics-final-budget.json。全部现有工作树改动保留，未清空个人资料。

### 2026-10-06 A4.2k：词法多查询收敛与等价性验收（资源门进行中）

沿A4.2j双域CPU观测拆分bigram读取；整词、最多8个bigram、Memory单词100条/Knowledge300条上限、原顺序和重复计分、scoped检索、来源校验、Room30和稳定提示词前缀均保持。拒绝用减少候选或缓存个人事实改变验收结果。

第一候选用显式读事务先读取各词ID、统一读取实体并复原各组；qualityCheck/assembleDebugAndroidTest成功530任务，438项JVM/0失败/1既有跳过，实机6项回归和17组完整行/BLOB等价性通过。正式benchmark候选6份测量保存于a4-resource-lexical-v1-candidate-{first,repeat}-{100,1000,10000}.json；10k首次P95约280.77ms，但idle PSS80345KiB（native PSS46716KiB），相较此前参考57553KiB仍明显增加，因此拒绝发布，不能用速度抵消驻留退化。

第二候选改为参数绑定的单条UNION ALL只读查询，各分支保留旧SQL、顺序和上限，单语句读快照不引入显式Room写事务；仅数字分支索引进入SQL，用户词/limit全部绑定。每行仍由Room物化，输出只在单次调用内共享同ID实体，不宣称唯一实体仅物化一次。新增LexicalGroupRow为查询DTO非Entity，没有数据库迁移或清空。最初构建因KbDao缺少RawQuery导入失败（a4-lexical-union-quality.log），修复后qualityCheck/assembleDebugAndroidTest成功（a4-lexical-union-quality-repaired.log，530任务/115执行），消除绑定数组类型推断警告后串行benchmark成功（a4-lexical-union-benchmark-build.log，384任务/58执行）。

a4-lexical-union-preflight-device.log实机OK6（7.108s）；a4-lexical-union-equivalence.json完整17组含空词、%/_、关键词、归档过滤和不存在词，逐行内容/顺序/元数据/向量字节一致，并验证重复ID输出共享引用。该in-memory差分测试仅证明等价，不是正式资源门。候选正式资源正在以lexical-union-v1独立文件名运行，随后需同测试包参考配对及云端语义回归；旧失败报告不覆盖。未新增付费调用，现有预算仍1035198/299（后续须手机复核）。A4及A4.1b.2b保持开放，不进入A5；测试结束必须恢复稳定debug包、Termux及模块状态。

### A4正式资源判定补充（2026-10-06用户确认，复测前锁定）

用户确认采用固定交叉复测规则。以整轮为单位，固定三个交叉轮次R→C、C→R、R→C，三档100/1k/10k均覆盖，使用同一冻结测试APK、非debuggable benchmark、实际speed编译、测前测后thermal≤1，隔离相同corpus；每档每包5次×16题×K=1/3，共160行。每次fixture独立进程，runTag=first表示本fixture首轮，不冒称网络冷请求。此前lexical-union-v1的12份1920行作为规则确定前观察，不用选择子样本充当新验收。

每个配对100条P50/P95候选减参考≤3ms；1k/10k P50/P95不增加；查询采样PSS及idle PSS候选≤参考×1.02。逐题recall/禁止/无关/引用/来源与corpus摘要一致，完整报告不可缺失，温度和实际编译状态必须合格。活堆、native实际分配、请求分配和采样heap单列核对，出现持续增长或异常必须定位，不用PSS容差掩盖泄漏。差异不与更早协议混比，debug冷态风险单列。新增门槛为用户项目决策，非自动放宽：三轮执行后逐配对检查，不挑轮、不删题。

正式交叉复测计划文件标签a4-cross-r1/r2/r3，固定顺序在run_a4_cross_resources.ps1，冻结主APK a4-benchmark-reference.apk与a4-lexical-union-benchmark.apk、测试APK a4-lexical-union-test.apk。构建最终质量复查a4-lexical-union-final-quality.log成功530任务/13执行；最终debug/test快照用于真实语义回归及交付，绑定数组仅补显式Any类型，不更改SQL行为。

A4.2k当前生产真实自动语义复验完成：a4-lexical-union-semantic-{100,1000,10000}.json/device.log及-summary.json三档均qualityAccepted=true，两域CURRENT/每域完整100/1k/10k候选，首次与热Recall@3均16/16，禁止/无关/重复0，引用与source完整、块长度预算合格。debug热@3 P95约138/463/3535ms，此为本次运行环境和已有真实响应凭据复用，不冒称冷网络性能或正式资源门；此前54/264/2270ms级结果不覆盖。持久累计预算实读1035198/299，未增加付费调用，限1500000/384。最终源代码质量检查530任务成功，实机差分17组及6项回归通过。

规则确定前lexical-union-v1参考/候选12份报告完整1920行，逐题来源/recall/禁止/无关/引用一致，actual speed与测前后thermal≤1核验通过，a4-lexical-union-v1-resource-comparison.json完整汇总：100首次P95 29.31→26.01ms、repeat29.46→31.69；1k首次138.89→88.12、repeat135.63→90.07；10k首次750.18→303.46、repeat732.67→309.07。idle PSS首次差-1923/-2130/-7785KiB，repeat差-1740/+926/+374KiB；10k repeat查询采样PSS+1211KiB、采样heap+1300920字节。保留全部正差，不据此直接验收。三轮按用户新规则独立cross-r1/r2/r3复验开始，18报告预期2880行；数字门之外单列堆/分配检查，最终结论仍待齐全。

### A4.2k交叉复测第一候选拒绝与有界窗口修订

按用户锁定规则开始cross-r1/r2/r3后，cross-r1全部6份/960行完成，cross-r2候选100完成；因已出现必失败配对停止后续隔离用例，保留完整/未完成日志，不把停止的样本列作通过。cross-r1参考→候选：100 P50 14.69→17.91ms（+3.22>3ms），P95 25.11→25.86；query采样PSS84544→86428KiB（+2.23%>2%）；1k P95143.75→87.88但idle72967→74774/query93532→98992KiB；10k P95742.28→269.75但idle70342→73308KiB。该候选因此拒绝，三轮尚未完成，不能声称资源验收通过，也未修改门槛。

驻留分类核对：1k native PSS40004→42036KiB而native实际分配10822448→10815360字节；10k native PSS37200→40544KiB而实际分配14820608→14847296，Java活堆两档均下降；差异包含native空闲页驻留，不是已证明泄漏，仍按PSS门阻断。下一候选以有界第501条ID探测分支，不做全表count、不持久缓存规模；≤500行恢复原逐词查询，较大集合按每次两词UNION ALL读取，减少单次游标物化量；每分支旧顺序/上限和完整实体/BLOB不变，多窗口读快照与原逐词读语义一致，不宣称全8词单一快照。Room写事务/Mutex、来源重检、稳定前缀、候选评分保持。

新增实机差分覆盖100与10k各17组，且核验两域500→501规模分支边界；已有fake DAO同步规模方法。正在重新qualityCheck/assembleDebugAndroidTest及benchmark，随后使用全新cross-b2-r1/r2/r3文件名重新按相同固定顺序/门槛复验。上一候选的真实语义回归保留，但不替代本次修订后回归；本轮未新增云端消耗。A4继续开放。

有界窗口版最终 qualityCheck/assembleDebugAndroidTest成功530任务/21执行（a4-lexical-bounded-final-quality.log）；benchmark成功384任务/58执行（47s）。a4-lexical-bounded-preflight-device.log实机OK6（15.775s），a4-lexical-bounded-equivalence.json两个完整报告100/10k各17组，并核验500/501切换；全行/BLOB/排序等价。固定规则和R-C/C-R/R-C顺序不变，冻结摘要见a4-cross-b2-frozen-manifest.json；新报告标签cross-b2-r1/r2/r3，已开始测试。

### A4.2k第三候选：单语句有序ID与唯一实体同快照

两词窗口候选同样拒绝：cross-b2-r1 100查询采样PSS85568→87936KiB超过2%；1k P50/P95 72.21/107.29→86.49/129.76ms、idle73315→76779；10k P95 740.93→686.96但idle73873→80179KiB。停止时保存14份完整报告（r1/r2各六份，r3参考100/1k），未完成r3 10k日志保留。不能以活堆或部分速度收益覆盖PSS门，也没有修改既定门槛。

当前生产改为LexicalGroupQuery：固定SQL模板只缓存语句，全部用户词/limit参数绑定；CTE首先执行原per-term有序ID/cap读取，再在同一语句返回每个ID唯一完整实体。匹配行用term_index/match_id及NULL实体列，实体行用NULL匹配信息加显式实体列；DAO按原ID流恢复各词组并共享唯一对象，完整内容和BLOB均保留。显式列名保护历史迁移后的物理列顺序；无额外规模探测、无写事务、无多读快照或个人事实缓存。只读CTE/UNION ALL/IN为旧SQLite已有功能，未使用新窗口函数/JSON扩展。Room30/实体表结构、写入守卫、来源重检、旧候选上限/评分/scoped检索/稳定前缀不变。

qualityCheck/assembleDebugAndroidTest成功530任务/118执行（a4-lexical-unique-quality.log）；串行benchmark成功384任务/58执行（42s，a4-lexical-unique-benchmark-build.log）。a4-lexical-unique-preflight-device.log实机OK6，a4-lexical-unique-equivalence.json两个完整100/10k报告，各17组全字段/BLOB/排序相同，500→501后仍相同。in-memory微观均值Memory100旧17.06→15.22ms、10k199.41→139.46；Knowledge10017.35→13.23、10k213.19→183.95，仅成本观察不是正式资源门。cross-u1-r1/r2/r3以原规则和R-C/C-R/R-C顺序重新验证，旧失败报告不覆盖；当前A4仍开放，候选不可据微观绿例发布。

### A4.2k第四候选与驻留诊断（未改发布门）

唯一实体全规模方案首轮拒绝：100参考/候选P50 15.26/23.06ms、P95 23.90/34.70（超3ms），1k P95 131.73/142.84且query采样PSS95537/102221KiB；10k P95752.64/379.43、query PSS126539/100508但idle56035/72080KiB。停止时保留9份完整报告（cross-u1-r1六份、r2候选三份），参考r2安装/温度记录保留，未完成不充作验收。相同尺度native实际分配与PSS不能混为一谈，仍需验证idle驻留时间变化，不凭无活堆泄漏就宣布通过。

自适应候选：≤2000行使用原逐词完整读取，>2000用同语句CTE有序ID＋唯一完整实体；判断只扫最多2001个ID。LexicalReadScale缓存5秒规模提示，弱引用DAO键、不捕获数据库或事实、最多16提示、锁内不悬挂数据库查询；两分支都直接读当前库，因此过期提示只可能改变成本而非事实内容或准入。100差分fixture增长到2000/2001时插入带BLOB的新事实并立即检索，验证规模提示不能隐匿新事实。Room30/写事务/来源重检/固定候选、稳定前缀不变。

新增resourceExtendedIdle显式离线诊断：原retainedAfterIdle仍为请求GC后5秒，不改变原phased-v3协议；额外等待55秒取得60秒快照，采样器已停止，无额外GC/trim、查询计时之外，不纳入原5秒发布门。目的是判断native空闲页驻留是否时间相关；没有使用隐藏API或压低内存指标。须凭实际配对结果确定后续项目决策。

qualityCheck/assembleDebugAndroidTest成功530任务/118执行（2m2s，a4-lexical-adaptive-quality.log）；自适应实机回归与benchmark构建正在执行。原固定3轮与2%/3ms规则仍有效，新候选/诊断仅待验，A4未收口，未进入A5。

自适应版实机a4-lexical-adaptive-preflight-device.log OK6（19.092s），两个差分报告100/10k各17组、全文/BLOB/顺序一致；2000/2001规模跨越后新到达事实立即可见且BLOB一致，缓存的规模提示不决定事实。a4-lexical-adaptive-benchmark-build.log成功384任务/58执行（54s）。

idle-a1参考/候选10k离线扩展闲置诊断完成，共320行，仍保留原5秒门；a4-extended-idle-comparison.json：参考/候选@3 P50 625.60/263.93ms，P95 747.32/458.60；query采样PSS140419/134785KiB；5秒idle60370/56309KiB，60秒60324/56503KiB。两包Java活堆和native实际分配在5/60秒均未增长，也无明显驻留释放；不支持用延长idle切换门来解除早先失败，更不能由此称完全不存在泄漏。诊断协议仅提供分段观测，两个报告测前后thermal≤1和actual speed须与脚本日志核对，原失败保留。

已冻结a4-cross-a1-frozen-manifest.json（参考/自适应benchmark/相同测试包/最终debug摘要），新cross-a1-r1/r2/r3按照原R-C/C-R/R-C三轮、100/1k/10k、160行/报告重验。正式复验不启用ExtendedIdle，仍为phased-v3请求GC后5秒。数字门逐配对检查，活堆与分配另审，不依据上述单轮诊断宣称A4通过。当前未新增付费调用；最终还需当前源码真实语义回归、安装摘要与模块恢复留证。

### A4 三轮完整交叉复测与控制器编译审计（2026-10-06）

cross-a1-r1/r2/r3 共18份、2880行；逐题质量与来源一致，但 a4-cross-a1-resource-comparison.json 的 numericGatePass=false。100条第二轮P95差额4.35ms超过3ms，1k第二轮P50/P95增加26.42/8.46ms，多个query/5秒idle PSS配对超过2%。不删除失败样本、不取平均替代逐轮门，A4继续开放。

同一冻结参考APK的三轮重复结果亦有显著波动：100条query/idle PSS范围约5.08%/4.88%，10k约25.34%/32.28%，详见a4-reference-repeatability.json。此证据说明测量重复性仍需校准，不能单独归因代码泄漏，也不能据此放宽已授权门槛。

控制器审计发现此前debug androidTest APK仍为DEBUGGABLE，speed请求实际降为verify（a4-harness-speed-package.log）。manifest声明false被AGP覆盖，失败产物与日志保留，不称Full。AGP8.7.3本地源码确认测试debuggable继承被测构建类型；新增可选-PacceptanceTestBuildType=benchmark，默认debug保持。正式资源控制器编译debug验收辅助类到测试APK，生产benchmark不加入该辅助类。个人资料ProductionRecallAcceptanceTest依赖services debug专属fixture，移入androidTestDebug源集保持原debug验收。新控制器仍须编译成功、实读非DEBUGGABLE与speed，并重新执行固定三轮才能得出结论。当前预算仍1,035,198 embedding tokens /299短重排，无新增付费调用。

非调试控制器已实现并验证：a4-benchmark-harness-source-separated-build.log BUILD SUCCESSFUL（240任务/12执行）；a4-nondebug-harness-package.log无DEBUGGABLE且arm64 actual speed；a4-nondebug-harness-preflight.log OK(1 test)。a4-full-harness-default-quality.log qualityCheck/assembleDebugAndroidTest成功530任务/16执行，个人资料测试class仍存在于debugAndroidTest。新增RequireFullHarness明确编译并检查测试APK实际speed，Resume也核对冻结双包哈希及编译状态。cross-a2冻结原参考/自适应生产benchmark与新控制器，原数值门和5秒协议不改，按三轮R-C/C-R/R-C复验；最终结果仍待完整报告，不提前验收。

### A4.2l完成 / A4.2k继续开放：双端Full三轮结论（2026-10-06）

cross-a2-r1/r2/r3全部18份报告、2880行完成，thermal测前/后≤1且每组应用/控制器actual speed；a4-cross-a2-frozen-manifest.json记录冻结双应用与公共控制器摘要。a4-cross-a2-resource-comparison.json逐题召回、禁入、无关项、引用数/有效引用和sourceKeys均一致。1k三轮@3 P95参考/候选为140.69/134.08、141.29/100.30、144.51/127.93ms；10k为752.05/342.46、741.24/355.14、736.58/358.12ms。

原数值门仍失败：100首轮P50 9.57→15.59ms（+6.02，超3ms）；1k首轮query PSS91884→97238KiB（+5.83%）；10k第二轮idle56945→69777KiB（+22.54%）；100第三轮query79814→83975（+5.21%）/idle66038→69374（+5.05%）；1k第三轮idle71409→72989（+2.21%）。不平均掩盖，不换5秒协议，不据质量/大规模性能通过声称全门通过。

a4-full-harness-reference-repeatability.json只描述同一冻结参考APK在cross-a2中的三次重复，并非独立A/A实验，也不替代门：100 P50跨度6.67ms，query/idle PSS范围5.15%/4.85%；1k P50跨度20.78ms、query PSS4.05%；10k query/idle PSS范围26.06%/26.19%。双端Full后波动仍在，不能把前次全部波动归因控制器JIT。10k第二轮闲置native实际分配参考14,819,056/候选14,849,088 bytes，native PSS25432/37560KiB，说明驻留页差额不能等同活分配；仍不足以排除所有泄漏。

默认质量门530任务/16执行成功，JVM438项0失败0错误1既有skip；benchmark控制器240任务/12执行成功与实机差分OK1。ROADMAP仅勾选A4.2l测量前提修订，A4/A4.2k继续开放。已询问后续项目规则：独立同APK A/A校准、另冻结误差规则后重验，或保留逐轮3ms/2%门继续优化；未获回答前不修改门。正在补当前自适应版真实语义流程（现有provider回执复用与累计账本），完成后恢复已验证日常APK及7模块留证。

用户已选择独立同APK A/A校准后冻结误差规则并重验，当前仍不验收。已预登记a4-independent-aa-frozen-manifest.json与run_a4_independent_aa.ps1：三对A-B/B-A/A-B，两标签实际均安装相同冻结参考APK和公共非调试控制器；安装/Resume顺序镜像cross-a2，100/1k/10k各5×16×K1/3，共18份/2880行。每fixture独立进程，双端actual speed、thermal前后≤1、原5秒协议及相同摘要/逐题质量必检。全部配对误差和参考min/max均保留，不使用候选结果拟合容差；A/A不是候选通过证据。完成后另冻结误差规则，再运行新候选三轮，不回填旧失败为通过。

当前自适应语义流程三档最终均完成：a4-lexical-adaptive-semantic-{100,1000,10000}.json及-summary.json qualityAccepted=true；首轮/热轮@3均16/16，禁入/无关/重复0、来源引用一致，回执复用不得称新网络延迟。10k运行中曾观察thermal=3，停止指令前实际已返回完整OK(1 test，464.665s)并完成JSON；保留完整质量结果与早期inflight部分记录，热态延迟不用于正式资源验收，不误记为未完成或冷态证据。已停止Zero/Termux降温，预算仍1,035,198/299。接下来仅在thermal≤1且双端actual speed条件启动独立A/A资源校准。

A/A采样开始前预登记误差规则计算方法（a4-aa-error-rule-protocol.json，数值待独立数据冻结）：新候选三轮各项必须同时通过配对噪声门与独立参考绝对上界。延迟配对允许量为max(原100条3ms/其余0，独立A/A最大绝对配对差额)，query/idle PSS为max(原2%，独立A/A最大对称相对配对差额)；候选绝对延迟不能超过A/A参考max加原延迟量，query/idle PSS不能超过A/A参考max×1.02。闲置Java/native实际分配与查询分配均值另核对独立参考包络+2%，超出须诊断，不靠PSS噪声放行。逐题/来源相同、哈希/actual speed/温度/5秒协议保持硬门。数值冻结之后仅新cross报告可判定，不回填旧失败。这个经验包络不是95%置信区间；若通过，结论限于“独立噪声及参考范围内无可分辨退化”，不称每个原始配对严格≤3ms/2%。

A/A首个aa-full试跑采样期间确认屏幕仍亮；熄屏后皮肤36.8℃/thermal1。为统一控制，停止试跑，保留100/1k两完整报告及10k中断日志，不混入校准。新增RequireScreenOff：采样前发sleep键，不更改系统设置；前后实读power并要求Asleep/Dozing，温度前后仍≤1。重新冻结aa-screen（三对18份），安装/Resume顺序和双端speed、5秒协议不变。新候选复验也须相同熄屏条件。详见a4-independent-screen-aa-frozen-manifest.json；旧试跑排除原因是预条件变化，不是指标挑样本。资源控制器/生产源码保持已编译版本。

后续顺序明确拆为A4.2m（独立A/A全18报告→冻结误差与绝对参考上界）和A4.2n（新候选三轮→实际活堆/分配复核→APK/模块恢复与预算）。未完成不勾选、不复用旧cross-a2为通过样本。当前生产语义三档质量通过，10k热态性能排除；后续资源只能用全程熄屏、thermal≤1、双端speed的新报告。新源集修订默认debug质量门已通过，后续仅验收脚本/文档，不再修改生产代码或门内样本。

A4.2m当前aa-screen已完成17/18报告，最后10k仍运行。后续校准数值由全部独立参考计算，不混旧aa-full条件变化试跑，不用cross-a2候选值拟合。冻规则还绑定calibration/protocol SHA256及UTC时间，新cross-a3开跑前核验APK和规则摘要。新版验收校验保留原始3ms/2%结果，同时独立判配对误差、绝对参考上界、query活堆/idle Java与native实分配/每请求分配包络+2%；逐题重复槽与blockChars也加入独立A/A及新候选同值检查。

### A4.2m独立校准完成；A4.2n新复验启动

独立aa-screen全18份/2880行完成，源摘要、来源/召回/禁入/无关/引用/重复槽/blockChars相同，全部采样前后thermal≤1与熄屏状态、双端actual speed已核对。a4-independent-aa-calibration.json与a4-aa-frozen-error-rule.json绑定SHA及UTC时间；ROADMAP仅勾A4.2m校准完成。原cross-a2失败保持。

冻结数字（全部独立参考计算，未用候选数据；低精度PSS不称严格2%无退化）：

|规模|P50/P95配对允许ms|query/idle PSS配对噪声|query/idle PSS绝对上界KiB|候选P50/P95绝对上界ms|
|---|---|---|---|---|
|100|3.00/4.75|3.90%/4.86%|86017.62/71039.94|20.07/32.85|
|1k|14.76/46.06|10.10%/3.63%|104890.68/75577.92|84.59/139.97|
|10k|24.52/19.42|27.16%/31.99%|127133.82/75455.52|664.54/760.82|

每个新候选配对需同时满足上述噪声量和独立参考绝对上界；query活堆、idle Java/native实分配、query分配均值另要求独立参考上界+2%内，超出诊断，不用噪声量放行。数字是有限三对的经验范围，不是95%置信区间；对10k PSS的测量精度限制继续显式保留。新cross-a3三轮R-C/C-R/R-C已启动，哈希与冻结规则先核验，全程熄屏、温度、actual speed及5秒协议不变。原始3ms/2%门另报告，新候选报告只能在冻结规则之后生成。A4仍未验收。

### A4.2n新候选首轮被摘要分配门阻断；窄修订进行中

cross-a3参考三档完成，候选未进入规模采样：a4-policy-digest-cross-a3-r1-candidate-device.log触发已有分配assert；1000次reference442368 bytes/6.10ms、production1868064/15.20ms，门reference×4+65536=1835008，超33056 bytes。policyKey仍beabf579...且providerCalls=0；失败报告保留，不放宽assert，不继续旧cross-a3。

源码发现BoundedSemanticReranker.policyKey每次model()都通过ModelSelector.select生成不被消费的selected/no_match诊断文字，并新建需求对象。修订ModelSelector.selectModel使用与select共享的候选筛选/排序；select继续提供原诊断，selectModel每次读取当前注册、enabled/key/capability/context/priority，不缓存模型状态。Reranker仅复用不可变ModelRequirements、使用selectModel；policyKey编码、VERSION/缓存/请求策略不变，无个人事实缓存。新增模态/工具/上下文选择一致性、注册移除立即生效两项回归，现有模型切换/禁用及golden键继续验证。

当前独立AA参考APK与冻结控制器没有改变，校准规则仍可用于新候选；新源码须qualityCheck/debug/benchmark编译与同冻结控制器实机摘要assert通过，再冻结新候选SHA并从头新三轮，不利用cross-a3部分样本补齐。A4/A4.2n保持开放。预算仍1035198/299。

路由修订验证完成：首次quality失败仅MaxLineLength并原位换行修正，未压制规则；a4-selector-policy-repaired-quality.log成功530任务/86执行（2m10s），a4-selector-policy-jvm-summary.json 440项0失败0错误1既有skip，含两项新增选路回归。a4-selector-policy-benchmark-build.log成功384任务/42执行（47s）。a4-selector-policy-preflight-device.log使用相同冻结非调试控制器和新benchmark、双端speed，OK7（7.491s），全文/BLOB/排序与规模边界、预算/缓存契约通过。

原分配assert实机通过：a4-selector-policy-preflight-digest.json reference492064 bytes/3.46ms，production589824/3.04ms/1000次，相较被拦下候选1868064下降约68%；仍同policyKey beabf579...、providerCalls0，未放宽门。新产物ZeroV2-a4-selector-policy-debug.apk/a4-selector-policy-benchmark.apk及default测试包冻结。独立AA参考/控制器/规则仍原SHA，新cross-a4新三轮已启动；cross-a3三份参考与候选摘要失败全部保留，不拼接为新验收。当前生产检索数据路径未再改变，后续补100条真实回执选路集成，前次三档语义质量与热态边界并列记录。

cross-a4完整18报告/2880行：所有质量/来源/重复槽/blockChars一致，全部query活堆/idle Java/native实分配/query分配包络通过；a4-cross-a4-calibrated-comparison.json仍false（7/9配对通过）。R2 1k P50参考74.99/候选93.80，delta18.82>冻结14.76且93.80>AA绝对84.59；query PSS93873/104198（+11.00%）>AA10.10%但仍在绝对104890.68内。R3 100 query PSS81102/84529（+4.23%）>AA3.90%，绝对上界86017.62仍满足。全部失败保持，未用平均/候选数据改门。

下一窄修订采用此前已验证的全行单语句UNION分组路径，取消前置规模查询与本轮创建的LexicalReadScale。每词原条件/importance排序/LIMIT和最大8词、Memory100/KB300不变，全部参数绑定，显式实体列保护历史物理列序；每组按SQL原顺序重建，重复ID在本次请求内共享完整实体，原文/BLOB/字段不裁剪，无跨请求事实缓存、无读写事务或来源重检变化。Room仍30。DTO改为非空实体，避免CTE匹配行的全NULL嵌入扫描；hasManyLexicalRows只保留给边界验收诊断，生产不调用。三档字段/BLOB/排序差分现包含100/1k/10k，并保留500/501与2000/2001新事实立即可见检查。冻结AA参考/控制器/规则不变，新实现须编译/实机等价通过后冻结新候选再独立三轮。


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


### 2026-10-13 B1–B5 当前实现补充
控制面鉴权、有界命令输出、高精度预算与模型价格入口、自动化认领/DAG、安全凭据恢复均已落地。价格仅支持用户填写统一USD/token单价；未填写、用量不足及历史缺口保持未知，套餐固定费不虚算。成本页日期筛选仍统计tokens，新增金额为月度预算累计，未实现历史区间金额账单。
实施、红绿回归、编译/质量门、实机版本与配置保持证据统一维护在《【收敛开发设计】缓存可靠性·基建修订·ChatGPT接入》第9节及 artifacts/infrastructure-20261013/acceptance.md；不将局部/旧APK测试外推所有设备。Fold3跨UID抽象Socket受系统拒绝，Android使用HTTP；Termux内Socket鉴权已实测。长期96–98%自然缓存和真实费用、模型语义质量仍是独立验收边界。

### 2026-10-13 B1–B5复审补充
自动化计划显式编号及依赖字段严格解析，禁止类型错误被静默降级。BudgetAccounting独立noBackup缺口标记让预算写入失败在重建后仍可见，不影响故障放行。TermuxBridge Socket兜底在令牌发送前核对Termux peer UID，4秒读等待、1MiB完整帧及200状态约束；HTTP主通道不变。测试/编译和实机边界见收敛设计§9.7；本轮未安装、未云测。

### 2026-10-13 ChatGPT账号认证 S1（未接通模型推理）
services/chatgpt提供固定官方OAuth端点、PKCE/回调/RS256身份验证、ChatGptVault的Keystore AES-GCM/noBackup AtomicFile及单一账号owner。注册按client_id/sub分离，pending仅内存；轮换整包保存、续期合并，退出阻止新取令牌，新登录使旧在途结果失效。CHAT模型管理加入独立账号卡，不改现有模型/缓存路由。10项新增JVM及隔离存储实机通过，真实OAuth和主UI尚未验；主APK未安装。S0/S1详细状态见收敛设计§10，S2未实施。

### 2026-10-13 ChatGPT S2套餐模型
ChatGptPlanLanguageModel按显式profile接入ModelSelector，冻结账号会话与模型；按需刷新Bearer，退出/切号取消旧请求。独立ChatGptPlanProtocol固定官方Responses端点、developer输入和zero函数namespace，stream=true/store=false；原生reasoning、usage及工具续轮复用。RequestContextPreparation仅套餐按注册身份保持缓存校准，旧API仍按凭据隔离；换代策略不变。套餐同步聚合SSE、不发送max_output_tokens、不做输出预算重试；本地预留不是服务端上限。显式启用的套餐模型在授权失败时报错，不因缺凭据选中付费候选。目录仅添加停用模型，未知能力保守、用户可声明；套餐计量独立。S2本地质量门通过，真实接入S3未验。证据及边界统一见收敛设计§11。

2026-10-13 S3实机补充：官方套餐实际completed.output可为空，ChatGPT路线从output_item.done收集原生项，完整终态后释放；解析异常保留usage。实机gpt-6.1-sol文本/图片/工具续轮与账号进程恢复通过，其他型号/真实到期刷新未外推。最终补修APKf253bb…（云用例通过版本573630…）已实装，106条原观测/配置保留、临时测试包清理。详细证据artifacts/chatgpt-s3-20261013/acceptance.md；S3父门待补自然到期刷新。

> P7后续验收已通过限定范围：普通角色页/链接、3次合成云调用技能复用、724项JVM及全质量/编译；旧1138/312条观测保留，临时包清理。分屏/完整Agent自主复用与长期门不外推。详见 artifacts/p7-followup-20261015/acceptance.md。

### 2026-10-17 P5 本地编年分层首批实现
设计§7.23规定编年来源、派生记忆、当前视图与检索判定四层分离；Jev不列为旁路候选。ChronicleGraph提供只读、有界、按关系类型遍历的图与当前候选投影；MemoryChronicleReader复用来源资格审核，在同一Room事务中读取指定记忆与依赖，不做访问计数或历史写入。记录时间不补作事件时间，新记录不自动替代旧记录，UNKNOWN不自动认证；来源/记忆标识歧义拒绝而不重命名历史。
Room35/JSONv3不变，未新增独立来源日志，未切换生产检索/注入，不代表语义质量门已过。后续C3必须同时交付独立来源存储、JSON恢复和用户删除传播；C4–C6包括派生重建、消费端版本复核与中文/个人/全库实机质量。快照单测不是实际Room并发或迁移验收。验证证据统一见artifacts/p5-chronicle-20261017/acceptance.md。

### 2026-10-17 C3 最终验收完成
Room36/JSONv4落实独立来源与删除标记；不补录历史、不认证UNKNOWN。新输入模型前落库、显式确认留存、备份恢复幂等/损坏回滚，以及来源副本与聊天原文删除分离已实现。来源删除会屏蔽当前/自动证据/模型排序/向量重建/图谱队列/迟到提交，历史管理记录保持；原关系继续复用memory_sources/supersedes，精确来源版本绑定与新派生重建由C4实施，不能将最新来源当作原始版本。
最终qualityCheck、assembleDebug及两个测试APK构建4分20秒通过（586任务113执行473复用）。模拟器数据层39项、应用备份恢复25项通过；向量/图谱套件28项通过，1项跨进程条件跳过后已单独在seed/complete/verify三个不同PID完成（每阶段1项通过）。原35库非空消息/记忆/向量字节/配置/usage逐表值保留，最早v5→36链路通过；来源表初始为空。
复审修复图谱outbox既有未限定列名误绑定，原查询在独立SQLite复现0条匹配，限定作业列后1条；误删已完成/失败任务及重复入队的2项实测失败修复后通过，失败/修复日志均保留。来源删除保护也覆盖后台重建和模型排序之前的候选，不只过滤最终回答。
模拟器新装的com.zero.v2、com.zero.v2.test、com.zero.v2.core.data.test均已卸载，确认无残留；本轮自启模拟器已关闭。无云模型调用、无Jev、无手机安装/配置/usage/历史读写。未触及缓存世代/前缀/默认模型/预算策略，不声称新增缓存命中率实测。C3完成不关闭P5语义质量或C4–C6/个人/全库/手机门。证据artifacts/p5-chronicle-storage-20261017/acceptance.md。
