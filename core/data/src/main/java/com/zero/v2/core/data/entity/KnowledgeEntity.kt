package com.zero.v2.core.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 知识库轨实体 — KB1.1（DB v18 · 设计 §五）。
 *
 * KbDocEntity：knowledge/ 子树 md 文档的索引行——**文件是唯一真相源，本表是可弃可重建的
 * 派生物**（doIndex 全量树对账即可恢复）。`relative_path` 相对 knowledge/ 根（含子目录，
 * 音乐 MD13 同律；深度受 listTree ≤5 层约束，GA1），UNIQUE 约束 = 索引 upsert 锚点。
 * ⚠️ DAO 侧"查-留-id-更新"纪律（B8）：禁裸 @Insert(REPLACE) 承接索引——REPLACE 撞
 * UNIQUE 走删旧插新，doc id 漂移 + kb_chunks FK CASCADE 全清。
 *
 * KbChunkEntity：markdown 切分片段——`text` 为切分副本（词法 LIKE 检索前提 + 文件暂不
 * 可读时片段仍可返回；属派生物与 embedding 同级可弃，KD3）；`heading_path` 祖先标题链
 * （嵌入上下文构造 KA4）；`embedding` 小端 FloatArray BLOB（Memory 同格式）；
 * `embed_model` 写入时 provider.modelId（KA5 换档防护：检索时维度+模型双校验）。
 */
@Entity(
    tableName = "kb_docs",
    indices = [Index(value = ["relative_path"], unique = true)]
)
data class KbDocEntity(
    @PrimaryKey val id: String,
    /** 唯一索引键：相对 knowledge/ 根的含子目录路径（不含 knowledge/ 前缀） */
    @ColumnInfo(name = "relative_path") val relativePath: String,
    /** 首个 # 标题，缺省文件基名（展示用，不承担定位职责） */
    @ColumnInfo(name = "title", defaultValue = "") val title: String = "",
    @ColumnInfo(name = "mtime") val mtime: Long,
    @ColumnInfo(name = "size") val size: Long,
    @ColumnInfo(name = "chunk_count", defaultValue = "0") val chunkCount: Int = 0,
    @ColumnInfo(name = "indexed_at") val indexedAt: Long,
    /** 预留标签（一期空串；kb_search tag 过滤锚点） */
    @ColumnInfo(name = "tags", defaultValue = "") val tags: String = ""
)

@Entity(
    tableName = "kb_chunks",
    foreignKeys = [
        ForeignKey(entity = KbDocEntity::class, parentColumns = ["id"],
            childColumns = ["doc_id"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("doc_id")]  // Room 外键须索引；删 doc 行 → chunks 随清（整文档级重切分 §5.3-3）
)
data class KbChunkEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "doc_id") val docId: String,
    /** 文档内序（0 起） */
    @ColumnInfo(name = "seq") val seq: Int,
    /** 祖先标题链（如 "学习笔记 > 中医 > 方剂"）——嵌入上下文构造（KA4） */
    @ColumnInfo(name = "heading_path", defaultValue = "") val headingPath: String = "",
    /** 切分正文副本（索引派生物；词法 LIKE 检索前提，KD3） */
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "char_len") val charLen: Int,
    /** 小端 FloatArray BLOB；NULL = 待回填（KD4 先入库后异步嵌入） */
    @ColumnInfo(name = "embedding") val embedding: ByteArray? = null,
    /** 写入时 provider.modelId（KA5 换档防护：检索时与当前模型双校验，不匹配只走词法） */
    @ColumnInfo(name = "embed_model", defaultValue = "") val embedModel: String = "",
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)
