package com.zero.v2.core.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.security.MessageDigest

/** Immutable text snapshot, not semantic certification. No FK to the mutable transcript. */
@Entity(tableName = "chronicle_sources", indices = [Index(value = ["kind", "anchorId"]), Index("conversationId")])
data class ChronicleSourceEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val anchorId: String,
    val conversationId: String?,
    val parentMemoryId: String?,
    val body: String,
    val provenance: String,
    val recordedAt: Long,
    val capturedAt: Long,
) {
    fun validate() {
        require(kind in setOf("message", "confirmation") && anchorId.isNotBlank() && body.isNotBlank())
        require(recordedAt >= 0 && capturedAt >= 0)
        require(if (kind == "message") !conversationId.isNullOrBlank() && parentMemoryId == null
            else conversationId == null && !parentMemoryId.isNullOrBlank())
        require(id == fingerprint(copy(id = ""))) { "chronicle_source_integrity" }
    }

    /** Four-field legacy records cannot establish absence of an automation run. Never upgrade them in place. */
    fun hasDirectUserProvenance(): Boolean {
        if (kind != "message" || runCatching { validate() }.isFailure) return false
        val fields = mutableListOf<String?>()
        var offset = 0
        while (offset < provenance.length && fields.size < 6) {
            val colon = provenance.indexOf(':', offset)
            if (colon < 0) return false
            val size = provenance.substring(offset, colon).toIntOrNull() ?: return false
            offset = colon + 1
            if (size == -1) fields.add(null) else {
                if (size < 0 || size > provenance.length - offset) return false
                fields.add(provenance.substring(offset, offset + size)); offset += size
            }
        }
        return offset == provenance.length && fields.size == 5 && fields[0] == "user" &&
            fields[1] in setOf("CHAT_WINDOW", "VOICE", "REMOTE") && fields.drop(2).all { it == null }
    }

    companion object {
        fun message(m: MessageEntity, now: Long): ChronicleSourceEntity? =
            m.takeIf { it.role.equals("user", ignoreCase = true) && !it.content.isNullOrBlank() &&
                it.source !in setOf("AUTONOMOUS", "SYSTEM") }?.let {
                create("message", it.id, it.conversationId, null, it.content!!,
                    packed(listOf(it.role, it.source, it.systemSource, it.triggerId, it.automationRunId)), it.createdAt, now)
            }

        fun confirmation(p: MemoryReviewEntity, now: Long): ChronicleSourceEntity {
            require(p.state == "ACCEPTED" && p.evidenceKind == "USER_CONFIRMATION" && p.resultMemoryId == "review:${p.id}")
            return create("confirmation", p.id, null, p.memoryId, p.proposedContent,
                packed(listOf(p.baseVersion, p.proposedType.name, p.proposedScope, p.sourceMessageId,
                    p.sourceVersion, p.resultMemoryId)), checkNotNull(p.decidedAt), now)
        }

        private fun create(kind: String, anchor: String, conversation: String?, parent: String?, body: String,
            provenance: String, recorded: Long, captured: Long): ChronicleSourceEntity {
            val value = ChronicleSourceEntity("", kind, anchor, conversation, parent, body, provenance, recorded, captured)
            return value.copy(id = fingerprint(value)).also { it.validate() }
        }

        private fun packed(values: List<String?>): String = values.joinToString("") { it?.let { s -> "${s.length}:$s" } ?: "-1:" }
        private fun fingerprint(s: ChronicleSourceEntity): String = MessageDigest.getInstance("SHA-256")
            .digest(packed(listOf(s.kind, s.anchorId, s.conversationId, s.parentMemoryId, s.body, s.provenance,
                s.recordedAt.toString())).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

/** Content-free, sticky across restore. Conversation anchors cover sources no longer in messages. */
@Entity(tableName = "chronicle_deletions", primaryKeys = ["kind", "anchorId"])
data class ChronicleDeletionEntity(val kind: String, val anchorId: String, val deletedAt: Long) {
    fun validate() {
        require(kind in setOf("message", "confirmation", "conversation", "transcript") && anchorId.isNotBlank() && deletedAt >= 0)
    }
}
