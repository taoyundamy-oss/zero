package com.zero.v2.app.acceptance

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.common.Attachment
import com.zero.v2.core.common.AttachmentVault
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** Production attachment UI with actual local bytes; no personal media or provider calls. */
class AttachmentViewingAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            result.add(node)
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
        return result
    }
    private fun awaitLabel(label: String): AccessibilityNodeInfo {
        repeat(50) {
            val current = nodes()
            (current.firstOrNull { it.contentDescription?.toString() == label }
                ?: current.firstOrNull { it.text?.toString()?.contains(label) == true })?.let { return it }
            Thread.sleep(100)
        }
        fail("Missing attachment UI: $label; actual=${nodes().map { it.text ?: it.contentDescription }}")
        error("unreachable")
    }
    private fun click(node: AccessibilityNodeInfo) {
        var owner: AccessibilityNodeInfo? = node
        while (owner != null && !owner.isClickable) owner = owner.parent
        assertTrue(requireNotNull(owner).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    @Test fun realThumbnailMissingFileFeedbackAndRemoveAcrossThemes() {
        val context = instrumentation.targetContext
        val root = File(context.filesDir, "attachments")
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val existed = File(root, digest).exists()
        val valid = AttachmentVault(root).save(bytes)
        // Owned absent hash path: no existing attachment is deleted or overwritten.
        val missing = File(root, java.util.UUID.randomUUID().toString().replace("-", "").repeat(2))
        assertFalse(missing.exists())
        val activity = instrumentation.startActivitySync(Intent(context, VisualAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        val cases = JSONArray()
        try {
            for (dark in listOf(false, true)) for (scale in listOf(1f, 2f)) {
                instrumentation.runOnMainSync {
                    activity.attachmentRemovals = 0
                    activity.attachmentFixture = Attachment(valid.toURI().toString(), "image/png")
                    activity.fixture = VisualAcceptanceActivity.Fixture("attachment", dark, false, scale, 360)
                }
                awaitLabel("图片预览")
                awaitLabel("点击打开")
                instrumentation.runOnMainSync { activity.attachmentFixture = Attachment(missing.toURI().toString(), "image/png") }
                awaitLabel("预览不可用")
                click(awaitLabel("图片附件"))
                awaitLabel("无法打开附件")
                instrumentation.uiAutomation.waitForIdle(250, 5_000)
                val iconBounds = Rect().also { awaitLabel("移除附件").getBoundsInScreen(it) }
                // Measure the actual click action containing the icon rather than its 24dp glyph.
                val owner = requireNotNull(nodes().filter { node ->
                    val bounds = Rect().also { node.getBoundsInScreen(it) }
                    node.isClickable && node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK } &&
                        bounds.contains(iconBounds)
                }.minByOrNull { node ->
                    val bounds = Rect().also { node.getBoundsInScreen(it) }
                    bounds.width() * bounds.height()
                })
                val rect = Rect().also { owner.getBoundsInScreen(it) }
                assertTrue("Remove target too small: $rect", rect.height() / activity.resources.displayMetrics.density >= 48)
                assertTrue(owner.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                // Accessibility actions are queued asynchronously; wait for the observable callback.
                for (attempt in 0 until 50) {
                    if (activity.attachmentRemovals == 1) break
                    Thread.sleep(100)
                }
                assertEquals(1, activity.attachmentRemovals)
                val dir = File(context.filesDir, "a7-visual").apply { mkdirs() }
                requireNotNull(instrumentation.uiAutomation.takeScreenshot()).let { shot ->
                    File(dir, "attachment-$dark-$scale.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    shot.recycle()
                }
                cases.put(JSONObject().put("dark", dark).put("fontScale", scale).put("thumbnail", true)
                    .put("previewFailureVisible", true).put("openFailureVisible", true).put("remove", true))
            }
            File(context.filesDir, "a8view-report.json").writeText(JSONObject().put("cases", cases)
                .put("scope", "production_tile_actual_bytes_not_external_gallery_playback").toString(2))
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            if (!existed) valid.delete()
        }
    }
}
