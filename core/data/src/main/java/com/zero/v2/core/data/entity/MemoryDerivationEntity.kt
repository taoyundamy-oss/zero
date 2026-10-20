package com.zero.v2.core.data.entity

import androidx.room.*
import com.zero.v2.core.common.MemoryType
import java.security.MessageDigest

/** The main dialogue's immutable classification payload plus a durable local materialization state. */
@Entity(tableName = "memory_derivations", foreignKeys = [ForeignKey(
    entity = ChronicleSourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"],
    onDelete = ForeignKey.CASCADE,
)], indices = [Index("sourceId"), Index("slotId"), Index(value = ["state", "createdAt"])])
data class MemoryDerivationEntity(
    @PrimaryKey val id: String,
    val sourceId: String, val slotId: String,
    val content: String, val quote: String, val type: MemoryType,
    val basis: String, val scope: String, val claimKey: String, val importance: Float,
    val occurredAt: Long?, val validFrom: Long?, val validUntil: Long?,
    val createdAt: Long,
    val state: String = "PENDING", val appliedAt: Long? = null,
) {
    val memoryId: String get() = "derived:$id"

    fun validate() {
        require(sourceId.matches(Regex("[0-9a-f]{64}"))) { "derivation_source_id" }
        require(content.isNotBlank() && content.length <= 2000 && quote.isNotBlank() && quote.length <= 2000)
        require(type in TYPES && basis in setOf("EXPLICIT", "INFERRED")) { "derivation_classification" }
        require(type != MemoryType.TASK || com.zero.v2.core.common.TaskMemory.decode(content) != null) { "task_fields_required" }
        require(type != MemoryType.DIRECTIVE || basis == "EXPLICIT") { "directive_requires_explicit_basis" }
        require(scope.isNotBlank() && scope.length <= 100 && claimKey.isNotBlank() && claimKey.length <= 100)
        require(importance.isFinite() && importance in 0f..1f && createdAt >= 0)
        require(listOfNotNull(occurredAt, validFrom, validUntil, appliedAt).all { it >= 0 })
        require(validFrom == null || validUntil == null || validFrom < validUntil) { "derivation_interval" }
        require(slotId == slot(scope, claimKey) && id == identity()) { "derivation_identity" }
        require(state in setOf("PENDING", "APPLIED", "STALE") && (state != "APPLIED" || appliedAt != null))
        require(state != "PENDING" || appliedAt == null)
    }

    fun boundTo(source: ChronicleSourceEntity): Boolean {
        val start = source.body.indexOf(quote)
        return source.id == sourceId && source.body.length <= 4000 && start >= 0 && start == source.body.lastIndexOf(quote)
    }

    fun memory(source: ChronicleSourceEntity): MemoryEntity = MemoryEntity(
        id = memoryId, content = content, importance = importance,
        type = type, tier = if (type == MemoryType.EVENT) 1 else 2,
        origin = "MODEL_CLASSIFIED", confidence = if (basis == "EXPLICIT") 0.8f else 0.5f,
        sourceMessageId = source.anchorId.takeIf { source.kind == "message" },
        sourceConversationId = source.conversationId, scope = scope, createdAt = createdAt, lastAccessedAt = createdAt,
        occurredAt = occurredAt, validFrom = validFrom, validUntil = validUntil,
    )

    fun matches(memory: MemoryEntity, source: ChronicleSourceEntity): Boolean {
        val expected = memory(source)
        return memory.copy(revision = expected.revision, supersedesId = expected.supersedesId).let {
            MemoryConflictEntity.version(it) == MemoryConflictEntity.version(expected)
        } && memory.importance == importance && memory.createdAt == createdAt
    }

    /** Equality of declared meaning is mechanical; never guesses synonyms or resolves contradictions. */
    fun meaning(): String = digest(listOf(content, type.name, basis, occurredAt.toString(),
        validFrom.toString(), validUntil.toString(), sourceId.takeIf { type == MemoryType.EVENT }.orEmpty()))

    private fun identity() = digest(listOf(sourceId, slotId, content, quote, type.name, basis,
        importance.toString(), occurredAt.toString(), validFrom.toString(), validUntil.toString()))

    companion object {
        val TYPES = setOf(MemoryType.FACT, MemoryType.EVENT, MemoryType.PREFERENCE, MemoryType.DIRECTIVE,
            MemoryType.RELATIONSHIP, MemoryType.TASK)
        fun slot(scope: String, key: String): String = digest(listOf(scope, key))
        fun digest(values: List<String>): String = MessageDigest.getInstance("SHA-256")
            .digest(values.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        fun create(sourceId: String, content: String, quote: String, type: MemoryType,
            basis: String, scope: String, claimKey: String, importance: Float,
            occurredAt: Long? = null, validFrom: Long? = null, validUntil: Long? = null, now: Long): MemoryDerivationEntity {
            val value = MemoryDerivationEntity("", sourceId, slot(scope, claimKey), content, quote, type,
                basis, scope, claimKey, importance, occurredAt, validFrom, validUntil, now)
            return value.copy(id = value.identity()).also { it.validate() }
        }
    }
}

/** Rebuildable metadata only, never a reusable authorization or a backup source of truth. */
@Entity(tableName = "memory_derivation_views", indices = [Index("scope")])
data class MemoryDerivationViewEntity(
    @PrimaryKey val slotId: String, val scope: String, val state: String,
    val selectedId: String?, val eligibleCount: Int, val version: String, val rebuiltAt: Long,
)
