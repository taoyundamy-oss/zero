package com.zero.v2.app.acceptance

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.chat.attachmentViewIntent
import com.zero.v2.core.common.Attachment
import com.zero.v2.core.common.AttachmentVault
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AttachmentMimeMetadataAcceptanceTest {
    @Test fun vaultViewMimeMatchesResolverWhileLegacyAndInvalidMetadataRemainCompatible() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.filesDir, "attachments")
        val cases = JSONArray()
        for ((asset, mime) in listOf("tone.m4a" to "audio/mp4", "viewer.mp4" to "video/mp4")) {
            val bytes = instrumentation.context.assets.open("a8media/$asset").use { it.readBytes() }
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val owned = !File(root, hash).exists()
            val file = AttachmentVault(root).save(bytes)
            try {
                val view = attachmentViewIntent(context, Attachment(file.toURI().toString(), mime))
                val uri = requireNotNull(view.data)
                val upperCase = attachmentViewIntent(context, Attachment(file.toURI().toString(), mime.uppercase(java.util.Locale.ROOT)))
                assertEquals(mime, upperCase.type)
                assertEquals(mime, context.contentResolver.getType(upperCase.data!!))
                assertEquals(mime, view.type)
                assertEquals(mime, context.contentResolver.getType(uri))
                assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, view.flags)
                assertArrayEquals(bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
                val legacy = FileProvider.getUriForFile(context, "${context.packageName}.attachments", file)
                assertEquals("application/octet-stream", context.contentResolver.getType(legacy))
                assertArrayEquals(bytes, context.contentResolver.openInputStream(legacy)!!.use { it.readBytes() })
                val invalid = uri.buildUpon().clearQuery().appendQueryParameter("zero_mime", "text/plain\r\ninvalid").build()
                assertEquals("application/octet-stream", context.contentResolver.getType(invalid))
                val outside = File(context.filesDir, "mime-outside-${java.util.UUID.randomUUID()}").apply { writeBytes(bytes) }
                try {
                    val traversal = uri.buildUpon().encodedPath("/media/..%2F${outside.name}").build()
                    try {
                        context.contentResolver.openInputStream(traversal)?.use { it.read() }
                        fail("MIME metadata must not permit reads outside the configured vault")
                    } catch (_: IllegalArgumentException) {
                        // The base FileProvider path contract is preserved.
                    } catch (_: SecurityException) {
                        // Some platform versions report this boundary as SecurityException.
                    }
                } finally { outside.delete() }
                cases.put(JSONObject().put("mime", mime).put("caseNormalized", true).put("resolverMatches", true).put("legacyReadable", true)
                    .put("pathEscapeRejected", true).put("invalidMimeRejected", true).put("bytesMatch", true))
            } finally { if (owned) file.delete() }
        }
        File(context.filesDir, "a8mime-report.json").writeText(JSONObject().put("cases", cases).toString(2))
    }
}
