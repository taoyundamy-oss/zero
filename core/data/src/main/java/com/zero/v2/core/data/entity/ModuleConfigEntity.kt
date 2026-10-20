package com.zero.v2.core.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** 模块配置持久化实体。对标决策 #2：Room 表 module_configs。 */
@Entity(tableName = "module_configs")
data class ModuleConfigEntity(
    @PrimaryKey @ColumnInfo(name = "module_id") val moduleId: String,
    @ColumnInfo(name = "config_json") val configJson: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)
