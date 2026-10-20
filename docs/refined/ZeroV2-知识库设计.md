# Zero v2 — 知识库模块开发设计

> **定案稿 v2.4** ｜ 2026-09-14
> **状态**：✅ 用户五裁定落档（§十七）+ AA 审计 + Assistant 并入 + **GPT 外审 GA1–GA5 封板勘误**（落地计划 §八c，5 项全采纳）——封板，可进编码；配套落地计划 `ZeroV2-知识库落地实施计划.md`
> **定位**：本地知识库 —— 独立 app 层模块 · 专属 `knowledge/` 子树 · 共享检索底座 · 表与生命周期分立 · 实时更新
> **依据**：代码基座实证（§二）+ 市场记忆/RAG 设计调研（§三）+ `ARCHITECTURE_FINAL.md` 铁律 + `ZeroV2-LLM开发指南.md`（配方 A/G，R1–R8，X9/X12/E28/B11）
> **沿革**：v1 草稿（Zero agent，2026-09-13）→ v1.1 修订（Claude，KA 裁定表 8 项）→ **v2 定案**（用户裁定 3 项落档：KA9 专属子树 / KA10 实时更新进 P1 / 评估集先建）→ v2.1 增补（用户裁定 4：记忆模块后台模型绑定 KA11——前置步 KB0.2/KB0.3，§十六.0）→ **v2.2 交叉审计修订**（AA 表 AA1–AA5，落地计划 §八b）→ **v2.3 并入增补**（裁-5 2026-09-14：Assistant 化与语音唤醒设计并入为 §十八，落地展开 Phase KB3——探索文档转来源存档）→ **v2.4 GPT 外审封板勘误**（GA1–GA5 全采纳，落地计划 §八c：目录深度/事件语义/microphone FGS 启动约束/KB0.3 同源/回填单飞）

---

## 一、目标与边界

### 1.1 目标

1. Agent 能检索工作目录 **`knowledge/` 专属子树**中的 md 知识文档（笔记 / 见闻 / 纪要 / 索引卡等）——按问题捞出**片段级**命中，而非整篇。
2. **实时更新**：LLM 写入 `knowledge/` 后索引秒级跟上（写后事件触发），周期扫描兜底外部改动。
3. 为**长期良性发展**打地基：索引规模可增长（万级 chunk）、检索可降级（无网/无 Key 仍可用）、模型可更换（重嵌入即恢复）、索引可弃可重建。

### 1.2 范围模型（KA9 用户裁定）

```
KnowledgePaths.SUBDIR = "knowledge"        // 编译期常量（音乐 MD15 同律，不入配置）
范围 = 工作目录 knowledge/ 子树内全部 .md 文件（仅 .md；子目录深度受 WorkDirRepository.listTree
       当前 ≤5 层递归上限约束——GA1 勘误：目标范围与底层能力一致，超深不枚举并 trace 提示）
子树不存在 → 首次索引幂等引导创建（createFolder，音乐 scan_bootstrap 先例）
```

- **目录即边界**："想入库就写进 `knowledge/`"——零配置、零歧义；子树外的一切（含 `llmmusic/`）天然不收。
- **`.md` only**：知识文件主体即 markdown（文件 FC 的产物、切分器的输入）；其他扩展日后真有需求再加，不预设。

### 1.3 边界

**做**：文档 → 切分 → 索引（词法 + 向量）→ 片段检索 → 元数据过滤（路径前缀/标签）。

**不做**：
- ❌ **不承载内容写入**——正文由 LLM 经 `save_to_file(file_name="knowledge/xxx.md", format="md")` 写入（**md 唯一写径**，AA1 审计修正：`create_document`/`edit_document` 属办公文档域 docx/xlsx/pdf 不产 md）；知识库只做索引（写入纪律见 §十一）。
- ❌ **不做 Auto-Retrieval**（每轮自动检索注入）——检索时机是 Agent 的**决策**，铁律「Module 只执行不决策」；且恒注入白耗 token。Agent 按需自调 `kb_search`。
- ❌ 一期不做 Reranker、知识图谱、跨库推理（拆分判据见 §十六.3）。

**已知边界（须自知）**：
1. 检索出的片段仍发给云端模型作答——"数据不落云盘"可保，"数据不出本机"不能；且**嵌入本身**（DashScope text-embedding-v3）也把 chunk 文本送云端——暴露面与记忆向量化相同；`hybridEmbedding` 开关可完全关闭向量路（纯本地词法）。
2. **外部改动无实时性**（文件管理器直放/直删文件）：SAF 无文件监听 API——写后事件只覆盖 Zero 文件 FC 的写入；外部改动靠周期扫描 + 启动校验兜底（§七.1）。

---

## 二、基座事实（代码实证 · 本设计的地基）

| # | 事实 | 证据 | 对设计的意义 |
|---|---|---|---|
| B1 | **混合检索已完整存在**：MemoryModule v4.6 = embedding 余弦主径 + 词法链（整词 LIKE → bigram 滑窗）+ **RRF 融合（K=60）** + MemoryScoringEngine 重排 | `framework/memory/.../MemoryModule.kt` `recall()`/`embeddingCandidates()`/`lexicalCandidates()`/`rrfFuse()` | 检索管线模式可代码级复用（RetrievalKit 抽取） |
| B2 | **EmbeddingProvider 是可注入共享接口**：DashScope OpenAI 兼容、批 ≤10、失败返 null 走降级、**modelId 来自可编辑 profile** | `services/.../embedding/EmbeddingProvider.kt` + `RemoteEmbeddingProvider.kt`；绑定于 `AppBindingModule.bindEmbeddingProvider` | 向量化零新增依赖；**换 profile = 换向量空间**——chunk 必须落 `embed_model` 列（KA5） |
| B3 | **md 唯一写径 = `save_to_file(format="md")`**（file_name 支持子目录相对路径，`WriteOutcome.finalRelPath` 返回真实落点）；`create_document`/`edit_document` 属**办公文档域**（docx/xlsx/pdf，FilesModule:143-198 实证）不产 md；读径 `read_work_file`/`list_work_files`/`move_file`/`delete_work_file`/`delete_folder` | `app/.../data/FilesModule.kt:119-332` + `WorkDirRepository.writeText:122`（`WriteOutcome(error, finalRelPath)`）/`readText:218` | KB 不新增写入口，只读这些 FC 的产物；实时事件挂其尾部（AA1 审计修正） |
| B4 | **WorkDirRepository 原语齐备**：`listTree`（递归 ≤5 层，含 size/lastModified）/ `resolvePath` / `resolveInSubtree` / `createFolder` / 读文本 | S9.1 落地（音乐轨 M13 勘误实证） | 增量判定用 **(path, size, mtime) 三元组**——音乐轨 M7/M8 成熟模式（KA6） |
| B5 | **层依赖**：app → framework → services → core 无环；app 可注入 services（MusicModule→TriggerHub 先例）；**framework 不可依赖 app** | `framework/memory/build.gradle.kts`（仅到 services）；MemoryModule 在 framework，`WorkDirRepository` 在 app | 草稿 v1"一期由 Memory 代管"结构性不可行（KA2） |
| B6 | Room v17（音乐轨后），`fallbackToDestructiveMigration` 开（X9 地雷）——迁移同 commit 纪律 | `core/data/.../db/ZeroDatabase.kt` | 新表 → **v18** 迁移组同 commit |
| B7 | SQLite FTS5 对中文无效是本项目已验证结论（D31）；Memory 词法链用 **LIKE + 查询侧 bigram** 而非倒排表 | MemoryModule 词法链注释 | 词法检索照抄 LIKE 路线，**不自建倒排索引**（KA6 附则） |
| B8 | DAO 纪律：**查-留-id-更新**，禁裸 `@Insert(REPLACE)` 撞 UNIQUE（删旧插新 → 子表 FK CASCADE 清空） | 音乐轨 M1/§5.5 教训 | kb_docs 按 path upsert 同律（chunk 表 FK CASCADE 在其下） |
| B9 | SystemBus 加流是既定解耦模式（`onScheduleTriggered`/`onMusicDue`/`onVoiceActive` 三先例，core:bus，emit 方不注入消费方模块） | 音乐轨 C1/C2 裁定链 | FilesModule → KnowledgeModule 的实时通知走 `onFilesChanged`（KA10），不互注 |

