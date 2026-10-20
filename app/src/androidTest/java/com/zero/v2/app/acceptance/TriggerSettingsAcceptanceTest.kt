package com.zero.v2.app.acceptance

import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.settings.TriggerSettingsContent
import com.zero.v2.app.ui.settings.TriggerSettingsViewModel
import com.zero.v2.app.ui.theme.ZeroTheme
import com.zero.v2.core.data.entity.ReminderEntity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real renderer and Activity saved state; all write callbacks are isolated from personal data. */
class TriggerSettingsAcceptanceTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null || !node.refresh()) return
            result.add(node)
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(inst.uiAutomation.rootInActiveWindow)
        return result
    }
    private fun find(label: String, back: Boolean = false): AccessibilityNodeInfo {
        repeat(70) { attempt ->
            nodes().firstOrNull { it.isVisibleToUser &&
                (it.text?.toString()?.contains(label) == true || it.contentDescription?.toString() == label) }?.let { return it }
            if (attempt % 3 == 2) nodes().firstOrNull { it.isScrollable }?.performAction(
                if (back) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(80)
        }
        error("Missing trigger setting: $label")
    }
    private fun click(label: String) {
        var node = find(label)
        while (!node.isClickable) node = requireNotNull(node.parent)
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        Thread.sleep(200)
    }
    private fun shot(name: String) {
        val file = File(inst.targetContext.filesDir, "s6c2-trigger/$name.png")
        file.parentFile!!.mkdirs()
        val bitmap = requireNotNull(inst.uiAutomation.takeScreenshot())
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun configurationActionsAndSearchSurviveActualActivityRecreation() {
        val narrow = InstrumentationRegistry.getArguments().getString("requireSplit") == "true"
        val prefix = if (narrow) "split-" else ""
        val report = JSONArray()
        val apps = (1..18).map { TriggerSettingsViewModel.AppEntry("isolated.app.$it", "验收应用 $it", false, it == 1) }
        val scales = if (narrow) listOf(2f) else listOf(1f, 2f)
        val colors = if (narrow) listOf(false) else listOf(false, true)
        for (scale in scales) for (dark in colors) for (dynamic in colors) {
            VisualAcceptanceActivity.fontScaleForNextLaunch = scale
            var activity = inst.startActivitySync(Intent(inst.targetContext, VisualAcceptanceActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
            val enabled = mutableStateOf(true)
            val selected = mutableStateOf(setOf("isolated.app.2"))
            val calls = mutableListOf<String>()
            val reminders = listOf(ReminderEntity("isolated-missed", "验收提醒", 1, status = ReminderEntity.STATUS_MISSED))
            fun mount() { inst.runOnMainSync { activity.setContent { ZeroTheme(darkTheme = dark, dynamicColor = dynamic) {
                TriggerSettingsContent(selected.value, apps, reminders, enabled.value, { calls.add("back") },
                    { pkg -> calls.add("toggle:$pkg"); selected.value = selected.value + pkg },
                    { calls.add("delete:$it") }, { calls.add("requeue:$it") })
            } } } }
            try {
                mount()
                if (narrow) {
                    val deadline = android.os.SystemClock.uptimeMillis() + 180000
                    while (!activity.isInMultiWindowMode && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(250)
                    assertTrue("Real split window required", activity.isInMultiWindowMode)
                    SplitWindowAcceptance.ensure(activity)
                }
                find("已配置 · 1 个应用")
                assertFalse(nodes().any { it.text?.contains("运行中") == true })
                shot("$prefix$scale-$dark-$dynamic-top")
                click("重新安排"); click("删除提醒")
                assertEquals(listOf("requeue:isolated-missed", "delete:isolated-missed"), calls)
                find("搜索应用")
                val field = nodes().first { it.isEditable && it.isVisibleToUser }
                assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "验收应用 18") }))
                find("isolated.app.18")
                click("isolated.app.18")
                assertEquals(1, calls.count { it == "toggle:isolated.app.18" })
                val monitor = inst.addMonitor(VisualAcceptanceActivity::class.java.name, null, false)
                try {
                    inst.runOnMainSync { VisualAcceptanceActivity.fontScaleForNextLaunch = scale; activity.recreate() }
                    activity = requireNotNull(inst.waitForMonitorWithTimeout(monitor, 10000)) as VisualAcceptanceActivity
                } finally { inst.removeMonitor(monitor) }
                mount()
                find("搜索应用")
                assertEquals("验收应用 18", nodes().first { it.isEditable && it.isVisibleToUser }.text.toString())
                shot("$prefix$scale-$dark-$dynamic-restored")
                inst.runOnMainSync { enabled.value = false }
                find("未启用 · 通知感知已关闭", back = true)
                shot("$prefix$scale-$dark-$dynamic-disabled")
                report.put(JSONObject().put("scale", scale).put("dark", dark).put("dynamic", dynamic)
                    .put("widthDp", activity.resources.configuration.screenWidthDp)
                    .put("multiWindow", activity.isInMultiWindowMode).put("restored", true)
                    .put("singleToggle", true).put("reminderCallbacks", true))
            } finally { inst.runOnMainSync { activity.finish() } }
        }
        File(inst.targetContext.filesDir, "s6c2-trigger/${prefix}report.json").writeText(report.toString(2))
    }
}
