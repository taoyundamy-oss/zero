package com.zero.v2.services.retrieval

import com.zero.v2.core.common.EvidenceRef

/**
 * 证据提供方 — R3（设计§5.1 / §R3）：跨域融合 Memory+Knowledge 检索结果为 ≤K 条短证据（带来源），
 * 供 ChatBot 注入 prompt turnContext（C1 槽位，不入 stablePrefix，缓存稳定）。
 *
 * **只封装检索结果的短引用，不改变原所有权**（memories/kb_chunks 写入域不变；与 memory_recall/
 * kb_search FC 并存——FC 是 LLM 按需主动召回，EvidenceProvider 是回合入口自动少量注入）。
 *
 * 实现在 app（注入 MemoryModule+KnowledgeModule 跨域）；ChatBot 注入本接口（services，framework/chat 可见）。
 */
interface EvidenceProvider {
    /** Includes current versions of previously selected IDs; missing IDs must be withdrawn. */
    suspend fun contextMemories(knownIds: List<String>): List<ContextMemory> = emptyList()
    /**
     * 按查询返回 top-K 证据（带来源署名 + 融合分）；无相关 → 空（不伪造，不注入）。
     * @param limit 证据条数上限（默认 3，设计§R3 "≤3 条短证据"）
     */
    suspend fun evidenceFor(query: String, limit: Int = 3): List<EvidenceRef>
    /** Immediately before formatting; implementations with source bindings must reread them. */
    suspend fun revalidate(refs: List<EvidenceRef>): List<EvidenceRef> = refs
}

/**
 * R3 纯逻辑：证据选型 + 格式化（JVM 可测，无模块依赖）。
 *
 * - [selectTopK]：跨域 RRF 融合 Memory+Knowledge 候选 → top-K（回写融合分为 score）。
 * - [formatBlock]：格式化证据块（≤3 条 + 来源署名 + 正文截断 + 预算封顶），空 → null（不注入）。
 */
object EvidencePolicy {
    private const val BYTE_MASK = 255
    private const val NIBBLE_MASK = 15
    private const val NIBBLE_BITS = 4
    /** Hash the full body, never the truncated citation or generated provenance label. */
    fun bodyDigest(body: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8))
        val digits = "0123456789abcdef"
        return buildString(bytes.size * 2) {
            for (byte in bytes) {
                val value = byte.toInt() and BYTE_MASK
                append(digits[value ushr NIBBLE_BITS]); append(digits[value and NIBBLE_MASK])
            }
        }
    }
    /** 单条证据正文截断字数（短引用，非全文）。 */
    const val PER_REF_CONTENT_CAP = 200
    /** 证据块总字数上限（≈200 token，在 C2 evidenceMax ≤8% 预算内；≤3 条短证据不触顶）。 */
    const val BLOCK_MAX_CHARS = 800
    /** 证据条数上限（设计§R3"≤3 条短证据"）。 */
    const val MAX_REFS = 3

    /** 跨域RRF先取top-K，再压缩相同全文；不补入较低排名的潜在无关正文。 */
    fun selectTopK(
        memoryCandidates: List<EvidenceRef>,
        knowledgeCandidates: List<EvidenceRef>,
        k: Int = MAX_REFS,
    ): List<EvidenceRef> =
        RetrievalKit.rrfFuseEvidence(memoryCandidates, knowledgeCandidates, limit = k.coerceAtLeast(0))
            .distinctBy { (ref, _) -> if (ref.completeSource) "${ref.key()}:${ref.sourceVersion}" else
                ref.bodyDigest?.takeIf { it.isNotBlank() }?.let { "body:$it" } ?: ref.key() }
            .map { it.first.copy(score = it.second) }

    /**
     * 格式化证据块（供 turnContext 注入）：≤3 条，每条附来源署名 + 正文截断；总字数 ≤ [maxChars]。
     * 空 refs 或无完整条目装下 → null（不注入，不伪造）。RAG 变化入 turnContext 不入 stablePrefix（缓存稳定）。
     */
    @Suppress("ReturnCount")  // 空→null / 无完整条目装下→null / 正常返回——校验 early-exit 模式
    fun formatBlock(refs: List<EvidenceRef>, maxChars: Int = BLOCK_MAX_CHARS): String? {
        if (refs.isEmpty()) return null
        val sb = StringBuilder("[相关证据]\n")
        var added = 0
        refs.take(MAX_REFS).forEach { ref ->
            val content = if (ref.completeSource) ref.content else ref.content.take(PER_REF_CONTENT_CAP)
            val full = "[来源：${ref.sourceRef}] $content\n"
            val line = if (ref.completeSource && sb.length + full.length > maxChars)
                "[来源：${ref.sourceRef}] 资料未展开，不得据此作答；请调用memory_recall，query=${ref.id}读取完整来源。\n" else full
            if (sb.length + line.length <= maxChars) { sb.append(line); added++ }
        }
        if (added == 0) return null  // 无完整条目装下（maxChars 极小）→ 不注入
        return sb.toString().trimEnd().take(maxChars)
    }
}
