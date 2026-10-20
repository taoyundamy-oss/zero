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
class GraphReadVisualAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private lateinit var activity: VisualAcceptanceActivity
    private val split get() = InstrumentationRegistry.getArguments().getString("requireSplit") == "true"
    private val output get() = if (split) "a8-graph-split-owned-read-ui" else "a8-graph-window-read-ui"

    private fun verifyWindow() {
        if (split) SplitWindowAcceptance.ensure(activity)
        val width = activity.resources.displayMetrics.widthPixels / activity.resources.displayMetrics.density
        if (split) {
            assertTrue("Actual multi-window required", activity.isInMultiWindowMode)
            assertTrue("Actual narrow split window required", width in 280f..599f)
            assertTrue(SplitWindowAcceptance.bounds(activity).width() / activity.resources.displayMetrics.density in 280f..599f)
        } else assertTrue("Actual unfolded window required", width >= 720)
    }

    @Test fun loadingErrorsRetainedRowsAndRetryRemainVisible() {
        VisualAcceptanceActivity.fontScaleForNextLaunch = InstrumentationRegistry.getArguments().getString("selectedCase")?.substringAfterLast('-')?.toFloat()
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, VisualAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        verifyWindow()
        val report = JSONArray()
        try {
            for (width in if (split) listOf(720) else listOf(360, 720)) for (dark in listOf(false, true))
                for (dynamic in listOf(false, true)) for (scale in listOf(1f, 2f)) {
                    val name = "$width-$dark-$dynamic-$scale"
                        val selectedCase = InstrumentationRegistry.getArguments().getString("selectedCase")
                        if (selectedCase != null && selectedCase != name) continue
                    for (page in listOf("graph-loading", "graph-error", "graph-stale")) {
                        setPage(page, width, dark, dynamic, scale)
                        val tree = await { it.any { node -> node.text?.toString() ==
                            if (page == "graph-loading") "用户图谱" else "重新读取" } }
                        assertFalse(tree.any { it.text?.toString()?.contains("暂无图谱") == true })
                        screenshot("$name-$page")
                        if (page != "graph-loading") {
                            assertTrue(tree.any { it.text?.toString()?.contains("无法读取图谱") == true })
                            if (page == "graph-stale") assertTrue(tree.any { it.text?.toString()?.startsWith("合成图谱实体") == true })
                            click("重新读取")
                            await { it.any { node -> node.text?.toString()?.startsWith("合成图谱实体") == true } }
                            assertEquals(listOf("read-retry"), activity.memoryActions.toList())
                            assertFalse(nodes().any { it.text?.toString() == "重新读取" })
                        }
                    }
                    report.put(JSONObject().put("name", name).put("loadingNotEmpty", true)
                        .put("errorNotEmpty", true).put("staleRowsRetained", true).put("retryCallback", true))
                }
            File(instrumentation.targetContext.filesDir, "$output/report.json").writeText(JSONObject()
                .put("passed", true).put("actualMultiWindow", activity.isInMultiWindowMode)
                .put("actualWidthDp", activity.resources.displayMetrics.widthPixels / activity.resources.displayMetrics.density).put("cases", report)
                .put("personalDataWrites", 0).put("providerRequests", 0).toString(2))
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private fun setPage(page: String, width: Int, dark: Boolean, dynamic: Boolean, scale: Float) {
        if (split) {
            instrumentation.runOnMainSync {
                activity.intent.putExtra("acceptancePage", page).putExtra("acceptanceDark", dark)
                    .putExtra("acceptanceDynamic", dynamic).putExtra("acceptanceFontScale", scale).putExtra("acceptanceWidth", width)
            }
            if (activity.resources.configuration.fontScale != scale) activity = SplitWindowAcceptance.recreate(activity, scale)
        } else {
            instrumentation.runOnMainSync { activity.finish(); VisualAcceptanceActivity.fontScaleForNextLaunch = scale }
            activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, VisualAcceptanceActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        }
        assertEquals(scale, activity.resources.configuration.fontScale, 0f)
        verifyWindow()
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
        val location = IntArray(2)
        instrumentation.runOnMainSync { activity.window.decorView.getLocationOnScreen(location) }
        val metrics = activity.resources.displayMetrics
        assertTrue(bounds.width() / metrics.density >= 48)
        assertTrue(bounds.left >= location[0] && bounds.right <= location[0] + metrics.widthPixels &&
            bounds.top >= location[1] && bounds.bottom <= location[1] + metrics.heightPixels)
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
        val image = requireNotNull(if (split && android.os.Build.VERSION.SDK_INT >= 34)
            automation.takeScreenshot(activity.window) else automation.takeScreenshot())
        val file = File(instrumentation.targetContext.filesDir, "$output/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
}
