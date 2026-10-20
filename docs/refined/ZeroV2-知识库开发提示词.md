# Claude Code 知识库模块开发提示词（KB 轨）

> 用途：跨会话流畅执行 `ZeroV2-知识库设计.md`（v2.4 · KA1–KA12/KD1–KD15 · 含 §十八 Assistant 化）与 `ZeroV2-知识库落地实施计划.md`（SR/C/G/F/AA/GA 六层验证 · KB0.2–KB3.4 共 14 Step）。
> 核心原则：**设计决策不变**（KA/KD 已封板）、**不破坏已有功能与基线**、**只做授权修改**。
> 记录日期：2026-09-13 · 基于 v2.2 交叉审计修订版；09-14 v2.3 并入增补（裁-5）+ v2.4 GPT 外审封板勘误（GA1–GA5 全采纳，落地计划 §八c）；沿用音乐轨提示词结构。

---

## 版本 A：完整启动（首次会话 / 中断后恢复）

```
你正在开发 Zero V2 知识库模块（KB 轨），为 Android AI Agent 应用 Zero 增设本地知识库
（knowledge/ 子树 md 索引 + 片段检索 + 实时更新）能力，并先期完成记忆模块后台模型绑定
修订（用户裁定裁-4）。项目位于 D:\ZeroV2。

## 第一步：加载上下文（只读，不修改任何文件）

请依次读取以下文件，了解本轨全貌：

1. `D:\ZeroV2\docs\refined\ZeroV2-知识库落地实施计划.md` — 实施计划与进度追踪
   （§四进度表 = 当前位置权威；§八b AA 审计表 = 交叉审计修订记录）
2. `D:\ZeroV2\docs\refined\ZeroV2-知识库设计.md` — 设计决策（KA1–KA12/KD1–KD10 ·
   §五实体/§六切分嵌入/§七索引管线/§八FC/§九检索/§十六.0 记忆配套修订）
3. `D:\ZeroV2\docs\refined\ZeroV2-知识库开发提示词.md` — 本提示词（行为边界）
4. `D:\ZeroV2\docs\refined\ZeroV2-LLM开发指南.md` — 跨轨通用规范（R1–R8 + 地雷清单 + 安全红线）

根据落地计划 §四进度表中第一个 ⏳ 状态的 Step，精读该 Step 的完整定义
（文件清单/验证标准/关联 KA-KD-AA 锚点）。

## 第二步：执行当前 Step

严格按落地计划中该 Step 的定义执行：
- 只创建/修改该 Step 明确列出的文件（Android ≤3 文件/Step；Room 迁移组 4 文件同 commit 例外）
- 不改动 Step 范围外的任何文件与功能
- 每个 Step 自带验证标准——逐条对照，不达标的验证项如实标注"待实测/待用户"

## 修改权限边界（硬性，违反即回滚）

只允许三类修改：
1. 实现已批准设计（知识库设计 KA1–KA12/KD1–KD10 及落地计划对应 Step，含 §十六.0 记忆配套修订）
2. 修复落地计划 SR/C/G/AA 验证表明确列出的问题
3. 修复实现过程中发现的真实 bug（有错误日志/复现证据）

不允许三类修改：
1. 未授权架构重构（模块边界/通信机制/数据层的结构性变更）
2. 将已裁定的方案重新设计（KA/KD 决策——如：独立 app 层模块归属、knowledge/ 子树常量、
   md 单写径、检索降级链、事件=提示树=真相、doIndex 单飞、RetrievalKit 纯搬家、
   记忆后台模型跟随当前对话模型）
3. 因"实现更简单"而修改模块边界、通信机制或 UI 基线（三 Tab 固定、chatHistory 纯净等）

遇到边界拿不准时：记录问题 + 向用户提问，不自作主张。

## 行为规则

1. **每次只做一个 Step**。完成后停下，构建验证，等用户确认再推进。
2. **Android ≤3 文件/Step**（Room 迁移组例外：Entity+Dao+DB版本+Migration 同 commit，X9）。
3. **构建验证**：代码改动后运行 `cmd //c "D:\ZeroV2\_build_apk.bat"`，验 APK 时间戳更新；
   构建失败读编译输出、自诊断、自修复后重试（不得带着失败标记完成）。
4. **先读后写**。修改现有文件前必须 Read 确认当前内容；FilesModule/WorkDirRepository/
   MemoryModule/SystemBus*/AppBindingModule 改动前精读相关段。
