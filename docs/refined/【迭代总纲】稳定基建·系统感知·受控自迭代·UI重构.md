# Zero 迭代总纲 v2

> 日期：2026-09-30；状态：设计完成、实现待开发。本文是本轮目标行为的总裁决，覆盖前版方案的范围与顺序。配套：`【迭代开发设计】多模态核心与框架收敛.md`、`【迭代落地计划】多模态核心与框架收敛.md`、`【视觉设计】Zero UI与动效规范.md`。

## 1. 用户意图与最终交付

基于当前稳定运行的 Zero 做升级式整理：保留用户数据与可靠能力，以替换、迁移和退役完成干净收口。Android 是稳定宿主，负责生命周期、数据、权限、预算、状态与可观测性；LLM 是规划和能力编排者；Termux 动态模块是可生成、验证、发布、撤回的唯一运行时自迭代区。UI 重构使这些能力成为易理解的个人助手体验。

“框架化完全落地”定义为：所有生产调用方走统一契约；系统状态有单一来源；工具注册/执行/错误模型统一；新增模块无须改 ChatBot/UI 分支；已替代生产路径删除；临时适配器有截止任务且在 Z1 清零或有明确外部格式兼容理由。不是删除全部历史数据、重新安装，或把所有类抽成接口。

用户已确认 D1–D6 继续有效。新增 D7：低风险动态模块可由 LLM 自动生成、测试、灰度启用；新增权限、删除用户数据、Android 基建修改及不能证明低风险的代码只产提案。D8：系统状态向 LLM 暴露，但按字段、时效与预算控制。D9：知识库/记忆需长期可用且可替换模型；UI/图标/动效同轮重构。

注意现状文档已取消全局AUTO/CONFIRM/FORBIDDEN安全分级。D7是本次新增的动态模块发布策略，不恢复所有普通工具的旧确认流程；前版设计引用这些旧枚举只表达“角色不能改变授权”的原则。实施者应按现有Dispatcher业务/系统权限边界接线，并对自迭代发布新增独立的policy verdict。

## 2. 稳定基建与迭代区的权责

| 域 | 唯一所有者 | LLM 可执行范围 | 发布方式 |
|---|---|---|---|
| Android APK、Hilt、Room schema、权限策略、凭据 | 人类维护的宿主版本 | 读允许的摘要，提出改进建议 | 开发构建与用户安装 |
| 调度与回合、工具目录、预算、系统状态 | Android Runtime | 经能力工具提交任务/查询状态；不能改守卫 | 随宿主发布 |
| 长期记忆、Knowledge 原文/索引 | 对应 Repository | 有来源的检索/受策略约束的记忆候选 | 事务、版本化迁移、可重建索引 |
| Termux supervisor、SDK、部署器 | 稳定运行时 | 查询、调用受控发布命令 | 宿主兼容版本升级，不可自我改写 |
| Termux 模块源码、配置与私有状态 | DynamicModuleReleaseManager | 提案→生成→验证→灰度→观测→晋升/撤回 | 每模块独立版本、依赖环境及活动指针 |

现有 `CompileManager` 注释 D67 明确无沙箱、全局 pip 安装；`compile()` 先覆盖 `/root/zero/modules/<id>.py`，随后检查语法。当前行为不足以保障稳定版本或自动低风险发布。本文 D7 覆盖旧全 AUTO 设计：先分离 staging 与 active，并建立可执行的风险分级，再启用自动灰度。

## 3. 统一基建与清理清单

