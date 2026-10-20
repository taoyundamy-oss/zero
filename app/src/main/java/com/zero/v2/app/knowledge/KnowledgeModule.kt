package com.zero.v2.app.knowledge

import com.zero.v2.core.common.ActionPermission
import com.zero.v2.core.common.EvidenceDomain
import com.zero.v2.core.common.EvidenceRef
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.data.dao.KbDao
import com.zero.v2.core.data.entity.KbChunkEntity
import com.zero.v2.core.data.entity.KbDocEntity
import com.zero.v2.core.moduleapi.ConfigField
import com.zero.v2.core.moduleapi.ConfigFieldType
import com.zero.v2.core.moduleapi.IZeroModule
import com.zero.v2.core.moduleapi.ModuleCategory
import com.zero.v2.core.moduleapi.ModuleConfigBase
import com.zero.v2.core.moduleapi.ModuleLifecycle
import com.zero.v2.core.moduleapi.ModuleMetadata
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import com.zero.v2.services.agent.AgentJob
import com.zero.v2.services.embedding.VectorIndexRuntime
import com.zero.v2.services.embedding.VectorDomain
import com.zero.v2.services.embedding.EmbeddingSpace
import com.zero.v2.services.embedding.spaceId
import com.zero.v2.services.embedding.EmbeddingProvider
import com.zero.v2.services.retrieval.RetrievalKit
import com.zero.v2.app.data.WorkDirRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 知识库轨路径常量 — KD7/KA9（用户裁定 2026-09-13）。
 *
 * 范围 = 工作目录 `knowledge/` 子树内全部 .md 文件；目录名编译期常量**不入配置**
 * （目录配置化 = 库内 relative_path 全失效，音乐 MD15 同律）。子树深度受
 * WorkDirRepository.listTree ≤5 层递归上限约束（GA1——超深不枚举并 trace 提示）。
 */
object KnowledgePaths {
    const val SUBDIR = "knowledge"

    /** 相对工作目录根的全路径（读文件用：readText/listTree 路径含前缀） */
    fun inSubdir(relativePath: String): String = "$SUBDIR/$relativePath"
}

class KnowledgeConfig : ModuleConfigBase() {
    @ConfigField(label = "启动自动索引", fieldType = ConfigFieldType.TOGGLE,
        description = "模块启动时后台增量索引 knowledge/ 子树（变更文件才重切分）")
    var autoIndexOnStart: Boolean = true

    @ConfigField(label = "周期索引间隔（分钟）", fieldType = ConfigFieldType.NUMBER,
        min = 10.0, max = 1440.0,
        description = "兜底外部改动（文件管理器直放/直删）的周期全量对账间隔")
    var indexIntervalMin: Int = 30

    @ConfigField(label = "向量混合检索", fieldType = ConfigFieldType.TOGGLE,
        description = "开启后使用云端向量与有界语义重排；关闭后仅本地词法检索，不发起云端检索请求")
    var hybridEmbedding: Boolean = true

    @ConfigField(label = "索引文档上限", fieldType = ConfigFieldType.NUMBER,
        min = 50.0, max = 5000.0,
        description = "防止索引失控的上限；超限拒收新文档并留痕（既有文档更新不受限）")
    var maxDocs: Int = 1000

    @ConfigField(label = "单文档块数上限", fieldType = ConfigFieldType.NUMBER,
        min = 10.0, max = 2000.0,
        description = "单篇文档切分块数上限；超出截断并留痕")
    var maxChunksPerDoc: Int = 500

    override fun toJson(): String =
        """{"autoIndexOnStart":$autoIndexOnStart,"indexIntervalMin":$indexIntervalMin,""" +
            """"hybridEmbedding":$hybridEmbedding,"maxDocs":$maxDocs,""" +
            """"maxChunksPerDoc":$maxChunksPerDoc}"""

    override fun fromJson(json: String) {
        try {
            val obj = JSONObject(json)
            // coerceIn 兜底：防直改存储 JSON 绕过 UI 界限（§7.3）
            autoIndexOnStart = obj.optBoolean("autoIndexOnStart", true)
            indexIntervalMin = obj.optInt("indexIntervalMin", 30).coerceIn(10, 1440)
            hybridEmbedding = obj.optBoolean("hybridEmbedding", true)
            maxDocs = obj.optInt("maxDocs", 1000).coerceIn(50, 5000)
            maxChunksPerDoc = obj.optInt("maxChunksPerDoc", 500).coerceIn(10, 2000)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) {}
    }

    override fun defaultConfig(): ModuleConfigBase = KnowledgeConfig()
}

/**
 * 知识库模块 — KB1.3 骨架（设计 §四/§六/§七；FC 与实时订阅随 KB1.6、嵌入随 KB2.1）。
 *
 * 核心纪律：
 * - **文件是唯一真相源，索引是可弃可重建的派生物**（KA7）——doIndex 全量树对账即可恢复；
 * - **事件=提示，树=真相**（KD8/GA2）：写后事件只触发 doIndex，一致性以 listTree 三元组对账为准；
 * - **doIndex 过程级单飞**（KD10/AA4）：事件/启动/周期/kb_reindex 四路经 indexMutex 串行；
 * - AgentJob 周期走 Scheduler 既有 onTick 轮询（AA3 契约），不另立 tick 源（D19）。
 */
