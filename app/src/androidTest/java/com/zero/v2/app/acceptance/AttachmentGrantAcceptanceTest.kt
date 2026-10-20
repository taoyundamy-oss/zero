package com.zero.v2.app.acceptance

import android.content.*
import android.graphics.Bitmap
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.chat.attachmentViewIntent
import com.zero.v2.core.common.Attachment
import com.zero.v2.core.common.AttachmentVault
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class AttachmentGrantAcceptanceTest {
    @Test fun externalUidReadsGrantedAttachmentAndCannotReadAfterRevocation() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(UUID.randomUUID().hashCode() or 0xff000000.toInt())
        val bytes = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        bitmap.recycle()
        val root = File(context.filesDir, "attachments")
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val owned = !File(root, hash).exists()
        val file = AttachmentVault(root).save(bytes)
        val activity = instrumentation.startActivitySync(Intent(context, VisualAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val view = attachmentViewIntent(activity, Attachment(file.toURI().toString(), "image/png"))
        val uri = requireNotNull(view.data)
        suspend fun receive(grant: Boolean): Intent {
            val action = "com.zero.v2.acceptance.${UUID.randomUUID()}"
            val reply = CompletableDeferred<Intent>()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) { intent?.let { reply.complete(it) } }
            }
            context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_EXPORTED)
            try {
                val request = Intent(view).setComponent(ComponentName("com.zero.v2.test", ExternalAttachmentReceiverActivity::class.java.name))
                    .putExtra("replyAction", action)
                if (!grant) request.flags = request.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION.inv()
                instrumentation.runOnMainSync { activity.startActivity(request) }
                return withTimeout(5_000) { reply.await() }
            } finally { context.unregisterReceiver(receiver) }
        }
        try {
            assertEquals("content", uri.scheme)
            val granted = receive(true)
            assertNotEquals(Process.myUid(), granted.getIntExtra("uid", -1))
            assertTrue(granted.getBooleanExtra("read", false))
            assertEquals(hash, granted.getStringExtra("hash"))
            assertEquals("image/png", granted.getStringExtra("mime"))
            assertEquals("image/png", granted.getStringExtra("resolverMime"))
            withTimeout(5_000) { while (!activity.hasWindowFocus()) delay(10) }
            context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val revoked = receive(false)
            assertNotEquals(Process.myUid(), revoked.getIntExtra("uid", -1))
            assertFalse(revoked.getBooleanExtra("read", true))
            assertTrue(revoked.getBooleanExtra("denied", false))
            File(context.filesDir, "a8grant-report.json").writeText(JSONObject().put("targetUid", Process.myUid())
                .put("resolverMime", granted.getStringExtra("resolverMime")).put("receiverUid", granted.getIntExtra("uid", -1)).put("grantedRead", true).put("revokedDenied", true)
                .put("scope", "production_view_intent_to_external_test_activity_not_gallery_ui").toString(2))
        } finally {
            context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            instrumentation.runOnMainSync { activity.finish() }
            if (owned) file.delete()
        }
    }
}