| 清理点/代码依据 | 目标 | 移除条件与回归证据 |
|---|---|---|
| `IZeroModule` 空方法、分散 `registerFunctions()`（TermuxBridge.onAwake 手动调用） | 生命周期只由 manager 驱动；可选能力声明由注册器读取；启动/重启幂等 | 所有模块迁移后删除手动重复注册；重复启动不重复目录项 |
| services/framework 直接注入 FunctionRegistryModule | 契约位于 module-api/common，registry/dispatcher 实现在 module-manager；app 完成组装 | 全生产 caller 迁移，services 不依赖 manager implementation；依赖图检查入 CI |
| ChatBot 的媒体解析、prompt拼装、具体 collaborator | 单回合编排 + 模态/上下文/模型/证据窄接口 | 旧路径调用数为零后删旧分支；跨入口文本、媒体、工具回归一致 |
| prompt中工具说明与HTTP tools重复、默认全量工具 | 唯一结构化目录；固定小集和按需发现 | 新工具接入无 prompt 手写清单；schema快照、权限/禁用过滤测试 |
| 独立状态字段、UI推断，例如 ZeroNavHost `liveActive=false` | UI与LLM共享真实状态投影 | 删除硬编码状态；离线/未知/陈旧均可见 |
| `TriggerHub` 仍含 `wechat_status` / `cloud_task_status` 历史路径 | 对照已放弃zero-cloud裁决核对调用可达性、配置迁移和现用IM依赖 | 有调用图与替代通道证据再删；不能仅按关键字批量删除 |
| 易失记忆队列、全量embedding载入、模型换档重建 | outbox + 有界检索 + 版本化索引 | 进程恢复不丢任务；中文/数字/否定样本召回不退化 |
| 旧文档冲突、废弃TODO、重复配置Key取用 | 单一現状入口、决策索引、凭据与配置适配 | 活跃文档无相互矛盾的执行入口；旧资料标历史；不删除用户配置 |

新增仓库门禁：禁止跨层 import（明确允许列表）；工具 handler 不能绕统一 dispatcher；对公开路径建立契约测试；查找旧路径得到零生产引用；配置/schema版本变更必须有迁移；新功能必须声明 owner、取消、超时、预算、失败语义及观测事件。自动架构检测只检查确有必要的结构约束，不将所有实现细节冻结。

## 4. Zero 系统状态向 LLM 暴露

新增 `SystemStateRepository`，聚合现有 `ModuleRuntimeStateStore`、权限状态、ModelProfile健康、BudgetGuard、AgentRuntime、TermuxBridge.health、索引作业及语音会话状态。源模块拥有事实，repository只投影，不再建第二套可随意写的状态。

```text
SystemSnapshot {
 schemaVersion, snapshotId, revision, capturedAt,
 modules: [{id, lifecycle, operational, reason, observedAt, staleAfterMs}],
 modelRoutes: [{profileId, capabilities, availability}],
 permissions: [{capability, granted, requiresUserAction}],
 runtime: {turnOwner, pendingJobs, lastFailureCode},
 termux: {connection, sdkVersion, activeReleases, deploymentState},
 resources: {batteryBand, charging, thermalBand, memoryPressure},
 budgets: {remainingClass, nextResetAt},
 retrieval: {memoryAvailable, knowledgeAvailable, indexVersion, backlog}
}
```

接口：`zero_status(sections, sinceRevision?, limit)` 只读分页；返回 facts、unknown、stale、truncated 与 nextCursor。默认回合 context只加入≤200 token必要摘要（可用能力、正在执行任务、与本次相关故障）；详细信息按需查询。状态变化可发布语义事件但要合并/去抖，不能每次电量/时间变化唤醒LLM。默认TTL建议：资源/任务15秒，权限/模块30秒，索引60秒；动作执行前仍由原始owner实时校验，不能凭LLM看到的快照执行。

未知必须保留unknown，不把超时当离线、不把缓存状态当实时。快照revision为投影版本而非跨源原子事务保证；各字段独立observedAt。查询不主动触发付费模型、安装软件或唤醒重服务。输出字段排除凭据、消息正文、文件内容、未经请求的定位和通知文本；事件日志记录ID/错误码而非秘密。固定系统前缀只写状态协议，状态实例放可变上下文以保护缓存。

## 5. Termux 自迭代闭环

状态机：`PROPOSED → STAGED → VALIDATING → READY → CANARY → ACTIVE`；任意失败进入 `FAILED`，灰度失败进入 `ROLLING_BACK → ROLLED_BACK`；进程恢复从部署日志继续，不重新执行已完成副作用。每个模块使用部署锁，宿主保留总并发/次数/费用/时长限制，用户输入与显式停止始终优先。

