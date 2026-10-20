package com.zero.v2.services.retrieval

import com.zero.v2.core.common.MemoryType
import com.zero.v2.core.common.TaskMemory

/** Client-validated user quotations, not a new instruction authority. */
data class ContextMemory(val id: String, val version: String, val content: String,
    val type: String, val recordedAt: Long, val rank: Int = 0)

/** Selection policy only: does not classify text or attest semantic correctness. */
object ContextMemoryPolicy {
    private const val IMPORTANT = .8f
    private const val FREQUENT = 2
    private const val OTHER_PRIORITY = 3
    fun accepts(
        type: MemoryType, content: String, importance: Float, accessCount: Int,
    ): Boolean = when (type) {
        MemoryType.DIRECTIVE, MemoryType.RELATIONSHIP -> true
        MemoryType.TASK -> TaskMemory.decode(content)?.active == true
        MemoryType.FACT, MemoryType.PREFERENCE -> importance >= IMPORTANT || accessCount >= FREQUENT
        else -> false
    }
    fun priority(type: String): Int = when (type) {
        "DIRECTIVE" -> 0
        "RELATIONSHIP" -> 1
        "TASK" -> 2
        else -> OTHER_PRIORITY
    }
}
