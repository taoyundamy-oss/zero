package com.zero.v2.services.llm

import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DeepSeek 差异适配器 — 开发前规范化 N2.2（D28 思考模式分离）。
 *
 * 仅承载 DeepSeek 特有请求字段，与旧 DeepSeekAdapter.buildRequestBody 逐字段一致：
 *   - 流式：thinking.type 始终发送（enabled/disabled）；reasoning_effort 仅启用思考时发送
 *   - 非流式（sync）：不注思考控制字段（旧行为保持——快速查询不走深度思考）
 * 通用协议（messages/tools/SSE/鉴权）由 OpenAiCompatibleClient 承载。
 * 流解析无差异（reasoning_content 已由 client 通用处理）。
 */
@Singleton
class DeepSeekLlmAdapter @Inject constructor() : LlmAdapter {
    override val providerId = "deepseek"

    override fun buildExtraBody(body: JSONObject, profile: LlmProfile, stream: Boolean) {
        if (!stream) return  // sync 不注思考控制（旧 Adapter 行为保持）
        body.put("thinking", JSONObject().apply {
            put("type", if (profile.enableThinking) "enabled" else "disabled")
        })
        if (profile.enableThinking) body.put("reasoning_effort", profile.reasoningEffort)
    }
}
