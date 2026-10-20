package com.zero.v2.services.llm

import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MiMo TTS 差异适配器 — 开发前规范化 N2.4。
 *
 * 仅承载 MiMo 特有字段，与旧 MimoTtsClient 逐字段一致：
 *   - audio: { format: "pcm16", voice } （pcm16 固定 24kHz 单声道）
 *   - 流解析：choices[0].delta.audio.data = base64 pcm16 → 由 client.ttsStreamPcm 解码回调
 * 合成文本放 assistant 角色消息（MiMo 惯例，user 角色为可选风格指令——语气控制预留）。
 */
@Singleton
class MimoTtsLlmAdapter @Inject constructor() : LlmAdapter {
    override val providerId = "mimo-tts"

    override fun buildExtraBody(body: JSONObject, profile: LlmProfile, stream: Boolean) {
        body.put("audio", JSONObject().apply {
            put("format", "pcm16")
            put("voice", profile.voice)
        })
    }

    /** 从 delta 提取 base64 音频块（null = 本块无音频） */
    override fun parseStreamExtra(delta: JSONObject): Any? = try {
        delta.optJSONObject("audio")?.optString("data")?.takeIf { it.isNotEmpty() && it != "null" }
    } catch (_: Exception) { null }
}