5. **参考标杆**：三重身份模块 → MusicModule；混合检索/词法降级/embedding 回填 →
   MemoryModule（recall/lexicalCandidates/startEmbeddingBackfill）；Room+迁移 →
   MIGRATION_15_16/MIGRATION_16_17；SystemBus 加流 → onVoiceActive/onMusicDue；
   双绑定 → AppBindingModule:52-55（bindMusic+bindMusicJob）；模型管理区 →
   ModuleProfilesSection（LlmProfilesScreen.kt）。
6. **埋 AgentTrace**：每个关键集成点 record 一句；静默失败路径必须留 trace（E28 铁律）。
7. **onAwake 必须自调 registerFunctions()**（ModuleLifecycleManager 不集中调——S4.2-fix 实测教训）。
8. **真实偏差入档**：发现设计与现实不符，先实测取证，记入 §E 教训表并同步两文档，再继续——
   不静默偏航。

## 第三步：输出标准格式

### Step KBx.y 完成：[名称]

**改动文件**：
- 新增: path/to/File（N 行）
- 修改: path/to/File（变化简述）

**验证结果**：
- [x] Build 通过 / APK 时间戳
- [x] 该 Step 验证项（逐条，实测数据）
- [ ] 待用户 L3 实测项（如有，写明操作）

**执行偏差**（如有）：与计划的差异 + 原因 + 已入档位置

## 第四步：更新状态

修改 `ZeroV2-知识库落地实施计划.md` §四进度表：
- 该 Step ⏳→✅ + 验证摘要
- 发现的新问题 → 追加到对应验证表（新编号）
- 用 git commit 留痕（信息含 Step 号与关键结论，尾行 Co-Authored-By: Claude Code）

---

不确定的实现选择：先查设计文档对应 KA/KD 决策 → 再查落地计划 SR/C/G/AA 表 → 都没有 → 向我提问。
```

---

## 版本 B：精简步进（连续开发 · 与版本 D 二选一）

```
继续 Zero V2 知识库模块（KB 轨）开发。项目在 D:\ZeroV2。

1. 读 `docs/refined/ZeroV2-知识库落地实施计划.md` §四进度表确认当前 Step（首个 ⏳）
2. 读该 Step 定义 + 关联设计决策（ZeroV2-知识库设计.md 对应节 + KA/KD/AA 锚点）
3. 执行（Android ≤3 文件/Step；迁移组 4 文件同 commit；构建验证 _build_apk.bat + 自修复）
4. 输出标准格式（改动文件/验证结果/执行偏差）
5. 更新进度表 + git commit

行为约束（详版见 ZeroV2-知识库开发提示词.md）：
- 只做三类授权修改（实现已批准设计/修复表中列出问题/修复真实 bug）
- 不做三类禁止修改（未授权重构/重设计 KA-KD 封板决策/因简化改边界与基线）
- 先读后写；标杆对齐；关键路径埋 trace；静默失败必留痕（E28）
- onAwake 自调 registerFunctions；DB 写 Mutex+@Transaction；迁移同 commit（X9）
- 知识库轨红线：knowledge/ 子树 .md only 目录常量 / md 单写径 save_to_file /
  内容零写径不做 Auto-Retrieval / 表分立永不衰减 / 事件=提示树=真相 /
  doIndex 单飞 indexMutex / RetrievalKit 纯搬家 / 记忆后台模型跟随当前对话模型 /
  Assistant 化（Phase KB3）：息屏唤醒不承诺 / 唤醒词入配置默认"你好，大帝" /
  Hotword 与 Live 并存=停采集保服务
- 不确定时提问，不自作主张
```

---

## 版本 C：修复模式（Build 失败 / 真机反馈后）

```
Zero V2 知识库模块测试反馈。项目在 D:\ZeroV2。

信息：
[粘贴完整编译错误 / 运行日志 / Zero agent 实测反馈 / kb_search 检索样例]

