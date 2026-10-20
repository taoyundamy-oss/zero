package com.zero.v2.app.acceptance

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.chat.attachmentViewIntent
import com.zero.v2.core.common.Attachment
import com.zero.v2.core.common.AttachmentType
import com.zero.v2.core.common.AttachmentVault
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** Installed Samsung viewers, actual generated files, no personal media or defaults changed. */
class ExternalMediaViewingAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private fun awaitPackage(packageName: String) {
        repeat(100) {
            if (automation.rootInActiveWindow?.packageName?.toString() == packageName) return
            Thread.sleep(100)
        }
        fail("Viewer not foreground: expected=$packageName actual=${automation.rootInActiveWindow?.packageName}")
    }
    private fun requestSystemBack() {
        // The host driver sends the actual ADB system key. Standalone runs fail the bounded return gate.
        File(instrumentation.targetContext.filesDir, "a8external-back.json").writeText(JSONObject()
            .put("nonce", java.util.UUID.randomUUID().toString()).put("requestBack", true).toString())
    }
    private fun colorCounts(bitmap: Bitmap): IntArray {
        val counts = IntArray(3)
        for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 3) {
            val c = bitmap.getPixel(x, y)
            if (Color.red(c) > 220 && Color.green(c) < 45 && Color.blue(c) < 45) counts[0]++
            if (Color.green(c) > 220 && Color.red(c) < 45 && Color.blue(c) < 45) counts[1]++
            if (Color.blue(c) > 220 && Color.red(c) < 45 && Color.green(c) < 45) counts[2]++
        }
        return counts
    }

    @Test fun galleryAndVideoShowGeneratedFramesAndReturnToCaller() {
        val context = instrumentation.targetContext
        File(context.filesDir, "a8external-back.json").writeText(JSONObject().put("requestBack", false).toString())
        val root = File(context.filesDir, "attachments")
        val dir = File(context.filesDir, "a8external").apply { mkdirs() }
        val image = Bitmap.createBitmap(600, 300, Bitmap.Config.ARGB_8888)
        for (x in 0 until image.width) for (y in 0 until image.height) {
            image.setPixel(x, y, when { x < 200 -> Color.RED; x < 400 -> Color.GREEN; else -> Color.BLUE })
        }
        val png = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        image.recycle()
        val rows = JSONArray()
        for ((bytes, mime, component) in listOf(
            Triple(png, "image/png", ComponentName("com.sec.android.gallery3d", "com.sec.android.gallery3d.app.GalleryActivity")),
            Triple(instrumentation.context.assets.open("a8media/viewer.mp4").use { it.readBytes() }, "video/mp4",
                ComponentName("com.samsung.android.video", "com.samsung.android.video.player.activity.MoviePlayer")))) {
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val owned = !File(root, hash).exists()
            val file = AttachmentVault(root).save(bytes)
            try {
            val activity = instrumentation.startActivitySync(Intent(context, VisualAcceptanceActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
            val uri = attachmentViewIntent(activity, Attachment(file.toURI().toString(), mime)).data!!
            try {
                instrumentation.runOnMainSync {
                    activity.attachmentFixture = Attachment(file.toURI().toString(), mime,
                        if (mime.startsWith("image")) AttachmentType.IMAGE else AttachmentType.VIDEO)
                    activity.fixture = VisualAcceptanceActivity.Fixture("attachment")
                }
                // Force only the test receiver selection, retaining the production MIME/URI/grant builder.
                val view = attachmentViewIntent(activity, activity.attachmentFixture!!).setComponent(component)
                assertEquals(mime, context.contentResolver.getType(uri))
                instrumentation.runOnMainSync { activity.startActivity(view) }
                awaitPackage(component.packageName)
                var rendered = false
                var finalCounts = IntArray(3)
                for (attempt in 0 until 30) {
                    val screenshot = requireNotNull(automation.takeScreenshot())
                    finalCounts = colorCounts(screenshot)
                    rendered = finalCounts.all { it > 2000 }
                    if (rendered || attempt == 29) {
                        File(dir, "${if (mime.startsWith("image")) "gallery" else "video"}.png").outputStream().use {
                            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    }
                    screenshot.recycle()
                    if (rendered) break
                    Thread.sleep(100)
                }
                assertTrue("Generated RGB stripes not rendered in $component: ${finalCounts.toList()}", rendered)
                requestSystemBack()
                awaitPackage(context.packageName)
                for (attempt in 0 until 50) {
                    if (activity.hasWindowFocus()) break
                    Thread.sleep(100)
                }
                assertTrue("Caller window did not recover", activity.hasWindowFocus())
                rows.put(JSONObject().put("mime", mime).put("receiver", component.flattenToString())
                    .put("sha256", hash).put("rgbSampleCounts", JSONArray(finalCounts.toList()))
                    .put("rendered", true).put("returned", true)
                    .put("resolverMime", context.contentResolver.getType(uri)))
            } finally {
                context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                instrumentation.runOnMainSync { activity.finish() }
            }
            } finally { if (owned) file.delete() }
        }
        File(context.filesDir, "a8external-report.json").writeText(JSONObject().put("cases", rows)
            .put("scope", "production_view_intent_explicit_installed_viewers_actual_render_and_back_not_audio_or_chooser").toString(2))
    }
}
