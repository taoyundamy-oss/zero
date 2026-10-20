package com.zero.v2.services.mcp

import android.content.Context
import com.zero.v2.core.common.AttachmentVault
import com.zero.v2.core.common.ContentLimits
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.common.MediaKind
import com.zero.v2.core.common.MediaRef
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.io.File
import java.util.Base64
import javax.inject.Inject

/** Turns MCP payloads into durable references before any history or trace serialization. */
class McpResultDecoder internal constructor(private val vault: AttachmentVault) {
    @Inject constructor(@ApplicationContext context: Context) :
        this(AttachmentVault(File(context.filesDir, "attachments")))

    private class Parts {
        val text = mutableListOf<String>()
        val media = mutableListOf<MediaRef>()
        val errors = mutableListOf<String>()
    }

    fun decode(result: JSONObject): FunctionResult {
        val parts = Parts()
        val content = result.optJSONArray("content")
        val failed = result.optBoolean("isError", false)
        for (i in 0 until (content?.length() ?: 0)) {
            val item = content?.optJSONObject(i) ?: continue
            try {
                readItem(item, failed, parts)
            } catch (_: IllegalArgumentException) { parts.errors += "媒体结果格式或大小无效，请让工具返回文件路径" }
            catch (_: java.io.IOException) { parts.errors += "媒体附件保存失败" }
            catch (_: IllegalStateException) { parts.errors += "媒体附件保存失败" }
        }
        if (parts.text.isEmpty()) result.optJSONObject("structuredContent")?.let { parts.text += it.toString() }
        return finish(parts, failed)
    }

    private fun readItem(item: JSONObject, failed: Boolean, parts: Parts) {
        when (val type = item.optString("type")) {
            "text" -> parts.text += item.optString("text")
            "image", "audio" -> if (!failed) parts.media += save(item, "data", type, parts.media.size)
            "resource" -> readResource(item.optJSONObject("resource"), failed, parts)
            "resource_link" -> parts.text += "资源引用：${item.optString("uri")} (${item.optString("mimeType")})"
            else -> parts.errors += "未支持的MCP结果类型：$type"
        }
    }

    private fun readResource(resource: JSONObject?, failed: Boolean, parts: Parts) {
        if (resource == null) {
            parts.errors += "MCP资源结果为空"
        } else if (resource.has("text")) parts.text += resource.optString("text")
        else if (resource.has("blob") && !failed) parts.media += save(resource, "blob", null, parts.media.size)
    }

    private fun finish(parts: Parts, failed: Boolean): FunctionResult {
        val data = parts.text.joinToString("\n")
        return when {
            failed || data.startsWith("❌") || data.startsWith("⚠️") ->
                FunctionResult.failure(data.ifBlank { "MCP工具返回错误" })
            parts.errors.isNotEmpty() -> FunctionResult.failure((parts.text + parts.errors).joinToString("\n"))
            else -> FunctionResult(true, data.ifBlank {
                if (parts.media.isNotEmpty()) "已返回${parts.media.size}个媒体附件" else "工具执行成功（无文本输出）"
            }, mediaRefs = parts.media.takeIf { it.isNotEmpty() })
        }
    }

    private fun save(item: JSONObject, field: String, type: String?, count: Int): MediaRef {
        require(count < MAX_MEDIA)
        val mime = item.optString("mimeType").lowercase()
        val family = mime.substringBefore('/')
        require(type == null || type == family)
        val kind = when (family) {
            "image" -> MediaKind.IMAGE
            "audio" -> MediaKind.AUDIO
            "video" -> MediaKind.VIDEO
            else -> throw IllegalArgumentException("unsupported_media")
        }
        val encoded = item.optString(field)
        require(encoded.length <= (ContentLimits.MAX_BYTES + RAW_BLOCK_SIZE - 1) / RAW_BLOCK_SIZE * BASE64_BLOCK_SIZE)
        val bytes = Base64.getDecoder().decode(encoded)
        return MediaRef(vault.save(bytes).toURI().toString(), mime, kind)
    }

    private companion object {
        const val MAX_MEDIA = 8
        const val BASE64_BLOCK_SIZE = 4
        const val RAW_BLOCK_SIZE = 3
    }
}
