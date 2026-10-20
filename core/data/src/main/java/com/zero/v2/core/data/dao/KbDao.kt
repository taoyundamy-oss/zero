package com.zero.v2.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Update
import com.zero.v2.core.data.entity.KbChunkEntity
import com.zero.v2.core.data.entity.KbDocEntity

/**
 * 知识库 DAO：准备内容后事务替换，文档 ID 保持稳定。
 *
 * docs upsert 走"查-留-id-更新"（B8）：replaceDocument 同时发布元数据与片段；
 * 禁裸 @Insert(REPLACE)——撞 UNIQUE(relative_path) 删旧插新致 doc id 漂移 +
 * chunks FK CASCADE 全清（音乐轨 M1 同款陷阱）。
 */
@Dao
interface KbDao {
    /** A delayed rank decision cannot return deleted/replaced chunks or changed document metadata. */
    @Transaction
    suspend fun currentRecall(snapshots: List<KbChunkEntity>, docs: Map<String, KbDocEntity>): List<KbChunkEntity> =
        snapshots.filter { snapshot ->
            val doc = docs[snapshot.docId]
            val currentDoc = doc?.let { getByPath(it.relativePath) }
            val current = getChunk(snapshot.id)
            currentDoc == doc && currentDoc != null && current?.docId == snapshot.docId &&
                current.text == snapshot.text && current.headingPath == snapshot.headingPath
        }

    @Query("SELECT * FROM kb_chunks WHERE id = :id")
    suspend fun getChunk(id: String): KbChunkEntity?

    @Query("SELECT id FROM kb_chunks WHERE doc_id = :docId ORDER BY seq")
    suspend fun chunkIds(docId: String): List<String>

    /** Read one disk candidate at a time inside the same transaction, including the baseline guard. */
    @Transaction
    suspend fun publishCandidates(
        count: Int,
        removedPaths: List<String>,
        baseline: String,
        candidate: suspend (Int) -> com.zero.v2.core.data.knowledge.KnowledgeCandidateStore.Candidate,
    ): Boolean {
        val preparedPaths = mutableSetOf<String>()
        var alreadyPublished = true
        repeat(count) { index ->
            val value = candidate(index)
            require(preparedPaths.add(value.doc.relativePath) && value.doc.relativePath !in removedPaths)
            if (getByPath(value.doc.relativePath) != value.doc || chunkIds(value.doc.id) != value.chunks.map { it.id }) {
                alreadyPublished = false
            }
        }
        if (alreadyPublished && removedPaths.all { getByPath(it) == null }) return false
        check(com.zero.v2.core.data.knowledge.KnowledgeCandidateStore.fingerprint(loadAllDocs().toString()) == baseline) {
            "reindex_active_changed"
        }
        repeat(count) { index ->
            val value = candidate(index)
            replaceDocument(value.doc, value.chunks)
        }
        removedPaths.forEach { deleteDoc(it) }
        return true
    }

    // ═══ docs ═══

    @Query("SELECT * FROM kb_docs ORDER BY relative_path")
    suspend fun loadAllDocs(): List<KbDocEntity>

    @Query("SELECT * FROM kb_docs WHERE relative_path = :path LIMIT 1")
    suspend fun getByPath(path: String): KbDocEntity?

    /** 新文档入库（无 REPLACE——已存在路径由调用方走 updateDocIndexed） */
    @Insert
    suspend fun insertDoc(doc: KbDocEntity)

    @Update
    suspend fun updateDoc(doc: KbDocEntity)

    /** Publish prepared text in one transaction; an insert failure restores the previous document. */
    @Transaction
    suspend fun replaceDocument(doc: KbDocEntity, chunks: List<KbChunkEntity>) {
        require(doc.chunkCount == chunks.size && chunks.all { it.docId == doc.id })
        val previous = getByPath(doc.relativePath)
        require(previous == null || previous.id == doc.id) { "Document identity changed" }
        if (previous == null) insertDoc(doc) else updateDoc(doc)
        deleteChunksByDoc(doc.id)
        insertChunks(chunks)
    }

    /** Publish a validated candidate set and removals together; SQLite rollback preserves active data. */
    @Transaction
    suspend fun publishDocuments(
        documents: List<KbDocEntity>,
        chunks: List<List<KbChunkEntity>>,
        removedPaths: List<String>,
    ) {
        require(documents.size == chunks.size)
        require(documents.map { it.relativePath }.distinct().size == documents.size)
        require(documents.none { it.relativePath in removedPaths })
        documents.forEachIndexed { index, doc -> replaceDocument(doc, chunks[index]) }
        removedPaths.forEach { deleteDoc(it) }
    }

    @Query("SELECT COUNT(*) FROM kb_chunks WHERE doc_id = :docId")
    suspend fun countChunksForDoc(docId: String): Int

    /** 重索引元数据（三元组比对后的 upsert 另一半；保留 id/相对路径不变） */
    @Query("UPDATE kb_docs SET mtime = :mtime, size = :size, chunk_count = :chunkCount, " +
            "indexed_at = :indexedAt WHERE relative_path = :path")
    suspend fun updateDocIndexed(path: String, mtime: Long, size: Long,
                                 chunkCount: Int, indexedAt: Long)