请读取相关文件，分析根因，只做最小必要修复（授权三类之三：真实 bug）。
若根因指向设计层问题（非实现 bug），停下来向我报告，不自行改设计。
修复后构建验证（_build_apk.bat）+ 更新进度表 + commit。
若涉检索质量：对照评估集（ZeroV2-知识库评估集.md）跑 Recall@5/10 记录数字，不凭感觉判断。
```

---

## 版本 D：逐步提示词（每 Step 一段，直接粘贴执行）

> 每段自带该 Step 的文件边界/设计锚点/验证标准——用于精确驱动单步；
> 连续开发用版本 B 亦可（进度表自动定位）。**顺序执行，不跳步**：
> KB1.1 FAIL 不进 KB1.2；KB1.2 的 memory_recall 回归 FAIL 必须先修复再继续（C4 纪律）；
> KB3.3 动手前先做 SR15 依赖核验（sherpa-onnx AAR 不在依赖树→本步显式声明，R5）。

### Step KB0.2 — 记忆模型绑定（前置 · 3 文件）

```
执行 Zero V2 知识库轨 Step KB0.2（记忆模块后台模型绑定，用户裁定裁-4/KA11/KD9）。
项目在 D:\ZeroV2。先读设计 §十六.0 + MemoryModuleConfig.kt/MemoryReflectionJob.kt/
GraphExtractionJob.kt 现状，再动手。

文件（3 文件）：
1. framework/memory/.../MemoryModuleConfig.kt — 删 reflectionModel/graphExtractionModel
   两 DROPDOWN 字段（连同 @ConfigField 注解）+ toJson/fromJson 对应行 +
   MemoryV1ToV2 迁移 put 两行。configVersion 保持 2（C7：删字段对存量 JSON 无破坏，
   不升版）。
2. framework/memory/.../MemoryReflectionJob.kt — distillPendingAi() 的模型解析
   `getById(cfg.reflectionModel) ?: select(CHAT)` → 直连
   `modelSelector.select(setOf(ModelCapability.CHAT))`；注释记 KA11 裁定
   （模型名不硬编码，跟随当前对话模型）；无可用模型 → 既有 return 0 + 缓冲保留语义不变。
3. framework/memory/.../GraphExtractionJob.kt — 同款直连；无可用模型 → 既有
   skipped no_model + 计数保留语义不变。

验证：Build ✅ APK 时间戳；AutoForm 参数区不再显示两模型 DROPDOWN；存量配置 JSON
（含旧 key）加载不崩。R8 输出 + 进度表 + commit。
```

### Step KB0.3 — 记忆详情页后台模型信息卡（前置 · 2 文件）

```
执行 Zero V2 知识库轨 Step KB0.3（记忆详情页"后台任务模型"只读卡，设计 §十六.0 方案 3 + AA5）。
项目在 D:\ZeroV2。先读 LlmProfilesScreen.kt（ModuleProfilesSection/ModuleProfilesViewModel）
+ ModuleDetailScreen.kt memory 分支现状，再动手。

文件（2 文件）：
1. app/.../ui/settings/LlmProfilesScreen.kt — 同文件加：
   a) 专用小 ViewModel（@HiltViewModel，注入 ModelSelector + LlmProfileStore；暴露
      currentChatName: StateFlow<String?>——数据源**与 Job 同源**：select(setOf(CHAT))
      实际结果的 modelId（GA4：勿自造 filter enabled+first——select 按 jaccard 择优
      非首个，两套逻辑多 CHAT 并存时不一致），collect profiles 变化重算；空 → null）。
      ⚠️ 不复用 ModuleProfilesViewModel——其 setKind 单值态会与同屏 EMBEDDING
      管理区互踩（AA5/SR13）。
   b) 只读卡 Composable "⚙️ 后台任务模型"：Reflection · 图谱提取 · 事实提炼 →
      跟随当前对话模型（{当前名}；null 时降级文案"未启用对话模型——后台将跳过"，SR12）。
2. app/.../ui/module/ModuleDetailScreen.kt — memory 分支（ModuleProfilesSection 调用旁）
   挂载该卡；其他模块分支零改动。

验证：Build ✅；记忆模块详情页卡与当前启用 CHAT 模型名一致；同屏"模型管理（向量嵌入）"
区过滤不受影响（AA5 互踩回归点）；其他模块详情页不受影响。
R8 输出 + 进度表 + commit。
```

### Step KB0.4 — 评估集建档（纯文档）

```
执行 Zero V2 知识库轨 Step KB0.4（评估集建档，裁-3）。项目在 D:\ZeroV2。

文件（1 文件，文档）：新建 docs/refined/ZeroV2-知识库评估集.md——
- 20 问 → 期望命中片段（术语类：专有名词/药名/编号/架构术语；语义类：换说法提问——各 10）
- Recall@5 / Recall@10 记录表（KB1.3 后跑 P1 词法基线；KB2.1 后跑 P2 混合对比，G2 判据）
- 跑法说明（问题 → kb_search → 人工比对期望片段命中与否）
- 判据：术语类达标而语义类掉队 → P2 必要性成立；反之 P1 足够

