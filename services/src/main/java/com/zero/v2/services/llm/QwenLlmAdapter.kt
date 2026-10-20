package com.zero.v2.services.llm

import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Qwen（DashScope 兼容模式）差异适配器 — 开发前规范化 N2.3（D28 思考模式分离）。
 *
 * 仅承载 Qwen 特有请求字段，与旧 QwenAdapter.buildRequestBody 逐字段一致：
 *   HTTP 顶层 enable_thinking / thinking_budget —— stream 与 sync 均发送
 *   （sync 旧行为=enable_thinking=false/budget 默认，由 profile 参数自然复现）
 * 通用协议（messages/tools/SSE/鉴权）由 OpenAiCompatibleClient 承载。
 */
@Singleton
class QwenLlmAdapter @Inject constructor() : LlmAdapter {
    override val providerId = "qwen"

    override fun buildExtraBody(body: JSONObject, profile: LlmProfile, stream: Boolean) {
        body.put("enable_thinking", profile.enableThinking)
        if (profile.enableThinking) body.put("thinking_budget", profile.thinkingBudget)
    }
}