建议目录（需和现有supervisor协议一起迁移）：`modules/<id>/releases/<contentHash>/` 存只读代码+manifest+lock+测试报告；`data/<id>/` 为模块私有数据；`staging/<jobId>/` 为候选；活动版本记录由supervisor持有。旧 `<id>.py` 导入首个版本并校验hash，禁止编译过程覆盖active。manifest固定 `sdkRange, entrypoint, capabilities, dependencies锁版本及hash, inputSchema, outputSchema, healthContract, resourceLimits, dataSchemaVersion`。

发布协议由 `prepare_release/validate_release/activate_release/rollback_release` 统一管理，返回deploymentId与幂等结果。prepare先校验路径和大小；验证包含语法、manifest/契约、确定性样例、错误/取消/超时、健康探针；依赖在每版本环境安装，不动全局pip。activation前写预提交journal；探活成功后原子切活动记录并发布目录revision；Android Room只镜像该部署结果，重启从supervisor对账，不采用DB与进程状态两个主节点。

“低风险”必须有可执行边界：优先限制为声明式转换/查询，或经过可信runner的纯计算；只读输入副本、临时输出、无新增外部副作用、依赖已批准且固定、额度内。**任意生成Python与supervisor同Termux UID运行，venv/proot不构成权限隔离**。仅静态扫描/manifest自报/LLM评分/探活不能证明低风险；若运行时不能阻止读取宿主数据、执行shell或任意联网，则该候选自动降为“需提案”，不自动启动测试或灰度。需要执行的高风险测试也必须等用户批准。受限执行器验证未通过前，自动灰度只覆盖受信解释器中的声明式模块。

灰度是单用户限定调用：先dry-run/只读样例，再限制调用数和时长（初始建议5次/10分钟，按模块契约调整）；校验结果、错误率、延迟和副作用账本。达到门槛后晋升，失败立即停止路由并恢复上一健康版本。副作用不能靠代码回滚撤销，因此有写入/发送/删除的模块不得自动回放真实请求；变更模块数据schema须先有备份/兼容性和补偿策略，无法逆转的迁移只能提案。紧急总开关独立于LLM、模块和网络。

LLM循环只读真实失败报告、提出修复、受预算重新验证（建议最多2次自动修复）。不能修改测试判定、放宽权限或增加预算来让自己通过；可信测试/评分器由宿主管理。长期记忆保存用户明确偏好；模块演进日志保存在工程记录中，不混入用户人格记忆。

## 6. 知识库/记忆的稳定与可持续升级

前版R1–R3继续，但补充三个验收门：原始数据与索引分离；模型/分词/切块/索引版本共构embeddingSpaceId；重建写影子索引，完整性和召回通过后切换，失败保留旧索引。更换模型不能把不同维度/归一化/提示模板向量混算。

基准集固定并版本化：中文同义与口语、专名/数字、否定、用户纠正、时效冲突、多文档来源、删除后不可召回、图音转写误差。报告Recall@K、引用准确性、误注入率、P95和峰值内存；对比基线而不是以“最新模型”作为升级理由。关键词预筛可能漏掉纯语义结果：保留可测的语义候选分支或分批有界扫描，若降召回不能以限内存名义接受。只有瓶颈证据要求时换native索引。

数据导出包含来源、版本、删除/失效状态；用户删除后不能被旧索引、缓存或待处理作业复活。云端embedding不可用时词法查询仍工作，提取和重建可续跑；不新增大本地模型。核心检索接口与provider/索引Adapter解耦，成熟开源设计按契约引入、锁版本、可替换。

2026-10-05 A4用户补充决策：回合入口自动证据允许有界云端语义，不再限定纯词法零网络。双域共享同一查询向量并按实际后端/用途隔离复用，检索仍核验各自已发布空间与实时来源；合并后最多8条唯一正文进行一次短筛选，允许无相关证据时弃权，不补回未选条目。语义阶段超时/失败退回词法，用户取消继续传播；仍最多3条短证据进入可变turnContext，不进入stablePrefix。主动检索工具独立保留，自动证据和工具分别报告召回、无关与延迟；同固定样本质量与资源门未通过前不得宣布A4整体验收。

## 7. UI 目标

对话为默认首页；历史会话在抽屉/侧栏；资料入口聚合知识库、记忆与引用；工作区展示自动任务和Termux模块版本；设置放角色、模型、权限和详细诊断。状态胶囊“就绪/运行中/需要处理”由SystemState投影驱动，轻触展开原因与可执行动作。日志和模块内部字段在详情层展示。所有既有音乐/日历/文件等能力在信息架构映射中保留入口，不能美化后功能失踪。

