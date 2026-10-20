package com.zero.v2.core.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Derived vectors only: Memory and Knowledge keep ownership of their source rows. */
@Entity(tableName = "vector_versions", primaryKeys = ["id"], indices = [Index("domain")])
data class VectorVersionEntity(
    val id: String,
    val domain: String,
    val space: String,
    val dimensions: Int,
    val expectedCount: Int,
    val baseVersion: String?,
    val state: String = "BUILDING",
    val digest: String = "",
    val createdAt: Long,
)

@Entity(
    tableName = "vector_entries", primaryKeys = ["versionId", "sourceId"],
    foreignKeys = [ForeignKey(entity = VectorVersionEntity::class, parentColumns = ["id"],
        childColumns = ["versionId"], onDelete = ForeignKey.CASCADE)],
)
data class VectorEntryEntity(
    val versionId: String,
    val sourceId: String,
    val sourceText: String,
    val sourceTitle: String = "",
    val sourceHeading: String = "",
    val vector: ByteArray,
)

@Entity(
    tableName = "vector_active", primaryKeys = ["domain"], indices = [Index("versionId")],
    foreignKeys = [ForeignKey(entity = VectorVersionEntity::class, parentColumns = ["id"],
        childColumns = ["versionId"], onDelete = ForeignKey.RESTRICT)],
)
data class VectorActiveEntity(val domain: String, val versionId: String)

/** Immutable evaluation evidence bound to the exact sealed candidate bytes, not a boolean override. */
@Entity(
    tableName = "vector_validation", primaryKeys = ["versionId"],
    foreignKeys = [ForeignKey(entity = VectorVersionEntity::class, parentColumns = ["id"],
        childColumns = ["versionId"], onDelete = ForeignKey.CASCADE)],
)
data class VectorValidationEntity(
    val versionId: String,
    val candidateDigest: String,
    val datasetVersion: String,
    val datasetDigest: String,
    val expectedSamples: Int,
    val evaluatedSamples: Int,
    val baselineRecall: Double,
    val candidateRecall: Double,
    val minimumRecall: Double,
    val report: String,
)

data class VectorSourceRow(val id: String, val text: String, val title: String, val heading: String)
