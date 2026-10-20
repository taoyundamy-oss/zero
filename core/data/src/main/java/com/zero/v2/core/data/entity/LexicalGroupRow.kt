package com.zero.v2.core.data.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded

data class MemoryLexicalGroupRow(
    @ColumnInfo(name = "term_index") val termIndex: Int,
    @ColumnInfo(name = "match_id") val matchId: String,
    @Embedded val memory: MemoryEntity?,
)

data class KnowledgeLexicalGroupRow(
    @ColumnInfo(name = "term_index") val termIndex: Int,
    @ColumnInfo(name = "match_id") val matchId: String,
    @Embedded val chunk: KbChunkEntity?,
)
