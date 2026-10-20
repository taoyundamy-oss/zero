package com.zero.v2.app.acceptance

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.common.Attachment
import com.zero.v2.core.common.AttachmentType
import com.zero.v2.core.common.AttachmentVault
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AttachmentAudioAcceptanceTest {
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
    private fun await(label: String): AccessibilityNodeInfo {
        repeat(100) {
            nodes().firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }?.let { return it }
            Thread.sleep(50)
        }
        fail("Audio UI missing: $label; actual=${nodes().map { it.text ?: it.contentDescription }}")
        error("unreachable")
    }
    private fun click(label: String) {
        val rect = Rect().also { await(label).getBoundsInScreen(it) }
        val now = android.os.SystemClock.uptimeMillis()
        for ((action, time) in listOf(android.view.MotionEvent.ACTION_DOWN to now, android.view.MotionEvent.ACTION_UP to now + 50)) {
            val event = android.view.MotionEvent.obtain(now, time, action, rect.exactCenterX(), rect.exactCenterY(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue("Native touch rejected: $label", instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
    }
    @Test fun actualAacPreviewPausesResumesCompletesAndReportsMissingFile() {
        val context = instrumentation.targetContext
        val bytes = instrumentation.context.assets.open("a8media/preview.m4a").use { it.readBytes() }
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val root = File(context.filesDir, "attachments")
        val owned = !File(root, hash).exists()
        val file = AttachmentVault(root).save(bytes)
        val activity = instrumentation.startActivitySync(Intent(context, VisualAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        val cases = JSONArray()
        val dir = File(context.filesDir, "a8audio").apply { mkdirs() }
        try {
            for ((dark, scale) in listOf(false to 1f, true to 2f)) {
                instrumentation.runOnMainSync {
                    activity.attachmentFixture = Attachment(file.toURI().toString(), "audio/mp4", AttachmentType.AUDIO)
                    activity.fixture = VisualAcceptanceActivity.Fixture("attachment", dark, false, scale, 360)
                }
                instrumentation.waitForIdleSync()
                Thread.sleep(300)
                println("Audio case: dark=$dark scale=$scale")
                click("预览音频")
                await("可播放")
                click("播放")
                await("正在播放")
                click("暂停")
                await("已暂停")
                Thread.sleep(2_500)
                await("已暂停")
                click("播放")
                await("播放完成")
                requireNotNull(instrumentation.uiAutomation.takeScreenshot()).let { image ->
                    File(dir, "audio-$dark-$scale.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    image.recycle()
                }
                click("关闭")
                await("音频附件")
                val missing = File(root, java.util.UUID.randomUUID().toString().replace("-", "").repeat(2))
                instrumentation.runOnMainSync { activity.attachmentFixture = Attachment(missing.toURI().toString(), "audio/mp4", AttachmentType.AUDIO) }
                instrumentation.waitForIdleSync()
                Thread.sleep(300)
                click("预览音频")
                await("音频无法播放：文件不可用或格式不受支持。")
                click("关闭")
                await("音频附件")
                cases.put(JSONObject().put("dark", dark).put("fontScale", scale).put("nativeDecoderCompleted", true)
                    .put("pausedBeyondClipDuration", true).put("resume", true).put("missingVisible", true))
            }
            File(context.filesDir, "a8audio-report.json").writeText(JSONObject().put("cases", cases)
                .put("scope", "actual_exoplayer_aac_preview_not_microphone_provider_or_speaker_audibility").toString(2))
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            if (owned) file.delete()
        }
    }
}