无代码改动，无需构建。R8 输出 + 进度表 + commit。
```

### Step KB1.1 — 存储层 v18（⚠️ X9 迁移组 · 4 文件同 commit）

```
执行 Zero V2 知识库轨 Step KB1.1（Room v18 迁移组）。项目在 D:\ZeroV2。
先读落地计划 KB1.1 定义 + 设计 §五，再动手。

文件（迁移组例外，4 文件同 commit）：
1. 新 core/data/.../entity/KnowledgeEntity.kt — KbDocEntity（表 kb_docs）：
   id TEXT PK | relative_path TEXT NOT NULL（UNIQUE 索引，相对 knowledge/ 根）|
   title TEXT NOT NULL DEFAULT '' | mtime/size INTEGER NOT NULL |
   chunk_count INTEGER NOT NULL DEFAULT 0 | indexed_at INTEGER NOT NULL |
   tags TEXT NOT NULL DEFAULT ''
   + KbChunkEntity（表 kb_chunks）：id TEXT PK | doc_id TEXT NOT NULL
   （FK→kb_docs.id CASCADE + 索引）| seq INTEGER NOT NULL | heading_path TEXT NOT NULL
   DEFAULT '' | text TEXT NOT NULL | char_len INTEGER NOT NULL | embedding BLOB 可空 |
   embed_model TEXT NOT NULL DEFAULT ''（KA5）| updated_at INTEGER NOT NULL
   NOT NULL DEFAULT 与 @ColumnInfo(defaultValue=) 严格对齐。
2. 新 core/data/.../dao/KbDao.kt — 设计 §5.3 全方法一次到位，全 suspend：docs
   查-留-id-更新（getByPath 命中→updateDocIndexed 保留 id；禁裸 @Insert(REPLACE)——B8）
   / chunks deleteChunksByDoc + insertChunks(@Transaction 批量) + searchByText(LIKE) +
   getWithEmbedding + updateEmbedding(id,blob,model) + getWithoutEmbedding + 计数
3. ZeroDatabase — version 17→18 + entities + abstract fun kbDao()
4. DatabaseModule — 私有 MIGRATION_17_18（CREATE 2 表 + UNIQUE(relative_path) 索引 +
   doc_id 索引，仿 MIGRATION_15_16）+ provideKbDao + addMigrations 注册

验证：Build 通过 APK 时间戳更新；老库升级不抹数据（X9 L3 必测——交装机实测）。
R8 输出 + 进度表 + commit（迁移组同 commit 铁律）。
```

### Step KB1.2 — RetrievalKit 抽取 + Memory 切换（2 文件）

```
执行 Zero V2 知识库轨 Step KB1.2（检索底座抽取）。项目在 D:\ZeroV2。
先读 MemoryModule.kt 的 cosineSimilarity/rrfFuse/encodeEmbedding/decodeEmbedding/
词法 bigram 段，再动手。

文件（2 文件）：
1. 新 services/src/main/java/com/zero/v2/services/retrieval/RetrievalKit.kt —
   无状态纯函数：cosineSimilarity / rrfFuse（泛型化：按 key 计分，K=60）/
   encodeEmbedding（FloatArray→BLOB 小端）/ decodeEmbedding / queryBigrams（查询侧
   bigram 滑窗）。函数体自 MemoryModule 搬家非重写（C4 纯搬家纪律）。
2. framework/memory/.../MemoryModule.kt — 删私有实现改调 RetrievalKit（rrfFuse 泛型化
   的调用点适配 + 注释）；行为零变化。

⚠️ 零 gradle 改动（framework/memory 已 implementation(:services)，R5/C3）。
验证：Build 通过；memory_recall 回归——trace path=hybrid/lexical 与切换前一致，
检索结果不变（SR5/C4——FAIL 必须先修复再继续）。
R8 输出 + 进度表 + commit。
```

### Step KB1.3 — KnowledgeModule 骨架 + 切分器 + 索引引擎（2 文件）

```
执行 Zero V2 知识库轨 Step KB1.3（KnowledgeModule 核心）。项目在 D:\ZeroV2。
先读设计 §四/§六/§七 + 标杆 MusicModule（三重身份/三元组扫描/枚举守卫），再动手。

