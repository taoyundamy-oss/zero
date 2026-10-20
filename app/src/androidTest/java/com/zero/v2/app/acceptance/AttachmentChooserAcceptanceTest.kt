package com.zero.v2.app.acceptance

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.common.Attachment
import com.zero.v2.core.common.AttachmentType
import com.zero.v2.core.common.AttachmentVault
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** Production tile -> OS resolver -> one-time installed Gallery choice; never sets a default. */
class AttachmentChooserAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            result.add(node)
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(automation.rootInActiveWindow)
        return result
    }
    private fun await(label: String): AccessibilityNodeInfo {
        repeat(100) {
            nodes().firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }?.let { return it }
            Thread.sleep(50)
        }
        fail("Chooser label missing: $label; actual=${nodes().map { it.text ?: it.contentDescription }}")
        error("unreachable")
    }
    private fun tap(label: String) {
        val rect = Rect().also { await(label).getBoundsInScreen(it) }
        val now = android.os.SystemClock.uptimeMillis()
        for ((action, time) in listOf(android.view.MotionEvent.ACTION_DOWN to now, android.view.MotionEvent.ACTION_UP to now + 50)) {
            val event = android.view.MotionEvent.obtain(now, time, action, rect.exactCenterX(), rect.exactCenterY(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
    }
    @Test fun productionTileUsesOneTimeGalleryChoiceAndReturns() {
        val context = instrumentation.targetContext
        val dir = File(context.filesDir, "a8chooser").apply { mkdirs() }
        val marker = File(context.filesDir, "a8external-back.json")
        marker.writeText(JSONObject().put("requestBack", false).toString())
        val bitmap = Bitmap.createBitmap(600, 300, Bitmap.Config.ARGB_8888)
        for (x in 0 until 600) for (y in 0 until 300) bitmap.setPixel(x, y,
            when { x < 200 -> Color.RED; x < 400 -> Color.GREEN; else -> Color.BLUE })
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val root = File(context.filesDir, "attachments")
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val owned = !File(root, hash).exists()
        val file = AttachmentVault(root).save(bytes)
        try {
            val activity = instrumentation.startActivitySync(Intent(context, VisualAcceptanceActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
            try {
                instrumentation.runOnMainSync {
                    activity.attachmentFixture = Attachment(file.toURI().toString(), "image/png", AttachmentType.IMAGE)
                    activity.fixture = VisualAcceptanceActivity.Fixture("attachment")
                }
                instrumentation.waitForIdleSync()
                Thread.sleep(300)
                tap("图片附件")
                await("仅此一次")
                Thread.sleep(500) // Resolver entry animation changes native button bounds.
                val resolverPackage = automation.rootInActiveWindow?.packageName?.toString()
                assertTrue("Unexpected resolver: $resolverPackage", resolverPackage in listOf("android", "com.android.intentresolver"))
                requireNotNull(automation.takeScreenshot()).let { image ->
                    File(dir, "resolver.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    image.recycle()
                }
                tap("相册")
                Thread.sleep(500)
                tap("仅此一次")
                repeat(100) {
                    if (automation.rootInActiveWindow?.packageName?.toString() != "com.sec.android.gallery3d") Thread.sleep(50)
                }
                assertEquals("com.sec.android.gallery3d", automation.rootInActiveWindow?.packageName?.toString())
                var rendered = false
                repeat(30) {
                    if (!rendered) {
                        val screen = requireNotNull(automation.takeScreenshot())
                        val counts = IntArray(3)
                        for (y in 0 until screen.height step 3) for (x in 0 until screen.width step 3) {
                            val c = screen.getPixel(x, y)
                            if (Color.red(c) > 220 && Color.green(c) < 45 && Color.blue(c) < 45) counts[0]++
                            if (Color.green(c) > 220 && Color.red(c) < 45 && Color.blue(c) < 45) counts[1]++
                            if (Color.blue(c) > 220 && Color.red(c) < 45 && Color.green(c) < 45) counts[2]++
                        }
                        rendered = counts.all { it > 2000 }
                        if (rendered) File(dir, "gallery.png").outputStream().use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        screen.recycle()
                        if (!rendered) Thread.sleep(100)
                    }
                }
                assertTrue("Selected Gallery did not render generated RGB", rendered)
                marker.writeText(JSONObject().put("nonce", java.util.UUID.randomUUID().toString()).put("requestBack", true).toString())
                await("图片附件")
                assertEquals(context.packageName, automation.rootInActiveWindow?.packageName?.toString())
                File(context.filesDir, "a8chooser-report.json").writeText(JSONObject().put("resolver", resolverPackage)
                    .put("oneTimeChoice", true).put("receiver", "com.sec.android.gallery3d").put("rendered", true)
                    .put("returned", true).put("scope", "production_image_tile_os_resolver_one_time_not_all_file_types").toString(2))
            } finally { instrumentation.runOnMainSync { activity.finish() } }
        } finally { if (owned) file.delete() }
    }
}
