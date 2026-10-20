package com.zero.v2.core.data.entity

import androidx.room.Entity
import androidx.room.Index

/** Persistent lineage, retained even after deletion so delayed work cannot resurrect derivatives. */
@Entity(tableName = "memory_sources", primaryKeys = ["summaryId", "sourceId"], indices = [Index("sourceId")])
data class MemorySourceEntity(val summaryId: String, val sourceId: String)