    @Query("DELETE FROM kb_docs WHERE relative_path = :path")
    suspend fun deleteDoc(path: String)

    // ═══ chunks ═══

    /** 整文档级重切分替换的前半（配合 insertChunks；doc 行删除时经 FK CASCADE 自动清） */
    @Query("DELETE FROM kb_chunks WHERE doc_id = :docId")
    suspend fun deleteChunksByDoc(docId: String)

    @Insert
    @Transaction
    suspend fun insertChunks(chunks: List<KbChunkEntity>)

    /** 词法链候选（B7：LIKE 子串——中文按字节匹配有效；FTS5 中文不可用 D31） */
    @Query("SELECT * FROM kb_chunks WHERE text LIKE '%' || :term || '%' LIMIT :limit")
    suspend fun searchByText(term: String, limit: Int): List<KbChunkEntity>

    @RawQuery
    suspend fun textGroupRows(query: androidx.sqlite.db.SupportSQLiteQuery): List<com.zero.v2.core.data.entity.KnowledgeLexicalGroupRow>

    @Query("SELECT EXISTS(SELECT id FROM kb_chunks LIMIT 1 OFFSET 2000)")
    suspend fun hasManyLexicalRows(): Boolean

    /** Legacy reads for small tables; one grouped snapshot for large tables. No cached facts. */
    suspend fun searchTextGroups(terms: List<String>, limit: Int): List<List<KbChunkEntity>> {
        require(terms.size <= 8 && limit in 1..300)
        if (terms.isEmpty()) return emptyList()
        if (!hasManyLexicalRows()) {
            return terms.map { searchByText(it, limit) }
        }
        val rows = textGroupRows(LexicalGroupQuery.knowledge(terms, limit))
        val entities = mutableMapOf<String, KbChunkEntity>()
        val groups = List(terms.size) { mutableListOf<KbChunkEntity>() }
        rows.forEach { row ->
            if (row.termIndex == -1) entities[row.matchId] = checkNotNull(row.chunk)
            else groups[row.termIndex].add(checkNotNull(entities[row.matchId]))
        }
        return groups
    }
    @Query("SELECT * FROM kb_chunks WHERE doc_id IN (:documents) " +
        "AND text LIKE '%' || :term || '%' ORDER BY id LIMIT :limit")
    suspend fun searchScopedText(term: String, documents: List<String>, limit: Int): List<KbChunkEntity>

    @Query("SELECT * FROM kb_chunks WHERE doc_id IN (:documents) AND embedding IS NOT NULL " +
        "AND embed_model = :model AND id > :after ORDER BY id LIMIT :limit")
    suspend fun scopedEmbeddingPage(model: String, after: String, documents: List<String>, limit: Int): List<KbChunkEntity>

    /**
     * R2：有界候选——当前[model]已嵌入的块，LIMIT [limit]（替代旧全量扫描 getWithEmbedding —— L-7 债收口）。
     * embed_model 过滤排除换档前的旧向量；余弦在 Kotlin 层计算（维度双校验在调用方防御）。
     * ORDER BY updated_at DESC 优先近期索引块；LIMIT 约束峰值工作集。
     */
    @Query("SELECT * FROM kb_chunks WHERE embedding IS NOT NULL AND embed_model = :model " +
            "ORDER BY updated_at DESC LIMIT :limit")
    suspend fun getWithEmbeddingBounded(model: String, limit: Int): List<KbChunkEntity>

    @Query("SELECT * FROM kb_chunks WHERE embedding IS NOT NULL AND embed_model = :model " +
        "AND id > :after ORDER BY id LIMIT :limit")
    suspend fun embeddingPage(model: String, after: String, limit: Int): List<KbChunkEntity>

    /** 嵌入回填取批（KD4：先入库 embedding=NULL，worker 批量消费；KB 重建走 kb_reindex 全量重嵌） */
    @Query("SELECT * FROM kb_chunks WHERE embedding IS NULL OR embed_model != :model ORDER BY id LIMIT :limit")
    suspend fun getForReembedding(model: String, limit: Int): List<KbChunkEntity>

    @Query("UPDATE kb_chunks SET embedding = :blob, embed_model = :model, updated_at = :updatedAt " +
            "WHERE id = :id")
    suspend fun updateEmbedding(id: String, blob: ByteArray, model: String, updatedAt: Long)

    @Query("SELECT COUNT(*) FROM kb_chunks")
    suspend fun countAllChunks(): Int

    @Query("SELECT COUNT(*) FROM kb_chunks WHERE embedding IS NULL OR embed_model != :model")
    suspend fun countEmbeddingBacklog(model: String): Int

    /** 换档检测（KA5）：当前模型块数 vs 总数——kb_status 据此提示 kb_reindex */
    @Query("SELECT COUNT(*) FROM kb_chunks WHERE embed_model = :model")
    suspend fun countModelOf(model: String): Int
}

