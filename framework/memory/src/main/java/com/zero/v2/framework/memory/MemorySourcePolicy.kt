package com.zero.v2.framework.memory

import com.zero.v2.core.common.ChatFinishedEvent
import com.zero.v2.core.common.MessageRole
import com.zero.v2.core.common.MessageSource

internal object MemorySourcePolicy {
    fun userInput(event: ChatFinishedEvent): String? {
        val message = event.userMessage
        val direct = message.source in setOf(MessageSource.CHAT_WINDOW, MessageSource.VOICE, MessageSource.REMOTE)
        if (!direct || message.role != MessageRole.USER || message.systemSource != null || message.triggerId != null) return null
        if (message.id.isBlank() || event.conversationId.isBlank()) return null
        return event.originalUserText?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_SOURCE_LENGTH }
    }

    private const val MAX_SOURCE_LENGTH = 4000
}
