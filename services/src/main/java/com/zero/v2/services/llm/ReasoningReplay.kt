package com.zero.v2.services.llm

import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.MessageRole
import org.json.JSONObject

internal object ReasoningReplay {
    fun apply(target: JSONObject, message: ChatMessage, required: Boolean) {
        if (required && message.role == MessageRole.ASSISTANT) {
            target.put("reasoning_content", message.thinking.orEmpty())
            if (!target.has("content")) target.put("content", message.content.orEmpty())
        } else if (message.thinking != null) target.put("reasoning_content", message.thinking)
    }
}