文件（2 文件）：
1. 新 app/src/main/java/com/zero/v2/app/knowledge/KnowledgeModule.kt — @Singleton：
   - KnowledgePaths.SUBDIR = "knowledge"（编译期常量，KD7/KA9——不进配置）
   - KnowledgeConfig 5 字段（设计 §十二：autoIndexOnStart/indexIntervalMin 10-1440/
     hybridEmbedding/maxDocs 50-5000/maxChunksPerDoc 10-2000；数值显式 min/max+coerceIn）
   - IZeroModule（id="knowledge"，SYSTEM，launchOrder=130）+ AgentJob（id="kb_index"；
     AA3 契约：override var nextRunTime 初值 MAX、onStart 置 now、execute 尾
     now+intervalMin——勿用恒 future 的 getter）
   - indexMutex 过程级单飞（AA4/KD10）：doIndex 全程持锁
   - markdown 切分器（§六.1）：^#{1,6} 分节→heading_path 祖先链→空行段落聚合 300-600 字→
     超长按句末标点硬切→代码围栏不切→无标题整篇一块→maxChunksPerDoc 停止+trace→
     title=首个 # 行缺省基名
   - doIndex(trigger)（§七.2）：createFolder 引导子树（幂等）→ listTree 过滤
     knowledge/ 子树 .md → 三元组 (path,size,mtime) 比对：一致跳过 / 新·变→readText
     读文→切分→查-留-id-更新 upsert + deleteChunksByDoc + insertChunks(@Transaction，
     Mutex) / 库有树无→删行；枚举守卫（零命中且库存非空→跳过删除+trace）；
     maxDocs 超限拒新+trace；index_done trace 统计
   - onAwake 自调 registerFunctions()（KB1.6 前 FC 空）; onStart 后台 autoIndexOnStart
2. app/.../di/AppBindingModule.kt — @Binds @IntoSet bindKnowledge + bindKnowledgeJob
   （双绑定，AppBindingModule:52-55 先例）

验证：knowledge/ 放 3 篇 md（多级标题长文/无标题短文/子目录各一）→ 启动触发 →
trace index_done 统计正确；heading_path 正确；改 1 篇仅该文档块替换；删文件行删。
Build + R8 输出 + 进度表 + commit。
```

### Step KB1.4 — SystemBus.onFilesChanged（2 文件）

```
执行 Zero V2 知识库轨 Step KB1.4（实时事件总线）。项目在 D:\ZeroV2。
先读 SystemBus.kt/SystemBusImpl.kt 的 onVoiceActive/onMusicDue 先例，再动手。

文件（2 文件）：
1. core/bus/.../SystemBus.kt — +onFilesChanged: MutableSharedFlow<List<String>>
   （DROP_OLDEST + buffer 32——EK5 勘误：原 EXTRA_BUFFERED 系 Channel 概念误植，SharedFlow 无此策略；List<String>=变更的工作目录相对路径，C5——勿改 Unit）
2. core/bus/.../SystemBusImpl.kt — 同步实现

验证：Build 通过。R8 输出 + 进度表 + commit。
```

### Step KB1.5 — FilesModule 四 FC 尾部 emit（1 文件）

```
执行 Zero V2 知识库轨 Step KB1.5（写后事件 emit）。项目在 D:\ZeroV2。
先读 FilesModule.kt 四个 FC handler 现状（save_to_file/move_file/delete_work_file/
delete_folder 的实现函数），再动手。

文件（1 文件）：app/.../data/FilesModule.kt — 四个可影响 knowledge/ 的 FC 完成路径尾部
各 +1-2 行 runCatching { systemBus.onFilesChanged.tryEmit(listOf(...)) }：
- save_to_file → emit WriteOutcome.finalRelPath 真实落点（SR14——非入参 file_name，
  重名冲突自动改名场景）
- move_file → emit 新旧两路径
- delete_work_file / delete_folder → emit 目标路径
⚠️ 不判断是否 knowledge/ 路径、不判断扩展名（过滤归消费方）；create_document/
edit_document 产物恒非 .md 不 emit（AA2/C8）；事件层故障不得影响文件操作本身。

验证：Build 通过；save_to_file 写文件 trace 见 emit 且路径=finalRelPath（构造重名冲突
场景验真实落点）；move/delete 同验。
R8 输出 + 进度表 + commit。
```

### Step KB1.6 — KB 订阅 + FC×3（1 文件）

```
执行 Zero V2 知识库轨 Step KB1.6（实时订阅 + 检索 FC）。项目在 D:\ZeroV2。
先读设计 §八 FC 表/§九检索管线 + KB1.3 已有 doIndex，再动手。