@Singleton
class KnowledgeModule internal constructor(
    private val workDir: KnowledgeSource,
    private val kbDao: KbDao,

    private val systemBus: com.zero.v2.core.bus.SystemBus,
    /** KB2.1：向量路（services 既有绑定；失败返 null 走词法降级，B2） */
    private val embeddingProvider: EmbeddingProvider,
    private val traceManager: AgentTraceManager,
    private val candidateStore: com.zero.v2.core.data.knowledge.KnowledgeCandidateStore,
    private val vectorIndex: VectorIndexRuntime,
    private val reranker: com.zero.v2.services.retrieval.BoundedSemanticReranker? = null,
) : IZeroModule, com.zero.v2.core.moduleapi.ToolContributor, AgentJob {

    @Inject constructor(
        workDir: WorkDirRepository,
        kbDao: KbDao,
        systemBus: com.zero.v2.core.bus.SystemBus,
        embeddingProvider: EmbeddingProvider,
        traceManager: AgentTraceManager,
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
        vectorIndex: VectorIndexRuntime,
        reranker: com.zero.v2.services.retrieval.BoundedSemanticReranker,
        skillStore: com.zero.v2.app.skills.SkillStore,
    ) : this(SkillKnowledgeSource(WorkDirectoryKnowledgeSource(workDir), skillStore), kbDao, systemBus, embeddingProvider, traceManager,
        com.zero.v2.core.data.knowledge.KnowledgeCandidateStore(java.io.File(context.noBackupFilesDir, "knowledge-rebuild")),
        vectorIndex, reranker)

    companion object {
        private const val LEXICAL_ROW_LIMIT = 300
        private const val TRACE_ID = "kb"
        /** 写后事件 debounce（§七.1；常数不入配置——G5） */
        private const val DEBOUNCE_MS = 2_000L
        /** 目标块长 300–600 汉字（§六.1；常数不入配置——M15 律） */
        private const val CHUNK_TARGET_MIN = 300
        private const val CHUNK_TARGET_MAX = 600
        /** markdown 标题行（^#{1,6} 空格开头；围栏内不识别） */
        private val HEADING_REGEX = Regex("^(#{1,6})\\s+(.+)$")
        /** 句末标点硬切边界（超长段落） */
        private val SENTENCE_END = Regex("(?<=[。！？；!?;])")
        private val LEXICAL_SEPARATOR = Regex("[\\s，。、；？！,;:]+")
        private val EXT = ".md"
        /** B11 同律：工作目录未配置的引导文案 */
        private const val NOT_CONFIGURED_HINT =
            "尚未设置 Zero 工作目录。请引导用户到 资料 → 知识与工作文件 选择目录后重试。"
    }

    /** 模块任务单飞；共享候选构建器另以域Mutex串行，源变化由构建复核或下次对账续跑。 */
    private var backfillJob: Job? = null

    // ═══ AgentJob（KB1.3 · AA3 契约）：Scheduler onTick 读 nextRunTime 属性 ═══
    override val id: String = "kb_index"

    /** 初值 MAX（不触发）；onStart 置 now（首 tick 即索引）；execute 尾 +=interval */
    override var nextRunTime: Long = Long.MAX_VALUE

    override val metadata = ModuleMetadata(
        id = "knowledge",
        displayName = "知识库",
        description = "knowledge/ 子树 md 文档索引与片段检索：切分入库、词法/向量混合检索、实时更新",
        category = ModuleCategory.SYSTEM,
        launchOrder = 130
    )

    private val _lifecycle = MutableStateFlow(ModuleLifecycle.UNINITIALIZED)
    override val lifecycle = _lifecycle.asStateFlow()
    override var isEnabled: Boolean = true
    override val requiredPermissions: List<String> = emptyList()
    override val config = KnowledgeConfig()

    private val moduleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** doIndex 过程级单飞（KD10/AA4）——DB 写 Mutex 之外的过程锁 */
    private val indexMutex = Mutex()

    /** DB 写串行（安全守卫 #8） */
    private val dbMutex = Mutex()

    override suspend fun onAwake() {
        _lifecycle.value = ModuleLifecycle.AWAKE
        // 静态工具由 ModuleLifecycleManager 在 onAwake 后集中读取 contributions。

    }

    @OptIn(FlowPreview::class)  // debounce（写后事件抖动合并）
    override suspend fun onStart() {
        _lifecycle.value = ModuleLifecycle.STARTED
        // AA3 契约：首 tick 即索引（Scheduler 下一 onTick 读到 now → execute）
        nextRunTime = System.currentTimeMillis()
        if ((config as KnowledgeConfig).autoIndexOnStart || runCatching { KnowledgeRebuild(workDir, kbDao, candidateStore).pending() }.getOrDefault(false)) {
            moduleScope.launch {
                runCatching { doIndex(trigger = "start") }
                    .onFailure { traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id,
                        "auto_index_failed: ${it.javaClass.simpleName} ${it.message?.take(50)}") }
            }
        }
        // KB2.1：存量块回填入口（doIndex 尾也会触发；单飞守卫下重复调用无害，KD14）
        ensureBackfill()
        // KB1.6（KA10/KD8/GA2）：写后事件订阅——debounce 2s 合并抖动 → 过滤 knowledge/
        // 前缀仅作触发判定+trace → doIndex **全量树对账**（事件=提示，树=真相；
        // 事件路径不做 path 级短路——三元组跳过未变更者，成本已低）
        moduleScope.launch {
            systemBus.onFilesChanged
                .debounce(DEBOUNCE_MS)
                .collect { paths ->
                    if (paths.any { it.startsWith("${KnowledgePaths.SUBDIR}/") }) {
                        traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                            "index_event: paths=${paths.joinToString(",").take(60)}")
                        runCatching { doIndex(trigger = "event") }
                            .onFailure { traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id,
                                "event_index_failed: ${it.message?.take(50)}") }
                    }
                }
        }
    }

    override suspend fun onDestroy() {
        moduleScope.cancel()
        _lifecycle.value = ModuleLifecycle.DESTROYED
    }

    override suspend fun onConfigChanged(newConfig: ModuleConfigBase) { ensureBackfill() }
    override suspend fun checkAvailability(): Boolean = true
    override fun markDisabled() { _lifecycle.value = ModuleLifecycle.DISABLED }

    /** KB1.6：三 FC（读类全 AUTO——无破坏性；破坏操作归文件域 FC 不代理） */
    override fun contributions(): List<com.zero.v2.core.moduleapi.ToolContribution> =
        com.zero.v2.core.moduleapi.toolDeclarations(metadata.id) {
        val functionRegistry = this
        functionRegistry.register(
            FunctionDefinition(
                name = "kb_search",
                description = "检索知识库（knowledge/ 目录沉淀的 md 文档）的**片段**——返回命中片段 + 来源路径与标题链。" +
                        "与 memory_recall 分工：记忆=用户说过的事（偏好/经历），知识库=**沉淀的文档资料**（笔记/纪要/见闻）。" +
                        "技能路径 knowledge/.zero-skills/<id>.md 用 skill_read(id) 读全文；普通命中可用 read_work_file(file_name=\"knowledge/<路径>\") 读全文。空库时按引导写入即可自动索引。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "query" to mapOf("type" to "string", "description" to "检索词（短语或问题关键词）"),
                        "top_k" to mapOf("type" to "number", "description" to "返回片段数（默认 5，最大 20）"),
                        "path_prefix" to mapOf("type" to "string",
                            "description" to "文档路径前缀过滤（如 中医，可选）"),
                        "tag" to mapOf("type" to "string", "description" to "标签过滤（可选）")
                    ),
                    "required" to listOf("query")
                )
            ),
            metadata.id, permission = ActionPermission.AUTO
        ) { args -> kbSearch(args) }

        functionRegistry.register(
            FunctionDefinition(
                name = "kb_status",
                description = "查看知识库索引状态：文档数/片段数/上次索引时间/最近文档列表。" +
                        "检索无结果或怀疑索引未跟上时先查此状态。",
                parameters = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
            ),
            metadata.id, permission = ActionPermission.AUTO
        ) { kbStatus() }

        functionRegistry.register(
            FunctionDefinition(
                name = "kb_reindex",
                description = "强制重建索引（后台执行，立即返回受理）。可传 path（相对 knowledge/，如 中医/方剂.md）" +
                        "只重建该文档；缺省全量重建。索引损坏/换嵌入模型后用此恢复（索引可弃可重建，文件是唯一真相源）。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "path" to mapOf("type" to "string",
                            "description" to "要重建的单文档相对路径（可选；缺省全量）")
                    )
                )
            ),
            metadata.id, permission = ActionPermission.AUTO
        ) { args -> kbReindex(args["path"] as? String) }
    }

    // ═══ AgentJob.execute：周期路（外部改动兜底）→ doIndex 全量对账 + 重排下一轮 ═══

    override suspend fun execute() {
        runCatching { doIndex(trigger = "periodic") }
            .onFailure { traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id,
                "periodic_index_failed: ${it.message?.take(50)}") }
        // AA3：execute 尾推进下一轮（间隔分钟；Scheduler 每 tick 读属性）
        nextRunTime = System.currentTimeMillis() +
                (config as KnowledgeConfig).indexIntervalMin * 60_000L
    }

    // ═══ 索引引擎（§七.2：三元组增量 + 枚举守卫 + 查-留-id-更新）═══

    internal data class IndexStats(val files: Int, val unchanged: Int, val inserted: Int,
                                  val updated: Int, val deleted: Int, val chunks: Int,
                                  val skippedMax: Int, val ms: Long)

    /** 全量树对账：子树引导 → listTree 过滤 .md → 三元组比对 → upsert/删行 + 守卫 */
    internal suspend fun doIndex(
        trigger: String, force: Boolean = false, onlyPath: String? = null,
    ): IndexStats = indexMutex.withLock {
        withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val rebuild = KnowledgeRebuild(workDir, kbDao, candidateStore)
            if (force || rebuild.pending()) {
                rebuild.run(onlyPath, resume = !force, config.maxDocs, config.maxChunksPerDoc,
                    prepare = { rel, text, mtime, size, previous ->
                        val split = splitMarkdown(text, rel.substringAfterLast('/').removeSuffix(EXT))
                        val now = System.currentTimeMillis()
                        val id = previous?.id ?: UUID.randomUUID().toString()
                        val doc = KbDocEntity(id, rel, split.title, mtime, size, split.chunks.size, now, previous?.tags.orEmpty())
                        val chunks = split.chunks.map {
                            KbChunkEntity(UUID.randomUUID().toString(), id, it.seq, it.headingPath, it.text,
                                it.text.length, updatedAt = now)
                        }
                        com.zero.v2.core.data.knowledge.KnowledgeCandidateStore.Candidate("", doc, chunks)
                    },
                    publish = { count, removed, baseline, read ->
                        dbMutex.withLock { kbDao.publishCandidates(count, removed, baseline, read) }
                    })
                traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                    "reindex_committed: recovered=${!force}")
                ensureBackfill()
                return@withContext IndexStats(kbDao.loadAllDocs().size, 0, 0, 0, 0,
                    kbDao.countAllChunks(), 0, System.currentTimeMillis() - t0)
            }

            // 子树引导（幂等——已存在直接成功；失败留痕不中断，如被同名文件占用）
            workDir.createFolder(KnowledgePaths.SUBDIR)?.let {
                traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                    "kb_bootstrap_failed: trigger=$trigger $it")
            }
            val prefix = "${KnowledgePaths.SUBDIR}/"
            val listing = workDir.listTree()
            val nodes = listing.nodes
                .filter { !it.isDir && it.path.startsWith(prefix) && it.path.endsWith(EXT) }
                .associateBy { it.path.removePrefix(prefix) }  // relPath → node（树内路径唯一）

            val existing = kbDao.loadAllDocs().associateBy { it.relativePath }
            var unchanged = 0; var inserted = 0; var updated = 0; var deleted = 0
            var chunkTotal = 0; var skippedMax = 0
            val cfg = config as KnowledgeConfig

            // 仅完整枚举可确认删除；空目录与读取失败必须区分。
            val skipDelete = !listing.complete || onlyPath != null
            if (skipDelete) {
                traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                    "index_incomplete: 枚举未完整确认或仅重建单篇——跳过删除（trigger=$trigger）")
            }
            val removedPaths = if (skipDelete) emptyList() else existing.keys.filter { it !in nodes }
            existing.values.filter { it.relativePath in removedPaths }.forEach {
                dbMutex.withLock { kbDao.deleteDoc(it.relativePath) }
                deleted++
            }

            nodes.forEach { (rel, node) ->
                if (onlyPath != null && rel != onlyPath) return@forEach
                val cur = existing[rel]
                if (cur != null && cur.size == node.sizeBytes && cur.mtime == node.lastModified &&
                    kbDao.countChunksForDoc(cur.id) == cur.chunkCount) {
                    unchanged++  // 三元组一致：零读取零切分（音乐 M7 同款）
                    return@forEach
                }
                // maxDocs 拒新（既有文档更新不受限，防失控非省钱）
                if (cur == null && existing.size + inserted >= cfg.maxDocs) {
                    skippedMax++
                    return@forEach
                }
                val text = workDir.readText(node.path)
                if (text == null) {
                    traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                        "read_failed: ${node.path}（下轮对账重试）")
                    return@forEach
                }
                val defaultTitle = rel.substringAfterLast('/').removeSuffix(EXT)
                val split = splitMarkdown(text, defaultTitle)
                var chunks = split.chunks
                if (chunks.size > cfg.maxChunksPerDoc) {
                    traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                        "chunks_truncated: $rel ${chunks.size}→${cfg.maxChunksPerDoc}")
                    chunks = chunks.take(cfg.maxChunksPerDoc)
                }
                val now = System.currentTimeMillis()
                val docId = cur?.id ?: UUID.randomUUID().toString()
                val preparedDoc = KbDocEntity(
                    id = docId, relativePath = rel, title = split.title,
                    mtime = node.lastModified, size = node.sizeBytes,
                    chunkCount = chunks.size, indexedAt = now, tags = cur?.tags.orEmpty())
                val preparedChunks = chunks.map { c ->
                    KbChunkEntity(id = UUID.randomUUID().toString(), docId = docId,
                        seq = c.seq, headingPath = c.headingPath, text = c.text,
                        charLen = c.text.length, embedding = null, embedModel = "", updatedAt = now)
                }
                dbMutex.withLock { kbDao.replaceDocument(preparedDoc, preparedChunks) }
                if (cur == null) inserted++ else updated++
                chunkTotal += chunks.size
            }

            val stats = IndexStats(nodes.size, unchanged, inserted, updated, deleted,
                chunkTotal, skippedMax, System.currentTimeMillis() - t0)
            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                "index_done: trigger=$trigger files=${stats.files} unchanged=${stats.unchanged} " +
                        "inserted=${stats.inserted} updated=${stats.updated} deleted=${stats.deleted} " +
                        "chunks=${stats.chunks} skipped_max=${stats.skippedMax} ms=${stats.ms}")
            ensureBackfill()  // KB2.1：新块入库后触发异步嵌入（单飞，KD4/KD14）
            stats
        }
    }

    // ═══ 嵌入回填（KB2.1 · KD4 先入库后异步嵌入 + KD14/GA5 全局单飞）═══

    /** 启动候选构建；活跃任务后发生的源变化由周期/事件对账补齐。 */
    private fun ensureBackfill() {
        if (backfillJob?.isActive == true) return
        backfillJob = moduleScope.launch {
            runCatching { backfillLoop() }
                .onFailure { traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id,
                    "backfill_failed: ${it.javaClass.simpleName} ${it.message?.take(50)}") }
        }
    }

    /** Build only in the shadow version; source text and legacy embeddings remain unchanged. */
    internal suspend fun backfillLoop() = withContext(Dispatchers.IO) {
        if (!config.hybridEmbedding) return@withContext
        val outcome = vectorIndex.build(VectorDomain.KNOWLEDGE)
        traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id, "vector_candidate: state=$outcome")
    }

    // ═══ FC 实现（KB1.6）═══

    /** kb_search：P1 纯词法（整词 LIKE ∥ 查询 bigram LIKE → 命中计数排序；向量路 KB2.1 叠加） */
    private suspend fun kbSearch(args: Map<String, Any?>): FunctionResult {
        if (!workDir.hasDirectory()) return FunctionResult.failure(NOT_CONFIGURED_HINT)
        val query = (args["query"] as? String ?: "").trim()
        if (query.isEmpty()) return FunctionResult.failure("query 不能为空")
        val topK = ((args["top_k"] as? Number)?.toInt() ?: 5).coerceIn(1, 20)
        val pathPrefix = (args["path_prefix"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val tag = (args["tag"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        return withContext(Dispatchers.IO) {
            val docs = kbDao.loadAllDocs()
            if (docs.isEmpty()) return@withContext FunctionResult.success(
                "📚 知识库为空——用 save_to_file(file_name=\"knowledge/主题.md\", format=\"md\") " +
                        "写入知识笔记（首行 # 标题、标题分层），写进来自动索引即可检索。")
            val docById = docs.associateBy { it.id }
            // 过滤前置（§九）：path_prefix / tag（一期 tags 空——传 tag 无命中属正常）
            val allowed = if (pathPrefix != null || tag != null) docs.filter { d ->
                (pathPrefix == null || d.relativePath.startsWith(pathPrefix)) &&
                        (tag == null || d.tags.split(',').map { it.trim() }.contains(tag))
            }.map { it.id }.toSet() else null
            if (allowed != null && allowed.isEmpty()) {
                return@withContext FunctionResult.success(
                    "🔍 过滤无匹配（path_prefix=\"$pathPrefix\" tag=\"$tag\"）——共索引 ${docs.size} 篇")
            }
            val (hits, path) = hybridSearch(query,
                limit = com.zero.v2.services.retrieval.RecentRecallWindow.limit(query,
                    maxOf(topK * 3, com.zero.v2.services.retrieval.BoundedSemanticReranker.MAX_CANDIDATES)),
                documents = allowed?.toList())
            val filtered = hits.filter { (allowed == null || it.docId in allowed) &&
                com.zero.v2.services.retrieval.CurrentRecallPolicy.eligible(query, it.text) }
            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                "search: query=${query.take(20)} hits=${filtered.size} path=$path")
            if (filtered.isEmpty()) {
                val recent = docs.sortedByDescending { it.indexedAt }.take(3)
                return@withContext FunctionResult.success(
                    "🔍 无命中「$query」——已索引 ${docs.size} 篇。最近索引：" +
                            recent.joinToString("、") { it.relativePath } +
                            "\n换关键词试试；或 read_work_file(file_name=\"knowledge/<路径>\") 直接读")
            }
            val top = rerankCurrent(query, filtered, docById).take(topK)
            FunctionResult.success(buildString {
                appendLine("🔍 「$query」命中 ${top.size} 段（来自 ${top.map { it.docId }.distinct().size} 篇）：")
                top.forEach { c ->
                    val d = docById[c.docId]
                    appendLine("[knowledge/${d?.relativePath ?: "?"}] " +
                            (if (c.headingPath.isNotBlank()) "${c.headingPath} ▸ " else "") +
                            c.text.take(160).replace('\n', ' '))
                }
                appendLine("── 技能全文：skill_read(id)；普通全文：read_work_file(file_name=\"knowledge/<路径>\")；" +
                        "用户说过的事（偏好/经历）另查 memory_recall")
            })
        }
    }

    private suspend fun rerankCurrent(
        query: String, hits: List<KbChunkEntity>, docs: Map<String, KbDocEntity>,
    ): List<KbChunkEntity> {
        val liveHits = hits.filter { currentSkillChunk(it, docs[it.docId]) }
        val (ranked, status) = if (!config.hybridEmbedding) {
            liveHits to "local_only"
        } else {
            val window = com.zero.v2.services.retrieval.RecentRecallWindow.arrange(query, liveHits, { it.id }, {
                com.zero.v2.services.retrieval.RecentRecallWindow.date(it.headingPath)
            })
            val result = reranker?.rank(query, window, { it.id }, {
                "《${docs[it.docId]?.title.orEmpty()}》 ${it.headingPath} ▸ ${it.text}"
            }) ?: (liveHits to "unconfigured")
            if (result.second in setOf("applied", "cache_hit", "not_needed")) result else liveHits to result.second
        }
        traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id, "rerank=$status candidates=${hits.size}")
        return kbDao.currentRecall(ranked, docs).filter { currentSkillChunk(it, docs[it.docId]) }
    }

    private suspend fun currentSkillChunk(chunk: KbChunkEntity, doc: KbDocEntity?): Boolean {
        if (doc == null) return false
        if (!doc.relativePath.startsWith(".zero-skills/")) return true
        val current = workDir.readText("knowledge/${doc.relativePath}") ?: return false
        return splitMarkdown(current, doc.relativePath.substringAfterLast('/').removeSuffix(".md")).chunks.any {
            it.text == chunk.text && it.headingPath == chunk.headingPath
        }
    }

    /** 词法链候选（B7/Memory 词法链同构）：整词 LIKE（权重 2）∥ bigram 补充（权重 1）→ 计数排序 */
    private suspend fun lexicalSearch(query: String, limit: Int, documents: List<String>? = null): List<KbChunkEntity> {
        val terms = query.split(LEXICAL_SEPARATOR)
            .map { it.trim() }.filter { it.length >= 2 }.distinct().take(6)
        val weighted = HashMap<String, Pair<KbChunkEntity, Int>>()
        terms.forEach { t ->
            lexicalRows(t, documents).forEach { c ->
                weighted[c.id] = c to ((weighted[c.id]?.second ?: 0) + 2)
            }
        }
        if (weighted.size < limit) {
            val bigrams = RetrievalKit.queryBigrams(query)
            val groups = if (documents == null) kbDao.searchTextGroups(bigrams, LEXICAL_ROW_LIMIT)
                else bigrams.map { lexicalRows(it, documents) }
            groups.forEach { rows ->
                rows.forEach { c ->
                    weighted[c.id] = c to ((weighted[c.id]?.second ?: 0) + 1)
                }
            }
        }
        return weighted.values.filter { com.zero.v2.services.retrieval.CurrentRecallPolicy.eligible(query, it.first.text) }
            .sortedByDescending { it.second }.take(limit).map { it.first }
    }

    private suspend fun lexicalRows(term: String, documents: List<String>?): List<KbChunkEntity> =
        if (documents == null) kbDao.searchByText(term, LEXICAL_ROW_LIMIT)
        else com.zero.v2.core.data.knowledge.ScopedDocumentQuery.page(documents, LEXICAL_ROW_LIMIT, { it.id }) {
            kbDao.searchScopedText(term, it, LEXICAL_ROW_LIMIT)
        }

    /**
     * R3：知识库域证据候选——词法路（lexicalSearch，快速本地零网络）→ EvidenceRef。
     *
     * 自动证据注入用词法（避免每回合 embedding API 调用）；语义向量召回走 kb_search FC（按需）。
     * 只读短引用，不替代 kb_search FC，不改变写入域所有权（设计§5.1）。
     */
    suspend fun evidenceCandidates(query: String, limit: Int = 5): List<EvidenceRef> = withContext(Dispatchers.IO) {
        if (!isEnabled || query.isBlank() || limit <= 0) return@withContext emptyList()
        val docs = try { kbDao.loadAllDocs().associateBy { it.id } } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { emptyMap() }
        val lexical = try { lexicalSearch(query, limit.coerceAtMost(100)) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { emptyList() }
        if (lexical.isEmpty()) return@withContext emptyList()
        lexical.filter { currentSkillChunk(it, docs[it.docId]) }.mapIndexed { i, c ->
            val rel = docs[c.docId]?.relativePath ?: "?"
            EvidenceRef(
                domain = EvidenceDomain.KNOWLEDGE,
                id = c.id, content = c.text.take(200), sourceRef = "knowledge/$rel",
                score = 1f / (RetrievalKit.RRF_K + i + 1),
                bodyDigest = com.zero.v2.services.retrieval.EvidencePolicy.bodyDigest(c.text),
            )
        }
    }

    /** Resolve live indexed chunks to their current path; no provider request or source mutation. */
    suspend fun semanticEvidenceCandidates(
        query: String, entries: List<com.zero.v2.core.data.entity.VectorEntryEntity>,
    ): List<EvidenceRef> = withContext(Dispatchers.IO) {
        if (!isEnabled || !config.hybridEmbedding) return@withContext emptyList()
        val docs = kbDao.loadAllDocs().associateBy { it.id }
        entries.mapNotNull { entry ->
            val chunk = kbDao.getChunk(entry.sourceId)?.takeIf {
                it.text == entry.sourceText && it.headingPath == entry.sourceHeading
            } ?: return@mapNotNull null
            val doc = docs[chunk.docId]?.takeIf { it.title == entry.sourceTitle } ?: return@mapNotNull null
            if (!currentSkillChunk(chunk, doc)) return@mapNotNull null
            if (!com.zero.v2.services.retrieval.CurrentRecallPolicy.eligible(query, chunk.text)) return@mapNotNull null
            EvidenceRef(domain = EvidenceDomain.KNOWLEDGE, id = chunk.id, content = chunk.text.take(200),
                sourceRef = "knowledge/${doc.relativePath}",
                bodyDigest = com.zero.v2.services.retrieval.EvidencePolicy.bodyDigest(chunk.text))
        }
    }

    /**
     * 双路检索（KB2.1 · §九）：向量路（可用时）+ 词法路 → RetrievalKit.rrfFuse（K=60）融合；
     * 降级链 **向量 → 词法 → 引导**（hybridEmbedding 关/无 Key/embed 失败/向量集空 → 纯词法，
     * 永不哑火）。返回 (候选, trace 路径标签)。
     */
    private suspend fun hybridSearch(
        query: String, limit: Int, documents: List<String>? = null,
    ): Pair<List<KbChunkEntity>, String> {
        val lexical = lexicalSearch(query, limit, documents)
        if (!(config as KnowledgeConfig).hybridEmbedding) return lexical to "lexical_config_off"
        vectorIndex.search(VectorDomain.KNOWLEDGE, query, RetrievalKit.DEFAULT_CANDIDATE_CAP, documents)?.let { entries ->
            val docs = kbDao.loadAllDocs().associateBy { it.id }
            val ranked = entries.mapNotNull { entry ->
                kbDao.getChunk(entry.sourceId)?.takeIf {
                    it.text == entry.sourceText && it.headingPath == entry.sourceHeading && docs[it.docId]?.title == entry.sourceTitle
                }
            }
            if (ranked.isEmpty()) return lexical to "lexical_no_published_match"
            return RetrievalKit.rrfFuse(ranked, lexical, limit = limit, keyOf = { it.id }).map { it.first } to "hybrid_versioned"
        }
        val model = embeddingProvider.spaceId(EmbeddingSpace.KNOWLEDGE)
        val qVec = try {
            if (!embeddingProvider.isAvailable()) return lexical to "lexical_no_key"
            embeddingProvider.embedQuery(query)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { null } ?: return lexical to "lexical_embed_failed"
        if (model != embeddingProvider.spaceId(EmbeddingSpace.KNOWLEDGE)) return lexical to "lexical_model_changed"
        // R2：有界候选——当前模型已嵌入块 LIMIT cap（替代全量扫描，L-7 债收口）。
        // embed_model 已在 SQL 过滤（旧模型块排除走词法）；维度双校验在 Kotlin 防御。
        val vecRanked = com.zero.v2.services.retrieval.PagedSemanticSearch.search(
            limit = RetrievalKit.DEFAULT_CANDIDATE_CAP,
            page = { after, size ->
                if (documents == null) kbDao.embeddingPage(model, after, size)
                else com.zero.v2.core.data.knowledge.ScopedDocumentQuery.page(documents, size, { it.id }) {
                    kbDao.scopedEmbeddingPage(model, after, it, size)
                }
            },
            id = { it.id },
            score = { item ->
                val vector = RetrievalKit.decodeEmbedding(item.embedding ?: byteArrayOf())
                if (vector.size == qVec.size) RetrievalKit.cosineSimilarity(qVec, vector) else null
            },
        )
        if (vecRanked.isEmpty()) return lexical to "lexical_vec_empty"
        val fused = RetrievalKit.rrfFuse(vecRanked, lexical, limit = limit, keyOf = { it.id })
            .map { it.first }
        return fused to "hybrid"
    }

    private suspend fun kbStatus(): FunctionResult = withContext(Dispatchers.IO) {
        if (!workDir.hasDirectory()) return@withContext FunctionResult.failure(NOT_CONFIGURED_HINT)
        val docs = kbDao.loadAllDocs()
        if (docs.isEmpty()) return@withContext FunctionResult.success(
            "📚 知识库为空（knowledge/ 下尚无 .md）——save_to_file(file_name=\"knowledge/主题.md\", " +
                    "format=\"md\") 写入即自动索引")
        val chunks = runCatching { kbDao.countAllChunks() }.getOrDefault(0)
        val last = docs.maxOfOrNull { it.indexedAt } ?: 0L
        val timeText = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(last))
        // KB2.1 嵌入状态 + KA5 换档检测（当前模型块数 vs 总数）
        val embedLine: String = if (!(config as KnowledgeConfig).hybridEmbedding) {
            "向量混合：已关闭（纯本地词法，零云端调用）"
        } else if (!embeddingProvider.isAvailable()) {
            "向量混合：凭据缺失或鉴权未通过，请核对记忆模块「模型管理（向量嵌入）」；词法检索可用"
        } else {
            "向量混合：已启用；候选完成构建和召回验收后发布，期间词法检索可用"
        }
        FunctionResult.success(buildString {
            appendLine("📚 知识库：${docs.size} 篇 · $chunks 段 · 上次索引 $timeText · 词法检索恒就绪")
            appendLine(embedLine)
            vectorIndex.evaluationReport(VectorDomain.KNOWLEDGE)?.let { raw ->
                val report = JSONObject(raw)
                val metrics = report.getJSONObject("metrics")
                val scope = if (metrics.optString("scope") == "independent_benchmark") "独立基准" else "资料抽样"
                val verdict = if (metrics.getBoolean("accepted")) "通过" else "未通过，保留原索引"
                appendLine("召回验收（$scope）：$verdict；Recall@${metrics.getInt("limit")}=" +
                    "${report.getDouble("candidateRecall")}，${report.getInt("evaluatedSamples")}个样本；" +
                    "当前启用=${report.getBoolean("currentlyActive")}")
                if (metrics.has("vectorRecall")) appendLine("原始向量=${metrics.getDouble("vectorRecall")}；" +
                    "最终检索=${report.getDouble("candidateRecall")}；" +
                    "当前排序策略一致=${report.optBoolean("currentRankingPolicyMatches")}")
                if (scope == "独立基准") appendLine("基准成绩不代表个人资料召回质量；个人资料需另行抽样验收。")
            }
            val vectorState = vectorIndex.status(VectorDomain.KNOWLEDGE)
            appendLine("向量版本：${if (vectorState.active == null) "尚无验收发布版本，兼容读取旧向量" else "已发布"}；" +
                "候选 ${when (vectorState.candidateState) {
                    "BUILDING" -> "构建中"
                    "VALIDATING" -> "等待召回验收"
                    "READY" -> "等待发布"
                    else -> "无"
                }} ${vectorState.staged}/${vectorState.total}")
            val rebuildState = runCatching { candidateStore.job()?.state }.getOrNull()
            if (rebuildState != null) appendLine("最近重建：" + when (rebuildState) {
                "BUILDING" -> "准备候选中（中断后自动恢复）"
                "READY" -> "候选就绪，等待发布确认"
                "COMMITTED" -> "已发布"
                "CANCELLED" -> "已取消，保留原索引"
                else -> "未完成，保留原索引；可重试 kb_reindex"
            })
            appendLine("最近：")
            docs.sortedByDescending { it.indexedAt }.take(5).forEach {
                appendLine("- [knowledge/${it.relativePath}] ${it.title}（${it.chunkCount} 段）")
            }
        })
    }

    /**
     * kb_reindex：强制重读、准备有界候选并复核目录，整批事务发布；失败保留旧索引。
     * 单文档仅处理目标路径，全量处理全部源文件；后台执行。
     * 立即返回受理（doIndex 的 indexMutex 保证与事件/周期路串行，KD10）。
     */
    private suspend fun kbReindex(path: String?): FunctionResult {
        if (!workDir.hasDirectory()) return FunctionResult.failure(NOT_CONFIGURED_HINT)
        // 容错：允许带 knowledge/ 前缀
        val rel = path?.trim()
            ?.removePrefix("${KnowledgePaths.SUBDIR}/")
            ?.takeIf { it.isNotEmpty() }
        moduleScope.launch {
            runCatching {
                rebuildIndex(rel)
            }.onFailure {
                traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id,
                    "reindex_failed: ${it.message?.take(50)}")
            }
        }
        traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
            "reindex_accepted: ${rel ?: "全量"}")
        return FunctionResult.success("♻️ 重建已受理（${rel ?: "全量"}，后台执行）——稍后用 kb_status 查看")
    }

    internal suspend fun rebuildIndex(rel: String?) {
        doIndex(trigger = "reindex", force = true, onlyPath = rel)
    }

    // ═══ markdown 切分器（§六.1：标题分节 → 段落聚合 300–600 → 超长句切 → 围栏保护）═══

    /** 切分结果（title = 首个标题行文本，缺省文件基名） */
    private data class SplitResult(val title: String, val chunks: List<RawChunk>)
    private data class RawChunk(val seq: Int, val headingPath: String, val text: String)

    private fun splitMarkdown(content: String, defaultTitle: String): SplitResult {
        var title: String? = null
        var inFence = false
        val headingStack = arrayOfNulls<String>(6)
        val sections = mutableListOf<Pair<String, StringBuilder>>()  // headingPath → body
        var current = StringBuilder()

        content.lines().forEach { line ->
            val fence = line.trimStart().startsWith("```")
            if (fence) inFence = !inFence  // 开/闭切换（内容行不进此分支）
            val heading = if (!inFence && !fence) HEADING_REGEX.find(line) else null
            when {
                heading != null -> {
                    if (current.isNotBlank()) sections.add(currentHeading(headingStack) to current)
                    current = StringBuilder()
                    val level = heading.groupValues[1].length
                    val text = heading.groupValues[2].trim()
                    if (title == null) title = text
                    headingStack[level - 1] = text
                    for (i in level until 6) headingStack[i] = null  // 清子级
                }
                else -> current.appendLine(line)
            }
        }
        if (current.isNotBlank()) sections.add(currentHeading(headingStack) to current)

        // 节内块化 → 展平编号
        val chunks = sections.flatMap { (headingPath, body) ->
            chunkSection(headingPath, body.toString())
        }.mapIndexed { i, c -> RawChunk(i, c.first, c.second) }

        return SplitResult(title ?: defaultTitle, chunks)
    }

    /** 祖先标题链（如 "学习笔记 > 中医 > 方剂"） */
    private fun currentHeading(stack: Array<String?>): String =
        stack.filterNotNull().joinToString(" > ")

    /** 节内段落聚合：空行分段（围栏内不断）→ 聚合至 ≥300 出块 → 单段 >600 句切 */
    private fun chunkSection(headingPath: String, body: String): List<Pair<String, String>> {
        // 按行重组段落（围栏状态保护：``` 块内的空行不构成段边界）
        val paragraphs = mutableListOf<StringBuilder>()
        var sb = StringBuilder()
        var inFence = false
        body.lines().forEach { line ->
            if (line.trimStart().startsWith("```")) {
                inFence = !inFence
                sb.appendLine(line)
                return@forEach
            }
            if (!inFence && line.isBlank()) {
                if (sb.isNotBlank()) paragraphs.add(sb)
                sb = StringBuilder()
            } else sb.appendLine(line)
        }
        if (sb.isNotBlank()) paragraphs.add(sb)

        // 段落聚合（目标 300–600：聚合至 ≥TARGET_MIN 即出块）
        val blocks = mutableListOf<String>()
        val acc = StringBuilder()
        paragraphs.forEach { p ->
            val para = p.toString().trim()
            if (para.isEmpty()) return@forEach
            if (acc.isNotEmpty() && acc.length + para.length > CHUNK_TARGET_MAX) {
                blocks.add(acc.toString().trim()); acc.clear()
            }
            acc.append(if (acc.isEmpty()) para else "\n$para")
            if (acc.length >= CHUNK_TARGET_MIN) {
                blocks.add(acc.toString().trim()); acc.clear()
            }
        }
        if (acc.isNotEmpty()) blocks.add(acc.toString().trim())

        // 超长块句末标点硬切（保代码围栏完整仅在其作为单段超长时）
        return blocks.flatMap { b ->
            if (b.length <= CHUNK_TARGET_MAX) listOf(headingPath to b)
            else hardSplitBySentence(b).map { headingPath to it }
        }
    }

    /** 句末贪心聚合（≤CHUNK_TARGET_MAX） */
    private fun hardSplitBySentence(text: String): List<String> {
        val parts = SENTENCE_END.split(text).filter { it.isNotBlank() }
        val out = mutableListOf<String>()
        val acc = StringBuilder()
        parts.forEach { s ->
            if (acc.isNotEmpty() && acc.length + s.length > CHUNK_TARGET_MAX) {
                out.add(acc.toString()); acc.clear()
            }
            acc.append(s)
        }
        if (acc.isNotEmpty()) out.add(acc.toString())
        return out
    }
}
