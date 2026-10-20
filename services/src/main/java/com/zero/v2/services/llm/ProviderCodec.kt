package com.zero.v2.services.llm

import com.zero.v2.core.common.ContentPart
import com.zero.v2.core.common.InputModality

/**
 * Provider 内容编解码（M3 / 设计§3）——把 [ContentPart] 序列化为 provider 原生 content 数组。
 *
 * 协议差异收在本接口实现内，不在 ChatBot 到处 `if(provider)`。先覆盖图片；音频接口留扩展位，
 * 只对实际支持 provider 启用（M3 后续）。返回纯 `List<Map>`（无 org.json 依赖，JVM 可测），
 * 由 [OpenAiCompatibleClient.buildChatBody] 转 JSONArray 发请求。
 *
 * @param base64Resolver 把 ImageRef.attachmentId(URI) 解析为 `data:mime;base64,...`；null=读取失败（跳过该图，不静默丢图——返回降级标记）。
 */
interface ProviderCodec {
    /** 匹配 LlmProfile.providerId / kind（一 codec 服务一协议族）。 */
    val protocolId: String

    /** 该 profile 是否支持原生图输入（决定走 content 数组 vs 文本回退 M4）。 */
    fun supportsImageInput(profile: LlmProfile): Boolean

    /** 把 ContentPart 列表序列化为 OpenAI 兼容 content 数组项（type/text 或 type/image_url）。 */
    fun contentArray(parts: List<ContentPart>, base64Resolver: (String) -> String?): List<Map<String, Any>>
}

/**
 * OpenAI 兼容 content 数组 codec（M3 默认实现）。
 *
 * 生成 `[{type:"text",text:...}, {type:"image_url",image_url:{url:"data:..."}}]`。
 * 媒体上传失败（resolver 返回 null）→ 插入 `{type:"text",text:"[图片不可用]"}` 降级标记（不静默丢图）。
 * 纯逻辑，JVM 可测。
 */
class OpenAiCompatibleCodec : ProviderCodec {
    override val protocolId: String = "openai_compat_content_v1"

    /** Preserve attachment references even after persistence has removed transient base64. */
    fun messageContent(message: com.zero.v2.core.common.ChatMessage, imageInput: Boolean): List<Map<String, Any>> {
        val parts = com.zero.v2.core.common.ContentAdapter.toContentParts(message.content, message.attachments)
            .map { part ->
            if (part is ContentPart.ImageRef && message.attachments.any {
                it.uri == part.attachmentId && it.route == com.zero.v2.core.common.MediaRoute.TOOLS
            }) ContentPart.Text("[图片附件 attachmentId=${part.attachmentId}；用户选择工具识图，" +
                "请 attachment_export 后发现并调用动态视觉工具]") else part
        }
        val payloads = message.attachments.associate { it.uri to it.base64Data }
        // Chat Completions image blocks belong to user messages; generated/tool media stays a reference.
        if (!imageInput || message.role != com.zero.v2.core.common.MessageRole.USER) return parts.map { part ->
            mapOf("type" to "text", "text" to when (part) {
                is ContentPart.Text -> part.text
                is ContentPart.ImageRef -> "[图片附件 attachmentId=${part.attachmentId}；" +
                    "文本模型可通过 attachment_export 导出后调用动态视觉工具]"
                is ContentPart.AudioRef -> "[音频附件 attachmentId=${part.attachmentId}]"
                is ContentPart.VideoRef -> "[视频附件 attachmentId=${part.attachmentId}]"
                is ContentPart.FileRef -> "[文件附件 attachmentId=${part.attachmentId}]"
            })
        }
        val referenced = parts.flatMap { part ->
            if (part is ContentPart.ImageRef) listOf(ContentPart.Text(
                "[图片引用 attachmentId=${part.attachmentId}；可按需用 media_analyze 或 attachment_export 调用补充工具]"), part)
            else listOf(part)
        }
        return contentArray(referenced) { uri -> payloads[uri]?.takeIf { it.isNotBlank() } }
    }

    override fun supportsImageInput(profile: LlmProfile): Boolean =
        com.zero.v2.services.llm.ModelCapability.IMAGE_INPUT in profile.capabilities

    override fun contentArray(parts: List<ContentPart>, base64Resolver: (String) -> String?): List<Map<String, Any>> {
        val out = mutableListOf<Map<String, Any>>()
        for (p in parts) {
            when (p) {
                is ContentPart.Text -> out += mapOf("type" to "text", "text" to p.text)
                is ContentPart.ImageRef -> {
                    val dataUrl = base64Resolver(p.attachmentId)
                    if (dataUrl != null) {
                        out += mapOf(
                            "type" to "image_url",
                            "image_url" to mapOf("url" to dataUrl),
                        )
                    } else {
                        // 媒体上传失败——降级标记，不静默丢图
                        out += mapOf("type" to "text", "text" to "[图片不可用：${p.attachmentId.take(ATTACHMENT_ID_TAKE)}]")
                    }
                }
                is ContentPart.AudioRef -> {
                    // M3 扩展位：只对实际支持原生音频 provider 启用；默认降级文本占位
                    out += mapOf("type" to "text", "text" to "[音频附件 attachmentId=${p.attachmentId}；" +
                        "可用 media_analyze 或 attachment_export 后调用工具]")
                }
                is ContentPart.VideoRef -> out += mapOf("type" to "text",
                    "text" to "[视频附件 attachmentId=${p.attachmentId}；可用 media_analyze 抽帧分析或 attachment_export 后调用工具]")
                is ContentPart.FileRef -> {
                    out += mapOf("type" to "text", "text" to "[文件附件 attachmentId=${p.attachmentId}]")
                }
            }
        }
        return out
    }

    companion object {
        /** 降级标记中 attachmentId 截断长度（防日志膨胀）。 */
        const val ATTACHMENT_ID_TAKE = 40

        /** 是否需要走 content 数组（含非文本模态）。 */
        fun needsArray(parts: List<ContentPart>): Boolean =
            parts.any { it !is ContentPart.Text }
    }
}