文件（1 文件）：KnowledgeModule.kt —
1. onStart collect systemBus.onFilesChanged → debounce 2s（常数）→ 过滤 knowledge/
   前缀 → 触发 doIndex(trigger="event") **全量树对账**（GA2：事件路径仅 trace 提示，
   不做 path 级短路——三元组跳过未变更者成本已低；事件=提示，真相以树对比为准，KD8）
2. registerFunctions 三 FC（FC 名一经发布稳定，KB1.6 前 grep 全量 FC 名复核 kb_ 前缀
   无冲突，SR6）：
   - kb_search(AUTO)：P1 纯词法——query 分词(≥2字) LIKE ∥ 查询 bigram LIKE（RetrievalKit.
     queryBigrams）→ 命中计数排序 + path_prefix/tag 过滤；返回 "[相对路径] heading ▸ 片段" +
     尾注"全文可 read_work_file(knowledge/<路径>)"；空库引导"用 save_to_file(file_name=
     \"knowledge/主题.md\", format=\"md\") 写入后自动索引"；无命中→"无命中+最近索引文档 N 篇"
     ——description 写明与 memory_recall 分工（记忆=用户的事/知识库=沉淀文档）
   - kb_status(AUTO)：文档数/块数/上次索引时间（嵌入位 KB2.1 增强占位）
   - kb_reindex(AUTO)：后台全量或单文档重切分（indexMutex 内），立即返回受理
3. 未配工作目录三 FC 返回引导文本（B11）不崩。

验证：save_to_file(file_name="knowledge/x.md", format="md") 写入 → 2-3s 内 trace
index_done trigger=event；对话 kb_search 命中片段附来源路径；评估集术语类 Recall@5
首轮基线入档。Build + R8 输出 + 进度表 + commit；P1 收官。
```

### Step KB2.1 — 嵌入回填 + 双路 RRF（1 文件）

```
执行 Zero V2 知识库轨 Step KB2.1（向量混合检索）。项目在 D:\ZeroV2。
先读设计 §六.2/§六.3/§七.3/§九 + MemoryModule.startEmbeddingBackfill 回填模式，再动手。

文件（1 文件）：KnowledgeModule.kt —
1. 嵌入回填：**全局唯一 backfill worker（KD14/GA5 单飞**——backfillJob?.isActive==true
   则新触发直接忽略，禁多 worker 并发消费 getWithoutEmbedding 重复计费）：worker 循环
   getWithoutEmbedding → 批（≤10）embedBatch（嵌入文本=
   "《title》 heading_path ▸ text".take(1000)，KA4）→ updateEmbedding(id,blob,modelId)
   ——整批失败停留下轮续；hybridEmbedding 关或 isAvailable()==false → 跳过+trace（E28）
2. kb_search 升级双路 RRF：向量路 embed(query)→getWithEmbedding 维度+embed_model 双校验
   （≠provider.modelId 只走词法，KA5）→余弦；RetrievalKit.rrfFuse(K=60) 融合；
   降级链向量→词法→引导（永不哑火）
3. kb_status 增强：已嵌入块数/当前嵌入模型与可用性/换档检测提示（多数块模型≠当前 →
   建议 kb_reindex）

验证：有 Key 环境——新文档块数=已嵌入块数收敛；trace path=hybrid；换 embedding profile
→ kb_status 提示换档 → kb_reindex 恢复混合；无 Key——path=lexical 恒可用；
评估集语义类 Recall@5 对比 P1 基线（G2 判据入档）。
Build + R8 输出 + 进度表 + commit；P2 向量混合收官（Assistant 化 Phase KB3 继续）。
```

### Step KB3.1 — Assistant 角色壳（3 文件 · 裁-5 并入）

```
执行 Zero V2 知识库轨 Step KB3.1（Assistant 角色壳，设计 §十八/裁-5）。
项目在 D:\ZeroV2。先读设计 §18.1/§18.2 + 标杆 LiveSessionService（companion start），
再动手。

文件（3 文件）：
1. 新 app/.../assistant/ZeroAssistantService.kt — 三组件同文件：
   a) ZeroAssistantService(VoiceInteractionService)：onPrepareSession 回调支持/isActive
   b) ZeroAssistantSessionService(VoiceInteractionSessionService)：newSession → Session
   c) ZeroAssistantSession(VoiceInteractionSession)：onShow → LiveSessionService.start
      （语音模式——单入口；Session 系统窗口免 BAL，AB-E；R6：会话已在跑则聚焦不重启）
2. AndroidManifest.xml — 两 service 声明：exported=true +
   android:permission="android.permission.BIND_VOICE_INTERACTION"（组件属性约束绑定方，
   app 零特权）+ intent-filter android.service.voice.VoiceInteractionService /
   VoiceInteractionSessionService