---

## 三、市场设计参照与取舍

| 方案 | 核心思想 | 取 | 不取（及理由） |
|---|---|---|---|
| **Mem0**（[arXiv 2504.19413](https://arxiv.org/html/2504.19413v1)） | 提取→巩固（ADD/UPDATE/DELETE/NOOP）→检索；事实级记忆操作管线 | "索引是可重建的派生物"运维观；巩固类比 = 本设计的重切分替换 | 事实级自动提取/合并——那是**记忆**的演化职责（KB 只增改不衰减，混用即误伤）；且自动提取=决策，撞铁律 |
| **Zep / Graphiti**（[arXiv 2501.13956](https://arxiv.org/abs/2501.13956)） | 时间知识图谱；实体/边带有效期，可审计历史 | 时间维度价值认可（kb_docs 留 mtime/indexed_at 备查） | 图数据库基建——个人单机场景过度；图谱列入拆分判据（§十六.3） |
| **Letta / MemGPT**（[对比](https://theaiengineer.substack.com/p/cognee-vs-zep-vs-mem0-vs-letta)） | Agent 自编辑记忆；主上下文 vs 外存的 OS 分层 | "外存按需换入主上下文"正是 `kb_search` 形态——Agent 决定何时检索 | 其专用 runtime——Zero 已有 FunctionRegistry + Agent 决策层，等价能力原生具备 |
| **Anthropic Contextual Retrieval**（[工程博客](https://www.anthropic.com/engineering/contextual-retrieval)） | chunk 嵌入前拼上下文 + BM25 混合 + rerank；检索失败率降 49–67% | **低成本版**：嵌入文本 = `《doc标题》 > heading_path > chunk 正文`（markdown 切分天然自带上下文，零 LLM 成本）（KA4）；混合检索共识与 B1 互证 | LLM 逐 chunk 生成上下文（真·contextual embedding）——token 成本高，列 P3 可选；reranker 同列 P3 |
| **本地 PKM**（Obsidian 语义检索生态） | 本地 md 库 + 语义索引 | 文件是唯一真相源、索引可弃——与维护契约（§十六.4）一致 | —— |

**市场结论**：四家共识 = ①混合检索（词法+向量+融合）②记忆与知识分层治理 ③索引/图谱是派生物。分歧在基建重量（图/专用 runtime）——Zero 以"共享底座 + 轻模块"取其共识、弃其重基建。

---

## 四、架构总览

### 4.1 模块归属（KA2）：独立 app 层模块

```
                    ┌─ app ── KnowledgeModule（本轨新增，id="knowledge"）
                    │           │ 依赖 WorkDirRepository（SAF 读文件）★须 app 层
                    │           │ 注入 EmbeddingProvider（services，既有绑定）
                    │           │ 订阅 SystemBus.onFilesChanged（B9 模式）
                    │           ▼
                    │      RetrievalKit（services/retrieval 新——纯函数抽取）
                    │           │ cosine / rrfFuse / bigram / BLOB 编解码
 Retrieval 底座 ────┤           ▲
 （共享，不复制）    │      MemoryModule（framework/memory，既有）
                    │           │ 同 commit 切换到 RetrievalKit（函数体搬家，行为不变）
                    │           ▼
                    ├─ Room ── memories 表（衰减/合并/归档——Memory 管）
                    │          kb_docs + kb_chunks（只增改不衰减——Knowledge 管）
                    └─ FC ──── memory_recall（既有） ｜ kb_search / kb_status / kb_reindex（新）
```

- **KnowledgeModule**：`@Singleton` + `IZeroModule` + `AgentJob`（MusicModule 三重身份先例；双 `@IntoSet` 绑定，`bindSystemEvent`+`bindSystemEventJob` 实证合法）。`category=SYSTEM`（崩溃 safeCall 自动禁用不伤 Core），`launchOrder=130`。
- **RetrievalKit**（`services/retrieval/RetrievalKit.kt`）：把 MemoryModule 私有的 `cosineSimilarity`/`rrfFuse`（泛型化）/`encodeEmbedding`/`decodeEmbedding`/查询 bigram 生成抽为无状态纯函数。放 services 因 framework 与 app 都可下行依赖（B5；framework/memory 已 `implementation(:services)` 零 gradle 改动）。
- **"共用底座"的物理落点** = EmbeddingProvider（既有，零改动）+ RetrievalKit（抽取）；**不是**模块挂靠——挂靠因 B5 依赖冲突不可行。

### 4.2 数据流

```
LLM 写知识文档（save_to_file(file_name="knowledge/xxx.md", format="md") → 既有 FC 零逻辑改动）
        ↓ 文件 FC 尾部 tryEmit(systemBus.onFilesChanged, listOf(路径))     ← KA10 实时链
        ↓ debounce 2s（KB 侧合并抖动）→ 过滤 knowledge/ 前缀 → 增量索引该文档
KnowledgeModule 索引引擎（事件即时段 + AgentJob 周期 + 启动校验 三路合一入口 doIndex）
        ↓ listTree 三元组比对（B4）→ 变更文档 → 读正文 → markdown 切分（§六）
        ↓ 词法文本入 kb_chunks.text；（hybridEmbedding 开且 Key 可用）异步 embedBatch → BLOB + embed_model
Agent 对话中自判需要资料 → kb_search(query)
        ↓ 词法链（LIKE+bigram，恒可用）∥ 向量链（余弦，可用时）
        ↓ RRF 融合 → Top-K 片段（附 来源路径 + heading_path + "read_work_file 可读全文"提示）
Agent 筛选注入上下文、作答（多文档串联 = Agent 多轮 kb_search 的职责，检索层只保证捞得准）
```

---

## 五、Room 持久化（v18，X9 迁移组同 commit）

### 5.1 kb_docs（文档表）

```
id TEXT PK ｜ relative_path TEXT NOT NULL（UNIQUE 索引，相对 knowledge/ 根，含子目录——音乐 MD13 同律）
｜ title TEXT NOT NULL DEFAULT ''（首个 # 标题或文件基名）
｜ mtime INTEGER NOT NULL ｜ size INTEGER NOT NULL
｜ chunk_count INTEGER NOT NULL DEFAULT 0 ｜ indexed_at INTEGER NOT NULL
｜ tags TEXT NOT NULL DEFAULT ''（预留，一期空）
```

### 5.2 kb_chunks（片段表）

```
id TEXT PK ｜ doc_id TEXT NOT NULL（FK → kb_docs.id CASCADE，索引）
｜ seq INTEGER NOT NULL ｜ heading_path TEXT NOT NULL DEFAULT ''（祖先标题链 "学习笔记 > 中医 > 方剂"）
｜ text TEXT NOT NULL（切分正文副本——索引派生物，可弃可重建）
｜ char_len INTEGER NOT NULL ｜ embedding BLOB（小端 FloatArray，Memory 同格式）
｜ embed_model TEXT NOT NULL DEFAULT ''（KA5：写入时 provider.modelId）｜ updated_at INTEGER NOT NULL
```

**三处刻意设计**：
1. **doc 正文不整篇入库**——唯一真相源是文件；但 **chunk.text 必须入库**：词法 LIKE 检索的前提 + 文件暂时不可读时片段仍可返回。chunk.text 属索引派生物，与 embedding 同级可弃。
2. **upsert 走查-留-id-更新**（B8）：`getByPath` 命中 → UPDATE mtime/size/chunk_count/indexed_at（保留 id）；**禁 `@Insert(REPLACE)`**——撞 UNIQUE(path) 删旧插新致 doc id 漂移 + chunk FK CASCADE 全清。
3. **重切分 = 整文档级替换**：doc 变更 → `deleteChunksByDoc` → 批量 insert 新 chunks。不做 chunk 级 diff（省复杂度，嵌入费用由 §七.3 治理）。

### 5.3 DAO（KbDao，`core/data/.../dao/KbDao.kt`，全 suspend）

docs：`loadAllDocs` / `getByPath` / `insertDoc`（无 REPLACE）/ `updateDocIndexed(path, mtime, size, chunkCount, indexedAt)` / `deleteDoc`
chunks：`deleteChunksByDoc` / `insertChunks(List)`（`@Transaction` 批量）/ `searchByText(term, limit)`（LIKE）/ `getWithEmbedding`（全量向量扫描，Memory 同款）/ `updateEmbedding(id, blob, model)` / `getWithoutEmbedding(limit)`（回填）/ `countAllChunks` / `countModelOf(model)`
迁移：`MIGRATION_17_18`（CREATE 2 表 + UNIQUE(path) 索引 + doc_id 索引，仿 `MIGRATION_15_16` 双表先例）；`DatabaseModule` provide + addMigrations。

---

## 六、切分与嵌入

### 6.1 markdown 感知切分（零依赖手写）

1. 按 `^#{1,6} ` 标题行分节；每节 heading_path = 祖先标题链。
2. 节内按空行段落聚合，目标块 **300–600 汉字**；超长段落再按句末标点（。！？；）硬切。
3. 代码围栏（```）不切（超长才硬切）——保代码完整。
4. 无标题小文档整篇一块；单文档块数 > `maxChunksPerDoc` 停止并 trace 告警（仍索引前 N 块）。
5. 文档 `title` = 首个 `# ` 行，缺省文件基名。

### 6.2 嵌入文本构造（KA4：Anthropic Contextual Retrieval 低成本版）

```
embed input = "《${doc.title}》 ${heading_path} ▸ ${chunk.text}".take(1000)
```
标题链前置给短 chunk 补足语义上下文（孤立块"三阴交：内踝尖上三寸"→"《学习笔记》 中医 > 穴位 ▸ 三阴交：内踝尖上三寸"），零 LLM 成本拿到 contextual embedding 的主要收益。查询侧不拼接。LLM 逐块生成场景说明列 P3。

### 6.3 模型换档防护（KA5）

- 写入时落 `embed_model = provider.modelId`（profile 可编辑——B2）。
- 检索时向量路逐块校验维度（Memory 先例 `v.size != qVec.size → null`）+ `embed_model == provider.modelId` 过滤；不匹配块只走词法路。
- `kb_status` 检测"多数块模型 ≠ 当前模型"→ 提示"嵌入模型已更换，建议 kb_reindex 全量重嵌入"。
- 换模型最坏情况 = 向量路失效退纯词法（§九降级链），**系统永不哑火**；重建即恢复。
- ⚠️ 联动发现：**Memory 表无 embed_model 列，同样存在换档混空间风险**——本轨不改（授权三类之外），记 backlog 提案交用户。

---

## 七、索引管线

### 7.1 触发三路（KA10：实时事件进 P1），同一入口 `doIndex(trigger)`

| 路 | 时机 | 实现 |
|---|---|---|
| **写后实时**（P1） | 文件写 FC 完成后 | `SystemBus.onFilesChanged: MutableSharedFlow<List<String>>`（DROP_OLDEST + buffer 32，EK5）；FilesModule **四个可影响 knowledge/ 的 FC**（AA2 审计收窄）：`save_to_file`（emit `WriteOutcome.finalRelPath` 真实落点——冲突改名场景）/`move_file`（新旧两路径）/`delete_work_file`/`delete_folder`，尾部 `tryEmit(路径)`（不阻塞 FC 返回）；`create_document`/`edit_document` 产物非 .md 不索引不 emit；KB collect → debounce 2s → 过滤 `knowledge/` 前缀 → **触发 doIndex(trigger="event") 全量树对账**（GA2 勘误：三元组跳过未变更者、成本已低；事件路径仅作 trace 提示，**不做 path 级短路**——防实现成 path-scope 索引削弱"树=真相"） |
| 启动 | `onStart` 后台一次（`autoIndexOnStart`） | `moduleScope.launch { doIndex("start") }`（音乐 autoScanOnStart 先例） |
| 周期 | AgentJob：`nextRunTime = now + indexIntervalMin` | Scheduler 轮询调 `execute()`（MusicModule AgentJob 同款）——**外部改动（文件管理器直放/直删）唯一捕获路**（SAF 无监听，边界 §一.3-2） |

事件路径只做"触发哪些文档重查"的提示；**真相以 listTree 三元组对比为准**（move 出子树 = 删行、move 进子树 = 下轮收录——最终一致性，音乐 MD16 同 philosophy）。写 FC 的 emit 与 KB 的索引互不阻塞、互不依赖成败。`download_file` 等旁路写入若落 .md 进 knowledge/，由周期路兜底（同外部改动语义）。

**单飞互斥（AA4）**：事件/启动/周期/`kb_reindex` 四路都汇入 `doIndex`，以 `indexMutex` 串行（过程级单飞，DB 写 Mutex 之外）——并发触发至多排队不重入；`kb_reindex` 全量与增量同锁。

### 7.2 增量判定（KA6：三元组，非 hash）

```
listTree()（≤5 层，B4）→ 过滤 knowledge/ 子树 .md
  ├─ 新文件        → 读 → 切分 → 入库（+异步嵌入）
  ├─ (size,mtime) 变 → 同上（整文档级重切分替换，§五.3-3）
  ├─ 三元组一致    → 跳过（零读取零嵌入——SAF 大库友好，音乐 M7 同款）
  └─ 库有树无      → 删 doc 行
枚举守卫必带（音乐 scan_suspect_empty 先例）：零命中且库存非空 → 视为 SAF 抖动，跳过删除 + trace
```
hash 不作增量键（每轮全读代价高）；内容变化在 mtime 变更后的重切分里天然体现。

### 7.3 嵌入预算与回填

- 新块**先入库**（`embedding=NULL`，词法立即可检索），批量后台嵌入（`embedBatch` ≤10/批，Memory backfill 同款循环：`getWithoutEmbedding` → 批嵌 → 回写，整批失败即停下轮续）——**索引速度不被网络绑架**。
- **回填单飞（KD14/GA5）**：全局唯一 backfill worker——新触发时 worker 活跃（`isActive`）即忽略；**禁止多 worker 并发消费 `getWithoutEmbedding`**（同一批 chunk 重复 embed = 重复 API 计费；连续写入+周期+kb_reindex 并发场景真实存在）。
- 费用治理：`maxDocs`（超限拒新文档 + trace 告警）+ `maxChunksPerDoc` + `hybridEmbedding` 总开关。qwen text-embedding-v3 千 chunk 级成本可忽略，治理为防失控非省钱。
- 嵌入失败/无 Key：块保留词法可检，trace 留痕（E28）。

---

## 八、FC 清单（`metadata.id="knowledge"` 注册，FC 名一经发布稳定）

| FC | 权限 | 参数 | 说明 |
|---|---|---|---|
| `kb_search` | AUTO | query, top_k?(默认 5), path_prefix?, tag? | 混合检索片段；返回每条 `[相对路径] heading ▸ 片段文本` + 尾注"全文可 read_work_file(knowledge/<路径>)"。description 写明与 memory_recall 分工：**记忆=用户说过的事（谁），知识库=knowledge/ 里沉淀的文档资料（什么）**；空库返回引导"知识库为空——用 save_to_file(file_name=\"knowledge/主题.md\", format=\"md\") 写入知识笔记后自动索引" |
| `kb_status` | AUTO | — | 文档数/块数/已嵌入块数/当前嵌入模型与可用性/上次索引时间/模型换档提示 |
| `kb_reindex` | AUTO | path?（缺省全量） | 强制重切分重建（幂等；换嵌入模型后刷新向量空间；后台执行立即返回受理） |

FC 名 `kb_*` 前缀与既有全集无冲突（落地计划 SR 表 grep 复核）。读类全 AUTO（无破坏性）；无 CONFIRM 项——删除/移动文件归文件域 FC（`delete_work_file`/`move_file`）不代理。

**前置依赖（B11 同律）**：`WorkDirRepository.hasDirectory()==false` 时三 FC 返回引导文本，不崩。

---

## 九、检索管线与降级链（照抄 Memory v4.6 模式，RetrievalKit 承载）

```
kb_search(query):
  路1 向量：embed(query) → kb_chunks 维度+embed_model 校验 → 余弦排序      【可用时】
  路2 词法：query 分词(≥2字) LIKE ∥ 查询 bigram 滑窗 LIKE → 命中计数排序   【恒可用】
  融合：RRF（K=60，RetrievalKit.rrfFuse 泛型版）
  过滤：path_prefix / tag 前置
  返回 Top-K；路1不可用 → 纯路2（trace path=lexical）→ 两路皆空 → "无命中 + 最近索引文档 N 篇提示"
```

降级链 = **向量 → 词法 → 引导**，任何一级失败不阻断。无网/无 Key/换模型三种场景全部落到路2——系统永不哑火（v1 草稿"失败降级，关键词兜底"的落地形态）。

---

## 十、与记忆/文件域的边界契约

| 维度 | Memory（既有） | Knowledge（本轨） | Files（既有） |
|---|---|---|---|
| 内容 | 用户说过的事（一句话一事） | knowledge/ 沉淀文档（篇章级） | 文件字节 |
| 生命周期 | 衰减/归档/合并/冲突检测 | **只增改，永不衰减** | 用户主权 |
| 写入主体 | Agent 提取+FC（**是决策**，归 Kernel §五） | **无人写内容**——只索引文件产物 | LLM 经 FC 显式写（不决策） |
| 检索 FC | `memory_recall` | `kb_search` | `read_work_file` |
| 表 | memories | kb_docs/kb_chunks | 无 |
| 向量空间 | 同 EmbeddingProvider 同模型（空间可比，跨库检索留门） | 同左 | — |

- **不共表、不共衰减**（v1 草稿 §2.2 四冲突全保留——本设计第一原则）。
- **不新增写径**：文件 FC 是唯一内容入口（单一事实源）。
- KB 对文件域唯一改动 = 四个 FC（save_to_file/move_file/delete_work_file/delete_folder）尾部各 +1 行 `tryEmit`（KA10/AA2；事件语义与索引成败解耦）。

---

## 十一、写入纪律（LLM 沉淀知识的约定 · prompt/文档层，非代码）

> 内容写入仍走文件 FC——本节是给 LLM 的使用约定，落点：`kb_search` 空库引导文案 + FC description + 各轨提示词文档引用。

1. **触发起写**：任务收尾值得沉淀 / 用户显式要求 / LLM 自判"日后会用到"。
2. **落点与写径唯一**（AA1）：知识一律经 `save_to_file(file_name="knowledge/<主题>.md", format="md")` 写入——新建 `append=false`、续记 `append=true`；写进来自动入索引，无需任何额外操作。**md 无精准局部编辑 FC**——修订循环 = `read_work_file` 读全文 → 融合修改 → `save_to_file(append=false)` 整篇重写（写入纪律层约定，非新 FC）。
3. **写前去重**：先 `kb_search` 查同类主题，有则走修订循环（读→改→重写）并入原文档，不另起一篇。
4. **头部元数据**：文档首行 `# 标题`，可跟一行 `- 日期/标签/来源`——切分器 title 提取与标签过滤友好。
5. **一主题一文档、标题分层清晰**：切分质量的上游（§六.1 按标题分节）。

---

## 十二、配置（KnowledgeConfig，@ConfigField，min/max 显式——指南 §3.1）

| 字段 | 类型 | 默认 | min/max | 说明 |
|---|---|---|---|---|
| autoIndexOnStart | Boolean(toggle) | true | — | 启动后台增量索引 |
| indexIntervalMin | Int(number) | 30 | 10/1440 | 周期索引间隔（分钟，兜底外部改动） |
| hybridEmbedding | Boolean(toggle) | true | — | 向量混合检索总开关（关=纯本地词法，零云端调用） |
| maxDocs | Int(number) | 1000 | 50/5000 | 索引文档上限（防失控，超限 trace 告警） |
| maxChunksPerDoc | Int(number) | 500 | 10/2000 | 单文档块数上限 |

常数不入配置（M15 律）：`knowledge` 子目录名（KA9——目录配置化=索引键全失效，音乐 MD15 同理）、目标块长 300–600、RRF K=60、批 10、debounce 2s。`toJson/fromJson` 手写 + `coerceIn` 钳位。

---

## 十三、KA 裁定表（v1 草稿 → v2 定案 · 代码实证逐项）

| # | 主张 | 核实 | 裁定 |
|---|---|---|---|
| KA1 | （v1）共用底座、表/接口/生命周期分开；不共衰减 | 与 B1/B8 相容，方向正确 | ✅ 采纳（§四/§十） |
| KA2 | （v1）一期由 Memory 代管 | **结构性冲突（B5）**：MemoryModule 在 framework，不可依赖 app 层 WorkDirRepository | ⚠ 改向：**独立 app 层 KnowledgeModule**（FilesModule/MusicModule 同列）；"不另起炉灶"由底座共享达成 |
| KA3 | （v1）P1 关键词 / P2 向量，理由"向量是新依赖" | **论据不成立（B1/B2）**：混合检索与 EmbeddingProvider 均已存在 | ⚠ 分期保留、论据改写：P1=**离线可用地基**（切分+词法+过滤+实时事件），P2=向量混合（有 Key 自动升级）——依据是可用性与费用 |
| KA4 | （v1 缺） | Anthropic：裸 chunk 嵌入检索失败率高 | ✅ 新增：标题链前置低成本上下文嵌入（§六.2）；LLM 逐块生成列 P3 |
| KA5 | （v1 缺） | B2：profile 可换 → 向量空间漂移 | ✅ 新增：`embed_model` 落列 + 检索过滤 + 换档提示 + kb_reindex 恢复（§六.3）；Memory 同风险记 backlog |
| KA6 | （v1）mtime+hash；自建倒排+bigram | hash 每轮全读成本高；B7 词法=LIKE 已验证 | ⚠ 增量键改 **(path,size,mtime) 三元组**（音乐 M7）；**不自建倒排**——查询侧 bigram+LIKE（万级 chunk 再议） |
| KA7 | （v1）不做 Auto-Retrieval；无 kb_write；索引可弃；评估集数字化 | 与铁律/架构一致 | ✅ 采纳（§一/§九/§十六） |
| KA8 | （v1）写入后即触发索引 | 方向对，需动 FilesModule 写 FC（AA2 审计后实为 4 个） | ✅ 采纳并**提前进 P1**（与 KA10 合并） |
| KA9 | （v1 待定）入库范围 | —— | ✅ **用户裁定（2026-09-13）**：专属 `knowledge/` 子树 + `.md` only（§一.2）；目录名编译期常量不入配置 |
| KA10 | （v1 待定）实时性 | —— | ✅ **用户裁定**：支持实时更新——`SystemBus.onFilesChanged` 事件链进 P1（§七.1） |
| KA11 | （v1 缺）记忆域 `reflectionModel`/`graphExtractionModel` DROPDOWN 静态名单 `["deepseek-v4-flash","qwen3.8-max"]`——模型名随时会变，名单即死 | `MemoryModuleConfig.kt:28-54` 实证（json 三处 + 迁移同款硬编码）；两 Job `getById(cfg.名) ?: select(CHAT)`（`MemoryReflectionJob.kt:117`/`GraphExtractionJob.kt:74`）——**绑定语义已在回退位**，配置/UI 展示死名单 | ✅ **用户裁定（2026-09-13）**：不做硬编码，**绑定当前 LLM 模型即可** + 对应 UI（§十六.0 方案：删两字段 + Job 直连 `select(CHAT)` + 详情页只读信息卡） |
| KA12 | （v2.1 前文档自误）写入纪律/FC 引用把 `create_document`/`edit_document` 当 md 写径；emit 面按"六写 FC"设计 | AA1/AA2 审计实证：两 FC 属办公文档域（docx/xlsx/pdf）不产 md；能影响 knowledge/ 的 FC 恰 4 个 | ✅ 交叉审计修订（v2.2）：md 单写径 `save_to_file(format="md")`；emit 面收窄至 4 FC；md 修订循环=读→改→整篇重写（§一.3/§七.1/§十一） |

---

## 十四、KD 决策记录

| # | 决策 | 理由 |
|---|---|---|
| KD1 | 独立 app 层 KnowledgeModule（id="knowledge"，SYSTEM，launchOrder=130）+ IZeroModule+AgentJob 双绑定 | B5 依赖实证；MusicModule 三重身份先例 |
| KD2 | RetrievalKit 抽取至 services（纯函数），Memory 同 commit 切换 | 底座共享的物理落点；framework/app 均可下行，零 gradle 改动 |
| KD3 | chunk.text 入库为派生副本；doc 正文不入库 | 词法 LIKE 前提 + 断网可用；唯一真相源仍是文件 |
| KD4 | 嵌入异步回填（先入库后嵌入，批 ≤10，失败续传） | 索引不被网络绑架，词法立即可用 |
| KD5 | 删除知识文档即删索引行（含枚举守卫） | 与音乐 M8 的差异：chunk 无累积状态，重建零损失 |
| KD6 | 全 FC AUTO 权限、无 CONFIRM | 读类无破坏性；破坏操作归文件域不代理 |
| KD7 | `KnowledgePaths.SUBDIR="knowledge"` 编译期常量（KA9） | 目录配置化=relative_path 全失效（MD15 同律）；目录即边界 |
| KD8 | 实时更新经 `SystemBus.onFilesChanged`（List<String> 载荷，**DROP_OLDEST + buffer 32**，tryEmit 不阻塞 FC）——EK5 勘误：原写 EXTRA_BUFFERED 系 Channel 概念误植（SharedFlow 溢出策略仅 SUSPEND/DROP_OLDEST/DROP_NEWEST），满丢旧保新与新事件对账覆盖旧事件语义一致 | B9 三先例模式；emit 方不注入消费方；事件=触发提示，真相以树对比为准；漏事件由周期路兜底 |
| KD9 | 记忆域后台 LLM（Reflection/图谱提取/事实提炼）统一**跟随当前对话模型** `select(setOf(ModelCapability.CHAT))`，不设每任务模型选择器 | KA11 用户裁定"绑定当前即可"；`extractFactWithLLM`（MemoryModule:411）已是该语义——三处统一；无可用模型 → 既有 skip+trace（E28）语义不变 |
| KD10 | `doIndex` 过程级单飞（`indexMutex`）——事件/启动/周期/kb_reindex 四路串行 | AA4：并发重入=重复切分重复嵌入；Scheduler 契约（onTick 遍历 `nextRunTime<=now→execute`，Scheduler.kt:78-91 实证）下 AgentJob 用 var：onStart 置 now（首 tick 即索引）、execute 尾 `now+intervalMin` |
| KD11 | Assistant 域并入 KB 轨（裁-5），组件落 `app/.../assistant/`：`ZeroAssistantService`(VoiceInteractionService 壳+Session 三组件同文件) + `AssistantModule`(id="assistant", SYSTEM, launchOrder=140) + `HotwordService`(前台 microphone)——纯增量零基线改动 | §十八；探索文档转存档；入口经 LiveSessionService 单入口（MD17 同精神） |
| KD12 | Hotword 与 Live 会话并存策略 = **停采集保服务**（onVoiceActive true→停 AudioRecord/KWS 保前台服务，通知切"会话中暂停监听"；false→恢复） | AB-A/R7 自唤醒防治；快速恢复免服务重启开销；通知透明 |
| KD13 | 唤醒词入 ConfigField(TEXT) 默认"你好，大帝"（裁-B），KWS 拼音序列默认硬预置（零依赖）；自定义转换路径三选一 KB3.4 定 | 唤醒词=用户偏好非约定常数（与 MD15 目录常量律相反方向，合规）；AB-B 开放项 |
| KD14 | embedding 回填**全局单飞 worker**（`isActive` 守卫，新触发忽略；禁多 worker 并发消费 `getWithoutEmbedding`） | GA5：重复消费=重复 embed=重复 API 计费；连续写入+周期+kb_reindex 并发真实存在 |
| KD15 | HotwordService **启动来源规则**：前台交互主路径 / VIS 豁免 / 后台事件仅 desired state+失败 trace 等合法窗口；residentMode=监听策略非启动保证；不做保活架构 | GA3（P1）：Android 12+ `ForegroundServiceStartNotAllowedException` + Android 14 microphone FGS while-in-use——POWER 广播直启不可靠（官方 restrictions-bg-start 豁免清单含 VoiceInteractionService 提供方） |

---

## 十五、安全与禁区遵守（对照指南 §四/§五）

- **X9**：Entity+Dao+version+MIGRATION_17_18 同 commit；老库升级 L3 必测。
- **X12**：KnowledgeModule 在 app（合法依赖 WorkDirRepository）；RetrievalKit 在 services（framework/app 双向可达）；**零 gradle 改动**（R5 合规）。
- **DB 写**：`Mutex` + `@Transaction` 批量 insert（Memory/音乐同律）。
- **chatHistory 纯净**：KB 事件不进 chatHistory；索引状态走 trace/FC（F8）。
- **E28**：索引/嵌入/检索/事件消费所有静默失败路径留 `traceManager.record`。
- **B11**：工作目录未配置返回引导文本，不崩内核。
- **D57**：KB 只读 SAF 树（resolvePath 拒穿越天然钳制 knowledge/ 子树内）；无写操作。
- **D19**：AgentJob 周期走 Scheduler 既有轮询，**不另立 tick 源**。
- **铁律**：Module 只执行不决策——检索时机归 Agent；不触 N6.6/FunctionRegistry 唯一/BudgetGuard。
- **≤3 文件/Step（R1）**：迁移组例外同提交；步骤拆分见落地计划。

---

## 十六、分期与验证

### 16.0 前置：记忆模块配套修订——后台模型绑定（用户裁定 2026-09-13 · KA11/KD9）

> 记忆域修订，作为知识库轨**前置步**（KB0.2/KB0.3）先行——两域共享 EmbeddingProvider/RetrievalKit 底座，模型治理一致化先于底座抽取（KB1.2）。

**证据链（代码实证）**：
- [MemoryModuleConfig.kt:28-54](../../framework/memory/src/main/java/com/zero/v2/framework/memory/MemoryModuleConfig.kt)：`reflectionModel`/`graphExtractionModel` 两 DROPDOWN，`options=["deepseek-v4-flash","qwen3.8-max"]` 静态名单 + 默认 `"deepseek-v4-flash"`；`toJson`/`fromJson` 与 `MemoryV1ToV2` 迁移同款硬编码——模型名随时会变（profile 用户可编辑、默认集演进），名单即死名单。
- `MemoryReflectionJob.kt:117` / `GraphExtractionJob.kt:74`：`modelSelector.getById(cfg.硬编码名) ?: modelSelector.select(setOf(CHAT))`——名字失效即**静默回退当前对话模型**（绑定语义已在回退位，配置与 UI 却仍展示死名单谎报）。
- `MemoryModule.extractFactWithLLM`（:411）已是 `select(CHAT)` 纯绑定——三处统一后记忆域后台 LLM 全部跟随当前对话模型。

**方案（三段 · KB0.2 配置+Job / KB0.3 UI）**：
1. **删两配置字段**（DROPDOWN 连同 `toJson`/`fromJson` 两处与 `MemoryV1ToV2` 迁移的 put 两行；`configVersion` 保持 2——删字段对存量 JSON 无破坏，`optString` 不读即无害，旧 JSON 多余 key 静默忽略）。AutoForm 参数区自动少两行（零 UI 代码）。
2. **两 Job 直连绑定**：`getById(cfg.名) ?: select(CHAT)` → `select(setOf(ModelCapability.CHAT))`；无可用模型 → 既有 skip/保留缓冲+trace 语义不变（E28 已留痕，KB0.2 验证点）。
3. **对应 UI（只读信息卡）**：记忆模块详情页新增"⚙️ 后台任务模型"卡——`Reflection · 图谱提取 · 事实提炼 → 跟随当前对话模型（{当前名}）`；数据源**与 Job 同源**：`modelSelector.select(setOf(ModelCapability.CHAT))` 实际选择结果的 modelId（**GA4 勘误：勿自造 "filter enabled+first" 两套逻辑**——`select` 按能力 jaccard 择优而非首个，ModelSelector.kt:47-54 实证，多 CHAT profile 并存时两套结果可能不一致）；经**专用小 ViewModel**（注入 ModelSelector，collect profile 变化重算）暴露（AA5：不复用 `ModuleProfilesViewModel`——其 `setKind` 单值态，EMBEDDING 管理区与 CHAT 信息卡同屏会互踩）；落点 `LlmProfilesScreen.kt` 同文件加卡片+小 VM（`ModuleProfilesSection` 同文件惯例）、`ModuleDetailScreen` memory 分支调用（`ModuleProfilesSection` 旁，S1.1/D115 模块自治区内）。**不设每任务模型选择器**（裁定"绑定即可"——当前对话模型切换经 openai_chat 模块详情页既有模型管理，天然即时生效，N2.6c）。

### 16.1 分期（展开见落地实施计划，步骤前缀 KB）

- **前置（记忆配套修订）**：KB0.2 记忆模型绑定（删字段+两 Job 直连）｜ KB0.3 记忆详情页后台模型信息卡
- **P1（离线地基 + 实时链）**：KB0.4 评估集建档 ｜ KB1.1 v18 迁移组 ｜ KB1.2 RetrievalKit 抽取+Memory 切换 ｜ KB1.3 KnowledgeModule 骨架+切分器+增量索引引擎 ｜ KB1.4 SystemBus.onFilesChanged ｜ KB1.5 FilesModule 四 FC 尾部 emit（AA2）｜ KB1.6 KB 订阅+FC×3（kb_search 纯词法/kb_status/kb_reindex）
- **P2（向量混合）**：KB2.1 嵌入回填 + kb_search 双路 RRF + embed_model 换档防护 + kb_status 增强
- **Phase KB3（Assistant 化 · 裁-5 并入，§十八）**：KB3.1 Assistant 角色壳 ｜ KB3.2 AssistantModule 配置骨架 ｜ KB3.3 HotwordService 唤醒常驻 ｜ KB3.4 打磨与 L3 实测（全轨收官）
- **P3（按效果再议，backlog）**：LLM contextual embedding、reranker、跨库检索（memory+kb 同空间融合）、知识图谱

### 16.2 评估集（KA7/裁-3：编码前建档）

`docs/refined/ZeroV2-知识库评估集.md`：20 问 → 期望命中片段，术语类（专有名词/编号）与语义类（换说法）各半。每轮改动跑 **Recall@5/Recall@10**；判据：术语类达标而语义类掉队 → P2 必要性成立；反之 P1 足够。L3 验收同用此集。

### 16.3 拆分/升级判据（保留 v1 §十）

碰到任一条再议独立强化：需 Reranker/复杂排序；需文档生命周期管理（版本/失效）；需图谱/跨库推理。底座已共享，抽离成本低。

### 16.4 维护契约（保留 v1 §八）

> **正文为准，索引可弃；模型统一，分表存储；改动入库，一键重建；失败降级，词法兜底。**

---

## 十七、裁定记录（2026-09-13 用户四裁定）

| # | 项 | 裁定 | 落档 |
|---|---|---|---|
| 裁-1 | 入库范围 | 工作目录建**专属 `knowledge/` 文件夹**保存 md 知识文件，支持**实时更新** | KA9/KD7/KD8（§一.2/§七.1） |
| 裁-2 | 动手时机 | 音乐测试等待实际使用反馈，知识库设计先行 | 本轨即刻开工（落地计划进度表） |
| 裁-3 | 评估集先建 | 同意 | KB0.4（§十六.2，随裁定 4 插入前置步顺延） |
| 裁-4 | 记忆模块后台模型 | 图谱提取/reflection 模型不硬编码（模型名随时会变），绑定当前 LLM 模型即可，并有对应 UI | KA11/KD9（§十六.0 方案；KB0.2 配置+Job 直连 / KB0.3 详情页只读信息卡） |
| 裁-5 | AS 归属（2026-09-14） | **探索设计并入知识库轨末尾（Phase KB3），不另立独立轨** | §十八 + 落地计划 Phase KB3（KB3.1–KB3.4）；探索文档转来源存档 |

---

## 十八、Assistant 化与语音唤醒（并入设计 · 裁-5 / 2026-09-14）

> 源自《ZeroV2-Assistant化与语音唤醒探索.md》（含裁-A~D 四裁定与 AB-A~E 审计，**该文转为来源存档**）；并入后展开为落地计划 **Phase KB3（KB3.1–KB3.4）**。本节为并入后的设计权威。

### 18.1 平台约束（两堵墙 + 一条免障通道——设计边界）

| 约束 | 内容 | 设计影响 |
|---|---|---|
| 开放通道 | `VoiceInteractionService` + 默认助手角色（设置→默认应用→数字助手；`RoleManager.ROLE_ASSISTANT` API 29+，minSdk 26 → 29 以下引导系统设置页）。`BIND_VOICE_INTERACTION` 为 service 组件属性（约束绑定方），app **零特权需求** | KB3.1 角色壳可行 |
| 特权墙 | 息屏 always-on 唤醒不可达：`CAPTURE_AUDIO_HOTWORD` signature\|privileged（Shizuku/adb 均不可授）+ Android 9+ 屏闭 idle 停供非特权 app 麦克风 | **息屏唤醒不设计不承诺**；亮屏/前台常驻可行（KB3.3） |
| 免 BAL | `VoiceInteractionSession` 自带系统窗口——免 Android 10+ 后台启动 Activity 限制；语音链经 `LiveSessionService`（服务启动不受限） | 手势→语音会话链路无启动阻塞（AB-E） |

### 18.2 组件与数据流（KD11 · app/.../assistant/ · 纯增量）

```
手势/电源键 ──► ZeroAssistantService（VoiceInteractionService 壳）
                    │ onPrepareSession → ZeroAssistantSession.show()（系统窗口，免 BAL）
                    ▼
              LiveSessionService.start（语音模式——MD17 单入口同精神，既有全链）
"你好，大帝" ──► HotwordService（前台服务 · microphone type · 亮屏有效 · Live 期间停采集保服务 KD12）
                    │ sherpa-onnx KWS 流式命中（拼音序列，默认词硬预置）
                    ▼
              LiveSessionService.start → onVoiceActive → 音乐让位/Hotword 暂停自动协同
```

- **ZeroAssistantService.kt**（三组件同文件）：`ZeroAssistantService`(VoiceInteractionService) + `ZeroAssistantSessionService`(VoiceInteractionSessionService) + `ZeroAssistantSession`(onShow→起 Live 语音会话)。
- **AssistantModule**（id="assistant"，SYSTEM，launchOrder=140）：承载配置 + HotwordService 生命周期编排（onConfigChanged→启停）。
- **HotwordService**：前台 microphone + AudioRecord 流喂 KWS + 命中→Live；`onVoiceActive` 协同（KD12）；三档常驻策略。
- **启动来源规则（KD15/GA3——Android 12+ 后台 FGS 启动限制 + Android 14 microphone FGS while-in-use 限制）**：
  1. **用户可见交互启动 = 合法主路径**（设置页开关/模块详情 AutoForm/通知按钮——app 前台时启动）；
  2. `VoiceInteractionService` 提供方身份属官方豁免情形（assistant 链内启动合法）；
  3. **后台事件（POWER 广播/后台 onConfigChanged）仅更新 desired state，不保证可直接创建 microphone FGS**——尝试启动须 catch `ForegroundServiceStartNotAllowedException`（Android 12+）与麦克风无流（Android 14 while-in-use）→ trace（E28）+ 等待下一合法窗口（用户打开 app / assistant 触发）补启动；
  4. `residentMode` 语义 = **监听策略**（何时允许监听）而非"任意后台可启动保证"；电池优化豁免引导（`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`）为缓解非解药；
  5. **不做后台保活架构**——WorkManager/AlarmManager 唤醒循环/Shizuku 保活一律不加（GPT 复审确认零必要）。
- 现成复用：LiveSessionService 全链 / `onVoiceActive`（音乐让位已接）/ TriggerHub+AutonomousThinkingJob（大脑）/ Termux·Shizuku（动作面）/ llama.cpp 本地低延迟应答。

### 18.3 配置（AssistantConfig，@ConfigField）

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| hotwordEnabled | Boolean(toggle) | **false**（裁-C） | 唤醒监听总开关 |
| residentMode | DROPDOWN | "关" | 三档：关 / 仅充电常驻 / 全时段常驻（裁-C） |
| wakeWord | TEXT | **"你好，大帝"**（裁-B） | KWS 拼音序列默认硬预置（零依赖）；自定义转换路径 AB-B 三选一 KB3.4 定 |

### 18.4 开放项与实测（沿探索文档，落 KB3 定案）

AB-B 自定义唤醒词转换路径（KB3.4）/ AB-C KWS 模型资产三路径（KB3.4）/ SR15 sherpa-onnx AAR 依赖树核验（KB3.3 前置）/ G6 三星 One UI 四点实测矩阵（KB3.4 L3）/ R7 自唤醒协同回归（KB3.3 验证项）。

### 18.5 外审对照（GPT 复审 2026-09-15 · fix10 后落档）

> GPT 建议方案 A（系统 DSP）/B（VIS 数字助理）/C（前台 KWS）三选与"唤醒检测与录音分离"分层。逐项对照实证裁定：**与既定架构同向、零新增阻塞**——方案 B 已落地（KB3.1，数字助手列表+电源键手势实测通过）；方案 A 特权墙不可达（§18.1，GPT 亦承认）；方案 C 即现状且 fix10 屏幕门控后"常驻麦克风"批评不再成立（亮屏监听/息屏彻底停麦）。**纠正 GPT 过乐观一处**：VIS 的 `createAlwaysOnHotwordDetector` 同样要求 `CAPTURE_AUDIO_HOTWORD`——锁屏 hotword 不因 VIS 身份开放，锁屏路径只剩锁屏手势触发（G6 待测）。其六项预验证大半已实测（VIS 列表/电源键/FGS 三形态限制），余项转 G6③/SR18 L3。分层诉求（Hotword 层 ↔ 会话层分离、命中才开全链、KD12 让位）架构已满足。

### 18.6 特权墙动向追踪（backlog · 2026-09-15 用户裁定落档）

> §18.1 特权墙现状核查（2026-09-15 三轮检索）：`CAPTURE_AUDIO_HOTWORD` 仍为 signature|privileged 仅预装（AOSP 文档无放开记录）；**新门缝——Android 15（Pixel QPR）新增 设置→应用→特殊应用访问→"Voice activation"**：设计上面向第三方助手免持唤醒（AndroidAuthority 挖掘），但无公开 API、零第三方实装、疑似 Google 白名单准入、One UI 未见覆盖。
> **追踪点**：Android 16/17 行为变更 + Google Issue Tracker hotword 议题——正式 API 发布即评估接入（**Zero 接入就绪度高**：KWS 本地栈/VIS 双路组件已备，接入面小）。其余获取路径（OEM 合作 preload / 自编 ROM）不适用于个人项目。当前 fix10 形态维持第三方最优解不变。

---

## 附：相关文档与来源

- 配套：`ZeroV2-知识库落地实施计划.md`（步骤/SR/C/G/F 表）｜ `ZeroV2-知识库评估集.md`（KB0.4 产出）｜ `ZeroV2-Assistant化与语音唤醒探索.md`（**来源存档**——§十八的探索底稿，含市场链接与 AB 审计细节）
- 内部：`ARCHITECTURE_FINAL.md`（铁律/分层）｜ `AGENT_KERNEL.md` §五（Memory System 归 Kernel——KB 与之边界见 §十）｜ `ZeroV2-音乐模块开发设计.md`（M7 三元组/M8 守卫/MD13 path/MD15 常量/DAO 纪律 先例族）｜ `ZeroV2-LLM开发指南.md`（配方 A/G、R1–R8、X9/X12）
- 市场调研：[Mem0 (arXiv 2504.19413)](https://arxiv.org/html/2504.19413v1) ｜ [Zep/Graphiti (arXiv 2501.13956)](https://arxiv.org/abs/2501.13956) ｜ [cognee vs Mem0 vs Zep vs Letta](https://theaiengineer.substack.com/p/cognee-vs-zep-vs-mem0-vs-letta) ｜ [Anthropic Contextual Retrieval](https://www.anthropic.com/engineering/contextual-retrieval)

*—— 本设计奉行 v1 草稿同一条原则：现在多花一点设计的功夫，换将来不必搬家。*
