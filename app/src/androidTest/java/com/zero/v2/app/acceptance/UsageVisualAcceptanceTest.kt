package com.zero.v2.app.acceptance

import android.content.Intent
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real chart composables, isolated data and unchanged device display preferences. */
class UsageVisualAcceptanceTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private fun nodes(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null || !node.refresh()) return
            out += node
            repeat(node.childCount) { visit(node.getChild(it)) }
        }
        visit(inst.uiAutomation.rootInActiveWindow)
        return out
    }
    private fun find(text: String): AccessibilityNodeInfo {
        repeat(40) {
            nodes().firstOrNull { it.isVisibleToUser && (it.text?.contains(text) == true ||
                it.contentDescription?.contains(text) == true) }?.let { return it }
            nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(150)
        }
        error("Missing usage chart: $text")
    }
    private fun click(node: AccessibilityNodeInfo) {
        var target = node
        while (!target.isClickable) target = requireNotNull(target.parent)
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        Thread.sleep(200)
    }
    private fun shot(activity: VisualAcceptanceActivity, name: String) {
        val bitmap = requireNotNull(inst.uiAutomation.takeScreenshot(activity.window))
        val file = File(inst.targetContext.filesDir, "c4-usage-ui/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun chartsRemainReadableAndSelectableWithUnknownAndLongHistory() {
        for (dark in listOf(false, true)) {
            val intent = Intent(inst.targetContext, VisualAcceptanceActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("acceptancePage", "usage")
                .putExtra("acceptanceDark", dark)
            val activity = inst.startActivitySync(intent) as VisualAcceptanceActivity
            try {
                find("缓存命中率 90.00%")
                shot(activity, "$dark-overview")
                click(find("00:00，输入"))
                find("未命中")
                shot(activity, "$dark-time")
                find("随请求与事件变化")
                click(find("请求4，命中率未知"))
                find("输入 1,000")
                shot(activity, "$dark-events")
            } finally { inst.runOnMainSync { activity.finish() } }
        }
    }
}