参考ChatGPT的留白、排版、输入器和渐进呈现；Grok图标的单色几何识别方向转化为Zero开口圆环与方点标识。视觉与动效见配套规范和 `assets/zero-ui-v2/preview.html`。素材为设计交付，不表示已替换APK资源。当前已有动态色、深色主题、enableEdgeToEdge基础，按现有Theme/Navigation改造，避免另起主题系统。

## 8. 基线、迁移和最终清理

先冻结可重复基线：当前版本/工作树差异、关键用户流、DB schema/导出恢复、默认profile与模块目录；现有dirty tree不是垃圾，不能clean/reset。每片使用前向迁移、适配→切换→删除三步。旧接口兼容只服务实际数据/协议，必须记录消费者与退出条件。每阶段提交与验证是开发行为，运行时LLM不修改Android基建。

完成门：启动/恢复、文本/多模态、工具、语音、调度、资料、角色、Termux至少一次端到端验收；所有旧生产入口有保留理由或被删除；不留双状态源、双凭据路径、默认全量工具或无owner后台任务；没有内存/检索/错误率显著回退；UI入口与无障碍完整。构建/单测、设备、真实模型分栏报告。本轮设计不重新运行设备测试；实现阶段必要的新功能验收仍需安排，不受旧P2-7性能采样停止决定替代。

## 9. 修订执行顺序与新增切片

顺序：**B0 → B1 → F1 → F2 → S1 → C1 → M1/M2/M3 → C2 → R1/R2/R3 → E1/E2/E3 → P1/P2/P3 → U1/U2/U3 → M4 → C3 → Z1 → I1**。斜线表示依次执行，不表示授权并行改同一工作树。

| 状态/编号 | 具体交付 | 主要代码位置 | 必须验证 |
|---|---|---|---|
| [ ] B0 | 冻结运行基线、数据恢复样本、功能到UI入口映射 | docs、现有测试 | assembleDebug及相关基线测试；记录未跑项 |
| [ ] B1 | 清理台账，每条旧路径owner/消费者/迁移/删除门 | §3涉及文件 | 可达性审计；不先删运行功能 |
| [ ] S1 | 统一系统快照、zero_status、事件投影与UI状态 | module-api/state、services/agent、app DI | unknown/stale/TTL、字段脱敏、无副作用、按需预算、动作前复核 |
| [ ] E1 | staging、release journal、SDK/manifest版本、依赖隔离、旧模块导入 | CompileManager、CodeDevModule、zero_supervisor.py、DynamicModuleDao | 编译失败active不变、hash/路径、启动中断恢复、重复发布幂等 |
| [ ] E2 | 低风险可信runner与部署策略裁决；高风险提案 | 新Policy/Runner契约、SDK | shell/network/路径逃逸测试；不能隔离则禁自动执行候选 |
| [ ] E3 | 灰度、观察、晋升、撤回、紧急停止 | supervisor/部署器/UI投影 | 失败自动恢复、并发发布互斥、数据迁移不可逆时阻断、无重复副作用 |
| [ ] U1 | 主题token、矢量图标/自适应图标、Compose基础部件 | Theme.kt、res、ChatScreen | light/dark/dynamic色、48dp、字体200%、资源编译 |
| [ ] U2 | 导航/聊天/资料/工作区/设置，compact与fold布局 | ZeroNavHost、各Screen/ViewModel | 旧入口映射、back/IME/insets、折叠状态、滚动/草稿保留 |
| [ ] U3 | 动效、系统状态卡、部署版本卡与可访问性 | Compose animation、语义 | reduced motion、TalkBack、后台停动画、取消任务即时反馈 |
| [ ] Z1 | 全部生产caller完成迁移、删除旧路径/开关、统一文档 | 清理台账全仓 | 零遗留调用、依赖门禁、数据兼容、功能映射完整 |

F/M/C/R/P/I 的原清单继续适用，但F2不允许无限期保留服务层对manager实现的依赖，M4回退保留为有明确能力用途的Adapter；用于迁移的重复旧实现必须在Z1移除。各片独立编译/测试，schema按当时版本顺序递增，最终I1需包含本总纲全部验收。

