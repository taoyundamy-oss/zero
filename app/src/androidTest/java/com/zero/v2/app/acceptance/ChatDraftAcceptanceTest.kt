package com.zero.v2.app.acceptance

import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.common.MediaRoute
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ChatDraftAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    @Test fun nativeImeSendInvokesComposerAndRespectsDisabledSend() {
        val cases = JSONArray()
        for (disabled in listOf(false, true)) for (dark in listOf(false, true)) for (scale in listOf(1f, 2f)) {
            val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, ChatDraftAcceptanceActivity::class.java)
                .putExtra("dark", dark).putExtra("fontScale", scale).putExtra("sendDisabled", disabled)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ChatDraftAcceptanceActivity
            try {
                waitFor { nodes().any { it.isEditable } }
                val draft = "IME local $disabled/$dark/$scale"
                val editor = nodes().first { it.isEditable }
                assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, draft)
                }))
                waitFor { activity.imeVisible && activity.model.draft.text.value == draft }
                val action = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                waitFor { nodes().any { it.isEditable && it.actionList.any { entry -> entry.id == action } } }
                assertTrue(nodes().first { it.isEditable }.performAction(action))
                if (!disabled) waitFor { activity.sends == 1 } else {
                    instrumentation.waitForIdleSync(); Thread.sleep(300)
                    assertEquals("IME must obey the same disabled-send guard", 0, activity.sends)
                }
                assertEquals(draft, activity.model.draft.text.value)
                screenshot("ime-send-$disabled-$dark-$scale")
                cases.put(JSONObject().put("sendDisabled", disabled).put("dark", dark).put("fontScale", scale)
                    .put("imeVisible", activity.imeVisible).put("sends", activity.sends).put("action", "ACTION_IME_ENTER"))
            } finally { instrumentation.runOnMainSync { activity.finish() } }
        }
        File(instrumentation.targetContext.filesDir, "a8a2-draft/ime-send-report.json").writeText(JSONObject().put("cases", cases)
            .put("scope", "native_accessibility_ime_action_on_production_composer_not_physical_samsung_key")
            .put("providerRequests", 0).toString(2))
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null || !node.refresh()) return
            result += node
            for (index in 0 until node.childCount) visit(node.getChild(index))
        }
        visit(automation.rootInActiveWindow); return result
    }
    private fun waitFor(predicate: () -> Boolean) {
        repeat(60) { if (predicate()) return; Thread.sleep(100) }
        fail("Draft/IME condition not reached")
    }
    private fun click(description: String) {
        var target: AccessibilityNodeInfo? = nodes().first { it.contentDescription?.toString() == description || it.text?.toString() == description }
        while (target != null && !target.isClickable) target = target.parent
        assertTrue(requireNotNull(target).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    private fun screenshot(name: String) {
        val image = requireNotNull(automation.takeScreenshot())
        File(instrumentation.targetContext.filesDir, "a8a2-draft/$name.png").also { it.parentFile!!.mkdirs() }
            .outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    @Test fun keyboardRouteAndRecreationPreserveDraftAndAttachmentReferences() {
        org.junit.Assume.assumeTrue(com.zero.v2.app.BuildConfig.DEBUG)
        automation.serviceInfo = automation.serviceInfo.apply { eventTypes = android.view.accessibility.AccessibilityEvent.TYPES_ALL_MASK }
        val cases = JSONArray()
        for (dark in listOf(false, true)) for (scale in listOf(1f, 2f)) {
            var activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, ChatDraftAcceptanceActivity::class.java)
                .putExtra("dark", dark).putExtra("fontScale", scale).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ChatDraftAcceptanceActivity
            try {
                waitFor { nodes().any { it.isEditable } }
                val draft = "A8a2 独立草稿 $dark/$scale"
                var editor = nodes().first { it.isEditable }
                assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, draft)
                }))
                waitFor { activity.imeVisible && activity.model.draft.text.value == draft }
                val imeInset = activity.imeInsetPx
                screenshot("draft-$dark-$scale-ime")
                click("添加图片、音频或视频（5MB以内）")
                assertEquals(MediaRoute.TOOLS, activity.model.draft.attachment.value!!.route)
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                waitFor { !activity.imeVisible }
                click("查看示例资料")
                waitFor { nodes().any { it.text?.toString() == "资料示例，草稿已保留" } }
                val monitor = instrumentation.addMonitor(ChatDraftAcceptanceActivity::class.java.name, null, false)
                instrumentation.runOnMainSync { activity.recreate() }
                activity = requireNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 10_000)) as ChatDraftAcceptanceActivity
                instrumentation.removeMonitor(monitor)
                waitFor { nodes().any { it.text?.toString() == "资料示例，草稿已保留" } }
                click("返回对话")
                waitFor { nodes().any { it.isEditable && it.text?.toString() == draft } }
                assertEquals(draft, activity.model.draft.text.value)
                assertEquals("content://fixture/image", activity.model.draft.attachment.value!!.uri)
                assertEquals(MediaRoute.TOOLS, activity.model.draft.attachment.value!!.route)
                assertEquals("isolated-conversation", activity.model.draft.conversationId)
                // New handle from saved primitive values; no Base64/media data travels through restoration.
                val values = activity.model.saved.keys().associateWith { activity.model.saved.get<Any?>(it) }
                val restored = com.zero.v2.app.ui.chat.ChatDraftState(androidx.lifecycle.SavedStateHandle(values))
                assertEquals(draft, restored.text.value)
                assertNull(restored.attachment.value!!.base64Data)
                assertEquals(MediaRoute.TOOLS, restored.attachment.value!!.route)
                screenshot("draft-$dark-$scale-restored")
                assertEquals(0, activity.sends)
                cases.put(JSONObject().put("dark", dark).put("fontScale", scale).put("ime", true)
                    .put("imeInsetPx", imeInset).put("activityRecreated", true).put("draftAndAttachmentPreserved", true))
            } finally { instrumentation.runOnMainSync { activity.finish() } }
        }
        File(instrumentation.targetContext.filesDir, "a8a2-draft/report.json").writeText(JSONObject().put("completed", true)
            .put("cases", cases).put("scope", "production_draft_and_composer_in_isolated_activity")
            .put("cloudRequests", 0).put("imeSendActionNotTested", true).put("actualForceStopOrProcessDeathNotTested", true).toString(2))
    }
}
