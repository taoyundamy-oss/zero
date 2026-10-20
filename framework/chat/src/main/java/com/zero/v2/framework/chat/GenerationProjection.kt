package com.zero.v2.framework.chat

import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.MessageRole
import java.security.MessageDigest
import java.util.Properties

/** Persists only hashes and trim lengths; original conversation text remains authoritative. */
internal class GenerationProjection(private val state: Properties = Properties()) {
    var resetReason: String? = null
        private set
    val epoch: Long get() = state.getProperty("epoch")?.toLongOrNull() ?: 0L

    private fun reset() {
        val retainedEpoch = epoch
        state.clear()
        state.setProperty("epoch", retainedEpoch.toString())
    }

    fun view(messages: List<ChatMessage>, scope: String): List<ChatMessage> {
        resetReason = null
        if (state.getProperty("scope") != digest(scope)) {
            if (state.isNotEmpty()) resetReason = "scope_changed"
            reset()
        }
        state.setProperty("scope", digest(scope))
        val anchor = state.getProperty("anchor")
        val start = messages.indexOfFirst { digest(it.id) == anchor }
        if (anchor != null && start < 0) {
            resetReason = "anchor_missing"
            reset()
            state.setProperty("scope", digest(scope))
        }
        return messages.drop(start.coerceAtLeast(0)).map { message ->
            val trim = state.getProperty("trim." + digest(message.id))?.split(":")
            val text = message.content
            if (message.role == MessageRole.TOOL && text != null && trim?.size == 2 &&
                trim[0] == digest(text)) {
                val length = trim[1].toIntOrNull()
                if (length != null && length in 0..text.length) message.copy(content = text.take(length) + SUFFIX)
                else message
            } else message
        }
    }

    fun freeze(original: List<ChatMessage>, retained: List<ChatMessage>, scope: String) {
        val previous = Properties().apply { putAll(state) }
        state.clear()
        state.setProperty("epoch", ((previous.getProperty("epoch")?.toLongOrNull() ?: 0L) + 1).toString())
        state.setProperty("scope", digest(scope))
        retained.firstOrNull()?.let { state.setProperty("anchor", digest(it.id)) }
        val source = original.associateBy { it.id }
        retained.forEach { message ->
            val key = "trim." + digest(message.id)
            val before = source[message.id]?.content
            val after = message.content
            if (message.role == MessageRole.TOOL && before != null && after != null && before != after &&
                after.endsWith(SUFFIX)) {
                state.setProperty(key, digest(before) + ":" + (after.length - SUFFIX.length))
            } else previous.getProperty(key)?.let { state.setProperty(key, it) }
        }
    }

    fun snapshot(): Properties = Properties().apply { putAll(state) }

    companion object {
        const val SUFFIX = "\n…[已瘦身，可重新调用工具获取完整结果]"
        fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