3. app/.../ui/settings/SettingsScreen.kt — "设为默认助手"引导入口：
   SDK_INT≥29 且 RoleManager.isRoleAvailable(ROLE_ASSISTANT) → requestRoleIntent；
   否则跳系统设置页（SR16）

验证：Build ✅；设为默认助手后角落手势/电源键触发 → Live 语音会话起
（三星 L3 点①②，交装机实测 G6）。R8 输出 + 进度表 + commit。
```

### Step KB3.2 — AssistantModule 配置骨架（2 文件）

```
执行 Zero V2 知识库轨 Step KB3.2（AssistantModule 配置骨架，设计 §18.3）。
项目在 D:\ZeroV2。先读标杆 MusicModule 骨架（模块结构/Config 惯例），再动手。

文件（2 文件）：
1. 新 app/.../assistant/AssistantModule.kt — @Singleton IZeroModule（id="assistant"，
   SYSTEM，launchOrder=140，无 FC）：AssistantConfig 三字段——hotwordEnabled(TOGGLE
   默认 false，裁-C)/residentMode(DROPDOWN: 关/仅充电/全时段，默认关)/
   wakeWord(TEXT 默认"你好，大帝"，裁-B)；onAwake/onConfigChanged → 按配置编排
   HotwordService 启停（服务类 KB3.3 才有——本步编排位空实现+注释）
2. AppBindingModule.kt — @Binds @IntoSet bindAssistant

验证：Build ✅；模块详情页 AutoForm 渲染三字段（零 UI 代码）；开关默认关。
R8 输出 + 进度表 + commit。
```

### Step KB3.3 — HotwordService 唤醒常驻（2–3 文件）

```
执行 Zero V2 知识库轨 Step KB3.3（唤醒词常驻，设计 §18.2/KD12）。
项目在 D:\ZeroV2。先做前置核验（SR15）：grep 语音模块 build.gradle.kts 是否已依赖
sherpa-onnx AAR——不在则本步 build.gradle.kts 显式声明（R5）。
再读标杆 LiveSessionService（前台 microphone 服务写法）+ SystemBus.onVoiceActive。

文件（2–3 文件）：
1. 新 app/.../assistant/HotwordService.kt — 前台服务（foregroundServiceType=
   "microphone"，仿 LiveSessionService 3-arg startForeground + companion start/stop）：
   - AudioRecord 流式喂 sherpa-onnx KWS（开放词表，关键词=拼音序列——默认"你好，大帝"
     硬预置，AB-B）；命中 →（可选确认音）→ LiveSessionService.start
   - onVoiceActive 协同（KD12）：true → 停 AudioRecord/KWS **保服务**（通知切
     "会话中暂停监听"）；false → 恢复监听——防 TTS 自唤醒（R7/AB-A）
   - 三档策略（裁-C）：关=服务不起；仅充电/全时段=**desired state**（KD15/GA3：POWER
     广播/后台 onConfigChanged 仅更新期望态并 try 启动，catch
     ForegroundServiceStartNotAllowedException（Android 12+）与麦克风无流（Android 14
     while-in-use）→ trace + 等待合法窗口（用户打开 app/VIS 触发）补启动；前台交互
     启动为主路径，VIS 提供方属官方豁免；**不加 WorkManager/AlarmManager/保活架构**）
   - 静默失败留 trace（E28）
2. AndroidManifest.xml — service 声明（foregroundServiceType="microphone"；
   FOREGROUND_SERVICE_MICROPHONE 权限既有）
3.（仅 SR15 核验失败）app/build.gradle.kts +sherpa-onnx（R5 本步显式声明）

验证：Build ✅；亮屏喊"你好，大帝"→Live 会话起（在播音乐自动让位）；Live 期间喊
不触发（R7 回归）；三档切换生效；**后台（HOME 出 app）POWER 触发启动 FGS 真机行为
记录入档**（GA3/SR18——Android 12+/14+ 限制实测）；息屏数分钟后喊不响应（平台边界
trace 记录）。
R8 输出 + 进度表 + commit。
```

### Step KB3.4 — 打磨与 L3 实测（1–2 文件+文档 · 全轨收官）

```
执行 Zero V2 知识库轨 Step KB3.4（Assistant 打磨 + 三星实测 + 全轨收官）。
项目在 D:\ZeroV2。先读设计 §18.4 开放项 + 探索存档 R1/G6-G7。

