package com.zero.v2.core.data.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * 用户图谱关系 — Step 4.10（DESIGN §3.2）。
 *
 * 实体间的有向边，同样带时间区间（"2023 起在 XX 公司工作"）。
 */
@Entity(
    tableName = "user_graph_relationships",
    primaryKeys = ["fromEntityId", "toEntityId", "type", "effectiveFrom"],
    indices = [Index("fromEntityId")]
)
data class UserGraphRelationship(
    val fromEntityId: String,
    val toEntityId: String,
    /** 关系类型，如 "colleague"、"family"、"works_at"、"owns" */
    val type: String,
    val effectiveFrom: Long,
    /** null = 当前有效 */
    val effectiveTo: Long? = null,
    /** Versioned memory source reference; null identifies legacy, unverified relationships. */
    val evidence: String? = null
)
