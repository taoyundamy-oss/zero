package com.zero.v2.app.acceptance

import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Error/loading/retry against production UI; isolated state callbacks, no real data mutation. */
class MemoryReadVisualAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private lateinit var activity: VisualAcceptanceActivity

    @Test fun loadingErrorsRetainedRowsAndRetryRemainVisible() {
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, VisualAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        val report = JSONArray()
        try {
            for (width in listOf(360, 720)) for (dark in listOf(false, true))
                for (dynamic in listOf(false, true)) for (scale in listOf(1f, 2f)) {
                    val name = "$width-$dark-$dynamic-$scale"
                    for (page in listOf("memory-loading", "memory-read-error", "memory-read-stale")) {
                        setPage(page, width, dark, dynamic, scale)
                        val tree = await { it.any { node -> node.text?.toString() ==
                            if (page == "memory-loading") "正在读取记忆" else "重新读取" } }
                        assertFalse(tree.any { it.text?.toString()?.contains("暂无记忆") == true })
                        screenshot("$name-$page")
                        if (page != "memory-loading") {
                            assertTrue(tree.any { it.text?.toString()?.contains("无法读取记忆") == true })
                            if (page == "memory-read-stale") assertTrue(tree.any { it.text?.toString()?.startsWith("长文记忆") == true })
                            click("重新读取")
                            await { it.any { node -> node.text?.toString()?.startsWith("长文记忆") == true } }
                            assertEquals(listOf("read-retry"), activity.memoryActions.toList())
                            assertFalse(nodes().any { it.text?.toString() == "重新读取" })
                        }
                    }
                    report.put(JSONObject().put("name", name).put("loadingNotEmpty", true)
                        .put("errorNotEmpty", true).put("staleRowsRetained", true).put("retryCallback", true))
                }
            setPage("memory-source", 360, true, false, 2f)
            await { it.any { node -> node.text?.toString() == "用户原文提取" } }
            assertTrue(nodes().any { it.text?.toString() == "来源未核验" })
            screenshot("source-labels")
            File(instrumentation.targetContext.filesDir, "a8-memory-read-ui/report.json").writeText(JSONObject()
                .put("passed", true).put("cases", report).put("sourceLabels", true)
                .put("personalDataWrites", 0).put("providerRequests", 0).toString(2))
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private fun setPage(page: String, width: Int, dark: Boolean, dynamic: Boolean, scale: Float) {
        instrumentation.runOnMainSync {
            activity.finish()
            VisualAcceptanceActivity.fontScaleForNextLaunch = scale
        }
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, VisualAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        assertEquals(scale, activity.resources.configuration.fontScale, 0f)
        instrumentation.runOnMainSync {
            activity.memoryActions.clear()
            activity.fixture = VisualAcceptanceActivity.Fixture(page, dark, dynamic, scale, width)
        }
        settle()
    }
    private fun contrast(a: Color, b: Color): Double {
        val first = a.luminance().toDouble(); val second = b.luminance().toDouble()
        return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
    }
    private fun owner(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        while (!current.isClickable) { current = requireNotNull(current.parent); current.refresh() }
        return current
    }
    private fun click(label: String, description: Boolean = false) {
        val tree = await { it.any { node -> if (description) node.contentDescription?.toString() == label else node.text?.toString() == label } }
        val target = owner(tree.first { if (description) it.contentDescription?.toString() == label else it.text?.toString() == label })
        val bounds = Rect().also { target.getBoundsInScreen(it) }
        assertTrue("$label height $bounds", bounds.height() / activity.resources.displayMetrics.density >= 48)
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK)); settle()
    }
    private fun reach(label: String) {
        repeat(24) {
            val tree = nodes()
            if (tree.any { it.isVisibleToUser && it.text?.toString() == label }) return
            val scroll = tree.firstOrNull { it.isScrollable && it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD } }
            assertNotNull("No scroll for $label", scroll)
            assertTrue(scroll!!.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)); settle()
        }
        fail("Hidden tail: $label")
    }
    private fun await(predicate: (List<AccessibilityNodeInfo>) -> Boolean): List<AccessibilityNodeInfo> {
        repeat(50) { val tree = nodes(); if (predicate(tree)) return tree; Thread.sleep(100) }
        screenshot("failure")
        throw AssertionError("Memory UI condition not reached")
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null || !node.refresh()) return
            out.add(node); for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(automation.rootInActiveWindow); return out
    }
    private fun settle() { instrumentation.waitForIdleSync(); automation.waitForIdle(200, 5_000); Thread.sleep(150) }
    private fun screenshot(name: String) {
        val image = requireNotNull(automation.takeScreenshot())
        val file = File(instrumentation.targetContext.filesDir, "a8-memory-read-ui/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
}