内容（1–2 文件+文档）：
1. KWS 模型资产落位（AB-C 三选一定案实施：assets/工作目录/首启下载——对齐既有
   sherpa 模型管理）
2. 自定义唤醒词路径定案（AB-B 三选一：模型自带方案/内置轻量拼音库（新依赖 R5 显式
   声明）/用户输拼音）+ 阈值/确认音调参
3. 三星 One UI 四点实测矩阵执行（G6：①默认助手可选 Zero ②角落手势触发 ③侧键长按
   可配置性 ④Bixby 占位替换）——结果入档
4. G7 耗电记录：常驻监听电量占比（超标→建议默认仅充电档）

R8 输出 + 进度表 + commit。KB 轨全轨收官（知识库域+记忆配套+Assistant 化）——
输出全轨验收清单交用户 L3。
```

---

## 知识库轨防漂移锚点（每会话自检 · 摘自落地计划 §十）

- **决策封板**：KA1–KA12 + KD1–KD10 为基线；"更好方案"仅 backlog 提案。
- **范围红线（KA9/KD7）**：`knowledge/` 子树 `.md` only；`KnowledgePaths.SUBDIR` 编译期常量不进配置——"收 llmmusic/根目录 md"或"目录可配置"即漂移。
- **md 单写径（KA12/AA1）**：知识 md 唯一写径 = `save_to_file(format="md")`；把 `create_document`/`edit_document` 当 md 写径引用即漂移；修订=读→改→整篇重写，不加精准编辑 FC。
- **内容零写径 + 不做 Auto-Retrieval（KA7）**：KB 无任何写内容 FC（`kb_write/kb_note` 即漂移）；检索时机归 Agent，不自动注入。
- **生命周期分立（KA1）**：KB 表永不衰减/归档/合并——把 Memory 演化逻辑套知识表即漂移。
- **事件=提示，树=真相（KD8/C5/C6）**：onFilesChanged 只触发索引，一致性以 listTree 三元组对比为准；emit 面=4 FC（AA2）不扩。
- **doIndex 单飞（KD10/AA4）**：事件/启动/周期/kb_reindex 四路经 indexMutex 串行。
- **纯搬家纪律（C4）**：RetrievalKit 抽取不改 Memory 行为；回归 FAIL 先修复再继续。
- **记忆后台模型绑定（KD9/KA11）**：Reflection/图谱提取/事实提炼一律 `select(CHAT)` 跟随当前对话模型——重新出现每任务模型 DROPDOWN/硬编码名单即漂移。
- **Assistant 边界（KD11/裁-5）**：息屏唤醒不设计不承诺（特权墙）——任何"息屏可唤醒"表述即漂移；唤醒词入 ConfigField(TEXT) 默认"你好，大帝"（裁-B）——硬编码唤醒词即漂移。
- **Hotword 协同（KD12/C10）**：Live 会话期间停采集保服务——"双服务同时采集"或"直接 stopService"即漂移。
- **Hotword 启动来源（KD15/GA3）**：前台交互主路径 / VIS 豁免 / 后台事件仅 desired state+catch+trace 等合法窗口——"POWER 广播保证拉起 microphone FGS"或"加保活架构"即漂移。
- **回填单飞（KD14/GA5）**：全局唯一 embedding backfill worker——"doIndex 尾部无条件 launch"即漂移。
- **降级链（§九）**：向量→词法→引导，永不哑火。
- **依赖红线**：KnowledgeModule 在 app（依赖 WorkDirRepository 合法）；RetrievalKit 在 services；零 gradle 改动。
- **通用**：迁移同 commit（X9）；chatHistory 纯净；≤3 文件/Step；onAwake 自调 registerFunctions；E28 静默失败留痕；DB 写 Mutex+@Transaction。

---

## 相关文档

- 设计：`ZeroV2-知识库设计.md`（v2.2 · KA/KD 权威）
- 实施计划：`ZeroV2-知识库落地实施计划.md`（SR/C/G/F/AA · 进度表权威）
- 评估集：`ZeroV2-知识库评估集.md`（KB0.4 产出 · 检索质量数字判据）
- Assistant 探索存档：`ZeroV2-Assistant化与语音唤醒探索.md`（§十八来源底稿 · 市场/AB 审计/裁定 A–D）
- 开发指南：`ZeroV2-LLM开发指南.md`（跨轨 R1–R8 + 地雷 + 红线）
- 结构范式参照：`ZeroV2-音乐模块开发提示词.md`（本档结构母本）
