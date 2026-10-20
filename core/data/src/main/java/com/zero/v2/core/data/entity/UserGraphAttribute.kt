package com.zero.v2.core.data.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * 用户图谱属性 — Step 4.10（DESIGN §3.2 / D16）。
 *
 * 带时间区间的版本化知识: effectiveFrom/effectiveTo 天然支持
 * "2020-2023 住北京，2023 至今住上海" — 新版覆盖旧版（关闭区间），无需冲突解决。
 * 复合主键 (entityId, key, effectiveFrom)：同一属性的历史版本多行共存。
 */
@Entity(
    tableName = "user_graph_attributes",
    primaryKeys = ["entityId", "key", "effectiveFrom"],
    indices = [Index("entityId")]
)
data class UserGraphAttribute(
    val entityId: String,
    /** 属性键，如 "location"、"occupation"、"skill.Rust.level" */
    val key: String,
    val value: String,
    val source: AttributeSource,
    /** 初始置信度: USER_STATED 0.9 / INFERRED 0.5 / EXTRACTED 0.4（D25，每月未确认衰减 0.05 见 4.11） */
    val confidence: Float,
    val effectiveFrom: Long,
    /** null = 当前有效 */
    val effectiveTo: Long? = null,
    /** 来源记忆/对话证据 */
    val evidence: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/** 属性来源（§3.2） */
enum class AttributeSource {
    /** 用户明确说的（confidence 高） */
    USER_STATED,
    /** 从对话推断的（confidence 中） */
    INFERRED,
    /** 从 Memory 提取的（confidence 中低） */
    EXTRACTED
}
