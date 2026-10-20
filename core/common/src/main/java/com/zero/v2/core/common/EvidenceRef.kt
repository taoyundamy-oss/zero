package com.zero.v2.core.common

/**
 * 统一证据引用 — R2（设计§R2 有界中文混合检索）：Memory 与 Knowledge 检索结果的统一只读投影。
 *
 * **写入域不合并**——memories / kb_chunks 各自持久化、schema 独立（MemoryDao/KbDao 写径不变）；
 * 仅读取侧统一为 EvidenceRef，供 R3 EvidenceProvider 跨域 RRF 融合后注入 prompt（≤3 条带来源）。
 *
 * - [domain]：来源域（MEMORY / KNOWLEDGE）——跨域 RRF 去重 key 前缀，mem:id 与 kb:id 不撞键。
 * - [id]：来源域内唯一标识（MemoryEntity.id / KbChunkEntity.id）。
 * - [content]：供 prompt 引用的文本片段（已截断，不含媒体正文/base64）。
 * - [sourceRef]：人类可读来源署名（如 "memory" / "knowledge/<相对路径>"），用于证据署名。
 * - [score]：检索分（RRF 融合分或余弦分，归一前；仅供排序，不暴露给用户）。
 *
 * 纯数据类型，无 core:data 依赖（MemoryEntity/KbChunkEntity → EvidenceRef 适配在调用方）。
 */
data class EvidenceRef(
    val domain: EvidenceDomain,
    val id: String,
    val content: String,
    val sourceRef: String,
    val score: Float = 0f,
    /** Exact full source body hash, computed before excerpt/provenance formatting; absent on legacy refs. */
    val bodyDigest: String? = null,
    /** Complete qualified source data: preserve the entire entry or emit a reference, never an excerpt. */
    val completeSource: Boolean = false,
    val sourceVersion: String? = null,
) {
    /** 跨域去重键："<prefix>:<id>"——mem:abc 与 kb:abc 视为不同证据，不撞键。 */
    fun key(): String = "${domain.prefix}:$id"
}

/** 证据来源域（prefix 用于跨域 RRF 去重 key）。 */
enum class EvidenceDomain(val prefix: String) {
    MEMORY("mem"),
    KNOWLEDGE("kb"),
}
