package com.zero.v2.core.common

/**
 * 版本化内容块（迭代开发设计§2.1 / 落地计划 M1）。
 *
 * 请求期内容表达——与持久化分离：[ChatMessage] 仍保存可读正文 + 附件元信息（uri/mime/type），
 * [ContentPart] 给单次 provider 调用按引用组装内容。**只持引用（attachmentId=uri）+ mime，
 * 不持 base64**——base64 不写数据库/日志/Trace（设计§3）。
 *
 * M1 先立类型 + 适配器 + 读取限制；M3 ProviderCodec 用 ContentPart 生成原生 content 数组，
 * M4 原生失败回退。contentVersion 标记内容协议版本，codec 变更时升版。
 */
sealed interface ContentPart {
    /** 内容协议版本（M1=v1；M3 多模态 content 数组落地时升 v2）。 */
    companion object { const val CONTENT_VERSION_V1 = "content_v1" }

    data class Text(val text: String) : ContentPart
    /** 图片引用：attachmentId=content URI，按引用在调用时读取字节。 */
    data class ImageRef(val attachmentId: String, val mime: String) : ContentPart
    data class AudioRef(val attachmentId: String, val mime: String) : ContentPart
    data class VideoRef(val attachmentId: String, val mime: String) : ContentPart
    data class FileRef(val attachmentId: String, val mime: String) : ContentPart
}

/**
 * ChatMessage → ContentPart 适配器（M1 纯逻辑）。
 *
 * Text 取自正文；ImageRef/FileRef/AudioRef 取自附件元信息（uri+mimeType），**不含 base64Data**。
 * 原正文不被视觉描述悄然改写——视觉结果经 attachmentId 关联，不并入 Text（设计§3.4）。
 */
object ContentAdapter {
    fun toContentParts(content: String?, attachments: List<Attachment>): List<ContentPart> {
        val parts = mutableListOf<ContentPart>()
        if (!content.isNullOrBlank()) parts += ContentPart.Text(content)
        for (att in attachments) {
            when (att.type) {
                AttachmentType.IMAGE -> parts += ContentPart.ImageRef(att.uri, att.mimeType)
                AttachmentType.FILE -> parts += ContentPart.FileRef(att.uri, att.mimeType)
                AttachmentType.AUDIO -> parts += ContentPart.AudioRef(att.uri, att.mimeType)
                AttachmentType.VIDEO -> parts += ContentPart.VideoRef(att.uri, att.mimeType)
            }
        }
        return parts
    }
}

/**
 * 媒体读取限制与结果分类（M1）——把 [ChatViewModel.resolveAttachmentData] 的 5MB/空流/mime 校验
 * 抽为纯逻辑，可单测。实际 ContentResolver 读取在 app 层；本对象只裁定字节/mime 是否放行。
 */
object ContentLimits {
    /** Read at most the limit plus one byte, so even a huge stream cannot allocate unbounded memory. */
    fun readBounded(input: java.io.InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var remaining = MAX_BYTES.toInt() + 1
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) break
            if (count == 0) break
            output.write(buffer, 0, count)
            remaining -= count
        }
        return output.toByteArray()
    }
    /** 单图字节上限（沿用既有 5MB——保护内存与 Vision API 限制）。 */
    const val MAX_BYTES = 5_000_000L

    /** 放行的图片 mime 前缀（image 斜杠后缀通配匹配）。 */
    val IMAGE_MIME_PREFIXES = setOf("image/")

    /** 按字节/mime 裁定读取结果。空流=Unavailable；超限=Oversize；非图片=Unsupported；否则 Ok。 */
    fun classify(mime: String, bytes: ByteArray): ReadOutcome {
        if (bytes.isEmpty()) return ReadOutcome.Unavailable("empty_stream")
        if (bytes.size > MAX_BYTES) return ReadOutcome.Oversize(bytes.size.toLong())
        val mimeLower = mime.lowercase()
        if (IMAGE_MIME_PREFIXES.none { mimeLower.startsWith(it) }) return ReadOutcome.Unsupported(mime)
        return ReadOutcome.Ok(bytes)
    }
}

/** 媒体读取结果（M1）——覆盖验收场景：Ok/Unavailable（不可用 URI）/Oversize（超尺寸）/Unsupported（非图片）。 */
sealed interface ReadOutcome {
    /** 读取成功，持有字节（仅运行时，不入库/日志）。 */
    data class Ok(val bytes: ByteArray) : ReadOutcome
    /** URI 不可用/空流——降级，不报错。 */
    data class Unavailable(val reason: String) : ReadOutcome
    /** 超尺寸——跳过，不内联 base64。 */
    data class Oversize(val byteCount: Long) : ReadOutcome
    /** mime 不支持——拒绝原生直送，可回退文本描述（M4）。 */
    data class Unsupported(val mime: String) : ReadOutcome

    fun isOk(): Boolean = this is Ok
}
