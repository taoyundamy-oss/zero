# Zero v2 — 知识库模块落地实施计划

> 配套设计文档：`ZeroV2-知识库设计.md`（v2.4 GPT 外审封板勘误稿，2026-09-14 用户五裁定已落档——含 §十八 Assistant 化并入）。本计划含 **SR/C/G/F 交叉验证表 + §八b AA 审计表 + §八c GA 外审复审表**——交叉审查全文，确保无落地阻塞、符合开发规范。
> 步骤前缀 **KB**（区别于 MU 音乐轨/M 模块化轨）。
> 遵循 `ZeroV2-LLM开发指南.md` R1–R8：**Android ≤3 文件/Step**（Room 迁移组例外同提交）；先读后写；参照标杆；埋 AgentTrace；改后 `cmd //c "D:\ZeroV2\_build_apk.bat"` 自构建+自修复；完成后按 R8 输出停下待用户验证。

---

## 一、文档定位

权威层级：`ARCHITECTURE_FINAL.md` > `DEVELOPMENT_DESIGN.md` > `ZeroV2-知识库设计.md` > **本计划** > `ZeroV2-LLM开发指南.md`。

---

## 二、前置依赖与基准锚点

- **工作目录须已配置**：`WorkDirRepository.hasDirectory()` 为 true；未配置时所有 kb_* FC 返回引导文本（B11 同律），不崩。
- **Room v17 现状**（音乐轨后）：`fallbackToDestructiveMigration` 开 → X9 地雷，v18 迁移同提交。
- **标杆模块**：FC+IZeroModule+AgentJob 三重身份→`MusicModule`；Room+迁移→`ReminderEntity`/`MIGRATION_15_16`/`MIGRATION_16_17`；混合检索→`MemoryModule.recall()`（B1）；词法降级链→`MemoryModule.lexicalCandidates()`；embedding 回填→`MemoryModule.startEmbeddingBackfill()`；SystemBus 加流→`onMusicDue`/`onVoiceActive` 先例；DI 双绑定→`AppBindingModule.bindMusic/bindMusicJob`（:52-55）；前台麦克风服务→`LiveSessionService`（KB3.1/KB3.3）；模型管理区→`ModuleProfilesSection`（KB0.3）。
- **不可破坏基线**：三 Tab 不动、chatHistory 纯净、N6.6、D19 单一 tick、FunctionRegistry 唯一、BudgetGuard、D57 SAF 边界。
- **范围（KA9 用户裁定）**：`knowledge/` 专属子树 `.md` only；目录名编译期常量 `KnowledgePaths.SUBDIR`（不入配置，MD15 同律）。
- **实时更新（KA10 用户裁定）**：`SystemBus.onFilesChanged` 事件链进 P1。
- **Assistant 化（裁-5 2026-09-14 并入）**：设计 §十八（源出探索文档存档）；Phase KB3 收官——**息屏唤醒不设计不承诺**（特权墙）；目标机型三星外版 One UI（裁-D，R1/G6 四点实测）；sherpa-onnx AAR 依赖树核验为 KB3.3 前置（SR15）。

---

## 三、分阶段实施

### Phase KB0 — 定案、记忆配套修订与评估

**KB0.1（已完成 · 3052972）**：设计定案 v2（三裁定落档 KA9/KA10/裁-3）+ 本落地计划 + 指南 §八 索引同步。纯文档零代码。
**KB0.1b（已完成 · 9b49079）**：v2.1 增补——记忆模块后台模型绑定方案落档（裁-4 → KA11/KD9/§十六.0）+ 计划前置步插入。纯文档零代码。
**KB0.1c（已完成 · 本提交）**：v2.2 交叉审计修订——AA1–AA5 全处置（§八b AA 表）：md 写径事实修正（`save_to_file` 单径）/emit 面收窄 4 FC/Scheduler 契约落档/doIndex 单飞互斥/KB0.3 专用小 VM。纯文档零代码。

**KB0.2（前置 · 记忆模型绑定，设计 §十六.0 方案 1+2）**：`MemoryModuleConfig.kt` 删 `reflectionModel`/`graphExtractionModel` 两 DROPDOWN（连同 `toJson`/`fromJson` 两处硬编码与 `MemoryV1ToV2` 迁移 put 两行；`configVersion` 保持 2——删字段对存量 JSON 无破坏，`optString` 不读即无害）+ `MemoryReflectionJob.kt`（`getById(cfg.名) ?: select(CHAT)` → 直连 `select(setOf(ModelCapability.CHAT))`，注释记 KA11 裁定）+ `GraphExtractionJob.kt`（同款直连；无可用模型 → 既有 skip+trace 不变）。3 文件。
- 验证：Build ✅；AutoForm 参数区不再显示两模型 DROPDOWN；trace `mem_reflection`/`mem_graph` 模型解析路径只剩 select（无 getById 分支）；存量配置 JSON 升级不崩（旧多余 key 静默忽略）。

**KB0.3（前置 · 记忆详情页后台模型信息卡，设计 §十六.0 方案 3 + AA5 + GA4）**：`LlmProfilesScreen.kt` 同文件加只读卡"⚙️ 后台任务模型——Reflection · 图谱提取 · 事实提炼 → 跟随当前对话模型（{当前名}）"+ **专用小 VM**（注入 **ModelSelector**，数据源**与 Job 同源**：`select(setOf(CHAT))` 实际结果的 modelId，collect profile 变化重算——**GA4 勿自造 "filter enabled+first"**，`select` 按 jaccard 择优非首个，两套逻辑多 CHAT 并存时会不一致；**不复用 `ModuleProfilesViewModel`**，其 `setKind` 单值态会与同屏 EMBEDDING 管理区互踩）+ `ModuleDetailScreen.kt` memory 分支调用（`ModuleProfilesSection` 旁）。2 文件。
- 验证：Build ✅；记忆模块详情页显示卡与当前启用 CHAT 模型名一致；同屏"模型管理（向量嵌入）"区过滤不受卡片影响（AA5 互踩回归点）；切换/停用 CHAT profile 后返回页面刷新（N2.6c 即时生效语义）；其他模块详情页不受影响（仅 memory 分支）。

**KB0.4**：评估集建档 `docs/refined/ZeroV2-知识库评估集.md`——20 问 → 期望命中片段（术语类/语义类各半）+ Recall@5/10 记录表 + 跑法说明。纯文档零代码（裁-3：编码前建，校准切分目标）。
- 验证：文档评审通过；KB1.3 切分器实现后可立即用真实文档跑首轮基线。

### Phase KB1 — P1 离线地基 + 实时链

**KB1.1（迁移组，同提交 X9）**：`KbDocEntity`+`KbChunkEntity`（`core/data/.../entity/KnowledgeEntity.kt` 新——`relative_path` UNIQUE 索引（MD13 同律）+ `doc_id` FK CASCADE + 索引 + `embed_model DEFAULT ''`（KA5）+ NOT NULL DEFAULT 与 `@ColumnInfo(defaultValue=)` 逐项对齐）+ `KbDao`（`core/data/.../dao/KbDao.kt` 新，§5.3 全方法一次到位：docs 查-留-id-更新禁 REPLACE（B8）/chunks 批量 `@Transaction`）+ `ZeroDatabase` v18+entities+`abstract fun kbDao()` + `DatabaseModule` `MIGRATION_17_18`+`provideKbDao`+`addMigrations`。同提交 4 文件（迁移组例外）。
- 验证：构建通过；老库升级不抹数据（X9 L3 必测）。
- 仿 `MIGRATION_16_17`（单表 CREATE）×2 + UNIQUE/外键索引仿 `MIGRATION_15_16`。

**KB1.2**：`RetrievalKit`（`services/src/main/java/com/zero/v2/services/retrieval/RetrievalKit.kt` 新——`cosineSimilarity`/`rrfFuse` 泛型化（按 key 计分）/`encodeEmbedding`/`decodeEmbedding`/`queryBigrams` 无状态纯函数，函数体自 MemoryModule 搬家非重写）+ `MemoryModule.kt` 切换引用（删除私有实现改调 RetrievalKit；**行为零变化**——C4）。2 文件。
- 验证：构建通过；**memory_recall 回归**——既有记忆检索结果不因搬家变化（trace path=hybrid/lexical 与切换前一致）。
- 零 gradle 改动（framework/memory 已 `implementation(:services)`，R5 合规）。

