package com.zero.v2.core.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 角色/人格实体（Room）— Zero v2。
 *
 * 定义 AI 助手的人格配置，包括名称、描述和系统提示词。
 * 角色可被对话引用，切换角色即切换 AI 的行为风格。
 *
 * @property id 角色唯一标识（UUID）
 * @property name 角色名称
 * @property description 角色简要描述
 * @property systemPrompt 系统提示词，定义角色人格
 * @property isPreset 是否为预设角色（预设角色不可删除）
 * @property createdAt 创建时间戳
 * @property updatedAt 最后更新时间戳
 */
@Entity(tableName = "characters")
data class CharacterEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "description") val description: String? = null,
    @ColumnInfo(name = "system_prompt") val systemPrompt: String,
    @ColumnInfo(name = "is_preset") val isPreset: Boolean = false,
    @ColumnInfo(name = "is_active") val isActive: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)
