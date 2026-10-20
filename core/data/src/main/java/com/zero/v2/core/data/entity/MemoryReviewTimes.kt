package com.zero.v2.core.data.entity

/** Null dates explicitly mean unknown; null object means retain the old dates. */
data class MemoryReviewTimes(val occurredAt: Long?, val validFrom: Long?, val validUntil: Long?) {
    init {
        require(listOf(occurredAt, validFrom, validUntil).all { it == null || it >= 0 })
        require(validFrom == null || validUntil == null || validUntil > validFrom)
    }
    companion object {
        fun from(p: MemoryReviewEntity): MemoryReviewTimes? = if (p.editTimes)
            MemoryReviewTimes(p.proposedOccurredAt, p.proposedValidFrom, p.proposedValidUntil) else null
        fun identity(base: String, content: String, type: String, scope: String, times: MemoryReviewTimes?): String {
            val values = listOf(base, content, type, scope) + if (times == null) emptyList() else
                listOf("explicit-times-v1", times.occurredAt.toString(), times.validFrom.toString(), times.validUntil.toString())
            return java.security.MessageDigest.getInstance("SHA-256")
                .digest(values.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
