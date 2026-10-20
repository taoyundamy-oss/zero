package com.zero.v2.core.common

/** Shared transcript boundary for request construction and persisted recovery. */
object ToolTranscript {
    fun repair(messages: List<ChatMessage>): List<ChatMessage> {
        val result = mutableListOf<ChatMessage>()
        val pending = linkedMapOf<String, ToolCall>()
        fun closeInterruptedBatch() {
            pending.values.forEach { call ->
                result += ChatMessage(
                    id = "interrupted_tool_${call.id}", role = MessageRole.TOOL,
                    content = "[工具未完成：上次回合中断，未取得结果。不要假定成功；需要时重新发起调用。]",
                    toolCallId = call.id, toolCallName = call.name, timestamp = 0,
                )
            }
            pending.clear()
        }
        for (message in messages) {
            if (message.role == MessageRole.TOOL) {
                if (pending.remove(message.toolCallId) != null) result += message
            } else {
                closeInterruptedBatch()
                val calls = message.toolCalls.filter { it.id.isNotBlank() && it.name.isNotBlank() }.distinctBy { it.id }
                result += if (calls == message.toolCalls) message else message.copy(toolCalls = calls)
                if (message.role == MessageRole.ASSISTANT) calls.forEach { pending[it.id] = it }
            }
        }
        closeInterruptedBatch()
        return result
    }
}
