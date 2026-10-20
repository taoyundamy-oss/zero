package com.zero.v2.services.mcp

import com.zero.v2.core.common.AttachmentVault
import com.zero.v2.core.common.MediaKind
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

class McpResultDecoderTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `image audio and video blobs become owned references without base64 in text`() {
        val vault = AttachmentVault(folder.newFolder())
        val decoder = McpResultDecoder(vault)
        val bytes = "private-test-payload".toByteArray()
        val encoded = Base64.getEncoder().encodeToString(bytes)
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", "done"))
            .put(media("image", "image/png", encoded))
            .put(media("audio", "audio/wav", encoded))
            .put(JSONObject().put("type", "resource").put("resource",
                JSONObject().put("mimeType", "video/mp4").put("blob", encoded)))
        val result = decoder.decode(JSONObject().put("content", content))
        assertTrue(result.success)
        assertEquals("done", result.data)
        assertEquals(listOf(MediaKind.IMAGE, MediaKind.AUDIO, MediaKind.VIDEO), result.mediaRefs!!.map { it.kind })
        result.mediaRefs!!.forEach { assertArrayEquals(bytes, vault.resolve(it.attachmentId).readBytes()) }
        assertFalse(result.toString().contains(encoded))
    }

    @Test fun `remote resource links remain references and are never read as local paths`() {
        val decoder = McpResultDecoder(AttachmentVault(folder.newFolder()))
        val result = decoder.decode(JSONObject().put("content", JSONArray().put(
            JSONObject().put("type", "resource_link").put("uri", "file:///etc/private"))))
        assertTrue(result.success)
        assertNull(result.mediaRefs)
        assertTrue(result.data.contains("file:///etc/private"))
    }

    @Test fun `invalid media and excess references cannot silently succeed`() {
        val decoder = McpResultDecoder(AttachmentVault(folder.newFolder()))
        for (item in listOf(media("audio", "image/png", "YQ=="), media("image", "image/png", "%%%"),
            media("image", "image/png", ""))) {
            assertFalse(decoder.decode(JSONObject().put("content", JSONArray().put(item))).success)
        }
        val content = JSONArray()
        repeat(9) { content.put(media("image", "image/png", "YQ==")) }
        assertFalse(decoder.decode(JSONObject().put("content", content)).success)
    }

    @Test fun `error result never publishes media and structured-only results retain data`() {
        val root = folder.newFolder()
        val decoder = McpResultDecoder(AttachmentVault(root))
        val result = decoder.decode(JSONObject().put("isError", true).put("content",
            JSONArray().put(media("audio", "audio/wav", "YQ=="))))
        assertFalse(result.success)
        assertNull(result.mediaRefs)
        assertTrue(root.listFiles()!!.isEmpty())
        val structured = decoder.decode(JSONObject().put("structuredContent", JSONObject().put("value", 42)))
        assertEquals(42, JSONObject(structured.data).getInt("value"))
    }

    private fun media(type: String, mime: String, data: String) =
        JSONObject().put("type", type).put("mimeType", mime).put("data", data)
}