**KB1.3**：`KnowledgeModule` 骨架（`app/src/main/java/com/zero/v2/app/knowledge/KnowledgeModule.kt` 新：`KnowledgePaths.SUBDIR="knowledge"` + `KnowledgeConfig` 5 字段（§十二）+ IZeroModule（SYSTEM/launchOrder=130）+ AgentJob（id=`kb_index`——**AA3 契约**：`override var nextRunTime` 初值 MAX、onStart 置 now（首 tick 即索引）、execute 尾 `now+indexIntervalMin`；Scheduler onTick 遍历读属性，Scheduler.kt:78-91 实证）+ **indexMutex 过程级单飞**（AA4/KD10——doIndex 全程持锁，事件/启动/周期/kb_reindex 四路排队不重入）+ **markdown 切分器**（§六.1：标题分节/300–600 字块/heading_path 链/代码围栏不切/maxChunksPerDoc 停止+trace）+ **索引引擎 doIndex**（§七.2：listTree 过滤子树 `.md` → 三元组比对 → 新/变→`readText` 读文切分→查-留-id-更新 upsert+deleteChunksByDoc+insertChunks → 库有树无→删行 + 枚举守卫 + maxDocs 拒新+trace + 启动 autoIndexOnStart + 子树引导 createFolder）+ `AppBindingModule` `bindKnowledge`+`bindKnowledgeJob` 双 `@IntoSet`。2 文件。
- 验证：放 3 篇 md 进 `knowledge/`（含 1 篇多级标题长文 + 1 篇无标题短文 + 子目录 1 篇）→ 启动/周期触发 → trace `index_done` 统计正确；chunk heading_path 正确；改 1 篇重索引仅该文档块替换；删文件行删；>5 层子目录 trace 提示不索引（SR1）。

**KB1.4**：`SystemBus`+`SystemBusImpl` 加 `onFilesChanged: MutableSharedFlow<List<String>>`（`core/bus` 2 文件；**DROP_OLDEST + buffer 32——EK5 勘误（原写 EXTRA_BUFFERED 系 Channel 概念误植，SharedFlow 无此策略）**：满丢旧保新（新事件对账覆盖旧事件）+ tryEmit 永不阻塞 FC；仿 `onVoiceActive` 同策略；**List<String> 载荷**=变更工作目录相对路径，C5）。2 文件。
- 验证：构建通过。

**KB1.5（AA2 收窄后）**：`FilesModule` **四个可影响 knowledge/ 的 FC** 尾部 `tryEmit`——`save_to_file`（emit `WriteOutcome.finalRelPath` **真实落点**非入参 file_name——冲突改名场景，AA2/SR14）/`move_file`（emit 新旧两路径）/`delete_work_file`/`delete_folder`（emit 目标路径）；各 +1–2 行，runCatching 包裹防事件层故障伤文件操作；**不判断是否 knowledge/ 路径、不判断扩展名**——过滤归消费方，文件域不感知知识库存在。`create_document`/`edit_document` 产物非 .md（办公域）不 emit。1 文件。
- 验证：构建通过；`save_to_file` 写文件后 trace 见 emit 且路径=finalRelPath（构造重名冲突场景验真实落点）；move/delete 同验（KB1.6 订阅前先验事件到达）。

**KB1.6**：`KnowledgeModule` 订阅 + FC×3——onStart collect `onFilesChanged` → debounce 2s → 过滤 `knowledge/` 前缀 → **触发 doIndex(trigger="event") 全量树对账**（**GA2 勘误**：事件路径仅作 trace 提示，**不做 path 级短路**——三元组跳过未变更者、成本已低；真相以树对比为准，KD8）；注册 `kb_search`（P1 纯词法：分词 LIKE ∥ 查询 bigram LIKE → 命中计数排序 + path_prefix/tag 过滤 + 空库/无命中引导文案）/`kb_status`（文档数/块数/上次索引时间；嵌入位 P2 增强占位）/`kb_reindex`（后台全量或单文档，立即返回受理）；onAwake 自调 registerFunctions（S4.2-fix 铁律）。1 文件。
- 验证：`save_to_file(file_name="knowledge/x.md", format="md")` 写入 → 2–3s 内 trace `index_done trigger=event`；对话 `kb_search` 命中片段且附来源路径；`read_work_file` 可按提示读全文；评估集术语类首轮 Recall@5 记录基线。

### Phase KB2 — P2 向量混合

**KB2.1**：`KnowledgeModule` 嵌入链——doIndex 新块异步回填（`getWithoutEmbedding`→`embedBatch`(≤10)→`updateEmbedding(id,blob,modelId)`，整批失败停留下轮续，Memory backfill 同款；**GA5/KD14 回填单飞：全局唯一 backfill worker——`backfillJob?.isActive==true` 则新触发直接忽略，禁多 worker 并发消费 `getWithoutEmbedding`（重复 embed=重复计费）**；`hybridEmbedding` 关或 Key 缺→跳过+trace）+ 嵌入文本构造（§六.2 `《title》 heading ▸ text`.take(1000)）+ `kb_search` 升级双路 RRF（向量路：embed(query)→`getWithEmbedding` 维度+embed_model 双校验→余弦；RRF K=60 融合词法路；降级链 §九）+ `kb_status` 增强（已嵌入块数/当前模型/换档检测提示）。1 文件。
- 验证：有 Key 环境——新文档块数=已嵌入块数收敛；`kb_search` trace path=hybrid；换 embedding profile → kb_status 提示换档 → `kb_reindex` 后恢复混合；无 Key 环境——trace path=lexical 恒可用（永不哑火）；评估集语义类 Recall@5 对比 P1 基线（G2 判据）。**P2 向量混合收官（Assistant 化 Phase KB3 继续）**。

### Phase KB3 — Assistant 化与语音唤醒（裁-5 并入 · 设计 §十八）

**KB3.1（Assistant 角色壳）**：新 `app/.../assistant/ZeroAssistantService.kt`（三组件同文件：`ZeroAssistantService`(VoiceInteractionService：onPrepareSession 回调支持/isActive) + `ZeroAssistantSessionService`(VoiceInteractionSessionService：newSession) + `ZeroAssistantSession`(VoiceInteractionSession：onShow→`LiveSessionService.start` 语音模式——MD17 单入口同精神；系统窗口免 BAL，AB-E)）+ `AndroidManifest.xml`（两 service 声明：exported=true + `android:permission="android.permission.BIND_VOICE_INTERACTION"` + intent-filter `android.service.voice.VoiceInteractionService`/`VoiceInteractionSessionService`）+ `SettingsScreen.kt` 加"设为默认助手"引导入口（SDK_INT≥29 且 `RoleManager.isRoleAvailable(ROLE_ASSISTANT)`→requestRoleIntent；否则跳系统设置页——SR16）。3 文件。
- 验证：Build ✅；设为默认助手后角落手势/电源键触发 → Live 语音会话起（三星 L3 点①②，R1/G6）。

**KB3.2（AssistantModule 配置骨架）**：新 `app/.../assistant/AssistantModule.kt`（id="assistant"，SYSTEM，launchOrder=140；`AssistantConfig` 三字段 §18.3——hotwordEnabled(TOGGLE 默认 false)/residentMode(DROPDOWN 关·仅充电·全时段)/wakeWord(TEXT 默认"你好，大帝")；onAwake/onConfigChanged→按配置编排 HotwordService 启停；无 FC）+ `AppBindingModule.kt` `bindAssistant` @IntoSet。2 文件。
- 验证：Build ✅；模块详情页 AutoForm 渲染三字段（零 UI 代码）；开关默认关（HotwordService 不启动）。

**KB3.3（HotwordService 唤醒常驻 · 含 KD15/GA3 启动来源规则）**：新 `app/.../assistant/HotwordService.kt`（前台服务 `microphone` type 仿 LiveSessionService 3-arg startForeground；AudioRecord 流式喂 sherpa-onnx KWS——**前置核验 SR15**：grep 语音模块 build.gradle 依赖树，缺失则本步 `build.gradle.kts` 显式声明（R5）；命中→（可选确认音）→`LiveSessionService.start`；`onVoiceActive` 协同 KD12：true→停 AudioRecord/KWS **保服务**（通知切"会话中暂停监听"）、false→恢复；三档策略执行：关=不启、仅充电/全时段=**desired state**（KD15——POWER 广播/后台 onConfigChanged 仅更新期望态并 try 启动，catch `ForegroundServiceStartNotAllowedException`（Android 12+）与麦克风无流（Android 14 while-in-use）→ trace（E28）+ 等待合法窗口（用户打开 app/VIS 触发）补启动；**前台交互启动为主路径，VIS 提供方属官方豁免**；**不加 WorkManager/AlarmManager/保活架构**）；静默失败留 trace（E28））+ `AndroidManifest.xml` service 声明（`foregroundServiceType="microphone"`，FOREGROUND_SERVICE_MICROPHONE 权限已有）。2–3 文件（+gradle 仅当 SR15 核验失败）。
- 验证：Build ✅；亮屏喊"你好，大帝"→Live 会话起（在播音乐自动让位）；**Live 会话期间喊不触发**（R7 自唤醒回归点）；三档切换生效；**后台（HOME 出 app 后）POWER 触发启动 FGS 真机行为记录入档**（GA3——Android 12+/14+ 限制实测，非 L3 可推）；息屏数分钟后喊不响应（平台边界行为 trace 记录入档）。
- **fix2 实测回执（09-15 用户确认）**：✅ 数字助手列表已出现 Zero——ASSIST activity 路即根因（G6 ① 通过）；G6 ②③（手势/侧键触发）随本阶段继续实测。

