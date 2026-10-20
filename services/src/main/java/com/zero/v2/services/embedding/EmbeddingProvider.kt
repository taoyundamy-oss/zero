package com.zero.v2.services.embedding

/**
 * 嵌入向量提供器 — Phase 4（D7 / D31 检索主径）。
 *
 * D31: 记忆检索主径为 Embedding 余弦相似度（FTS5 对中文无效）。
 * 实现约定:
 *   - 失败返回 null（不抛异常），调用方走降级链
 *   - 批量接口按入参顺序返回（失败位为 null）
 *   - 向量维度由实现自定，混合比较前须校验维度一致
 */
interface EmbeddingProvider {
    /** 模型标识（如 "text-embedding-v3"） */
    val modelId: String

    /** Semantic backend identity, excluding credentials and display labels. */
    val identity: String get() = modelId

    /** Key 是否已配置（回填前置检查） */
    suspend fun isAvailable(): Boolean

    /** 单条嵌入；失败返回 null */
    suspend fun embed(text: String): FloatArray?

    /** 批量嵌入（≤10 条/批由实现保证）；按入参顺序返回，失败位 null */
    suspend fun embedBatch(texts: List<String>): List<FloatArray?>

    /** Providers with asymmetric retrieval must distinguish queries from indexed documents. */
    suspend fun embedBatch(texts: List<String>, purpose: EmbeddingPurpose): List<FloatArray?> = embedBatch(texts)

    suspend fun embedQuery(text: String): FloatArray? = embed(text)
}

enum class EmbeddingPurpose(val wireValue: String) { DOCUMENT("document"), QUERY("query") }
