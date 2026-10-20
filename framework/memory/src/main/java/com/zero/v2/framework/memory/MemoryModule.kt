package com.zero.v2.framework.memory

import com.zero.v2.core.common.*
import com.zero.v2.core.data.dao.MemoryDao
import com.zero.v2.core.data.dao.MemoryExtractionJobDao
import com.zero.v2.core.data.dao.UserGraphDao
import com.zero.v2.core.data.entity.MemoryEntity
import com.zero.v2.core.moduleapi.*
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import com.zero.v2.services.embedding.VectorIndexRuntime
import com.zero.v2.services.embedding.VectorDomain
import com.zero.v2.services.embedding.EmbeddingSpace
import com.zero.v2.services.embedding.spaceId
import com.zero.v2.services.embedding.EmbeddingProvider
import com.zero.v2.services.retrieval.RetrievalKit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 记忆管理模块 — Zero v2 Room 版本。
 *
 * 对标 Alife MemoryService + v1 MemoryModule。
 * v2.1: Mutex 保护所有写入，防止后台自动化与前台对话并发写入 Room。
 * v2.2: FTS5 表结构预配置（Phase 10 混合检索）。
 *
 * @property memoryDao Room MemoryDao（从 v1 移植）
 */
@Singleton
class MemoryModule @Inject constructor(
    private val memoryDao: MemoryDao,
    private val graphDao: UserGraphDao,

    private val traceManager: AgentTraceManager,
    private val embeddingProvider: EmbeddingProvider,
    private val scoringEngine: MemoryScoringEngine,
    /** R1：记忆提取作业 outbox（替代 pendingAiBuffer 易失队列） */
    private val extractionJobDao: MemoryExtractionJobDao,
    private val vectorIndex: VectorIndexRuntime,
    private val reranker: com.zero.v2.services.retrieval.BoundedSemanticReranker,
    private val classificationTools: MemoryClassificationTools,
    private val derivationStore: com.zero.v2.core.data.store.MemoryDerivationStore,
) : IZeroModule, com.zero.v2.core.moduleapi.ToolContributor, com.zero.v2.services.agent.AgentJob {

    override val id = "memory_vector_retry"
    override val nextRunTime: Long
        get() = if (isEnabled && lifecycle.value == ModuleLifecycle.STARTED) vectorIndex.retryAt(VectorDomain.MEMORY) else Long.MAX_VALUE

    override suspend fun execute() {
        if (isEnabled && lifecycle.value == ModuleLifecycle.STARTED) startEmbeddingBackfill()
    }

    override val metadata = ModuleMetadata(
        id = "memory",
        displayName = "记忆管理",
        description = "读取、写入和删除记忆。需要记住重要信息或回忆之前内容时使用。",
        category = ModuleCategory.CORE,
        launchOrder = 50
    )

    private val _lifecycle = MutableStateFlow(ModuleLifecycle.UNINITIALIZED)
    override val lifecycle = _lifecycle.asStateFlow()
    override var isEnabled: Boolean = true
    override val requiredPermissions: List<String> = emptyList()
    /** v4.8: 反射相关 ConfigField（5.2 扩展为完整 §3.4 配置） */
    override val config: MemoryModuleConfig = MemoryModuleConfig()

    /** v2.1: 写入互斥锁 */
    private val writeMutex = Mutex()
    private val classifiedReader = com.zero.v2.services.retrieval.ClassifiedMemoryReader(derivationStore)
    private val sourceReader = com.zero.v2.services.retrieval.MemoryEvidenceReader(memoryDao)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** v4.9: 自上次图谱提取以来的新记忆计数（GraphExtractionJob 触发依据，§3.3） */
    private val pendingGraphCount = java.util.concurrent.atomic.AtomicInteger(0)

    private suspend fun <T> withWriteLock(block: suspend () -> T): T {
        return writeMutex.withLock { block() }
    }

    override suspend fun onAwake() { _lifecycle.value = ModuleLifecycle.AWAKE }

    override suspend fun onStart() {
        _lifecycle.value = ModuleLifecycle.STARTED
        scope.launch {
            withWriteLock {
                val retired = extractionJobDao.retireAssistantSources(System.currentTimeMillis())
                val retiredUser = extractionJobDao.retireUserFactSources(System.currentTimeMillis())
                traceManager.record("mem_source", NodeType.SYSTEM_EVENT, metadata.id,
                    "source_recovery: retired=$retired retired_user_classifier=$retiredUser")
            }
            processPendingDerivations()
            derivationStore.sweep("", System.currentTimeMillis())
            startEmbeddingBackfill()
        }
    }

    override suspend fun onDestroy() {
        scope.cancel()
        _lifecycle.value = ModuleLifecycle.DESTROYED
    }
    override suspend fun onConfigChanged(newConfig: ModuleConfigBase) { startEmbeddingBackfill() }
    override suspend fun checkAvailability(): Boolean = true
    override fun markDisabled() { _lifecycle.value = ModuleLifecycle.DISABLED }

    // ═══════════════════════════════════════════════════════
    // FC 函数注册
    // ═══════════════════════════════════════════════════════

    override fun contributions(): List<com.zero.v2.core.moduleapi.ToolContribution> =
        com.zero.v2.core.moduleapi.toolDeclarations(metadata.id) {
        val functionRegistry = this
        functionRegistry.register(
            FunctionDefinition("memory_recall", "查记忆及来源。写前查重；更新来源绑定条目前取memoryId/version。无命中不编造，已撤回内容不作当前依据。",
                mapOf("type" to "object", "properties" to mapOf(
                    "query" to mapOf("type" to "string", "description" to "搜索关键词"),
                    "scope" to mapOf("type" to "string", "description" to "明确读取范围，默认personal；使用保存结果返回的scope，不跨范围召回"),
                    "top_k" to mapOf("type" to "number", "description" to "返回条数")
                ), "required" to listOf("query")), FunctionSecurity.SAFE
            ), metadata.id
        ) { args -> recall(args) }

        functionRegistry.register(
            FunctionDefinition("memory_forget", "停止使用匹配记忆，保留历史，已发送内容尾部撤回、换代整理，不得重新记回。仅用户明确彻底删除时purge=true。",
                mapOf("type" to "object", "properties" to mapOf(
                    "keyword" to mapOf("type" to "string", "description" to "具体记忆关键词，避免大范围误撤回"),
                    "purge" to mapOf("type" to "boolean", "description" to "默认false逻辑撤回；true永久删除及来源传播")
                ), "required" to listOf("keyword")), FunctionSecurity.SENSITIVE
            ), metadata.id
        ) { args -> forget(args) }
    } + classificationTools.contributions { scope.launch { processPendingDerivations() } }

    // ═══════════════════════════════════════════════════════
    // FC 实现（v2.1: withWriteLock 保护所有 Room 写入）
    // ═══════════════════════════════════════════════════════

    private suspend fun recall(args: Map<String, Any?>): FunctionResult = withContext(Dispatchers.IO) {
        val query = (args["query"] as? String ?: "").trim()
        val recallScope = if (args.containsKey("scope")) args["scope"] as? String else "personal"
        if (recallScope.isNullOrBlank() || recallScope.length > 100)
            return@withContext FunctionResult.failure("scope必须为非空范围名称，最长100字")
        val topK = ((args["top_k"] as? Number)?.toInt() ?: 5).coerceIn(1, 100)
        if (query.isBlank()) return@withContext FunctionResult.failure("搜索关键词不能为空")
        memoryDao.getById(query)?.let { exact ->
            if (exact.scope != recallScope) return@withContext FunctionResult.failure("记忆不在指定scope内；请明确使用保存时的scope。")
            if (exact.origin == "MODEL_CLASSIFIED") {
                val captured = classifiedReader.capture(exact.id, recallScope)
                val ticket = captured.ticket
                return@withContext if (ticket != null && classifiedReader.revalidate(ticket))
                    FunctionResult.success(MemoryHistoryLinks(memoryDao).attach(ticket.payload, exact))
                else classificationTools.conflictDiagnostics(query, recallScope, exact.id)?.let { FunctionResult.success(it) }
                    ?: classifiedHistory(exact, recallScope)?.let { FunctionResult.success(it) }
                    ?: FunctionResult.failure("该候选当前不能用作来源资料：${captured.reason}；历史管理记录保持。")
            }
            if (memoryDao.recallBlocked(exact.id)) return@withContext FunctionResult.failure("该记忆来源已删除，仅保留历史管理记录。")
            legacySourceEvidence(exact, recallScope)?.let { return@withContext FunctionResult.success(MemoryHistoryLinks(memoryDao).attach(it.content, exact)) }
            return@withContext FunctionResult.success(MemoryHistoryLinks(memoryDao).attach(org.json.JSONObject()
                .put("id", exact.id).put("content", exact.content).put("status", exact.status.name)
                .put("origin", exact.origin).put("recordedAt", exact.createdAt)
                .put("version", com.zero.v2.core.data.entity.MemoryConflictEntity.version(exact))
                .put("notice", if (exact.status == MemoryStatus.ACTIVE) "历史来源按origin核对" else "历史已停用，不作当前回答或操作依据")
                .put("sourceIds", org.json.JSONArray(memoryDao.summarySources(exact.id))).toString(), exact))
        }

        if (runCatching { com.zero.v2.core.data.entity.MemorySearchQuery.terms(query) }.isSuccess) {
            classificationTools.conflictDiagnostics(query, recallScope)?.let {
                return@withContext FunctionResult.success(it)
            }
        }

        // ═══ v4.6 混合检索: Embedding 主径 + 词法链，两路 RRF 融合 + ScoringEngine 排序 ═══
        val embedRanked = MemoryRecallCandidates.select(query, embeddingCandidates(query).filter { it.scope == recallScope })
        val lexicalRanked = lexicalCandidates(query).filter { it.scope == recallScope }
        // KB1.2（C4 纯搬家）：rrfFuse 已抽至 RetrievalKit（泛型化——keyOf 传 id，
        // 融合与排序语义同原版逐行一致）
        val fused = RetrievalKit.rrfFuse(embedRanked, lexicalRanked,
            limit = com.zero.v2.services.retrieval.RecentRecallWindow.limit(query,
                maxOf(topK * 2, com.zero.v2.services.retrieval.BoundedSemanticReranker.MAX_CANDIDATES)),
            keyOf = MemoryRecallCandidates::key)

        val scored = if (fused.isNotEmpty()) {
            val rrfMax = fused.first().second
            val rrfById = fused.associate { it.first.id to it.second }
            scoringEngine.rank(
                fused.map { it.first },
                relevanceOf = { m -> if (rrfMax > 0f) (rrfById[m.id] ?: 0f) / rrfMax else 0f }
            )
        } else emptyList()
        val window = com.zero.v2.services.retrieval.RecentRecallWindow.arrange(query, scored, { it.id }, {
            com.zero.v2.services.retrieval.RecentRecallWindow.date(it.content)
        })
        val (reordered, rerankStatus) = if (window.any { it.origin == "MODEL_CLASSIFIED" }) {
            // The main dialogue reads the complete sources; do not send its candidate summaries to another LLM.
            window to "main_dialogue_sources"
        } else reranker.rank(query, window, { it.id }, { it.content })
        val ranked = if (rerankStatus in setOf("applied", "cache_hit", "not_needed", "main_dialogue_sources")) reordered else scored
        val results = withWriteLock { memoryDao.recordCurrentRecall(ranked, topK) }

        traceManager.record("mem_recall", NodeType.SYSTEM_EVENT, metadata.id,
            "recall_fused: embed=${embedRanked.size} lexical=${lexicalRanked.size} " +
                    "fused=${fused.size} top=${results.size} rerank=$rerankStatus " +
                    "path=${if (embedRanked.isNotEmpty()) "hybrid" else "lexical"}")

        // 兜底: 两路皆空 → 最近 Tier 2 长期事实（v3.6 降级链保留）
        if (results.isEmpty()) {
            val tier2Facts = try {
                MemoryRecallCandidates.select(query, memoryDao.getByTier(2)
                    .filter { it.scope == recallScope && it.status == com.zero.v2.core.common.MemoryStatus.ACTIVE && !memoryDao.recallBlocked(it.id) }
                    .sortedByDescending { it.createdAt }).take(3)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { emptyList() }
            if (tier2Facts.isNotEmpty()) {
                traceManager.record("mem_recall", NodeType.SYSTEM_EVENT, metadata.id,
                    "recall_fallback_tier2: query=$query facts=${tier2Facts.size}")
                return@withContext FunctionResult.success(
                    "未找到与 \"$query\" 直接匹配的记忆。以下是可能相关的历史记录:\n" +
                            formatRecallRecords(tier2Facts, recallScope))
            }
            return@withContext FunctionResult.success("未找到与 \"$query\" 相关的记忆。")
        }

        FunctionResult.success(
            "关于 \"$query\" 的相关记忆（${results.size} 条）:\n" +
            formatRecallRecords(results, recallScope)
        )
    }

    private suspend fun classifiedHistory(memory: MemoryEntity, scope: String): String? {
        val ticket = classifiedReader.captureHistory(memory.id, scope).ticket ?: return null
        if (classifiedReader.captureHistory(memory.id, scope).ticket != ticket) return null
        return MemoryHistoryLinks(memoryDao).attach(ticket.payload, memory)
    }

    private suspend fun formatRecallRecords(memories: List<MemoryEntity>, recallScope: String): String = buildString {
        append(MemoryRecallFormatter.NOTICE).append('\n')
        for (memory in memories) {
            val ticket = if (memory.origin == "MODEL_CLASSIFIED") classifiedReader.capture(memory.id, recallScope).ticket else null
            if (memory.origin == "MODEL_CLASSIFIED" && (ticket == null || !classifiedReader.revalidate(ticket))) continue
            val payload = ticket?.payload ?: legacySourceEvidence(memory, recallScope)?.content
            val line = "- ${MemoryRecallFormatter.format(memory, payload)}\n"
            val output = if (length + line.length <= 16000) line else
                "- [memory/${memory.id}] 本条完整资料未展示，不得推断内容；请按此ID调用memory_recall读取。\n"
            if (length + output.length <= 16000) append(output)
        }
    }

    /** C5 staged path: no automatic caller until the independent main-dialogue quality gate passes. */
    suspend fun qualifiedEvidenceCandidates(query: String, limit: Int = 3): List<EvidenceRef> = withContext(Dispatchers.IO) {
        if (!isEnabled || query.isBlank() || limit <= 0) return@withContext emptyList()
        lexicalCandidates(query).take(16).mapNotNull { memory ->
            if (memory.origin != "MODEL_CLASSIFIED") return@mapNotNull null
            val ticket = classifiedReader.capture(memory.id).ticket ?: return@mapNotNull null
            if (!classifiedReader.revalidate(ticket)) return@mapNotNull null
            EvidenceRef(EvidenceDomain.MEMORY, memory.id, ticket.payload, "memory/${memory.id}",
                bodyDigest = com.zero.v2.services.retrieval.EvidencePolicy.bodyDigest(ticket.text),
                completeSource = true, sourceVersion = com.zero.v2.services.retrieval.EvidencePolicy.bodyDigest(ticket.payload))
        }.take(limit.coerceAtMost(3))
    }

    suspend fun revalidateQualifiedEvidence(ref: EvidenceRef): Boolean {
        if (!isEnabled || !ref.completeSource || ref.domain != EvidenceDomain.MEMORY) return false
        val ticket = classifiedReader.capture(ref.id).ticket ?: return false
        return ticket.payload == ref.content &&
            com.zero.v2.services.retrieval.EvidencePolicy.bodyDigest(ticket.payload) == ref.sourceVersion &&
            classifiedReader.revalidate(ticket)
    }

    private suspend fun forget(args: Map<String, Any?>): FunctionResult = withWriteLock {
        val keyword = (args["keyword"] as? String ?: "").trim()
        if (keyword.isBlank()) return@withWriteLock FunctionResult.failure("请指定要删除的记忆关键词")

        val purge = args["purge"] ?: false
        if (purge !is Boolean) return@withWriteLock FunctionResult.failure("purge必须为布尔值")
        if (purge) {
            val deleted = withContext(Dispatchers.IO) { extractionJobDao.forgetMatching(keyword, System.currentTimeMillis()) }
            FunctionResult.success("已永久删除 $deleted 条匹配记忆及其来源副本；已发送历史待换代整理。")
        } else {
            try {
                val withdrawn = classificationTools.withdraw(keyword)
                FunctionResult.success("已停止使用 $withdrawn 条匹配记忆/待办，保留来源历史；旧上下文将追加撤回通知，换代再整理。")
            } catch (e: IllegalArgumentException) {
                FunctionResult.failure("撤回未执行：${e.message}；请缩小关键词范围。")
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    // 召回与公开 API
    // ═══════════════════════════════════════════════════════

    /**
     * 语义检索 — v4.6-pre (D31) 起 Embedding 余弦为主径。
     * Provider 不可用 / 无命中 → 降级 keyword 链。
     */
    suspend fun semanticSearch(query: String, topK: Int = 10): List<MemoryEntity> {
        return withContext(Dispatchers.IO) {
            val ranked = embeddingCandidates(query).filterNot { memoryDao.recallBlocked(it.id) }
            if (ranked.isNotEmpty()) {
                traceManager.record("memory_embedding", NodeType.SYSTEM_EVENT, metadata.id,
                    "semantic_search: path=embedding hits=${ranked.size}")
                ranked.take(topK).forEach { memoryDao.recordAccess(it.id) }
                return@withContext ranked.take(topK)
            }
            // 降级链（D31）: keyword
            traceManager.record("memory_embedding", NodeType.SYSTEM_EVENT, metadata.id,
                "semantic_search: path=keyword_fallback query=${query.take(30)}")
            memoryDao.searchActiveByKeyword(query).filterNot { memoryDao.recallBlocked(it.id) }.take(topK).also { results ->
                results.forEach { memoryDao.recordAccess(it.id) }
            }
        }
    }

    /** 路1: Embedding 余弦候选（D31 主径；Provider 不可用返回空）。
     *  R2：有界候选——按当前模型 + 重要性预筛 LIMIT cap（替代全表扫描，L-7 债收口）。
     *  模型不匹配的旧向量被 SQL 排除（embed_model 过滤）→ 维度/模型不匹配走词法降级（recall 降级链）。 */
    private suspend fun embeddingCandidates(query: String): List<MemoryEntity> =
        unfilteredEmbeddingCandidates(query).filterNot { memoryDao.recallBlocked(it.id) }

    private suspend fun unfilteredEmbeddingCandidates(query: String): List<MemoryEntity> {
        vectorIndex.search(VectorDomain.MEMORY, query, RetrievalKit.DEFAULT_CANDIDATE_CAP)?.let { entries ->
            return entries.mapNotNull { entry ->
                memoryDao.getById(entry.sourceId)?.takeIf { it.status == MemoryStatus.ACTIVE && it.content == entry.sourceText }
            }
        }
        // Until the first validated version exists, legacy vectors remain readable, never overwritten.
        val model = embeddingProvider.spaceId(EmbeddingSpace.MEMORY)
        val qVec = try { embeddingProvider.embedQuery(query) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { null } ?: return emptyList()
        if (qVec.isEmpty() || model != embeddingProvider.spaceId(EmbeddingSpace.MEMORY)) return emptyList()
        return com.zero.v2.services.retrieval.PagedSemanticSearch.search(
            limit = RetrievalKit.DEFAULT_CANDIDATE_CAP,
            page = { after, size -> memoryDao.embeddingPage(model, after, size) },
            id = { it.id },
            score = { item ->
                val vector = RetrievalKit.decodeEmbedding(item.embedding ?: byteArrayOf())
                if (vector.size == qVec.size) RetrievalKit.cosineSimilarity(qVec, vector) else null
            },
        )
    }

    /**
     * R3：记忆域证据候选——词法路（lexicalCandidates，快速本地零网络）→ EvidenceRef。
     *
     * 自动证据注入用词法（避免每回合 embedding API 调用）；语义向量召回走 memory_recall FC（按需）。
     * 只读短引用，不替代 memory_recall FC，不改变写入域所有权（设计§5.1）。
     */
    suspend fun evidenceCandidates(query: String, limit: Int = 5): List<com.zero.v2.core.common.EvidenceRef> =
        withContext(Dispatchers.IO) {
            if (!isEnabled || query.isBlank() || limit <= 0) return@withContext emptyList()
            val lexicalRanked = lexicalCandidates(query)
            if (lexicalRanked.isEmpty()) return@withContext emptyList()
            lexicalRanked.take(limit.coerceAtMost(100)).mapNotNull { legacySourceEvidence(it) }
        }

    /** Project only still-live sources from a validated published index; no provider request here. */
    suspend fun semanticEvidenceCandidates(
        query: String, entries: List<com.zero.v2.core.data.entity.VectorEntryEntity>,
    ): List<com.zero.v2.core.common.EvidenceRef> = withContext(Dispatchers.IO) {
        if (!isEnabled) return@withContext emptyList()
        val live = entries.mapNotNull { entry ->
            memoryDao.getById(entry.sourceId)?.takeIf {
                it.status == com.zero.v2.core.common.MemoryStatus.ACTIVE && it.content == entry.sourceText &&
                    !memoryDao.recallBlocked(it.id)
            }
        }
        MemoryRecallCandidates.select(query, live).mapNotNull { legacySourceEvidence(it) }
    }

    /** Never inject an unverified summary as evidence, nor bypass the staged MODEL_CLASSIFIED gate. */
    private suspend fun legacySourceEvidence(memory: MemoryEntity, requestedScope: String = "personal"): EvidenceRef? {
        val ticket = sourceReader.capture(memory.id).ticket ?: return null
        if (ticket.scope != requestedScope) return null
        val decision = sourceReader.revalidate(ticket, com.zero.v2.services.retrieval.EvidenceSupport.Use.CERTAIN,
            com.zero.v2.services.retrieval.EvidenceSupport.QueryScope(System.currentTimeMillis()))
        if (decision.reason != "semantic_judgment") return null
        val confirmation = memoryDao.readEvidenceSnapshot(memory.id)?.confirmation
        val sourceId = confirmation?.let { classificationTools.confirmedSource(it) }
        val data = org.json.JSONObject().put("sourceBody", ticket.text).put("scope", ticket.scope)
            .put("sourceId", sourceId ?: org.json.JSONObject.NULL).put("recordedAt", memory.createdAt)
            .put("memoryVersion", com.zero.v2.core.data.entity.MemoryConflictEntity.version(memory))
            .put("validFrom", memory.validFrom ?: org.json.JSONObject.NULL)
            .put("validUntil", memory.validUntil ?: org.json.JSONObject.NULL)
            .put("notice", "仅来源已核验，不等于问题支持；依据完整原文保留主体、否定、时间与条件。").toString()
        return EvidenceRef(EvidenceDomain.MEMORY, memory.id, data, "memory/${memory.id}",
            bodyDigest = com.zero.v2.services.retrieval.EvidencePolicy.bodyDigest(ticket.text), completeSource = true,
            sourceVersion = com.zero.v2.services.retrieval.EvidencePolicy.bodyDigest(data))
    }

    suspend fun revalidateSourceEvidence(ref: EvidenceRef): Boolean {
        if (!isEnabled || !ref.completeSource || ref.domain != EvidenceDomain.MEMORY) return false
        val memory = memoryDao.getById(ref.id) ?: return false
        val fresh = legacySourceEvidence(memory) ?: return false
        return fresh.content == ref.content && fresh.sourceVersion == ref.sourceVersion
    }

    /** 路2: 词法链候选 — 整词/分词 LIKE → bigram 滑窗（中文无分词降级） */
    private suspend fun lexicalCandidates(query: String): List<MemoryEntity> =
        unfilteredLexicalCandidates(query).filterNot { memoryDao.recallBlocked(it.id) }

    private suspend fun unfilteredLexicalCandidates(query: String): List<MemoryEntity> {
        val keywords = query.split(LEXICAL_SEPARATOR).filter { it.length >= 2 }
        val kwResults = if (keywords.size > 1) {
            keywords.flatMap { kw -> memoryDao.searchActiveByKeyword(kw) }
                .distinctBy { it.id }
                .sortedByDescending { it.importance }
        } else {
            memoryDao.searchActiveByKeyword(query)
        }
        val eligible = MemoryRecallCandidates.select(query, kwResults)
        if (eligible.isNotEmpty()) return eligible

        // bigram 滑窗（命中数 ≈ 相关度）——KB1.2 抽至 RetrievalKit.queryBigrams（同式搬家）
        val bigrams = RetrievalKit.queryBigrams(query)
        val ranked = memoryDao.searchActiveKeywordGroups(bigrams).flatten()
            .groupBy { it.id }
            .map { (_, list) -> list.first() to list.size }
            .sortedWith(compareByDescending<Pair<MemoryEntity, Int>> { it.second }
                .thenByDescending { it.first.importance })
            .map { it.first }
        return MemoryRecallCandidates.select(query, ranked)
    }

    // KB1.2（C4 纯搬家）：cosineSimilarity / rrfFuse（泛型化）/ encode·decodeEmbedding /
    // queryBigrams 五个检索原语已抽至 services/retrieval/RetrievalKit——本模块与
    // KnowledgeModule（知识库轨）共用同一检索底座；此处行为零变化（SR5 回归判据）。

    // ═══════════════════════════════════════════════════════
    // v4.6-pre: Embedding 写入与回填（D31 检索地基）
    // ═══════════════════════════════════════════════════════

    /** All sources, including compressed summaries, share the persisted candidate builder. */
    private fun startEmbeddingBackfill() {
        scope.launch {
            if (!isEnabled || lifecycle.value != ModuleLifecycle.STARTED) return@launch
            try {
                val outcome = vectorIndex.build(VectorDomain.MEMORY)
                traceManager.record("memory_embedding", NodeType.SYSTEM_EVENT, metadata.id,
                    "vector_candidate: state=$outcome")
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                traceManager.record("memory_embedding", NodeType.ERROR, metadata.id,
                    "vector_candidate_failed: ${failure.javaClass.simpleName}")
            }
        }
    }

    internal fun onDerivationViewsRebuilt() { startEmbeddingBackfill() }

    /**
     * v4.2: 语义相似检索（D31 — 余弦在 Kotlin 层计算，非 SQL）。
     *
     * 对指定记忆在全量 ACTIVE 向量中找余弦相似 ≥ minSimilarity 的邻居。
     * Embedding 由 Step 4.6-pre 写入——落地前本方法恒返回空列表（接口先行，
     * 4.5 冲突检测 / 4.6 混合检索在 4.6-pre 后自动获得数据）。
     */
    suspend fun findSimilar(id: String, minSimilarity: Float = 0.8f, limit: Int = 3): List<MemoryEntity> {
        return withContext(Dispatchers.IO) {
            if (memoryDao.recallBlocked(id)) return@withContext emptyList()
            vectorIndex.similar(VectorDomain.MEMORY, id, minSimilarity, limit)?.let { entries ->
                return@withContext entries.mapNotNull { entry ->
                    memoryDao.getById(entry.sourceId)?.takeIf {
                        it.status == MemoryStatus.ACTIVE && it.content == entry.sourceText && !memoryDao.recallBlocked(it.id)
                    }
                }
            }
            val targetMemory = memoryDao.getById(id) ?: return@withContext emptyList()
            if (targetMemory.embedModel.isBlank() || targetMemory.embedModel != embeddingProvider.spaceId(EmbeddingSpace.MEMORY)) return@withContext emptyList()
            val target = targetMemory.embedding?.let { RetrievalKit.decodeEmbedding(it) }
                ?: return@withContext emptyList()
            if (target.isEmpty()) return@withContext emptyList()

            memoryDao.getActiveWithEmbeddingBounded(
                model = targetMemory.embedModel, excludeId = id, limit = RetrievalKit.DEFAULT_CANDIDATE_CAP
            ).filterNot { memoryDao.recallBlocked(it.id) }
                .mapNotNull { candidate ->
                    val vec = RetrievalKit.decodeEmbedding(candidate.embedding ?: return@mapNotNull null)
                    if (vec.size != target.size) return@mapNotNull null
                    RetrievalKit.cosineSimilarity(target, vec) to candidate
                }
                .filter { it.first >= minSimilarity }
                .sortedByDescending { it.first }
                .take(limit)
                .map { it.second }
        }
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) { memoryDao.count() }

    // ═══ v4.5: 冲突检测供数与解决原语（MemoryConflictDetector 调用）═══

    /** 当前事实、偏好及规范供冲突候选检测；自动归档仍仅允许同来源精确事实重复。 */
    suspend fun activeConflictCandidates(): List<MemoryEntity> = withContext(Dispatchers.IO) {
        try { listOf(MemoryType.FACT, MemoryType.PREFERENCE, MemoryType.DIRECTIVE)
            .flatMap { memoryDao.getByType(it) }.filterNot { it.origin == "MODEL_CLASSIFIED" || memoryDao.recallBlocked(it.id) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { emptyList() }
    }

    /** 归档（KEEP_NEWER 旧版处理；§3.1 T2 生命周期"旧版本归档"） */
    suspend fun consolidateDuplicate(kept: MemoryEntity, removed: MemoryEntity): Boolean = withWriteLock {
        MemoryConflictPolicy.canConsolidate(kept, removed) && memoryDao.archiveDuplicate(kept, removed)
    }

    suspend fun recordConflict(a: MemoryEntity, b: MemoryEntity, judgment: String) = withWriteLock {
        memoryDao.insertConflict(com.zero.v2.core.data.entity.MemoryConflictEntity.candidate(a, b, judgment, System.currentTimeMillis()))
    }

    suspend fun hasConflictDecision(a: MemoryEntity, b: MemoryEntity): Boolean =
        memoryDao.conflict(com.zero.v2.core.data.entity.MemoryConflictEntity.candidate(a, b, "", 0).id) != null

    suspend fun contextMemories(ids: List<String>): List<com.zero.v2.services.retrieval.ContextMemory> {
        require(ids.size <= 64)
        val now = System.currentTimeMillis()
        val legacy = (memoryDao.importantMemories(now) + memoryDao.currentMemories(ids, now)).distinctBy { it.id }.filter {
            it.scope == "personal" && contextEligible(it)
        }.map {
            com.zero.v2.services.retrieval.ContextMemory(it.id,
                com.zero.v2.core.data.entity.MemoryConflictEntity.version(it), memoryContextText(it), it.type.name, it.createdAt, it.accessCount)
        }
        val managed = (memoryDao.managedContextCandidates("personal", now, TaskMemory.header("ACTIVE"), TaskMemory.header("WAITING")) +
            ids.mapNotNull { memoryDao.getById(it) }).distinctBy { it.id }.filter {
            it.origin == "MODEL_CLASSIFIED" && it.scope == "personal" && contextEligible(it)
        }.mapNotNull { memory ->
            val ticket = classifiedReader.capture(memory.id, "personal", now).ticket ?: return@mapNotNull null
            if (!classifiedReader.revalidate(ticket)) return@mapNotNull null
            com.zero.v2.services.retrieval.ContextMemory(memory.id,
                com.zero.v2.core.data.entity.MemoryConflictEntity.version(memory),
                "[主Agent记录，非语义认证；source=${ticket.sourceId}]\n${memory.content}", memory.type.name, memory.createdAt, memory.accessCount)
        }
        return legacy + managed
    }

    private fun contextEligible(memory: MemoryEntity): Boolean = com.zero.v2.services.retrieval.ContextMemoryPolicy.accepts(
        memory.type, memory.content, memory.importance, memory.accessCount)

    suspend fun archiveMemory(id: String) {
        withContext(Dispatchers.IO) { memoryDao.updateStatus(id, MemoryStatus.ARCHIVED) }
    }

    /** 降权（KEEP_BOTH_DOWNGRADE），下限 0 */
    suspend fun downgradeConfidence(id: String, delta: Float) {
        withContext(Dispatchers.IO) { memoryDao.downgradeConfidence(id, delta) }
    }

    /**
     * v4.11: 过期归档（90 天未访问的 ACTIVE → ARCHIVED），返回归档条数。
     * 5.2 接 ConfigField（archiveAfterDays）。
     */
    suspend fun archiveExpired(days: Long = 90): Int = withContext(Dispatchers.IO) {
        val expired = try {
            memoryDao.getExpired(System.currentTimeMillis() - days * 86_400_000L)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { emptyList() }
        expired.forEach { memoryDao.updateStatus(it.id, MemoryStatus.ARCHIVED) }
        if (expired.isNotEmpty()) {
            traceManager.record("mem_archive", NodeType.SYSTEM_EVENT, metadata.id,
                "archive_expired: days=$days count=${expired.size}")
        }
        expired.size
    }

    /** 上次图衰减时间（进程内，初始化为 now — 重启推迟衰减：宁可少衰减，不可多衰减） */
    private var lastGraphDecayAt = System.currentTimeMillis()

    /**
     * v4.11 (D25): 图属性每月未确认衰减 0.05。
     * 间隔 ≥30 天才执行；作用于当前有效且 effectiveFrom 早于 30 天的属性（下限 0）。
     * "再确认"机制: GraphExtractionJob 重新提取会写新行刷新 effectiveFrom，衰减自然复位。
     */
    suspend fun updateGraphConfidence(): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (now - lastGraphDecayAt < DECAY_INTERVAL_MS) return@withContext 0
        lastGraphDecayAt = now
        try {
            val rows = graphDao.decayCurrentConfidence(now - DECAY_INTERVAL_MS, 0.05f)
            if (rows > 0) {
                traceManager.record("mem_graph", NodeType.SYSTEM_EVENT, metadata.id,
                    "graph_confidence_decayed: rows=$rows delta=0.05")
            }
            rows
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { 0 }
    }

    private companion object {
        private val LEXICAL_SEPARATOR = Regex("[\\s，。、]+")
        /** v4.11 (D25): 衰减间隔 = 30 天 */
        const val DECAY_INTERVAL_MS = 30L * 86_400_000
    }

    /** Materialize only explicit tool submissions; no secondary classifier or inferred history edits. */
    suspend fun processPendingDerivations(): Int {
        if (!isEnabled || lifecycle.value != ModuleLifecycle.STARTED) return 0
        val count = derivationStore.drain()
        if (count > 0) startEmbeddingBackfill()
        return count
    }

    /** R1：有界保留——清已完结作业（SUCCEEDED/FAILED/SUPERSEDED）早于 before。供周期清理调用。 */
    suspend fun purgeCompletedJobs(beforeDays: Int = 7) = withContext(Dispatchers.IO) {
        val before = System.currentTimeMillis() - beforeDays * 86_400_000L
        extractionJobDao.purgeCompleted(before)
    }

    /** v4.9: 图谱提取触发计数（GraphExtractionJob 读取，§3.3） */
    val graphPendingCount: Int get() = pendingGraphCount.get()
    fun resetGraphCount() = pendingGraphCount.set(0)

    /**
     * 提炼结果入库（ReflectionJob / 未来 GraphExtractionJob 调用）。
     * 无用户来源的提炼结果仅作为 EVENT 笔记；含向量调度与关键词。
     */
    suspend fun insertExtracted(content: String, tier: Int = 2, confidence: Float = 0.5f) = withWriteLock {
        if (content.isBlank()) return@withWriteLock
        val id = UUID.randomUUID().toString()
        withContext(Dispatchers.IO) {
            memoryDao.insert(MemoryEntity(
                id = id,
                content = content,
                importance = if (tier == 2) 0.7f else 0.5f,
                tier = tier,
                type = MemoryType.EVENT, // Model-authored note, not independently verified user testimony.
                origin = "MODEL_NOTE",
                status = MemoryStatus.ACTIVE,
                confidence = confidence,
                keywords = extractKeywords(content)
            ))
        }
        startEmbeddingBackfill()
        pendingGraphCount.incrementAndGet()
    }

    private fun extractKeywords(content: String): String {
        val stopWords = setOf("的","是","在","了","和","就","都","而","及","the","is","are","a","an","in","of","to")
        return content.split(Regex("""[\s，。！？,.!?()（）\n]+"""))
            .filter { it.length >= 2 && it.lowercase() !in stopWords }
            .groupBy { it }.mapValues { it.value.size }.entries
            .sortedByDescending { it.value }.take(8)
            .joinToString(",") { it.key }
    }
}
