package com.zero.v2.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M1：ContentPart 适配 + AttachmentCodec 编解码 + ContentLimits 读取限制（落地计划 M1 验收）。
 */
class ContentPartTest {

    private fun image(uri: String, mime: String = "image/jpeg", base64: String? = null) =
        Attachment(uri = uri, mimeType = mime, type = AttachmentType.IMAGE, base64Data = base64)

    // ── ContentAdapter：文本/单图/多图/base64 排除 ──

    @Test
    fun `text only produces single Text part`() {
        val parts = ContentAdapter.toContentParts("你好", emptyList())
        assertEquals(1, parts.size)
        assertTrue(parts[0] is ContentPart.Text)
    }

    @Test
    fun `single image produces Text plus ImageRef without base64`() {
        val att = image("content://media/img/1", base64 = "data:image/jpeg;base64,/9j/4AAQ")
        val parts = ContentAdapter.toContentParts("看这张图", listOf(att))
        assertEquals(2, parts.size)
        assertTrue(parts.any { it is ContentPart.Text })
        val img = parts.filterIsInstance<ContentPart.ImageRef>().single()
        assertEquals("content://media/img/1", img.attachmentId)
        assertEquals("image/jpeg", img.mime)
        // ContentPart 不持 base64——引用 only
        assertFalse(img.toString().contains("base64"))
    }

    @Test
    fun `multi image produces one Text plus multiple ImageRefs`() {
        val atts = listOf(image("uri://a"), image("uri://b"), image("uri://c"))
        val parts = ContentAdapter.toContentParts("这三张", atts)
        assertEquals(4, parts.size) // 1 Text + 3 ImageRef
        assertEquals(3, parts.filterIsInstance<ContentPart.ImageRef>().size)
    }

    @Test
    fun `FileRef for file attachments`() {
        val att = Attachment(uri = "content://file/doc.pdf", mimeType = "application/pdf", type = AttachmentType.FILE)
        val parts = ContentAdapter.toContentParts(null, listOf(att))
        assertEquals(1, parts.size)
        assertTrue(parts[0] is ContentPart.FileRef)
    }

    // ── AttachmentCodec：round-trip + base64 不入库 ──

    @Test
    fun `encode decode round trip preserves uri mime type`() {
        val atts = listOf(
            image("content://media/img/1", "image/png"),
            Attachment(uri = "content://file/doc.pdf", mimeType = "application/pdf", type = AttachmentType.FILE),
        )
        val json = AttachmentCodec.encode(atts)
        val decoded = AttachmentCodec.decode(json)
        assertEquals(2, decoded.size)
        assertEquals("content://media/img/1", decoded[0].uri)
        assertEquals("image/png", decoded[0].mimeType)
        assertEquals(AttachmentType.IMAGE, decoded[0].type)
        assertEquals("application/pdf", decoded[1].mimeType)
        assertEquals(AttachmentType.FILE, decoded[1].type)
    }

    @Test
    fun `encoded json does NOT contain base64 data`() {
        val att = image("content://media/img/1", base64 = "data:image/jpeg;base64,BIGBLOB")
        val json = AttachmentCodec.encode(listOf(att))
        assertFalse("base64 不得入库", json.contains("BIGBLOB"))
        assertFalse(json.contains("base64Data"))
    }

    @Test
    fun `decoded attachments have null base64 data`() {
        val att = image("content://media/img/1", base64 = "data:image/jpeg;base64,BIGBLOB")
        val decoded = AttachmentCodec.decode(AttachmentCodec.encode(listOf(att)))
        assertEquals(1, decoded.size)
        assertEquals(null, decoded[0].base64Data) // 恢复后无 base64
    }

    @Test
    fun `decode empty or malformed returns empty list without throwing`() {
        assertEquals(emptyList<Attachment>(), AttachmentCodec.decode(null))
        assertEquals(emptyList<Attachment>(), AttachmentCodec.decode(""))
        assertEquals(emptyList<Attachment>(), AttachmentCodec.decode("[]"))
        assertEquals(emptyList<Attachment>(), AttachmentCodec.decode("not json"))
    }

    @Test
    fun `uri with special characters survives round trip`() {
        val att = image("content://media/外?query=a&b=c/图.png", "image/png")
        val decoded = AttachmentCodec.decode(AttachmentCodec.encode(listOf(att)))
        assertEquals(1, decoded.size)
        assertEquals(att.uri, decoded[0].uri)
    }

    // ── ContentLimits：超尺寸/不可用/非图片 ──

    @Test
    fun `oversize bytes classified as Oversize`() {
        val big = ByteArray((ContentLimits.MAX_BYTES + 1).toInt())
        val outcome = ContentLimits.classify("image/jpeg", big)
        assertTrue(outcome is ReadOutcome.Oversize)
        assertFalse(outcome.isOk())
    }

    @Test
    fun `empty stream classified as Unavailable`() {
        val outcome = ContentLimits.classify("image/jpeg", ByteArray(0))
        assertTrue(outcome is ReadOutcome.Unavailable)
    }

    @Test
    fun `non image mime classified as Unsupported`() {
        val outcome = ContentLimits.classify("application/pdf", ByteArray(100))
        assertTrue(outcome is ReadOutcome.Unsupported)
    }

    @Test
    fun `valid image under limit classified as Ok`() {
        val outcome = ContentLimits.classify("image/jpeg", ByteArray(1024))
        assertTrue(outcome.isOk())
    }
}
