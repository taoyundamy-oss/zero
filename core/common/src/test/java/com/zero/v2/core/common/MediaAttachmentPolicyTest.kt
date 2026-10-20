package com.zero.v2.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaAttachmentPolicyTest {
    @Test fun `provider MPEG label is corrected only for recognizable MP4 audio headers`() {
        val mp4 = byteArrayOf(0, 0, 0, 24) + "ftypM4A ".toByteArray()
        assertEquals("audio/mp4", MediaAttachmentPolicy.mimeForImport("audio/mpeg", mp4))
        assertEquals("audio/mpeg", MediaAttachmentPolicy.mimeForImport("audio/mpeg", "ID3actual mp3".toByteArray()))
        assertEquals("audio/mpeg", MediaAttachmentPolicy.mimeForImport("audio/mpeg", byteArrayOf(1, 2)))
        assertEquals("audio/mpeg", MediaAttachmentPolicy.mimeForImport("audio/mpeg", byteArrayOf(0, 0, 0, 24) + "ftypzzzz".toByteArray()))
        assertEquals("video/mp4", MediaAttachmentPolicy.mimeForImport("video/mp4", mp4))
        assertEquals("application/octet-stream", MediaAttachmentPolicy.mimeForImport("application/octet-stream", mp4))
    }

    @Test fun `audio and video preserve identity and routing after process restart`() {
        for ((mime, type) in listOf("audio/wav" to AttachmentType.AUDIO, "video/mp4" to AttachmentType.VIDEO)) {
            val original = Attachment("file:/attachments/input", mime, MediaAttachmentPolicy.type(mime), route = MediaRoute.TOOLS)
            val restored = AttachmentCodec.decode(AttachmentCodec.encode(listOf(original))).single()
            assertEquals(original, restored)
            assertEquals(type, restored.type)
            assertFalse(ContentAdapter.toContentParts(null, listOf(restored)).single() is ContentPart.ImageRef)
        }
    }

    @Test fun `tool media references stay bounded and failed outputs are not displayed`() {
        val refs = List(10) { MediaRef("file:/attachments/$it", "video/mp4", MediaKind.VIDEO) }
        val result = FunctionResult.success("ok").copy(mediaRefs = refs)
        assertEquals(8, result.attachments().size)
        assertEquals(AttachmentType.VIDEO, result.attachments().first().type)
        assertEquals(emptyList<Attachment>(), result.copy(success = false).attachments())
    }

    @Test fun `import rejects oversize and unsupported content`() {
        assertThrows(IllegalArgumentException::class.java) {
            MediaAttachmentPolicy.validate("video/mp4", ByteArray(ContentLimits.MAX_BYTES.toInt() + 1))
        }
        assertThrows(IllegalArgumentException::class.java) { MediaAttachmentPolicy.validate("text/html", byteArrayOf(1)) }
    }
}
