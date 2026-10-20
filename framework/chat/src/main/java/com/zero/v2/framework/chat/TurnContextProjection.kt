package com.zero.v2.framework.chat

import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.MessageRole

/** Request context projection shared by all chat rounds. */
internal class TurnContextProjection {
    private val snapshots = linkedMapOf<String, ChatMessage>()

    fun clear() = snapshots.clear()

    fun inject(msgs: List<ChatMessage>, turnContext: String?): List<ChatMessage> {
        val users = msgs.filter { it.role == MessageRole.USER }.map { it.id }.toSet()
        snapshots.keys.retainAll(users)
        val current = msgs.lastOrNull { it.role == MessageRole.USER }
        if (current != null && !turnContext.isNullOrBlank()) {
            snapshots.getOrPut(current.id) {
                msgs.firstOrNull { it.id == contextId(current.id) } ?: ChatMessage(
                    id = contextId(current.id), role = MessageRole.SYSTEM,
                    content = "[以下上下文仅用于紧随的用户回合；历史快照不代表当前状态]\n$turnContext",
                )
            }
        }
        val existing = msgs.map { it.id }.toSet()
        return msgs.flatMap { msg ->
            val context = snapshots[msg.id]?.takeIf { it.id !in existing && msg.role == MessageRole.USER }
            if (context == null) listOf(msg) else listOf(context, msg)
        }
    }

    companion object {
        fun contextId(userId: String) = com.zero.v2.core.common.TurnHistory.contextId(userId)
    }
}
