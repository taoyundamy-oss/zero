package com.zero.v2.core.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

/**
 * 用户图谱实体 — Step 4.10（DESIGN §3.2 / D16）。
 *
 * 图谱回答"用户是谁"（Memory 回答"用户说过什么"）。
 * id 约定: "user"（用户本体）、"person_张三"、"place_邵阳" 等语义化前缀。
 */
@Entity(
    tableName = "user_graph_entities",
    indices = [Index("name"), Index("type")]
)
data class UserGraphEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: EntityType,
    val createdAt: Long = System.currentTimeMillis(),
    /** Only host-created grounded entities may be removed automatically when orphaned. */
    @ColumnInfo(defaultValue = "0") val memoryDerived: Boolean = false
)

/** 实体类型（§3.2） */
enum class EntityType {
    PERSON,        // 人
    PLACE,         // 地点
    ORGANIZATION,  // 组织
    SKILL,         // 技能
    INTEREST,      // 兴趣
    PROJECT,       // 项目
    EVENT,         // 事件
    CONCEPT        // 抽象概念
}
