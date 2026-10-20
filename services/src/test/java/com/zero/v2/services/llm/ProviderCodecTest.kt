package com.zero.v2.services.llm

import com.zero.v2.core.common.Attachment
import com.zero.v2.core.common.AttachmentType
import com.zero.v2.core.common.ContentAdapter
import com.zero.v2.core.common.ContentPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3：ProviderCodec content 数组生成（落地计划 M3 验收——请求快照覆盖纯文本/单图/多图/媒体上传失败）。
 */
class ProviderCodecTest {
    @Test fun `tool choice survives persistence and never sends image payload`() {
        val attachment = Attachment("file:/attachments/input", "image/png", base64Data = "data:image/png;base64,AA==",
            route = com.zero.v2.core.common.MediaRoute.TOOLS)
        val message = com.zero.v2.core.common.ChatMessage(role = com.zero.v2.core.common.MessageRole.USER,
            content = "识图", attachments = listOf(attachment))
        val before = codec.messageContent(message, true)
        val after = codec.messageContent(message.copy(attachments = com.zero.v2.core.common.AttachmentCodec.decode(
            com.zero.v2.core.common.AttachmentCodec.encode(message.attachments))), true)
        assertEquals(before, after)
        assertTrue(before.all { it["type"] == "text" })
        assertTrue(before.toString().contains(attachment.uri))
    }
    @Test fun `non user image output remains a reference in chat completions`() {
        val roles = listOf(com.zero.v2.core.common.MessageRole.ASSISTANT, com.zero.v2.core.common.MessageRole.TOOL)
        for (role in roles) {
            val attachment = Attachment("file:/attachments/output", "image/png",
                base64Data = "data:image/png;base64,AA==")
            val parts = codec.messageContent(com.zero.v2.core.common.ChatMessage(
                role = role, content = "生成结果", attachments = listOf(attachment)), true)
            assertTrue(parts.all { it["type"] == "text" })
            assertTrue(parts.toString().contains(attachment.uri))
        }
    }

    @Test
    fun `text model preserves full attachment reference across persistence`() {
        val attachment = Attachment(uri = "file:/data/user/0/com.zero.v2/files/attachments/" + "a".repeat(64),
            mimeType = "image/png", type = AttachmentType.IMAGE, base64Data = "data:image/png;base64,AA==")
        val message = com.zero.v2.core.common.ChatMessage(role = com.zero.v2.core.common.MessageRole.USER,
            content = "看图", attachments = listOf(attachment))
        val before = codec.messageContent(message, imageInput = false)
        val restored = codec.messageContent(
            message.copy(attachments = listOf(attachment.copy(base64Data = null))), false,
        )
        assertEquals(before, restored)
        assertTrue(before.toString().contains(attachment.uri))
        assertFalse(before.toString().contains("图片不可用"))
        assertFalse(before.toString().contains("base64"))
    }

    private val codec = OpenAiCompatibleCodec()
    private val goodResolver: (String) -> String? = { uri -> "data:image/jpeg;base64,BLOB_$uri" }
    private val failingResolver: (String) -> String? = { null }

    private fun imagePart(uri: String) = ContentPart.ImageRef(uri, "image/jpeg")
    private fun textPart(t: String) = ContentPart.Text(t)

    // ── 纯文本 ──

    @Test
    fun `text only produces single text content item`() {
        val arr = codec.contentArray(listOf(textPart("你好")), goodResolver)
        assertEquals(1, arr.size)
        assertEquals("text", arr[0]["type"])
        assertEquals("你好", arr[0]["text"])
    }

    // ── 单图 ──

    @Test
    fun `single image produces text plus image_url with data url`() {
        val arr = codec.contentArray(listOf(textPart("看图"), imagePart("uri://1")), goodResolver)
        assertEquals(2, arr.size)
        assertEquals("text", arr[0]["type"])
        val img = arr[1]
        assertEquals("image_url", img["type"])
        @Suppress("UNCHECKED_CAST")
        val imgUrl = img["image_url"] as Map<String, Any>
        assertEquals("data:image/jpeg;base64,BLOB_uri://1", imgUrl["url"])
    }

    // ── 多图 ──

    @Test
    fun `multi image produces text plus multiple image_urls`() {
        val arr = codec.contentArray(
            listOf(textPart("这三张"), imagePart("uri://a"), imagePart("uri://b"), imagePart("uri://c")),
            goodResolver,
        )
        assertEquals(4, arr.size)
        assertEquals(3, arr.count { it["type"] == "image_url" })
    }

    // ── 媒体上传失败：降级标记，不静默丢图 ──

    @Test
    fun `media upload failure degrades to text marker not silent drop`() {
        val arr = codec.contentArray(listOf(textPart("看图"), imagePart("uri://bad")), failingResolver)
        assertEquals(2, arr.size)
        assertEquals("text", arr[0]["type"])
        assertEquals("text", arr[1]["type"]) // 降级为 text
        val marker = arr[1]["text"].toString()
        assertTrue("降级标记应含'图片不可用'", marker.contains("图片不可用"))
    }

    // ── 不支持的原生音频/文件：文本占位 ──

    @Test
    fun `audio and file parts degrade to text placeholder`() {
        val arr = codec.contentArray(
            listOf(ContentPart.AudioRef("uri://a", "audio/wav"), ContentPart.FileRef("uri://f", "application/pdf")),
            goodResolver,
        )
        assertEquals(2, arr.size)
        assertTrue(arr.all { it["type"] == "text" })
    }

    // ── supportsImageInput：按 profile 显式能力 ──

    @Test
    fun `supportsImageInput true when profile declares IMAGE_INPUT`() {
        val profile = LlmProfile(
            id = "qwen-vl", displayName = "vl", baseUrl = "x", apiKeyRef = "k",
            modelId = "qwen3-vl-flash", kind = LlmProfile.Kind.VISION,
            capabilities = setOf(ModelCapability.CHAT, ModelCapability.IMAGE_INPUT, ModelCapability.VISION),
        )
        assertTrue(codec.supportsImageInput(profile))
    }

    @Test
    fun `supportsImageInput false when profile lacks IMAGE_INPUT even if modelId contains vl`() {
        // modelId 含 "vl" 但未声明 IMAGE_INPUT → 不支持（不靠 modelId 推断）
        val profile = LlmProfile(
            id = "fake", displayName = "f", baseUrl = "x", apiKeyRef = "k",
            modelId = "some-vl-model", kind = LlmProfile.Kind.CHAT,
            capabilities = setOf(ModelCapability.CHAT),
        )
        assertFalse(codec.supportsImageInput(profile))
    }

    // ── needsArray ──

    @Test
    fun `needsArray false for text only`() {
        assertFalse(OpenAiCompatibleCodec.needsArray(listOf(textPart("only text"))))
    }

    @Test
    fun `needsArray true when image present`() {
        assertTrue(OpenAiCompatibleCodec.needsArray(listOf(textPart("t"), imagePart("uri://1"))))
    }

    // ── ContentAdapter → codec 端到端（附件→ContentPart→content 数组）──

    @Test
    fun `attachment to content array end to end`() {
        val atts = listOf(
            Attachment(uri = "uri://img1", mimeType = "image/jpeg", type = AttachmentType.IMAGE, base64Data = null),
            Attachment(uri = "uri://img2", mimeType = "image/png", type = AttachmentType.IMAGE, base64Data = null),
        )
        val parts = ContentAdapter.toContentParts("看这两张", atts)
        val arr = codec.contentArray(parts, goodResolver)
        assertEquals(3, arr.size) // 1 text + 2 image_url
        assertEquals(2, arr.count { it["type"] == "image_url" })
    }
}
