package com.zero.v2.core.data.entity

import com.zero.v2.core.common.MemoryStatus
import com.zero.v2.core.common.MemoryType
import java.security.MessageDigest
import java.util.Base64

/** Versioned reference to the exact grounded memory used for a graph attribute. */
object GraphMemoryEvidence {
    const val PREFIX = "memory:v1:"

    fun eligible(memory: MemoryEntity): Boolean =
        memory.id.startsWith("user-fact:") && memory.type == MemoryType.FACT &&
            memory.status == MemoryStatus.ACTIVE && !memory.sourceConversationId.isNullOrBlank()

    fun sourcePrefix(memoryId: String): String = PREFIX +
        Base64.getUrlEncoder().withoutPadding().encodeToString(memoryId.toByteArray(Charsets.UTF_8)) + ":"

    fun reference(memory: MemoryEntity): String {
        val text = listOf(memory.content, memory.sourceConversationId.orEmpty(), memory.createdAt.toString())
            .joinToString("") { "${it.length}:$it" }
        val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return sourcePrefix(memory.id) + hash
    }

    fun memoryId(reference: String): String? {
        if (!reference.startsWith(PREFIX)) return null
        return runCatching {
            String(Base64.getUrlDecoder().decode(reference.removePrefix(PREFIX).substringBefore(':')), Charsets.UTF_8)
        }.getOrNull()
    }
}