## 10. 本次修订交付与验证

本次交付总纲、视觉规范、原设计/计划修订和本地设计素材；生产Kotlin/Python/Room与APK资源未改。`JAVA_HOME=C:\Program Files\Zulu\zulu-21`、现有用户Gradle缓存下执行`:app:assembleDebug --offline --no-daemon --max-workers=1 --console=plain`，BUILD SUCCESSFUL，227任务（1执行、226最新）。此结果仅证明当前源码可构建，不代表新设计已实现。SVG/XML/JSON/HTML脚本静态检查通过，生成参考图已目视检查；浏览器拒绝本地file://，交互预览尚未做渲染验收。未新增设备或真实模型测试，不标任何实施任务完成。

## 11. 2026-10-01 用户补充验收与执行口径

本节补充§8–9，优先于历史已完成声明：

- **工作目录与文档阅读**：SAF目录须能逐级进入/返回，Markdown在Zero内只读阅读，无外部阅读器也可用；文件授权失败不得静默吞掉，读取须有大小边界；保留用户已选目录和原文件。
- **随点报时自驱**：沿用S2R v6。用户聊天/语音直输步进退避，在场保持静默；离场后由干净报点恢复波次。定时任务kick只提前调度心跳，不得绕过空载自驱期限；启停不能积累重复订阅；窗口切换、电量、定时播放不作为用户对话重置源。需分别记录虚拟时钟边界测试与真实设备长期频率。
- **缓存**：保持固定工具发现/调用协议与追加式历史；新状态实例放可变上下文。98%按§4及多模态设计的可观测口径报告，未知不可伪装成命中或零积压。
- **Termux最新授权口径**：用户明确允许Debian工作站内LLM自主工作不受限；模块迭代部署保留staging/active分离、验证、健康检查、原子切换和回滚等稳定性规则，避免错误编码损害正在运行的版本。不得把同UID/proot说成恶意代码权限沙箱；不恢复针对一般工作站操作的额外审批。本条覆盖§5中与该授权冲突的自动执行限制，Android宿主凭据、权限及数据迁移仍由宿主治理。
- **完成记录**：实施进度与审计报告必须分列实现、单测/编译、实机、真实provider证据；剩余验收不得因旧ROADMAP全勾选而省略。

## 12. 2026-10-02 多模态与对话视觉补充验收

- 所有LLM正文采用统一现代化样式，不以正文包含勾号或短语推断特殊气泡；自驱/通知/任务来源卡保留来源区分，同步主题化、折叠与可访问点击区域。
- 已验证能力的DeepSeek官方图片模型可原生接收USER图片，非USER媒体结果保持引用，不制造Chat Completions非法角色图片块。
- 附件路线提供自动、原生多模态、工具处理；选择随附件持久化。LLM可获取完整引用，自行调用原生media_analyze或导出给动态模块；不得在历史轮次改写路线损害缓存。
- 图片、音频、视频导入/持久化/工具输出/打开须贯通。视频采样分析与原生完整视频能力明确区分；音频独立解码转写，不干扰Live麦克风。文件限额、格式不支持、模型不可用均须明确反馈并提供工具路径。
- 验收分别记录本地协议/持久化回归、APK编译、Android真实解码、真实provider输入、动态工具往返、媒体查看与缓存统计；任何一项缺证据不能升级为全量通过。

## 13. 2026-10-02 UI一致性与设置收敛追加验收
- 执行轨迹展开的思考正文、工具参数、提示文字必须与实际容器背景成对取主题色；浅色、深色、动态色可读，正文对比至少4.5:1。
- 所有一级与二级页面统一现代化中性风格、排版、圆角、间距与状态反馈；不得只改对话页。
- 配置入口按领域唯一归属；其他页面只允许必要的上下文跳转到同一配置实现，避免重复设置入口及双状态源。实际功能入口保留可达。
- 清理已经退役的设置、过时功能描述和无用户价值的开发编号；不以UI清理删除用户配置或改变已授权能力。
- 验收需记录入口映射、存量功能可达、展开/返回、浅深色及大字号可读性；编译通过不能替代实机验收。
