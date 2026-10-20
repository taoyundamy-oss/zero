package com.zero.v2.services.retrieval

import com.zero.v2.core.common.EvidenceRef

/**
 * 检索底座纯函数集 — KB1.2（KD2：自 MemoryModule 抽取，framework/app 双端共用——
 * framework/memory 已 implementation(:services)、app→services 合法，零 gradle 改动）。
 *
 * C4 纯搬家纪律：函数体原样迁移，**除 rrfFuse 泛型化（按 keyOf 计分）外行为零变化**；
 * 回归判据 = memory_recall trace path（hybrid/lexical）与切换前一致（SR5）。
 *
 * R2 扩展：[DEFAULT_CANDIDATE_CAP] 有界候选预筛上限 + [rrfFuseEvidence] 跨域证据 RRF（mem/kb
 * 按域前缀 key 去重，写入域不合并）。
 */
object RetrievalKit {
    private val BIGRAM_IGNORED = Regex("[\\s，。、？?！!的了吗呢]")

    /** RRF 融合常数（标准倒数排名融合 K=60——Memory 同值不调参，G4） */
    const val RRF_K = 60

    /**
     * 有界候选预筛上限（R2）——Memory/KB 向量路 SQL LIMIT 的默认候选数。
     *
     * 个人 Agent 记忆/知识库量级（典型 <1k~10k 条）下，按状态/模型/重要性/时间预筛 100 条再
     * 余弦重排，峰值工作集受 cap 约束、不随全表规模膨胀（替代旧全表余弦 L-7 债）。10k 样本仍
     * 不达设备预算才独立评估 sqlite-vec/倒排索引（R2 决策门槛，不得直接导入主干）。
     */
    const val DEFAULT_CANDIDATE_CAP = 100

    /** Double累加避免有限Float向量平方溢出/下溢；非有限结果由分页检索排除。 */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float = when {
        a.size != b.size || a.isEmpty() -> 0f
        a.any { !it.isFinite() } || b.any { !it.isFinite() } -> Float.NaN
        else -> finiteCosine(a, b)
    }

    private fun finiteCosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) {
            val x = a[i].toDouble(); val y = b[i].toDouble()
            dot += x * y; na += x * x; nb += y * y
        }
        val denom = kotlin.math.sqrt(na) * kotlin.math.sqrt(nb)
        return if (denom > 0.0) (dot / denom).coerceIn(-1.0, 1.0).toFloat() else 0f
    }

    /**
     * RRF 倒数排名融合: score(d) = Σ 1/(K + rank)。
     * KB1.2 泛型化（C4 唯一签名变化）：按 keyOf 计分——Memory 传 { it.id }、知识库传
     * chunk id；融合与排序语义与原 MemoryEntity 版逐行一致。
     */
    fun <T> rrfFuse(vararg rankings: List<T>, limit: Int, keyOf: (T) -> String): List<Pair<T, Float>> {
        val scores = HashMap<String, Float>()
        val byKey = HashMap<String, T>()
        rankings.forEach { list ->
            list.forEachIndexed { i, item ->
                val k = keyOf(item)
                scores.merge(k, 1f / (RRF_K + i + 1), Float::plus)
                byKey[k] = item
            }
        }
        return scores.entries
            .mapNotNull { e -> byKey[e.key]?.let { it to e.value } }
            .sortedByDescending { it.second }
            .take(limit)
    }

    /**
     * 跨域证据 RRF 融合（R2）：Memory + Knowledge 候选统一为 [EvidenceRef] 后按域前缀 key 去重融合。
     *
     * mem:id 与 kb:id 视为不同证据（不撞键）——**写入域不合并**（memories/kb_chunks schema 独立），
     * 仅读取侧统一。score(d) = Σ 1/(K + rank)，与 [rrfFuse] 同式（keyOf = EvidenceRef::key）。
     * 供 R3 EvidenceProvider 跨域融合后取 top-K 注入 prompt。
     */
    fun rrfFuseEvidence(vararg rankings: List<EvidenceRef>, limit: Int): List<Pair<EvidenceRef, Float>> =
        rrfFuse(*rankings, limit = limit, keyOf = { it.key() })

    /** 查询侧 bigram 滑窗（B7 词法链降级：中文无分词，字符二元组子串检索；命中数≈相关度） */
    fun queryBigrams(query: String): List<String> =
        query.replace(BIGRAM_IGNORED, "")
            .windowed(2).distinct().take(8)

    /** FloatArray → BLOB（小端序，与 decodeEmbedding 对称——Memory 4.6-pre 格式约定） */
    fun encodeEmbedding(vec: FloatArray): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(vec.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        vec.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    /** BLOB → FloatArray（小端序，与 encodeEmbedding 对称） */
    fun decodeEmbedding(blob: ByteArray): FloatArray {
        val buffer = java.nio.ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        return FloatArray(blob.size / 4) { buffer.float }
    }
}