**KB3.4（打磨与 L3 实测 · 全轨收官）**：KWS 模型资产落位（AB-C 三选一定案实施：assets/工作目录/首启下载）+ 默认词拼音序列预置与自定义唤醒词路径定案（AB-B 三选一；若选内置拼音库则 R5 显式声明）+ 阈值/确认音调参 + 三星 One UI 四点实测矩阵执行（G6：①默认助手能否选到 Zero ②角落手势触发 ③侧键长按可配置性 ④Bixby 占位替换）结果入档。1–2 文件+文档。
- 验证：误触/漏触主观达标；R1/G6 四点结果入档；**KB 轨全轨收官——输出全轨验收清单交用户 L3**（知识库域 + 记忆配套 + Assistant 化）。

### backlog 提案（不占步骤，用户裁定后另立）

- Memory 表补 `embed_model` 列（KA5 联动发现——记忆域同款换档防护 + v19 迁移）。
- LLM contextual embedding / reranker / 跨库检索（memory+kb 同空间融合）/ 知识图谱（P3，触发 §十六.3 拆分判据）。
- 指南 §1.2 "Room 版本 14" 数字已滞后（当前 v17，本轨后 v18）——顺带勘误候选。

---

## 四、§进度表

| Step | 内容 | 状态 |
|---|---|---|
| KB0.1 | 设计定案 v2（三裁定落档）+ 落地计划 + 指南 §八 索引同步 | ✅ 2026-09-13（纯文档零代码） |
| KB0.1b | v2.1 增补：记忆后台模型绑定方案落档（裁-4/KA11/KD9）+ 前置步插入 | ✅ 2026-09-13（纯文档零代码） |
| KB0.1c | v2.2 交叉审计修订：AA1–AA5 全处置（md 写径/emit 面/Scheduler 契约/单飞互斥/专用 VM） | ✅ 2026-09-13（纯文档零代码） |
| KB0.2 | 记忆模型绑定：删两 DROPDOWN 硬编码 + 两 Job 直连 select(CHAT) | ✅ | Build ✅ APK 09-14 20:55（3 文件：MemoryModuleConfig——删 reflectionModel/graphExtractionModel 两 DROPDOWN + toJson/fromJson 两处 + MemoryV1ToV2 迁移 put 两行（KA11 注释入类 doc；configVersion 保持 2，C7）；MemoryReflectionJob——distillPendingAi 模型解析 getById?:select(CHAT) → 直连 select(CHAT)（无可用模型→return 0 缓冲保留，原语义）；GraphExtractionJob——同款直连（无可用模型→skip 计数保留，原语义）；**模型 DROPDOWN 消失（反射遍历字段即除）+ 存量 JSON 残留 key 静默无害 = 代码可证；真机 AutoForm 显示/trace select 路径/覆盖安装不崩 = L3 待用户** |
| KB0.3 | 记忆详情页"后台任务模型"只读信息卡（跟随当前对话模型） | ✅ | Build ✅ APK 09-14 21:09（2 文件：LlmProfilesScreen——+`MemoryBgModelViewModel`（注入 ModelSelector+LlmProfileStore，`currentChatName = store.profiles.map { select(CHAT)?.modelId }`——**GA4 与 Job 同源**，独立小 VM 防互踩 AA5/SR13）+`MemoryBackgroundModelCard` 只读卡（SR12 空降级文案"未启用对话模型——后台任务将跳过"）；ModuleDetailScreen——profilesKind 区内 memory 分支挂载卡（其他模块零改动）；自修复 1 处：KDoc 草稿残字；**卡显示/同屏 EMBEDDING 区不受互踩/切模型返回刷新/其他模块页无影响 = L3 待用户** |
| KB0.4 | 评估集建档（20 问 + Recall 记录表） | ✅ | 纯文档 1 文件（`ZeroV2-知识库评估集.md`）：测试语料三篇强一致（中医方剂穴位/Zero 架构决策/日常见闻——按目标块 300–600 字+标题分层组织，兼作切分目标校准样张）+ 20 问（术语 10：方名/药名/寸数/模块名/决策号/编号；语义 10：刻意零/近零共享词换说法——词法弱向量强的区分区）+ 命中判定规则（同节相邻 chunk 计中）+ 跑法 5 步（kb_reindex 归零/同条件纪律/换版作废）+ Recall@5/10 记录表 + G2 数字判据（术语 P1≥9 达标；语义 P2 提升≥2 题坐实；<1 题复议 hybridEmbedding 默认）；**KB1.3/KB2.1 后真机跑数填表 = L3 待用户** |
| KB1.1 | KbDoc/KbChunk Entity + KbDao + v18 + MIGRATION_17_18 | ✅ | Build ✅ APK 09-14 21:19（X9 迁移组 4 文件同 commit：KnowledgeEntity——KbDocEntity（relative_path UNIQUE 索引=upsert 锚点，MD13 同律；title/tags DEFAULT ''）+KbChunkEntity（doc_id FK CASCADE+索引；heading_path DEFAULT ''；embedding BLOB 可空=待回填 KD4；embed_model DEFAULT '' 换档防护 KA5）；KbDao——docs 查-留-id-更新五方法（insertDoc 无 REPLACE，B8）+chunks 八方法（deleteChunksByDoc/insertChunks @Transaction/searchByText LIKE/getWithEmbedding/getWithoutEmbedding/updateEmbedding 含 model 落列/countAllChunks/countModelOf 换档检测）一次到位（KB1.3/1.6/2.1 不再动 DAO）；MIGRATION_17_18 双表 CREATE+UNIQUE/外键索引（DEFAULT 逐项对齐，FK 子句与 Room 生成一致）；v18+provideKbDao+addMigrations）；**老库升级不抹数据（v17→18 覆盖安装）= L3 必测待用户** |
| KB1.2 | RetrievalKit 抽取 + MemoryModule 切换（行为零变化） | ✅ | Build ✅ APK 09-14 21:27（2 文件：RetrievalKit.kt 新（services/retrieval——cosineSimilarity/rrfFuse **泛型化**（keyOf 计分，唯一签名变化）/queryBigrams/encode·decodeEmbedding 五原语 + RRF_K=60；C4 纯搬家：函数体逐行原样）；MemoryModule——删四私有实现+RRF_K、8 调用点全走 RetrievalKit（rrfFuse 调用点 keyOf={ it.id } 适配+注释；grep 复核零裸调用残留）；零 gradle 改动（framework/memory 已 implementation(:services)，C3）；**memory_recall 回归（trace path=hybrid/lexical 与切换前一致、检索结果不变）= L3 待用户（C4 判据——PASS 才可进后续真机项）** |
| KB1.3 | KnowledgeModule 骨架 + 切分器 + doIndex 增量引擎 + 双绑定 | ✅ | Build ✅ APK 09-14 21:34（2 文件：KnowledgeModule.kt 新——KnowledgePaths.SUBDIR 常量（KD7）+KnowledgeConfig 5 字段（min/max 显式+coerceIn）+IZeroModule（knowledge/SYSTEM/130）+AgentJob（**AA3 契约**：var nextRunTime 初值 MAX/onStart 置 now/execute 尾 +=intervalMin）+**indexMutex 过程级单飞**（KD10）+markdown 切分器（标题栈 heading_path 链/围栏 ``` 保护空行不切/段落聚合 300-600（≥300 出块）/超长句末贪心硬切/无标题整篇（<300 单块）/title=首标题行缺省基名/maxChunksPerDoc 截断+trace）+doIndex（createFolder 幂等引导→listTree 过滤子树 .md→三元组比对一致跳过→新·变 readText 重切分→查-留-id-更新（deleteChunksByDoc+updateDocIndexed/insertDoc+insertChunks，dbMutex）→库有树无删行+**枚举守卫**→maxDocs 拒新+trace→index_done 统计）；AppBindingModule——bindKnowledge+bindKnowledgeJob 双绑定（:52-55 先例）；**执行偏差 1 项**：update 路径 title 列不刷新（updateDocIndexed 不含 title、DAO 已一次到位不加参数）——首个标题行改名后 DB title 陈旧仅影响展示/嵌入上下文用名，删行重收录即刷新（代码注释记录）；**3 篇测试 md 入库/heading_path/改动仅该文档重切/删文件行删/>5 层提示 = L3 待用户（评估集语料即测试样本）** |
| KB1.4 | SystemBus.onFilesChanged（core:bus 2 文件） | ✅ | Build ✅ APK 09-14 22:03（2 文件：SystemBus——接口 +KDoc（载荷语义/事件=提示树=真相/四 FC emit 面与 finalRelPath，AA2/C8/GA2 引用）；SystemBusImpl——DROP_OLDEST + buffer 32 + tryEmit 永不阻塞；**EK5 勘误同 commit 入档**：设计原写 EXTRA_BUFFERED 系 Channel 概念误植（SharedFlow 溢出策略仅 SUSPEND/DROP_OLDEST/DROP_NEWEST）——三文档（设计 KD8/§七.1、计划 C5/KB1.4/EK5、提示词 KB1.4 段）同步改 DROP_OLDEST（满丢旧保新=新事件对账覆盖旧事件，语义一致）；订阅方随 KB1.6 |
| KB1.5 | FilesModule 四写 FC 尾部 emit（save_to_file·finalRelPath/move/delete×2，AA2） | ✅ | Build ✅ APK 09-14 22:09（1 文件：FilesModule——构造 +SystemBus 注入 + `notifyFilesChanged(vararg)` 统一 helper（tryEmit+runCatching 故障不伤文件操作）；四处成功路径 emit：save_to_file→**finalRelPath 真实落点**（SR14）/move_file→新旧两路径/delete_work_file/delete_folder→目标路径；**不判路径/扩展名**（过滤归消费方）；create_document/edit_document（办公域非 .md）不 emit（AA2/C8）；订阅方随 KB1.6）；**写文件后 trace 见 emit 且路径=finalRelPath（重名冲突场景）/move·delete 同验 = L3 待用户（KB1.6 订阅前先验事件到达）** |
| KB1.6 | KB 订阅 debounce + FC×3（kb_search 词法/kb_status/kb_reindex） | ✅ | Build ✅ APK 09-14 22:28（1 文件：KnowledgeModule——onStart 订阅 onFilesChanged **debounce 2s**（@OptIn FlowPreview）→ 过滤 knowledge/ 前缀仅触发判定+trace → doIndex 全量树对账（GA2 不做 path 级短路）；FC×3（SR6 grep 全库无 kb_ 前缀冲突）：**kb_search**(AUTO——P1 纯词法：整词 LIKE 权重2 ∥ RetrievalKit.queryBigrams 补充权重1 → 计数排序 + path_prefix/tag 前置过滤（docById 映射）+ 空库引导 save_to_file 文案（KA12）+ 无命中附最近文档 + 尾注 read_work_file/memory_recall 分工；description 写明与 memory_recall 分工)；**kb_status**(文档数/块数/上次索引/最近 5 篇；嵌入位 KB2.1 占位)；**kb_reindex**(删行→doIndex 全量对账重收录——单文档=删该行/全量=清空行，后台受理+indexMutex 串行，knowledge/ 前缀容错)；B11 未配目录三 FC 引导不崩；自修复 1 处（kb_status description 字符串拼接漏 + 号）；**写 md→2-3s 内 index_done trigger=event / kb_search 命中附来源路径 / 评估集术语类 Recall@5 基线 = L3 待用户**；**P1 离线地基+实时链收官** |
| KB2.1 | 嵌入回填 + 双路 RRF + embed_model 换档防护（P2 收官） | ✅ | Build ✅ APK 09-14 23:00（1 文件：KnowledgeModule——**回填链**（ensureBackfill 单飞守卫 KD14/GA5：worker 活跃即忽略、doIndex 尾+onStart 双触发；backfillLoop Memory 同款节奏：批 10→embedBatch→updateEmbedding 含 modelId 落列 KA5，整批失败终止留下轮续；hybrid 关/无 Key 跳过+trace E28；**KA4 嵌入文本构造**《title》 heading ▸ text.take(1000)）；**hybridSearch 双路**（向量路 embed→getWithEmbedding **维度+embed_model 双校验**（≠当前模型只走词法）→余弦；RetrievalKit.rrfFuse K=60 融合词法；降级链 config_off/no_key/embed_failed/vec_empty→纯词法，**永不哑火**；trace path=hybrid/lexical_*）；**kb_status 增强**（嵌入状态三态行：关闭/无 Key（指引记忆模块向量嵌入配置）/启用+换档检测（cur==0 全换档强提示、部分换档提示 kb_reindex，countModelOf））；**有 Key 收敛/换 profile→reindex 恢复/无 Key 恒词法/评估集语义类对比 P1 = L3 待用户（G1/G2 判据）**；P2 向量混合收官 |
| KB3.1a | Assistant 角色壳组件 + manifest 声明（裁-5 并入） | ✅ | Build ✅ APK 09-14 23:08（3 文件：ZeroAssistantService.kt 三组件同文件——VoiceInteractionService(onReady+trace)/SessionService(@AndroidEntryPoint 注入 trace 传 Session)/Session(**onShow→LiveSessionService.start 幂等**（R6：onStartCommand 无 action 路径幂等，免运行态查询）+hide() 系统窗口退场零 UI v1)；Manifest 两 service（BIND_VOICE_INTERACTION 组件属性零特权+meta-data）；res/xml/zero_assistant.xml（sessionService 关联——系统标准机制）；**执行偏差（R7 入档）**：计划 3 文件未计 xml 资源（sessionService 关联必需，AgentRuntimeService meta-data 同款先例）→ 按 R1 拆 KB3.1a/b；**设默认助手后手势/电源键触发→Live 会话起（三星 L3 ①②）待用户（KB3.1b 引导入口就绪后验）** |
| KB3.1b | SettingsScreen"设为默认助手"引导入口（RoleManager 29+/设置页跳转，SR16） | ✅ | Build ✅ APK 09-14 23:20（1 文件：SettingsScreen——音乐库行后 +"语音助手"入口（Mic 图标，filled.* 通配免 import）+requestAssistantRole：直跳 ACTION_VOICE_INPUT_SETTINGS（语音输入与助手页，比默认应用列表直达）→ 失败回落 ACTION_MANAGE_DEFAULT_APPS → 双失败 Toast）；**执行偏差（R7 入档）**：原计划 RoleManager.createRequestRoleIntent(ROLE_ASSISTANT) 系统弹窗——`android.app.RoleManager` 在本编译环境 unresolved（compileSdk 35 理应含 API 29 类，原因未定位不硬磕）→ 降级设置页直跳（SR16 手动分支升主路径），L3 后再议恢复；**入口可达性/选中 Zero 后手势触发（G6 ①②）= L3 待用户**；**fix（L3 实测 09-14 · 数字助手选择列表无 Zero）**：根因排查——①One UI picker 已知对第三方 VoiceInteractionService 挑剔（连 Bixby 更新后都曾从列表消失，外部多起报告）②覆盖安装后 system_server 不重扫助手服务（Android 已知行为——重启强制全量重扫）；处置：Manifest 两 service +label="Zero"（OEM picker 渲染硬化）+ 三步排查通道（重启→确认"数字助手应用程序"页→adb 强制设置验证声明链路：`adb shell settings put secure assistant com.zero.v2/com.zero.v2.app.assistant.ZeroAssistantService`——强设后手势可触发即证明声明有效、问题锁定 One UI picker 过滤，届时降级入口方案另议）；Build ✅ APK 09-14 23:34；**fix2（L3 实测 09-14 · 重启+label 后列表仍无 Zero）**：根因定位——系统"数字助手"候选列表 = VoiceInteractionService（service 路）**∥ ACTION_ASSIST activity（轻助手路）两路并集**（AOSP DefaultAssistPreferenceController 同构；ChatGPT 成功配方两路兼备，公开拆解证实 ASSIST 路存在）——此前 Zero 只有 service 路，One UI 对第三方 VIS 挑剔时即遗漏；处置：+`AssistHandlerActivity`（Theme.NoDisplay 无 UI 中转：assist intent → LiveSessionService.start → finish；manifest 声明 ACTION_ASSIST + DEFAULT/ASSIST 双 category + excludeFromRecents/noHistory）——activity 候选查询恒实时（不经 VIS rescan 缓存），装完即应入列；Build ✅ APK 09-14 23:56 |
| KB3.2 | AssistantModule 配置骨架（三档常驻/唤醒词 ConfigField）+ 绑定 | ✅ | Build ✅ APK 09-15 00:08（2 文件：AssistantModule.kt 新——AssistantConfig 三字段（hotwordEnabled TOGGLE 默认关，裁-C/residentMode DROPDOWN 关·仅充电·全时段（fromJson 白名单钳位）/wakeWord TEXT 默认"你好，大帝"，裁-B——**toJson 整体经 JSONObject 构建**（wakeWord 自由文本防引号破 JSON）；模块 id="assistant" SYSTEM launchOrder=140 无 FC；**applyHotwordPolicy 编排位**（期望态计算+trace 锚点——KB3.3 实装 HotwordService 启停，KD15 启动来源规则注释入档）；onConfigChanged trace 留痕）；AppBindingModule +bindAssistant）；**模块详情页 AutoForm 三字段渲染 = L3 待用户** |
| KB3.3 | HotwordService 唤醒常驻（sherpa-onnx KWS + onVoiceActive 协同 + 三档策略） | ✅ | Build ✅ APK 09-15 00:16（3 文件：**SR15 核验通过**——sherpa-onnx.jar 在 services/libs（`implementation(files)` 不向 app 传递）→ app/build.gradle.kts 同源声明（R5 显式，KB3.3 预授权；JAR 含全套 KWS 类 KeywordSpotter/Config/Result 实证；ASR 模型走 ModelAssetManager 资产机制=AB-C 对齐对象）；HotwordService.kt——前台 microphone 服务（3-arg startForeground 仿 Live）+ **KD12 协同**（onVoiceActive true→stopCollecting 保服务/通知切"会话中暂停"、false→resume）+ 期望态自检（开关关/仅充电未充电→stopSelf）+ resolveKws 五件套就绪检查（filesDir/models/kws——未就绪不采集省电+trace，**KWS 流式循环随 KB3.4 与模型同落**（本步服务/通知/协同/三档全链可验））+ 通知三态文案（监听中/会话暂停/模型未就绪）+ companion start 返回成败（KD15 catch 归调用方留痕）+ **HotwordPowerReceiver**（manifest 注册，仅充电档插电 tryStart+失败 trace 等合法窗口/拔电停——POWER 不在 FGS 豁免清单）；Manifest +service+receiver 声明；自修复 1 处通知文案残字；**服务启停/通知三态/KD12 协同/仅充电自停 = L3 待用户（真唤醒随 KB3.4）** |
| KB3.4 | 打磨与 L3 实测（模型资产 AB-C/自定义词 AB-B/三星四点 G6）——全轨收官 | ✅ | Build ✅ APK 09-15 00:23（2 文件：HotwordService——**KWS 流式接线**（javap 预核验 JAR Builder 签名：setFeatureConfig(16k/80)/setOnlineModelConfig/setKeywordsFile/setKeywordsThreshold 0.25=调参入口；AudioRecord VOICE_RECOGNITION 16k mono 100ms 帧→acceptWaveform→isReady/decode 循环→getResult().keyword 命中→reset+3s 冷却→LiveSessionService.start；RECORD_AUDIO 运行时守卫；release 全链）；**AB-C 定案=双通道**（filesDir/models/kws/ 直推【L3 免重打包 adb push 即生效】+ assets/models/kws/ 打包内置自拷贝【对齐 ModelAssetManager 惯例】）；AssistantModule——**编排实装**（期望态→真启停：关=stop；全时段=start；仅充电=充电态判定未充电由 PowerReceiver 拉起；KD15 失败 trace 等合法窗口；@ApplicationContext 注入）；**keywords.txt 交模型包提供**（默认词"你好大帝"拼音行 L3 按包内格式添加——硬编码 token 序列与模型词表不匹配风险高，不开=AB-B"模型自带方案"）；**模型就位指引（L3）**：GitHub k2-fsa/sherpa-onnx releases 下 `sherpa-onnx-kws-zipformer-zh-wenetspeech-3.3M-2024-04-19` → 五件套**重命名** encoder/decoder/joiner.onnx+tokens.txt → `adb push * /data/data/com.zero.v2/files/models/kws/` → keywords.txt 参照包内格式加"你好大帝"拼音行 → 开开关即监听；**真唤醒/KD12 协同回归/三档实测/G6 ②③（手势/侧键触发）= L3 待用户**；**fix（模型内置 09-15）**：KB3.4 模型资产由开发侧直接落位——GitHub k2-fsa/sherpa-onnx `kws-models` tag 下载 `sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01`（Apache-2.0），epoch-99 int8 三件套（encoder 4.8M/decoder 181K/joiner 65K）重命名标准名入 `app/src/main/assets/models/kws/`（AB-C assets 通道）+ tokens.txt（1.6K）；**keywords.txt 格式破译**（官方样例：`拼音 token 序列（声母+带调韵母成对）@显示名`，token 全覆盖校验通过）→ 默认行 `n ǐ h ǎo d à d ì @你好大帝`（仅此行——官方示例词会成额外误触面已去除）；**开箱即用**：装 APK 开开关即监听（assets 自拷贝通道生效），adb push 直推通道保留（换词/换模型用）；Build ✅ APK 09-15 00:32（147.1MB，+3.6MB）；**fix3（L3 实测 09-15 · 无法唤醒）**：日志根因——`cfg_assistant` 有 config_saved（全时段）但 `assistant_module` 块仅启动时一条 policy_stop、`hotword` 块不存在 = **`onConfigChanged` 全库零调用**（接口死回调，配置改动历来只靠下次启动恢复生效——其他模块无实时诉求从未暴露）；处置：ModuleDetailViewModel.saveNow 成功后接线 `module.onConfigChanged(module.config)`（runCatching+trace，配置系统级补全——惠及全部模块实时生效，空实现模块零影响）；Build ✅ APK 09-15 11:28；**装新 APK（配置已持久化→启动 policy_tick 即拉起）→ 改开关即时启停 → 喊"你好，大帝" = L3 复测**；**fix4（L3 实测 09-15 · AS 模拟器闪退）**：adb crash 栈实证 `HotwordService.onStartCommand:97 startForeground(microphone)` 抛 `SecurityException` → FATAL——**Android 14+ microphone FGS 启动瞬间必须已持 RECORD_AUDIO**（模拟器未授权录音即崩；原权限检查错置于 startForeground 之后 + startForeground 无 catch，顺序缺陷）；处置三处：① companion `start()` 前置权限检查（未授直接 false 不发 start）；② onStartCommand 首行权限守卫（未授→trace+及时 stopSelf 满足 FGS 时限，不触发 did-not-start 超时）；③ `startForeground` runCatching 包裹（GA3/SR18 eligible-state 拒绝→trace 留痕+停服等待合法窗口，不崩主进程）；Build ✅ APK 09-15 11:35；**模拟器复测前置：先授录音权限（adb pm grant 或跑一次 Live 会话）**；**fix5（L3 实测 09-15 · 语音无唤醒动作）**：日志三事实——① `policy_start ok=false` 后授权限（ui_permission 9/9）无补拉：**KD15 合法窗口信号未接线**（fix3 只接保存配置/启动两点，授权完成/回前台无人再 try）；② `hotword service_created` 后零后续 trace：`startCollecting` 的 voicePaused 分支静默 return（**E28 违规实现 bug**——用户先测电源键 Live、onVoiceActive replay=true、服务创建即 KD12 暂停态，属设计行为但无留痕无法分诊）；③ 用户终态 residentMode=关（服务不再起）；处置：HotwordService——voicePaused 分支补 trace `collect_deferred_voice`；AssistantModule——+SystemBus 注入 + `onAppForeground.collect → applyHotwordPolicy("app_foreground")`（**KD15 原文合法窗口"用户打开 app"的接线实现**，主流第三方 ChatGPT onResume 保活同款；非设计变更）；**G6 ② ✅ 电源键手势唤醒 Live 成功（用户确认）**；Build ✅ APK 09-15 11:43；**fix6（L3 实测 09-15 · 服务在跑但喊话无响应）**：fix5 半程生效（policy_start ok=true + collect_started + 通知"正在监听"）但无 wake_word_hit——根因：**服务由 trigger=start（App 冷启动模块 onStart）拉起，启动时无前台性 → Android 14 while-in-use 静默**（后台启动的 microphone FGS read 得静音帧不报错，SR18 预言的 eligible-state 第三形态）；且 app_foreground 首次 emit 早于订阅被错过（replay=0）、对已运行服务重发 onStartCommand 不重建进程、静默无法自愈；处置（KD15 框架内实现细化）：① HotwordService 采集循环+**静默监测**（连续 10s 近零能量→trace `mic_silent_10s` 分诊证据）+ companion `running` 标志；② AssistantModule 编排改**前台热重启**（服务在跑时 stop→delay 600ms→start 重建获取麦克风前台性；30s 节流防 onAppForeground 连发；trace `policy_restart`）；Build ✅ APK 09-15 11:52（自修复 1 处双 companion）；**复测：装 APK → 切前台一次（或关→开开关保存）→ trace 应见 policy_restart/mic 恢复 → 喊话；若见 mic_silent_10s 且仍不响应再议深层**；**fix7（L3 实测 09-15 · 仍无唤醒）**：fix6 静默假设**被证伪**（无 mic_silent_10s=音频有能量）→ 根因收窄 KWS 检测层（阈值/喂流/token 三嫌疑无观测不可锁死）；处置（一次一动可测变量）：① KEYWORDS_THRESHOLD 0.25→**0.12 灵敏化**（误触由 3s 冷却+KD12 承接，G4 调参）；② 采集循环+**audio_stats 快照**（每 10s：累计秒数/平均振幅/峰值/decode 次数——下轮日志直接分诊：avgAmp 低=音源增益问题（换 MIC source）/ decodes=0=喂流层 / 均正常仍不中=token 序列层）；Build ✅ APK 09-15 11:58；**fix8（L3 实测 09-15 · 用户观察"喊话时麦克风指示不亮"）**：判决性证据——**audio_stats 从未出现**（采集跑数分钟 10s 快照必然触发）= `record.read()` 恒 ≤0 在 `continue` 空转（**无健康检查的 E28 盲区**）= AudioRecord 从未获音频通道；修复三处：① `createAudioRecord()` 工厂——**AudioSource VOICE_RECOGNITION→MIC**（前源在本机后台 FGS 场景无通道）+ `STATE_INITIALIZED` 校验（失败 trace+null）；② read 失败健康检查——连续 ~5s 无数据→trace `read_stalled`+一次重建自愈，重建再败放弃本轮（服务保活等下窗口）；③ 变量可空调 `rec!!` 收敛；Build ✅ APK 09-15 12:54（自修复 1 处可空编译错）；**复测注意：检查系统"麦克风后台访问"开关（三星 设置→隐私/应用信息→麦克风）**；**fix9（L3 实测 09-15 · read_stalled n=0 判决）**：**根因 100% 坐实——漏调 `AudioRecord.startRecording()`****（构造成功但录音未启动时 read() 恒返 0 长度，与日志 n=0 完全吻合；`LocalVoiceProvider.kt:256` 先例明确该调用）；fix8 的 MIC 源/初始化校验/read 健康检查全部保留为纵深防御；此前 fix6"静默假设被证伪"的推理链修正（read=0 在能量统计前 continue，mic_silent_10s 本就不会触发——观测缺陷非证据）；修复：主路径与重建路径均 startRecording() + RECORDSTATE_RECORDING 校验；Build ✅ APK 09-15 12:59；**fix10（用户确认唤醒成功 + 麦克风常开问题 09-15）**：✅ **wake_word_hit "你好大帝" → Live 会话起（用户确认：去逗号后成功——wakeWord 配置名与 keywords.txt 显示名一致性 + fix9+热重启共同收案）**；**Gemini 形态落地——亮屏监听/息屏停麦**（用户指令"参考 Gemini 正常设计唤醒系统"：麦克风不再 24h 常开）：HotwordService +SCREEN_ON/OFF 动态 receiver（SCREEN_OFF→stopCollecting 彻底 release AudioRecord+KWS、麦克风指示灯灭、通知"息屏暂停监听"；SCREEN_ON→自动恢复采集；PowerManager.isInteractive 真值初始化；息屏启动守卫 collect_wait_screen；KD12 与屏幕态独立判定；通知四态文案）；AssistantConfig 默认 wakeWord "你好，大帝"→**"你好大帝"**（与 keywords.txt 显示名一致，fromJson 兜底同步）；Build ✅ APK 09-15 13:06（自修复 1 处 ContextCompat.registerReceiver 签本不可用→Service 原生 API33 分支） |

---

## 五、§SR 实现级事实/风险表

| # | 级 | 事实/风险 | 处置 |
|---|---|---|---|
| SR1 | 🟢 | SAF `listTree` 递归 ≤5 层——`knowledge/` 深于 5 层的子目录不被枚举 | 个人知识库深度足够；超深 trace 提示（KB1.3）；文档边界说明 |
| SR2 | 🟠 | SAF 无文件监听——外部改动（文件管理器直放/直删）无实时性 | 周期扫描（indexIntervalMin）+ 启动校验兜底；设计 §一.3-2 已钉死边界 |
| SR3 | 🟢 | DashScope embedding 单请求 ≤10 条、费用 | embedBatch 内部分块（既有实现）；maxDocs/maxChunksPerDoc/hybridEmbedding 三重治理（§七.3） |
| SR4 | 🟡 | embedding profile 可换 → 向量空间漂移 | `embed_model` 落列 + 双校验 + kb_status 换档提示 + kb_reindex 恢复（KA5/KD8，KB2.1） |
| SR5 | 🟠 | RetrievalKit 抽取后 Memory 行为漂移 | C4 钉死"纯搬家"；KB1.2 回归 memory_recall trace path 一致性；rrfFuse 泛型化签名改动在 Memory 调用点适配并注释 |
| SR6 | 🟢 | FC 名 `kb_*` 与既有冲突 | KB1.6 前 grep 全量 FC 名复核（既有无此前缀；音乐 SR13 同款动作） |
| SR7 | 🟢 | KnowledgeModule 双绑定（IZeroModule+AgentJob） | `AppBindingModule.bindMusic/bindMusicJob`（:52-55）实证先例 |
| SR8 | 🟡 | 词法 LIKE 全表扫随 chunk 数线性增长 | 个人库千级 chunk ms 级；maxDocs×maxChunksPerDoc 上限 50w 极端不到；达万级再议倒排/FTS5（KA6 附则）——G 表监控 |
| SR9 | 🟢 | 大文档全读入内存切分 | 个人 md 笔记级（<1MB）安全；maxChunksPerDoc 上限防失控 |
| SR10 | 🟡 | FilesModule emit 改动面（AA2 后 4 FC） | 各 1–2 行 runCatching tryEmit（事件层故障不伤文件操作）；不判断路径/扩展名——过滤归消费方（KB1.5） |
| SR11 | 🟢 | 删 `reflectionModel`/`graphExtractionModel` 字段与存量 JSON/`MemoryV1ToV2` 迁移兼容 | 删字段非 schema 破坏：`fromJson optString` 不读即无害，旧 JSON 多余 key 静默忽略；迁移 put 两行同步移除（C7） |
| SR12 | 🟡 | 信息卡当前模型名可能为空（无启用 CHAT profile） | 降级文案"（未启用对话模型——后台 Reflection/图谱将跳过）"，与 Job skip+trace 语义对齐 |
| SR13 | 🟠 | KB0.3 复用 `ModuleProfilesViewModel` 会互踩——`kind` 为单值 `MutableStateFlow`（LlmProfilesScreen.kt:65-71），同屏 EMBEDDING 管理区与 CHAT 卡后者顶掉前者 | **专用小 VM**（AA5；KB0.3 已细化）；验证含互踩回归点 |
| SR14 | 🟡 | `save_to_file` 重名冲突自动改名——emit 入参 file_name 会指向不存在的路径 | emit 用 `WriteOutcome.finalRelPath` 真实落点（WorkDirRepository:94 实证，AA2/KB1.5） |
| SR15 | 🟠 | KB3.3 前置：sherpa-onnx AAR 是否已在依赖树（KWS 复用前提 = ASR/TTS 即该栈） | KB3.3 动手前 grep 语音模块 `build.gradle.kts`；已在→零新增；缺失→本步显式声明（R5，探索 AB-D） |
| SR16 | 🟡 | `RoleManager.ROLE_ASSISTANT` 需 API 29+（项目 minSdk 26） | KB3.1 分支：≥29 且 isRoleAvailable→requestRole；否则"设为默认助手"入口跳系统设置页手动 |
| SR17 | 🟡 | KWS 模型资产（zipformer 中文，数 MB–数十 MB）路径未定（AB-C） | KB3.4 三选一定案：assets（增 APK 体积）/ 工作目录放置 / 首启下载——倾向对齐既有 sherpa 模型管理 |
| SR18 | 🟠 | **microphone FGS 后台启动限制**（GA3/KD15）：Android 12+ `ForegroundServiceStartNotAllowedException` + Android 14 while-in-use 麦克风无流——POWER 广播直启不可靠 | 启动来源规则（KD15）：前台交互主路径 / VIS 提供方官方豁免 / 后台事件仅 desired state+catch+trace+等合法窗口；residentMode=监听策略非启动保证；电池优化豁免引导为缓解；**后台启动真机行为 KB3.3 实测入档**（非纯 L3 项）；不做保活架构 |

---

## 六、§C 交叉审查冲突裁决表

| # | 级 | 冲突 | 裁决 |
|---|---|---|---|
| **C1** | 🔴P0 | （v1 草稿）"Memory 代管知识库"——MemoryModule 在 framework，`WorkDirRepository` 在 app，X12 禁止 framework→app，**构建失败** | **KA2 已裁**：独立 app 层 `KnowledgeModule`（FilesModule/MusicModule 同列）；底座共享经 EmbeddingProvider（零改动）+ RetrievalKit（services 纯函数）达成 |
| **C2** | 🟠P1 | FilesModule 与 KnowledgeModule 同在 app 层——实时通知若直接互注 = 模块间持引用（违反"模块绝不直接持有引用"） | 经 `SystemBus.onFilesChanged` 解耦（B9 三先例模式；KD8）；emit 方不感知消费方，事件与索引成败解耦 |
| **C3** | 🟢 | RetrievalKit 落哪层（framework 与 app 都要用） | services：framework/memory `build.gradle` 已 `implementation(:services)`（实证）；app→services 合法——两端零 gradle 改动 |
| **C4** | 🟠P1 | MemoryModule 切换 RetrievalKit 若伴随行为变化 = 未授权重构 | 钉死**纯搬家**：函数体原样迁移（rrfFuse 泛型化除外，调用点适配+注释）；KB1.2 同 commit；回归判据=memory_recall trace path/结果不变（SR5） |
| **C5** | 🟡P2 | onFilesChanged 载荷：Unit tick vs 路径列表 | **List<String> 相对路径**——写 FC 天然知道路径，精准过滤免全量扫描；DROP_OLDEST+buffer32 + tryEmit 不阻塞 FC、慢消费下丢旧保新（漏掉的由周期路兜底——最终一致性；EK5 勘误） |
| **C6** | 🟡P2 | move/delete 与索引一致性（move 出子树=应删行；事件只见路径） | 事件仅触发 doIndex，**真相以 listTree 树对比为准**（KD8/§七.1）；move 出 knowledge/ → 树中消失 → 删行；move 进 → 下轮收录——音乐 MD16 同 philosophy |
| **C7** | 🟡P2 | 删两模型字段是否需要 `configVersion` 升版（ConfigMigration 机制在册） | **不升版**：删字段非破坏性变更——`fromJson optString` 不读即无害、旧 JSON 多余 key 静默忽略；升版反而引入无意义迁移链。`MemoryV1ToV2` put 两行移除后 v1 存量仍走 fromJson 默认值路径（SR11）。模型切换即时性由 N2.6c（profile 启停即时刷新 ModelSelector）天然保证 |
| **C8** | 🟡P2 | 实时事件 emit 面：六个写 FC 全挂 vs 只挂可影响 knowledge/ 者 | **四个**（AA2）：`save_to_file`/`move_file`/`delete_work_file`/`delete_folder`——`create_document`/`edit_document` 产物 docx/xlsx/pdf 永非 .md 不索引不 emit（改动面最小化）；emit 不判路径/扩展名，过滤归消费方 |
| **C9** | 🟠P1 | AS 归属：独立轨置后（裁-A 原案）vs 并入 KB 轨末尾 | **裁-5（2026-09-14）已裁定并入**：Phase KB3 承载；探索文档转来源存档（头注声明以 KB 两文档为准）；指南 §八 AS 行改并入标注；原"置后/待展开"表述全清 |
| **C10** | 🟡P2 | HotwordService 与 LiveSessionService 双前台麦克风服务并存策略（Live 起时 Hotword 如何让位） | **停采集保服务**（KD12）：onVoiceActive true→停 AudioRecord/KWS、保前台服务、通知切"会话中暂停监听"；false→恢复——快速恢复免服务重启，通知透明；防 TTS 自唤醒（R7/AB-A） |

---

## 七、§G 外部审查/开放项表

| # | 级 | 开放项 | 处置/验证点 |
|---|---|---|---|
| G1 | 🟡P2 | 嵌入 API 真机链路（经既有 OpenAiCompatibleClient，Key 链 `embedding:qwen`） | KB2.1 L2 真机验；失败降级词法（§九）不阻塞 |
| G2 | 🟠P1 | 混合检索收益是否成立 | 评估集数字判据（§十六.2）：语义类 Recall@5 混合 vs 纯词法提升显著 → P2 价值坐实；不显著 → hybridEmbedding 默认值复议 |
| G3 | 🟡P2 | 首次全量索引耗时/流量（大库） | KB1.3 trace `index_done ms=` 记录；增量三元组保证二次起近零成本 |
| G4 | 🟡P2 | RRF 融合 vs 纯词法排序质量 | 评估集对比记录；K=60 标准参数不调参（Memory 同值） |
| G5 | 🟢 | debounce 2s 是否够（连续 `save_to_file` 多次追加） | 事件合并在 KB 侧，2s 窗口内多事件仅一次 doIndex；实测调整（常数，不入配置） |
| G6 | 🟠P1 | 三星 One UI（外版，裁-D）Assistant 四点实测：①默认助手可选 Zero ②角落手势触发 ③侧键长按可配置性 ④Bixby 占位替换 | KB3.4 执行 R1 矩阵入档；One UI 前景良好（支持第三方助手先例）但四点均 L3 确认 |
| G7 | 🟡P2 | KWS 流式 CPU/耗电实测（帧处理延迟 ms、常驻电量占比） | KB3.4 记录；超标则评估 zipformer-tiny 档模型或仅充电档默认建议 |

---

## 八、§F 终审表（阻塞清零确认 · 2026-09-13 定案时点）

| 项 | 结论 |
|---|---|
| 依赖方向 X12 | ✅ C1 清零——KnowledgeModule 在 app（合法依赖 WorkDirRepository）；RetrievalKit 在 services 双端可达；零 gradle 改动 |
| Room 迁移 X9 | ✅ v18 迁移组同提交（KB1.1）；老库升级 L3 必测 |
| FC 名冲突 | ✅ `kb_` 前缀既有无（KB1.6 前 grep 复核，SR6） |
| chatHistory 纯净 | ✅ KB 事件不进 chatHistory；索引状态走 trace/FC（F8） |
| 不可破坏禁区 | ✅ 不触 N6.6/D19（AgentJob 走 Scheduler 既有轮询非新 tick）/FunctionRegistry/BudgetGuard/D57 |
| 模块归属 | ✅ KA2 独立 app 层模块（v1"代管"依赖冲突已修） |
| 实时性边界 | ✅ KA10 事件链覆盖 Zero FC 写入；外部改动周期兜底（SR2 钉死） |
| Memory 回归风险 | ✅ C4 纯搬家纪律 + 回归判据（SR5） |
| ≤3 文件/Step | ✅ 迁移组例外同提交（KB1.1 四文件）；KB1.4 拆 SystemBus+Impl 与 FilesModule 分步 |
| 记忆配套修订（裁-4） | ✅ KA11 方案三段（删字段/Job 直连/只读卡）——零新依赖、存量 JSON 兼容（C7/SR11）、无可用模型降级语义对齐（SR12）；绑定切换即时性由 N2.6c 既有机制保证 |
| 构建验证 | ✅ 每 Step `_build_apk.bat` |
| 交叉审计（§八b AA 表） | ✅ 2026-09-13 AA1–AA5 全处置：2 实锤已修订（md 写径 AA1 / emit 面 AA2）、3 补强落档（Scheduler 契约 AA3 / doIndex 单飞 AA4 / KB0.3 专用 VM AA5）——修订后无设计阻塞、无落地阻塞 |
| Assistant 化并入（裁-5/Phase KB3） | ✅ 零特权（C9 归属落定）；BAL 免障（Session 系统窗口，AB-E）；自唤醒协同 KD12/C10；依赖核验前置 SR15；API 29 分支 SR16；息屏唤醒明示不承诺（特权墙）——**并入后无落地阻塞** |
| GPT 外审复审（§八c GA 表） | ✅ 2026-09-14 GA1–GA5 全采纳（0 驳回）：目录深度/事件语义/回填单飞三勘误 + KB0.3 同源 + **KB3 microphone FGS 启动约束 KD15**（P1 风险降级为可测约束——SR18 实测入档）——零结构改动 |
| **结论** | **v2.4 封板勘误后：知识库域 + 记忆配套 + Assistant 化三域全链无落地阻塞项、无设计阻塞项，可进 KB0.2**。G1/G2/G6/G7 为真机/数字验证项，SR18 为 KB3.3 实测项，均有降级路径不阻塞编码 |

---

## 八b、§AA 交叉审计表（2026-09-13 · 逐项代码实证 · 修订同步两文档）

> 审计维度：依赖方向/构建可行性、FC 真实能力与签名、Room 迁移细节、Scheduler/AgentJob 契约、并发互斥、UI 落点可行性、文档内部一致性。方法：对设计/计划引用的每个代码事实回仓库核实（音乐轨 M 表同款流程，本轮自审）。

| # | 级 | 审计发现 | 证据 | 裁定与落点 |
|---|---|---|---|---|
| AA1 | 🔴设计 | 文档（v2/v2.1）把 `create_document`/`edit_document` 当 md 写径引用（§一.3/B3/§4.2/§八/§十一 多处） | `FilesModule.kt:143-198` 实证：`create_document`=docx/xlsx/pdf 办公文档、`edit_document`=office 精准修改（不支持 md）；md 唯一写径=`save_to_file(format="md")`（file_name 支持子目录，:203-225） | ✅ 已修订：设计 §一.3/B3/§4.2/§八/§十一（md 单写径 + 修订循环"read_work_file 读→改→save_to_file(append=false) 整篇重写"）；KA12 落档 |
| AA2 | 🟠落地 | KB1.5 emit 面按"六写 FC"设计——两个永不可能影响索引 | 能影响 knowledge/ 的 FC 恰 4 个：`save_to_file`/`move_file`/`delete_work_file`/`delete_folder`；`create_document`/`edit_document` 产物恒非 .md；且 `save_to_file` 重名冲突自动改名——须 emit `WriteOutcome.finalRelPath`（`WorkDirRepository.kt:94`）而非入参 | ✅ 已修订：设计 §七.1/§十 + KB1.5 重写（4 FC + finalRelPath）；C8/SR14 落档 |
| AA3 | 🟢落档 | AgentJob 周期实现契约未写（var 还是 getter、首跑时机） | `Scheduler.kt:78-91`：onTick 遍历 `nextRunTime <= now → execute()`——每 tick 读属性，var/getter 均合法 | ✅ 落档 KB1.3/KD10：`var nextRunTime` 初值 MAX、onStart 置 now（首 tick 即索引）、execute 尾 `now+interval`——D19 合规（消费 onTick 经 Scheduler 即法定路径） |
| AA4 | 🟠设计 | doIndex 四路（事件/启动/周期/kb_reindex）并发无互斥——重入=重复切分重复嵌入 | 事件 collect 与 AgentJob execute 分属两个协程上下文 | ✅ 已修订：`indexMutex` 过程级单飞（设计 §七.1/KD10/KB1.3——DB 写 Mutex 之外补过程锁） |
| AA5 | 🟡落地 | KB0.3 若复用 `ModuleProfilesViewModel` 取 CHAT 当前名会与同屏 EMBEDDING 管理区互踩 | `LlmProfilesScreen.kt:65-71`：`kind` 单值 `MutableStateFlow`，`setKind(CHAT)` 顶掉 `ModuleProfilesSection(EMBEDDING)` 的过滤 | ✅ 已修订：专用小 VM + 卡片同文件（KB0.3 细化）；`getEnabled` API 实证存在（`RemoteEmbeddingProvider.kt:30`）；SR13 落档 |

**AA 表后：2 实锤修订完毕、3 补强落档——设计 v2.2 与本计划同步，无设计阻塞、无落地阻塞，可进 KB0.2。**

---

## 八c、§GA GPT 外审复审裁定表（2026-09-14 · 逐项对照仓库代码与 Android 官方文档核实）

> 外审结论"0 P0 / 1 P1（KB3 microphone FGS）/ 2 P2 勘误 / 若干优化"——逐项核实后裁定。**5 项全采纳、0 驳回；零结构改动，全部为封板级勘误/约束补强。**

| # | 级 | GPT 主张 | 核实 | 裁定与落点 |
|---|---|---|---|---|
| GA1 | 🟡勘误 | 设计 §1.2"任意深度子目录"与 B4/SR1（listTree ≤5 层）、KB1.3 验证（>5 层不索引）矛盾——目标范围宽于底层能力 | **成立**——三处表述并存，设计目标句确为笔误级不一致 | ✅ 已修订设计 §1.2：改为"子目录深度受 listTree 当前 ≤5 层递归上限约束，超深不枚举并 trace 提示" |
| GA2 | 🟡勘误 | KB1.6"仅对该路径 doIndex"易被实现成 path 级短路，与"事件=提示树=真相"（KD8/C6）张力；建议第一版全量树对账（三元组跳过成本已低） | **成立**——步骤文字与设计原则存在实现歧义 | ✅ 已修订设计 §七.1 + KB1.6：事件触发 doIndex(trigger="event") **全量树对账**，事件路径仅 trace 提示不做 path 级短路 |
| GA3 | 🟠P1 | microphone FGS 后台启动限制：Android 12+ 后台 FGS 启动 `ForegroundServiceStartNotAllowedException`；Android 14 microphone FGS while-in-use 限制（后台启动拿不到麦克风流）；POWER 广播→startForegroundService 不可假设可靠；VIS 提供方属官方豁免 | **成立**——[官方 restrictions-bg-start](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) 明确，豁免清单含 VoiceInteractionService 提供方 | ✅ 已修订设计 §18.2 + KB3.3 + SR18 + **KD15 启动来源规则**：前台交互主路径 / VIS 豁免 / 后台事件仅 desired state+catch+trace+等合法窗口；residentMode=监听策略非启动保证；**不做保活架构**；后台启动真机行为 KB3.3 实测入档（降级为可测约束，非纯 L3 项） |
| GA4 | 🟡勘误 | KB0.3"filter CHAT && enabled 取首个"与 Job 实际 `select(CHAT)` 可能不一致（两套"当前模型"定义） | **成立**——`ModelSelector.select` 按能力 **jaccard 择优**（ModelSelector.kt:47-54 实证）而非首个，多 CHAT profile 且能力集不同时结果相异 | ✅ 已修订设计 §十六.0 方案 3 + KB0.3：UI 数据源**与 Job 同源**（注入 ModelSelector，`select(CHAT)` 结果的 modelId，collect profile 变化重算）；SR13 处置列同步 |
| GA5 | 🟡勘误 | embedding 回填无单飞约束——doIndex 尾部 launch 会多开 worker，并发消费 `getWithoutEmbedding` → 重复 embed 重复计费 | **成立**——KB2.1 原文即"doIndex 尾部 launch"模式；连续写入+周期+kb_reindex 并发场景真实 | ✅ 已修订设计 §七.3 + KB2.1 + **KD14**：全局唯一 backfill worker（`isActive` 守卫，新触发忽略） |

**GA 表后：5 项全采纳（0 驳回）——v2.4 封板勘误完毕，零结构改动，可进 KB0.2。**

---

## 九、§E 经验教训表（预登）

| # | 教训 | 触发点 |
|---|---|---|
| EK1 | 模块归属由依赖方向决定，不由概念亲疏决定——"Memory 代管知识库"概念顺但 framework↔app 结构性不可行 | C1/KA2 |
| EK2 | "共用底座"的物理落点是接口/纯函数抽取（EmbeddingProvider/RetrievalKit），不是模块挂靠 | KA2/KD2 |
| EK3 | 凡持久化向量必落 embed_model——可换 profile 的嵌入服务必经历换档空间断裂 | KA5 |
| EK4 | 事件载荷带定位信息（路径列表）优于 Unit tick——消费方免全量扫描；但真相永远以对树/对表结果为准 | C5/C6 |
| EK5 | **SharedFlow 无 EXTRA_BUFFERED 溢出策略**（该常量属 Channel API）——设计文档写错，实现时发现按 R7 勘误为 DROP_OLDEST+buffer32（满丢旧保新，新事件对账覆盖旧事件语义一致）；教训：跨 API 概念（Channel vs SharedFlow）勿凭印象写配置 | KD8/C5（KB1.4 实现期发现） |

---

## 十、防漂移锚点（每会话自检）

- **决策封板**：KA1–KA12 + KD1–KD10（§十三/十四）为本轨基线；"更好方案"仅 backlog 提案。
- **范围红线**：`knowledge/` 子树 `.md` only、目录名编译期常量（KA9）——"顺手把 llmmusic 或根目录 md 也收进来"即漂移。
- **内容零写径**：KB 不提供任何写内容 FC（KA7）——出现 `kb_write/kb_note` 即漂移。
- **md 单写径**：知识 md 唯一写径 = `save_to_file(format="md")`（KA12/AA1）——把 `create_document`/`edit_document` 当 md 写径引用即漂移；md 修订 = 读→改→整篇重写循环，不加精准编辑 FC。
- **不做 Auto-Retrieval**：检索时机归 Agent（KA7）——"每轮自动注入知识片段"即漂移。
- **生命周期分立**：KB 表永不衰减/归档/合并（KA1）——把 Memory 演化逻辑套上知识表即漂移。
- **纯搬家纪律**：RetrievalKit 抽取不改 Memory 行为（C4）。
- **迁移同提交**：v18 组同 commit（X9）。
- **事件=提示，树=真相**：onFilesChanged 只触发索引，一致性以 listTree 对比为准（C5/C6）。
- **降级链**：向量→词法→引导，永不哑火（§九）。
- **记忆后台模型绑定**：Reflection/图谱提取/事实提炼一律跟随当前对话模型 `select(CHAT)`（KD9/KA11）——重新出现每任务模型 DROPDOWN 或硬编码模型名单即漂移。
- **Assistant 边界（KD11/裁-5）**：**息屏唤醒不设计不承诺**（特权墙，平台约束）——任何"息屏可唤醒"表述即漂移；唤醒词入 ConfigField(TEXT) 默认"你好，大帝"（裁-B）——硬编码唤醒词即漂移。
- **Hotword 协同（KD12/C10）**：Live 会话期间停采集保服务——"双服务同时采集"或"直接 stopService"即漂移。
- **Hotword 启动来源（KD15/GA3）**：前台交互主路径 / VIS 豁免 / 后台事件仅 desired state+catch+trace 等合法窗口——"POWER 广播直接保证拉起 microphone FGS"或"加 WorkManager/保活架构"即漂移；residentMode 是监听策略不是启动保证。
- **回填单飞（KD14/GA5）**：全局唯一 embedding backfill worker——"doIndex 尾部无条件 launch 新 worker"即漂移。

---

## 十一、相关文档

- 设计：`ZeroV2-知识库设计.md`（v2.3 并入增补稿——含 §十八 Assistant 化）
- 评估集：`ZeroV2-知识库评估集.md`（KB0.4 产出）
- Assistant 探索存档：`ZeroV2-Assistant化与语音唤醒探索.md`（§十八来源底稿——市场链接/AB 审计细节/裁定 A–D）
- 开发指南：`ZeroV2-LLM开发指南.md`
- 真相源：`ARCHITECTURE_FINAL.md`（根）
- 标杆：`MusicModule`（三重身份+三元组增量+枚举守卫）/`MemoryModule`（混合检索+回填）/`MIGRATION_15_16`（双表）/`SystemBus.onMusicDue`（加流先例）/`AppBindingModule:52-55`（双绑定）/`ModuleProfilesSection`（LlmProfilesScreen.kt·模块自治模型管理区 S1.1/D115）/`LlmProfileStore.getEnabled`（RemoteEmbeddingProvider 同款取用先例）
