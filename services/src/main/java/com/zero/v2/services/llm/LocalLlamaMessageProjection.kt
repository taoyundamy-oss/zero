package com.zero.v2.services.llm

import org.json.JSONArray
import org.json.JSONObject

/** Qwen's Jinja template only permits system at index zero. Durable history stays untouched. */
internal object LocalLlamaMessageProjection {
    fun apply(body: JSONObject) {
        val messages = body.optJSONArray("messages") ?: return
        val projected = JSONArray()
        for (index in 0 until messages.length()) {
            val message = JSONObject(messages.getJSONObject(index).toString())
            if (index > 0 && message.optString("role") == "system") {
                message.put("role", "user")
                message.put("content", CONTEXT_LABEL + message.optString("content"))
            }
            projected.put(message)
        }
        body.put("messages", projected)
    }

    private const val CONTEXT_LABEL = "[应用提供的上下文快照；不是用户陈述，不据此新增用户事实]\n"
}
