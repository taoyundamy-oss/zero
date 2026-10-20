package com.zero.v2.core.common

/** Preserve the ordered wire transcript, including the context immediately preceding its user. */
object TurnHistory {
    fun contextId(userId: String) = "turn_context_$userId"

    fun slice(messages: List<ChatMessage>, userId: String): List<ChatMessage> {
        val userIndex = messages.indexOfFirst { it.id == userId && it.role == MessageRole.USER }
        if (userIndex < 0) return emptyList()
        val start = if (userIndex > 0 && messages[userIndex - 1].id == contextId(userId)) userIndex - 1 else userIndex
        val next = ((userIndex + 1) until messages.size).firstOrNull { messages[it].role == MessageRole.USER }
            ?: messages.size
        val end = if (next < messages.size && next > 0 && messages[next - 1].id == contextId(messages[next].id)) next - 1 else next
        return messages.subList(start, end).filterNot {
            it.role == MessageRole.ASSISTANT && it.content.isNullOrBlank() && it.toolCalls.isEmpty()
        }.filterNot { it.role == MessageRole.TOOL && it.toolCallId == null }
    }
}
