package com.zero.v2.app.acceptance

import android.graphics.BitmapFactory
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.common.*
import com.zero.v2.services.llm.MediaDecodingAcceptanceAccess
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID

class AudioVideoTransferAcceptanceTest {
    @Test fun actualAudioVideoDecodeImportAndDebianTransferPreserveBytes() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(instrumentation is ProviderAcceptanceRunner)
        val context = instrumentation.targetContext
        val entry = EntryPointAccessors.fromApplication(context, RegenerationAcceptanceEntryPoint::class.java)
        assertNotNull("real supervisor must be reachable", entry.termux().health())
        val root = File(context.filesDir, "attachments").also { it.mkdirs() }
        val rows = JSONArray()
        for ((asset, kind) in listOf("tone.m4a" to AttachmentType.AUDIO, "frames.mp4" to AttachmentType.VIDEO)) {
            val bytes = instrumentation.context.assets.open("a8media/$asset").use { it.readBytes() }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val source = File(root, "acceptance-${UUID.randomUUID()}-$asset").also { it.writeBytes(bytes) }
            val destination = File(root, hash)
            val owned = !destination.exists()
            try {
                val input = FileProvider.getUriForFile(context, "${context.packageName}.attachments", source)
                val prepared = entry.attachments().prepare(Attachment(input.toString(), "application/octet-stream", route = MediaRoute.TOOLS))
                assertEquals(kind, prepared.type)
                assertEquals(if (kind == AttachmentType.AUDIO) "audio/mp4" else "video/mp4", prepared.mimeType)
                assertEquals(MediaRoute.TOOLS, prepared.route)
                assertNull(prepared.base64Data)
                val durable = File(java.net.URI(prepared.uri))
                assertArrayEquals(bytes, durable.readBytes())
                assertTrue(source.delete())
                val row = JSONObject().put("asset", asset).put("sha256", hash).put("mime", prepared.mimeType)
                if (kind == AttachmentType.AUDIO) {
                    val wav = MediaDecodingAcceptanceAccess.audio(durable)
                    assertEquals("RIFF", wav.copyOfRange(0, 4).toString(Charsets.US_ASCII))
                    assertEquals("WAVE", wav.copyOfRange(8, 12).toString(Charsets.US_ASCII))
                    val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
                    assertEquals(16000, header.getInt(24))
                    assertEquals(1, header.getShort(22).toInt())
                    assertEquals(16, header.getShort(34).toInt())
                    assertTrue(wav.size > 44)
                    row.put("pcmBytes", wav.size - 44)
                } else {
                    val frames = MediaDecodingAcceptanceAccess.video(durable)
                    assertEquals(3, frames.size)
                    frames.forEach {
                        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(it, 0, it.size))
                        assertTrue(bitmap.width in 1..1024 && bitmap.height in 1..1024)
                        bitmap.recycle()
                    }
                    row.put("frames", frames.size)
                }
                val exported = entry.attachments().export(prepared.uri)
                assertTrue(exported.errorMessage ?: exported.data, exported.success)
                val path = JSONObject(exported.data).getString("path")
                assertEquals("/root/zero/attachments/$hash", path)
                assertArrayEquals(bytes, entry.termux().readMediaFile(path))
                val published = entry.attachments().publish(path, prepared.mimeType)
                assertTrue(published.errorMessage ?: published.data, published.success)
                val ref = published.mediaRefs.orEmpty().single()
                assertEquals(MediaKind.valueOf(kind.name), ref.kind)
                assertArrayEquals(bytes, File(java.net.URI(ref.attachmentId)).readBytes())
                row.put("debianPath", path).put("transferBytes", bytes.size).put("publishKind", ref.kind.name)
                rows.put(row)
            } finally {
                source.delete()
                if (owned) destination.delete()
            }
        }
        File(context.filesDir, "a8media-report.json").writeText(JSONObject().put("rows", rows)
            .put("cloudRequests", 0).put("scope", "real_android_codecs_and_supervisor_file_transfer_not_dynamic_analysis")
            .put("remoteFixturesRetained", true).toString(2))
    }
}
